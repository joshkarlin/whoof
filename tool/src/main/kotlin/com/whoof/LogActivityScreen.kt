package com.whoof

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.whoof.privateapi.PrivateWhoopError
import com.whoof.privateapi.PrivateWhoopException
import com.whoof.privateapi.PrivateWhoopSport
import com.whoof.privateapi.PrivateWhoopSports
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// ---- Sport picker ---------------------------------------------------------------------------

internal data class SportPickerState(
    /** The keyboard editor is open only after the user taps Search. */
    val typing: Boolean = false,
    val query: String = "",
    val recentIds: List<Int> = emptyList(),
    val favouriteIds: List<Int> = emptyList(),
)

internal class SportPickerViewModel(
    private val prefs: ActivityPrefs,
    val pickOnly: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LightViewModel<Int>() {
    private val _state = MutableStateFlow(SportPickerState())
    val state: StateFlow<SportPickerState> = _state

    init {
        reload()
    }

    /** Also run on every show, so favourites edited on the child page appear on return. */
    override fun onScreenShow(screen: SimpleLightScreen<Int>) {
        super.onScreenShow(screen)
        reload()
    }

    internal fun reload() {
        viewModelScope.launch {
            val (recents, favourites) = try {
                withContext(ioDispatcher) { prefs.recentSportIds() to prefs.favouriteSportIds() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList<Int>() to emptyList()
            }
            _state.update { it.copy(recentIds = recents, favouriteIds = favourites) }
        }
    }

    /** Enter submits the query and leaves the keyboard so the matches stay visible. */
    fun submitQuery(text: String) = _state.update { it.copy(query = text.trim(), typing = false) }

    fun startTyping() = _state.update { it.copy(typing = true) }

    /** Leaves the editor without changing the query. */
    fun stopTyping() = _state.update { it.copy(typing = false) }

    fun clearQuery() = _state.update { it.copy(query = "") }

    override fun onBackPressed(): Boolean {
        if (_state.value.typing) {
            stopTyping()
            return true
        }
        return false
    }
}

internal class SportPickerScreen(
    sealedActivity: SealedLightActivity,
    private val pickOnly: Boolean = false,
) : LightScreen<Int, SportPickerViewModel>(sealedActivity) {
    private val tools = ActivityTools.from(lightContext)

    override val viewModelClass: Class<SportPickerViewModel> = SportPickerViewModel::class.java

    override fun createViewModel(): SportPickerViewModel = SportPickerViewModel(tools.prefs, pickOnly)

    private fun openFavourites() {
        navigateTo({ FavouritesScreen(it) }) { }
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        if (state.typing) {
            SportSearchEditor(state.query, viewModel::submitQuery, viewModel::stopTyping)
            return
        }
        val sections = buildSportSections(state.query, state.favouriteIds, state.recentIds, pickOnly = pickOnly)
        val searching = state.query.isNotEmpty()
        WhoofPage(
            title = null,
            onBack = { goBack() },
            rightButton = LightBarButton.LightIcon(
                LightIcons.SEARCH,
                onClick = viewModel::startTyping,
                contentDescription = "Search sports",
            ),
        ) {
            if (searching) QueryLine(state.query, viewModel::clearQuery)
            if (sections.isEmpty) {
                CenteredMessage("NO MATCHES")
                return@WhoofPage
            }
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1f.gridUnitsAsDp()),
            ) {
                if (!searching && !pickOnly) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 1f.gridUnitsAsDp()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionLabel("FAVOURITES", Modifier.weight(1f))
                        if (sections.favourites.isNotEmpty()) {
                            LightIcon(
                                icon = LightIcons.PENCIL,
                                size = 1.3f,
                                contentDescription = "Edit favourites",
                                modifier = Modifier
                                    .lightClickable(onClick = ::openFavourites)
                                    .padding(horizontal = 0.4f.gridUnitsAsDp()),
                            )
                        }
                    }
                    if (sections.favourites.isEmpty()) {
                        LightText(
                            text = "Add favourites",
                            variant = LightTextVariant.Heading,
                            lighten = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable(onClickLabel = "Add favourites", onClick = ::openFavourites)
                                .padding(vertical = 0.45f.gridUnitsAsDp()),
                        )
                    }
                }
                sections.favourites.forEach { SportRow(it) { goBack(it.id) } }
                if (sections.recent.isNotEmpty()) {
                    SectionLabel("RECENT", Modifier.padding(top = 0.8f.gridUnitsAsDp()))
                }
                sections.recent.forEach { SportRow(it) { goBack(it.id) } }
                if (!searching && sections.all.isNotEmpty()) {
                    SectionLabel("ALL", Modifier.padding(top = 0.8f.gridUnitsAsDp()))
                }
                sections.all.forEach { SportRow(it) { goBack(it.id) } }
                Box(modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()))
            }
        }
    }
}

