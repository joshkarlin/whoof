package com.whoof

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WhoopApiModelsTest {
    @Test
    fun decodesDocumentedRecoveryV2Shape() {
        val collection = Json.decodeFromString<RecoveryCollection>(
            """
            {
              "records": [
                {
                  "cycle_id": 93845,
                  "sleep_id": "123e4567-e89b-12d3-a456-426614174000",
                  "user_id": 10129,
                  "created_at": "2022-04-24T11:25:44.774Z",
                  "updated_at": "2022-04-24T14:25:44.774Z",
                  "score_state": "SCORED",
                  "score": {
                    "user_calibrating": false,
                    "recovery_score": 37.0,
                    "resting_heart_rate": 64,
                    "hrv_rmssd_milli": 31.813562,
                    "spo2_percentage": 95.6875,
                    "skin_temp_celsius": 33.7
                  }
                }
              ],
              "next_token": "next-page"
            }
            """.trimIndent(),
        )

        val record = collection.records.single()
        assertEquals(93845L, record.cycleId)
        assertEquals("123e4567-e89b-12d3-a456-426614174000", record.sleepId)
        assertEquals(37.0, record.score?.recoveryScore)
        assertEquals("37%", record.score?.recoveryScore?.wholePercent())
        assertEquals("next-page", collection.nextToken)
    }

    @Test
    fun keepsUnscoredRecoveryExplicit() {
        val record = Json.decodeFromString<RecoveryRecord>(
            """
            {
              "cycle_id": 1,
              "sleep_id": "sleep-id",
              "user_id": 2,
              "created_at": "2022-04-24T11:25:44.774Z",
              "updated_at": "2022-04-24T14:25:44.774Z",
              "score_state": "PENDING_SCORE",
              "score": null
            }
            """.trimIndent(),
        )

        assertEquals("PENDING_SCORE", record.scoreState)
        assertNull(record.score)
    }

    @Test
    fun decodesDocumentedCycleCollectionShape() {
        val collection = Json.decodeFromString<CycleCollection>(
            """
            {
              "records": [
                {
                  "id": 93845,
                  "user_id": 10129,
                  "created_at": "2022-04-24T11:25:44.774Z",
                  "updated_at": "2022-04-24T14:25:44.774Z",
                  "start": "2022-04-24T02:25:44.774Z",
                  "end": "2022-04-24T10:25:44.774Z",
                  "timezone_offset": "-05:00",
                  "score_state": "SCORED",
                  "score": {
                    "strain": 5.2951527
                  }
                }
              ],
              "next_token": "next-cycle-page"
            }
            """.trimIndent(),
        )

        val record = collection.records.single()
        assertEquals(93845L, record.id)
        assertEquals("2022-04-24T02:25:44.774Z", record.start)
        assertEquals(5.2951527, record.score?.strain)
        assertEquals("next-cycle-page", collection.nextToken)
    }

    @Test
    fun keepsCurrentUnscoredCyclePartial() {
        val record = Json.decodeFromString<CycleRecord>(
            """
            {
              "id": 93845,
              "user_id": 10129,
              "created_at": "2022-04-24T11:25:44.774Z",
              "updated_at": "2022-04-24T14:25:44.774Z",
              "start": "2022-04-24T02:25:44.774Z",
              "timezone_offset": "-05:00",
              "score_state": "PENDING_SCORE"
            }
            """.trimIndent(),
        )

        assertNull(record.end)
        assertEquals("PENDING_SCORE", record.scoreState)
        assertNull(record.score)
    }

    @Test
    fun decodesPrimarySleepForCycleShape() {
        val sleep = Json.decodeFromString<SleepRecord>(
            """
            {
              "id": "ecfc6a15-4661-442f-a9a4-f160dd7afae8",
              "cycle_id": 93845,
              "user_id": 10129,
              "created_at": "2022-04-24T11:25:44.774Z",
              "updated_at": "2022-04-24T14:25:44.774Z",
              "start": "2022-04-24T02:25:44.774Z",
              "end": "2022-04-24T10:25:44.774Z",
              "timezone_offset": "-05:00",
              "nap": false,
              "score_state": "SCORED",
              "score": {
                "sleep_performance_percentage": 98
              }
            }
            """.trimIndent(),
        )

        assertEquals("ecfc6a15-4661-442f-a9a4-f160dd7afae8", sleep.id)
        assertEquals(93845L, sleep.cycleId)
        assertEquals(false, sleep.nap)
        assertEquals(98.0, sleep.score?.sleepPerformancePercentage)
    }

    @Test
    fun keepsUnscoredSleepExplicit() {
        val sleep = Json.decodeFromString<SleepRecord>(
            """
            {
              "id": "ecfc6a15-4661-442f-a9a4-f160dd7afae8",
              "cycle_id": 93845,
              "user_id": 10129,
              "created_at": "2022-04-24T11:25:44.774Z",
              "updated_at": "2022-04-24T14:25:44.774Z",
              "start": "2022-04-24T02:25:44.774Z",
              "end": "2022-04-24T10:25:44.774Z",
              "timezone_offset": "-05:00",
              "nap": false,
              "score_state": "PENDING_SCORE",
              "score": null
            }
            """.trimIndent(),
        )

        assertEquals("PENDING_SCORE", sleep.scoreState)
        assertNull(sleep.score)
    }

    @Test
    fun decodesRecentSleepCollectionWithScoreDetailsAndPagination() {
        val collection = Json.decodeFromString<SleepCollection>(
            """
            {
              "records": [
                {
                  "id": "ecfc6a15-4661-442f-a9a4-f160dd7afae8",
                  "cycle_id": 93845,
                  "user_id": 10129,
                  "created_at": "2022-04-24T11:25:44.774Z",
                  "updated_at": "2022-04-24T14:25:44.774Z",
                  "start": "2022-04-24T02:25:44.774Z",
                  "end": "2022-04-24T10:25:44.774Z",
                  "timezone_offset": "-05:00",
                  "nap": false,
                  "score_state": "SCORED",
                  "score": {
                    "stage_summary": {
                      "total_in_bed_time_milli": 30272735,
                      "total_awake_time_milli": 1403507,
                      "total_no_data_time_milli": 0,
                      "total_light_sleep_time_milli": 14905851,
                      "total_slow_wave_sleep_time_milli": 6630370,
                      "total_rem_sleep_time_milli": 5879573,
                      "sleep_cycle_count": 3,
                      "disturbance_count": 12
                    },
                    "sleep_needed": {
                      "baseline_milli": 27395716,
                      "need_from_sleep_debt_milli": 352230,
                      "need_from_recent_strain_milli": 208595,
                      "need_from_recent_nap_milli": -12312
                    },
                    "respiratory_rate": 16.11328125,
                    "sleep_performance_percentage": 98,
                    "sleep_consistency_percentage": 90,
                    "sleep_efficiency_percentage": 91.69533848
                  }
                },
                {
                  "id": "pending-sleep",
                  "cycle_id": 93846,
                  "user_id": 10129,
                  "created_at": "2022-04-25T11:25:44.774Z",
                  "updated_at": "2022-04-25T11:25:44.774Z",
                  "start": "2022-04-25T02:25:44.774Z",
                  "end": "2022-04-25T10:25:44.774Z",
                  "timezone_offset": "-05:00",
                  "nap": false,
                  "score_state": "PENDING_SCORE"
                }
              ],
              "next_token": "next-sleep-page"
            }
            """.trimIndent(),
        )

        val scored = collection.records[0]
        val pending = collection.records[1]
        assertEquals(2, collection.records.size)
        assertEquals(30272735L, scored.score?.stageSummary?.totalInBedTimeMilli)
        assertEquals(16.11328125, scored.score?.respiratoryRate)
        assertEquals(91.69533848, scored.score?.sleepEfficiencyPercentage)
        assertEquals("PENDING_SCORE", pending.scoreState)
        assertNull(pending.score)
        assertEquals("next-sleep-page", collection.nextToken)
    }

    @Test
    fun decodesRecentWorkoutCollectionWithScoreDetailsAndPagination() {
        val collection = Json.decodeFromString<WorkoutCollection>(
            """
            {
              "records": [
                {
                  "id": "ecfc6a15-4661-442f-a9a4-f160dd7afae8",
                  "v1_id": 1043,
                  "user_id": 9012,
                  "created_at": "2022-04-24T11:25:44.774Z",
                  "updated_at": "2022-04-24T14:25:44.774Z",
                  "start": "2022-04-24T02:25:44.774Z",
                  "end": "2022-04-24T10:25:44.774Z",
                  "timezone_offset": "-05:00",
                  "sport_name": "running",
                  "score_state": "SCORED",
                  "score": {
                    "strain": 8.2463,
                    "average_heart_rate": 123,
                    "max_heart_rate": 146,
                    "kilojoule": 1569.34033203125,
                    "percent_recorded": 100,
                    "distance_meter": 1772.77035916,
                    "altitude_gain_meter": 46.64384460449,
                    "altitude_change_meter": -0.781372010707855,
                    "zone_durations": {
                      "zone_zero_milli": 300000,
                      "zone_one_milli": 600000,
                      "zone_two_milli": 900000,
                      "zone_three_milli": 900000,
                      "zone_four_milli": 600000,
                      "zone_five_milli": 300000
                    }
                  },
                  "sport_id": 1
                },
                {
                  "id": "pending-workout",
                  "user_id": 9012,
                  "created_at": "2022-04-25T11:25:44.774Z",
                  "updated_at": "2022-04-25T11:25:44.774Z",
                  "start": "2022-04-25T02:25:44.774Z",
                  "end": "2022-04-25T03:25:44.774Z",
                  "timezone_offset": "-05:00",
                  "sport_name": "running",
                  "score_state": "PENDING_SCORE"
                }
              ],
              "next_token": "next-workout-page"
            }
            """.trimIndent(),
        )

        val scored = collection.records[0]
        val pending = collection.records[1]
        assertEquals(2, collection.records.size)
        assertEquals("running", scored.sportName)
        assertEquals(8.2463, scored.score?.strain)
        assertEquals(1772.77035916, scored.score?.distanceMeter)
        assertEquals(900000L, scored.score?.zoneDurations?.zoneTwoMilli)
        assertEquals(1, scored.sportId)
        assertNull(pending.v1Id)
        assertEquals("PENDING_SCORE", pending.scoreState)
        assertNull(pending.score)
        assertEquals("next-workout-page", collection.nextToken)
    }

    @Test
    fun clampsRecentCollectionLimitToTheDocumentedBounds() {
        assertEquals(1, boundedRecentLimit(0))
        assertEquals(7, boundedRecentLimit(7))
        assertEquals(25, boundedRecentLimit(100))
    }
}
