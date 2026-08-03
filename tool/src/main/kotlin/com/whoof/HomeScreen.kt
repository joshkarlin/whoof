package com.whoof

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal sealed interface HomeState {
    data object Starting : HomeState
    data object NotConnected : HomeState
    data object Loading : HomeState
    data object NoData : HomeState
    data object AuthorizationExpired : HomeState
    data object Offline : HomeState
    data class Ready(val today: TodayModel) : HomeState
    data class ApiError(val message: String, val connected: Boolean) : HomeState
}

/**
 * Presentation data for the Today screen. Recovery is populated by the current API;
 * cycle and sleep values remain nullable until their API calls are wired in.
 */
internal data class TodayModel(
    val recovery: RecoveryRecord? = null,
    val strain: Double? = null,
    val sleep: Double? = null,
    val syncedAt: String,
) {
    val hasData: Boolean
        get() = recovery != null || strain != null || sleep != null
}

internal class HomeViewModel(
    private val tokenStore: WhoopCredentialStore,
    private val api: WhoopRecoveryClient = WhoopApi(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> String = { Instant.now().toString() },
) : LightViewModel<Unit>() {
    private val tokenCoordinator = WhoopTokenCoordinator(tokenStore, api)
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
        start()
    }

    internal fun start() {
        if (initialized) return
        initialized = true
        viewModelScope.launch(ioDispatcher) {
            try {
                hasCredentials = tokenStore.load() != null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.value = HomeState.ApiError("Unable to load stored credentials.", false)
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
                    _state.value = HomeState.ApiError(it.message ?: "Invalid provisioning QR code.", false)
                    return@launch
                }
            _state.value = HomeState.Loading
            try {
                withContext(ioDispatcher) { tokenStore.save(credentials) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.value = HomeState.ApiError("Unable to store the access token securely.", false)
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
        refreshJob = viewModelScope.launch(ioDispatcher) {
            try {
                val today = tokenCoordinator.execute { accessToken ->
                    val recovery = api.latestRecovery(accessToken)
                    val cycle = api.latestCycle(accessToken)
                    val sleep = cycle?.let { api.sleepForCycle(accessToken, it.id) }
                    TodayModel(
                        recovery = recovery,
                        strain = cycle?.score?.strain,
                        sleep = sleep?.score?.sleepPerformancePercentage,
                        syncedAt = now(),
                    )
                }
                if (generation == refreshGeneration) {
                    _state.value = if (!today.hasData) {
                        HomeState.NoData
                    } else {
                        HomeState.Ready(today)
                    }
                }
            } catch (error: WhoopApiException) {
                if (generation == refreshGeneration) {
                    _state.value = if (error.reason == WhoopApiError.Unauthorized) {
                        HomeState.AuthorizationExpired
                    } else {
                        HomeState.ApiError(error.reason.userMessage(), true)
                    }
                }
            } catch (_: WhoopReprovisioningException) {
                if (generation == refreshGeneration) {
                    _state.value = HomeState.AuthorizationExpired
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (generation == refreshGeneration) {
                    _state.value = if (error.isOffline()) {
                        HomeState.Offline
                    } else {
                        HomeState.ApiError("Unable to reach WHOOP.", true)
                    }
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
                withContext(ioDispatcher) { tokenStore.clear() }
                _state.value = HomeState.NotConnected
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.value = HomeState.ApiError("Unable to clear stored credentials.", false)
            }
        }
    }

    override fun onCleared() {
        api.close()
        super.onCleared()
    }

}

internal fun WhoopApiError.userMessage(): String = when (this) {
    WhoopApiError.Unauthorized -> "Access token expired. Scan a new token."
    WhoopApiError.MissingScope -> "Authorization is missing a required WHOOP scope."
    WhoopApiError.RateLimited -> "WHOOP rate limit reached. Try again later."
    WhoopApiError.InvalidRequest -> "WHOOP rejected the request."
    WhoopApiError.Unavailable -> "WHOOP returned an unexpected response."
}

internal fun Exception.isOffline(): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is IOException) return true
        cause = cause.cause
    }
    return false
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
                    text = "WHOOP TODAY",
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
                    HomeState.Loading -> Status("LOADING", "FETCHING TODAY")
                    HomeState.NoData -> NoDataContent(
                        onRefresh = viewModel::refresh,
                        onDisconnect = viewModel::disconnect,
                    )
                    HomeState.AuthorizationExpired -> AuthorizationExpiredContent(
                        onReplaceToken = {
                            navigateTo(::ProvisioningScreen) { viewModel.provision(it) }
                        },
                        onDisconnect = viewModel::disconnect,
                    )
                    HomeState.Offline -> OfflineContent(
                        onRetry = viewModel::refresh,
                        onDisconnect = viewModel::disconnect,
                    )
                    is HomeState.Ready -> RecoveryContent(
                        today = current.today,
                        onHistory = { navigateTo(::HistoryScreen) },
                        onRefresh = viewModel::refresh,
                        onDisconnect = viewModel::disconnect,
                    )
                    is HomeState.ApiError -> ApiErrorContent(
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
private fun ColumnScope.NoDataContent(
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    LightText(text = "NO DATA", variant = LightTextVariant.Subheading)
    LightText(
        text = "WHOOP returned no recovery, cycle, or sleep data.",
        variant = LightTextVariant.Paragraph,
        lighten = true,
        modifier = Modifier.padding(top = 12.dp),
    )
    Spacer(Modifier.weight(1f))
    Divider(Modifier.padding(vertical = 18.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Action("REFRESH", onRefresh)
        Action("DISCONNECT", onDisconnect)
    }
}

@Composable
private fun ColumnScope.AuthorizationExpiredContent(
    onReplaceToken: () -> Unit,
    onDisconnect: () -> Unit,
) {
    LightText(text = "AUTHORIZATION EXPIRED", variant = LightTextVariant.Subheading)
    LightText(
        text = "Scan a new QR code to reconnect Whoof to WHOOP.",
        variant = LightTextVariant.Paragraph,
        lighten = true,
        modifier = Modifier.padding(top = 12.dp),
    )
    Spacer(Modifier.weight(1f))
    Divider(Modifier.padding(vertical = 18.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Action("SCAN TOKEN", onReplaceToken)
        Action("DISCONNECT", onDisconnect)
    }
}

@Composable
private fun ColumnScope.OfflineContent(
    onRetry: () -> Unit,
    onDisconnect: () -> Unit,
) {
    LightText(text = "OFFLINE", variant = LightTextVariant.Subheading)
    LightText(
        text = "WHOOP could not be reached. Check the connection and try again.",
        variant = LightTextVariant.Paragraph,
        lighten = true,
        modifier = Modifier.padding(top = 12.dp),
    )
    Spacer(Modifier.weight(1f))
    Divider(Modifier.padding(vertical = 18.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Action("RETRY", onRetry)
        Action("DISCONNECT", onDisconnect)
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
    today: TodayModel,
    onHistory: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val recovery = today.recovery
    TodayMetricRow(
        TodayMetric(
            label = "RECOVERY",
            value = recovery?.score?.recoveryScore?.wholePercent()
                ?: recovery?.scoreState?.scoreStateValue()
                ?: "--",
        ),
        TodayMetric("STRAIN", today.strain.strainValue()),
        TodayMetric("SLEEP", today.sleep.sleepValue()),
    )
    Divider(Modifier.padding(vertical = 18.dp))
    TodayMetricRow(
        TodayMetric("RHR", recovery?.score?.restingHeartRate.metricValue("bpm")),
        TodayMetric("HRV", recovery?.score?.hrvRmssdMilli.metricValue("ms")),
        TodayMetric("LAST SYNC", today.syncedAt.syncValue()),
    )

    Spacer(Modifier.weight(1f))
    Divider(Modifier.padding(vertical = 18.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Action("HISTORY", onHistory)
        Action("REFRESH", onRefresh)
        Action("DISCONNECT", onDisconnect)
    }
}

private fun Double?.metricValue(unit: String): String =
    this?.let { "${it.toInt()} $unit" } ?: "--"

private fun Double?.strainValue(): String = this?.let { String.format(Locale.US, "%.1f", it) } ?: "--"

private fun Double?.sleepValue(): String = this?.let { "${it.toInt()}%" } ?: "--"

private fun String.syncValue(): String = runCatching {
    SYNC_TIME_FORMAT.format(Instant.parse(this).atZone(ZoneId.systemDefault()))
}.getOrDefault("--")

private fun String.scoreStateValue(): String = when (this) {
    "PENDING_SCORE" -> "PENDING"
    "UNSCORABLE" -> "--"
    else -> this
}

private val SYNC_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private data class TodayMetric(
    val label: String,
    val value: String,
)

@Composable
private fun TodayMetricRow(
    first: TodayMetric,
    second: TodayMetric,
    third: TodayMetric,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TodayMetricContent(first, Alignment.Start)
        TodayMetricContent(second, Alignment.CenterHorizontally)
        TodayMetricContent(third, Alignment.End)
    }
}

@Composable
private fun RowScope.TodayMetricContent(
    metric: TodayMetric,
    alignment: Alignment.Horizontal,
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = alignment,
    ) {
        LightText(text = metric.label, variant = LightTextVariant.Superfine, lighten = true)
        LightText(text = metric.value, variant = LightTextVariant.Subheading)
    }
}

@Composable
private fun ColumnScope.ApiErrorContent(
    message: String,
    connected: Boolean,
    onRetry: () -> Unit,
    onReplaceToken: () -> Unit,
    onDisconnect: () -> Unit,
) {
    LightText(
        text = if (connected) "API ERROR" else "ERROR",
        variant = LightTextVariant.Subheading,
    )
    LightText(
        text = message,
        variant = LightTextVariant.Paragraph,
        lighten = true,
        modifier = Modifier.padding(top = 12.dp),
    )
    Spacer(Modifier.weight(1f))
    Divider(Modifier.padding(vertical = 18.dp))
    if (connected) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Action("RETRY", onRetry)
            Action("DISCONNECT", onDisconnect)
        }
        Action("REPLACE TOKEN", onReplaceToken, Modifier.padding(top = 20.dp))
    } else {
        Action("SCAN TOKEN", onReplaceToken)
    }
}

@Composable
private fun ColumnScope.Status(text: String, detail: String? = null) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LightText(text = text, variant = LightTextVariant.Subheading, lighten = true)
            detail?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Micro,
                    lighten = true,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
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
