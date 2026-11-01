package com.whoof

import com.whoof.privateapi.PrivateActivityPayload
import com.whoof.privateapi.PrivateWhoopChallenge
import com.whoof.privateapi.PrivateWhoopError
import com.whoof.privateapi.PrivateWhoopException
import com.whoof.privateapi.PrivateWhoopLoginResult
import com.whoof.privateapi.PrivateWhoopSession
import com.whoof.privateapi.PrivateWhoopState
import com.whoof.privateapi.PrivateWhoopStore
import com.whoof.privateapi.WhoopPrivateService
import com.whoof.privateapi.WhoopPrivateTransport
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    @Test
    fun badPasswordShowsMappedMessageKeepsEmailAndClearsPassword() = signIn(
        FakeTransport(login = { throw PrivateWhoopException(PrivateWhoopError.CredentialsRejected) }),
    ) { vm, transport ->
        vm.setEmail("user@example.test"); vm.setPassword("synthetic-password")
        vm.submit()
        assertTrue(vm.state.value.busy)
        vm.submit() // double tap while in flight
        advanceUntilIdle()
        assertEquals(1, transport.logins)
        assertEquals("Email or password not accepted.", vm.state.value.error)
        assertEquals("user@example.test", vm.state.value.email)
        assertEquals("", vm.state.value.password)
        assertTrue(!vm.state.value.busy && !vm.state.value.done)
    }

    @Test
    fun verificationThenCodeCompletesAndClearsSecrets() = signIn(
        FakeTransport(
            login = { PrivateWhoopLoginResult.VerificationRequired(CHALLENGE) },
            complete = { PrivateWhoopLoginResult.Authenticated(SESSION) },
        ),
    ) { vm, _ ->
        vm.setEmail("user@example.test"); vm.setPassword("synthetic-password")
        vm.submit(); advanceUntilIdle()
        assertEquals(SignInStage.Code, vm.state.value.stage)
        assertEquals("", vm.state.value.password)
        vm.setCode("123456"); vm.submit(); advanceUntilIdle()
        assertTrue(vm.state.value.done)
        assertEquals("", vm.state.value.code)
    }

    @Test
    fun wrongCodeStaysOnCodeStageAndExpiredReturnsToCredentials() = signIn(
        FakeTransport(
            login = { PrivateWhoopLoginResult.VerificationRequired(CHALLENGE) },
            complete = { throw PrivateWhoopException(PrivateWhoopError.WrongCode) },
        ),
    ) { vm, transport ->
        vm.setEmail("user@example.test"); vm.setPassword("synthetic-password")
        vm.submit(); advanceUntilIdle()
        vm.setCode("000000"); vm.submit(); advanceUntilIdle()
        assertEquals(SignInStage.Code, vm.state.value.stage)
        assertEquals("Wrong code.", vm.state.value.error)
        transport.complete = { throw PrivateWhoopException(PrivateWhoopError.CodeExpired) }
        vm.setCode("111111"); vm.submit(); advanceUntilIdle()
        assertEquals(SignInStage.Credentials, vm.state.value.stage)
        assertEquals("user@example.test", vm.state.value.email)
    }

    @Test
    fun emptyFieldsDoNotCallWhoop() = signIn(FakeTransport()) { vm, transport ->
        vm.submit(); advanceUntilIdle()
        assertEquals(0, transport.logins)
        assertTrue(vm.state.value.error != null)
        assertNull(vm.state.value.openEditor)
    }

    @Test
    fun scannedPrivateQrConnectsAndGuardsDoubleSubmission() = signIn(FakeTransport()) { vm, transport ->
        vm.provisionScanned(QR)
        assertTrue(vm.state.value.busy)
        assertEquals("CONNECTING", vm.state.value.busyLabel)
        vm.provisionScanned(QR) // second scan while in flight
        vm.submit() // password submit while in flight
        advanceUntilIdle()
        assertEquals(1, transport.checks)
        assertEquals(0, transport.logins)
        assertTrue(vm.state.value.done && !vm.state.value.busy)
        assertNull(vm.state.value.error)
    }

    @Test
    fun invalidQrShowsMessageWithoutCallingWhoopAndAllowsRescan() = signIn(FakeTransport()) { vm, transport ->
        vm.provisionScanned("whoof://provision?v=1&client_id=synthetic")
        advanceUntilIdle()
        assertEquals("Not a Whoof account QR.", vm.state.value.error)
        assertEquals(0, transport.checks)
        assertTrue(!vm.state.value.busy && !vm.state.value.done)
        vm.provisionScanned(QR)
        advanceUntilIdle()
        assertTrue(vm.state.value.done)
        assertNull(vm.state.value.error)
    }

    @Test
    fun rejectedSessionAsksForNewQr() = signIn(
        FakeTransport(check = { throw PrivateWhoopException(PrivateWhoopError.Unauthorized) }),
    ) { vm, _ ->
        vm.provisionScanned(QR)
        advanceUntilIdle()
        assertEquals("Session expired. Make a new QR.", vm.state.value.error)
        assertTrue(!vm.state.value.busy && !vm.state.value.done)
    }

    @Test
    fun otherProvisionErrorsUseSignInMessages() = signIn(
        FakeTransport(check = { throw PrivateWhoopException(PrivateWhoopError.Unavailable) }),
    ) { vm, _ ->
        vm.provisionScanned(QR)
        advanceUntilIdle()
        assertEquals("WHOOP could not be reached.", vm.state.value.error)
    }

    private fun signIn(transport: FakeTransport, block: suspend TestScope.(SignInViewModel, FakeTransport) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val service = WhoopPrivateService(MemoryStore(), transport) { Instant.ofEpochSecond(NOW) }
            block(SignInViewModel(service, dispatcher), transport)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class MemoryStore : PrivateWhoopStore {
        private var state = PrivateWhoopState()
        override suspend fun load() = state
        override suspend fun save(state: PrivateWhoopState) { this.state = state }
        override suspend fun clear() { state = PrivateWhoopState() }
    }

    private class FakeTransport(
        var login: () -> PrivateWhoopLoginResult = { throw IllegalStateException() },
        var complete: () -> PrivateWhoopLoginResult = { throw IllegalStateException() },
        var check: () -> Unit = {},
    ) : WhoopPrivateTransport {
        var logins = 0
        var checks = 0
        override suspend fun beginLogin(email: String, password: String): PrivateWhoopLoginResult { logins++; return login() }
        override suspend fun completeLogin(challenge: PrivateWhoopChallenge, code: String) = complete()
        override suspend fun refresh(session: PrivateWhoopSession) = session
        override suspend fun refreshFromToken(refreshToken: String) = SESSION
        override suspend fun checkAccount(session: PrivateWhoopSession) { checks++; check() }
        override suspend fun createActivity(session: PrivateWhoopSession, payload: PrivateActivityPayload, timeZone: String) =
            throw IllegalStateException()
    }

    private companion object {
        const val NOW = 1_798_700_000L
        const val QR = "whoof://private-provision?v=1&access_token=synthetic-access-token-123456" +
            "&refresh_token=synthetic-refresh-token-123456&expires_at=1798703600"
        val SESSION = PrivateWhoopSession("synthetic-access-token-123456", "synthetic-refresh-token-123456", NOW + 3600)
        val CHALLENGE = PrivateWhoopChallenge("SMS_MFA", "synthetic-challenge-session", "synthetic-user")
    }
}
