package studio.acmenra.control.core.network.auth

sealed interface SessionState {
    data object Authenticated : SessionState

    data object Unauthenticated : SessionState

    data object SessionExpired : SessionState
}
