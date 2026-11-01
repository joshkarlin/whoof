package com.whoof

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val CHART_DAYS = 7
private const val NEWEST_CYCLE = -1L

internal enum class HistoryMetric(val title: String) {
    Recovery("RECOVERY"),
    Strain("STRAIN"),
    Sleep("SLEEP"),
}

internal class DaysViewModel(
    repository: WhoofRepository,
    private val signedIn: suspend () -> Boolean = { false },
) : LightViewModel<Boolean>() {
    val days: StateFlow<List<DaySummary>?> = repository.days
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _expandedSleep = MutableStateFlow<Long?>(NEWEST_CYCLE)

    /** The sleep row showing stage details; the newest day until the user picks another, null when collapsed. */
    val expandedSleep: StateFlow<Long?> = _expandedSleep

    private val _canEditActivities = MutableStateFlow(false)

    /** True when a private WHOOP session exists, so activities can be opened and deleted. */
    val canEditActivities: StateFlow<Boolean> = _canEditActivities

    override fun onScreenShow(screen: SimpleLightScreen<Boolean>) {
        super.onScreenShow(screen)
        viewModelScope.launch { _canEditActivities.value = runCatching { signedIn() }.getOrDefault(false) }
    }

    fun toggleSleep(cycleId: Long, expanded: Boolean) {
        _expandedSleep.value = if (expanded) null else cycleId
    }
}

