package com.whoof

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

internal const val WHOOF_DATABASE_NAME = "whoof_cache.db"

@Entity(tableName = "cycles")
internal data class CycleEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "start_millis") val startMillis: Long,
    @ColumnInfo(name = "end_millis") val endMillis: Long?,
    @ColumnInfo(name = "timezone_offset") val timezoneOffset: String,
    @ColumnInfo(name = "score_state") val scoreState: String,
    val strain: Double?,
    val kilojoule: Double?,
    @ColumnInfo(name = "average_heart_rate") val averageHeartRate: Int?,
    @ColumnInfo(name = "max_heart_rate") val maxHeartRate: Int?,
    @ColumnInfo(name = "updated_millis") val updatedMillis: Long,
)

@Entity(tableName = "recoveries")
internal data class RecoveryEntity(
    @PrimaryKey @ColumnInfo(name = "cycle_id") val cycleId: Long,
    @ColumnInfo(name = "score_state") val scoreState: String,
    @ColumnInfo(name = "recovery_score") val recoveryScore: Double?,
    @ColumnInfo(name = "resting_heart_rate") val restingHeartRate: Double?,
    @ColumnInfo(name = "hrv_rmssd_milli") val hrvRmssdMilli: Double?,
    @ColumnInfo(name = "spo2_percentage") val spo2Percentage: Double?,
    @ColumnInfo(name = "skin_temp_celsius") val skinTempCelsius: Double?,
    @ColumnInfo(name = "user_calibrating") val userCalibrating: Boolean?,
    @ColumnInfo(name = "updated_millis") val updatedMillis: Long,
)

@Entity(tableName = "sleeps")
internal data class SleepEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "cycle_id") val cycleId: Long,
    @ColumnInfo(name = "start_millis") val startMillis: Long,
    @ColumnInfo(name = "end_millis") val endMillis: Long,
    @ColumnInfo(name = "timezone_offset") val timezoneOffset: String,
    val nap: Boolean,
    @ColumnInfo(name = "score_state") val scoreState: String,
    @ColumnInfo(name = "performance_percentage") val performancePercentage: Double?,
    @ColumnInfo(name = "respiratory_rate") val respiratoryRate: Double?,
    @ColumnInfo(name = "in_bed_milli") val inBedMilli: Long?,
    @ColumnInfo(name = "awake_milli") val awakeMilli: Long?,
    @ColumnInfo(name = "light_milli") val lightMilli: Long?,
    @ColumnInfo(name = "deep_milli") val deepMilli: Long?,
    @ColumnInfo(name = "rem_milli") val remMilli: Long?,
    @ColumnInfo(name = "need_milli") val needMilli: Long?,
    @ColumnInfo(name = "updated_millis") val updatedMillis: Long,
)

@Entity(tableName = "workouts")
internal data class WorkoutEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "start_millis") val startMillis: Long,
    @ColumnInfo(name = "end_millis") val endMillis: Long,
    @ColumnInfo(name = "timezone_offset") val timezoneOffset: String,
    @ColumnInfo(name = "sport_name") val sportName: String,
    @ColumnInfo(name = "score_state") val scoreState: String,
    val strain: Double?,
    @ColumnInfo(name = "average_heart_rate") val averageHeartRate: Int?,
    @ColumnInfo(name = "max_heart_rate") val maxHeartRate: Int?,
    @ColumnInfo(name = "zone_zero_milli") val zoneZeroMilli: Long?,
    @ColumnInfo(name = "zone_one_milli") val zoneOneMilli: Long?,
    @ColumnInfo(name = "zone_two_milli") val zoneTwoMilli: Long?,
    @ColumnInfo(name = "zone_three_milli") val zoneThreeMilli: Long?,
    @ColumnInfo(name = "zone_four_milli") val zoneFourMilli: Long?,
    @ColumnInfo(name = "zone_five_milli") val zoneFiveMilli: Long?,
    @ColumnInfo(name = "updated_millis") val updatedMillis: Long,
)

/** Cached WHOOP records as persisted locally, independent of transport and UI models. */
internal data class CacheSnapshot(
    val cycles: List<CycleEntity> = emptyList(),
    val recoveries: List<RecoveryEntity> = emptyList(),
    val sleeps: List<SleepEntity> = emptyList(),
    val workouts: List<WorkoutEntity> = emptyList(),
) {
    val isEmpty: Boolean
        get() = cycles.isEmpty() && recoveries.isEmpty() && sleeps.isEmpty() && workouts.isEmpty()
}

