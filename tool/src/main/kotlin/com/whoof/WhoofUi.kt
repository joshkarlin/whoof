package com.whoof

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarButton
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Data colours only; chrome stays on LightOS theme tokens. */
internal object WhoofColors {
    val RecoveryGreen = Color(0xFF16EC06)
    val RecoveryYellow = Color(0xFFFFDE00)
    val RecoveryRed = Color(0xFFFF0026)
    val Strain = Color(0xFF0093E7)
    val Sleep = Color(0xFF7BA1BB)

    val SleepAwake = Color(0xFF555555)
    val SleepLight = Color(0xFF5B6F80)
    val SleepDeep = Sleep
    val SleepRem = Color(0xFFC6D9E6)

    val Zones = listOf(
        Color(0xFF555555),
        Sleep,
        Strain,
        RecoveryYellow,
        Color(0xFFFF7A00),
        RecoveryRed,
    )
}

internal const val MAX_STRAIN = 21.0

internal fun RecoveryZone.color(): Color = when (this) {
    RecoveryZone.Green -> WhoofColors.RecoveryGreen
    RecoveryZone.Yellow -> WhoofColors.RecoveryYellow
    RecoveryZone.Red -> WhoofColors.RecoveryRed
}

@Composable
internal fun trackColor(): Color = LightThemeTokens.colors.contentSecondary.copy(alpha = 0.25f)

/** Standard page: LightOS top bar, themed background, and the remaining height for content. */
@Composable
internal fun WhoofPage(
    title: String?,
    onBack: (() -> Unit)? = null,
    rightButton: LightTopBarButton? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            LightTopBar(
                leftButton = onBack?.let {
                    LightBarButton.LightIcon(LightIcons.BACK, onClick = it, contentDescription = "Back")
                },
                center = title?.let { LightTopBarCenter.Text(it) },
                rightButton = rightButton,
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            content()
        }
    }
}

/** A whole-page message centred in the available content area. */
@Composable
internal fun ColumnScope.CenteredMessage(title: String, detail: String? = null) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LightText(text = title, variant = LightTextVariant.Subheading, align = TextAlign.Center)
            detail?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Paragraph,
                    lighten = true,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
            }
        }
    }
}

@Composable
internal fun Dial(
    value: String,
    label: String,
    fraction: Float?,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = trackColor()
    Column(
        modifier = modifier.lightClickable(onClickLabel = "$label history", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = size.minDimension * 0.09f
                val inset = strokeWidth / 2
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                drawArc(
                    color = track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokeWidth),
                )
                fraction?.let {
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = 360f * it.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
            }
            LightText(text = value, variant = LightTextVariant.Subheading)
        }
        LightText(
            text = label,
            variant = LightTextVariant.Superfine,
            lighten = true,
            modifier = Modifier.padding(top = 0.3f.gridUnitsAsDp()),
        )
    }
}

internal data class ChartBar(
    val label: String,
    val fraction: Float?,
    val color: Color,
)

/** Fixed-height bars, oldest left; a missing value leaves its slot empty. */
@Composable
internal fun BarChart(bars: List<ChartBar>, height: Dp, modifier: Modifier = Modifier) {
    val track = trackColor()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            if (bars.isEmpty()) return@Canvas
            val slot = size.width / bars.size
            val barWidth = slot * 0.62f
            val radius = CornerRadius(barWidth * 0.12f)
            bars.forEachIndexed { index, bar ->
                val left = index * slot + (slot - barWidth) / 2
                drawRoundRect(
                    color = track,
                    topLeft = Offset(left, size.height - 2f),
                    size = Size(barWidth, 2f),
                )
                bar.fraction?.let { fraction ->
                    val barHeight = (size.height * fraction.coerceIn(0.04f, 1f))
                    drawRoundRect(
                        color = bar.color,
                        topLeft = Offset(left, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = radius,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            bars.forEach {
                LightText(
                    text = it.label,
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    align = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Proportional segments, such as heart-rate zones or sleep stages. */
@Composable
internal fun SegmentBar(fractions: List<Float>, colors: List<Color>, height: Dp, modifier: Modifier = Modifier) {
    val track = trackColor()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(track),
        horizontalArrangement = Arrangement.Start,
    ) {
        fractions.forEachIndexed { index, fraction ->
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .weight(fraction)
                        .fillMaxSize()
                        .background(colors[index % colors.size]),
                )
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    LightText(text = text, variant = LightTextVariant.Superfine, lighten = true, modifier = modifier)
}

@Composable
internal fun ActivityRow(activity: ActivitySummary, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            LightText(
                text = activity.sportName.displaySportName(),
                variant = LightTextVariant.Copy,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            LightText(
                text = activity.strain.strainText(activity.state),
                variant = LightTextVariant.Copy,
                color = WhoofColors.Strain,
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            SectionLabel(activity.timeRangeText(), Modifier.weight(1f))
            activity.averageHeartRate?.let { SectionLabel("AVG $it BPM") }
        }
        if (activity.zoneFractions.isNotEmpty()) {
            SegmentBar(
                fractions = activity.zoneFractions,
                colors = WhoofColors.Zones,
                height = 6.dp,
                modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
            )
        }
    }
}

internal fun ScoreState.placeholder(): String = when (this) {
    ScoreState.Pending -> "…"
    ScoreState.Scored, ScoreState.Unscorable -> "--"
}

internal fun Double?.percentText(state: ScoreState?): String =
    this?.roundToInt()?.toString() ?: (state ?: ScoreState.Unscorable).placeholder()

internal fun Double?.strainText(state: ScoreState?): String =
    this?.let { String.format(Locale.US, "%.1f", it) } ?: (state ?: ScoreState.Unscorable).placeholder()

internal fun Long.hoursMinutesText(): String {
    val totalMinutes = this / 60_000
    return String.format(Locale.US, "%d:%02d", totalMinutes / 60, totalMinutes % 60)
}

internal fun LocalDate.topBarText(): String = TOP_BAR_DATE.format(this).uppercase(Locale.US)

internal fun LocalDate.dayInitial(): String = DAY_INITIAL.format(this).take(1).uppercase(Locale.US)

internal fun LocalDate.rowLabel(today: LocalDate): String = when (this) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> ROW_DATE.format(this)
}

internal fun Long.syncTimeText(zone: ZoneId = ZoneId.systemDefault()): String =
    TIME.format(Instant.ofEpochMilli(this).atZone(zone))

internal fun ActivitySummary.timeRangeText(): String {
    val minutes = (end.toEpochMilli() - start.toEpochMilli()) / 60_000
    return "${TIME.format(localTime(start, offset))} – ${TIME.format(localTime(end, offset))} · $minutes MIN"
}

/** WHOOP returns lowercase, hyphenated sport names such as `functional-fitness`. */
internal fun String.displaySportName(): String =
    split('-', '_', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .replaceFirstChar { it.titlecase(Locale.US) }

private val TOP_BAR_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
private val DAY_INITIAL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.US)
private val ROW_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
