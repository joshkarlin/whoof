package com.whoof.privateapi

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WhoopPrivateSignInErrorTest {
    private val expected = mapOf(
        "NotAuthorizedException" to PrivateWhoopError.CredentialsRejected,
        "UserNotFoundException" to PrivateWhoopError.AccountNotFound,
        "PasswordResetRequiredException" to PrivateWhoopError.PasswordResetRequired,
        "UserNotConfirmedException" to PrivateWhoopError.AccountNotConfirmed,
        "CodeMismatchException" to PrivateWhoopError.WrongCode,
        "ExpiredCodeException" to PrivateWhoopError.CodeExpired,
        "TooManyRequestsException" to PrivateWhoopError.TooManyAttempts,
        "LimitExceededException" to PrivateWhoopError.TooManyAttempts,
    )

    @Test
    fun loginMapsKnownCognitoTypesIncludingPrefixedForms() = runTest {
        expected.forEach { (type, reason) ->
            listOf(type, "com.amazonaws.cognito#$type", "#$type").forEach { wire ->
                val body = """{"__type":"$wire","message":"secret free text user@example.test"}"""
                val error = assertFailsWith<PrivateWhoopException> {
                    client(400, body).beginLogin("user@example.test", "synthetic-password")
                }
                assertEquals(reason, error.reason)
                assertEquals(reason.name, error.message)
                assertNull(error.cause)
                assertFalse(error.toString().contains("secret"))
            }
        }
    }

    @Test
    fun codeStepUsesTheSameMapping() = runTest {
        val challenge = PrivateWhoopChallenge("SMS_MFA", "synthetic-challenge-session", "synthetic-user")
        val error = assertFailsWith<PrivateWhoopException> {
            client(400, """{"__type":"CodeMismatchException","message":"x"}""").completeLogin(challenge, "123456")
        }
        assertEquals(PrivateWhoopError.WrongCode, error.reason)
    }

    @Test
    fun unknownMissingOrMalformedTypesFallBackToStatus() = runTest {
        val cases = listOf(
            Triple(401, """{"__type":"SomethingNewException"}""", PrivateWhoopError.Unauthorized),
            Triple(401, "not json", PrivateWhoopError.Unauthorized),
            Triple(401, """{"message":"no type"}""", PrivateWhoopError.Unauthorized),
            Triple(400, """{"__type":42}""", PrivateWhoopError.Rejected),
            Triple(429, "{}", PrivateWhoopError.RateLimited),
            Triple(500, """{"__type":"NotAuthorizedException"}""", PrivateWhoopError.Unavailable),
        )
        cases.forEach { (status, body, reason) ->
            val error = assertFailsWith<PrivateWhoopException> {
                client(status, body).beginLogin("user@example.test", "synthetic-password")
            }
            assertEquals(reason, error.reason, "status $status")
        }
    }

    @Test
    fun refreshDoesNotUseSignInMapping() = runTest {
        val session = PrivateWhoopSession("synthetic-access-token-123456", "synthetic-refresh-token-123456", NOW + 3600)
        val error = assertFailsWith<PrivateWhoopException> {
            client(400, """{"__type":"NotAuthorizedException"}""").refresh(session)
        }
        assertEquals(PrivateWhoopError.Rejected, error.reason)
    }

    private fun client(status: Int, body: String) = WhoopPrivateClient(object : PrivateRequestExecutor {
        override suspend fun execute(request: PrivateHttpRequest) = PrivateHttpResponse(status, body.encodeToByteArray())
    }, { NOW })

    private companion object {
        const val NOW = 1_798_700_000L
    }
}
