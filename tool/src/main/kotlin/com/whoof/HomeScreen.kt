package com.whoof

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface HomeState {
    data object Starting : HomeState
    data object NotConnected : HomeState
    data object Loading : HomeState
    data class Ready(val recovery: RecoveryRecord?) : HomeState
    data class Error(val message: String, val connected: Boolean) : HomeState
}

internal class HomeViewModel(
    private val tokenStore: TokenStore,
    private val api: WhoopApi = WhoopApi(),
) : LightViewModel<Unit>() {
    private val _state = MutableStateFlow<HomeState>(HomeState.Starting)
    val state: StateFlow<HomeState> = _state
    @Volatile
    private var hasCredentials = false
    private var initialized = false
    @Volatile
    private var refreshGeneration = 0L
    private var refreshJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (initialized) return
        initialized = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                hasCredentials = tokenStore.load() != null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.value = HomeState.Error("Unable to load stored credentials.", false)
                return@launch
            }
            if (!hasCredentials) {
                _state.value = HomeState.NotConnected
            } else {
                refresh()
            }
        }
    }

    fun provision(payload: String) {
        viewModelScope.launch {
            val credentials = runCatching { ProvisioningPayload.parse(payload) }
                .getOrElse {
                    _state.value = HomeState.Error(it.message ?: "Invalid provisioning QR code.", false)
                    return@launch
                }
            _state.value = HomeState.Loading
            try {
                withContext(Dispatchers.IO) { tokenStore.save(credentials) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.value = HomeState.Error("Unable to store the access token securely.", false)
                return@launch
            }
            hasCredentials = true
            refresh()
        }
    }

    fun refresh() {
        if (!hasCredentials) {
            _state.value = HomeState.NotConnected
            return
        }
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        _state.value = HomeState.Loading
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val credentials = tokenStore.load()
                    ?: throw WhoopApiException(WhoopApiError.Unauthorized)
                val recovery = api.latestRecovery(credentials.accessToken)
                if (generation == refreshGeneration) {
                    _state.value = HomeState.Ready(recovery)
                }
            } catch (error: WhoopApiException) {
                if (generation == refreshGeneration) {
                    _state.value = HomeState.Error(error.reason.userMessage(), true)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (generation == refreshGeneration) {
                    _state.value = HomeState.Error("Unable to reach WHOOP.", true)
                }
            }
        }
    }

    fun disconnect() {
        ++refreshGeneration
        refreshJob?.cancel()
        hasCredentials = false
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { tokenStore.clear() }
                _state.value = HomeState.NotConnected
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.value = HomeState.Error("Unable to clear stored credentials.", false)
            }
        }
    }

    override fun onCleared() {
        api.close()
        super.onCleared()
    }

    private fun WhoopApiError.userMessage(): String = when (this) {
        WhoopApiError.Unauthorized -> "Access token expired. Scan a new token."
        WhoopApiError.MissingScope -> "Token is missing the read:recovery scope."
        WhoopApiError.RateLimited -> "WHOOP rate limit reached. Try again later."
        WhoopApiError.InvalidRequest -> "WHOOP rejected the request."
        WhoopApiError.Unavailable -> "WHOOP returned an unexpected response."
    }
}

@InitialScreen
internal class HomeScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, HomeViewModel>(sealedActivity) {
    override val viewModelClass: Class<HomeViewModel> = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel = HomeViewModel(TokenStore(lightContext.dataStore))

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val colors by LightThemeController.colors.collectAsState()

        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                LightText(text = "WHOOF", variant = LightTextVariant.Heading)
                LightText(
                    text = "WHOOP RECOVERY",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                )
                Divider(Modifier.padding(vertical = 22.dp))

