package com.whoof

import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class WhoopSyncTest {
    @Test
    fun fetchWindowFollowsTokensUntilItReachesTheCutoff() = runTest {
        val pages = mapOf(
            null to (listOf(30L, 25L) to "page-2"),
            "page-2" to (listOf(20L, 5L) to "page-3"),
            "page-3" to (listOf(1L) to null),
        )
        val requested = mutableListOf<String?>()

        val fetch = fetchWindow<Long>(cutoffMillis = 10L, startOf = { it }) { token ->
            requested += token
            pages.getValue(token)
        }

        assertEquals(listOf(30L, 25L, 20L, 5L), fetch.records)
        assertEquals(listOf(null, "page-2"), requested)
        assertEquals(false, fetch.exhausted)
        assertEquals(5L, fetch.windowStartMillis(10L) { it })
    }

    @Test
    fun exhaustedCollectionCoversTheWholeCutoffWindowEvenWhenEmpty() = runTest {
        val empty = fetchWindow<Long>(cutoffMillis = 10L, startOf = { it }) { emptyList<Long>() to null }
        val partial = fetchWindow<Long>(cutoffMillis = 10L, startOf = { it }) { listOf(40L, 30L) to null }

        assertEquals(true, empty.exhausted)
        assertEquals(10L, empty.windowStartMillis(10L) { it })
        assertEquals(10L, partial.windowStartMillis(10L) { it })
    }

    @Test
    fun fetchWindowStopsAtThePageBound() = runTest {
        var calls = 0

        val fetch = fetchWindow<Long>(cutoffMillis = 0L, startOf = { it }, maxPages = 2) {
            calls++
            listOf(100L) to "more"
        }

        assertEquals(2, calls)
        assertEquals(listOf(100L, 100L), fetch.records)
        assertEquals(100L, fetch.windowStartMillis(0L) { it })
    }

    @Test
    fun syncStoresMappedRecordsWithWindowsAndRecordsSuccess() = runTest {
        val cache = MemoryCache()
        val metadata = MemoryMetadata()
        val api = FakeHistoryClient()

        synchronizer(api, cache, metadata).sync()

        val batch = cache.applied.single()
        assertEquals(listOf(2L, 1L), batch.cycles.map { it.id })
        assertEquals(NOW.minusSeconds(14L * 86_400).toEpochMilli(), batch.cycleWindowStartMillis)
        assertEquals(listOf("run"), batch.workouts.map { it.id })
        assertEquals(listOf(2L), batch.recoveries.map { it.cycleId })
        assertEquals(NOW.minusSeconds(30L * 86_400).toEpochMilli(), batch.retainSinceMillis)
        assertEquals(NOW.toEpochMilli(), metadata.recorded)
    }

    @Test
    fun syncLeavesCacheAndMetadataUntouchedOnFailure() = runTest {
        val cache = MemoryCache()
        val metadata = MemoryMetadata()

        assertFailsWith<IOException> {
            synchronizer(FakeHistoryClient(workoutFailure = IOException("offline")), cache, metadata).sync()
        }

        assertEquals(emptyList(), cache.applied)
        assertNull(metadata.recorded)
    }

    @Test
    fun syncRefreshesOnceAndRetriesAfterUnauthorized() = runTest {
        val api = FakeHistoryClient(unauthorizedFor = "expired-access-token")
        val store = SyncMemoryCredentialStore()
        val cache = MemoryCache()

        WhoopSynchronizer(api, WhoopTokenCoordinator(store, api), cache, MemoryMetadata()) { NOW }.sync()

        assertEquals(1, api.refreshCount)
        assertEquals("rotated-access-token", store.credentials.accessToken)
        assertEquals(1, cache.applied.size)
    }

    @Test
    fun mappingDropsRecordsWithInvalidTimestampsOrOffsets() {
        assertNull(cycleRecord(1, "not-a-time").toEntity())
        assertNull(cycleRecord(1, "2026-11-01T00:00:00Z", offset = "nowhere").toEntity())
        assertEquals(1L, cycleRecord(1, "2026-11-01T00:00:00Z").toEntity()?.id)
    }

    @Test
    fun mappingSumsSleepNeedIncludingNegativeNapCredit() {
        val record = sleepRecord(
            score = SleepScore(
                sleepNeeded = SleepNeeded(
                    baselineMilli = 28_800_000,
                    needFromSleepDebtMilli = 1_800_000,
                    needFromRecentStrainMilli = 600_000,
                    needFromRecentNapMilli = -1_200_000,
                ),
            ),
        )

        assertEquals(30_000_000L, record.toEntity()?.needMilli)
        assertNull(sleepRecord(score = null).toEntity()?.needMilli)
    }

    @Test
    fun provisioningClearsThePreviousAccountsCacheAndSyncTime() = runTest {
        val cache = MemoryCache()
        val metadata = MemoryMetadata().apply { recorded = 1L }
        val store = SyncMemoryCredentialStore()
        val repository = DefaultWhoofRepository(store, cache, metadata, FakeHistoryClient())
        val replacement = store.credentials.copy(accessToken = "replacement-access-token")

        repository.provision(replacement)

        assertEquals(1, cache.clears)
        assertNull(metadata.recorded)
        assertEquals("replacement-access-token", store.credentials.accessToken)
    }

    @Test
    fun disconnectWaitsForAnInFlightSyncBeforeDeleting() = runTest {
        val release = CompletableDeferred<Unit>()
        val cache = MemoryCache(beforeApply = { release.await() })
        val store = SyncMemoryCredentialStore()
        val repository = DefaultWhoofRepository(store, cache, MemoryMetadata(), FakeHistoryClient())

        val sync = launch { repository.sync() }
        runCurrent()
        val disconnect = launch { repository.disconnect() }
        runCurrent()
        assertEquals(0, store.clears)

        release.complete(Unit)
        sync.join()
        disconnect.join()
        assertEquals(listOf("apply", "clear"), cache.events)
        assertEquals(1, store.clears)
    }

    private fun synchronizer(api: FakeHistoryClient, cache: MemoryCache, metadata: MemoryMetadata) =
        WhoopSynchronizer(api, WhoopTokenCoordinator(SyncMemoryCredentialStore(), api), cache, metadata) { NOW }
}

