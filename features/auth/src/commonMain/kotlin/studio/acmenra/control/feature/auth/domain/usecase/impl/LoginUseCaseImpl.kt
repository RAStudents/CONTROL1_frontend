package studio.acmenra.control.feature.auth.domain.usecase.impl

import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.feature.auth.domain.model.AuthTokens
import studio.acmenra.control.feature.auth.domain.model.LoginCredentials
import studio.acmenra.control.feature.auth.domain.repository.AuthRepository
import studio.acmenra.control.feature.auth.domain.usecase.LoginUseCase

class LoginUseCaseImpl(
    private val authRepository: AuthRepository,
) : LoginUseCase {
    override suspend operator fun invoke(credentials: LoginCredentials): NetworkResult<AuthTokens> =
        authRepository.login(credentials)
}
