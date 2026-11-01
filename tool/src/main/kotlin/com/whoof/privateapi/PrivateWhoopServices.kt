package com.whoof.privateapi

import com.thelightphone.sdk.SealedLightContext

/** One service per process, so every screen shares the same operation lock and session record. */
internal object PrivateWhoopServices {
    @Volatile
    private var instance: WhoopPrivateService? = null

    fun getInstance(context: SealedLightContext): WhoopPrivateService =
        instance ?: synchronized(this) {
            instance ?: WhoopPrivateService(
                store = EncryptedPrivateWhoopStore(context.dataStore),
                client = WhoopPrivateClient(),
            ).also { instance = it }
        }
}
