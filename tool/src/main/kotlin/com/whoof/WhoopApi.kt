package com.whoof

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import com.whoof.privateapi.WhoopPrivateHeaders
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

private const val RECOVERY_URL = "https://api.prod.whoop.com/developer/v2/recovery"
private const val CYCLE_URL = "https://api.prod.whoop.com/developer/v2/cycle"
private const val SLEEP_URL = "https://api.prod.whoop.com/developer/v2/activity/sleep"
private const val WORKOUT_URL = "https://api.prod.whoop.com/developer/v2/activity/workout"
private const val DEFAULT_RECENT_LIMIT = 7
private const val MAX_COLLECTION_LIMIT = 25

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
    @SerialName("recovery_score") val recoveryScore: Double? = null,
    @SerialName("resting_heart_rate") val restingHeartRate: Double? = null,
    @SerialName("hrv_rmssd_milli") val hrvRmssdMilli: Double? = null,
    @SerialName("spo2_percentage") val spo2Percentage: Double? = null,
    @SerialName("skin_temp_celsius") val skinTempCelsius: Double? = null,
)

@Serializable
internal data class CycleCollection(
    val records: List<CycleRecord> = emptyList(),
    @SerialName("next_token") val nextToken: String? = null,
)

@Serializable
internal data class CycleRecord(
    val id: Long,
    @SerialName("user_id") val userId: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val start: String,
    val end: String? = null,
    @SerialName("timezone_offset") val timezoneOffset: String,
    @SerialName("score_state") val scoreState: String,
    val score: CycleScore? = null,
)

@Serializable
internal data class CycleScore(
    val strain: Double? = null,
    @SerialName("kilojoule") val kilojoule: Double? = null,
    @SerialName("average_heart_rate") val averageHeartRate: Int? = null,
    @SerialName("max_heart_rate") val maxHeartRate: Int? = null,
)

@Serializable
internal data class SleepCollection(
    val records: List<SleepRecord> = emptyList(),
    @SerialName("next_token") val nextToken: String? = null,
)

@Serializable
internal data class SleepRecord(
    val id: String,
    @SerialName("cycle_id") val cycleId: Long,
    @SerialName("user_id") val userId: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val start: String,
    val end: String,
    @SerialName("timezone_offset") val timezoneOffset: String,
    val nap: Boolean,
    @SerialName("score_state") val scoreState: String,
    val score: SleepScore? = null,
)

@Serializable
internal data class SleepScore(
    @SerialName("stage_summary") val stageSummary: SleepStageSummary? = null,
    @SerialName("sleep_needed") val sleepNeeded: SleepNeeded? = null,
    @SerialName("respiratory_rate") val respiratoryRate: Double? = null,
    @SerialName("sleep_performance_percentage") val sleepPerformancePercentage: Double? = null,
    @SerialName("sleep_consistency_percentage") val sleepConsistencyPercentage: Double? = null,
    @SerialName("sleep_efficiency_percentage") val sleepEfficiencyPercentage: Double? = null,
)

@Serializable
internal data class SleepStageSummary(
    @SerialName("total_in_bed_time_milli") val totalInBedTimeMilli: Long? = null,
    @SerialName("total_awake_time_milli") val totalAwakeTimeMilli: Long? = null,
    @SerialName("total_no_data_time_milli") val totalNoDataTimeMilli: Long? = null,
    @SerialName("total_light_sleep_time_milli") val totalLightSleepTimeMilli: Long? = null,
    @SerialName("total_slow_wave_sleep_time_milli") val totalSlowWaveSleepTimeMilli: Long? = null,
    @SerialName("total_rem_sleep_time_milli") val totalRemSleepTimeMilli: Long? = null,
    @SerialName("sleep_cycle_count") val sleepCycleCount: Int? = null,
    @SerialName("disturbance_count") val disturbanceCount: Int? = null,
)

@Serializable
internal data class SleepNeeded(
    @SerialName("baseline_milli") val baselineMilli: Long? = null,
    @SerialName("need_from_sleep_debt_milli") val needFromSleepDebtMilli: Long? = null,
    @SerialName("need_from_recent_strain_milli") val needFromRecentStrainMilli: Long? = null,
    @SerialName("need_from_recent_nap_milli") val needFromRecentNapMilli: Long? = null,
)

@Serializable
internal data class WorkoutCollection(
    val records: List<WorkoutRecord> = emptyList(),
    @SerialName("next_token") val nextToken: String? = null,
)

