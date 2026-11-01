package com.whoof

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.sdk.SealedLightContext
import com.whoof.privateapi.PrivateWhoopError
import com.whoof.privateapi.PrivateWhoopException
import com.whoof.privateapi.PrivateWhoopServices
import com.whoof.privateapi.PrivateWhoopSport
import com.whoof.privateapi.PrivateWhoopSports
import com.whoof.privateapi.WhoopPrivateService
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.first

internal const val MAX_RECENT_SPORTS = 5
internal const val MAX_FAVOURITE_SPORTS = 30
internal const val MIN_ACTIVITY_MILLIS = 60_000L
internal const val MAX_ACTIVITY_MILLIS = 86_400_000L
internal const val MANUAL_DEFAULT_MINUTES = 30L
internal val ADJUST_STEPS_MINUTES = listOf(-15, -5, -1, 1, 5, 15)

/** A running activity timer. Activity timing only: no credentials or health values. */
internal data class ActivityTimer(val sportId: Int, val startMillis: Long)

/** What Today needs to decide whether to offer logging, deleting, or the running timer. */
internal data class ActivityStatus(val signedIn: Boolean = false, val timer: ActivityTimer? = null)

internal interface ActivityPrefs {
    suspend fun recentSportIds(): List<Int>
    suspend fun recordRecent(sportId: Int)

    /** Favourite sport ids in the order the user arranged them. */
    suspend fun favouriteSportIds(): List<Int>
    suspend fun saveFavouriteSportIds(ids: List<Int>)
    suspend fun timer(): ActivityTimer?
    suspend fun saveTimer(timer: ActivityTimer)
    suspend fun clearTimer()

    /** Removes the timer, recent sports, and favourites. */
    suspend fun clear()
}

/** Nonsensitive plain DataStore entries: recently used sport ids and the running timer. */
internal class DataStoreActivityPrefs(private val dataStore: DataStore<Preferences>) : ActivityPrefs {
    override suspend fun recentSportIds(): List<Int> =
        parseRecents(dataStore.data.first()[RECENT_KEY])

    override suspend fun recordRecent(sportId: Int) {
        dataStore.edit { prefs ->
            prefs[RECENT_KEY] = pushRecent(parseRecents(prefs[RECENT_KEY]), sportId).joinToString(",")
        }
    }

    override suspend fun favouriteSportIds(): List<Int> =
        parseSportIds(dataStore.data.first()[FAVOURITES_KEY], MAX_FAVOURITE_SPORTS)

    override suspend fun saveFavouriteSportIds(ids: List<Int>) {
        dataStore.edit { it[FAVOURITES_KEY] = ids.distinct().take(MAX_FAVOURITE_SPORTS).joinToString(",") }
    }

    override suspend fun timer(): ActivityTimer? {
        val prefs = dataStore.data.first()
        val sportId = prefs[TIMER_SPORT_KEY] ?: return null
        val start = prefs[TIMER_START_KEY] ?: return null
        if (PrivateWhoopSports.byId(sportId) == null || start <= 0) return null
        return ActivityTimer(sportId, start)
    }

    override suspend fun saveTimer(timer: ActivityTimer) {
        dataStore.edit {
            it[TIMER_SPORT_KEY] = timer.sportId
            it[TIMER_START_KEY] = timer.startMillis
        }
    }

    override suspend fun clearTimer() {
        dataStore.edit {
            it.remove(TIMER_SPORT_KEY)
            it.remove(TIMER_START_KEY)
        }
    }

    override suspend fun clear() {
        dataStore.edit {
            it.remove(RECENT_KEY)
            it.remove(FAVOURITES_KEY)
            it.remove(TIMER_SPORT_KEY)
            it.remove(TIMER_START_KEY)
        }
    }

    private companion object {
        val RECENT_KEY = stringPreferencesKey("activity_recent_sports")
        val FAVOURITES_KEY = stringPreferencesKey("activity_favourite_sports")
        val TIMER_SPORT_KEY = intPreferencesKey("activity_timer_sport")
        val TIMER_START_KEY = longPreferencesKey("activity_timer_start_millis")
    }
}

/** The private-session operations the activity screens need; faked in tests. */
internal interface ActivityBackend {
    suspend fun signedIn(): Boolean

