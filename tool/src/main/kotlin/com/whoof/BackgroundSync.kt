package com.whoof

import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import com.whoof.privateapi.PrivateWhoopError
import com.whoof.privateapi.PrivateWhoopException
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException

internal const val BACKGROUND_SYNC_KEY = "whoof-sync"

/** WorkManager clamps anything under 15 minutes; the system still decides the exact run time. */
internal val BACKGROUND_SYNC_INTERVAL = 30.minutes

/** The schedule for [BACKGROUND_SYNC_KEY], kept behind an interface so it is testable on the JVM. */
internal interface BackgroundSync {
    /** Ensures exactly one periodic schedule exists. Safe to call repeatedly. */
    fun schedule()

    fun cancel()
}

internal object NoBackgroundSync : BackgroundSync {
    override fun schedule() = Unit

    override fun cancel() = Unit
}

/** Unique periodic work through the Light SDK; re-enqueueing updates the existing schedule. */
internal class LightWorkBackgroundSync(private val context: SealedLightContext) : BackgroundSync {
    override fun schedule() = guarded {
        LightWork.enqueuePeriodic(context, BACKGROUND_SYNC_KEY, BACKGROUND_SYNC_INTERVAL)
    }

    override fun cancel() = guarded { LightWork.cancel(context, BACKGROUND_SYNC_KEY) }

    // Scheduling is best effort: a failure must never break sign-in, and nothing is logged.
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
    }
}

/**
 * One background run. It goes through the same repository as Today, so the synchronizer lock and
 * the account session's single-flight token refresh apply and a foreground and background sync
 * never overlap.
 */
internal suspend fun runBackgroundSync(repository: WhoofRepository): LightJobResult {
    try {
        if (!repository.isSignedIn()) return LightJobResult.Success()
        repository.sync()
        return LightJobResult.Success()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: PrivateWhoopException) {
        // Reconnect, rate limit, and other definite failures wait for the next interval; Today
        // shows SIGN IN when the session is gone. Only an unreachable service is worth a backoff retry.
        return if (error.reason == PrivateWhoopError.Unavailable) LightJobResult.Retry else LightJobResult.Success()
    } catch (error: WhoopApiException) {
        return if (error.reason == WhoopApiError.Unavailable) LightJobResult.Retry else LightJobResult.Success()
    } catch (error: Exception) {
        return if (error.isOffline()) LightJobResult.Retry else LightJobResult.Success()
    }
}

@LightJob("whoof-sync")
val whoofBackgroundSync: LightJobHandler = { context, _ ->
    runBackgroundSync(DefaultWhoofRepository.getInstance(context))
}
