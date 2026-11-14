package studio.acmenra.control.feature.auth.presentation

import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.auth.domain.validation.isValidEmail

data class LoginState(
    val email: String = "",
    val password: String = "",
    val isEmailFormatError: Boolean = false,
    val isInvalidCredentialsError: Boolean = false,
    val isLoading: Boolean = false,
    val notices: List<Notice> = emptyList(),
    val isSuccess: Boolean = false,
) {
    val trimmedEmail: String
        get() = email.trim()

    val isEmailValid: Boolean
        get() = isValidEmail(trimmedEmail)

    // Кнопка активна только если поля не пусты, почта валидна и не идет загрузка
    val isSubmitEnabled: Boolean
        get() = trimmedEmail.isNotEmpty() && isEmailValid && password.isNotEmpty() && !isLoading
}
