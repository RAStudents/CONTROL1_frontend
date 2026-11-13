package studio.acmenra.control.feature.auth.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.auth.generated.resources.Res
import studio.acmenra.control.feature.auth.generated.resources.login_notice_error_message
import studio.acmenra.control.feature.auth.generated.resources.login_notice_error_title
import studio.acmenra.control.feature.auth.generated.resources.login_notice_info_message
import studio.acmenra.control.feature.auth.generated.resources.login_notice_info_title
import studio.acmenra.control.feature.auth.generated.resources.login_notice_offline_title
import studio.acmenra.control.feature.auth.generated.resources.login_notice_success_message
import studio.acmenra.control.feature.auth.generated.resources.login_notice_success_title

class LoginViewModel : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun onNoticeConsumed(id: Long) {
        _state.update { state ->
            state.copy(notices = state.notices.filterNot { it.id == id })
        }
    }

    fun showInfoNotice() {
        enqueueNotice(
            Notice.info(
                title = Res.string.login_notice_info_title,
                message = Res.string.login_notice_info_message,
            ),
        )
    }

    fun showSuccessNotice() {
        enqueueNotice(
            Notice.success(
                title = Res.string.login_notice_success_title,
                message = Res.string.login_notice_success_message,
            ),
        )
    }

    fun showErrorNotice() {
        enqueueNotice(
            Notice.error(
                title = Res.string.login_notice_error_title,
                message = Res.string.login_notice_error_message,
            ),
        )
    }

    fun showOfflineNotice() {
        enqueueNotice(
            Notice.offline(
                title = Res.string.login_notice_offline_title,
            ),
        )
    }

    private fun enqueueNotice(notice: Notice) {
        _state.update { state ->
            state.copy(notices = state.notices + notice)
        }
    }
}
