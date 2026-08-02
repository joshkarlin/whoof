package com.whoof

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable

@Serializable
internal data class WhoopCredentials(
    val accessToken: String,
)

internal object ProvisioningPayload {
    private const val MAX_PAYLOAD_LENGTH = 2_900
    private const val MIN_TOKEN_LENGTH = 20

    fun parse(payload: String): WhoopCredentials {
        require(payload.length <= MAX_PAYLOAD_LENGTH) { "Provisioning QR code is too large." }

        val uri = runCatching { URI(payload) }
            .getOrElse { throw IllegalArgumentException("Invalid provisioning QR code.") }
        require(uri.scheme == "whoof" && uri.host == "provision") {
            "This is not a Whoof provisioning QR code."
        }

        val values = LinkedHashMap<String, String>()
        uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.forEach { part ->
            val pieces = part.split('=', limit = 2)
            val key = decode(pieces[0])
            require(key.isNotBlank()) { "Provisioning QR code contains an empty field name." }
            require(!values.containsKey(key)) { "Provisioning QR code contains a duplicate field." }
            require(key in setOf("v", "access_token")) {
                "Provisioning QR code contains an unsupported field."
            }
            values[key] = decode(pieces.getOrElse(1) { "" })
        }

        require(values["v"] == "1") { "Unsupported provisioning QR code version." }
        val token = values["access_token"].orEmpty()
        require(token.length >= MIN_TOKEN_LENGTH && token.none(Char::isWhitespace)) {
            "Provisioning QR code does not contain a valid access token."
        }

        return WhoopCredentials(accessToken = token)
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
