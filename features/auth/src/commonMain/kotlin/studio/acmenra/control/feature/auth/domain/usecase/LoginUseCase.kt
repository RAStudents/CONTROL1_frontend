package studio.acmenra.control.feature.auth.domain.usecase

import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.feature.auth.domain.model.AuthTokens
import studio.acmenra.control.feature.auth.domain.model.LoginCredentials

interface LoginUseCase {
    suspend operator fun invoke(credentials: LoginCredentials): NetworkResult<AuthTokens>
}
