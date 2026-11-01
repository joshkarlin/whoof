package com.whoof.privateapi

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PrivateWhoopPersistenceTest {
    @Test
    fun codecRoundTripsCurrentVersionAndSensitiveStateWithoutChangingFields() {
        val state = PrivateWhoopState(
            version = 1,
            session = testSession(),
            draft = PrivateActivityDraft(
                id = UUID.randomUUID().toString(),
                sport = PrivateWhoopSport.Cycling,
                startEpochMillis = Instant.parse("2024-10-27T00:30:00.125Z").toEpochMilli(),
                endEpochMillis = Instant.parse("2024-10-27T01:30:00.875Z").toEpochMilli(),
                timeZone = "Europe/London",
                status = PrivateActivityStatus.Submitted,
                activityId = UUID.randomUUID().toString(),
            ),
        )

        val encoded = PrivateWhoopStateCodec.encode(state)

        assertTrue(encoded.contains("\"version\":1"))
        assertEquals(state, PrivateWhoopStateCodec.decode(encoded))
    }

    @Test
    fun codecRejectsEveryVersionOtherThanTheExactSupportedVersion() {
        for (version in listOf(0, 2, 99)) {
            val encoded = """{"version":$version,"session":null,"draft":null}"""
            val error = assertFailsWith<PrivateWhoopException> { PrivateWhoopStateCodec.decode(encoded) }
            assertEquals(PrivateWhoopError.StorageFailed, error.reason)
        }
    }

    @Test
    fun codecRejectsMalformedJsonUnknownFieldsAndOversizedPayloads() {
        val malformed = listOf(
            "{not-json",
            """{"version":1,"session":null,"draft":null,"extra":"field"}""",
            " ".repeat(8_193),
        )

        malformed.forEach { payload ->
            val error = assertFailsWith<PrivateWhoopException> { PrivateWhoopStateCodec.decode(payload) }
            assertEquals(PrivateWhoopError.StorageFailed, error.reason)
        }
    }

    @Test
    fun codecFailureDoesNotExposeMalformedPayloadOrSyntheticCredentialText() {
        val privateValue = "synthetic-access-token-do-not-leak"
        val payload = """{"version":8,"session":{"accessToken":"$privateValue"}}"""

        val error = assertFailsWith<PrivateWhoopException> { PrivateWhoopStateCodec.decode(payload) }

        assertEquals(PrivateWhoopError.StorageFailed, error.reason)
        assertFalse(error.message.orEmpty().contains(privateValue))
        assertFalse(error.toString().contains(privateValue))
    }

    @Test
    fun encoderRejectsInvalidDraftInsteadOfPersistingIt() {
        val invalidDraft = PrivateActivityDraft(
            id = "not-a-uuid",
            sport = PrivateWhoopSport.Running,
            startEpochMillis = 1_000,
            endEpochMillis = 61_000,
            timeZone = "UTC",
        )

        val error = assertFailsWith<PrivateWhoopException> {
            PrivateWhoopStateCodec.encode(PrivateWhoopState(session = testSession(), draft = invalidDraft))
        }

        assertEquals(PrivateWhoopError.StorageFailed, error.reason)
    }

    @Test
    fun provisioningAcceptsOnlyThePrivateProvisioningAuthorityAndExactFieldSet() {
        val now = Instant.ofEpochSecond(TEST_NOW)
        val parsed = PrivateWhoopProvisioning.parse(provisioningUri(), now)

        assertEquals("import-access-token-synthetic-123", parsed.accessToken)
        assertEquals("import-refresh-token-synthetic-123", parsed.refreshToken)
        assertEquals(TEST_NOW + 61, parsed.expiresAtEpochSeconds)

        listOf(
            "whoof://provision?v=1&access_token=import-access-token-synthetic-123&refresh_token=import-refresh-token-synthetic-123&expires_at=${TEST_NOW + 61}",
            "https://api.prod.whoop.com/oauth/authorize?client_id=synthetic-public-client",
            "whoof://private-provision?v=1&access_token=import-access-token-synthetic-123&refresh_token=import-refresh-token-synthetic-123&expires_at=${TEST_NOW + 61}&extra=x",
            "whoof://private-provision?v=1&access_token=import-access-token-synthetic-123&expires_at=${TEST_NOW + 61}",
            "whoof://private-provision.evil.invalid?v=1&access_token=import-access-token-synthetic-123&refresh_token=import-refresh-token-synthetic-123&expires_at=${TEST_NOW + 61}",
        ).forEach { uri ->
            val error = assertFailsWith<PrivateWhoopException> { PrivateWhoopProvisioning.parse(uri, now) }
            assertEquals(PrivateWhoopError.InvalidInput, error.reason)
        }
    }

    @Test
    fun provisioningRejectsDuplicateQueryKeys() {
        val uri = "whoof://private-provision?v=1&v=1&access_token=import-access-token-synthetic-123&refresh_token=import-refresh-token-synthetic-123&expires_at=${TEST_NOW + 61}"

        val error = assertFailsWith<PrivateWhoopException> {
            PrivateWhoopProvisioning.parse(uri, Instant.ofEpochSecond(TEST_NOW))
        }

        assertEquals(PrivateWhoopError.InvalidInput, error.reason)
    }

    @Test
    fun provisioningRejectsExpiryAtOrWithinSixtySeconds() {
        val now = Instant.ofEpochSecond(TEST_NOW)
        for (offset in listOf(-1L, 0L, 1L, 60L)) {
            val error = assertFailsWith<PrivateWhoopException> {
                PrivateWhoopProvisioning.parse(provisioningUri(TEST_NOW + offset), now)
            }
            assertEquals(PrivateWhoopError.InvalidInput, error.reason)
        }
    }

    @Test
    fun provisioningErrorsAreGenericAndDoNotEchoImportedValues() {
        val syntheticSecret = "synthetic-access-token-private-value"
        val invalid = "whoof://other-host?v=1&access_token=$syntheticSecret"

        val error = assertFailsWith<PrivateWhoopException> {
            PrivateWhoopProvisioning.parse(invalid, Instant.ofEpochSecond(TEST_NOW))
        }

        assertEquals(PrivateWhoopError.InvalidInput, error.reason)
        assertNotEquals(syntheticSecret, error.message)
        assertFalse(error.toString().contains(syntheticSecret))
    }

    private companion object {
        const val TEST_NOW = 1_735_732_800L
    }
}

private fun testSession() = PrivateWhoopSession(
    accessToken = "codec-access-token-synthetic-123",
    refreshToken = "codec-refresh-token-synthetic-123",
    expiresAtEpochSeconds = 1_735_736_400L,
)

private fun provisioningUri(expiry: Long = 1_735_732_861L) =
    "whoof://private-provision?v=1&access_token=import-access-token-synthetic-123&refresh_token=import-refresh-token-synthetic-123&expires_at=$expiry"