                when (val current = state) {
                    HomeState.Starting -> Status("STARTING")
                    HomeState.NotConnected -> NotConnected(
                        onConnect = {
                            navigateTo(::ProvisioningScreen) { viewModel.provision(it) }
                        },
                    )
                    HomeState.Loading -> Status("SYNCING")
                    is HomeState.Ready -> RecoveryContent(
                        recovery = current.recovery,
                        onRefresh = viewModel::refresh,
                        onDisconnect = viewModel::disconnect,
                    )
                    is HomeState.Error -> ErrorContent(
                        message = current.message,
                        connected = current.connected,
                        onRetry = viewModel::refresh,
                        onReplaceToken = {
                            navigateTo(::ProvisioningScreen) { viewModel.provision(it) }
                        },
                        onDisconnect = viewModel::disconnect,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotConnected(onConnect: () -> Unit) {
    LightText(text = "NO TOKEN", variant = LightTextVariant.Subheading)
    LightText(
        text = "Scan a locally generated provisioning QR code.",
        variant = LightTextVariant.Paragraph,
        lighten = true,
        modifier = Modifier.padding(top = 12.dp),
    )
    Action("SCAN TOKEN", onConnect, Modifier.padding(top = 32.dp))
}

@Composable
private fun ColumnScope.RecoveryContent(
    recovery: RecoveryRecord?,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    if (recovery == null) {
        LightText(text = "NO RECOVERY", variant = LightTextVariant.Subheading)
        LightText(
            text = "WHOOP returned no recovery records.",
            variant = LightTextVariant.Paragraph,
            lighten = true,
            modifier = Modifier.padding(top = 12.dp),
        )
    } else if (recovery.score?.recoveryScore == null) {
        LightText(text = recovery.scoreState, variant = LightTextVariant.Subheading)
        LightText(
            text = "Latest record has not been scored yet.",
            variant = LightTextVariant.Paragraph,
            lighten = true,
            modifier = Modifier.padding(top = 12.dp),
        )
    } else {
        LightText(
            text = "${recovery.score.recoveryScore}%",
            variant = LightTextVariant.Title,
            modifier = Modifier.fillMaxWidth(),
            align = TextAlign.Center,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric("HRV", recovery.score.hrvRmssdMilli.metricValue("ms"))
            Metric("RHR", recovery.score.restingHeartRate.metricValue("bpm"), Alignment.End)
        }
    }

    Spacer(Modifier.weight(1f))
    recovery?.let {
        LightText(
            text = "UPDATED ${it.updatedAt}",
            variant = LightTextVariant.Micro,
            lighten = true,
        )
    }
    Divider(Modifier.padding(vertical = 18.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Action("REFRESH", onRefresh)
        Action("DISCONNECT", onDisconnect)
    }
}

private fun Double?.metricValue(unit: String): String =
    this?.let { "${it.toInt()} $unit" } ?: "--"

@Composable
private fun ErrorContent(
    message: String,
    connected: Boolean,
    onRetry: () -> Unit,
    onReplaceToken: () -> Unit,
    onDisconnect: () -> Unit,
) {
    LightText(text = "UNAVAILABLE", variant = LightTextVariant.Subheading)
    LightText(
        text = message,
        variant = LightTextVariant.Paragraph,
        lighten = true,
        modifier = Modifier.padding(top = 12.dp),
    )
    Action(if (connected) "RETRY" else "SCAN TOKEN", if (connected) onRetry else onReplaceToken,
        Modifier.padding(top = 28.dp))
    if (connected) {
        Action("REPLACE TOKEN", onReplaceToken, Modifier.padding(top = 20.dp))
        Action("DISCONNECT", onDisconnect, Modifier.padding(top = 20.dp))
    }
}

@Composable
private fun Status(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LightText(text = text, variant = LightTextVariant.Subheading, lighten = true)
    }
}

@Composable
private fun Metric(label: String, value: String, alignment: Alignment.Horizontal = Alignment.Start) {
    Column(horizontalAlignment = alignment) {
        LightText(text = label, variant = LightTextVariant.Superfine, lighten = true)
        LightText(text = value, variant = LightTextVariant.Subheading)
    }
}

@Composable
private fun Action(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LightText(
        text = text,
        variant = LightTextVariant.Button,
        modifier = modifier
            .lightClickable(onClick = onClick)
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun Divider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LightThemeTokens.colors.contentSecondary),
    )
}
