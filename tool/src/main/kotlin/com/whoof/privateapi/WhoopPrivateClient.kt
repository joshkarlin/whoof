package com.whoof.privateapi

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import okhttp3.Interceptor
import okhttp3.Response
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject

/** Private WHOOP session transport. Destinations and request shapes are deliberately fixed. */
internal class WhoopPrivateClient internal constructor(
    private val request: PrivateRequestExecutor,
    private val nowEpochSeconds: () -> Long,
) : WhoopPrivateTransport {
    internal constructor() : this(KtorPrivateRequest(), { Instant.now().epochSecond })

    override suspend fun beginLogin(email: String, password: String): PrivateWhoopLoginResult {
        requirePrivate(email.isNotBlank() && password.isNotEmpty())
        val response = auth("InitiateAuth", buildJsonObject {
            put("AuthFlow", "USER_PASSWORD_AUTH")
            put("ClientId", "")
            put("AuthParameters", buildJsonObject {
                put("USERNAME", email.trim())
                put("PASSWORD", password)
            })
        })
        return loginResult(response, email.trim())
    }

    override suspend fun completeLogin(
        challenge: PrivateWhoopChallenge,
        code: String,
    ): PrivateWhoopLoginResult {
        requirePrivate(
            challenge.name in MFA_FIELDS && challenge.session.isNotBlank() && challenge.session.length <= 16_384 &&
                challenge.username.isNotBlank() && code.isNotBlank(),
        )
        val response = auth("RespondToAuthChallenge", buildJsonObject {
            put("ClientId", "")
            put("ChallengeName", challenge.name)
            put("Session", challenge.session)
            put("ChallengeResponses", buildJsonObject {
                put("USERNAME", challenge.username)
                put(MFA_FIELDS.getValue(challenge.name), code.trim())
            })
        })
        return loginResult(response, challenge.username)
    }

    override suspend fun refresh(session: PrivateWhoopSession): PrivateWhoopSession {
        val response = auth("InitiateAuth", buildJsonObject {
            put("AuthFlow", "REFRESH_TOKEN_AUTH")
            put("ClientId", "")
            put("AuthParameters", buildJsonObject { put("REFRESH_TOKEN", session.refreshToken) })
        })
        return tokens(response, session.refreshToken)
    }

    override suspend fun checkAccount(session: PrivateWhoopSession) {
        val response = execute(PrivateHttpRequest("GET", ACCOUNT_URL, accountHeaders(session), null))
        val userId = response.json["user"]?.asObject()?.get("id")
        if (userId == null || !numericId(userId)) throw privateError(PrivateWhoopError.InvalidResponse)
    }

    override suspend fun createActivity(
        session: PrivateWhoopSession,
        payload: PrivateActivityPayload,
        timeZone: String,
    ): String {
        validateActivity(payload, timeZone)
        val response = execute(PrivateHttpRequest(
                "POST", ACTIVITY_URL, accountHeaders(session, timeZone), json.encodeToString(payload),
            ), ambiguousWrite = true)
        val receipt = response.json["id"]?.stringValue()
        if (!canonicalUuid(receipt)) throw privateError(PrivateWhoopError.SubmissionUncertain)
        return receipt!!
    }

    override fun close() {
        request.close()
    }

    private suspend fun auth(operation: String, body: JsonObject): JsonObject {
        val response = execute(PrivateHttpRequest("POST", AUTH_URL, authHeaders(operation), body.toString()))
        return response.json
    }

    private suspend fun execute(
        request: PrivateHttpRequest,
        ambiguousWrite: Boolean = false,
    ): PrivateHttpResponse {
        val response = try {
            this.request.execute(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw privateError(if (ambiguousWrite) PrivateWhoopError.SubmissionUncertain else PrivateWhoopError.Unavailable)
        }
        if (response.status in 300..399) {
            throw privateError(if (ambiguousWrite) PrivateWhoopError.SubmissionUncertain else PrivateWhoopError.InvalidResponse)
        }
        if (response.status !in 200..299) {
            val reason = when (response.status) {
                401 -> PrivateWhoopError.Unauthorized
                429 -> PrivateWhoopError.RateLimited
                in 400..499 -> PrivateWhoopError.Rejected
            else -> if (ambiguousWrite) PrivateWhoopError.SubmissionUncertain else PrivateWhoopError.Unavailable
            }
            throw privateError(reason)
        }
        if (response.body.size > MAX_RESPONSE_BYTES) {
            throw privateError(if (ambiguousWrite) PrivateWhoopError.SubmissionUncertain else PrivateWhoopError.InvalidResponse)
        }
        val parsed = try {
            json.parseToJsonElement(response.body.decodeToString()).jsonObject
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw privateError(if (ambiguousWrite) PrivateWhoopError.SubmissionUncertain else PrivateWhoopError.InvalidResponse)
        }
        return PrivateHttpResponse(response.status, response.body, parsed)
    }

    private fun loginResult(response: JsonObject, fallbackUsername: String): PrivateWhoopLoginResult {
        if ("AuthenticationResult" in response) {
            return PrivateWhoopLoginResult.Authenticated(tokens(response))
        }
        val name = response["ChallengeName"]?.stringValue()
        val session = response["Session"]?.stringValue()
        if (name == null || name !in MFA_FIELDS || session.isNullOrEmpty() || session.length > 16_384) {
            throw privateError(PrivateWhoopError.UnsupportedChallenge)
        }
        val parameters = response["ChallengeParameters"]?.asObject()
        val username = parameters?.get("USERNAME")?.stringValue()
            ?: parameters?.get("USER_ID_FOR_SRP")?.stringValue()
            ?: fallbackUsername
        if (username.isBlank()) throw privateError(PrivateWhoopError.InvalidResponse)
        return PrivateWhoopLoginResult.VerificationRequired(PrivateWhoopChallenge(name, session, username))
    }

    private fun tokens(response: JsonObject, fallbackRefresh: String? = null): PrivateWhoopSession {
        val result = response["AuthenticationResult"]?.asObject()
            ?: throw privateError(PrivateWhoopError.InvalidResponse)
        val access = result["AccessToken"]?.stringValue()
        val refresh = result["RefreshToken"]?.stringValue() ?: fallbackRefresh
        val lifetimeElement = try {
            result["ExpiresIn"]?.jsonPrimitive
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val lifetime = lifetimeElement?.longOrNull
        if (lifetime == null || lifetimeElement.isString || lifetime <= 0L ||
            !validPrivateToken(access.orEmpty()) || !validPrivateToken(refresh.orEmpty())) {
            throw privateError(PrivateWhoopError.InvalidResponse)
        }
        if (lifetimeElement.contentOrNull == "true") throw privateError(PrivateWhoopError.InvalidResponse)
        val now = nowEpochSeconds()
        val expiry = try {
            Math.addExact(now, lifetime)
        } catch (_: ArithmeticException) {
            throw privateError(PrivateWhoopError.InvalidResponse)
        }
        if (expiry <= now) throw privateError(PrivateWhoopError.InvalidResponse)
        return try {
            PrivateWhoopSession(access!!, refresh!!, expiry)
        } catch (_: PrivateWhoopException) {
            throw privateError(PrivateWhoopError.InvalidResponse)
        }
    }

    private fun validateActivity(payload: PrivateActivityPayload, timeZone: String) {
        requirePrivate(payload.sportId in SUPPORTED_SPORT_IDS && !payload.gpsEnabled)
        val zone = try {
            ZoneId.of(timeZone)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw privateError(PrivateWhoopError.InvalidInput)
        }
        val start = try {
            Instant.parse(payload.startTime)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw privateError(PrivateWhoopError.InvalidInput)
        }
        val end = try {
            Instant.parse(payload.endTime)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw privateError(PrivateWhoopError.InvalidInput)
        }
        val duration = try {
            Math.subtractExact(end.toEpochMilli(), start.toEpochMilli())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw privateError(PrivateWhoopError.InvalidInput)
        }
        requirePrivate(duration in 60_000L..86_400_000L && !end.isAfter(Instant.ofEpochSecond(nowEpochSeconds())))
        // Resolve the zone before any network operation; the payload timestamps are UTC by contract.
        zone.rules
    }

    private fun authHeaders(operation: String) = linkedMapOf(
        "Content-Type" to "application/x-amz-json-1.1",
        "X-Amz-Target" to "AWSCognitoIdentityProviderService.$operation",
        "User-Agent" to AUTH_USER_AGENT,
        "Accept" to "application/json",
        "amz-sdk-request" to "attempt=1; max=1",
        "amz-sdk-invocation-id" to UUID.randomUUID().toString(),
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private fun accountHeaders(session: PrivateWhoopSession, timeZone: String = DEFAULT_TIME_ZONE) = linkedMapOf(
        "Authorization" to "Bearer ${session.accessToken}",
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "User-Agent" to "iOS",
        "x-whoop-device-platform" to "iOS",
        "x-whoop-time-zone" to timeZone,
        "x-whoop-bundle-name" to "com.whoop.iphone",
        "x-whoop-ios-version" to "5.48.0",
        "x-whoop-ios-build-number" to "573097",
    )

    private companion object {
        const val AUTH_URL = "https://api.prod.whoop.com/auth-service/v3/whoop/"
        const val ACCOUNT_URL = "https://api.prod.whoop.com/users-service/v2/bootstrap?apiVersion=7"
        const val ACTIVITY_URL = "https://api.prod.whoop.com/core-details-bff/v0/create-activity?apiVersion=7"
        const val DEFAULT_TIME_ZONE = "Europe/London"
        const val MAX_RESPONSE_BYTES = 1_048_576
        const val AUTH_USER_AGENT = "aws-sdk-swift/1.5.86 ua/2.1 api/cognito_identity_provider#1.5.86 os/ios#26.3.1 lang/swift#5.10 m/D,N,Z,b"
        val MFA_FIELDS = mapOf(
            "SMS_MFA" to "SMS_MFA_CODE",
            "SOFTWARE_TOKEN_MFA" to "SOFTWARE_TOKEN_MFA_CODE",
            "EMAIL_OTP" to "EMAIL_OTP_CODE",
        )
        val SUPPORTED_SPORT_IDS = setOf(0, 1, 45, 52, 63)
        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

internal data class PrivateHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
) {
    override fun toString(): String = "PrivateHttpRequest(redacted)"
}

internal data class PrivateHttpResponse(
    val status: Int,
    val body: ByteArray,
    val json: JsonObject = JsonObject(emptyMap()),
) {
    override fun toString(): String = "PrivateHttpResponse(status=$status, redacted)"
}

internal interface PrivateRequestExecutor {
    suspend fun execute(request: PrivateHttpRequest): PrivateHttpResponse
    fun close() = Unit
}

private class KtorPrivateRequest : PrivateRequestExecutor {
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
                retryOnConnectionFailure(false)
                addNetworkInterceptor(WhoopOneShotNetworkInterceptor())
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    override suspend fun execute(request: PrivateHttpRequest): PrivateHttpResponse {
        val response = client.prepareRequest(request.url) {
            method = HttpMethod.parse(request.method)
            request.headers.forEach { (name, value) -> header(name, value) }
            request.body?.let {
                setBody(it)
                contentType(ContentType.parse(request.headers.getValue("Content-Type")))
            }
        }.execute { httpResponse ->
            val output = ByteArrayOutputStream()
            val channel = httpResponse.bodyAsChannel()
            val buffer = ByteArray(8192)
            while (output.size() <= 1_048_576) {
                val remaining = 1_048_577 - output.size()
                val count = channel.readAvailable(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
            }
            PrivateHttpResponse(httpResponse.status.value, output.toByteArray())
        }
        return response
    }

    override fun close() = client.close()
}

/** Prevents OkHttp HTTP follow-ups from issuing the same call a second network exchange. */
internal class WhoopOneShotNetworkInterceptor : Interceptor {
    private val calls = Collections.newSetFromMap(WeakHashMap<okhttp3.Call, Boolean>())

    override fun intercept(chain: Interceptor.Chain): Response {
        val firstExchange = synchronized(calls) { calls.add(chain.call()) }
        if (!firstExchange) throw IOException("WHOOP request replay blocked")
        return chain.proceed(chain.request())
    }
}

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
private fun JsonElement.stringValue(): String? = try {
    jsonPrimitive.takeIf { it.isString }?.contentOrNull
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
private fun numericId(value: JsonElement): Boolean {
    val primitive = try {
        value.jsonPrimitive
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return false
    }
    val text = primitive.contentOrNull ?: return false
    return text.isNotEmpty() && text.all(Char::isDigit)
}
private fun canonicalUuid(value: String?): Boolean = value != null && try {
    UUID.fromString(value).toString() == value
} catch (_: IllegalArgumentException) {
    false
}
private fun privateError(reason: PrivateWhoopError) = PrivateWhoopException(reason)
