package com.whoof

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class ProvisioningPayloadTest {
    @Test
    fun parsesVersionOneAccessOnlyPayload() {
        val token = "header.payload.signature-value"
        val credentials = ProvisioningPayload.parse("whoof://provision?v=1&access_token=$token")

        assertEquals(token, credentials.accessToken)
        assertNull(credentials.refreshToken)
    }

    @Test
    fun parsesVersionTwoWithTheExactCredentialFieldSet() {
        val credentials = ProvisioningPayload.parse(
            "whoof://provision?v=2" +
                "&client_id=client-id-placeholder" +
                "&client_secret=client-secret-placeholder" +
                "&access_token=access-token-placeholder-12345" +
                "&refresh_token=refresh-token-placeholder-12345" +
                "&expires_in=3600",
        )

        assertEquals("client-id-placeholder", credentials.clientId)
        assertEquals("client-secret-placeholder", credentials.clientSecret)
        assertEquals("access-token-placeholder-12345", credentials.accessToken)
        assertEquals("refresh-token-placeholder-12345", credentials.refreshToken)
        assertEquals(3_600L, credentials.expiresIn)
    }

    @Test
    fun decodesEscapedToken() {
        assertEquals(
            "token+with/symbols.and-padding",
            ProvisioningPayload.parse(
                "whoof://provision?v=1&access_token=token%2Bwith%2Fsymbols.and-padding",
            ).accessToken,
        )
    }

    @Test
    fun rejectsWrongScheme() {
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(
                "https://example.com/provision?v=1&access_token=header.payload.signature",
            )
        }
    }

    @Test
    fun rejectsUnsupportedVersion() {
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(
                "whoof://provision?v=3&access_token=header.payload.signature",
            )
        }
    }

    @Test
    fun rejectsVersionOneRefreshToken() {
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(
                "whoof://provision?v=1&access_token=header.payload.signature-value" +
                    "&refresh_token=refresh-token-placeholder-12345",
            )
        }
    }

    @Test
    fun rejectsVersionTwoMissingRefreshToken() {
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(
                "whoof://provision?v=2" +
                    "&client_id=client-id-placeholder" +
                    "&client_secret=client-secret-placeholder" +
                    "&access_token=access-token-placeholder-12345" +
                    "&expires_in=3600",
            )
        }
    }

    @Test
    fun rejectsVersionTwoMissingAccessToken() {
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(
                "whoof://provision?v=2" +
                    "&client_id=client-id-placeholder" +
                    "&client_secret=client-secret-placeholder" +
                    "&refresh_token=refresh-token-placeholder-12345" +
                    "&expires_in=3600",
            )
        }
    }

    @Test
    fun rejectsExtraVersionTwoFields() {
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(
                "whoof://provision?v=2" +
                    "&client_id=client-id-placeholder" +
                    "&client_secret=client-secret-placeholder" +
                    "&access_token=access-token-placeholder-12345" +
                    "&refresh_token=refresh-token-placeholder-12345" +
                    "&expires_in=3600&scope=read",
            )
        }
    }

    @Test
    fun rejectsDuplicateFields() {
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(
                "whoof://provision?v=1&access_token=header.payload.signature-value" +
                    "&access_token=another-token-value",
            )
        }
    }

    @Test
    fun rejectsUnsupportedCredentialFields() {
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(
                "whoof://provision?v=1&access_token=header.payload.signature-value" +
                    "&client_id=client-id-placeholder",
            )
        }
    }

    @Test
    fun rejectsPayloadJustOverQrSafeCeiling() {
        val prefix = "whoof://provision?v=1&access_token="
        val payload = prefix + "a".repeat(2_901 - prefix.length)
        assertEquals(2_901, payload.length)

        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(payload)
        }
    }

    @Test
    fun rejectsMalformedQueryFields() {
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(
                "whoof://provision?v=2" +
                    "&client_id=client-id-placeholder" +
                    "&client_secret=client-secret-placeholder" +
                    "&access_token=access-token-placeholder-12345" +
                    "&refresh_token=refresh-token-placeholder-12345&expires_in",
            )
        }
    }

    @Test
    fun rejectsInvalidV2CredentialsAndExpiry() {
        assertFailsWith<IllegalArgumentException> {
            parseVersionTwo(clientId = "", clientSecret = "client-secret-placeholder")
        }
        assertFailsWith<IllegalArgumentException> {
            parseVersionTwo(clientId = "client id-placeholder")
        }
        assertFailsWith<IllegalArgumentException> {
            parseVersionTwo(clientSecret = "client\nsecret-placeholder")
        }
        assertFailsWith<IllegalArgumentException> {
            parseVersionTwo(expiresIn = "0")
        }
        assertFailsWith<IllegalArgumentException> {
            parseVersionTwo(expiresIn = "-1")
        }
        assertFailsWith<IllegalArgumentException> {
            parseVersionTwo(expiresIn = "not-a-duration")
        }
    }

    @Test
    fun rejectsEmptyAndOversizedTokens() {
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(
                "whoof://provision?v=2" +
                    "&client_id=client-id-placeholder" +
                    "&client_secret=client-secret-placeholder" +
                    "&access_token=" +
                    "&refresh_token=refresh-token-placeholder-12345&expires_in=3600",
            )
        }
        val oversizedToken = "a".repeat(2_049)
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(
                "whoof://provision?v=2" +
                    "&client_id=client-id-placeholder" +
                    "&client_secret=client-secret-placeholder" +
                    "&access_token=$oversizedToken" +
                    "&refresh_token=refresh-token-placeholder-12345&expires_in=3600",
            )
        }
    }

    @Test
    fun rejectsWhitespaceAndControlCharactersInCredentials() {
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(
                "whoof://provision?v=2" +
                    "&client_id=client-id-placeholder" +
                    "&client_secret=client-secret-placeholder" +
                    "&access_token=access-token-placeholder-12345" +
                    "&refresh_token=refresh%20token-placeholder-12345&expires_in=3600",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(
                "whoof://provision?v=2" +
                    "&client_id=client-id-placeholder" +
                    "&client_secret=client-secret-placeholder" +
                    "&access_token=access-token-placeholder-12345" +
                    "&refresh_token=refresh%00token-placeholder-12345&expires_in=3600",
            )
        }
    }

    @Test
    fun rejectsUriDecorationsOutsideProvisioningContract() {
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(
                "whoof://provision:443?v=1&access_token=header.payload.signature-value",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload.parse(
                "whoof://provision?v=1&access_token=header.payload.signature-value#fragment",
            )
        }
    }

    private fun parseVersionTwo(
        clientId: String = "client-id-placeholder",
        clientSecret: String = "client-secret-placeholder",
        expiresIn: String = "3600",
    ): WhoopCredentials = ProvisioningPayload.parse(
        "whoof://provision?v=2" +
            "&client_id=${encode(clientId)}" +
            "&client_secret=${encode(clientSecret)}" +
            "&access_token=access-token-placeholder-12345" +
            "&refresh_token=refresh-token-placeholder-12345" +
            "&expires_in=$expiresIn",
    )

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
