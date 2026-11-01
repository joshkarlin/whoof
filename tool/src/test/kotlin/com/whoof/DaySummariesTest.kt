package com.whoof

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DaySummariesTest {
    @Test
    fun groupsRecoverySleepAndWorkoutsIntoTheirCycle() {
        val snapshot = CacheSnapshot(
            cycles = listOf(
                cycle(1, "2026-10-30T23:00:00Z", "2026-10-31T22:30:00Z"),
                cycle(2, "2026-10-31T22:30:00Z", null),
            ),
            recoveries = listOf(recovery(2, 75.0)),
            sleeps = listOf(
                sleep("night", 2, "2026-10-31T22:30:00Z", "2026-11-01T06:45:00Z"),
                sleep("nap", 2, "2026-11-01T13:00:00Z", "2026-11-01T13:30:00Z", nap = true),
            ),
            workouts = listOf(
                workout("evening", "2026-11-01T18:05:00Z"),
                workout("morning", "2026-11-01T07:10:00Z"),
                workout("previous", "2026-10-31T09:00:00Z"),
            ),
        )

        val days = buildDaySummaries(snapshot)

        assertEquals(listOf(2L, 1L), days.map { it.cycleId })
        val today = days.first()
        assertEquals(LocalDate.of(2026, 11, 1), today.date)
        assertEquals(75.0, today.recovery?.score)
        assertEquals(listOf("morning", "evening"), today.activities.map { it.id })
        assertEquals(listOf("previous"), days[1].activities.map { it.id })
    }

    @Test
    fun usesWakeDateInTheSleepTimezone() {
        // 22:00 to 06:30 at +10:00; the UTC end date is still 31 October.
        val cycle = cycle(1, "2026-10-31T12:00:00Z", null)
        val sleep = sleep("s", 1, "2026-10-31T12:00:00Z", "2026-10-31T20:30:00Z", offset = "+10:00")

        assertEquals(LocalDate.of(2026, 11, 1), cycleDate(cycle, sleep))
    }

    @Test
    fun asleepTimeIsMissingUnlessEveryStageIsPresent() {
        val complete = sleep("a", 1, "2026-11-01T00:00:00Z", "2026-11-01T07:00:00Z", stages = Triple(3L, 2L, 1L))
        val partial = sleep("b", 2, "2026-11-01T00:00:00Z", "2026-11-01T07:00:00Z", stages = Triple(3L, null, 1L))
        val days = buildDaySummaries(
            CacheSnapshot(
                cycles = listOf(cycle(1, "2026-11-01T00:00:00Z", null), cycle(2, "2026-10-31T00:00:00Z", null)),
                sleeps = listOf(complete, partial),
            ),
        )

        assertEquals(6L, days.first { it.cycleId == 1L }.sleep?.asleepMilli)
        assertNull(days.first { it.cycleId == 2L }.sleep?.asleepMilli)
    }

    @Test
    fun updatedTimeIsTheLatestWhoopUpdateForTheDay() {
        val days = buildDaySummaries(
            CacheSnapshot(
                cycles = listOf(cycle(1, "2026-11-01T00:00:00Z", null).copy(updatedMillis = 10)),
                recoveries = listOf(recovery(1, 70.0).copy(updatedMillis = 30)),
                sleeps = listOf(sleep("s", 1, "2026-11-01T00:00:00Z", "2026-11-01T07:00:00Z").copy(updatedMillis = 20)),
            ),
        )

        assertEquals(30L, days.single().updatedMillis)
    }

    @Test
    fun lateCycleStartWithoutSleepBelongsToTheNextDay() {
        assertEquals(LocalDate.of(2026, 11, 1), cycleDate(cycle(1, "2026-10-31T22:30:00Z", null), null))
        assertEquals(LocalDate.of(2026, 11, 1), cycleDate(cycle(1, "2026-11-01T01:00:00Z", null), null))
    }

    @Test
    fun keepsUnscoredValuesAsExplicitStates() {
        val days = buildDaySummaries(
            CacheSnapshot(
                cycles = listOf(cycle(1, "2026-11-01T00:00:00Z", null, state = "PENDING_SCORE", strain = null)),
                recoveries = listOf(recovery(1, null, state = "UNSCORABLE")),
            ),
        )

        assertEquals(ScoreState.Pending, days.single().cycleState)
        assertNull(days.single().strain)
        assertEquals(ScoreState.Unscorable, days.single().recovery?.state)
        assertNull(days.single().recovery?.score)
    }

    @Test
    fun trendNeedsThreeEarlierValuesAndIgnoresSmallChanges() {
        assertNull(trend(60.0, listOf(50.0, null, 50.0)))
        assertEquals(Trend.Up, trend(60.0, listOf(50.0, 50.0, 50.0)))
        assertEquals(Trend.Down, trend(45.0, listOf(50.0, 50.0, 50.0)))
        assertEquals(Trend.Flat, trend(51.0, listOf(50.0, 50.0, 50.0)))
        assertNull(trend(null, listOf(50.0, 50.0, 50.0)))
    }

    @Test
    fun trendBaselineUsesOnlyTheSevenMostRecentValues() {
        val earlier = List(7) { 50.0 } + List(20) { 500.0 }

        assertEquals(Trend.Flat, trend(50.0, earlier))
    }

    @Test
    fun recoveryZonesUseWhoopThresholds() {
        assertEquals(RecoveryZone.Green, recoveryZone(67.0))
        assertEquals(RecoveryZone.Yellow, recoveryZone(66.9))
        assertEquals(RecoveryZone.Yellow, recoveryZone(34.0))
        assertEquals(RecoveryZone.Red, recoveryZone(33.9))
    }

    @Test
    fun zoneFractionsTreatMissingZonesAsZeroAndEmptyTotalsAsUnknown() {
        assertEquals(listOf(0.25f, 0f, 0.75f), zoneFractions(listOf(1_000L, null, 3_000L)))
        assertEquals(emptyList(), zoneFractions(listOf(null, 0L)))
    }

    @Test
    fun recentDaysFillsGapsOldestFirst() {
        val days = listOf(day(2, LocalDate.of(2026, 11, 1)), day(1, LocalDate.of(2026, 10, 30)))

        val recent = recentDays(days, LocalDate.of(2026, 11, 1), 3)

        assertEquals(
            listOf(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1)),
            recent.map { it.first },
        )
        assertEquals(listOf(1L, null, 2L), recent.map { it.second?.cycleId })
    }

    @Test
    fun formatsSportNamesAndDurations() {
        assertEquals("Functional fitness", "functional-fitness".displaySportName())
        assertEquals("7:42", (7 * 60 + 42).toLong().times(60_000).hoursMinutesText())
    }
}

