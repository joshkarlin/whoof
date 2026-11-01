package com.whoof

import com.whoof.privateapi.PrivateWhoopError
import com.whoof.privateapi.PrivateWhoopException
import com.whoof.privateapi.WhoopAccountSession
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    fun syncForcesOneRefreshAndRetriesTheRejectedGetOnce() = runTest {
        val api = FakeHistoryClient(unauthorizedFor = setOf("expired-access-token"))
        val session = FakeAccountSession()
        val cache = MemoryCache()

        WhoopSynchronizer(api, session, cache, MemoryMetadata()) { NOW }.sync()

        assertEquals(1, session.forcedRefreshes)
        assertEquals(listOf("expired-access-token"), session.rejectedTokens)
        assertEquals(1, api.rejected)
        assertEquals("rotated-access-token", api.tokensSeen.last())
        assertEquals(1, cache.applied.size)
    }

    @Test
    fun syncGivesUpAfterTheRetryIsRejectedToo() = runTest {
        val api = FakeHistoryClient(unauthorizedFor = setOf("expired-access-token", "rotated-access-token"))
        val session = FakeAccountSession()
        val cache = MemoryCache()
        val metadata = MemoryMetadata()

        val failure = assertFailsWith<PrivateWhoopException> {
            WhoopSynchronizer(api, session, cache, metadata) { NOW }.sync()
        }

        assertEquals(PrivateWhoopError.ReconnectRequired, failure.reason)
        assertEquals(1, session.forcedRefreshes)
        assertEquals(2, api.tokensSeen.size)
        assertEquals(emptyList(), cache.applied)
        assertNull(metadata.recorded)
    }

    @Test
    fun syncRefreshesAtMostOncePerRunEvenIfALaterGetIsRejected() = runTest {
        val api = FakeHistoryClient(unauthorizedFor = setOf("expired-access-token"), rejectRotatedFrom = 2)
        val session = FakeAccountSession()

        val failure = assertFailsWith<PrivateWhoopException> {
            WhoopSynchronizer(api, session, MemoryCache(), MemoryMetadata()) { NOW }.sync()
        }

        assertEquals(PrivateWhoopError.ReconnectRequired, failure.reason)
        assertEquals(1, session.forcedRefreshes)
    }

    @Test
    fun syncDoesNotRefreshForNonAuthorizationFailures() = runTest {
        val api = FakeHistoryClient(workoutFailure = WhoopApiException(WhoopApiError.RateLimited))
        val session = FakeAccountSession()

        val failure = assertFailsWith<WhoopApiException> {
            WhoopSynchronizer(api, session, MemoryCache(), MemoryMetadata()) { NOW }.sync()
        }

        assertEquals(WhoopApiError.RateLimited, failure.reason)
        assertEquals(0, session.forcedRefreshes)
    }

    @Test
    fun syncWhileSignedOutRequiresReconnectAndCallsNothing() = runTest {
        val api = FakeHistoryClient()
        val session = FakeAccountSession(signedIn = false)
        val cache = MemoryCache()

        val failure = assertFailsWith<PrivateWhoopException> {
            WhoopSynchronizer(api, session, cache, MemoryMetadata()) { NOW }.sync()
        }

        assertEquals(PrivateWhoopError.ReconnectRequired, failure.reason)
        assertEquals(emptyList(), api.tokensSeen)
        assertEquals(emptyList(), cache.applied)
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
    fun signOutClearsSessionCacheMetadataTimerAndRecentsButKeepsTheMarker() = runTest {
        val cache = MemoryCache()
        val metadata = MemoryMetadata().apply { recorded = 1L }
        val session = FakeAccountSession()
        val prefs = MemoryActivityPrefs(ActivityTimer(0, 1L), mutableListOf(0, 1))

        DefaultWhoofRepository(session, cache, metadata, prefs, MemoryPending(), FakeHistoryClient()).signOut()

        assertEquals(1, session.disconnects)
        assertEquals(0, session.deletes)
        assertEquals(1, cache.clears)
        assertNull(metadata.recorded)
        assertNull(prefs.timer)
        assertEquals(emptyList(), prefs.recents)
    }

    @Test
    fun deleteLocalDataClearsEverythingIncludingTheStoredMarker() = runTest {
        val cache = MemoryCache()
        val metadata = MemoryMetadata().apply { recorded = 1L }
        val session = FakeAccountSession()
        val prefs = MemoryActivityPrefs(ActivityTimer(0, 1L), mutableListOf(3))

        DefaultWhoofRepository(session, cache, metadata, prefs, MemoryPending(), FakeHistoryClient()).deleteLocalData()

        assertEquals(0, session.disconnects)
        assertEquals(listOf(true), session.deleteConfirmations)
        assertEquals(1, cache.clears)
        assertNull(metadata.recorded)
        assertNull(prefs.timer)
        assertEquals(emptyList(), prefs.recents)
    }

    @Test
    fun signOutAndDeleteLocalDataClearPendingActivitiesAndFavourites() = runTest {
        listOf(true, false).forEach { signOut ->
            val pending = MemoryPending(listOf(pendingRow("a", NOW.toEpochMilli())))
            val prefs = MemoryActivityPrefs().apply { favourites = listOf(1, 2) }
            val repository = DefaultWhoofRepository(FakeAccountSession(), MemoryCache(), MemoryMetadata(), prefs, pending, FakeHistoryClient())
            if (signOut) repository.signOut() else repository.deleteLocalData()
            assertEquals(1, pending.clears)
            assertEquals(emptyList(), pending.items.value)
            assertEquals(emptyList(), prefs.favourites)
        }
    }

    @Test
    fun acceptingANewAccountClearsCachePendingMetadataAndTimerButKeepsFavouritesAndRecents() = runTest {
        val cache = MemoryCache()
        val metadata = MemoryMetadata().apply { recorded = 1L }
        val session = FakeAccountSession()
        val pending = MemoryPending(listOf(pendingRow("a", NOW.toEpochMilli())))
        val prefs = MemoryActivityPrefs(ActivityTimer(0, 1L), mutableListOf(3, 4)).apply { favourites = listOf(1, 2) }

        DefaultWhoofRepository(session, cache, metadata, prefs, pending, FakeHistoryClient()).clearForNewAccount()

        assertEquals(1, cache.clears)
        assertNull(metadata.recorded)
        assertEquals(emptyList(), pending.items.value)
        assertNull(prefs.timer)
        assertEquals(listOf(3, 4), prefs.recents)
        assertEquals(listOf(1, 2), prefs.favourites)
        assertEquals(0, session.disconnects)
        assertEquals(0, session.deletes)
    }

    @Test
    fun syncPrunesPendingActivitiesWhoopListsOrThatExpired() = runTest {
        val now = System.currentTimeMillis()
        val cache = MemoryCache()
        cache.snapshot.value = CacheSnapshot(workouts = listOf(workoutRow("LISTED")))
        val pending = MemoryPending(
            listOf(
                pendingRow("listed", now),
                pendingRow("fresh", now - 60_000),
                pendingRow("old", now - 49 * 3_600_000L),
            ),
        )
        val repository = DefaultWhoofRepository(FakeAccountSession(), cache, MemoryMetadata(), MemoryActivityPrefs(), pending, FakeHistoryClient())
        repository.sync()
        assertEquals(listOf("fresh"), pending.items.value.map { it.activityId })
    }

    @Test
    fun failedSyncKeepsPendingActivities() = runTest {
        val pending = MemoryPending(listOf(pendingRow("old", 0L)))
        val session = FakeAccountSession(signedIn = false)
        val repository = DefaultWhoofRepository(session, MemoryCache(), MemoryMetadata(), MemoryActivityPrefs(), pending, FakeHistoryClient())
        runCatching { repository.sync() }
        assertEquals(1, pending.items.value.size)
    }

    @Test
    fun removingACachedWorkoutAlsoRemovesItsPendingCopy() = runTest {
        val pending = MemoryPending(listOf(pendingRow("a", NOW.toEpochMilli()), pendingRow("b", NOW.toEpochMilli())))
        val cache = MemoryCache()
        val repository = DefaultWhoofRepository(FakeAccountSession(), cache, MemoryMetadata(), MemoryActivityPrefs(), pending, FakeHistoryClient())
        repository.removeCachedWorkout("a")
        assertEquals(listOf("b"), pending.items.value.map { it.activityId })
        assertEquals(listOf("delete:a"), cache.events)
    }

    @Test
    fun repositoryDaysShowPendingActivitiesBeforeWhoopListsThem() = runTest {
        val cache = MemoryCache()
        cache.snapshot.value = CacheSnapshot(cycles = listOf(cycleRow()))
        val pending = MemoryPending(listOf(pendingRow("a", System.currentTimeMillis())))
        val repository = DefaultWhoofRepository(FakeAccountSession(), cache, MemoryMetadata(), MemoryActivityPrefs(), pending, FakeHistoryClient())
        val day = repository.days.first().single()
        assertEquals(listOf("a"), day.activities.map { it.id })
        assertTrue(day.activities.single().awaitingWhoop)
    }

    @Test
    fun repositoryReportsSignedInFromTheSession() = runTest {
        val session = FakeAccountSession(signedIn = false)
        val repository = DefaultWhoofRepository(session, MemoryCache(), MemoryMetadata(), MemoryActivityPrefs(), MemoryPending(), FakeHistoryClient())

        assertEquals(false, repository.isSignedIn())
        session.signedIn = true
        assertTrue(repository.isSignedIn())
    }

    @Test
    fun signOutWaitsForAnInFlightSyncBeforeDeleting() = runTest {
        val release = CompletableDeferred<Unit>()
        val cache = MemoryCache(beforeApply = { release.await() })
        val session = FakeAccountSession()
        val repository = DefaultWhoofRepository(session, cache, MemoryMetadata(), MemoryActivityPrefs(), MemoryPending(), FakeHistoryClient())

        val sync = launch { repository.sync() }
        runCurrent()
        val signOut = launch { repository.signOut() }
        runCurrent()
        assertEquals(0, session.disconnects)

        release.complete(Unit)
        sync.join()
        signOut.join()
        assertEquals(listOf("apply", "clear"), cache.events)
        assertEquals(1, session.disconnects)
    }

    private fun synchronizer(api: FakeHistoryClient, cache: MemoryCache, metadata: MemoryMetadata) =
        WhoopSynchronizer(api, FakeAccountSession(), cache, metadata) { NOW }
}

