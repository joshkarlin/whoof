package com.whoof

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val AUTO_SYNC_INTERVAL_MILLIS = 5 * 60 * 1000L
private const val MAX_FAILURE_BACKOFF_MILLIS = 60 * 60 * 1000L
private const val RATE_LIMIT_HOLD_MILLIS = 30 * 60 * 1000L
private const val PULL_THRESHOLD_UNITS = 3f
private const val PULL_RESISTANCE = 0.5f
private const val SYNC_START_WAIT_MILLIS = 1_000L

internal enum class Connection {
    Starting,
    NotConnected,
    Connected,
}

internal sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data object Offline : SyncStatus
    data object AuthorizationExpired : SyncStatus
    data class Failed(val message: String, val rateLimited: Boolean = false) : SyncStatus
}

internal data class HomeUiState(
    val connection: Connection = Connection.Starting,
    /** Null until the cache has been read once, so the empty state never flashes during loading. */
    val days: List<DaySummary>? = null,
    val sync: SyncStatus = SyncStatus.Idle,
    val lastSyncMillis: Long? = null,
    val message: String? = null,
)

/**
 * Coroutines start on the main dispatcher so sync bookkeeping stays single-threaded; only
 * repository calls move to [ioDispatcher].
 */
internal class HomeViewModel(
    private val repository: WhoofRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : LightViewModel<Unit>() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state
    private var initialized = false
    private var syncJob: Job? = null
    private var consecutiveFailures = 0
    private var holdUntilMillis = 0L

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (initialized) syncIfStale() else start()
    }

    internal fun start() {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            combine(repository.days, repository.lastSuccessfulSyncMillis) { days, lastSync -> days to lastSync }
                .collect { (days, lastSync) ->
                    _state.update { it.copy(days = days, lastSyncMillis = lastSync) }
                }
        }
        viewModelScope.launch {
            val connected = try {
                withContext(ioDispatcher) { repository.hasCredentials() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.update { it.copy(connection = Connection.NotConnected, message = "Stored credentials could not be read.") }
                return@launch
            }
            _state.update { it.copy(connection = if (connected) Connection.Connected else Connection.NotConnected) }
            if (connected) sync()
        }
    }

    fun provision(payload: String) {
        val credentials = runCatching { ProvisioningPayload.parse(payload) }.getOrElse {
            _state.update { state -> state.copy(message = it.message ?: "Invalid provisioning QR code.") }
            return
        }
        viewModelScope.launch {
            stopSync()
            try {
                withContext(ioDispatcher) { repository.provision(credentials) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.update { it.copy(message = "Unable to store the access token securely.") }
                return@launch
            }
            resetBackoff()
            _state.update { it.copy(connection = Connection.Connected, sync = SyncStatus.Idle, message = null) }
            sync()
        }
    }

    /** Starts a sync unless one is already running. Explicit requests ignore failure backoff. */
    fun sync() {
        if (_state.value.connection != Connection.Connected) return
        if (syncJob?.isActive == true) return
        _state.update { it.copy(sync = SyncStatus.Syncing) }
        syncJob = viewModelScope.launch {
            val result = try {
                withContext(ioDispatcher) { repository.sync() }
                SyncStatus.Idle
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: WhoopReprovisioningException) {
                SyncStatus.AuthorizationExpired
            } catch (error: WhoopApiException) {
                when (error.reason) {
                    WhoopApiError.Unauthorized -> SyncStatus.AuthorizationExpired
                    WhoopApiError.RateLimited -> SyncStatus.Failed(error.reason.userMessage(), rateLimited = true)
                    else -> SyncStatus.Failed(error.reason.userMessage())
                }
            } catch (error: Exception) {
                if (error.isOffline()) SyncStatus.Offline else SyncStatus.Failed("Unable to reach WHOOP.")
            }
            recordOutcome(result)
            _state.update { it.copy(sync = result) }
        }
    }

    /** Called when Today is shown again; respects the sync interval and failure backoff. */
    internal fun syncIfStale() {
        val state = _state.value
        if (state.sync is SyncStatus.AuthorizationExpired) return
        if (nowMillis() < holdUntilMillis) return
        val lastSync = state.lastSyncMillis
        if (lastSync == null || nowMillis() - lastSync >= AUTO_SYNC_INTERVAL_MILLIS) sync()
    }

    fun disconnect() {
        viewModelScope.launch {
            stopSync()
            try {
                withContext(ioDispatcher) { repository.disconnect() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.update { it.copy(message = "Unable to delete local data.") }
                return@launch
            }
            resetBackoff()
            _state.update { it.copy(connection = Connection.NotConnected, sync = SyncStatus.Idle, message = null) }
        }
    }

    private suspend fun stopSync() {
        syncJob?.cancelAndJoin()
        syncJob = null
    }

    private fun recordOutcome(result: SyncStatus) {
        when (result) {
            SyncStatus.Idle -> resetBackoff()
            is SyncStatus.Failed, SyncStatus.Offline -> {
                consecutiveFailures++
                val backoff = if (result is SyncStatus.Failed && result.rateLimited) {
                    RATE_LIMIT_HOLD_MILLIS
                } else {
                    failureBackoffMillis(consecutiveFailures)
                }
                holdUntilMillis = nowMillis() + backoff
            }
            SyncStatus.AuthorizationExpired, SyncStatus.Syncing -> Unit
        }
    }

    private fun resetBackoff() {
        consecutiveFailures = 0
        holdUntilMillis = 0L
    }
}

/** Doubles from the normal sync interval after each consecutive failure, up to one hour. */
internal fun failureBackoffMillis(consecutiveFailures: Int): Long {
    val doublings = (consecutiveFailures - 1).coerceIn(0, 10)
    return (AUTO_SYNC_INTERVAL_MILLIS shl doublings).coerceAtMost(MAX_FAILURE_BACKOFF_MILLIS)
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
    private val repository = DefaultWhoofRepository.getInstance(lightContext)

    override val viewModelClass: Class<HomeViewModel> = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel = HomeViewModel(repository)

    private fun scanToken() {
        navigateTo(::ProvisioningScreen) { viewModel.provision(it) }
    }

    private fun openSettings() {
        navigateTo(::SettingsScreen) { action ->
            when (action) {
                SettingsAction.SyncNow -> viewModel.sync()
                SettingsAction.ReplaceToken -> scanToken()
                SettingsAction.Disconnect -> viewModel.disconnect()
            }
        }
    }

    private fun openHistory(metric: HistoryMetric) {
        navigateTo({ MetricHistoryScreen(it, metric, repository) })
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val days = state.days
        val today = days?.firstOrNull()

        WhoofPage(
            title = today?.date?.topBarText(),
            rightButton = if (state.connection == Connection.Connected) {
                LightBarButton.LightIcon(LightIcons.SETTINGS, onClick = ::openSettings, contentDescription = "Settings")
            } else {
                null
            },
        ) {
            when {
                state.connection == Connection.Starting -> Unit
                state.connection == Connection.NotConnected -> NotConnected(state.message, ::scanToken)
                days == null -> Unit
                today == null -> EmptyToday(state.sync, ::scanToken, viewModel::sync)
                else -> TodayContent(
                    today = today,
                    earlier = days.drop(1),
                    syncing = state.sync == SyncStatus.Syncing,
                    syncDetails = state.syncDetailsText(today),
                    syncProblem = state.sync.problemText(),
                    onSync = viewModel::sync,
                    onReconnect = if (state.sync is SyncStatus.AuthorizationExpired) ::scanToken else null,
                    onHistory = ::openHistory,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.NotConnected(message: String?, onConnect: () -> Unit) {
    CenteredMessage("CONNECT WHOOP", message ?: "Scan a provisioning QR code made on your computer.")
    LightBottomBar(listOf(LightBarButton.Text(text = "SCAN QR CODE", onClick = onConnect)))
}

@Composable
private fun ColumnScope.EmptyToday(sync: SyncStatus, onScan: () -> Unit, onRetry: () -> Unit) {
    when (sync) {
        SyncStatus.Syncing -> CenteredMessage("LOADING")
        SyncStatus.Idle -> CenteredMessage("NO DATA", "WHOOP has no recent cycles for this account.")
        SyncStatus.Offline -> {
            CenteredMessage("OFFLINE", "WHOOP could not be reached.")
            LightBottomBar(listOf(LightBarButton.Text(text = "RETRY", onClick = onRetry)))
        }
        SyncStatus.AuthorizationExpired -> {
            CenteredMessage("RECONNECT WHOOP", "Authorization expired. Scan a new provisioning QR code.")
            LightBottomBar(listOf(LightBarButton.Text(text = "SCAN QR CODE", onClick = onScan)))
        }
        is SyncStatus.Failed -> {
            CenteredMessage("SYNC FAILED", sync.message)
            LightBottomBar(listOf(LightBarButton.Text(text = "RETRY", onClick = onRetry)))
        }
    }
}

/** Shows WHOOP's record update time separately from Whoof's last successful sync. */
private fun HomeUiState.syncDetailsText(today: DaySummary): String = listOfNotNull(
    "UPDATED ${today.updatedMillis.syncTimeText()}",
    lastSyncMillis?.let { "SYNCED ${it.syncTimeText()}" },
).joinToString(" · ")

/** Failures stay visible beneath the activities so stale data is never presented as current. */
private fun SyncStatus.problemText(): String? = when (this) {
    SyncStatus.Syncing, SyncStatus.Idle -> null
    SyncStatus.Offline -> "OFFLINE"
    SyncStatus.AuthorizationExpired -> "AUTHORIZATION EXPIRED"
    is SyncStatus.Failed -> if (rateLimited) "RATE LIMITED" else "SYNC FAILED"
}

@Composable
private fun ColumnScope.TodayContent(
    today: DaySummary,
    earlier: List<DaySummary>,
    syncing: Boolean,
    syncDetails: String,
    syncProblem: String?,
    onSync: () -> Unit,
    onReconnect: (() -> Unit)?,
    onHistory: (HistoryMetric) -> Unit,
) {
    PullToSync(syncing = syncing, details = syncDetails, onSync = onSync) {
        // Dials and vitals stay fixed; only the activity rows scroll.
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
            Dials(today, onHistory)
            Vitals(today, earlier, Modifier.padding(top = 1.2f.gridUnitsAsDp()))
            SectionLabel("ACTIVITY", Modifier.padding(top = 1.4f.gridUnitsAsDp()))
        }
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            ActivityRows(today.activities, Modifier.padding(bottom = 1f.gridUnitsAsDp()))
        }
    }
    syncProblem?.let {
        LightText(
            text = it,
            variant = LightTextVariant.Superfine,
            lighten = true,
            align = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 0.6f.gridUnitsAsDp()),
        )
    }
    onReconnect?.let {
        LightBottomBar(listOf(LightBarButton.Text(text = "SCAN QR CODE", onClick = it)))
    }
}

/**
 * Dragging down on the page outside the activity list reveals sync details; releasing past the
 * threshold starts a sync and holds the details open until it ends. The activity list consumes its
 * own drags, so scrolling it never triggers a sync.
 */
@Composable
private fun ColumnScope.PullToSync(
    syncing: Boolean,
    details: String,
    onSync: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val thresholdPx = with(LocalDensity.current) { PULL_THRESHOLD_UNITS.gridUnitsAsDp().toPx() }
    val pull = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var holding by remember { mutableStateOf(false) }
    val currentSyncing by rememberUpdatedState(syncing)
    val currentOnSync by rememberUpdatedState(onSync)

    // Holds the details open for whichever sync is running, including one that was already in
    // progress when the pull was released, then retracts once it ends. The new sync's state can
    // arrive a frame after release, and a refused request never starts, hence the bounded wait.
    LaunchedEffect(holding) {
        if (!holding) return@LaunchedEffect
        withTimeoutOrNull(SYNC_START_WAIT_MILLIS) { snapshotFlow { currentSyncing }.first { it } }
        snapshotFlow { currentSyncing }.first { !it }
        holding = false
        scope.launch { pull.animateTo(0f) }
    }

    fun drag(delta: Float) {
        val next = if (delta > 0) pull.value + delta * PULL_RESISTANCE else pull.value + delta
        scope.launch { pull.snapTo(next.coerceIn(0f, thresholdPx * 1.5f)) }
    }

    fun release() {
        if (pull.value >= thresholdPx && !holding) {
            if (!currentSyncing) currentOnSync()
            holding = true
        }
        scope.launch { pull.animateTo(if (holding) thresholdPx else 0f) }
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .draggable(
                state = rememberDraggableState { drag(it) },
                orientation = Orientation.Vertical,
                onDragStopped = { release() },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(LocalDensity.current) { pull.value.toDp() })
                .clipToBounds(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 0.4f.gridUnitsAsDp()),
            ) {
                LightText(
                    text = when {
                        holding -> "SYNCING"
                        pull.value >= thresholdPx -> "RELEASE TO SYNC"
                        else -> "PULL TO SYNC"
                    },
                    variant = LightTextVariant.Superfine,
                )
                LightText(text = details, variant = LightTextVariant.Superfine, lighten = true)
            }
        }
        content()
    }
}

@Composable
private fun Dials(today: DaySummary, onHistory: (HistoryMetric) -> Unit) {
    val recovery = today.recovery
    val sleep = today.sleep
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(0.8f.gridUnitsAsDp()),
    ) {
        Dial(
            value = recovery?.score.percentText(recovery?.state),
            label = "RECOVERY",
            fraction = recovery?.score?.let { (it / 100).toFloat() },
            color = recovery?.score?.let { recoveryZone(it).color() } ?: WhoofColors.RecoveryGreen,
            onClick = { onHistory(HistoryMetric.Recovery) },
            modifier = Modifier.weight(1f),
        )
        Dial(
            value = today.strain.strainText(today.cycleState),
            label = "STRAIN",
            fraction = today.strain?.let { (it / MAX_STRAIN).toFloat() },
            color = WhoofColors.Strain,
            onClick = { onHistory(HistoryMetric.Strain) },
            modifier = Modifier.weight(1f),
        )
        Dial(
            value = sleep?.performancePercentage.percentText(sleep?.state),
            label = "SLEEP",
            fraction = sleep?.performancePercentage?.let { (it / 100).toFloat() },
            color = WhoofColors.Sleep,
            onClick = { onHistory(HistoryMetric.Sleep) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Vitals(today: DaySummary, earlier: List<DaySummary>, modifier: Modifier) {
    val hrv = today.recovery?.hrvMilli
    val rhr = today.recovery?.restingHeartRate
    Row(modifier = modifier.fillMaxWidth()) {
        Vital(
            value = hrv?.let { "${it.toInt()}" } ?: "--",
            unit = "MS",
            label = "HRV",
            trend = trend(hrv, earlier.map { it.recovery?.hrvMilli }),
            higherIsBetter = true,
            modifier = Modifier.weight(1f),
        )
        Vital(
            value = rhr?.let { "${it.toInt()}" } ?: "--",
            unit = "BPM",
            label = "RHR",
            trend = trend(rhr, earlier.map { it.recovery?.restingHeartRate }),
            higherIsBetter = false,
            modifier = Modifier.weight(1f),
        )
        Vital(
            value = today.sleep?.asleepMilli?.hoursMinutesText() ?: "--",
            unit = null,
            label = "ASLEEP",
            trend = null,
            higherIsBetter = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Vital(
    value: String,
    unit: String?,
    label: String,
    trend: Trend?,
    higherIsBetter: Boolean,
    modifier: Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            LightText(text = value, variant = LightTextVariant.Subheading)
            unit?.let {
                LightText(
                    text = " $it",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 0.15f.gridUnitsAsDp()),
                )
            }
        }
        Row {
            SectionLabel(label)
            trend?.takeIf { it != Trend.Flat }?.let {
                val improving = (it == Trend.Up) == higherIsBetter
                LightText(
                    text = if (it == Trend.Up) " ▲" else " ▼",
                    variant = LightTextVariant.Superfine,
                    color = if (improving) WhoofColors.RecoveryGreen else WhoofColors.RecoveryRed,
                )
            }
        }
    }
}

@Composable
internal fun Activities(activities: List<ActivitySummary>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionLabel("ACTIVITY")
        ActivityRows(activities)
    }
}

@Composable
private fun ActivityRows(activities: List<ActivitySummary>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (activities.isEmpty()) {
            LightText(
                text = "No activities recorded",
                variant = LightTextVariant.Paragraph,
                lighten = true,
                modifier = Modifier.padding(top = 0.4f.gridUnitsAsDp()),
            )
        }
        activities.forEach {
            ActivityRow(it, Modifier.padding(top = 0.6f.gridUnitsAsDp()))
        }
    }
}
