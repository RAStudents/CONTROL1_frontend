package studio.acmenra.control.core.access.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import studio.acmenra.control.core.access.data.model.AccessErrorResponseDto
import studio.acmenra.control.core.access.data.model.CompanyAccessResponseDto
import studio.acmenra.control.core.access.data.model.GlobalContextResponseDto
import studio.acmenra.control.core.network.auth.AccessInvalidationListener
import studio.acmenra.control.core.network.client.AccessInvalidationAttributeKey
import studio.acmenra.control.core.network.client.isConnectivityError
import studio.acmenra.control.core.network.client.safeRequest
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult

interface AccessRemoteDataSource {
    suspend fun getGlobalContext(): NetworkResult<GlobalContextResponseDto>

    suspend fun getCompanyAccess(
        companyId: String,
        ifNoneMatch: String?,
    ): NetworkResult<AccessFetchResult>
}

class KtorAccessRemoteDataSource(
    private val httpClient: HttpClient,
    private val invalidationListener: AccessInvalidationListener? = null,
) : AccessRemoteDataSource {
    override suspend fun getGlobalContext(): NetworkResult<GlobalContextResponseDto> =
        httpClient.safeRequest {
            get("api/v1/auth/context")
        }

    override suspend fun getCompanyAccess(
        companyId: String,
        ifNoneMatch: String?,
    ): NetworkResult<AccessFetchResult> =
        try {
            val response =
                httpClient.get("api/v1/companies/$companyId/access") {
                    if (!ifNoneMatch.isNullOrBlank()) {
                        header(HttpHeaders.IfNoneMatch, ifNoneMatch)
                    }
                }

            when {
                response.status == HttpStatusCode.NotModified -> {
                    NetworkResult.Success(AccessFetchResult.NotModified, statusCode = response.status.value)
                }

                response.status.isSuccess() -> {
                    try {
                        val body = response.body<CompanyAccessResponseDto>()
                        val etag = response.headers[HttpHeaders.ETag]
                        NetworkResult.Success(
                            AccessFetchResult.Modified(body, etag),
                            statusCode = response.status.value,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SerializationException) {
                        NetworkResult.Failure(
                            NetworkError.Serialization(
                                message = "Failed to deserialize company access: ${e.message}",
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
                    }
                }

                else -> {
                    val errorBody = response.readBodyTextSafely()
                    if (response.status == HttpStatusCode.Forbidden) {
                        val listener =
                            invalidationListener ?: httpClient.attributes.getOrNull(AccessInvalidationAttributeKey)
                        if (listener != null) {
                            val errorDto = AccessErrorResponseDto.parse(errorBody)
                            val resolvedCode = errorDto?.resolvedCode
                            val resolvedCompanyId = errorDto?.companyId ?: companyId
                            listener.onAccessInvalidated(resolvedCode, resolvedCompanyId)
                        }
                    }
                    NetworkResult.Failure(
                        NetworkError.Http(
                            statusCode = response.status.value,
                            message = response.status.description,
                            errorBody = errorBody,
                        ),
                    )
                }
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

    private suspend fun HttpResponse.readBodyTextSafely(): String? =
        try {
            bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } catch (e: Throwable) {
            if (isConnectivityError(e)) null else throw e
        }
}
