package com.whoof

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val RECOVERY_URL = "https://api.prod.whoop.com/developer/v2/recovery"

@Serializable
internal data class RecoveryCollection(
    val records: List<RecoveryRecord> = emptyList(),
    @SerialName("next_token") val nextToken: String? = null,
)

@Serializable
internal data class RecoveryRecord(
    @SerialName("cycle_id") val cycleId: Long,
    @SerialName("sleep_id") val sleepId: String,
    @SerialName("user_id") val userId: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("score_state") val scoreState: String,
    val score: RecoveryScore? = null,
)

@Serializable
internal data class RecoveryScore(
    @SerialName("user_calibrating") val userCalibrating: Boolean? = null,
    @SerialName("recovery_score") val recoveryScore: Int? = null,
    @SerialName("resting_heart_rate") val restingHeartRate: Double? = null,
    @SerialName("hrv_rmssd_milli") val hrvRmssdMilli: Double? = null,
    @SerialName("spo2_percentage") val spo2Percentage: Double? = null,
    @SerialName("skin_temp_celsius") val skinTempCelsius: Double? = null,
)

internal enum class WhoopApiError {
    Unauthorized,
    MissingScope,
    RateLimited,
    InvalidRequest,
    Unavailable,
}

internal class WhoopApiException(val reason: WhoopApiError) : Exception()

internal class WhoopApi {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun latestRecovery(accessToken: String): RecoveryRecord? {
        val response = client.get(RECOVERY_URL) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("limit", 1)
        }
        validate(response)
        return response.body<RecoveryCollection>().records.firstOrNull()
    }

    fun close() {
        client.close()
    }

    private fun validate(response: HttpResponse) {
        if (response.status.isSuccess()) return
        val reason = when (response.status) {
            HttpStatusCode.Unauthorized -> WhoopApiError.Unauthorized
            HttpStatusCode.Forbidden -> WhoopApiError.MissingScope
            HttpStatusCode.BadRequest -> WhoopApiError.InvalidRequest
            HttpStatusCode.TooManyRequests -> WhoopApiError.RateLimited
            else -> WhoopApiError.Unavailable
        }
        throw WhoopApiException(reason)
    }
}
