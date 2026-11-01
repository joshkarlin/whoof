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
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.flow.Flow

internal const val PENDING_DATABASE_NAME = "whoof_pending.db"

/** How long a logged activity is shown locally while waiting for WHOOP's workout list to return it. */
internal val PENDING_ACTIVITY_TTL: Duration = Duration.ofHours(48)

/**
 * An activity this phone submitted to WHOOP that the Developer API's workout list may not return
 * yet. Health data, so it lives in Room beside the cache and is removed with it.
 */
@Entity(tableName = "pending_activities")
internal data class PendingActivityEntity(
    @PrimaryKey @ColumnInfo(name = "activity_id") val activityId: String,
    @ColumnInfo(name = "sport_id") val sportId: Int,
    @ColumnInfo(name = "sport_name") val sportName: String,
    @ColumnInfo(name = "start_millis") val startMillis: Long,
    @ColumnInfo(name = "end_millis") val endMillis: Long,
    @ColumnInfo(name = "timezone_offset") val timezoneOffset: String,
    @ColumnInfo(name = "created_millis") val createdMillis: Long,
) {
    override fun toString(): String = "PendingActivityEntity(redacted)"
}

internal interface PendingActivityStore {
    fun observe(): Flow<List<PendingActivityEntity>>

    suspend fun all(): List<PendingActivityEntity>

    suspend fun add(activity: PendingActivityEntity)

    suspend fun remove(ids: Collection<String>)

    suspend fun clear()
}

@Dao
internal interface PendingActivityDao {
    @Query("SELECT * FROM pending_activities ORDER BY start_millis DESC")
    fun observe(): Flow<List<PendingActivityEntity>>

    @Query("SELECT * FROM pending_activities")
    suspend fun all(): List<PendingActivityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(activity: PendingActivityEntity)

    @Query("DELETE FROM pending_activities WHERE activity_id IN (:ids)")
    suspend fun remove(ids: List<String>)

    @Query("DELETE FROM pending_activities")
    suspend fun clear()
}

/**
 * A separate database file from the WHOOP cache: the SDK's database builder cannot register Room
 * migrations, so adding a table to the cache database would break existing installs.
 */
@Database(entities = [PendingActivityEntity::class], version = 1, exportSchema = false)
internal abstract class PendingDatabase : RoomDatabase() {
    abstract fun pendingDao(): PendingActivityDao
}

internal class RoomPendingActivityStore(database: PendingDatabase) : PendingActivityStore {
    private val dao = database.pendingDao()

    override fun observe(): Flow<List<PendingActivityEntity>> = dao.observe()

    override suspend fun all(): List<PendingActivityEntity> = dao.all()

    override suspend fun add(activity: PendingActivityEntity) = dao.upsert(activity)

    override suspend fun remove(ids: Collection<String>) {
        if (ids.isNotEmpty()) dao.remove(ids.toList())
    }

    override suspend fun clear() = dao.clear()
}

private fun String.idKey(): String = lowercase(Locale.US)

/** Pending ids to forget: WHOOP now lists them as workouts, or they outlived [PENDING_ACTIVITY_TTL]. */
internal fun stalePendingIds(
    pending: List<PendingActivityEntity>,
    workoutIds: Collection<String>,
    nowMillis: Long,
): List<String> {
    val listed = workoutIds.map { it.idKey() }.toSet()
    val cutoff = nowMillis - PENDING_ACTIVITY_TTL.toMillis()
    return pending.filter { it.activityId.idKey() in listed || it.createdMillis < cutoff }.map { it.activityId }
}

/** Pending activities still worth showing: not already a cached workout and not expired. */
internal fun livePending(
    pending: List<PendingActivityEntity>,
    workoutIds: Collection<String>,
    nowMillis: Long,
): List<PendingActivityEntity> {
    val stale = stalePendingIds(pending, workoutIds, nowMillis).toSet()
    return pending.filter { it.activityId !in stale }
}

/**
 * Shown like a workout WHOOP has not scored yet. The name takes the form of WHOOP's `sport_name`
 * (lowercase, hyphenated) so it displays the same once the real workout replaces it.
 */
internal fun PendingActivityEntity.toSummary() = ActivitySummary(
    id = activityId,
    sportName = sportName.lowercase(Locale.US).replace(' ', '-'),
    start = Instant.ofEpochMilli(startMillis),
    end = Instant.ofEpochMilli(endMillis),
    offset = runCatching { ZoneOffset.of(timezoneOffset) }.getOrDefault(ZoneOffset.UTC),
    state = ScoreState.Pending,
    strain = null,
    averageHeartRate = null,
    zoneFractions = emptyList(),
    awaitingWhoop = true,
)