private val NOW: Instant = Instant.parse("2026-11-01T12:00:00Z")

private class FakeHistoryClient(
    private val workoutFailure: Exception? = null,
    private val unauthorizedFor: String? = null,
) : WhoopHistoryClient {
    var refreshCount = 0

    private fun check(accessToken: String) {
        if (accessToken == unauthorizedFor) throw WhoopApiException(WhoopApiError.Unauthorized)
    }

    override suspend fun recentRecoveries(accessToken: String, limit: Int, nextToken: String?): RecoveryCollection {
        check(accessToken)
        return RecoveryCollection(
            records = listOf(
                RecoveryRecord(2, "night", 9, "2026-11-01T07:00:00Z", "2026-11-01T07:00:00Z", "SCORED"),
            ),
        )
    }

    override suspend fun recentCycles(accessToken: String, limit: Int, nextToken: String?): CycleCollection {
        check(accessToken)
        return CycleCollection(
            records = listOf(
                cycleRecord(2, "2026-11-01T00:00:00Z"),
                cycleRecord(1, "2026-10-31T23:00:00Z"),
                cycleRecord(3, "invalid"),
            ),
        )
    }

    override suspend fun recentSleeps(accessToken: String, limit: Int, nextToken: String?): SleepCollection {
        check(accessToken)
        return SleepCollection(records = listOf(sleepRecord(score = null)))
    }

    override suspend fun recentWorkouts(accessToken: String, limit: Int, nextToken: String?): WorkoutCollection {
        check(accessToken)
        workoutFailure?.let { throw it }
        return WorkoutCollection(
            records = listOf(
                WorkoutRecord(
                    id = "run",
                    userId = 9,
                    createdAt = "2026-11-01T08:00:00Z",
                    updatedAt = "2026-11-01T08:00:00Z",
                    start = "2026-11-01T07:10:00Z",
                    end = "2026-11-01T07:52:00Z",
                    timezoneOffset = "+00:00",
                    sportName = "running",
                    scoreState = "SCORED",
                ),
            ),
        )
    }

    override suspend fun refresh(credentials: WhoopCredentials): WhoopTokenRefreshResponse {
        refreshCount++
        return WhoopTokenRefreshResponse("rotated-access-token", "rotated-refresh-token", 3_600)
    }
}

private fun cycleRecord(id: Long, start: String, offset: String = "+00:00") = CycleRecord(
    id = id,
    userId = 9,
    createdAt = "2026-11-01T00:00:00Z",
    updatedAt = "2026-11-01T00:00:00Z",
    start = start,
    timezoneOffset = offset,
    scoreState = "SCORED",
    score = CycleScore(strain = 10.0),
)

private fun sleepRecord(score: SleepScore?) = SleepRecord(
    id = "night",
    cycleId = 2,
    userId = 9,
    createdAt = "2026-11-01T07:00:00Z",
    updatedAt = "2026-11-01T07:00:00Z",
    start = "2026-11-01T00:00:00Z",
    end = "2026-11-01T06:45:00Z",
    timezoneOffset = "+00:00",
    nap = false,
    scoreState = if (score == null) "PENDING_SCORE" else "SCORED",
    score = score,
)

private class MemoryCache(private val beforeApply: (suspend () -> Unit)? = null) : WhoopCache {
    val applied = mutableListOf<SyncBatch>()
    val events = mutableListOf<String>()
    var clears = 0

    override fun observe(): Flow<CacheSnapshot> = MutableStateFlow(CacheSnapshot())

    override suspend fun apply(batch: SyncBatch) {
        beforeApply?.invoke()
        applied += batch
        events += "apply"
    }

    override suspend fun clear() {
        clears++
        events += "clear"
    }
}

private class MemoryMetadata : SyncMetadataStore {
    var recorded: Long? = null

    override val lastSuccessfulSyncMillis: Flow<Long?> = MutableStateFlow(null)

    override suspend fun recordSuccess(atMillis: Long) {
        recorded = atMillis
    }

    override suspend fun clear() {
        recorded = null
    }
}

private class SyncMemoryCredentialStore : WhoopCredentialStore {
    var credentials = WhoopCredentials(
        accessToken = "expired-access-token",
        clientId = "client-id-placeholder",
        clientSecret = "client-secret-placeholder",
        refreshToken = "refresh-token-placeholder-12345",
        expiresIn = 3_600,
    )

    override suspend fun load(): WhoopCredentials = credentials

    var clears = 0

    override suspend fun save(credentials: WhoopCredentials) {
        this.credentials = credentials
    }

    override suspend fun clear() {
        clears++
    }
}
