package studio.acmenra.control.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.client.isConnectivityError
import studio.acmenra.control.core.network.client.isFatalError
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.auth.data.mapper.toTokenPair
import studio.acmenra.control.feature.auth.domain.model.LoginCredentials
import studio.acmenra.control.feature.auth.domain.usecase.LoginUseCase
import studio.acmenra.control.feature.auth.domain.validation.isValidEmail
import studio.acmenra.control.feature.auth.generated.resources.Res
import studio.acmenra.control.feature.auth.generated.resources.login_error_network_title
import studio.acmenra.control.feature.auth.generated.resources.login_error_server_title

class LoginViewModel(
    private val loginUseCase: LoginUseCase,
    private val sessionManager: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private var loginJob: Job? = null
    private var nextNoticeId: Long = 1L

    fun onEmailChanged(newEmail: String) {
        _state.update { current ->
            val trimmed = newEmail.trim()
            val shouldResetFormatError =
                current.isEmailFormatError && (trimmed.isEmpty() || isValidEmail(trimmed))
            current.copy(
                email = newEmail,
                isInvalidCredentialsError = false,
                isEmailFormatError = if (shouldResetFormatError) false else current.isEmailFormatError,
            )
        }
    }

    fun onEmailFocusLost() {
        _state.update { current ->
            if (current.trimmedEmail.isNotEmpty() && !current.isEmailValid) {
                current.copy(isEmailFormatError = true)
            } else {
                current
            }
        }
    }

    fun onPasswordChanged(newPassword: String) {
        _state.update { current ->
            current.copy(
                password = newPassword,
                isInvalidCredentialsError = false,
            )
        }
    }

    fun onSubmit() {
        if (_state.value.isLoading || loginJob?.isActive == true) {
            return
        }

        val currentState = _state.value
        if (!currentState.isEmailValid) {
            _state.update { it.copy(isEmailFormatError = true) }
            return
        }

        if (currentState.password.isEmpty()) {
            return
        }

        _state.update {
            it.copy(
                isLoading = true,
                isInvalidCredentialsError = false,
                isEmailFormatError = false,
            )
        }

        val emailToSend = currentState.trimmedEmail
        val passwordToSend = currentState.password

        loginJob =
            viewModelScope.launch {
                try {
                    when (val result = loginUseCase(LoginCredentials(email = emailToSend, password = passwordToSend))) {
                        is NetworkResult.Success -> {
                            sessionManager.saveSession(result.data.toTokenPair())
                            _state.update {
                                it.copy(
                                    password = "",
                                    isLoading = false,
                                    isSuccess = true,
                                )
                            }
                        }

                        is NetworkResult.Failure -> {
                            handleFailure(result.error)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (isFatalError(t)) throw t
                    val error =
                        if (isConnectivityError(t)) {
                            NetworkError.Connectivity(
                                message = t.message?.take(120) ?: "Ошибка подключения к сети",
                                cause = t,
                            )
                        } else if (t is Exception) {
                            NetworkError.Unknown(
                                message = t.message?.take(120) ?: "Неизвестная ошибка",
                                cause = t,
                            )
                        } else {
                            throw t
                        }
                    handleFailure(error)
                }
            }
    }

    fun onNoticeConsumed(id: Long) {
        _state.update { current ->
            current.copy(notices = current.notices.filterNot { it.id == id })
        }
    }

    private fun handleFailure(error: NetworkError) {
        when (error) {
            is NetworkError.Http -> {
                val isAuthError =
                    error.statusCode == 401 ||
                        (error.statusCode == 400 && isInvalidCredentialsErrorBody(error.errorBody ?: error.message))
                if (isAuthError) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isInvalidCredentialsError = true,
                        )
                    }
                } else {
                    val safeUserMessage =
                        when (error.statusCode) {
                            400 -> "Неверный запрос"
                            403 -> "Доступ запрещен"
                            404 -> "Сервис авторизации недоступен"
                            429 -> "Слишком много запросов. Попробуйте позже"
                            in 500..599 -> "Внутренняя ошибка сервера"
                            else -> "Ошибка сервера"
                        }
                    val formattedMessage = "$safeUserMessage (код ${error.statusCode})"
                    val notice =
                        Notice.error(
                            title = Res.string.login_error_server_title,
                            serverMessage = formattedMessage,
                            id = nextNoticeId++,
                        )
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isInvalidCredentialsError = false,
                            notices = it.notices + notice,
                        )
                    }
                }
            }

            is NetworkError.Connectivity -> {
                val message = error.message?.takeIf { it.isNotBlank() }?.take(120) ?: "Нет подключения к сети"
                val notice =
                    Notice.error(
                        title = Res.string.login_error_network_title,
                        serverMessage = message,
                        id = nextNoticeId++,
                    )
                _state.update {
                    it.copy(
                        isLoading = false,
                        isInvalidCredentialsError = false,
                        notices = it.notices + notice,
                    )
                }
            }

            is NetworkError.Serialization -> {
                val message = error.message?.takeIf { it.isNotBlank() }?.take(120) ?: "Ошибка обработки данных"
                val notice =
                    Notice.error(
                        title = Res.string.login_error_server_title,
                        serverMessage = message,
                        id = nextNoticeId++,
                    )
                _state.update {
                    it.copy(
                        isLoading = false,
                        isInvalidCredentialsError = false,
                        notices = it.notices + notice,
                    )
                }
            }

            is NetworkError.Unknown -> {
                val message = error.message?.takeIf { it.isNotBlank() }?.take(120) ?: "Неизвестная ошибка"
                val notice =
                    Notice.error(
                        title = Res.string.login_error_server_title,
                        serverMessage = message,
                        id = nextNoticeId++,
                    )
                _state.update {
                    it.copy(
                        isLoading = false,
                        isInvalidCredentialsError = false,
                        notices = it.notices + notice,
                    )
                }
            }
        }
    }

    private fun isInvalidCredentialsErrorBody(body: String?): Boolean {
        if (body == null) return false
        val lower = body.lowercase()
        return lower.contains("invalid_grant") ||
            lower.contains("invalid_credentials") ||
            lower.contains("bad_credentials") ||
            lower.contains("invalid password") ||
            lower.contains("user not found")
    }
}
