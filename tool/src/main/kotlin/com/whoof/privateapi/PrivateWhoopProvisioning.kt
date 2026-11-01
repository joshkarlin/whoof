package com.whoof.privateapi

import java.net.URI
import java.net.URLDecoder
import java.time.Instant

internal object PrivateWhoopProvisioning {
    fun parse(payload: String, now: Instant = Instant.now()): PrivateWhoopSession {
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
            requirePrivate(fields.keys == setOf("v", "access_token", "refresh_token", "expires_at") && fields["v"] == "1")
            val expiry = fields.getValue("expires_at").toLongOrNull()
                ?: throw PrivateWhoopException(PrivateWhoopError.InvalidInput)
            requirePrivate(expiry > now.epochSecond + 60)
            return PrivateWhoopSession(fields.getValue("access_token"), fields.getValue("refresh_token"), expiry)
        } catch (_: Exception) {
            throw PrivateWhoopException(PrivateWhoopError.InvalidInput)
        }
    }
}
