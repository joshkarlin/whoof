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
                    "recovery_score": 44,
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
        assertEquals(44, record.score?.recoveryScore)
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
}
