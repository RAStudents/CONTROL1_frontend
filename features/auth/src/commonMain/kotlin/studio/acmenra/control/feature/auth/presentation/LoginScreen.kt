package studio.acmenra.control.feature.auth.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import studio.acmenra.control.core.ui.components.AmberButton
import studio.acmenra.control.core.ui.components.AmberEmailField
import studio.acmenra.control.core.ui.components.AmberPasswordField
import studio.acmenra.control.core.ui.notice.ConsumeNotices
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.auth.generated.resources.Res
import studio.acmenra.control.feature.auth.generated.resources.login_button_submit
import studio.acmenra.control.feature.auth.generated.resources.login_email_label
import studio.acmenra.control.feature.auth.generated.resources.login_error_invalid_credentials
import studio.acmenra.control.feature.auth.generated.resources.login_error_invalid_email
import studio.acmenra.control.feature.auth.generated.resources.login_forgot_password
import studio.acmenra.control.feature.auth.generated.resources.login_password_label
import studio.acmenra.control.feature.auth.generated.resources.login_title

@Composable
fun LoginRouteContent(
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val autofillManager = LocalAutofillManager.current

    LaunchedEffect(state.isSuccess) {
        if (state.isSuccess) {
            autofillManager?.commit()
        }
    }

    ConsumeNotices(
        notices = state.notices,
        onConsumed = viewModel::onNoticeConsumed,
    )

    LoginScreen(
        state = state,
        onEmailChanged = viewModel::onEmailChanged,
        onEmailFocusLost = viewModel::onEmailFocusLost,
        onPasswordChanged = viewModel::onPasswordChanged,
        onSubmit = viewModel::onSubmit,
        modifier = modifier,
    )
}

@Composable
fun LoginScreen(
    state: LoginState,
    onEmailChanged: (String) -> Unit,
    onEmailFocusLost: () -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    onForgotPassword: () -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isDesktop = maxWidth >= AmberDimens.TabletBreakpoint

        if (isDesktop) {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(AmberDimens.ScreenMargin),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(AmberCorners.ExtraLarge))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                )
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    LoginForm(
                        state = state,
                        onEmailChanged = onEmailChanged,
                        onEmailFocusLost = onEmailFocusLost,
                        onPasswordChanged = onPasswordChanged,
                        onSubmit = onSubmit,
                        onForgotPassword = onForgotPassword,
                        modifier = Modifier.width(AmberDimens.LoginFormDesktopWidth),
                    )
                }
            }
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = AmberDimens.ScreenMargin),
                contentAlignment = Alignment.Center,
            ) {
                LoginForm(
                    state = state,
                    onEmailChanged = onEmailChanged,
                    onEmailFocusLost = onEmailFocusLost,
                    onPasswordChanged = onPasswordChanged,
                    onSubmit = onSubmit,
                    onForgotPassword = onForgotPassword,
                    modifier =
                        Modifier
                            .widthIn(max = AmberDimens.LoginFormMobileWidth)
                            .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LoginForm(
    state: LoginState,
    onEmailChanged: (String) -> Unit,
    onEmailFocusLost: () -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val passwordFocusRequester = remember { FocusRequester() }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AmberDimens.GapMedium),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.login_title),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )

        AmberEmailField(
            value = state.email,
            onValueChange = onEmailChanged,
            label = stringResource(Res.string.login_email_label),
            modifier = Modifier.fillMaxWidth(),
            isError = state.isEmailFormatError || state.isInvalidCredentialsError,
            errorText =
                if (state.isEmailFormatError) {
                    stringResource(Res.string.login_error_invalid_email)
                } else {
                    null
                },
            enabled = !state.isLoading,
            imeAction = ImeAction.Next,
            onImeAction = { passwordFocusRequester.requestFocus() },
            onFocusLost = onEmailFocusLost,
        )

        AmberPasswordField(
            value = state.password,
            onValueChange = onPasswordChanged,
            label = stringResource(Res.string.login_password_label),
            modifier = Modifier.fillMaxWidth().focusRequester(passwordFocusRequester),
            isError = state.isInvalidCredentialsError,
            errorText =
                if (state.isInvalidCredentialsError) {
                    stringResource(Res.string.login_error_invalid_credentials)
                } else {
                    null
                },
            enabled = !state.isLoading,
            imeAction = ImeAction.Done,
            onImeAction = onSubmit,
        )

        AmberButton(
            text = stringResource(Res.string.login_button_submit),
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.isSubmitEnabled,
            isLoading = state.isLoading,
        )

        TextButton(
            onClick = onForgotPassword,
        ) {
            Text(
                text = stringResource(Res.string.login_forgot_password),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
