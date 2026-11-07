package studio.acmenra.control.core.network.model

sealed interface NetworkResult<out T> {
    data class Success<T>(
        val data: T,
        val statusCode: Int = 200,
    ) : NetworkResult<T>

    data class Failure(
        val error: NetworkError,
    ) : NetworkResult<Nothing>

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
}

fun <T> NetworkResult<T>.getOrNull(): T? =
    when (this) {
        is NetworkResult.Success -> data
        is NetworkResult.Failure -> null
    }

fun <T> NetworkResult<T>.getOrDefault(defaultValue: T): T =
    when (this) {
        is NetworkResult.Success -> data
        is NetworkResult.Failure -> defaultValue
    }

inline fun <T, R> NetworkResult<T>.map(transform: (T) -> R): NetworkResult<R> =
    when (this) {
        is NetworkResult.Success -> NetworkResult.Success(transform(data), statusCode)
        is NetworkResult.Failure -> this
    }

inline fun <T> NetworkResult<T>.onSuccess(action: (T) -> Unit): NetworkResult<T> {
    if (this is NetworkResult.Success) action(data)
    return this
}

inline fun <T> NetworkResult<T>.onFailure(action: (NetworkError) -> Unit): NetworkResult<T> {
    if (this is NetworkResult.Failure) action(error)
    return this
}

inline fun <T, R> NetworkResult<T>.fold(
    onSuccess: (data: T, statusCode: Int) -> R,
    onFailure: (error: NetworkError) -> R,
): R =
    when (this) {
        is NetworkResult.Success -> onSuccess(data, statusCode)
        is NetworkResult.Failure -> onFailure(error)
    }
