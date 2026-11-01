package com.whoof.privateapi

import kotlin.test.Test
import kotlin.test.assertEquals

class WhoopPrivateHeadersTest {
    @Test
    fun readAndAccountHeadersUseTheProvidedZone() {
        assertEquals("America/New_York", WhoopPrivateHeaders.read("synthetic-token", "America/New_York")["x-whoop-time-zone"])
        assertEquals("Asia/Tokyo", WhoopPrivateHeaders.account("synthetic-token", "Asia/Tokyo")["x-whoop-time-zone"])
    }

    @Test
    fun currentZoneIsUsedWhenValidAndFallsBackOtherwise() {
        assertEquals("Australia/Sydney", WhoopPrivateHeaders.currentTimeZone { "Australia/Sydney" })
        assertEquals("UTC", WhoopPrivateHeaders.currentTimeZone { "UTC" })
        assertEquals(WhoopPrivateHeaders.DEFAULT_TIME_ZONE, WhoopPrivateHeaders.currentTimeZone { "Not/A_Zone" })
        assertEquals(WhoopPrivateHeaders.DEFAULT_TIME_ZONE, WhoopPrivateHeaders.currentTimeZone { null })
        assertEquals(WhoopPrivateHeaders.DEFAULT_TIME_ZONE, WhoopPrivateHeaders.currentTimeZone { error("no zone") })
    }

    @Test
    fun defaultHeadersCarryTheDevicesCurrentValidZone() {
        val zone = WhoopPrivateHeaders.read("synthetic-token").getValue("x-whoop-time-zone")
        assertEquals(zone, WhoopPrivateHeaders.currentTimeZone())
    }
}
