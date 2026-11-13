package studio.acmenra.control.feature.auth.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.core.ui.notice.NoticeText
import studio.acmenra.control.core.ui.notice.NoticeType
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

    fun onNoticeConsumed() {
        _state.update { it.copy(notice = null) }
    }

    fun showInfoNotice() {
        _state.update {
            it.copy(
                notice =
                    Notice.info(
                        title = Res.string.login_notice_info_title,
                        message = Res.string.login_notice_info_message,
                    ),
            )
        }
    }

    fun showSuccessNotice() {
        _state.update {
            it.copy(
                notice =
                    Notice.success(
                        title = Res.string.login_notice_success_title,
                        message = Res.string.login_notice_success_message,
                    ),
            )
        }
    }

    fun showErrorNotice() {
        _state.update {
            it.copy(
                notice =
                    Notice.error(
                        title = Res.string.login_notice_error_title,
                        message = Res.string.login_notice_error_message,
                    ),
            )
        }
    }

    fun showOfflineNotice() {
        _state.update {
            it.copy(
                notice =
                    Notice(
                        type = NoticeType.Offline,
                        title = NoticeText.Resource(Res.string.login_notice_offline_title),
                    ),
            )
        }
    }
}
