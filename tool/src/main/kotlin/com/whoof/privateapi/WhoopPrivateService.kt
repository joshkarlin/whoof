package com.whoof.privateapi

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Share one instance across private screens. No activity POST is ever replayed. */
internal class WhoopPrivateService(
    private val store: PrivateWhoopStore,
    private val client: WhoopPrivateTransport,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun state(): PrivateWhoopState = operations.withLock { store.load() }

    suspend fun beginLogin(email: String, password: String): PrivateWhoopLoginResult = operations.withLock {
        client.beginLogin(email, password).also { acceptLogin(it) }
    }

    suspend fun completeLogin(challenge: PrivateWhoopChallenge, code: String): PrivateWhoopLoginResult =
        operations.withLock { client.completeLogin(challenge, code).also { acceptLogin(it) } }

    suspend fun provision(payload: String) = operations.withLock {
        val session = PrivateWhoopProvisioning.parse(payload, clock())
        acceptSession(session)
    }

    suspend fun checkConnection() = operations.withLock {
        authorizedState(store.load())
        Unit
    }

    suspend fun disconnect() = operations.withLock {
        // Keep the submission marker across logout so reconnecting cannot silently replay it.
        val state = store.load()
        store.save(state.copy(session = null, draft = state.draft?.takeUnless { it.status == PrivateActivityStatus.Prepared }))
    }

    /** Explicit local-data deletion, not a server-side revoke or activity deletion. */
    suspend fun deleteLocalData(confirmed: Boolean) = operations.withLock {
        if (!confirmed) throw PrivateWhoopException(PrivateWhoopError.ConfirmationRequired)
        store.clear()
    }

    suspend fun prepareActivity(
        sport: PrivateWhoopSport,
        start: Instant,
        end: Instant,
        timeZone: ZoneId,
    ): PrivateActivityDraft = operations.withLock {
        val state = store.load()
        if (state.session == null) throw PrivateWhoopException(PrivateWhoopError.ReconnectRequired)
        if (state.draft?.status == PrivateActivityStatus.Uncertain) {
            throw PrivateWhoopException(PrivateWhoopError.SubmissionUncertain)
        }
        val draft = PrivateActivityDraft(UUID.randomUUID().toString(), sport, start.toEpochMilli(), end.toEpochMilli(), timeZone.id)
        draft.validate(clock())
        store.save(state.copy(draft = draft))
        draft
    }

    /** Only after the user has checked WHOOP for a possibly accepted activity. */
    suspend fun discardDraft(confirmed: Boolean) = operations.withLock {
        if (!confirmed) throw PrivateWhoopException(PrivateWhoopError.ConfirmationRequired)
        val state = store.load()
        store.save(state.copy(draft = null))
    }

    suspend fun submitActivity(draftId: String, confirmed: Boolean): PrivateActivityDraft = operations.withLock {
        if (!confirmed) throw PrivateWhoopException(PrivateWhoopError.ConfirmationRequired)
        val original = store.load()
        val draft = original.draft ?: throw PrivateWhoopException(PrivateWhoopError.InvalidInput)
        requirePrivate(draft.id == draftId)
        if (draft.status != PrivateActivityStatus.Prepared) {
            throw PrivateWhoopException(if (draft.status == PrivateActivityStatus.Uncertain)
                PrivateWhoopError.SubmissionUncertain else PrivateWhoopError.AlreadyAttempted)
        }
        draft.validate(clock())
        // Refresh/check before marking the write attempted; a failure here sends no POST.
        val ready = authorizedState(original)
        val attempted = draft.copy(status = PrivateActivityStatus.Uncertain)
        store.save(ready.copy(draft = attempted))
        val receipt = try {
            client.createActivity(ready.session!!, draft.payload(), draft.timeZone)
        } catch (error: CancellationException) {
            throw error
        } catch (error: PrivateWhoopException) {
            if (error.reason in DEFINITE_REJECTIONS) {
                store.save(ready.copy(draft = draft.copy(status = PrivateActivityStatus.Rejected)))
                throw error
            }
            throw PrivateWhoopException(PrivateWhoopError.SubmissionUncertain)
        } catch (_: Exception) {
            throw PrivateWhoopException(PrivateWhoopError.SubmissionUncertain)
        }
        if (!runCatching { UUID.fromString(receipt).toString() == receipt }.getOrDefault(false)) {
            throw PrivateWhoopException(PrivateWhoopError.SubmissionUncertain)
        }
        val submitted = draft.copy(status = PrivateActivityStatus.Submitted, activityId = receipt)
        try {
            store.save(ready.copy(draft = submitted))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw PrivateWhoopException(PrivateWhoopError.SubmissionUncertain)
        }
        submitted
    }

    private suspend fun acceptLogin(result: PrivateWhoopLoginResult) {
        if (result is PrivateWhoopLoginResult.Authenticated) acceptSession(result.session)
    }

    private suspend fun acceptSession(session: PrivateWhoopSession) {
        requirePrivate(session.expiresAtEpochSeconds > clock().epochSecond + 60)
        client.checkAccount(session)
        val previous = store.load()
        // A draft prepared under a previous account must not migrate to another account.
        store.save(previous.copy(session = session, draft = previous.draft?.takeUnless { it.status == PrivateActivityStatus.Prepared }))
    }

    private suspend fun readySession(state: PrivateWhoopState): PrivateWhoopState {
        val current = state.session ?: throw PrivateWhoopException(PrivateWhoopError.ReconnectRequired)
        if (current.expiresAtEpochSeconds > clock().epochSecond + 60) return state
        return refreshState(state)
    }

    private suspend fun authorizedState(state: PrivateWhoopState): PrivateWhoopState {
        val ready = readySession(state)
        try {
            client.checkAccount(ready.session!!)
            return ready
        } catch (error: PrivateWhoopException) {
            if (error.reason != PrivateWhoopError.Unauthorized) throw error
            if (ready !== state) throw PrivateWhoopException(PrivateWhoopError.ReconnectRequired)
        }
        // A rejected GET may refresh once. Never run this retry around an activity POST.
        val refreshed = refreshState(ready)
        try {
            client.checkAccount(refreshed.session!!)
        } catch (error: PrivateWhoopException) {
            if (error.reason == PrivateWhoopError.Unauthorized) {
                throw PrivateWhoopException(PrivateWhoopError.ReconnectRequired)
            }
            throw error
        }
        return refreshed
    }

    private suspend fun refreshState(state: PrivateWhoopState): PrivateWhoopState {
        val current = state.session ?: throw PrivateWhoopException(PrivateWhoopError.ReconnectRequired)
        val replacement = try {
            client.refresh(current)
        } catch (error: PrivateWhoopException) {
            if (error.reason == PrivateWhoopError.Unauthorized || error.reason == PrivateWhoopError.Rejected) {
                throw PrivateWhoopException(PrivateWhoopError.ReconnectRequired)
            }
            throw error
        }
        requirePrivate(replacement.expiresAtEpochSeconds > clock().epochSecond + 60)
        return state.copy(session = replacement).also { store.save(it) }
    }

    private companion object {
        // Guards shared DataStore records even if a screen accidentally constructs a second service.
        val operations = Mutex()
        val DEFINITE_REJECTIONS = setOf(PrivateWhoopError.InvalidInput, PrivateWhoopError.Unauthorized,
            PrivateWhoopError.Rejected, PrivateWhoopError.RateLimited)
    }
}