internal class MetricHistoryScreen(
    sealedActivity: SealedLightActivity,
    private val metric: HistoryMetric,
    private val repository: WhoofRepository,
) : LightScreen<Boolean, DaysViewModel>(sealedActivity) {
    override val viewModelClass: Class<DaysViewModel> = DaysViewModel::class.java

    override fun createViewModel(): DaysViewModel = DaysViewModel(repository)

    @Composable
    override fun Content() {
        val days by viewModel.days.collectAsState()
        val expandedSleep by viewModel.expandedSleep.collectAsState()

        WhoofPage(title = metric.title, onBack = { goBack() }) {
            val loaded = days ?: return@WhoofPage
            if (loaded.isEmpty()) {
                CenteredMessage("NO HISTORY", "Records appear here after WHOOP syncs.")
                return@WhoofPage
            }
            val today = LocalDate.now()
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1f.gridUnitsAsDp()),
            ) {
                BarChart(
                    bars = recentDays(loaded, maxOf(today, loaded.first().date), CHART_DAYS).map { (date, day) ->
                        ChartBar(date.dayInitial(), day?.fraction(metric), day?.barColor(metric) ?: Color.Transparent)
                    },
                    height = 120.dp,
                )
                Column(modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp())) {
                    val expandedCycle = if (expandedSleep == NEWEST_CYCLE) loaded.first().cycleId else expandedSleep
                    loaded.take(HISTORY_DAYS.toInt()).forEach { day ->
                        val label = day.date.rowLabel(today)
                        when (metric) {
                            HistoryMetric.Recovery -> RecoveryRow(label, day)
                            HistoryMetric.Strain -> StrainRow(label, day) {
                                navigateTo({ DayScreen(it, day.cycleId, repository) }) { deleted ->
                                    if (deleted) goBack(true)
                                }
                            }
                            HistoryMetric.Sleep -> {
                                val expanded = day.cycleId == expandedCycle
                                SleepRow(label, day, expanded) { viewModel.toggleSleep(day.cycleId, expanded) }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal class DayScreen(
    sealedActivity: SealedLightActivity,
    private val cycleId: Long,
    private val repository: WhoofRepository,
) : LightScreen<Boolean, DaysViewModel>(sealedActivity) {
    override val viewModelClass: Class<DaysViewModel> = DaysViewModel::class.java

    private val tools = ActivityTools.from(lightContext)

    override fun createViewModel(): DaysViewModel = DaysViewModel(repository) { tools.backend.signedIn() }

    @Composable
    override fun Content() {
        val days by viewModel.days.collectAsState()
        val canEdit by viewModel.canEditActivities.collectAsState()
        val day = days?.firstOrNull { it.cycleId == cycleId }

        WhoofPage(title = day?.date?.topBarText(), onBack = { goBack() }) {
            when {
                days == null -> Unit
                day == null -> CenteredMessage("NOT CACHED", "This day is no longer stored on the phone.")
                day.activities.isEmpty() -> CenteredMessage("NO ACTIVITIES", "Strain ${day.strain.strainText(day.cycleState)}")
                else -> LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 1f.gridUnitsAsDp()),
                ) {
                    MetricRow("Day strain", day.strain.strainText(day.cycleState), WhoofColors.Strain)
                    Activities(
                        day.activities,
                        Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp()),
                        onActivityClick = if (canEdit) { activity ->
                            navigateTo({ ActivityScreen(it, activity, repository) }) { deleted ->
                                if (deleted) goBack(true)
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

private fun DaySummary.fraction(metric: HistoryMetric): Float? = when (metric) {
    HistoryMetric.Recovery -> recovery?.score?.let { (it / 100).toFloat() }
    HistoryMetric.Strain -> strain?.let { (it / MAX_STRAIN).toFloat() }
    HistoryMetric.Sleep -> sleep?.performancePercentage?.let { (it / 100).toFloat() }
}

private fun DaySummary.barColor(metric: HistoryMetric): Color = when (metric) {
    HistoryMetric.Recovery -> recovery?.score?.let { recoveryZone(it).color() } ?: Color.Transparent
    HistoryMetric.Strain -> WhoofColors.Strain
    HistoryMetric.Sleep -> WhoofColors.Sleep
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    valueColor: Color?,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 0.45f.gridUnitsAsDp())) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            LightText(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
            LightText(text = value, variant = LightTextVariant.Copy, color = valueColor)
        }
        detail?.let { SectionLabel(it) }
    }
}

@Composable
private fun RecoveryRow(label: String, day: DaySummary) {
    val recovery = day.recovery
    MetricRow(
        label = label,
        value = recovery?.score?.let { "${it.roundToInt()}%" }
            ?: (recovery?.state ?: ScoreState.Unscorable).placeholder(),
        valueColor = recovery?.score?.let { recoveryZone(it).color() },
        detail = recovery?.let {
            listOfNotNull(
                it.hrvMilli?.let { hrv -> "HRV ${hrv.toInt()} MS" },
                it.restingHeartRate?.let { rhr -> "RHR ${rhr.toInt()} BPM" },
            ).joinToString(" · ").ifEmpty { null }
        },
    )
}

@Composable
private fun StrainRow(label: String, day: DaySummary, onClick: () -> Unit) {
    MetricRow(
        label = label,
        value = day.strain.strainText(day.cycleState),
        valueColor = WhoofColors.Strain,
        detail = day.activities
            .joinToString(" · ") { it.sportName.displaySportName().uppercase() }
            .ifEmpty { null },
        modifier = Modifier.lightClickable(onClickLabel = "Activities for $label", onClick = onClick),
    )
}

@Composable
private fun SleepRow(label: String, day: DaySummary, expanded: Boolean, onClick: () -> Unit) {
    val sleep = day.sleep
    val performance = sleep?.performancePercentage?.let { "${it.roundToInt()}%" }
    MetricRow(
        label = label,
        value = listOfNotNull(
            performance ?: sleep?.state?.placeholder(),
            sleep?.asleepMilli?.hoursMinutesText(),
        ).joinToString(" · ").ifEmpty { "--" },
        valueColor = null,
        modifier = if (sleep != null) {
            Modifier.lightClickable(onClickLabel = if (expanded) "Hide sleep details" else "Show sleep details", onClick = onClick)
        } else {
            Modifier
        },
    )
    if (expanded && sleep != null) SleepStages(sleep)
}

@Composable
private fun SleepStages(sleep: SleepSummary) {
    val stages = listOf(
        "AWAKE" to sleep.awakeMilli,
        "LIGHT" to sleep.lightMilli,
        "DEEP" to sleep.deepMilli,
        "REM" to sleep.remMilli,
    )
    val total = stages.sumOf { it.second ?: 0L }
    val extras = listOfNotNull(
        sleep.needMilli?.let { "NEED ${it.hoursMinutesText()}" },
        sleep.respiratoryRate?.let { "RESP ${String.format(java.util.Locale.US, "%.1f", it)}" },
    )
    Column(modifier = Modifier.padding(bottom = 0.8f.gridUnitsAsDp())) {
        if (total > 0L) {
            SegmentBar(
                fractions = stages.map { (it.second ?: 0L).toFloat() / total },
                colors = listOf(WhoofColors.SleepAwake, WhoofColors.SleepLight, WhoofColors.SleepDeep, WhoofColors.SleepRem),
                height = 10.dp,
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 0.3f.gridUnitsAsDp())) {
                stages.forEach { (name, milli) ->
                    SectionLabel("$name ${milli?.hoursMinutesText() ?: "--"}", Modifier.weight(1f))
                }
            }
        }
        when {
            extras.isNotEmpty() -> SectionLabel(extras.joinToString(" · "), Modifier.padding(top = 0.3f.gridUnitsAsDp()))
            total <= 0L -> SectionLabel("NO STAGE DATA")
        }
    }
}
