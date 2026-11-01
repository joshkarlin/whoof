package com.whoof.privateapi

import java.net.URI
import java.net.URLDecoder
import java.time.Instant

/** A scanned private-session QR: a full session (v=1) or only a refresh token (v=2). */
internal sealed interface PrivateWhoopProvisioned {
    class Session(val session: PrivateWhoopSession) : PrivateWhoopProvisioned {
        override fun toString(): String = "Session(redacted)"
    }

    class RefreshToken(val value: String) : PrivateWhoopProvisioned {
        override fun toString(): String = "RefreshToken(redacted)"
    }
}

internal object PrivateWhoopProvisioning {
    /** v=1 payloads only; a v=2 payload is rejected here. Use [parsePayload] for both. */
    fun parse(payload: String, now: Instant = Instant.now()): PrivateWhoopSession =
        (parsePayload(payload, now) as? PrivateWhoopProvisioned.Session)?.session
            ?: throw PrivateWhoopException(PrivateWhoopError.InvalidInput)

    fun parsePayload(payload: String, now: Instant = Instant.now()): PrivateWhoopProvisioned {
        try {
            requirePrivate(payload.length <= 2_900 && payload.toByteArray(Charsets.UTF_8).size <= 2_900)
            val uri = URI(payload)
            requirePrivate(uri.scheme == "whoof" && uri.host == "private-provision" &&
                uri.port == -1 && uri.userInfo == null && uri.rawPath.isEmpty() && uri.fragment == null)
            val fields = linkedMapOf<String, String>()
            val query = uri.rawQuery ?: throw PrivateWhoopException(PrivateWhoopError.InvalidInput)
            query.split('&').forEach { part ->
                val split = part.indexOf('=')
                requirePrivate(split > 0)
                val key = URLDecoder.decode(part.substring(0, split), "UTF-8")
                val value = URLDecoder.decode(part.substring(split + 1), "UTF-8")
                requirePrivate(key !in fields)
                fields[key] = value
            }
            if (fields["v"] == "2") {
                requirePrivate(fields.keys == setOf("v", "refresh_token") && validPrivateToken(fields.getValue("refresh_token")))
                return PrivateWhoopProvisioned.RefreshToken(fields.getValue("refresh_token"))
            }
            requirePrivate(fields.keys == setOf("v", "access_token", "refresh_token", "expires_at") && fields["v"] == "1")
            val expiry = fields.getValue("expires_at").toLongOrNull()
                ?: throw PrivateWhoopException(PrivateWhoopError.InvalidInput)
            requirePrivate(expiry > now.epochSecond + 60)
            return PrivateWhoopProvisioned.Session(
                PrivateWhoopSession(fields.getValue("access_token"), fields.getValue("refresh_token"), expiry),
            )
        } catch (_: Exception) {
            throw PrivateWhoopException(PrivateWhoopError.InvalidInput)
        }
    }
}
