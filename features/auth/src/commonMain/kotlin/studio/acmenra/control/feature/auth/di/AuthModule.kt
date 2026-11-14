package studio.acmenra.control.feature.auth.di

import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import studio.acmenra.control.core.network.client.ApiConfig
import studio.acmenra.control.feature.auth.data.datasource.AuthRemoteDataSource
import studio.acmenra.control.feature.auth.data.datasource.KtorAuthRemoteDataSource
import studio.acmenra.control.feature.auth.data.repository.AuthRepositoryImpl
import studio.acmenra.control.feature.auth.domain.repository.AuthRepository
import studio.acmenra.control.feature.auth.domain.usecase.LoginUseCase
import studio.acmenra.control.feature.auth.domain.usecase.impl.LoginUseCaseImpl
import studio.acmenra.control.feature.auth.presentation.LoginViewModel

val authModule =
    module {
        single<AuthRemoteDataSource> {
            val apiConfig = getOrNull<ApiConfig>()
            KtorAuthRemoteDataSource(
                httpClient = get(named("api")),
                loginPath = apiConfig?.loginPath ?: "api/auth/login",
            )
        }

        single<AuthRepository> {
            AuthRepositoryImpl(
                remoteDataSource = get(),
            )
        }

        single<LoginUseCase> {
            LoginUseCaseImpl(
                authRepository = get(),
            )
        }

        viewModel {
            LoginViewModel(
                loginUseCase = get(),
                sessionManager = get(),
            )
        }
    }
