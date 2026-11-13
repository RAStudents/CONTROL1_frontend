package studio.acmenra.control.feature.auth.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import studio.acmenra.control.core.ui.notice.ConsumeNotice
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.auth.generated.resources.Res
import studio.acmenra.control.feature.auth.generated.resources.login_btn_test_error
import studio.acmenra.control.feature.auth.generated.resources.login_btn_test_info
import studio.acmenra.control.feature.auth.generated.resources.login_btn_test_offline
import studio.acmenra.control.feature.auth.generated.resources.login_btn_test_success
import studio.acmenra.control.feature.auth.generated.resources.login_title

@Composable
fun LoginRouteContent(
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    ConsumeNotice(
        notice = state.notice,
        onConsumed = viewModel::onNoticeConsumed,
    )

    LoginScreen(
        state = state,
        onShowInfo = viewModel::showInfoNotice,
        onShowSuccess = viewModel::showSuccessNotice,
        onShowError = viewModel::showErrorNotice,
        onShowOffline = viewModel::showOfflineNotice,
        modifier = modifier,
    )
}

@Composable
fun LoginScreen(
    state: LoginState,
    onShowInfo: () -> Unit,
    onShowSuccess: () -> Unit,
    onShowError: () -> Unit,
    onShowOffline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(AmberDimens.ScreenMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AmberDimens.GapMedium, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(Res.string.login_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Button(
            onClick = onShowInfo,
            modifier =
                Modifier
                    .widthIn(max = AmberDimens.NoticeMaxWidth)
                    .fillMaxWidth()
                    .height(AmberDimens.ButtonHeight),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = stringResource(Res.string.login_btn_test_info),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Button(
            onClick = onShowSuccess,
            modifier =
                Modifier
                    .widthIn(max = AmberDimens.NoticeMaxWidth)
                    .fillMaxWidth()
                    .height(AmberDimens.ButtonHeight),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = stringResource(Res.string.login_btn_test_success),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Button(
            onClick = onShowError,
            modifier =
                Modifier
                    .widthIn(max = AmberDimens.NoticeMaxWidth)
                    .fillMaxWidth()
                    .height(AmberDimens.ButtonHeight),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = stringResource(Res.string.login_btn_test_error),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Button(
            onClick = onShowOffline,
            modifier =
                Modifier
                    .widthIn(max = AmberDimens.NoticeMaxWidth)
                    .fillMaxWidth()
                    .height(AmberDimens.ButtonHeight),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = stringResource(Res.string.login_btn_test_offline),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
