package com.whoof

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes WHOOP refresh-token rotation and bounds a request to one retry. */
internal class WhoopTokenCoordinator(
    private val store: WhoopCredentialStore,
    private val refresher: WhoopTokenRefresher,
) {
    private val refreshMutex = Mutex()

    suspend fun <T> execute(request: suspend (String) -> T): T {
        val observed = store.load() ?: throw WhoopReprovisioningException()
        return try {
            request(observed.accessToken)
        } catch (error: WhoopApiException) {
            if (error.reason != WhoopApiError.Unauthorized) throw error

            val replacement = refreshIfStillCurrent(observed)
            try {
                request(replacement.accessToken)
            } catch (retryError: WhoopApiException) {
                if (retryError.reason == WhoopApiError.Unauthorized) {
                    throw WhoopReprovisioningException()
                }
                throw retryError
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }

    private suspend fun refreshIfStillCurrent(observed: WhoopCredentials): WhoopCredentials =
        refreshMutex.withLock {
            val current = store.load() ?: throw WhoopReprovisioningException()
            if (current.accessToken != observed.accessToken) return@withLock current

            val refreshed = try {
                refresher.refresh(current)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: WhoopApiException) {
                if (error.reason == WhoopApiError.Unauthorized ||
                    error.reason == WhoopApiError.InvalidRequest
                ) {
                    throw WhoopReprovisioningException(error)
                }
                throw error
            } catch (error: Exception) {
                throw error
            }
            val replacement = try {
                current.copy(
                    accessToken = refreshed.accessToken,
                    refreshToken = refreshed.refreshToken,
                    expiresIn = refreshed.expiresIn,
                )
            } catch (error: IllegalArgumentException) {
                throw WhoopReprovisioningException(error)
            }

            try {
                store.save(replacement)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                throw WhoopReprovisioningException(error)
            }
            replacement
        }
}
