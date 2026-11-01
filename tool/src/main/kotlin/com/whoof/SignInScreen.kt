package com.whoof

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.whoof.privateapi.PrivateWhoopChallenge
import com.whoof.privateapi.PrivateWhoopError
import com.whoof.privateapi.PrivateWhoopException
import com.whoof.privateapi.PrivateWhoopLoginResult
import com.whoof.privateapi.PrivateWhoopServices
import com.whoof.privateapi.WhoopPrivateService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SignInStage { Credentials, Code }

internal enum class SignInField { Email, Password, Code }

/** Credentials live only in this state; nothing here is persisted or logged. */
internal data class SignInUiState(
    val stage: SignInStage = SignInStage.Credentials,
    val email: String = "",
    val password: String = "",
    val code: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val busyLabel: String = "SIGNING IN",
    val done: Boolean = false,
    val openEditor: SignInField? = null,
) {
    override fun toString(): String = "SignInUiState(redacted)"
}

internal fun PrivateWhoopError.signInMessage(): String = when (this) {
    PrivateWhoopError.CredentialsRejected -> "Email or password not accepted."
    PrivateWhoopError.AccountNotFound -> "No WHOOP account for that email."
    PrivateWhoopError.PasswordResetRequired -> "Password reset required. Reset it in the WHOOP app."
    PrivateWhoopError.AccountNotConfirmed -> "Account not confirmed. Finish setup in the WHOOP app."
    PrivateWhoopError.WrongCode -> "Wrong code."
    PrivateWhoopError.CodeExpired -> "Code expired. Sign in again."
    PrivateWhoopError.TooManyAttempts, PrivateWhoopError.RateLimited ->
        "Too many attempts. Wait and try again."
    PrivateWhoopError.Unauthorized -> "WHOOP rejected the sign-in."
    PrivateWhoopError.Rejected -> "WHOOP rejected the request."
    PrivateWhoopError.Unavailable -> "WHOOP could not be reached."
    PrivateWhoopError.UnsupportedChallenge -> "This verification method is not supported."
    PrivateWhoopError.InvalidResponse -> "WHOOP returned an unexpected response."
    PrivateWhoopError.StorageFailed -> "The session could not be saved."
    else -> "Sign-in failed."
}

internal fun PrivateWhoopError.scanMessage(): String = when (this) {
    PrivateWhoopError.InvalidInput -> "Not a Whoof account QR."
    PrivateWhoopError.Unauthorized, PrivateWhoopError.ReconnectRequired ->
        "Session expired. Make a new QR."
    else -> signInMessage()
}

