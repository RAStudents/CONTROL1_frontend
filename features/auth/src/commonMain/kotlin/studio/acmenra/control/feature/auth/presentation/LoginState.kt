package studio.acmenra.control.feature.auth.presentation

import studio.acmenra.control.core.ui.notice.Notice

data class LoginState(
    val isLoading: Boolean = false,
    val notice: Notice? = null,
)
