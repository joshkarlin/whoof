package com.whoof

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

internal enum class SettingsAction {
    SyncNow,

    /** The WHOOP account screen signed out, so Today must show its signed-out state. */
    SignedOut,
    DeleteData,
}

internal class SettingsScreen(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<SettingsAction>(sealedActivity) {
    @Composable
    override fun Content() {
        WhoofPage(title = "SETTINGS", onBack = { goBack() }) {
            Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                SettingsRow("Sync now") { goBack(SettingsAction.SyncNow) }
                SettingsRow("WHOOP account") {
                    navigateTo(::AccountScreen) { signedOut -> if (signedOut) goBack(SettingsAction.SignedOut) }
                }
                SettingsRow("Delete local data") {
                    navigateTo(::ConfirmDeleteDataScreen) { confirmed ->
                        if (confirmed) goBack(SettingsAction.DeleteData)
                    }
                }
            }
        }
    }
}

internal class ConfirmDeleteDataScreen(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<Boolean>(sealedActivity) {
    @Composable
    override fun Content() {
        WhoofPage(title = "DELETE DATA", onBack = { goBack(false) }) {
            CenteredMessage(
                "Delete local data?",
                "Signs out and removes your WHOOP sign-in, cached records, and activity timer from this phone.",
            )
            LightBottomBar(listOf(LightBarButton.Text(text = "DELETE", onClick = { goBack(true) })))
        }
    }
}

@Composable
private fun SettingsRow(text: String, onClick: () -> Unit) {
    LightText(
        text = text,
        variant = LightTextVariant.Heading,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.7f.gridUnitsAsDp()),
    )
}
