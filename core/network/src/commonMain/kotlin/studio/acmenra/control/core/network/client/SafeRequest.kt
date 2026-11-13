package studio.acmenra.control.core.network.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult

suspend inline fun <reified T> HttpClient.safeRequest(
    crossinline block: suspend HttpClient.() -> HttpResponse,
): NetworkResult<T> =
    try {
        val response = this.block()

        if (response.status.isSuccess()) {
            try {
                val body = response.body<T>()
                NetworkResult.Success(data = body, statusCode = response.status.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerializationException) {
                NetworkResult.Failure(
                    NetworkError.Serialization(
                        message = "Failed to deserialize response: ${e.message}",
                        cause = e,
                    ),
                )
            } catch (e: Exception) {
                if (isConnectivityError(e)) {
                    NetworkResult.Failure(
                        NetworkError.Connectivity(
                            message = "Connection lost while reading response: ${e.message}",
                            cause = e,
                        ),
                    )
                } else {
                    NetworkResult.Failure(
                        NetworkError.Unknown(
                            message = e.message ?: "Failed to read response body",
                            cause = e,
                        ),
                    )
                }
            } catch (e: Throwable) {
                if (isConnectivityError(e)) {
                    NetworkResult.Failure(
                        NetworkError.Connectivity(
                            message = "Connection lost while reading response: ${e.message}",
                            cause = e,
                        ),
                    )
                } else {
                    throw e
                }
            }
        } else {
            val errorBody = response.readErrorBodySafely()
            NetworkResult.Failure(
                NetworkError.Http(
                    statusCode = response.status.value,
                    message = response.status.description,
                    errorBody = errorBody,
                ),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SerializationException) {
        NetworkResult.Failure(
            NetworkError.Serialization(
                message = "Serialization error: ${e.message}",
                cause = e,
            ),
        )
    } catch (e: Exception) {
        if (isConnectivityError(e)) {
            NetworkResult.Failure(
                NetworkError.Connectivity(
                    message = e.message ?: "Network connection failed",
                    cause = e,
                ),
            )
        } else {
            NetworkResult.Failure(
                NetworkError.Unknown(
                    message = e.message ?: "Unknown error occurred",
                    cause = e,
                ),
            )
        }
    } catch (e: Throwable) {
        if (isConnectivityError(e)) {
            NetworkResult.Failure(
                NetworkError.Connectivity(
                    message = e.message ?: "Network connection failed",
                    cause = e,
                ),
            )
        } else {
            throw e
        }
    }

fun isConnectivityError(throwable: Throwable): Boolean {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = throwable

    while (current != null && seen.add(current)) {
        if (isConnectivityThrowableType(current)) {
            return true
        }
        val message = current.message?.lowercase()
        if (message != null && isConnectivityMessage(message)) {
            return true
        }
        current = current.cause
    }
    return false
}

private fun isConnectivityThrowableType(t: Throwable): Boolean =
    t is HttpRequestTimeoutException ||
        t is ConnectTimeoutException ||
        t is SocketTimeoutException ||
        t is IOException

private fun isConnectivityMessage(message: String): Boolean =
    message.contains("failed to fetch") ||
        message.contains("fail to fetch") ||
        message.contains("networkerror") ||
        message.contains("network error") ||
        message.contains("load failed") ||
        message.contains("connection refused") ||
        message.contains("connection reset") ||
        message.contains("unable to resolve host") ||
        message.contains("unknownhostexception") ||
        message.contains("network is unreachable") ||
        message.contains("socket is closed") ||
        message.contains("timed out")

@PublishedApi
internal suspend fun HttpResponse.readErrorBodySafely(): String? =
    try {
        bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } catch (e: Throwable) {
        if (isConnectivityError(e)) {
            null
        } else {
            throw e
        }
    }
