package com.whoof

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProvisioningPayloadTest {
    @Test
    fun parsesVersionedAccessToken() {
        val token = "header.payload.signature-value"
        assertEquals(
            token,
            ProvisioningPayload.parse("whoof://provision?v=1&access_token=$token").accessToken,
        )
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
                "whoof://provision?v=2&access_token=header.payload.signature",
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
                    "&refresh_token=refresh-token-value",
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
}
