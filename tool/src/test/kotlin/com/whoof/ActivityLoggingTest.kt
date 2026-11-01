package com.whoof

import com.whoof.privateapi.PrivateWhoopError
import com.whoof.privateapi.PrivateWhoopException
import com.whoof.privateapi.PrivateWhoopSport
import com.whoof.privateapi.PrivateWhoopSports
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private val NOW = Instant.parse("2026-11-01T12:00:00Z")
private const val RECEIPT = "5b1d3c2e-7f1a-4c55-9d0e-0a1b2c3d4e5f"
private val SPORT = PrivateWhoopSport(1, "Cycling")

class ActivityPureLogicTest {
    @Test
    fun elapsedFormatsHoursMinutesSeconds() {
        assertEquals("0:00:00", formatElapsed(0))
        assertEquals("0:00:59", formatElapsed(59_999))
        assertEquals("0:01:05", formatElapsed(65_000))
        assertEquals("1:00:00", formatElapsed(3_600_000))
        assertEquals("25:01:01", formatElapsed(90_061_000))
        assertEquals("0:00:00", formatElapsed(-5_000))
    }

    @Test
    fun adjustMovesByMinutesAndRollsOverMidnight() {
        val max = NOW.plusSeconds(86_400)
        val late = Instant.parse("2026-11-01T23:50:00Z")
        val rolled = adjustTime(late, 15, max)
        assertEquals(Instant.parse("2026-11-02T00:05:00Z"), rolled)
        assertEquals("Mon 2 Nov 00:05", rolled.dateTimeText(ZoneOffset.UTC))
        assertEquals("Sun 1 Nov 23:50", late.dateTimeText(ZoneOffset.UTC))
        assertEquals(Instant.parse("2026-10-31T23:59:00Z"), adjustTime(Instant.parse("2026-11-01T00:00:00Z"), -1, max))
    }

    @Test
    fun adjustNeverPassesTheMaximum() {
        assertEquals(NOW, adjustTime(NOW.minusSeconds(120), 15, NOW))
        assertEquals(NOW, adjustTime(NOW, 1, NOW))
    }

    private val catalog = listOf(
        PrivateWhoopSport(1, "Cycling"), PrivateWhoopSport(2, "Yoga"), PrivateWhoopSport(3, "Running"),
        PrivateWhoopSport(4, "Boxing"), PrivateWhoopSport(5, "Rowing"), PrivateWhoopSport(6, "Trail Running"),
        PrivateWhoopSport(7, "Swimming"),
    )

    @Test
    fun emptyQueryBuildsFavouritesInUserOrderRecentsWithoutFavouritesAndAlphabeticalAll() {
        val sections = buildSportSections("", favouriteIds = listOf(7, 1), recentIds = listOf(1, 3, 2, 4, 5, 6), all = catalog)
        assertEquals(listOf(7, 1), sections.favourites.map { it.id })
        assertEquals(listOf(3, 2, 4, 5, 6), sections.recent.map { it.id })
        assertEquals(
            listOf("Boxing", "Cycling", "Rowing", "Running", "Swimming", "Trail Running", "Yoga"),
            sections.all.map { it.name },
        )
    }

    @Test
    fun recentsAreLimitedToFiveAndUnknownFavouritesAreSkipped() {
        val sections = buildSportSections("", listOf(99), listOf(1, 2, 3, 4, 5, 6, 7), catalog)
        assertTrue(sections.favourites.isEmpty())
        assertEquals(MAX_RECENT_SPORTS, sections.recent.size)
    }

    @Test
    fun queryMatchesCaseInsensitivelyWithPrefixMatchesFirstAndNoSections() {
        val sports = listOf(
            PrivateWhoopSport(1, "Trail Running"), PrivateWhoopSport(2, "Running"),
            PrivateWhoopSport(3, "Yoga"), PrivateWhoopSport(4, "Runway Sprints"),
        )
        val sections = buildSportSections("  RUN ", favouriteIds = listOf(1), recentIds = listOf(2), all = sports)
        assertTrue(sections.favourites.isEmpty() && sections.recent.isEmpty())
        assertEquals(listOf("Running", "Runway Sprints", "Trail Running"), sections.all.map { it.name })
        assertTrue(buildSportSections("zzzz", emptyList(), emptyList(), sports).isEmpty)
    }

