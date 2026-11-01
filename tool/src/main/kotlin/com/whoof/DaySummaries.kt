package com.whoof

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.abs

private const val TREND_BASELINE_DAYS = 7
private const val TREND_MIN_BASELINE = 3
private const val TREND_THRESHOLD = 0.03
private const val RECOVERY_GREEN_MIN = 67.0
private const val RECOVERY_YELLOW_MIN = 34.0
private val CYCLE_DATE_SHIFT: Duration = Duration.ofHours(12)

internal enum class ScoreState {
    Scored,
    Pending,
    Unscorable,
}

internal fun scoreStateOf(value: String): ScoreState = when (value) {
    "SCORED" -> ScoreState.Scored
    "UNSCORABLE" -> ScoreState.Unscorable
    else -> ScoreState.Pending
}

internal enum class RecoveryZone {
    Green,
    Yellow,
    Red,
}

internal fun recoveryZone(score: Double): RecoveryZone = when {
    score >= RECOVERY_GREEN_MIN -> RecoveryZone.Green
    score >= RECOVERY_YELLOW_MIN -> RecoveryZone.Yellow
    else -> RecoveryZone.Red
}

internal enum class Trend {
    Up,
    Down,
    Flat,
}

internal data class RecoverySummary(
    val state: ScoreState,
    val score: Double?,
    val hrvMilli: Double?,
    val restingHeartRate: Double?,
    val spo2Percentage: Double?,
    val skinTempCelsius: Double?,
)

internal data class SleepSummary(
    val state: ScoreState,
    val performancePercentage: Double?,
    val asleepMilli: Long?,
    val awakeMilli: Long?,
    val lightMilli: Long?,
    val deepMilli: Long?,
    val remMilli: Long?,
    val needMilli: Long?,
    val respiratoryRate: Double?,
)

internal data class ActivitySummary(
    val id: String,
    val sportName: String,
    val start: Instant,
    val end: Instant,
    val offset: ZoneOffset,
    val state: ScoreState,
    val strain: Double?,
    val averageHeartRate: Int?,
    /** Zone 0 through 5 as fractions of recorded zone time, or empty when WHOOP reports none. */
    val zoneFractions: List<Float>,
)

internal data class DaySummary(
    val cycleId: Long,
    val date: LocalDate,
    val cycleState: ScoreState,
    val strain: Double?,
    val recovery: RecoverySummary?,
    val sleep: SleepSummary?,
    val activities: List<ActivitySummary>,
    /** Latest WHOOP update time across the cycle, recovery, and sleep records, distinct from Whoof's sync time. */
    val updatedMillis: Long,
)

/** Groups cached records into WHOOP cycles, newest first. */
internal fun buildDaySummaries(snapshot: CacheSnapshot): List<DaySummary> {
    val recoveries = snapshot.recoveries.associateBy { it.cycleId }
    val sleeps = snapshot.sleeps
        .filter { !it.nap }
        .groupBy { it.cycleId }
        .mapValues { (_, records) -> records.maxBy { it.endMillis } }
    return snapshot.cycles
        .sortedByDescending { it.startMillis }
        .map { cycle ->
            val sleep = sleeps[cycle.id]
            val recovery = recoveries[cycle.id]
            DaySummary(
                cycleId = cycle.id,
                date = cycleDate(cycle, sleep),
                cycleState = scoreStateOf(cycle.scoreState),
                strain = cycle.strain,
                recovery = recovery?.toSummary(),
                sleep = sleep?.toSummary(),
                activities = snapshot.workouts
                    .filter { it.startMillis >= cycle.startMillis && (cycle.endMillis == null || it.startMillis < cycle.endMillis) }
                    .sortedBy { it.startMillis }
                    .map { it.toSummary() },
                updatedMillis = listOfNotNull(cycle.updatedMillis, recovery?.updatedMillis, sleep?.updatedMillis).max(),
            )
        }
}

/**
 * A WHOOP cycle begins at sleep onset, so its calendar day is the wake date. Without a sleep, the
 * start is shifted forward so a late-evening start lands on the following day.
 */
internal fun cycleDate(cycle: CycleEntity, sleep: SleepEntity?): LocalDate {
    if (sleep != null) {
        return Instant.ofEpochMilli(sleep.endMillis).atOffset(ZoneOffset.of(sleep.timezoneOffset)).toLocalDate()
    }
    return Instant.ofEpochMilli(cycle.startMillis)
        .plus(CYCLE_DATE_SHIFT)
        .atOffset(ZoneOffset.of(cycle.timezoneOffset))
        .toLocalDate()
}

/**
 * Compares [current] with the mean of up to seven earlier values. Returns null until at least three
 * earlier values exist; changes within three percent are flat.
 */
internal fun trend(current: Double?, earlier: List<Double?>): Trend? {
    if (current == null) return null
    val baseline = earlier.filterNotNull().take(TREND_BASELINE_DAYS)
    if (baseline.size < TREND_MIN_BASELINE) return null
    val mean = baseline.average()
    if (mean == 0.0) return null
    val change = (current - mean) / mean
    return when {
        abs(change) < TREND_THRESHOLD -> Trend.Flat
        change > 0 -> Trend.Up
        else -> Trend.Down
    }
}

/** Returns [count] consecutive dates ending at [latest], oldest first, matched to cached days. */
internal fun recentDays(days: List<DaySummary>, latest: LocalDate, count: Int): List<Pair<LocalDate, DaySummary?>> {
    val byDate = days.groupBy { it.date }.mapValues { (_, matches) -> matches.first() }
    return (count - 1 downTo 0).map { back ->
        val date = latest.minusDays(back.toLong())
        date to byDate[date]
    }
}

internal fun zoneFractions(zones: List<Long?>): List<Float> {
    val values = zones.map { it ?: 0L }
    val total = values.sum()
    if (total <= 0L) return emptyList()
    return values.map { it.toFloat() / total }
}

internal fun localTime(instant: Instant, offset: ZoneOffset): LocalTime = instant.atOffset(offset).toLocalTime()

private fun RecoveryEntity.toSummary() = RecoverySummary(
    state = scoreStateOf(scoreState),
    score = recoveryScore,
    hrvMilli = hrvRmssdMilli,
    restingHeartRate = restingHeartRate,
    spo2Percentage = spo2Percentage,
    skinTempCelsius = skinTempCelsius,
)

private fun SleepEntity.toSummary(): SleepSummary {
    val stages = listOf(lightMilli, deepMilli, remMilli)
    return SleepSummary(
        state = scoreStateOf(scoreState),
        performancePercentage = performancePercentage,
        // A partial stage set would understate time asleep, so it is reported as missing.
        asleepMilli = if (stages.any { it == null }) null else stages.sumOf { it ?: 0L },
        awakeMilli = awakeMilli,
        lightMilli = lightMilli,
        deepMilli = deepMilli,
        remMilli = remMilli,
        needMilli = needMilli,
        respiratoryRate = respiratoryRate,
    )
}

private fun WorkoutEntity.toSummary() = ActivitySummary(
    id = id,
    sportName = sportName,
    start = Instant.ofEpochMilli(startMillis),
    end = Instant.ofEpochMilli(endMillis),
    offset = ZoneOffset.of(timezoneOffset),
    state = scoreStateOf(scoreState),
    strain = strain,
    averageHeartRate = averageHeartRate,
    zoneFractions = zoneFractions(
        listOf(zoneZeroMilli, zoneOneMilli, zoneTwoMilli, zoneThreeMilli, zoneFourMilli, zoneFiveMilli),
    ),
)
