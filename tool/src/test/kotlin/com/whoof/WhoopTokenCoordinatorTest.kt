package com.whoof

import io.ktor.http.Parameters
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class WhoopTokenCoordinatorTest {
    @Test
    fun refreshFormUsesTheDocumentedOfflineFields() {
        val parameters = buildRefreshParameters(credentials())

        assertEquals(
            Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", "refresh-token-placeholder-12345")
                append("client_id", "client-id-placeholder")
                append("client_secret", "client-secret-placeholder")
                append("scope", "offline")
            },
            parameters,
        )
    }

    @Test
    fun refreshRotatesTokensBeforeRetryAndPreservesClientCredentials() = runBlocking {
        val refresher = FakeRefresher(
            WhoopTokenRefreshResponse(
                accessToken = "replacement-access-token-12345",
                refreshToken = "replacement-refresh-token-12345",
                expiresIn = 7_200,
            ),
        )
        val events = mutableListOf<String>()
        val trackedStore = MemoryCredentialStore(credentials(), events)
        val trackedCoordinator = WhoopTokenCoordinator(trackedStore, refresher)

        val result = trackedCoordinator.execute { accessToken ->
            events += "request:$accessToken"
            if (accessToken == "access-token-placeholder-12345") {
                throw WhoopApiException(WhoopApiError.Unauthorized)
            }
            accessToken
        }

        assertEquals("replacement-access-token-12345", result)
        assertEquals(1, refresher.calls)
        assertEquals(
            listOf(
                "request:access-token-placeholder-12345",
                "save:replacement-access-token-12345/replacement-refresh-token-12345",
                "request:replacement-access-token-12345",
            ),
            events,
        )
        assertEquals("client-id-placeholder", trackedStore.current.clientId)
        assertEquals("client-secret-placeholder", trackedStore.current.clientSecret)
        assertEquals("replacement-refresh-token-12345", trackedStore.current.refreshToken)
    }

    @Test
    fun concurrentUnauthorizedRequestsPerformOneRefreshAndReuseTheRotatedPair() = runBlocking {
        val store = MemoryCredentialStore(credentials())
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val refresher = FakeRefresher(
            response = WhoopTokenRefreshResponse(
                accessToken = "replacement-access-token-12345",
                refreshToken = "replacement-refresh-token-12345",
                expiresIn = 7_200,
            ),
            beforeResponse = {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
            },
        )
        val coordinator = WhoopTokenCoordinator(store, refresher)

        val calls = AtomicInteger(0)
        suspend fun request(accessToken: String): String {
            calls.incrementAndGet()
            if (accessToken == "access-token-placeholder-12345") {
                throw WhoopApiException(WhoopApiError.Unauthorized)
            }
            return accessToken
        }

        val first = async { coordinator.execute(::request) }
        refreshStarted.await()
        val second = async { coordinator.execute(::request) }
        releaseRefresh.complete(Unit)

        assertEquals(
            listOf(
                "replacement-access-token-12345",
                "replacement-access-token-12345",
            ),
            listOf(first.await(), second.await()).sorted(),
        )
        assertEquals(1, refresher.calls)
        assertEquals(4, calls.get())
    }

    @Test
    fun secondUnauthorizedResponseRequiresReprovisioningWithoutAnotherRefresh() = runBlocking {
        val store = MemoryCredentialStore(credentials())
        val refresher = FakeRefresher(
            WhoopTokenRefreshResponse(
                accessToken = "replacement-access-token-12345",
                refreshToken = "replacement-refresh-token-12345",
                expiresIn = 7_200,
            ),
        )
        val coordinator = WhoopTokenCoordinator(store, refresher)

        assertFailsWith<WhoopReprovisioningException> {
            coordinator.execute {
                throw WhoopApiException(WhoopApiError.Unauthorized)
            }
        }
        assertEquals(1, refresher.calls)
    }

    @Test
    fun refreshFailureRequiresReprovisioningAndLeavesStoredPairUntouched() = runBlocking {
        val original = credentials()
        val store = MemoryCredentialStore(original)
        val refresher = FakeRefresher(failure = WhoopApiException(WhoopApiError.InvalidRequest))
        val coordinator = WhoopTokenCoordinator(store, refresher)

        assertFailsWith<WhoopReprovisioningException> {
            coordinator.execute {
                throw WhoopApiException(WhoopApiError.Unauthorized)
            }
        }
        assertEquals(original, store.current)
    }

    @Test
    fun transientRefreshFailureRemainsRetryableAndLeavesStoredPairUntouched() = runBlocking {
        val original = credentials()
        val store = MemoryCredentialStore(original)
        val refresher = FakeRefresher(failure = WhoopApiException(WhoopApiError.Unavailable))
        val coordinator = WhoopTokenCoordinator(store, refresher)

        val error = assertFailsWith<WhoopApiException> {
            coordinator.execute {
                throw WhoopApiException(WhoopApiError.Unauthorized)
            }
        }

        assertEquals(WhoopApiError.Unavailable, error.reason)
        assertEquals(original, store.current)
    }

    @Test
    fun nonUnauthorizedApiErrorsAreNotRetried() = runBlocking {
        val store = MemoryCredentialStore(credentials())
        val refresher = FakeRefresher(
            WhoopTokenRefreshResponse(
                accessToken = "replacement-access-token-12345",
                refreshToken = "replacement-refresh-token-12345",
                expiresIn = 7_200,
            ),
        )
        val coordinator = WhoopTokenCoordinator(store, refresher)
        val error = assertFailsWith<WhoopApiException> {
            coordinator.execute {
                throw WhoopApiException(WhoopApiError.RateLimited)
            }
        }

        assertEquals(WhoopApiError.RateLimited, error.reason)
        assertEquals(0, refresher.calls)
    }

    private fun credentials() = WhoopCredentials(
        accessToken = "access-token-placeholder-12345",
        clientId = "client-id-placeholder",
        clientSecret = "client-secret-placeholder",
        refreshToken = "refresh-token-placeholder-12345",
        expiresIn = 3_600,
    )
}

private class MemoryCredentialStore(
    initial: WhoopCredentials,
    private val events: MutableList<String> = mutableListOf(),
) : WhoopCredentialStore {
    var current: WhoopCredentials = initial
        private set
    var saveEvents: MutableList<String> = mutableListOf()

    override suspend fun load(): WhoopCredentials = current

    override suspend fun save(credentials: WhoopCredentials) {
        current = credentials
        events += "save:${credentials.accessToken}/${credentials.refreshToken}"
    }

    override suspend fun clear() = Unit
}

private class FakeRefresher(
    private val response: WhoopTokenRefreshResponse? = null,
    private val failure: Exception? = null,
    private val beforeResponse: (suspend () -> Unit)? = null,
) : WhoopTokenRefresher {
    var calls = 0
        private set

    override suspend fun refresh(credentials: WhoopCredentials): WhoopTokenRefreshResponse {
        calls++
        beforeResponse?.invoke()
        failure?.let { throw it }
        return response ?: error("Test refresher has no response.")
    }
}