/**
 * One synchronization's fetched records. Each `*WindowStartMillis` is the earliest start covered by a
 * complete fetch; cached records at or after it that WHOOP no longer returns are removed.
 */
internal data class SyncBatch(
    val cycles: List<CycleEntity>,
    val recoveries: List<RecoveryEntity>,
    val sleeps: List<SleepEntity>,
    val workouts: List<WorkoutEntity>,
    val cycleWindowStartMillis: Long?,
    val sleepWindowStartMillis: Long?,
    val workoutWindowStartMillis: Long?,
    val retainSinceMillis: Long,
)

internal interface WhoopCache {
    fun observe(): Flow<CacheSnapshot>

    suspend fun apply(batch: SyncBatch)

    /** Removes one cached workout, such as one just deleted from WHOOP. */
    suspend fun deleteWorkout(id: String)

    suspend fun clear()
}

@Dao
internal interface WhoopCacheDao {
    @Query("SELECT * FROM cycles ORDER BY start_millis DESC")
    fun observeCycles(): Flow<List<CycleEntity>>

    @Query("SELECT * FROM recoveries")
    fun observeRecoveries(): Flow<List<RecoveryEntity>>

    @Query("SELECT * FROM sleeps ORDER BY start_millis DESC")
    fun observeSleeps(): Flow<List<SleepEntity>>

