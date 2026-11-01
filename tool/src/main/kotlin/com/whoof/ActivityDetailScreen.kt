package com.whoof

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One activity from the cache. Delete is offered only because the caller checked for a private session. */
internal class ActivityScreen(
    sealedActivity: SealedLightActivity,
    private val activity: ActivitySummary,
    private val repository: WhoofRepository,
) : SimpleLightScreen<Boolean>(sealedActivity) {
    private val tools = ActivityTools.from(lightContext)

    @Composable
    override fun Content() {
        WhoofPage(
            title = activity.start.atOffset(activity.offset).toLocalDate().topBarText(),
            onBack = { goBack(false) },
            rightButton = LightBarButton.LightIcon(
                LightIcons.TRASH,
                onClick = {
                    navigateTo({ ConfirmDeleteScreen(it, activity, repository, tools) }) { deleted ->
                        if (deleted) goBack(true)
                    }
                },
                contentDescription = "Delete activity",
            ),
        ) {
            Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                ActivityRow(activity, Modifier.padding(top = 0.5f.gridUnitsAsDp()))
            }
        }
    }
}

internal data class DeleteState(val busy: Boolean = false, val error: String? = null, val done: Boolean = false)

internal class DeleteActivityViewModel(
    private val activityId: String,
    private val backend: ActivityBackend,
    private val removeCached: suspend (String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LightViewModel<Boolean>() {
    private val _state = MutableStateFlow(DeleteState())
    val state: StateFlow<DeleteState> = _state

    /** One DELETE per tap; the busy flag is set before suspending. A NotFound is treated as success by the service. */
    fun delete() {
        val current = _state.value
        if (current.busy || current.done) return
        _state.value = DeleteState(busy = true)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { backend.delete(activityId) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _state.value = DeleteState(error = error.activityMessage(delete = true))
                return@launch
            }
            // WHOOP no longer has it; a failed cache update is repaired by the next sync.
            try {
                withContext(ioDispatcher) { removeCached(activityId) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // ignored
            }
            _state.update { it.copy(busy = false, done = true) }
        }
    }
}

internal class ConfirmDeleteScreen(
    sealedActivity: SealedLightActivity,
    private val activity: ActivitySummary,
    private val repository: WhoofRepository,
    private val tools: ActivityTools,
) : LightScreen<Boolean, DeleteActivityViewModel>(sealedActivity) {
    override val viewModelClass: Class<DeleteActivityViewModel> = DeleteActivityViewModel::class.java

    override fun createViewModel(): DeleteActivityViewModel =
        DeleteActivityViewModel(activity.id, tools.backend, repository::removeCachedWorkout)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        LaunchedEffect(state.done) { if (state.done) goBack(true) }
        val summary = "${activity.sportName.displaySportName()}\n" +
            "${activity.start.dateTimeText(activity.offset)} · ${durationText(activity.start, activity.end)}"

        WhoofPage(title = null, onBack = if (state.busy) null else ({ goBack(false) })) {
            if (state.busy || state.done) {
                CenteredMessage("DELETING")
            } else {
                CenteredMessage("DELETE FROM WHOOP?", listOfNotNull(summary, state.error).joinToString("\n\n"))
                LightBottomBar(listOf(LightBarButton.Text(text = "DELETE", onClick = viewModel::delete)))
            }
        }
    }
}

/** Generic yes/no page: Back is "no", the bottom button is "yes". */
internal class ConfirmScreen(
    sealedActivity: SealedLightActivity,
    private val headline: String,
    private val detail: String,
    private val actionLabel: String,
) : SimpleLightScreen<Boolean>(sealedActivity) {
    @Composable
    override fun Content() {
        WhoofPage(title = null, onBack = { goBack(false) }) {
            CenteredMessage(headline, detail)
            LightBottomBar(listOf(LightBarButton.Text(text = actionLabel, onClick = { goBack(true) })))
        }
    }
}