internal class SignInViewModel(
    private val service: WhoopPrivateService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LightViewModel<Boolean>() {
    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state
    private var challenge: PrivateWhoopChallenge? = null

    fun setEmail(value: String) = _state.update {
        it.copy(email = value.trim(), error = null, openEditor = if (it.password.isEmpty() && value.isNotBlank()) SignInField.Password else null)
    }

    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun setCode(value: String) = _state.update { it.copy(code = value.trim(), error = null) }

    fun editorOpened() = _state.update { it.copy(openEditor = null) }

    /** Drop everything typed, including a pending challenge, when the user leaves. */
    fun reset() {
        challenge = null
        _state.value = SignInUiState()
    }

    fun submit() {
        val current = _state.value
        if (current.busy || current.done) return
        when (current.stage) {
            SignInStage.Credentials -> {
                if (current.email.isBlank() || current.password.isEmpty()) {
                    _state.update { it.copy(error = "Enter your email and password.") }
                    return
                }
                _state.update { it.copy(busy = true, busyLabel = "SIGNING IN", error = null) }
                val email = current.email
                val password = current.password
                viewModelScope.launch { attempt { service.beginLogin(email, password) } }
            }
            SignInStage.Code -> {
                val pending = challenge
                if (pending == null) {
                    _state.update { it.copy(stage = SignInStage.Credentials, code = "", error = "Sign in again.") }
                    return
                }
                if (current.code.isBlank()) {
                    _state.update { it.copy(error = "Enter the code.") }
                    return
                }
                _state.update { it.copy(busy = true, busyLabel = "SIGNING IN", error = null) }
                val code = current.code
                viewModelScope.launch { attempt { service.completeLogin(pending, code) } }
            }
        }
    }

    /** Provision from a scanned private-session QR. The scanned text is never stored or shown. */
    fun provisionScanned(text: String) {
        val current = _state.value
        if (current.busy || current.done) return
        _state.update { it.copy(busy = true, busyLabel = "CONNECTING", error = null) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { service.provision(text) }
                challenge = null
                _state.update { it.copy(password = "", code = "", busy = false, done = true) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                val message = (error as? PrivateWhoopException)?.reason?.scanMessage() ?: "Sign-in failed."
                _state.update { it.copy(busy = false, error = message) }
            }
        }
    }

    private suspend fun attempt(call: suspend () -> PrivateWhoopLoginResult) {
        val stage = _state.value.stage
        try {
            when (val result = withContext(ioDispatcher) { call() }) {
                is PrivateWhoopLoginResult.Authenticated -> {
                    challenge = null
                    _state.update { it.copy(password = "", code = "", busy = false, done = true) }
                }
                is PrivateWhoopLoginResult.VerificationRequired -> {
                    if (stage == SignInStage.Code) {
                        // A second challenge after the code is not part of the supported flow.
                        challenge = null
                        _state.update {
                            it.copy(stage = SignInStage.Credentials, password = "", code = "", busy = false,
                                error = PrivateWhoopError.UnsupportedChallenge.signInMessage())
                        }
                    } else {
                        challenge = result.challenge
                        _state.update { it.copy(stage = SignInStage.Code, password = "", code = "", busy = false) }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            val reason = (error as? PrivateWhoopException)?.reason
            val message = reason?.signInMessage() ?: "Sign-in failed."
            _state.update {
                when {
                    stage == SignInStage.Code && reason == PrivateWhoopError.WrongCode ->
                        it.copy(code = "", busy = false, error = message)
                    stage == SignInStage.Code && (reason == PrivateWhoopError.CodeExpired ||
                        reason == PrivateWhoopError.CredentialsRejected) -> {
                        // The challenge session is spent; go back to the credentials, keeping the email.
                        challenge = null
                        it.copy(stage = SignInStage.Credentials, password = "", code = "", busy = false,
                            error = "Verification expired. Sign in again.")
                    }
                    stage == SignInStage.Code -> it.copy(code = "", busy = false, error = message)
                    else -> it.copy(password = "", busy = false, error = message)
                }
            }
        }
    }

    override fun onCleared() {
        challenge = null
        super.onCleared()
    }
}

internal class SignInScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Boolean, SignInViewModel>(sealedActivity) {
    override val viewModelClass: Class<SignInViewModel> = SignInViewModel::class.java

    override fun createViewModel(): SignInViewModel =
        SignInViewModel(PrivateWhoopServices.getInstance(lightContext))

    private fun leave(result: Boolean) {
        viewModel.reset()
        goBack(result)
    }

    private fun edit(field: SignInField) {
        val state = viewModel.state.value
        when (field) {
            SignInField.Email -> navigateTo(
                { TextEntryScreen(it, "EMAIL", state.email) },
                { viewModel.setEmail(it) },
            )
            SignInField.Password -> navigateTo(
                { TextEntryScreen(it, "PASSWORD", "", "CONTINUE") },
                { viewModel.setPassword(it); viewModel.submit() },
            )
            SignInField.Code -> navigateTo(
                { TextEntryScreen(it, "CODE", "", "VERIFY") },
                { viewModel.setCode(it); viewModel.submit() },
            )
        }
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()

        LaunchedEffect(state.done) { if (state.done) leave(true) }
        LaunchedEffect(state.openEditor) {
            state.openEditor?.let {
                viewModel.editorOpened()
                edit(it)
            }
        }

        WhoofPage(
            title = "SIGN IN",
            onBack = { leave(false) },
            rightButton = if (state.busy) null else LightBarButton.LightIcon(
                LightIcons.CAMERA,
                onClick = {
                    navigateTo({ ProvisioningScreen(it, "SCAN ACCOUNT QR") }) { viewModel.provisionScanned(it) }
                },
                contentDescription = "Scan account QR",
            ),
        ) {
            if (state.busy) {
                CenteredMessage(state.busyLabel)
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    when (state.stage) {
                        SignInStage.Credentials -> {
                            LightTextField(
                                label = "Email:",
                                value = state.email,
                                placeholder = "WHOOP email",
                                onClick = { edit(SignInField.Email) },
                            )
                            LightTextField(
                                label = "Password:",
                                value = "•".repeat(state.password.length.coerceAtMost(24)),
                                placeholder = "WHOOP password",
                                onClick = { edit(SignInField.Password) },
                            )
                        }
                        SignInStage.Code -> {
                            LightText(
                                text = "WHOOP sent you a verification code.",
                                variant = LightTextVariant.Paragraph,
                                lighten = true,
                                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                            )
                            LightTextField(
                                label = "Code:",
                                value = state.code,
                                placeholder = "Verification code",
                                onClick = { edit(SignInField.Code) },
                            )
                        }
                    }
                    state.error?.let {
                        LightText(
                            text = it,
                            variant = LightTextVariant.Paragraph,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                        )
                    }
                }
                LightBottomBar(
                    listOf(
                        LightBarButton.Text(
                            text = if (state.stage == SignInStage.Code) "VERIFY" else "SIGN IN",
                            onClick = viewModel::submit,
                        ),
                    ),
                )
            }
        }
    }
}

/** Single-line keyboard entry. Returns the text only when submitted; Back returns nothing. */
internal class TextEntryScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String,
    private val submitLabel: String = "DONE",
) : SimpleLightScreen<String>(sealedActivity) {
    @Composable
    override fun Content() {
        val textState = rememberTextFieldState(initial)
        val colors by LightThemeController.colors.collectAsState()
        val keyboardOptions = rememberKeyboardOptions()
        LightTheme(colors = colors) {
            LightTextInputEditor(
                title = title,
                state = textState,
                keyboardOptionsFlow = keyboardOptions,
                onSubmit = { goBack(it.toString()) },
                onBack = { goBack() },
                submitLabel = submitLabel,
                singleLine = true,
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}

internal class AccountViewModel(
    private val signOutAndClear: suspend () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LightViewModel<Boolean>() {
    data class State(val busy: Boolean = false, val signedOut: Boolean = false, val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun signOut() {
        if (_state.value.busy) return
        _state.value = State(busy = true)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { signOutAndClear() }
                _state.value = State(signedOut = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.value = State(error = "Could not sign out.")
            }
        }
    }
}

internal class AccountScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Boolean, AccountViewModel>(sealedActivity) {
    override val viewModelClass: Class<AccountViewModel> = AccountViewModel::class.java

    private val repository = DefaultWhoofRepository.getInstance(lightContext)

    override fun createViewModel(): AccountViewModel = AccountViewModel(repository::signOut)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        LaunchedEffect(state.signedOut) { if (state.signedOut) goBack(true) }

        WhoofPage(title = "WHOOP ACCOUNT", onBack = { goBack() }) {
            CenteredMessage(
                if (state.busy) "SIGNING OUT" else "Signed in",
                state.error ?: if (state.busy) null else "Signing out also removes cached WHOOP data from this phone.",
            )
            if (!state.busy) {
                LightBottomBar(listOf(LightBarButton.Text(text = "SIGN OUT", onClick = viewModel::signOut)))
            }
        }
    }
}
