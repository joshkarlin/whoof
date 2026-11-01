package com.whoof

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val HISTORY_DAYS = 14L
private const val RETAIN_DAYS = 30L
private const val PAGE_LIMIT = 25
private const val MAX_PAGES = 4

/** Nonsensitive synchronization metadata. */
internal interface SyncMetadataStore {
    val lastSuccessfulSyncMillis: Flow<Long?>

    suspend fun recordSuccess(atMillis: Long)

    suspend fun clear()
}

internal class DataStoreSyncMetadata(
    private val dataStore: DataStore<Preferences>,
) : SyncMetadataStore {
    override val lastSuccessfulSyncMillis: Flow<Long?> = dataStore.data.map { it[LAST_SYNC_KEY] }

    override suspend fun recordSuccess(atMillis: Long) {
        dataStore.edit { it[LAST_SYNC_KEY] = atMillis }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(LAST_SYNC_KEY) }
    }

    private companion object {
        val LAST_SYNC_KEY = longPreferencesKey("last_successful_sync_millis")
    }
}

internal class WhoopSynchronizer(
    private val api: WhoopHistoryClient,
    private val tokenCoordinator: WhoopTokenCoordinator,
    private val cache: WhoopCache,
    private val metadata: SyncMetadataStore,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()

    /** Runs [block] while no synchronization is in progress, such as replacing or deleting credentials. */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** Fetches recent WHOOP records and replaces the cached window. Cache is untouched on failure. */
    suspend fun sync() = mutex.withLock {
        val startedAt = now()
        val cutoffMillis = startedAt.minus(Duration.ofDays(HISTORY_DAYS)).toEpochMilli()
        val batch = tokenCoordinator.execute { accessToken ->
            fetchBatch(accessToken, cutoffMillis, startedAt)
        }
        cache.apply(batch)
        metadata.recordSuccess(startedAt.toEpochMilli())
    }

    private suspend fun fetchBatch(
        accessToken: String,
        cutoffMillis: Long,
        startedAt: Instant,
    ): SyncBatch {
        val cycles = fetchWindow<CycleEntity>(cutoffMillis, { it.startMillis }) { token ->
            api.recentCycles(accessToken, PAGE_LIMIT, token).let { page ->
                page.records.mapNotNull(CycleRecord::toEntity) to page.nextToken
            }
        }
        val sleeps = fetchWindow<SleepEntity>(cutoffMillis, { it.startMillis }) { token ->
            api.recentSleeps(accessToken, PAGE_LIMIT, token).let { page ->
                page.records.mapNotNull(SleepRecord::toEntity) to page.nextToken
            }
        }
        val workouts = fetchWindow<WorkoutEntity>(cutoffMillis, { it.startMillis }) { token ->
            api.recentWorkouts(accessToken, PAGE_LIMIT, token).let { page ->
                page.records.mapNotNull(WorkoutRecord::toEntity) to page.nextToken
            }
        }
        val recoveries = api.recentRecoveries(accessToken, PAGE_LIMIT).records.map(RecoveryRecord::toEntity)
        return SyncBatch(
            cycles = cycles.records,
            recoveries = recoveries,
            sleeps = sleeps.records,
            workouts = workouts.records,
            cycleWindowStartMillis = cycles.windowStartMillis(cutoffMillis) { it.startMillis },
            sleepWindowStartMillis = sleeps.windowStartMillis(cutoffMillis) { it.startMillis },
            workoutWindowStartMillis = workouts.windowStartMillis(cutoffMillis) { it.startMillis },
            retainSinceMillis = startedAt.minus(Duration.ofDays(RETAIN_DAYS)).toEpochMilli(),
        )
    }
}

internal data class WindowFetch<T>(
    val records: List<T>,
    /** True when WHOOP has no further pages, so nothing older than the fetched records exists. */
    val exhausted: Boolean,
) {
    /**
     * The earliest start this fetch covers completely. An exhausted collection covers the whole
     * cutoff window even when it returned nothing; otherwise coverage begins at the oldest record.
     */
    fun windowStartMillis(cutoffMillis: Long, startOf: (T) -> Long): Long? {
        val oldest = records.minOfOrNull(startOf)
        return if (exhausted) minOf(cutoffMillis, oldest ?: cutoffMillis) else oldest
    }
}

/**
 * Pages through a start-descending WHOOP collection until it reaches [cutoffMillis], runs out of
 * pages, or hits the page bound. The records cover every start from the oldest one to now.
 */
internal suspend fun <T> fetchWindow(
    cutoffMillis: Long,
    startOf: (T) -> Long,
    maxPages: Int = MAX_PAGES,
    fetchPage: suspend (nextToken: String?) -> Pair<List<T>, String?>,
): WindowFetch<T> {
    val records = mutableListOf<T>()
    var nextToken: String? = null
    repeat(maxPages) {
        val (page, token) = fetchPage(nextToken)
        records += page
        if (token == null || page.isEmpty()) return WindowFetch(records, exhausted = true)
        if (page.any { startOf(it) < cutoffMillis }) return WindowFetch(records, exhausted = false)
        nextToken = token
    }
    return WindowFetch(records, exhausted = false)
}