    /** Prepares and sends exactly one POST; returns WHOOP's activity id. Throws [PrivateWhoopException] with a fixed reason. */
    suspend fun submit(sport: PrivateWhoopSport, start: Instant, end: Instant, zone: ZoneId): String

    suspend fun discardDraft()

    suspend fun delete(activityId: String)
}

internal class ServiceActivityBackend(private val service: WhoopPrivateService) : ActivityBackend {
    override suspend fun signedIn(): Boolean = try {
        service.state().session != null
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }

    override suspend fun submit(sport: PrivateWhoopSport, start: Instant, end: Instant, zone: ZoneId): String {
        val draft = service.prepareActivity(sport, start, end, zone)
        return service.submitActivity(draft.id, confirmed = true).activityId
            ?: throw PrivateWhoopException(PrivateWhoopError.SubmissionUncertain)
    }

    override suspend fun discardDraft() = service.discardDraft(confirmed = true)

    override suspend fun delete(activityId: String) = service.deleteActivity(activityId, confirmed = true)
}

/** Shared construction for screens; the service itself is the process-wide singleton. */
internal class ActivityTools(val backend: ActivityBackend, val prefs: ActivityPrefs) {
    suspend fun status(): ActivityStatus {
        val signedIn = backend.signedIn()
        val timer = if (signedIn) runCatching { prefs.timer() }.getOrNull() else null
        return ActivityStatus(signedIn, timer)
    }

    companion object {
        fun from(context: SealedLightContext) = ActivityTools(
            ServiceActivityBackend(PrivateWhoopServices.getInstance(context)),
            DataStoreActivityPrefs(context.dataStore),
        )
    }
}

internal fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) / 1_000)
    return String.format(Locale.US, "%d:%02d:%02d", totalSeconds / 3_600, totalSeconds / 60 % 60, totalSeconds % 60)
}

internal fun parseSportIds(stored: String?, max: Int): List<Int> =
    stored.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        .filter { PrivateWhoopSports.byId(it) != null }
        .distinct()
        .take(max)

internal fun parseRecents(stored: String?): List<Int> = parseSportIds(stored, MAX_RECENT_SPORTS)

internal fun pushRecent(recents: List<Int>, sportId: Int): List<Int> =
    (listOf(sportId) + recents.filter { it != sportId }).take(MAX_RECENT_SPORTS)

/** Appends [sportId] unless it is already a favourite or the list is full. */
internal fun addFavourite(favourites: List<Int>, sportId: Int): List<Int> =
    if (sportId in favourites || favourites.size >= MAX_FAVOURITE_SPORTS) favourites else favourites + sportId

internal fun removeFavourite(favourites: List<Int>, sportId: Int): List<Int> = favourites.filter { it != sportId }

