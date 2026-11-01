package com.whoof.privateapi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhoopPrivateClientTest {
    @Test
    fun loginUsesFixedAuthEndpointHeadersAndPayload() = runTest {
        val executor = FakeExecutor(response(200, """{"AuthenticationResult":${authResult()}}"""))
        val client = WhoopPrivateClient(executor, { NOW })

        val result = assertIs<PrivateWhoopLoginResult.Authenticated>(client.beginLogin("  user@example.test ", "synthetic-password"))

        assertEquals(PrivateWhoopSession(ACCESS, REFRESH, NOW + 3600), result.session)
        val request = executor.requests.single()
        assertEquals("POST", request.method)
        assertEquals(AUTH_URL, request.url)
        assertEquals("application/x-amz-json-1.1", request.headers["Content-Type"])
        assertEquals("AWSCognitoIdentityProviderService.InitiateAuth", request.headers["X-Amz-Target"])
        assertEquals(AUTH_USER_AGENT, request.headers["User-Agent"])
        assertEquals("attempt=1; max=1", request.headers["amz-sdk-request"])
        assertTrue(request.headers.getValue("amz-sdk-invocation-id").matches(Regex("[0-9a-f-]{36}")))
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("USER_PASSWORD_AUTH", body["AuthFlow"]?.jsonPrimitive?.content)
        assertEquals("", body["ClientId"]?.jsonPrimitive?.content)
        val params = body.getValue("AuthParameters").jsonObject
        assertEquals("user@example.test", params["USERNAME"]?.jsonPrimitive?.content)
        assertEquals("synthetic-password", params["PASSWORD"]?.jsonPrimitive?.content)
    }

    @Test
    fun allAllowlistedMfaTypesMapToTheirExactFields() = runTest {
        val fields = mapOf(
            "SMS_MFA" to "SMS_MFA_CODE",
            "SOFTWARE_TOKEN_MFA" to "SOFTWARE_TOKEN_MFA_CODE",
            "EMAIL_OTP" to "EMAIL_OTP_CODE",
        )
        fields.forEach { (challengeName, expectedField) ->
            val executor = FakeExecutor(response(200, """{"AuthenticationResult":${authResult()}}"""))
            val client = WhoopPrivateClient(executor, { NOW })
            client.completeLogin(PrivateWhoopChallenge(challengeName, "synthetic-challenge-session", "synthetic-user"), "  123456 ")
            val request = executor.requests.single()
            assertEquals(AUTH_URL, request.url)
            assertEquals("AWSCognitoIdentityProviderService.RespondToAuthChallenge", request.headers["X-Amz-Target"])
            val body = Json.parseToJsonElement(request.body!!).jsonObject
            assertEquals(challengeName, body["ChallengeName"]?.jsonPrimitive?.content)
            val fieldsObject = body.getValue("ChallengeResponses").jsonObject
            assertEquals("123456", fieldsObject[expectedField]?.jsonPrimitive?.content)
            assertEquals("synthetic-user", fieldsObject["USERNAME"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun loginChallengeAcceptsOnlySupportedMfaAndDoesNotExposeChallengeSecrets() = runTest {
        val executor = FakeExecutor(response(200, """{"ChallengeName":"EMAIL_OTP","Session":"synthetic-challenge-session","ChallengeParameters":{"USERNAME":"opaque-user"}}"""))
        val challenge = assertIs<PrivateWhoopLoginResult.VerificationRequired>(
            WhoopPrivateClient(executor, { NOW }).beginLogin("user@example.test", "synthetic-password"),
        ).challenge
        assertEquals("EMAIL_OTP", challenge.name)
        assertEquals("opaque-user", challenge.username)
        assertFalse(challenge.toString().contains("synthetic-challenge-session"))

        val unsupported = WhoopPrivateClient(
            FakeExecutor(response(200, """{"ChallengeName":"NEW_MFA","Session":"synthetic-challenge-session"}""")),
            { NOW },
        )
        assertReason(PrivateWhoopError.UnsupportedChallenge) {
            unsupported.beginLogin("user@example.test", "synthetic-password")
        }
    }

    @Test
    fun refreshRetainsRefreshTokenAndValidatesServerExpiryAndTokenBounds() = runTest {
        val executor = FakeExecutor(response(200, """{"AuthenticationResult":{"AccessToken":"$ACCESS","ExpiresIn":120}}"""))
        val result = WhoopPrivateClient(executor, { NOW }).refresh(session())
        assertEquals(PrivateWhoopSession(ACCESS, REFRESH, NOW + 120), result)
        assertEquals("REFRESH_TOKEN_AUTH", Json.parseToJsonElement(executor.requests.single().body!!).jsonObject["AuthFlow"]?.jsonPrimitive?.content)
        assertEquals(REFRESH, Json.parseToJsonElement(executor.requests.single().body!!).jsonObject
            .getValue("AuthParameters").jsonObject["REFRESH_TOKEN"]?.jsonPrimitive?.content)

        val invalid = WhoopPrivateClient(FakeExecutor(response(200, """{"AuthenticationResult":{"AccessToken":"$ACCESS","RefreshToken":"$REFRESH","ExpiresIn":0}}""")), { NOW })
        assertReason(PrivateWhoopError.InvalidResponse) { invalid.beginLogin("user@example.test", "synthetic-password") }
    }

    @Test
    fun accountCheckUsesFixedHeadersAndDoesNotExposeUserId() = runTest {
        val privateId = "98765432101234567890"
        val executor = FakeExecutor(response(200, """{"user":{"id":"$privateId"}}"""))
        WhoopPrivateClient(executor, { NOW }).checkAccount(session())
        val request = executor.requests.single()
        assertEquals("GET", request.method)
        assertEquals(ACCOUNT_URL, request.url)
        assertEquals(null, request.body)
        assertEquals("Bearer $ACCESS", request.headers["Authorization"])
        assertEquals("Europe/London", request.headers["x-whoop-time-zone"])
        assertEquals("com.whoop.iphone", request.headers["x-whoop-bundle-name"])
        assertEquals("5.48.0", request.headers["x-whoop-ios-version"])
        assertEquals("573097", request.headers["x-whoop-ios-build-number"])
        assertFalse(PrivateWhoopException(PrivateWhoopError.InvalidResponse).toString().contains(privateId))
    }

    @Test
    fun activityUsesExactPayloadAndRejectsUnsafePayloadBeforeRequest() = runTest {
        val receipt = "00000000-0000-4000-8000-000000000001"
        val executor = FakeExecutor(response(200, """{"id":"$receipt"}"""))
        val result = WhoopPrivateClient(executor, { NOW }).createActivity(session(), payload(), "Europe/London")
        assertEquals(receipt, result)
        val request = executor.requests.single()
        assertEquals("POST", request.method)
        assertEquals(ACTIVITY_URL, request.url)
        assertEquals("Bearer $ACCESS", request.headers["Authorization"])
        assertEquals("Europe/London", request.headers["x-whoop-time-zone"])
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals(0, body["sport_id"]?.jsonPrimitive?.content?.toInt())
        assertEquals("2026-10-31T10:00:00.000Z", body["start_time"]?.jsonPrimitive?.content)
        assertEquals("2026-10-31T11:00:00.000Z", body["end_time"]?.jsonPrimitive?.content)
        assertTrue("gps_enabled" in body)
        assertEquals("false", body["gps_enabled"]?.jsonPrimitive?.content)

        val invalidExecutor = FakeExecutor()
        val invalidClient = WhoopPrivateClient(invalidExecutor, { NOW })
        assertReason(PrivateWhoopError.InvalidInput) {
            invalidClient.createActivity(session(), payload().copy(gpsEnabled = true), "Europe/London")
        }
        assertTrue(invalidExecutor.requests.isEmpty())
    }

    @Test
    fun activityAmbiguousOutcomesNeverBecomeRetryableRejections() = runTest {
        val uncertainStatuses = listOf(302, 500, 503)
        uncertainStatuses.forEach { status ->
            val client = WhoopPrivateClient(FakeExecutor(response(status, "{}")), { NOW })
            assertReason(PrivateWhoopError.SubmissionUncertain) {
                client.createActivity(session(), payload(), "Europe/London")
            }
        }
        val malformedReceipt = WhoopPrivateClient(FakeExecutor(response(200, """{"id":"not-a-uuid"}""")), { NOW })
        assertReason(PrivateWhoopError.SubmissionUncertain) {
            malformedReceipt.createActivity(session(), payload(), "Europe/London")
        }
        val rejected = WhoopPrivateClient(FakeExecutor(response(422, "sensitive server body")), { NOW })
        val exception = assertFailsWith<PrivateWhoopException> {
            rejected.createActivity(session(), payload(), "Europe/London")
        }
        assertEquals(PrivateWhoopError.Rejected, exception.reason)
        assertEquals(PrivateWhoopError.Rejected.name, exception.message)
        assertNull(exception.cause)
        assertFalse(exception.toString().contains("sensitive server body"))

        val failedWrite = WhoopPrivateClient(FakeExecutor(failure = IllegalStateException("synthetic transport failure")), { NOW })
        assertReason(PrivateWhoopError.SubmissionUncertain) {
            failedWrite.createActivity(session(), payload(), "Europe/London")
        }
        val oversizedReceipt = WhoopPrivateClient(FakeExecutor(response(200, " ".repeat(1_048_577))), { NOW })
        assertReason(PrivateWhoopError.SubmissionUncertain) {
            oversizedReceipt.createActivity(session(), payload(), "Europe/London")
        }
    }

    @Test
    fun accountRejectsNonNumericIdsAndOversizedResponses() = runTest {
        val malformed = WhoopPrivateClient(FakeExecutor(response(200, """{"user":{"id":"user-123"}}""")), { NOW })
        assertReason(PrivateWhoopError.InvalidResponse) { malformed.checkAccount(session()) }
        val oversized = WhoopPrivateClient(FakeExecutor(response(200, " ".repeat(1_048_577))), { NOW })
        assertReason(PrivateWhoopError.InvalidResponse) { oversized.checkAccount(session()) }
    }

    @Test
    fun accountAndAuthErrorsAreCategorizedWithoutRemoteDetails() = runTest {
        val cases = mapOf(401 to PrivateWhoopError.Unauthorized, 429 to PrivateWhoopError.RateLimited, 403 to PrivateWhoopError.Rejected)
        cases.forEach { (status, reason) ->
            val client = WhoopPrivateClient(FakeExecutor(response(status, "token=$ACCESS email=user@example.test")), { NOW })
            val exception = assertFailsWith<PrivateWhoopException> { client.checkAccount(session()) }
            assertEquals(reason, exception.reason)
            assertEquals(reason.name, exception.message)
            assertNull(exception.cause)
            assertFalse(exception.toString().contains(ACCESS))
            assertFalse(exception.toString().contains("user@example.test"))
        }
        val redirects = WhoopPrivateClient(FakeExecutor(response(302, "")), { NOW })
        assertReason(PrivateWhoopError.InvalidResponse) { redirects.checkAccount(session()) }

        val directPassword401 = WhoopPrivateClient(FakeExecutor(response(401, "synthetic auth rejection")), { NOW })
        assertReason(PrivateWhoopError.Unauthorized) {
            directPassword401.beginLogin("user@example.test", "synthetic-password")
        }
    }

    @Test
    fun cancellationIsPropagatedUnchanged() = runTest {
        val cancellation = CancellationException("cancel synthetic request")
        val executor = FakeExecutor(failure = cancellation)
        val caught = assertFailsWith<CancellationException> {
            WhoopPrivateClient(executor, { NOW }).checkAccount(session())
        }
        assertTrue(caught === cancellation)

        val writeCancellation = CancellationException("cancel synthetic write")
        val cancelledWrite = WhoopPrivateClient(FakeExecutor(failure = writeCancellation), { NOW })
        val writeCaught = assertFailsWith<CancellationException> {
            cancelledWrite.createActivity(session(), payload(), "Europe/London")
        }
        assertTrue(writeCaught === writeCancellation)
    }

    @Test
    fun transportRequestAndResponseToStringRedactHeadersAndBodies() {
        val request = PrivateHttpRequest(
            "POST", ACTIVITY_URL, mapOf("Authorization" to "Bearer $ACCESS"), "password=synthetic-password",
        )
        val response = PrivateHttpResponse(200, "access_token=$ACCESS".encodeToByteArray())

        assertEquals("PrivateHttpRequest(redacted)", request.toString())
        assertFalse(request.toString().contains(ACCESS))
        assertFalse(request.toString().contains("synthetic-password"))
        assertTrue(response.toString().contains("status=200"))
        assertFalse(response.toString().contains(ACCESS))
        assertFalse(response.toString().contains("access_token"))
    }

    @Test
    fun okhttpDoesNotReplayPostOn503RetryAfterZero() {
        val server = ServerSocket(0).apply { soTimeout = 750 }
        val requestsReceived = AtomicInteger()
        val serverThread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    break
                }
                socket.use { accepted ->
                    val reader = BufferedReader(InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8))
                    var line = reader.readLine()
                    if (line != null) requestsReceived.incrementAndGet()
                    while (!line.isNullOrEmpty()) line = reader.readLine()
                    accepted.getOutputStream().write(
                        ("HTTP/1.1 503 Service Unavailable\r\n" +
                            "Retry-After: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .toByteArray(StandardCharsets.US_ASCII),
                    )
                    accepted.getOutputStream().flush()
                }
            }
        }
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .addNetworkInterceptor(WhoopOneShotNetworkInterceptor())
            .build()
        val body = "{}".toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("http://127.0.0.1:${server.localPort}/activity")
            .post(body)
            .build()

        try {
            val outcome = runCatching { client.newCall(request).execute().use { it.code } }
            serverThread.join(2_000)
            assertEquals(1, requestsReceived.get(), "OkHttp must not replay a 503/Retry-After: 0 POST")
            assertTrue(outcome.isSuccess || outcome.exceptionOrNull() is IOException)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            server.close()
            serverThread.join(1_000)
        }
    }

    private suspend fun assertReason(reason: PrivateWhoopError, block: suspend () -> Unit) {
        val exception = assertFailsWith<PrivateWhoopException> { block() }
        assertEquals(reason, exception.reason)
        assertEquals(reason.name, exception.message)
        assertNull(exception.cause)
    }

    private class FakeExecutor(
        private val next: PrivateHttpResponse = response(200, "{}"),
        private val failure: Throwable? = null,
    ) : PrivateRequestExecutor {
        val requests = mutableListOf<PrivateHttpRequest>()
        override suspend fun execute(request: PrivateHttpRequest): PrivateHttpResponse {
            requests += request
            failure?.let { throw it }
            return next
        }
    }

    private companion object {
        const val NOW = 1_798_700_000L
        const val ACCESS = "synthetic-access-token-123456"
        const val REFRESH = "synthetic-refresh-token-123456"
        const val AUTH_URL = "https://api.prod.whoop.com/auth-service/v3/whoop/"
        const val ACCOUNT_URL = "https://api.prod.whoop.com/users-service/v2/bootstrap?apiVersion=7"
        const val ACTIVITY_URL = "https://api.prod.whoop.com/core-details-bff/v0/create-activity?apiVersion=7"
        const val AUTH_USER_AGENT = "aws-sdk-swift/1.5.86 ua/2.1 api/cognito_identity_provider#1.5.86 os/ios#26.3.1 lang/swift#5.10 m/D,N,Z,b"

        fun session() = PrivateWhoopSession(ACCESS, REFRESH, NOW + 3600)
        fun authResult() = """{"AccessToken":"$ACCESS","RefreshToken":"$REFRESH","ExpiresIn":3600}"""
        fun response(status: Int, body: String) = PrivateHttpResponse(status, body.encodeToByteArray())
        fun payload() = PrivateActivityPayload(0, "2026-10-31T10:00:00.000Z", "2026-10-31T11:00:00.000Z")
    }
}
