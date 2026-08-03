package com.whoof

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable

@Serializable
internal data class WhoopCredentials(
    val accessToken: String,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Long? = null,
) {
    init {
        CredentialValidation.validate(this)
    }
}

internal object CredentialValidation {
    const val MIN_TOKEN_LENGTH = 20
    const val MAX_TOKEN_LENGTH = 2_048
    const val MAX_STORED_JSON_BYTES = 8_192

    fun validate(credentials: WhoopCredentials) {
        validateAccessToken(credentials.accessToken)

        val v2Fields = listOf(
            credentials.clientId,
            credentials.clientSecret,
            credentials.refreshToken,
            credentials.expiresIn,
        )
        require(v2Fields.all { it == null } || v2Fields.all { it != null }) {
            "Credentials are incomplete."
        }
        if (v2Fields.all { it != null }) {
            validateClientId(credentials.clientId!!)
            validateClientSecret(credentials.clientSecret!!)
            validateRefreshToken(credentials.refreshToken!!)
            validateExpiresIn(credentials.expiresIn!!)
        }
    }

    fun validateAccessToken(token: String) {
        require(isValidToken(token)) { "Access token is missing or malformed." }
    }

    fun validateClientId(clientId: String) {
        require(isPrintableNonWhitespace(clientId)) { "Client ID is missing or malformed." }
    }

    fun validateClientSecret(clientSecret: String) {
        require(isPrintableNonWhitespace(clientSecret)) { "Client secret is missing or malformed." }
    }

    fun validateRefreshToken(token: String) {
        require(isValidToken(token)) { "Refresh token is missing or malformed." }
    }

    fun validateExpiresIn(expiresIn: Long) {
        require(expiresIn > 0) { "Token expiry must be positive." }
    }

    private fun isValidToken(token: String): Boolean =
        token.length in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH &&
            token.all { character -> character.code in 0x21..0x7e }

    private fun isPrintableNonWhitespace(value: String): Boolean =
        value.isNotEmpty() && value.all { character -> character.code in 0x21..0x7e }
}

internal object ProvisioningPayload {
    private const val MAX_PAYLOAD_LENGTH = 2_900
    private val SUPPORTED_FIELDS = setOf(
        "v",
        "client_id",
        "client_secret",
        "access_token",
        "refresh_token",
        "expires_in",
    )
    private val VERSION_ONE_FIELDS = setOf("v", "access_token")
    private val VERSION_TWO_FIELDS = setOf(
        "v",
        "client_id",
        "client_secret",
        "access_token",
        "refresh_token",
        "expires_in",
    )

    fun parse(payload: String): WhoopCredentials {
        require(
            payload.length <= MAX_PAYLOAD_LENGTH &&
                payload.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_LENGTH,
        ) { "Provisioning QR code is too large." }

        val uri = runCatching { URI(payload) }
            .getOrElse { throw IllegalArgumentException("Invalid provisioning QR code.") }
        require(
            uri.scheme == "whoof" &&
                uri.host == "provision" &&
                uri.port == -1 &&
                uri.userInfo == null &&
                uri.rawPath.isEmpty() &&
                uri.fragment == null,
        ) { "This is not a Whoof provisioning QR code." }

        val values = parseQuery(uri.rawQuery)
        return when (values["v"]) {
            "1" -> {
                require(values.keys == VERSION_ONE_FIELDS) {
                    "Version 1 provisioning QR codes contain unsupported fields."
                }
                WhoopCredentials(accessToken = values.required("access_token"))
            }

            "2" -> {
                require(values.keys == VERSION_TWO_FIELDS) {
                    "Version 2 provisioning QR codes require the complete credential set."
                }
                val expiresIn = values.required("expires_in").toLongOrNull()
                require(expiresIn != null && expiresIn > 0) {
                    "Provisioning QR code has an invalid expiry."
                }
                WhoopCredentials(
                    accessToken = values.required("access_token"),
                    clientId = values.required("client_id"),
                    clientSecret = values.required("client_secret"),
                    refreshToken = values.required("refresh_token"),
                    expiresIn = expiresIn,
                )
            }

            else -> throw IllegalArgumentException("Unsupported provisioning QR code version.")
        }
    }

    private fun parseQuery(rawQuery: String?): LinkedHashMap<String, String> {
        require(!rawQuery.isNullOrEmpty()) { "Provisioning QR code has no fields." }

        val values = LinkedHashMap<String, String>()
        rawQuery.split('&').forEach { part ->
            require(part.isNotEmpty()) { "Provisioning QR code contains an empty field." }
            val separator = part.indexOf('=')
            require(separator > 0) { "Provisioning QR code contains a malformed field." }

            val key = decode(part.substring(0, separator))
            require(key.isNotBlank()) { "Provisioning QR code contains an empty field name." }
            require(key in SUPPORTED_FIELDS) {
                "Provisioning QR code contains an unsupported field."
            }
            require(!values.containsKey(key)) { "Provisioning QR code contains a duplicate field." }
            values[key] = decode(part.substring(separator + 1))
        }
        require(values.containsKey("v")) { "Provisioning QR code has no version field." }
        return values
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrElse { throw IllegalArgumentException("Invalid provisioning QR code.") }

    private fun Map<String, String>.required(key: String): String =
        get(key) ?: throw IllegalArgumentException("Provisioning QR code is missing a required field.")
}
