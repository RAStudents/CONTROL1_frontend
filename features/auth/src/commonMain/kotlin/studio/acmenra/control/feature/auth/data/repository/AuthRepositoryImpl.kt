package studio.acmenra.control.feature.auth.data.repository

import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.core.network.model.map
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
        remoteDataSource.login(credentials.toDto()).map { it.toDomain() }
}
