package studio.acmenra.control.feature.auth.data.repository

import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.feature.auth.data.datasource.AuthRemoteDataSource
import studio.acmenra.control.feature.auth.data.mapper.toDomain
import studio.acmenra.control.feature.auth.data.mapper.toDto
import studio.acmenra.control.feature.auth.domain.model.AuthTokens
import studio.acmenra.control.feature.auth.domain.model.LoginCredentials
import studio.acmenra.control.feature.auth.domain.repository.AuthRepository

class AuthRepositoryImpl(
    private val remoteDataSource: AuthRemoteDataSource,
) : AuthRepository {
    override suspend fun login(credentials: LoginCredentials): NetworkResult<AuthTokens> =
        when (val result = remoteDataSource.login(credentials.toDto())) {
            is NetworkResult.Success -> {
                val dto = result.data
                if (dto.accessToken.isBlank() || dto.refreshToken.isBlank()) {
                    NetworkResult.Failure(
                        NetworkError.Serialization(
                            message = "Сервер вернул пустые токены авторизации",
                        ),
                    )
                } else {
                    NetworkResult.Success(dto.toDomain(), result.statusCode)
                }
            }

            is NetworkResult.Failure -> result
        }
}
