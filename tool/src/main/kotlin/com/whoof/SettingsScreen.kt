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
    ReplaceToken,
    Disconnect,
}

internal class SettingsScreen(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<SettingsAction>(sealedActivity) {
    @Composable
    override fun Content() {
        WhoofPage(title = "SETTINGS", onBack = { goBack() }) {
            Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                SettingsRow("Sync now") { goBack(SettingsAction.SyncNow) }
                SettingsRow("Replace token") { goBack(SettingsAction.ReplaceToken) }
                SettingsRow("Disconnect and delete data") {
                    navigateTo(::ConfirmDisconnectScreen) { confirmed ->
                        if (confirmed) goBack(SettingsAction.Disconnect)
                    }
                }
            }
        }
    }
}

internal class ConfirmDisconnectScreen(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<Boolean>(sealedActivity) {
    @Composable
    override fun Content() {
        WhoofPage(title = "DISCONNECT", onBack = { goBack(false) }) {
            CenteredMessage(
                "Delete Whoof data?",
                "Removes your WHOOP credentials and cached records from this phone.",
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
