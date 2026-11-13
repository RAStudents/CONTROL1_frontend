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
import studio.acmenra.control.feature.auth.generated.resources.login_error_bad_request
import studio.acmenra.control.feature.auth.generated.resources.login_error_forbidden
import studio.acmenra.control.feature.auth.generated.resources.login_error_general_server
import studio.acmenra.control.feature.auth.generated.resources.login_error_internal_server
import studio.acmenra.control.feature.auth.generated.resources.login_error_network_no_connection
import studio.acmenra.control.feature.auth.generated.resources.login_error_network_title
import studio.acmenra.control.feature.auth.generated.resources.login_error_serialization
import studio.acmenra.control.feature.auth.generated.resources.login_error_server_title
import studio.acmenra.control.feature.auth.generated.resources.login_error_service_unavailable
import studio.acmenra.control.feature.auth.generated.resources.login_error_too_many_requests
import studio.acmenra.control.feature.auth.generated.resources.login_error_unknown

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
                            NetworkError.Connectivity(cause = t)
                        } else if (t is Exception) {
                            NetworkError.Unknown(cause = t)
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
                    val messageRes =
                        when (error.statusCode) {
                            400 -> Res.string.login_error_bad_request
                            403 -> Res.string.login_error_forbidden
                            404 -> Res.string.login_error_service_unavailable
                            429 -> Res.string.login_error_too_many_requests
                            in 500..599 -> Res.string.login_error_internal_server
                            else -> Res.string.login_error_general_server
                        }
                    val notice =
                        Notice.error(
                            title = Res.string.login_error_server_title,
                            message = messageRes,
                            args = listOf(error.statusCode),
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
                val notice =
                    Notice.error(
                        title = Res.string.login_error_network_title,
                        message = Res.string.login_error_network_no_connection,
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
                val notice =
                    Notice.error(
                        title = Res.string.login_error_server_title,
                        message = Res.string.login_error_serialization,
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
                val notice =
                    Notice.error(
                        title = Res.string.login_error_server_title,
                        message = Res.string.login_error_unknown,
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