/** Moves a favourite one place earlier ([delta] -1) or later (+1); out-of-range moves change nothing. */
internal fun moveFavourite(favourites: List<Int>, sportId: Int, delta: Int): List<Int> {
    val from = favourites.indexOf(sportId)
    val to = from + delta
    if (from < 0 || to !in favourites.indices) return favourites
    return favourites.toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * What the picker lists. With a query only [all] is filled (the matches); without one, [favourites]
 * (user order), [recent] (most recent first, minus favourites) and [all] (alphabetical) are filled.
 */
internal data class SportSections(
    val favourites: List<PrivateWhoopSport>,
    val recent: List<PrivateWhoopSport>,
    val all: List<PrivateWhoopSport>,
) {
    val isEmpty: Boolean get() = favourites.isEmpty() && recent.isEmpty() && all.isEmpty()
}

/**
 * [pickOnly] is for choosing a new favourite: favourites are left out of every list since they are
 * already chosen, and the FAVOURITES section is not offered.
 */
internal fun buildSportSections(
    query: String,
    favouriteIds: List<Int>,
    recentIds: List<Int>,
    all: List<PrivateWhoopSport> = PrivateWhoopSports.all,
    pickOnly: Boolean = false,
): SportSections {
    val needle = query.trim().lowercase(Locale.US)
    val byId = all.associateBy { it.id }
    val favourites = favouriteIds.distinct().mapNotNull { byId[it] }
    val favouriteSet = favourites.map { it.id }.toSet()
    val pool = if (pickOnly) all.filter { it.id !in favouriteSet } else all
    if (needle.isNotEmpty()) {
        val matches = pool.filter { it.name.lowercase(Locale.US).contains(needle) }
        val (prefix, other) = matches.partition { it.name.lowercase(Locale.US).startsWith(needle) }
        return SportSections(emptyList(), emptyList(), prefix.sortedByName() + other.sortedByName())
    }
    val recent = recentIds.distinct().filter { it !in favouriteSet }.mapNotNull { byId[it] }.take(MAX_RECENT_SPORTS)
    return SportSections(if (pickOnly) emptyList() else favourites, recent, pool.sortedByName())
}

private fun List<PrivateWhoopSport>.sortedByName() = sortedBy { it.name.lowercase(Locale.US) }

/** Moves [instant] by [deltaMinutes], never later than [max]. */
internal fun adjustTime(instant: Instant, deltaMinutes: Int, max: Instant): Instant =
    minOf(instant.plusSeconds(deltaMinutes * 60L), max)

internal fun Instant.dateTimeText(zone: ZoneId): String = DATE_TIME.format(atZone(zone))

internal fun Instant.dateText(zone: ZoneId): String = DATE.format(atZone(zone))

internal fun Instant.clockText(zone: ZoneId): String = CLOCK.format(atZone(zone))

internal fun durationText(start: Instant, end: Instant): String {
    val minutes = Duration.between(start, end).toMinutes().coerceAtLeast(0)
    return if (minutes >= 60) String.format(Locale.US, "%dH %02dM", minutes / 60, minutes % 60) else "$minutes MIN"
}

internal enum class RangeProblem(val message: String) {
    EndBeforeStart("End must be after start."),
    TooShort("Activities must be at least 1 minute."),
    TooLong("Activities can be at most 24 hours."),
    InFuture("An activity cannot end in the future."),
}

internal fun rangeProblem(start: Instant, end: Instant, now: Instant): RangeProblem? {
    val length = end.toEpochMilli() - start.toEpochMilli()
    return when {
        length <= 0 -> RangeProblem.EndBeforeStart
        length < MIN_ACTIVITY_MILLIS -> RangeProblem.TooShort
        length > MAX_ACTIVITY_MILLIS -> RangeProblem.TooLong
        end.isAfter(now) -> RangeProblem.InFuture
        else -> null
    }
}

internal const val UNCERTAIN_MESSAGE =
    "A submission may or may not have reached WHOOP. Check the WHOOP app, then clear this warning."

internal fun PrivateWhoopError.activityMessage(delete: Boolean = false): String = when (this) {
    PrivateWhoopError.Overlap ->
        "This overlaps an activity already in WHOOP. Delete that one first, then submit again."
    PrivateWhoopError.TooShort -> "Activities must be at least 1 minute."
    PrivateWhoopError.InvalidInput ->
        if (delete) "That activity cannot be deleted." else "Check the times. Activities run 1 minute to 24 hours and cannot end in the future."
    PrivateWhoopError.SubmissionUncertain, PrivateWhoopError.AlreadyAttempted -> UNCERTAIN_MESSAGE
    PrivateWhoopError.ReconnectRequired, PrivateWhoopError.Unauthorized ->
        "Session expired. Sign out in Settings, then sign in again."
    PrivateWhoopError.RateLimited, PrivateWhoopError.TooManyAttempts -> "Too many attempts. Wait and try again."
    PrivateWhoopError.Unavailable -> "WHOOP could not be reached."
    PrivateWhoopError.Rejected -> "WHOOP rejected the request."
    PrivateWhoopError.InvalidResponse -> "WHOOP returned an unexpected response."
    PrivateWhoopError.StorageFailed -> "The request could not be saved on this phone."
    else -> if (delete) "Could not delete the activity." else "Could not log the activity."
}

internal fun Throwable.activityMessage(delete: Boolean = false): String =
    (this as? PrivateWhoopException)?.reason?.activityMessage(delete)
        ?: if (delete) "Could not delete the activity." else "Could not log the activity."

private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.US)
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
