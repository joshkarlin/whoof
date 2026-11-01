package com.whoof

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.whoof.privateapi.PrivateWhoopServices
import com.whoof.privateapi.WhoopPrivateService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

internal class SettingsViewModel(
    private val service: WhoopPrivateService,
) : LightViewModel<SettingsAction>() {
    /** Null until the stored session has been read, so the row never flashes the wrong label. */
    private val _signedIn = MutableStateFlow<Boolean?>(null)
    val signedIn: StateFlow<Boolean?> = _signedIn

    override fun onScreenShow(screen: SimpleLightScreen<SettingsAction>) {
        super.onScreenShow(screen)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _signedIn.value = try {
                withContext(Dispatchers.IO) { service.state().session != null }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
        }
    }
}

internal class SettingsScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<SettingsAction, SettingsViewModel>(sealedActivity) {
    override val viewModelClass: Class<SettingsViewModel> = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel =
        SettingsViewModel(PrivateWhoopServices.getInstance(lightContext))

    @Composable
    override fun Content() {
        val signedIn by viewModel.signedIn.collectAsState()
        WhoofPage(title = "SETTINGS", onBack = { goBack() }) {
            Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                SettingsRow("Sync now") { goBack(SettingsAction.SyncNow) }
                SettingsRow("Replace token") { goBack(SettingsAction.ReplaceToken) }
                when (signedIn) {
                    true -> SettingsRow("WHOOP account") { navigateTo(::AccountScreen) { viewModel.refresh() } }
                    false -> SettingsRow("Sign in to WHOOP") { navigateTo(::SignInScreen) { viewModel.refresh() } }
                    null -> Unit
                }
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