private val NOW: Instant = Instant.parse("2026-11-01T12:00:00Z")

private class FakeHistoryClient(
    private val workoutFailure: Exception? = null,
    private val unauthorizedFor: Set<String> = emptySet(),
    /** Rejects "rotated-access-token" from this call number on (1-based), to model a late 401. */
    private val rejectRotatedFrom: Int? = null,
) : WhoopHistoryClient {
    val tokensSeen = mutableListOf<String>()
    var rejected = 0

    private fun check(accessToken: String) {
        tokensSeen += accessToken
        val late = rejectRotatedFrom != null && accessToken == "rotated-access-token" &&
            tokensSeen.size >= rejectRotatedFrom + 1
        if (accessToken in unauthorizedFor || late) {
            rejected++
            throw WhoopApiException(WhoopApiError.Unauthorized)
        }
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

    val snapshot = MutableStateFlow(CacheSnapshot())

    override fun observe(): Flow<CacheSnapshot> = snapshot

    override suspend fun apply(batch: SyncBatch) {
        beforeApply?.invoke()
        applied += batch
        events += "apply"
    }

    override suspend fun deleteWorkout(id: String) {
        events += "delete:$id"
    }

    override suspend fun clear() {
        clears++
        events += "clear"
    }
}

private class MemoryPending(initial: List<PendingActivityEntity> = emptyList()) : PendingActivityStore {
    val items = MutableStateFlow(initial)
    var clears = 0

    override fun observe(): Flow<List<PendingActivityEntity>> = items
    override suspend fun all(): List<PendingActivityEntity> = items.value
    override suspend fun add(activity: PendingActivityEntity) { items.value = items.value.filter { it.activityId != activity.activityId } + activity }
    override suspend fun remove(ids: Collection<String>) { items.value = items.value.filter { it.activityId !in ids } }
    override suspend fun clear() { clears++; items.value = emptyList() }
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

private class FakeAccountSession(var signedIn: Boolean = true) : WhoopAccountSession {
    private var current = "expired-access-token"
    var forcedRefreshes = 0
    val rejectedTokens = mutableListOf<String>()
    var disconnects = 0
    var deletes = 0
    val deleteConfirmations = mutableListOf<Boolean>()

    override suspend fun accessToken(forceRefresh: Boolean, rejectedToken: String?): String {
        if (!signedIn) throw PrivateWhoopException(PrivateWhoopError.ReconnectRequired)
        if (forceRefresh && (rejectedToken == null || rejectedToken == current)) {
            forcedRefreshes++
            rejectedTokens += listOfNotNull(rejectedToken)
            current = "rotated-access-token"
        }
        return current
    }

    override suspend fun isSignedIn(): Boolean = signedIn

    override suspend fun disconnect() {
        disconnects++
        signedIn = false
    }

    override suspend fun deleteLocalData(confirmed: Boolean) {
        deletes++
        deleteConfirmations += confirmed
        signedIn = false
    }
}

private class MemoryActivityPrefs(
    var timer: ActivityTimer? = null,
    val recents: MutableList<Int> = mutableListOf(),
) : ActivityPrefs {
    override suspend fun recentSportIds(): List<Int> = recents.toList()
    override suspend fun recordRecent(sportId: Int) { recents.add(0, sportId) }
    var favourites: List<Int> = emptyList()
    override suspend fun favouriteSportIds(): List<Int> = favourites
    override suspend fun saveFavouriteSportIds(ids: List<Int>) { favourites = ids }
    override suspend fun timer(): ActivityTimer? = timer
    override suspend fun saveTimer(timer: ActivityTimer) { this.timer = timer }
    override suspend fun clearTimer() { timer = null }
    override suspend fun clear() { timer = null; recents.clear(); favourites = emptyList() }
}

private fun pendingRow(id: String, created: Long) = PendingActivityEntity(
    activityId = id,
    sportId = 1,
    sportName = "Cycling",
    startMillis = NOW.toEpochMilli() - 1_800_000,
    endMillis = NOW.toEpochMilli(),
    timezoneOffset = "Z",
    createdMillis = created,
)

private fun cycleRow() = CycleEntity(
    id = 1, startMillis = NOW.toEpochMilli() - 20 * 3_600_000L, endMillis = null, timezoneOffset = "+00:00",
    scoreState = "SCORED", strain = 5.0, kilojoule = null, averageHeartRate = null, maxHeartRate = null, updatedMillis = 0,
)

private fun workoutRow(id: String) = WorkoutEntity(
    id = id, startMillis = NOW.toEpochMilli() - 1_800_000, endMillis = NOW.toEpochMilli(), timezoneOffset = "+00:00",
    sportName = "cycling", scoreState = "SCORED", strain = 5.0, averageHeartRate = null, maxHeartRate = null,
    zoneZeroMilli = null, zoneOneMilli = null, zoneTwoMilli = null, zoneThreeMilli = null, zoneFourMilli = null,
    zoneFiveMilli = null, updatedMillis = 0,
)
