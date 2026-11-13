package studio.acmenra.control.feature.auth.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import studio.acmenra.control.core.network.client.safeRequest
import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.feature.auth.data.model.LoginRequestDto
import studio.acmenra.control.feature.auth.data.model.LoginResponseDto

class KtorAuthRemoteDataSource(
    private val httpClient: HttpClient,
) : AuthRemoteDataSource {
    override suspend fun login(request: LoginRequestDto): NetworkResult<LoginResponseDto> =
        httpClient.safeRequest {
            post("api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
}