/** The submitted query as a small line; tapping it clears the filter. */
@Composable
private fun QueryLine(query: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClickLabel = "Clear search", onClick = onClear)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.3f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel("\"$query\"", Modifier.weight(1f))
        LightIcon(icon = LightIcons.CLOSE, size = 1f, contentDescription = "Clear search")
    }
}

@Composable
private fun SportSearchEditor(initial: String, onSubmit: (String) -> Unit, onBack: () -> Unit) {
    val textState = rememberTextFieldState(initial)
    val colors by LightThemeController.colors.collectAsState()
    val keyboardOptions = rememberKeyboardOptions()
    LightTheme(colors = colors) {
        LightTextInputEditor(
            title = "SPORT",
            state = textState,
            keyboardOptionsFlow = keyboardOptions,
            onSubmit = { onSubmit(it.toString()) },
            onBack = onBack,
            submitLabel = "SEARCH",
            singleLine = true,
            modifier = Modifier.background(LightThemeTokens.colors.background),
        )
    }
}

@Composable
private fun SportRow(sport: PrivateWhoopSport, onClick: () -> Unit) {
    LightText(
        text = sport.name,
        variant = LightTextVariant.Heading,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClickLabel = sport.name, onClick = onClick)
            .padding(vertical = 0.45f.gridUnitsAsDp()),
    )
}

// ---- Favourites -----------------------------------------------------------------------------

internal data class FavouritesState(val ids: List<Int> = emptyList(), val loaded: Boolean = false)

/** Favourites kept in the ViewModel; every change is written through to [ActivityPrefs] in order. */
internal class FavouritesViewModel(
    private val prefs: ActivityPrefs,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LightViewModel<Boolean>() {
    private val _state = MutableStateFlow(FavouritesState())
    val state: StateFlow<FavouritesState> = _state
    private val writes = Mutex()

    init {
        viewModelScope.launch {
            val stored = try {
                withContext(ioDispatcher) { prefs.favouriteSportIds() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
            _state.update { FavouritesState(stored, loaded = true) }
        }
    }

    fun add(sportId: Int) = change { addFavourite(it, sportId) }

    fun remove(sportId: Int) = change { removeFavourite(it, sportId) }

    fun move(sportId: Int, delta: Int) = change { moveFavourite(it, sportId, delta) }

    private fun change(transform: (List<Int>) -> List<Int>) {
        val current = _state.value
        if (!current.loaded) return
        val next = transform(current.ids)
        if (next == current.ids) return
        _state.value = current.copy(ids = next)
        viewModelScope.launch {
            writes.withLock {
                try {
                    withContext(ioDispatcher) { prefs.saveFavouriteSportIds(next) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // The on-screen order stays; the next change writes the full list again.
                }
            }
        }
    }
}

internal class FavouritesScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Boolean, FavouritesViewModel>(sealedActivity) {
    private val tools = ActivityTools.from(lightContext)

    override val viewModelClass: Class<FavouritesViewModel> = FavouritesViewModel::class.java

    override fun createViewModel(): FavouritesViewModel = FavouritesViewModel(tools.prefs)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        WhoofPage(
            title = null,
            onBack = { goBack(false) },
            rightButton = LightBarButton.LightIcon(
                LightIcons.ADD,
                onClick = {
                    navigateTo({ SportPickerScreen(it, pickOnly = true) }) { sportId -> viewModel.add(sportId) }
                },
                contentDescription = "Add favourite",
            ),
        ) {
            val sports = state.ids.mapNotNull(PrivateWhoopSports::byId)
            if (state.loaded && sports.isEmpty()) {
                CenteredMessage("NO FAVOURITES")
                return@WhoofPage
            }
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1f.gridUnitsAsDp()),
            ) {
                sports.forEachIndexed { index, sport ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LightText(
                            text = sport.name,
                            variant = LightTextVariant.Heading,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(vertical = 0.45f.gridUnitsAsDp()),
                        )
                        FavouriteControl(LightIcons.UP, "Move ${sport.name} up", index > 0) { viewModel.move(sport.id, -1) }
                        FavouriteControl(LightIcons.DOWN, "Move ${sport.name} down", index < sports.lastIndex) {
                            viewModel.move(sport.id, 1)
                        }
                        FavouriteControl(LightIcons.CLOSE, "Remove ${sport.name}", true) { viewModel.remove(sport.id) }
                    }
                }
                Box(modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()))
            }
        }
    }
}