    @Query("SELECT * FROM workouts ORDER BY start_millis DESC")
    fun observeWorkouts(): Flow<List<WorkoutEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCycles(records: List<CycleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecoveries(records: List<RecoveryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSleeps(records: List<SleepEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkouts(records: List<WorkoutEntity>)

    @Query("DELETE FROM cycles WHERE start_millis >= :windowStart")
    suspend fun deleteCyclesFrom(windowStart: Long)

    @Query("DELETE FROM sleeps WHERE start_millis >= :windowStart")
    suspend fun deleteSleepsFrom(windowStart: Long)

    @Query("DELETE FROM workouts WHERE start_millis >= :windowStart")
    suspend fun deleteWorkoutsFrom(windowStart: Long)

    @Query("DELETE FROM cycles WHERE start_millis < :cutoff")
    suspend fun pruneCycles(cutoff: Long)

    @Query("DELETE FROM recoveries WHERE cycle_id NOT IN (SELECT id FROM cycles)")
    suspend fun pruneOrphanRecoveries()

    @Query("DELETE FROM sleeps WHERE start_millis < :cutoff")
    suspend fun pruneSleeps(cutoff: Long)

    @Query("DELETE FROM workouts WHERE start_millis < :cutoff")
    suspend fun pruneWorkouts(cutoff: Long)

    @Query("DELETE FROM cycles")
    suspend fun clearCycles()

    @Query("DELETE FROM recoveries")
    suspend fun clearRecoveries()

    @Query("DELETE FROM sleeps")
    suspend fun clearSleeps()

    @Query("DELETE FROM workouts")
    suspend fun clearWorkouts()

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun deleteWorkoutById(id: String)
}

@Database(
    entities = [CycleEntity::class, RecoveryEntity::class, SleepEntity::class, WorkoutEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class WhoofDatabase : RoomDatabase() {
    abstract fun cacheDao(): WhoopCacheDao
}

internal class RoomWhoopCache(private val database: WhoofDatabase) : WhoopCache {
    private val dao = database.cacheDao()

    override fun observe(): Flow<CacheSnapshot> = combine(
        dao.observeCycles(),
        dao.observeRecoveries(),
        dao.observeSleeps(),
        dao.observeWorkouts(),
    ) { cycles, recoveries, sleeps, workouts ->
        CacheSnapshot(cycles, recoveries, sleeps, workouts)
    }

    override suspend fun apply(batch: SyncBatch) {
        database.withTransaction {
            batch.cycleWindowStartMillis?.let { dao.deleteCyclesFrom(it) }
            batch.sleepWindowStartMillis?.let { dao.deleteSleepsFrom(it) }
            batch.workoutWindowStartMillis?.let { dao.deleteWorkoutsFrom(it) }
            dao.upsertCycles(batch.cycles)
            dao.upsertRecoveries(batch.recoveries)
            dao.upsertSleeps(batch.sleeps)
            dao.upsertWorkouts(batch.workouts)
            dao.pruneCycles(batch.retainSinceMillis)
            dao.pruneOrphanRecoveries()
            dao.pruneSleeps(batch.retainSinceMillis)
            dao.pruneWorkouts(batch.retainSinceMillis)
        }
    }

    override suspend fun deleteWorkout(id: String) = dao.deleteWorkoutById(id)

    override suspend fun clear() {
        database.withTransaction {
            dao.clearCycles()
            dao.clearRecoveries()
            dao.clearSleeps()
            dao.clearWorkouts()
        }
    }
}

/** Returns null for a record whose required timestamps or offset cannot be parsed. */
internal fun CycleRecord.toEntity(): CycleEntity? {
    val startMillis = start.epochMillisOrNull() ?: return null
    if (!timezoneOffset.isValidOffset()) return null
    return CycleEntity(
        id = id,
        startMillis = startMillis,
        endMillis = end?.let { it.epochMillisOrNull() ?: return null },
        timezoneOffset = timezoneOffset,
        scoreState = scoreState,
        strain = score?.strain,
        kilojoule = score?.kilojoule,
        averageHeartRate = score?.averageHeartRate,
        maxHeartRate = score?.maxHeartRate,
        updatedMillis = updatedAt.epochMillisOrNull() ?: startMillis,
    )
}

internal fun RecoveryRecord.toEntity(): RecoveryEntity = RecoveryEntity(
    cycleId = cycleId,
    scoreState = scoreState,
    recoveryScore = score?.recoveryScore,
    restingHeartRate = score?.restingHeartRate,
    hrvRmssdMilli = score?.hrvRmssdMilli,
    spo2Percentage = score?.spo2Percentage,
    skinTempCelsius = score?.skinTempCelsius,
    userCalibrating = score?.userCalibrating,
    updatedMillis = updatedAt.epochMillisOrNull() ?: 0L,
)

internal fun SleepRecord.toEntity(): SleepEntity? {
    val startMillis = start.epochMillisOrNull() ?: return null
    val endMillis = end.epochMillisOrNull() ?: return null
    if (!timezoneOffset.isValidOffset()) return null
    val stages = score?.stageSummary
    return SleepEntity(
        id = id,
        cycleId = cycleId,
        startMillis = startMillis,
        endMillis = endMillis,
        timezoneOffset = timezoneOffset,
        nap = nap,
        scoreState = scoreState,
        performancePercentage = score?.sleepPerformancePercentage,
        respiratoryRate = score?.respiratoryRate,
        inBedMilli = stages?.totalInBedTimeMilli,
        awakeMilli = stages?.totalAwakeTimeMilli,
        lightMilli = stages?.totalLightSleepTimeMilli,
        deepMilli = stages?.totalSlowWaveSleepTimeMilli,
        remMilli = stages?.totalRemSleepTimeMilli,
        needMilli = score?.sleepNeeded?.totalMilli(),
        updatedMillis = updatedAt.epochMillisOrNull() ?: startMillis,
    )
}

internal fun WorkoutRecord.toEntity(): WorkoutEntity? {
    val startMillis = start.epochMillisOrNull() ?: return null
    val endMillis = end.epochMillisOrNull() ?: return null
    if (!timezoneOffset.isValidOffset()) return null
    val zones = score?.zoneDurations
    return WorkoutEntity(
        id = id,
        startMillis = startMillis,
        endMillis = endMillis,
        timezoneOffset = timezoneOffset,
        sportName = sportName,
        scoreState = scoreState,
        strain = score?.strain,
        averageHeartRate = score?.averageHeartRate,
        maxHeartRate = score?.maxHeartRate,
        zoneZeroMilli = zones?.zoneZeroMilli,
        zoneOneMilli = zones?.zoneOneMilli,
        zoneTwoMilli = zones?.zoneTwoMilli,
        zoneThreeMilli = zones?.zoneThreeMilli,
        zoneFourMilli = zones?.zoneFourMilli,
        zoneFiveMilli = zones?.zoneFiveMilli,
        updatedMillis = updatedAt.epochMillisOrNull() ?: startMillis,
    )
}

/** WHOOP reports nap credit as a negative adjustment, so the components are summed as given. */
private fun SleepNeeded.totalMilli(): Long? {
    val parts = listOfNotNull(
        baselineMilli,
        needFromSleepDebtMilli,
        needFromRecentStrainMilli,
        needFromRecentNapMilli,
    )
    return if (parts.isEmpty()) null else parts.sum()
}

private fun String.epochMillisOrNull(): Long? =
    runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()

private fun String.isValidOffset(): Boolean = runCatching { ZoneOffset.of(this) }.isSuccess
