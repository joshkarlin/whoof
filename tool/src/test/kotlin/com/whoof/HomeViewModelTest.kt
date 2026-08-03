package com.whoof

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Test
    fun reportsLoadingWhileRecoveryRequestIsPending() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val requestStarted = CompletableDeferred<Unit>()
            val releaseRequest = CompletableDeferred<Unit>()
            val api = FakeRecoveryClient(
                beforeRecovery = {
                    requestStarted.complete(Unit)
                    releaseRequest.await()
                },
            )
            val viewModel = HomeViewModel(HomeMemoryCredentialStore(credentials()), api, dispatcher) { NOW }

            viewModel.start()
            runCurrent()

            requestStarted.await()
            assertEquals(HomeState.Loading, viewModel.state.value)
            releaseRequest.complete(Unit)
            advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun reportsNoDataForAnEmptyRecoveryResponse() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = HomeViewModel(
                HomeMemoryCredentialStore(credentials()),
                FakeRecoveryClient(recovery = null),
                dispatcher,
                { NOW },
            )

            viewModel.start()
            advanceUntilIdle()

            assertEquals(HomeState.NoData, viewModel.state.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun readyStateBuildsTodayModelFromRecoveryCycleAndSleep() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = HomeViewModel(
                HomeMemoryCredentialStore(credentials()),
                FakeRecoveryClient(
                    cycle = cycle(strain = 8.4),
                    sleep = sleep(performance = 91.0),
                ),
                dispatcher,
                { NOW },
            )

            viewModel.start()
            advanceUntilIdle()

            val ready = viewModel.state.value as HomeState.Ready
            assertEquals(1L, ready.today.recovery?.cycleId)
            assertEquals(8.4, ready.today.strain)
            assertEquals(91.0, ready.today.sleep)
            assertEquals(NOW, ready.today.syncedAt)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun reportsAuthorizationExpiredWhenRefreshRequiresReprovisioning() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = HomeViewModel(
                HomeMemoryCredentialStore(credentials()),
                FakeRecoveryClient(
                    recoveryFailure = WhoopApiException(WhoopApiError.Unauthorized),
                    refreshFailure = WhoopApiException(WhoopApiError.Unauthorized),
                ),
                dispatcher,
                { NOW },
            )

            viewModel.start()
            advanceUntilIdle()

            assertEquals(HomeState.AuthorizationExpired, viewModel.state.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun reportsOfflineForAnIOException() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = HomeViewModel(
                HomeMemoryCredentialStore(credentials()),
                FakeRecoveryClient(recoveryFailure = IOException("network unavailable")),
                dispatcher,
                { NOW },
            )

            viewModel.start()
            advanceUntilIdle()

            assertEquals(HomeState.Offline, viewModel.state.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun reportsRepresentativeApiError() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = HomeViewModel(
                HomeMemoryCredentialStore(credentials()),
                FakeRecoveryClient(recoveryFailure = WhoopApiException(WhoopApiError.MissingScope)),
                dispatcher,
                { NOW },
            )

            viewModel.start()
            advanceUntilIdle()

            assertEquals(
                HomeState.ApiError("Authorization is missing a required WHOOP scope.", true),
                viewModel.state.value,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class FakeRecoveryClient(
    private val recovery: RecoveryRecord? = RecoveryRecord(
        cycleId = 1,
        sleepId = "sleep-id",
        userId = 2,
        createdAt = "created",
        updatedAt = "updated",
        scoreState = "SCORED",
        score = null,
    ),
    private val recoveryFailure: Throwable? = null,
    private val refreshFailure: Throwable? = null,
    private val beforeRecovery: (suspend () -> Unit)? = null,
    private val cycle: CycleRecord? = cycle(strain = null),
    private val sleep: SleepRecord? = sleep(performance = null),
) : WhoopRecoveryClient {
    override suspend fun latestRecovery(accessToken: String): RecoveryRecord? {
        beforeRecovery?.invoke()
        recoveryFailure?.let { throw it }
        return recovery
    }

    override suspend fun refresh(credentials: WhoopCredentials): WhoopTokenRefreshResponse {
        refreshFailure?.let { throw it }
        error("Refresh was not expected in this test.")
    }

    override suspend fun latestCycle(accessToken: String): CycleRecord? = cycle

    override suspend fun sleepForCycle(accessToken: String, cycleId: Long): SleepRecord? = sleep

    override fun close() = Unit
}

private fun cycle(strain: Double?) = CycleRecord(
    id = 1,
    userId = 2,
    createdAt = "created",
    updatedAt = "updated",
    start = "start",
    timezoneOffset = "+00:00",
    scoreState = if (strain == null) "PENDING_SCORE" else "SCORED",
    score = strain?.let { CycleScore(it) },
)

private fun sleep(performance: Double?) = SleepRecord(
    id = "sleep-id",
    cycleId = 1,
    userId = 2,
    createdAt = "created",
    updatedAt = "updated",
    start = "start",
    end = "end",
    timezoneOffset = "+00:00",
    nap = false,
    scoreState = if (performance == null) "PENDING_SCORE" else "SCORED",
    score = performance?.let { SleepScore(sleepPerformancePercentage = it) },
)

private const val NOW = "2026-08-02T22:00:00Z"

private class HomeMemoryCredentialStore(
    private var credentials: WhoopCredentials?,
) : WhoopCredentialStore {
    override suspend fun load(): WhoopCredentials? = credentials

    override suspend fun save(credentials: WhoopCredentials) {
        this.credentials = credentials
    }

    override suspend fun clear() {
        credentials = null
    }
}

private fun credentials() = WhoopCredentials(
    accessToken = "access-token-placeholder-12345",
    clientId = "client-id-placeholder",
    clientSecret = "client-secret-placeholder",
    refreshToken = "refresh-token-placeholder-12345",
    expiresIn = 3_600,
)
