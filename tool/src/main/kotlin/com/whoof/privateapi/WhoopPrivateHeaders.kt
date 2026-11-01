package com.whoof.privateapi

import java.time.ZoneId

/** The one place the iOS-style request headers are built, for private and Developer-shaped reads. */
internal object WhoopPrivateHeaders {
    /** Used only when the phone reports a zone id that is not a valid region or offset. */
    const val DEFAULT_TIME_ZONE = "Europe/London"

    /** The phone's current zone id, read at request time; [DEFAULT_TIME_ZONE] if it is not valid. */
    fun currentTimeZone(zoneId: () -> String? = { ZoneId.systemDefault().id }): String {
        val id = try {
            zoneId()
        } catch (_: Exception) {
            null
        }
        return if (id != null && runCatching { ZoneId.of(id) }.isSuccess) id else DEFAULT_TIME_ZONE
    }

    /** Headers for a GET with a bearer token. Content-Type is left to the HTTP engine. */
    fun read(accessToken: String, timeZone: String = currentTimeZone()): Map<String, String> = linkedMapOf(
        "Authorization" to "Bearer $accessToken",
        "Accept" to "application/json",
        "User-Agent" to "iOS",
        "x-whoop-device-platform" to "iOS",
        "x-whoop-time-zone" to timeZone,
        "x-whoop-bundle-name" to "com.whoop.iphone",
        "x-whoop-ios-version" to "5.48.0",
        "x-whoop-ios-build-number" to "573097",
    )

    /** [read] plus an explicit JSON Content-Type, for the private client's own requests. */
    fun account(accessToken: String, timeZone: String = currentTimeZone()): Map<String, String> =
        read(accessToken, timeZone) + ("Content-Type" to "application/json")
}
