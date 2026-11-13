package studio.acmenra.control.feature.auth.domain.repository

import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.feature.auth.domain.model.AuthTokens
import studio.acmenra.control.feature.auth.domain.model.LoginCredentials

interface AuthRepository {
    suspend fun login(credentials: LoginCredentials): NetworkResult<AuthTokens>
}
