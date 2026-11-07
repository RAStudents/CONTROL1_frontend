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
            } catch (e: SerializationException) {
                NetworkResult.Failure(
                    NetworkError.Serialization(
                        message = "Failed to deserialize response: ${e.message}",
                        cause = e,
                    ),
                )
            } catch (e: HttpRequestTimeoutException) {
                NetworkResult.Failure(
                    NetworkError.Connectivity(
                        message = "Request timed out: ${e.message}",
                        cause = e,
                    ),
                )
            } catch (e: SocketTimeoutException) {
                NetworkResult.Failure(
                    NetworkError.Connectivity(
                        message = "Socket timed out: ${e.message}",
                        cause = e,
                    ),
                )
            } catch (e: IOException) {
                NetworkResult.Failure(
                    NetworkError.Connectivity(
                        message = "Connection lost while reading response: ${e.message}",
                        cause = e,
                    ),
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                NetworkResult.Failure(
                    NetworkError.Unknown(
                        message = e.message ?: "Failed to read response body",
                        cause = e,
                    ),
                )
            }
        } else {
            val errorBody = runCatching { response.bodyAsText() }.getOrNull()
            NetworkResult.Failure(
                NetworkError.Http(
                    statusCode = response.status.value,
                    message = response.status.description,
                    errorBody = errorBody,
                ),
            )
        }
    } catch (e: HttpRequestTimeoutException) {
        NetworkResult.Failure(
            NetworkError.Connectivity(
                message = "Request timed out: ${e.message}",
                cause = e,
            ),
        )
    } catch (e: ConnectTimeoutException) {
        NetworkResult.Failure(
            NetworkError.Connectivity(
                message = "Connection timed out: ${e.message}",
                cause = e,
            ),
        )
    } catch (e: SocketTimeoutException) {
        NetworkResult.Failure(
            NetworkError.Connectivity(
                message = "Socket timed out: ${e.message}",
                cause = e,
            ),
        )
    } catch (e: SerializationException) {
        NetworkResult.Failure(
            NetworkError.Serialization(
                message = "Serialization error: ${e.message}",
                cause = e,
            ),
        )
    } catch (e: IOException) {
        NetworkResult.Failure(
            NetworkError.Connectivity(
                message = "Network connection failed: ${e.message}",
                cause = e,
            ),
        )
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        NetworkResult.Failure(
            NetworkError.Unknown(
                message = e.message ?: "Unknown error occurred",
                cause = e,
            ),
        )
    }
