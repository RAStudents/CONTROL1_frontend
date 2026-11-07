package studio.acmenra.control.core.network.auth

sealed interface SessionState {
    data class Authenticated(
        val accessToken: String,
    ) : SessionState

    data object Unauthenticated : SessionState

    data object SessionExpired : SessionState
}
