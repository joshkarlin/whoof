package com.whoof

import com.thelightphone.sdk.LightJobResult
import com.whoof.privateapi.PrivateWhoopError
import com.whoof.privateapi.PrivateWhoopException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class BackgroundSyncTest {
    @Test
    fun signedOutDoesNothingAndSucceeds() = runTest {
        val repository = JobRepository(signedIn = false)
        assertIs<LightJobResult.Success>(runBackgroundSync(repository))
        assertEquals(0, repository.syncs)
    }

    @Test
    fun signedInSyncsAndSucceeds() = runTest {
        val repository = JobRepository()
        assertIs<LightJobResult.Success>(runBackgroundSync(repository))
        assertEquals(1, repository.syncs)
    }

    @Test
    fun sessionLossAndRateLimitingWaitForTheNextInterval() = runTest {
        listOf<Exception>(
            PrivateWhoopException(PrivateWhoopError.ReconnectRequired),
            PrivateWhoopException(PrivateWhoopError.Unauthorized),
            PrivateWhoopException(PrivateWhoopError.RateLimited),
            PrivateWhoopException(PrivateWhoopError.StorageFailed),
            WhoopApiException(WhoopApiError.Unauthorized),
            WhoopApiException(WhoopApiError.RateLimited),
            WhoopApiException(WhoopApiError.InvalidRequest),
            IllegalStateException("synthetic"),
        ).forEach { failure ->
            assertIs<LightJobResult.Success>(runBackgroundSync(JobRepository(failure = failure)), failure.toString())
        }
    }

    @Test
    fun transientFailuresRetryWithBackoff() = runTest {
        listOf<Exception>(
            PrivateWhoopException(PrivateWhoopError.Unavailable),
            WhoopApiException(WhoopApiError.Unavailable),
            IOException("offline"),
            IllegalStateException("wrapped", IOException("offline")),
        ).forEach { failure ->
            assertEquals(LightJobResult.Retry, runBackgroundSync(JobRepository(failure = failure)), failure.toString())
        }
    }

    @Test
    fun cancellationPropagates() = runTest {
        assertFailsWith<CancellationException> { runBackgroundSync(JobRepository(failure = CancellationException("stop"))) }
    }

    private class JobRepository(
        private val signedIn: Boolean = true,
        private val failure: Exception? = null,
    ) : WhoofRepository {
        var syncs = 0
        override val days: Flow<List<DaySummary>> = MutableStateFlow(emptyList())
        override val lastSuccessfulSyncMillis: Flow<Long?> = MutableStateFlow(null)
        override suspend fun isSignedIn() = signedIn
        override suspend fun sync() {
            syncs++
            failure?.let { throw it }
        }
        override suspend fun addPendingActivity(activity: PendingActivityEntity) = Unit
        override suspend fun removeCachedWorkout(id: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun clearForNewAccount() = Unit
        override suspend fun deleteLocalData() = Unit
    }
}
