package com.whoof.privateapi

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal enum class PrivateWhoopError {
    InvalidInput, Unauthorized, Rejected, RateLimited, Unavailable, InvalidResponse,
    UnsupportedChallenge, ReconnectRequired, ConfirmationRequired, SubmissionUncertain,
    AlreadyAttempted, StorageFailed,
    // Definite activity-create/delete outcomes.
    Overlap, TooShort, NotFound,
    // Fixed Cognito sign-in outcomes, selected only from the error `__type`.
    CredentialsRejected, AccountNotFound, PasswordResetRequired, AccountNotConfirmed,
    WrongCode, CodeExpired, TooManyAttempts,
}

/** Fixed error categories only: never retain a remote body or transport exception. */
internal class PrivateWhoopException(val reason: PrivateWhoopError) : Exception(reason.name)

@Serializable
internal data class PrivateWhoopSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
) {
    init {
        requirePrivate(validPrivateToken(accessToken) && validPrivateToken(refreshToken) && expiresAtEpochSeconds > 0)
    }

    override fun toString(): String = "PrivateWhoopSession(redacted)"
}

internal fun validPrivateToken(value: String): Boolean =
    value.length in 20..2_048 && value.all { it.code in 0x21..0x7e }

internal fun requirePrivate(condition: Boolean) {
    if (!condition) throw PrivateWhoopException(PrivateWhoopError.InvalidInput)
}

internal class PrivateWhoopChallenge(val name: String, val session: String, val username: String) {
    override fun toString(): String = "PrivateWhoopChallenge(redacted)"
}

internal sealed interface PrivateWhoopLoginResult {
    class Authenticated(val session: PrivateWhoopSession) : PrivateWhoopLoginResult
    class VerificationRequired(val challenge: PrivateWhoopChallenge) : PrivateWhoopLoginResult
}

@Serializable
internal data class PrivateWhoopSport(val id: Int, val name: String)

@Serializable
internal data class PrivateActivityPayload(
    @SerialName("sport_id") val sportId: Int,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("gps_enabled") val gpsEnabled: Boolean = false,
) {
    override fun toString(): String = "PrivateActivityPayload(redacted)"
}

@Serializable
internal enum class PrivateActivityStatus { Prepared, Uncertain, Submitted, Rejected }

@Serializable
internal data class PrivateActivityDraft(
    val id: String,
    val sportId: Int,
    val sportName: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val timeZone: String,
    val status: PrivateActivityStatus = PrivateActivityStatus.Prepared,
    val activityId: String? = null,
) {
    override fun toString(): String = "PrivateActivityDraft(redacted)"

    fun payload(): PrivateActivityPayload = PrivateActivityPayload(
        sportId = sportId,
        startTime = UTC_MILLIS.format(Instant.ofEpochMilli(startEpochMillis)),
        endTime = UTC_MILLIS.format(Instant.ofEpochMilli(endEpochMillis)),
    )

    fun validate(now: Instant, completedOnly: Boolean = true) {
        requirePrivate(PrivateWhoopSports.byId(sportId)?.name == sportName)
        requirePrivate(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        requirePrivate(runCatching { ZoneId.of(timeZone) }.isSuccess)
        requirePrivate(endEpochMillis > startEpochMillis &&
            endEpochMillis - startEpochMillis in 60_000L..86_400_000L)
        if (completedOnly) requirePrivate(endEpochMillis <= now.toEpochMilli())
        requirePrivate((status == PrivateActivityStatus.Submitted) == (activityId != null))
        activityId?.let { requirePrivate(runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)) }
    }

    private companion object {
        val UTC_MILLIS = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    }
}

/** Where Developer-shaped reads get their bearer token: always the private account session. */
internal interface WhoopAccountSession {
    /**
     * The current access token, refreshed first when within 60 s of expiry. [forceRefresh] is for a
     * GET that returned 401: it refreshes unless [rejectedToken] is no longer the stored token
     * (another caller already rotated it). Throws ReconnectRequired when there is no usable session.
     */
    suspend fun accessToken(forceRefresh: Boolean = false, rejectedToken: String? = null): String

    suspend fun isSignedIn(): Boolean

    /** Ends the session but keeps the no-replay activity marker. */
    suspend fun disconnect()

    /** Removes everything this phone stores about the account, including the no-replay marker. */
    suspend fun deleteLocalData(confirmed: Boolean)
}

internal interface WhoopPrivateTransport {
    suspend fun beginLogin(email: String, password: String): PrivateWhoopLoginResult
    suspend fun completeLogin(challenge: PrivateWhoopChallenge, code: String): PrivateWhoopLoginResult
    suspend fun refresh(session: PrivateWhoopSession): PrivateWhoopSession
    /** Exchange a scanned (v=2) refresh token. The token is single-use: WHOOP rotates it. */
    suspend fun refreshFromToken(refreshToken: String): PrivateWhoopSession
    suspend fun checkAccount(session: PrivateWhoopSession)
    suspend fun createActivity(session: PrivateWhoopSession, payload: PrivateActivityPayload, timeZone: String): String
    /** Single DELETE; callers must never retry it. */
    suspend fun deleteActivity(session: PrivateWhoopSession, activityId: String)
    fun close() = Unit
}