    @Test
    fun pickOnlyHidesFavouritesEverywhere() {
        val sections = buildSportSections("", listOf(1, 2), listOf(1, 3), catalog, pickOnly = true)
        assertTrue(sections.favourites.isEmpty())
        assertEquals(listOf(3), sections.recent.map { it.id })
        assertTrue(sections.all.none { it.id == 1 || it.id == 2 })
        assertTrue(buildSportSections("o", listOf(4), emptyList(), catalog, pickOnly = true).all.none { it.id == 4 })
    }

    @Test
    fun favouriteAddMoveRemoveRules() {
        assertEquals(listOf(1, 2), addFavourite(listOf(1), 2))
        assertEquals(listOf(1, 2), addFavourite(listOf(1, 2), 1))
        assertEquals(listOf(2, 1, 3), moveFavourite(listOf(1, 2, 3), 2, -1))
        assertEquals(listOf(1, 3, 2), moveFavourite(listOf(1, 2, 3), 2, 1))
        assertEquals(listOf(1, 2, 3), moveFavourite(listOf(1, 2, 3), 1, -1))
        assertEquals(listOf(1, 2, 3), moveFavourite(listOf(1, 2, 3), 3, 1))
        assertEquals(listOf(1, 2, 3), moveFavourite(listOf(1, 2, 3), 9, 1))
        assertEquals(listOf(1, 3), removeFavourite(listOf(1, 2, 3), 2))
        val full = (1..MAX_FAVOURITE_SPORTS).toList()
        assertEquals(full, addFavourite(full, 999))
    }

    @Test
    fun recentsAreMostRecentFirstBoundedAndDeduplicated() {
        var recents = emptyList<Int>()
        listOf(1, 2, 3, 4, 5, 6, 3).forEach { recents = pushRecent(recents, it) }
        assertEquals(listOf(3, 6, 5, 4, 2), recents)
        assertEquals(listOf(1, 0), parseRecents("1, x,0,999999,1"))
    }

    @Test
    fun rangeProblemsCoverBoundaries() {
        val end = NOW
        assertNull(rangeProblem(end.minusSeconds(60), end, NOW))
        assertEquals(RangeProblem.TooShort, rangeProblem(end.minusSeconds(59), end, NOW))
        assertEquals(RangeProblem.EndBeforeStart, rangeProblem(end, end, NOW))
        assertNull(rangeProblem(end.minusSeconds(86_400), end, NOW))
        assertEquals(RangeProblem.TooLong, rangeProblem(end.minusSeconds(86_401), end, NOW))
        assertEquals(RangeProblem.InFuture, rangeProblem(NOW.minusSeconds(600), NOW.plusSeconds(1), NOW))
    }

    @Test
    fun durationTextUsesMinutesThenHours() {
        assertEquals("45 MIN", durationText(NOW.minusSeconds(45 * 60), NOW))
        assertEquals("1H 05M", durationText(NOW.minusSeconds(65 * 60), NOW))
    }