private fun millis(value: String) = Instant.parse(value).toEpochMilli()

private fun cycle(
    id: Long,
    start: String,
    end: String?,
    state: String = "SCORED",
    strain: Double? = 10.0,
) = CycleEntity(
    id = id,
    startMillis = millis(start),
    endMillis = end?.let(::millis),
    timezoneOffset = "+00:00",
    scoreState = state,
    strain = strain,
    kilojoule = null,
    averageHeartRate = null,
    maxHeartRate = null,
    updatedMillis = 0,
)

private fun recovery(cycleId: Long, score: Double?, state: String = "SCORED") = RecoveryEntity(
    cycleId = cycleId,
    scoreState = state,
    recoveryScore = score,
    restingHeartRate = null,
    hrvRmssdMilli = null,
    spo2Percentage = null,
    skinTempCelsius = null,
    userCalibrating = null,
    updatedMillis = 0,
)

private fun sleep(
    id: String,
    cycleId: Long,
    start: String,
    end: String,
    nap: Boolean = false,
    offset: String = "+00:00",
    stages: Triple<Long?, Long?, Long?> = Triple(null, null, null),
) = SleepEntity(
    id = id,
    cycleId = cycleId,
    startMillis = millis(start),
    endMillis = millis(end),
    timezoneOffset = offset,
    nap = nap,
    scoreState = "SCORED",
    performancePercentage = 86.0,
    respiratoryRate = null,
    inBedMilli = null,
    awakeMilli = null,
    lightMilli = stages.first,
    deepMilli = stages.second,
    remMilli = stages.third,
    needMilli = null,
    updatedMillis = 0,
)

private fun workout(id: String, start: String) = WorkoutEntity(
    id = id,
    startMillis = millis(start),
    endMillis = millis(start) + 45 * 60_000,
    timezoneOffset = "+00:00",
    sportName = "running",
    scoreState = "SCORED",
    strain = 11.2,
    averageHeartRate = 151,
    maxHeartRate = 180,
    zoneZeroMilli = null,
    zoneOneMilli = null,
    zoneTwoMilli = null,
    zoneThreeMilli = null,
    zoneFourMilli = null,
    zoneFiveMilli = null,
    updatedMillis = 0,
)

private fun day(cycleId: Long, date: LocalDate) = DaySummary(
    cycleId = cycleId,
    date = date,
    cycleState = ScoreState.Scored,
    strain = null,
    recovery = null,
    sleep = null,
    activities = emptyList(),
    updatedMillis = 0,
)
