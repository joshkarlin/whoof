package com.whoof

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TokenStoreTest {
    @Test
    fun decodesExistingAccessOnlyStorage() {
        val credentials = StoredCredentialsCodec.decode(
            "{\"accessToken\":\"access-token-placeholder-12345\"}",
        )

        assertEquals("access-token-placeholder-12345", credentials.accessToken)
        assertNull(credentials.clientId)
        assertNull(credentials.clientSecret)
        assertNull(credentials.refreshToken)
        assertNull(credentials.expiresIn)
    }

    @Test
    fun serializesAndDecodesV2CredentialsTogether() {
        val credentials = WhoopCredentials(
            accessToken = "access-token-placeholder-12345",
            clientId = "client-id-placeholder",
            clientSecret = "client-secret-placeholder",
            refreshToken = "refresh-token-placeholder-12345",
            expiresIn = 3_600L,
        )

        val serialized = StoredCredentialsCodec.encode(credentials)
        val decoded = StoredCredentialsCodec.decode(serialized)

        assertEquals(credentials, decoded)
        assertEquals(
            "{\"accessToken\":\"access-token-placeholder-12345\"," +
                "\"clientId\":\"client-id-placeholder\"," +
                "\"clientSecret\":\"client-secret-placeholder\"," +
                "\"refreshToken\":\"refresh-token-placeholder-12345\"," +
                "\"expiresIn\":3600}",
            serialized,
        )
    }

    @Test
    fun rejectsUnknownStorageFields() {
        assertFailsWith<IllegalArgumentException> {
            StoredCredentialsCodec.decode(
                "{\"accessToken\":\"access-token-placeholder-12345\",\"unexpected\":\"value\"}",
            )
        }
    }

    @Test
    fun rejectsInvalidStoredCredentials() {
        assertFailsWith<IllegalArgumentException> {
            StoredCredentialsCodec.decode("{\"accessToken\":\"short\"}")
        }
        assertFailsWith<IllegalArgumentException> {
            StoredCredentialsCodec.decode(
                "{\"accessToken\":\"access-token-placeholder-12345\",\"refreshToken\":\"short\"}",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StoredCredentialsCodec.decode(
                "{\"accessToken\":\"access-token-placeholder-12345\",\"clientId\":\"client-id-placeholder\"}",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            val oversizedToken = "a".repeat(CredentialValidation.MAX_TOKEN_LENGTH + 1)
            StoredCredentialsCodec.decode(
                "{\"accessToken\":\"$oversizedToken\"}",
            )
        }
    }

    @Test
    fun rejectsInvalidV2ClientCredentialsAndExpiry() {
        val common =
            "\"accessToken\":\"access-token-placeholder-12345\"," +
                "\"clientId\":\"client-id-placeholder\"," +
                "\"clientSecret\":\"client-secret-placeholder\"," +
                "\"refreshToken\":\"refresh-token-placeholder-12345\"," +
                "\"expiresIn\":3600"

        for (json in listOf(
            "{\"accessToken\":\"access-token-placeholder-12345\",\"clientId\":\"client id-placeholder\"," +
                "\"clientSecret\":\"client-secret-placeholder\",\"refreshToken\":\"refresh-token-placeholder-12345\",\"expiresIn\":3600}",
            "{\"accessToken\":\"access-token-placeholder-12345\",\"clientId\":\"client-id-placeholder\"," +
                "\"clientSecret\":\"client-secret-placeholder\\n\",\"refreshToken\":\"refresh-token-placeholder-12345\",\"expiresIn\":3600}",
            "{${common.replace("3600", "0")}}",
            "{${common.replace("3600", "-1")}}",
        )) {
            assertFailsWith<IllegalArgumentException> {
                StoredCredentialsCodec.decode(json)
            }
        }
    }

    @Test
    fun rejectsOversizedSerializedStorage() {
        val oversized = "{" + "a".repeat(CredentialValidation.MAX_STORED_JSON_BYTES) + "}"

        assertFailsWith<IllegalArgumentException> {
            StoredCredentialsCodec.decode(oversized)
        }
    }
}