@Serializable
internal data class WorkoutRecord(
    val id: String,
    @SerialName("v1_id") val v1Id: Long? = null,
    @SerialName("user_id") val userId: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val start: String,
    val end: String,
    @SerialName("timezone_offset") val timezoneOffset: String,
    @SerialName("sport_name") val sportName: String,
    @SerialName("score_state") val scoreState: String,
    val score: WorkoutScore? = null,
    @SerialName("sport_id") val sportId: Int? = null,
)

@Serializable
internal data class WorkoutScore(
    val strain: Double? = null,
    @SerialName("average_heart_rate") val averageHeartRate: Int? = null,
    @SerialName("max_heart_rate") val maxHeartRate: Int? = null,
    val kilojoule: Double? = null,
    @SerialName("percent_recorded") val percentRecorded: Double? = null,
    @SerialName("distance_meter") val distanceMeter: Double? = null,
    @SerialName("altitude_gain_meter") val altitudeGainMeter: Double? = null,
    @SerialName("altitude_change_meter") val altitudeChangeMeter: Double? = null,
    @SerialName("zone_durations") val zoneDurations: WorkoutZoneDurations? = null,
)

@Serializable
internal data class WorkoutZoneDurations(
    @SerialName("zone_zero_milli") val zoneZeroMilli: Long? = null,
    @SerialName("zone_one_milli") val zoneOneMilli: Long? = null,
    @SerialName("zone_two_milli") val zoneTwoMilli: Long? = null,
    @SerialName("zone_three_milli") val zoneThreeMilli: Long? = null,
    @SerialName("zone_four_milli") val zoneFourMilli: Long? = null,
    @SerialName("zone_five_milli") val zoneFiveMilli: Long? = null,
)

internal fun Double.wholePercent(): String = "${roundToInt()}%"

internal fun boundedRecentLimit(limit: Int): Int = limit.coerceIn(1, MAX_COLLECTION_LIMIT)

internal enum class WhoopApiError {
    Unauthorized,
    MissingScope,
    RateLimited,
    InvalidRequest,
    Unavailable,
}

internal class WhoopApiException(val reason: WhoopApiError) : Exception()

/** Developer-v2-shaped reads, authorized with the private account session's bearer token. */
internal interface WhoopHistoryClient {
    suspend fun recentRecoveries(
        accessToken: String,
        limit: Int = DEFAULT_RECENT_LIMIT,
        nextToken: String? = null,
    ): RecoveryCollection

    suspend fun recentCycles(
        accessToken: String,
        limit: Int = DEFAULT_RECENT_LIMIT,
        nextToken: String? = null,
    ): CycleCollection

    suspend fun recentSleeps(
        accessToken: String,
        limit: Int = DEFAULT_RECENT_LIMIT,
        nextToken: String? = null,
    ): SleepCollection

    suspend fun recentWorkouts(
        accessToken: String,
        limit: Int = DEFAULT_RECENT_LIMIT,
        nextToken: String? = null,
    ): WorkoutCollection

    fun close() = Unit
}

internal class WhoopApi : WhoopHistoryClient {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    override suspend fun recentRecoveries(
        accessToken: String,
        limit: Int,
        nextToken: String?,
    ): RecoveryCollection = getCollection(RECOVERY_URL, accessToken, limit, nextToken)

    override suspend fun recentCycles(
        accessToken: String,
        limit: Int,
        nextToken: String?,
    ): CycleCollection = getCollection(CYCLE_URL, accessToken, limit, nextToken)

    override suspend fun recentSleeps(
        accessToken: String,
        limit: Int,
        nextToken: String?,
    ): SleepCollection = getCollection(SLEEP_URL, accessToken, limit, nextToken)

    override suspend fun recentWorkouts(
        accessToken: String,
        limit: Int,
        nextToken: String?,
    ): WorkoutCollection = getCollection(WORKOUT_URL, accessToken, limit, nextToken)

    private suspend inline fun <reified T> getCollection(
        url: String,
        accessToken: String,
        limit: Int,
        nextToken: String?,
    ): T {
        val response = client.get(url) {
            headers { WhoopPrivateHeaders.read(accessToken).forEach { (name, value) -> append(name, value) } }
            parameter("limit", boundedRecentLimit(limit))
            nextToken?.let { parameter("nextToken", it) }
        }
        validate(response)
        return response.body()
    }

    override fun close() {
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