/** A fixed-width slot so the controls stay aligned; an unavailable move leaves it empty. */
@Composable
private fun FavouriteControl(icon: LightIconConfiguration, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(2.4f.gridUnitsAsDp())
            .then(if (enabled) Modifier.lightClickable(onClickLabel = description, onClick = onClick) else Modifier)
            .padding(vertical = 0.45f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) LightIcon(icon = icon, size = 1.3f, contentDescription = description)
    }
}

// ---- Timer ----------------------------------------------------------------------------------

internal data class TimerState(
    val sport: PrivateWhoopSport? = null,
    val startMillis: Long? = null,
    val nowMillis: Long = 0,
    val loaded: Boolean = false,
    val error: String? = null,
) {
    val running: Boolean get() = startMillis != null
    val elapsedMillis: Long get() = startMillis?.let { nowMillis - it } ?: 0
}

internal class TimerViewModel(
    private val initialSportId: Int?,
    private val prefs: ActivityPrefs,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : LightViewModel<Boolean>() {
    private val _state = MutableStateFlow(TimerState(nowMillis = clock()))
    val state: StateFlow<TimerState> = _state

    /** Re-read on every show, so returning from review or a killed process always reflects storage. */
    override fun onScreenShow(screen: SimpleLightScreen<Boolean>) {
        super.onScreenShow(screen)
        reload()
    }

    internal fun reload() {
        viewModelScope.launch {
            val stored = try {
                withContext(ioDispatcher) { prefs.timer() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            val sport = (stored?.sportId ?: initialSportId)?.let(PrivateWhoopSports::byId)
            _state.update {
                it.copy(sport = sport, startMillis = stored?.startMillis, nowMillis = clock(), loaded = true)
            }
        }
    }

    fun tick() = _state.update { it.copy(nowMillis = clock()) }

    fun start() {
        val current = _state.value
        val sport = current.sport
        if (!current.loaded || current.running || sport == null) return
        val now = clock()
        _state.value = current.copy(startMillis = now, nowMillis = now, error = null)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { prefs.saveTimer(ActivityTimer(sport.id, now)) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.update { it.copy(error = "The timer could not be saved.") }
            }
        }
    }

    fun discard() {
        _state.update { it.copy(startMillis = null, error = null) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { prefs.clearTimer() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.update { it.copy(error = "The timer could not be cleared.") }
            }
        }
    }

    /** Start and end for the running timer, ending now; null when not running. */
    fun stopRange(): Pair<Instant, Instant>? {
        val start = _state.value.startMillis ?: return null
        return Instant.ofEpochMilli(start) to Instant.ofEpochMilli(clock())
    }

    /** A past activity: the last 30 minutes, ending at the current minute. */
    fun manualRange(): Pair<Instant, Instant> {
        val end = Instant.ofEpochMilli(clock()).truncatedTo(ChronoUnit.MINUTES)
        return end.minus(MANUAL_DEFAULT_MINUTES, ChronoUnit.MINUTES) to end
    }
}

internal class TimerScreen(
    sealedActivity: SealedLightActivity,
    private val sportId: Int?,
) : LightScreen<Boolean, TimerViewModel>(sealedActivity) {
    private val tools = ActivityTools.from(lightContext)

    override val viewModelClass: Class<TimerViewModel> = TimerViewModel::class.java

    override fun createViewModel(): TimerViewModel = TimerViewModel(sportId, tools.prefs)

    private fun review(range: Pair<Instant, Instant>, sport: PrivateWhoopSport) {
        navigateTo({ ReviewActivityScreen(it, sport, range.first, range.second) }) { logged ->
            if (logged) goBack(true)
        }
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val sport = state.sport
        val running = state.running
        LaunchedEffect(running) {
            if (running) while (true) {
                viewModel.tick()
                delay(1_000)
            }
        }

        WhoofPage(
            title = null,
            onBack = { goBack() },
            rightButton = when {
                sport == null || !state.loaded -> null
                running -> LightBarButton.LightIcon(
                    LightIcons.TRASH,
                    onClick = {
                        navigateTo({
                            ConfirmScreen(it, "DISCARD TIMER?", "Stops timing ${sport.name}. Nothing is sent to WHOOP.", "DISCARD")
                        }) { confirmed -> if (confirmed) viewModel.discard() }
                    },
                    contentDescription = "Discard timer",
                )
                else -> LightBarButton.LightIcon(
                    LightIcons.PENCIL,
                    onClick = { review(viewModel.manualRange(), sport) },
                    contentDescription = "Enter times",
                )
            },
        ) {
            if (state.loaded && sport == null) {
                CenteredMessage("NO SPORT")
                return@WhoofPage
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                if (state.loaded && sport != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LightText(
                            text = formatElapsed(state.elapsedMillis),
                            variant = LightTextVariant.Title,
                            align = TextAlign.Center,
                        )
                        LightText(
                            text = sport.name,
                            variant = LightTextVariant.Paragraph,
                            lighten = true,
                            align = TextAlign.Center,
                            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                        )
                        state.error?.let {
                            LightText(
                                text = it,
                                variant = LightTextVariant.Paragraph,
                                align = TextAlign.Center,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                        }
                    }
                }
            }
            if (state.loaded && sport != null) {
                LightBottomBar(
                    listOf(
                        if (running) {
                            LightBarButton.Text(
                                text = "STOP",
                                onClick = { viewModel.stopRange()?.let { review(it, sport) } },
                            )
                        } else {
                            LightBarButton.Text(text = "START", onClick = viewModel::start)
                        },
                    ),
                )
            }
        }
    }
}

// ---- Review and submit ----------------------------------------------------------------------

internal data class ReviewState(
    val start: Instant,
    val end: Instant,
    val busy: Boolean = false,
    val error: String? = null,
    /** The last submission may or may not have reached WHOOP; nothing is sent until it is cleared. */
    val uncertain: Boolean = false,
    val done: Boolean = false,
)

internal class ReviewViewModel(
    private val backend: ActivityBackend,
    private val prefs: ActivityPrefs,
    private val recordPending: suspend (PendingActivityEntity) -> Unit,
    val sport: PrivateWhoopSport,
    start: Instant,
    end: Instant,
    val zone: ZoneId,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Instant = Instant::now,
) : LightViewModel<Boolean>() {
    private val _state = MutableStateFlow(ReviewState(start, end))
    val state: StateFlow<ReviewState> = _state

    fun setStart(value: Instant) = _state.update { if (it.busy || it.uncertain) it else it.copy(start = value, error = null) }

    fun setEnd(value: Instant) = _state.update { if (it.busy || it.uncertain) it else it.copy(end = value, error = null) }

    /** The busy flag is set before any suspension, so a second tap cannot send a second POST. */
    fun submit() {
        val current = _state.value
        if (current.busy || current.done || current.uncertain) return
        rangeProblem(current.start, current.end, clock())?.let { problem ->
            _state.value = current.copy(error = problem.message)
            return
        }
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val activityId = withContext(ioDispatcher) { backend.submit(sport, current.start, current.end, zone) }
                withContext(ioDispatcher) {
                    try {
                        recordPending(
                            PendingActivityEntity(
                                activityId = activityId,
                                sportId = sport.id,
                                sportName = sport.name,
                                startMillis = current.start.toEpochMilli(),
                                endMillis = current.end.toEpochMilli(),
                                timezoneOffset = zone.rules.getOffset(current.start).id,
                                createdMillis = clock().toEpochMilli(),
                            ),
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // Today still shows the activity once WHOOP lists it.
                    }
                    runCatching { prefs.recordRecent(sport.id) }
                    runCatching { prefs.clearTimer() }
                }
                _state.update { it.copy(busy = false, done = true) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                val reason = (error as? PrivateWhoopException)?.reason
                if (reason == PrivateWhoopError.SubmissionUncertain || reason == PrivateWhoopError.AlreadyAttempted) {
                    _state.update { it.copy(busy = false, uncertain = true, error = UNCERTAIN_MESSAGE) }
                } else {
                    _state.update { it.copy(busy = false, error = error.activityMessage()) }
                }
            }
        }
    }

    /** Only after the user has checked WHOOP. Never resubmits by itself. */
    fun clearUncertain() {
        val current = _state.value
        if (current.busy || !current.uncertain) return
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { backend.discardDraft() }
                _state.update { it.copy(busy = false, uncertain = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _state.update { it.copy(busy = false, error = error.activityMessage()) }
            }
        }
    }
}

internal class ReviewActivityScreen(
    sealedActivity: SealedLightActivity,
    private val sport: PrivateWhoopSport,
    private val start: Instant,
    private val end: Instant,
) : LightScreen<Boolean, ReviewViewModel>(sealedActivity) {
    private val tools = ActivityTools.from(lightContext)
    private val repository = DefaultWhoofRepository.getInstance(lightContext)

    override val viewModelClass: Class<ReviewViewModel> = ReviewViewModel::class.java

    override fun createViewModel(): ReviewViewModel =
        ReviewViewModel(tools.backend, tools.prefs, repository::addPendingActivity, sport, start, end, ZoneId.systemDefault())

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        LaunchedEffect(state.done) { if (state.done) goBack(true) }
        val zone = viewModel.zone

        WhoofPage(title = null, onBack = if (state.busy) null else ({ goBack(false) })) {
            when {
                state.busy -> CenteredMessage("SUBMITTING")
                state.uncertain -> {
                    CenteredMessage("CHECK WHOOP", state.error)
                    LightBottomBar(listOf(LightBarButton.Text(text = "CLEAR", onClick = viewModel::clearUncertain)))
                }
                else -> {
                    val problem = rangeProblem(state.start, state.end, Instant.now())
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    ) {
                        LightText(text = sport.name, variant = LightTextVariant.Heading, maxLines = 2)
                        LightTextField(
                            label = "Start:",
                            value = state.start.dateTimeText(zone),
                            placeholder = "",
                            onClick = {
                                navigateTo({ AdjustTimeScreen(it, "START", state.start, zone) }) { viewModel.setStart(it) }
                            },
                        )
                        LightTextField(
                            label = "End:",
                            value = state.end.dateTimeText(zone),
                            placeholder = "",
                            onClick = {
                                navigateTo({ AdjustTimeScreen(it, "END", state.end, zone) }) { viewModel.setEnd(it) }
                            },
                        )
                        LightText(
                            text = if (problem == null) durationText(state.start, state.end) else "",
                            variant = LightTextVariant.Paragraph,
                            lighten = true,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                        )
                        (state.error ?: problem?.message)?.let {
                            LightText(
                                text = it,
                                variant = LightTextVariant.Paragraph,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                        }
                    }
                    LightBottomBar(listOf(LightBarButton.Text(text = "SUBMIT TO WHOOP", onClick = viewModel::submit)))
                }
            }
        }
    }
}

// ---- Time adjustment ------------------------------------------------------------------------

internal class AdjustTimeViewModel(initial: Instant, private val max: Instant) : LightViewModel<Instant>() {
    private val _value = MutableStateFlow(minOf(initial, max))
    val value: StateFlow<Instant> = _value

    fun adjust(deltaMinutes: Int) = _value.update { adjustTime(it, deltaMinutes, max) }
}

internal class AdjustTimeScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: Instant,
    private val zone: ZoneId,
    private val max: Instant = Instant.now(),
) : LightScreen<Instant, AdjustTimeViewModel>(sealedActivity) {
    override val viewModelClass: Class<AdjustTimeViewModel> = AdjustTimeViewModel::class.java

    override fun createViewModel(): AdjustTimeViewModel = AdjustTimeViewModel(initial, max)

    @Composable
    override fun Content() {
        val value by viewModel.value.collectAsState()
        WhoofPage(title = title, onBack = { goBack() }) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LightText(
                    text = value.dateText(zone),
                    variant = LightTextVariant.Paragraph,
                    lighten = true,
                    modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp()),
                )
                LightText(text = value.clockText(zone), variant = LightTextVariant.Title, align = TextAlign.Center)
                ADJUST_STEPS_MINUTES.chunked(3).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 1.2f.gridUnitsAsDp())) {
                        row.forEach { minutes ->
                            LightText(
                                text = if (minutes > 0) "+$minutes" else "$minutes",
                                variant = LightTextVariant.Heading,
                                align = TextAlign.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .lightClickable(onClickLabel = "$minutes minutes") { viewModel.adjust(minutes) }
                                    .padding(vertical = 0.5f.gridUnitsAsDp()),
                            )
                        }
                    }
                }
            }
            LightBottomBar(listOf(LightBarButton.Text(text = "DONE", onClick = { goBack(value) })))
        }
    }
}