    @Test
    fun errorsMapToShortMessages() {
        assertEquals(
            "This overlaps an activity already in WHOOP. Delete that one first, then submit again.",
            PrivateWhoopError.Overlap.activityMessage(),
        )
        assertEquals(UNCERTAIN_MESSAGE, PrivateWhoopError.SubmissionUncertain.activityMessage())
        assertEquals("Session expired. Sign out in Settings, then sign in again.", PrivateWhoopError.ReconnectRequired.activityMessage())
        assertEquals("Too many attempts. Wait and try again.", PrivateWhoopError.RateLimited.activityMessage())
        assertEquals("WHOOP could not be reached.", PrivateWhoopError.Unavailable.activityMessage(delete = true))
        assertEquals("Could not log the activity.", PrivateWhoopError.NotFound.activityMessage())
        assertEquals("Could not delete the activity.", PrivateWhoopError.NotFound.activityMessage(delete = true))
        assertEquals("Could not log the activity.", RuntimeException("boom").activityMessage())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelsTest {
    @Test
    fun doubleTappedSubmitSendsOnceAndSucceeds() = vmTest { dispatcher ->
        val gate = CompletableDeferred<Unit>()
        val backend = FakeBackend(submitGate = gate)
        val prefs = FakePrefs(timer = ActivityTimer(1, 1L))
        val vm = review(backend, prefs, dispatcher)
        vm.submit()
        assertTrue(vm.state.value.busy)
        vm.submit()
        vm.submit()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, backend.submits)
        assertTrue(vm.state.value.done)
        assertEquals(listOf(1), prefs.recents)
        assertNull(prefs.timer)
        vm.submit() // after success
        advanceUntilIdle()
        assertEquals(1, backend.submits)
    }

    @Test
    fun invalidRangeIsRejectedLocallyWithoutSending() = vmTest { dispatcher ->
        val backend = FakeBackend()
        val vm = review(backend, FakePrefs(), dispatcher, start = NOW.minusSeconds(30))
        vm.submit()
        advanceUntilIdle()
        assertEquals(0, backend.submits)
        assertEquals(RangeProblem.TooShort.message, vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun overlapKeepsTheTimerAndAllowsAnotherAttempt() = vmTest { dispatcher ->
        val backend = FakeBackend(failure = PrivateWhoopException(PrivateWhoopError.Overlap))
        val prefs = FakePrefs(timer = ActivityTimer(1, 1L))
        val vm = review(backend, prefs, dispatcher)
        vm.submit(); advanceUntilIdle()
        assertEquals(PrivateWhoopError.Overlap.activityMessage(), vm.state.value.error)
        assertNotNull(prefs.timer)
        assertFalse(vm.state.value.done || vm.state.value.uncertain)
        vm.submit(); advanceUntilIdle()
        assertEquals(2, backend.submits)
    }

    @Test
    fun uncertainBlocksResubmitUntilClearedAndNeverRetries() = vmTest { dispatcher ->
        val backend = FakeBackend(failure = PrivateWhoopException(PrivateWhoopError.SubmissionUncertain))
        val prefs = FakePrefs(timer = ActivityTimer(1, 1L))
        val vm = review(backend, prefs, dispatcher)
        vm.submit(); advanceUntilIdle()
        assertTrue(vm.state.value.uncertain)
        assertEquals(UNCERTAIN_MESSAGE, vm.state.value.error)
        assertNotNull(prefs.timer)
        vm.submit(); advanceUntilIdle()
        assertEquals(1, backend.submits)
        vm.clearUncertain(); advanceUntilIdle()
        assertEquals(1, backend.discards)
        assertFalse(vm.state.value.uncertain)
        assertEquals(1, backend.submits)
    }

    @Test
    fun editingTimesIsBlockedWhileUncertain() = vmTest { dispatcher ->
        val backend = FakeBackend(failure = PrivateWhoopException(PrivateWhoopError.SubmissionUncertain))
        val vm = review(backend, FakePrefs(), dispatcher)
        vm.submit(); advanceUntilIdle()
        val before = vm.state.value.start
        vm.setStart(before.minusSeconds(900))
        assertEquals(before, vm.state.value.start)
    }

    @Test
    fun deleteRemovesCacheOnlyAfterSuccessAndSendsOnce() = vmTest { dispatcher ->
        val gate = CompletableDeferred<Unit>()
        val backend = FakeBackend(deleteGate = gate)
        val removed = mutableListOf<String>()
        val vm = DeleteActivityViewModel("id-1", backend, { removed += it }, dispatcher)
        vm.delete(); vm.delete()
        assertTrue(removed.isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, backend.deletes)
        assertEquals(listOf("id-1"), removed)
        assertTrue(vm.state.value.done)
    }

    @Test
    fun failedDeleteKeepsCacheAndShowsMessageThenAllowsRetry() = vmTest { dispatcher ->
        val backend = FakeBackend(failure = PrivateWhoopException(PrivateWhoopError.Unavailable))
        val removed = mutableListOf<String>()
        val vm = DeleteActivityViewModel("id-1", backend, { removed += it }, dispatcher)
        vm.delete(); advanceUntilIdle()
        assertEquals("WHOOP could not be reached.", vm.state.value.error)
        assertTrue(removed.isEmpty())
        assertFalse(vm.state.value.busy)
        vm.delete(); advanceUntilIdle()
        assertEquals(2, backend.deletes)
    }

    @Test
    fun timerPersistsStartAndSurvivesANewViewModel() = vmTest { dispatcher ->
        val prefs = FakePrefs()
        var clock = 1_000_000L
        val first = TimerViewModel(1, prefs, dispatcher) { clock }
        first.reload(); advanceUntilIdle()
        assertFalse(first.state.value.running)
        first.start(); advanceUntilIdle()
        assertEquals(ActivityTimer(1, 1_000_000L), prefs.timer)
        clock += 125_000
        first.tick()
        assertEquals("0:02:05", formatElapsed(first.state.value.elapsedMillis))

        val reopened = TimerViewModel(null, prefs, dispatcher) { clock }
        reopened.reload(); advanceUntilIdle()
        assertTrue(reopened.state.value.running)
        assertEquals("Cycling", reopened.state.value.sport?.name)
        val (start, end) = reopened.stopRange()!!
        assertEquals(1_000_000L, start.toEpochMilli())
        assertEquals(clock, end.toEpochMilli())

        reopened.discard(); advanceUntilIdle()
        assertNull(prefs.timer)
        assertFalse(reopened.state.value.running)
    }

    @Test
    fun manualRangeIsTheLastThirtyMinutesEndingAtTheCurrentMinute() = vmTest { dispatcher ->
        val vm = TimerViewModel(1, FakePrefs(), dispatcher) { NOW.toEpochMilli() + 42_000 }
        val (start, end) = vm.manualRange()
        assertEquals(NOW, end)
        assertEquals(NOW.minusSeconds(1_800), start)
    }

    @Test
    fun startIsIgnoredWhileAlreadyRunning() = vmTest { dispatcher ->
        val prefs = FakePrefs(timer = ActivityTimer(1, 5_000L))
        val vm = TimerViewModel(1, prefs, dispatcher) { 9_000L }
        vm.reload(); advanceUntilIdle()
        vm.start(); advanceUntilIdle()
        assertEquals(5_000L, prefs.timer?.startMillis)
    }

    @Test
    fun pickerOpensOnTheListAndBackFromTheEditorKeepsTheQuery() = vmTest { dispatcher ->
        val prefs = FakePrefs(recents = mutableListOf(45), favourites = mutableListOf(1))
        val vm = SportPickerViewModel(prefs, ioDispatcher = dispatcher)
        advanceUntilIdle()
        assertFalse(vm.state.value.typing)
        assertEquals(listOf(45), vm.state.value.recentIds)
        assertEquals(listOf(1), vm.state.value.favouriteIds)
        assertFalse(vm.onBackPressed()) // on the list Back leaves
        vm.startTyping()
        vm.submitQuery("  run ")
        assertFalse(vm.state.value.typing)
        assertEquals("run", vm.state.value.query)
        vm.startTyping()
        assertTrue(vm.onBackPressed())
        assertFalse(vm.state.value.typing)
        assertEquals("run", vm.state.value.query)
        vm.clearQuery()
        assertEquals("", vm.state.value.query)
    }

    @Test
    fun favouritesPersistAddMoveRemoveInOrder() = vmTest { dispatcher ->
        val prefs = FakePrefs(favourites = mutableListOf(1, 2))
        val vm = FavouritesViewModel(prefs, dispatcher)
        advanceUntilIdle()
        assertEquals(listOf(1, 2), vm.state.value.ids)
        vm.add(3); vm.add(3)
        vm.move(3, -1)
        vm.move(1, 1)
        vm.remove(2)
        advanceUntilIdle()
        assertEquals(listOf(3, 1), vm.state.value.ids)
        assertEquals(listOf(3, 1), prefs.favourites)
        assertEquals(4, prefs.favouriteSaves)
    }

    @Test
    fun favouriteChangesBeforeLoadingAreIgnored() = vmTest { dispatcher ->
        val prefs = FakePrefs(favourites = mutableListOf(1))
        val vm = FavouritesViewModel(prefs, dispatcher)
        vm.add(2)
        advanceUntilIdle()
        assertEquals(listOf(1), prefs.favourites)
    }

    @Test
    fun successfulSubmitRecordsAPendingActivityWithTheReceiptId() = vmTest { dispatcher ->
        val backend = FakeBackend()
        val vm = review(backend, FakePrefs(), dispatcher)
        vm.submit(); advanceUntilIdle()
        val pending = recorded.single()
        assertEquals(RECEIPT, pending.activityId)
        assertEquals(SPORT.id, pending.sportId)
        assertEquals("Cycling", pending.sportName)
        assertEquals(NOW.minusSeconds(1_800).toEpochMilli(), pending.startMillis)
        assertEquals(NOW.toEpochMilli(), pending.endMillis)
        assertEquals("Z", pending.timezoneOffset)
        assertEquals(NOW.toEpochMilli(), pending.createdMillis)
    }

    @Test
    fun failedSubmitRecordsNothingAndPendingFailureDoesNotFailTheSubmit() = vmTest { dispatcher ->
        val failing = review(FakeBackend(failure = PrivateWhoopException(PrivateWhoopError.Overlap)), FakePrefs(), dispatcher)
        failing.submit(); advanceUntilIdle()
        assertTrue(recorded.isEmpty())
        val vm = ReviewViewModel(FakeBackend(), FakePrefs(), { error("disk") }, SPORT, NOW.minusSeconds(1_800), NOW, ZoneId.of("UTC"), dispatcher) { NOW }
        vm.submit(); advanceUntilIdle()
        assertTrue(vm.state.value.done)
    }

    private fun review(
        backend: FakeBackend,
        prefs: FakePrefs,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        start: Instant = NOW.minusSeconds(1_800),
    ) = ReviewViewModel(backend, prefs, { recorded += it }, SPORT, start, NOW, ZoneId.of("UTC"), dispatcher) { NOW }

    private val recorded = mutableListOf<PendingActivityEntity>()

    private fun vmTest(block: suspend TestScope.(kotlinx.coroutines.CoroutineDispatcher) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            block(dispatcher)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class FakeBackend(
    private val failure: PrivateWhoopException? = null,
    private val submitGate: CompletableDeferred<Unit>? = null,
    private val deleteGate: CompletableDeferred<Unit>? = null,
) : ActivityBackend {
    var submits = 0
    var discards = 0
    var deletes = 0

    override suspend fun signedIn() = true

    override suspend fun submit(sport: PrivateWhoopSport, start: Instant, end: Instant, zone: ZoneId): String {
        submits++
        submitGate?.await()
        failure?.let { throw it }
        return RECEIPT
    }

    override suspend fun discardDraft() {
        discards++
    }

    override suspend fun delete(activityId: String) {
        deletes++
        deleteGate?.await()
        failure?.let { throw it }
    }
}

private class FakePrefs(
    var timer: ActivityTimer? = null,
    val recents: MutableList<Int> = mutableListOf(),
    val favourites: MutableList<Int> = mutableListOf(),
) : ActivityPrefs {
    var favouriteSaves = 0

    override suspend fun favouriteSportIds(): List<Int> = favourites.toList()

    override suspend fun saveFavouriteSportIds(ids: List<Int>) {
        favouriteSaves++
        favourites.clear(); favourites.addAll(ids)
    }

    override suspend fun recentSportIds(): List<Int> = recents.toList()

    override suspend fun recordRecent(sportId: Int) {
        val next = pushRecent(recents, sportId)
        recents.clear(); recents.addAll(next)
    }

    override suspend fun timer(): ActivityTimer? = timer

    override suspend fun saveTimer(timer: ActivityTimer) {
        this.timer = timer
    }

    override suspend fun clearTimer() {
        timer = null
    }

    override suspend fun clear() {
        timer = null
        recents.clear()
        favourites.clear()
    }
}
