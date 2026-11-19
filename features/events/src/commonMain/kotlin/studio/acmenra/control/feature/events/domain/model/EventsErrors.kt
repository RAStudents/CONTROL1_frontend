package studio.acmenra.control.feature.events.domain.model

import kotlin.time.Instant

sealed interface EventsInitialLoadError {
    data object Network : EventsInitialLoadError

    data object AccessDenied : EventsInitialLoadError

    data class InvalidFilter(
        val invalidFields: List<String>,
    ) : EventsInitialLoadError

    data class SnapshotTooLarge(
        val maxItems: Int? = null,
        val suggestedFrom: Instant? = null,
        val suggestedTo: Instant? = null,
    ) : EventsInitialLoadError

    data class Server(
        val message: String? = null,
    ) : EventsInitialLoadError
}

sealed interface EventsPaginationError {
    data object Network : EventsPaginationError

    data object SnapshotExpired : EventsPaginationError

    data class Server(
        val message: String? = null,
    ) : EventsPaginationError
}

sealed interface EventsMutationError {
    data object Network : EventsMutationError

    data object PermissionDenied : EventsMutationError

    data class StateConflict(
        val latestEvent: Event? = null,
    ) : EventsMutationError

    data object PayloadMismatch : EventsMutationError

    data class Server(
        val message: String? = null,
    ) : EventsMutationError
}

sealed interface EventsOperationResult<out T> {
    data class Success<T>(
        val data: T,
    ) : EventsOperationResult<T>

    data class Failure(
        val error: EventsMutationError,
    ) : EventsOperationResult<Nothing>
}
