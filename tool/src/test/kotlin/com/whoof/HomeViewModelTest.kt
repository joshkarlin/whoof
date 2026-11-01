package com.whoof

import com.whoof.privateapi.PrivateWhoopError
import com.whoof.privateapi.PrivateWhoopException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Test
    fun reportsNotConnectedWhenSignedOut() = homeTest(FakeRepository(connected = false)) { viewModel, _ ->
        viewModel.start()
        advanceUntilIdle()

        assertEquals(Connection.NotConnected, viewModel.state.value.connection)
    }

    @Test
    fun reportsSyncingWhileRequestIsPending() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = FakeRepository(beforeSync = {
            started.complete(Unit)
            release.await()
        })
        homeTest(repository) { viewModel, _ ->
            viewModel.start()
            runCurrent()

            started.await()
            assertEquals(SyncStatus.Syncing, viewModel.state.value.sync)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(SyncStatus.Idle, viewModel.state.value.sync)
        }
    }

    @Test
    fun exposesCachedDaysAndLastSync() = homeTest(FakeRepository(days = listOf(day(1)))) { viewModel, repository ->
        viewModel.start()
        advanceUntilIdle()

        assertEquals(listOf(day(1)), viewModel.state.value.days)
        assertEquals(repository.syncedAt, viewModel.state.value.lastSyncMillis)
    }

    @Test
    fun keepsCachedDaysWhenSyncIsOffline() {
        val repository = FakeRepository(days = listOf(day(1)), syncFailure = IOException("offline"))
        homeTest(repository) { viewModel, _ ->
            viewModel.start()
            advanceUntilIdle()

            assertEquals(SyncStatus.Offline, viewModel.state.value.sync)
            assertEquals(listOf(day(1)), viewModel.state.value.days)
        }
    }

    @Test
    fun reportsAuthorizationExpiredWhenReconnectIsRequired() {
        val repository = FakeRepository(syncFailure = PrivateWhoopException(PrivateWhoopError.ReconnectRequired))
        homeTest(repository) { viewModel, _ ->
            viewModel.start()
            advanceUntilIdle()

            assertEquals(SyncStatus.AuthorizationExpired, viewModel.state.value.sync)
        }
    }

    @Test
    fun reportsRepresentativeApiError() {
        val repository = FakeRepository(syncFailure = WhoopApiException(WhoopApiError.RateLimited))
        homeTest(repository) { viewModel, _ ->
            viewModel.start()
            advanceUntilIdle()

            assertEquals(
                SyncStatus.Failed("WHOOP rate limit reached. Try again later.", rateLimited = true),
                viewModel.state.value.sync,
            )
        }
    }

    @Test
    fun signedOutTodayDoesNotSyncAndOffersSignIn() = homeTest(FakeRepository(connected = false)) { viewModel, repository ->
        viewModel.start()
        advanceUntilIdle()
        viewModel.sync()
        viewModel.syncIfStale()
        advanceUntilIdle()

        assertEquals(Connection.NotConnected, viewModel.state.value.connection)
        assertEquals(0, repository.syncCount)
    }

    @Test
    fun signingInAfterReturningSyncsAndClearsExpiredState() {
        val repository = FakeRepository(syncFailure = PrivateWhoopException(PrivateWhoopError.ReconnectRequired))
        homeTest(repository) { viewModel, _ ->
            viewModel.start()
            advanceUntilIdle()
            assertEquals(SyncStatus.AuthorizationExpired, viewModel.state.value.sync)

            repository.syncFailure = null
            viewModel.onSignedIn()
            advanceUntilIdle()

            assertEquals(2, repository.syncCount)
            assertEquals(SyncStatus.Idle, viewModel.state.value.sync)
        }
    }

    @Test
    fun recheckPicksUpASignInDoneElsewhere() = homeTest(FakeRepository(connected = false)) { viewModel, repository ->
        viewModel.start()
        advanceUntilIdle()

        repository.connected = true
        viewModel.recheckSignIn()
        advanceUntilIdle()

        assertEquals(Connection.Connected, viewModel.state.value.connection)
        assertEquals(1, repository.syncCount)
    }

    @Test
    fun recheckShowsSignedOutAfterSigningOutElsewhere() = homeTest(FakeRepository()) { viewModel, repository ->
        viewModel.start()
        advanceUntilIdle()

        repository.signOut()
        viewModel.recheckSignIn()
        advanceUntilIdle()

        assertEquals(Connection.NotConnected, viewModel.state.value.connection)
        assertEquals(emptyList(), viewModel.state.value.days)
    }

    @Test
    fun deleteLocalDataReturnsToSignedOutState() =
        homeTest(FakeRepository(days = listOf(day(1)))) { viewModel, repository ->
            viewModel.start()
            advanceUntilIdle()

            viewModel.deleteLocalData()
            advanceUntilIdle()

            assertTrue(repository.deleted)
            assertEquals(Connection.NotConnected, viewModel.state.value.connection)
            assertEquals(emptyList(), viewModel.state.value.days)
            assertNull(viewModel.state.value.lastSyncMillis)
        }

    @Test
    fun ignoresSyncRequestsWhileOneIsRunning() {
        val release = CompletableDeferred<Unit>()
        val repository = FakeRepository(beforeSync = { release.await() })
        homeTest(repository) { viewModel, _ ->
            viewModel.start()
            runCurrent()
            viewModel.sync()
            viewModel.sync()
            release.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, repository.syncCount)
        }
    }

    @Test
    fun deleteStopsAnInFlightSyncBeforeDeleting() {
        val repository = FakeRepository(beforeSync = { CompletableDeferred<Unit>().await() })
        homeTest(repository) { viewModel, _ ->
            viewModel.start()
            runCurrent()
            assertEquals(SyncStatus.Syncing, viewModel.state.value.sync)

            viewModel.deleteLocalData()
            advanceUntilIdle()

            assertTrue(repository.deleted)
            assertEquals(SyncStatus.Idle, viewModel.state.value.sync)
            assertEquals(Connection.NotConnected, viewModel.state.value.connection)
        }
    }

    @Test
    fun returningToTodayBacksOffAfterFailures() {
        val repository = FakeRepository(syncFailure = IOException("offline"))
        homeTest(repository) { viewModel, _ ->
            viewModel.start()
            advanceUntilIdle()
            assertEquals(1, repository.syncCount)

            viewModel.syncIfStale()
            advanceUntilIdle()
            assertEquals(1, repository.syncCount)

            clock += 5 * MINUTE
            viewModel.syncIfStale()
            advanceUntilIdle()
            assertEquals(2, repository.syncCount)

            clock += 5 * MINUTE
            viewModel.syncIfStale()
            advanceUntilIdle()
            assertEquals(2, repository.syncCount)

            clock += 5 * MINUTE
            viewModel.syncIfStale()
            advanceUntilIdle()
            assertEquals(3, repository.syncCount)
        }
    }

    @Test
    fun rateLimitHoldsAutomaticSyncForThirtyMinutesButNotExplicitSync() {
        val repository = FakeRepository(syncFailure = WhoopApiException(WhoopApiError.RateLimited))
        homeTest(repository) { viewModel, _ ->
            viewModel.start()
            advanceUntilIdle()

            clock += 29 * MINUTE
            viewModel.syncIfStale()
            advanceUntilIdle()
            assertEquals(1, repository.syncCount)

            viewModel.sync()
            advanceUntilIdle()
            assertEquals(2, repository.syncCount)
        }
    }

    @Test
    fun failureBackoffDoublesUpToOneHour() {
        assertEquals(5 * MINUTE, failureBackoffMillis(1))
        assertEquals(10 * MINUTE, failureBackoffMillis(2))
        assertEquals(40 * MINUTE, failureBackoffMillis(4))
        assertEquals(60 * MINUTE, failureBackoffMillis(5))
        assertEquals(60 * MINUTE, failureBackoffMillis(50))
    }

    private var clock = NOW_MILLIS

    private fun homeTest(
        repository: FakeRepository,
        block: suspend TestScope.(HomeViewModel, FakeRepository) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            block(HomeViewModel(repository, dispatcher) { clock }, repository)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class FakeRepository(
    var connected: Boolean = true,
    days: List<DaySummary> = emptyList(),
    var syncFailure: Exception? = null,
    private val beforeSync: (suspend () -> Unit)? = null,
) : WhoofRepository {
    val syncedAt = NOW_MILLIS
    private val daysFlow = MutableStateFlow(days)
    private val lastSyncFlow = MutableStateFlow<Long?>(null)
    var syncCount = 0
    var deleted = false

    override val days: Flow<List<DaySummary>> = daysFlow
    override val lastSuccessfulSyncMillis: Flow<Long?> = lastSyncFlow

    override suspend fun isSignedIn(): Boolean = connected

    override suspend fun sync() {
        syncCount++
        beforeSync?.invoke()
        syncFailure?.let { throw it }
        lastSyncFlow.value = syncedAt
    }

    val removed = mutableListOf<String>()

    override suspend fun addPendingActivity(activity: PendingActivityEntity) = Unit

    override suspend fun removeCachedWorkout(id: String) {
        removed += id
    }

    override suspend fun signOut() {
        connected = false
        daysFlow.value = emptyList()
        lastSyncFlow.value = null
    }

    override suspend fun deleteLocalData() {
        deleted = true
        connected = false
        daysFlow.value = emptyList()
        lastSyncFlow.value = null
    }
}

private fun day(cycleId: Long) = DaySummary(
    cycleId = cycleId,
    date = LocalDate.of(2026, 11, 1),
    cycleState = ScoreState.Scored,
    strain = 12.4,
    recovery = null,
    sleep = null,
    activities = emptyList(),
    updatedMillis = 0,
)

private const val NOW_MILLIS = 1_793_500_000_000L
private const val MINUTE = 60_000L
