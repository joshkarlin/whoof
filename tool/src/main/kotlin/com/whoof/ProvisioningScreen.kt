package com.whoof

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

internal class ProvisioningScreen(
    sealedActivity: SealedLightActivity,
    private val title: String = "SCAN ACCOUNT QR",
) : SimpleLightScreen<String>(sealedActivity) {
    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        var scannedValue by remember { mutableStateOf<String?>(null) }

        LightTheme(colors = colors) {
            LightQrCodeScanner(
                title = title,
                onScanned = { scannedValue = it },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }

        LaunchedEffect(scannedValue) {
            scannedValue?.let { goBack(it) }
        }
    }
}
