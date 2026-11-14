package studio.acmenra.control.core.access.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import studio.acmenra.control.core.access.data.datasource.AccessLocalDataSource
import studio.acmenra.control.core.access.data.datasource.AccessRemoteDataSource
import studio.acmenra.control.core.access.data.datasource.InMemoryAccessLocalDataSource
import studio.acmenra.control.core.access.data.datasource.KtorAccessRemoteDataSource
import studio.acmenra.control.core.access.data.mapper.AccessMapper
import studio.acmenra.control.core.access.data.repository.AccessRepositoryImpl
import studio.acmenra.control.core.access.domain.AccessInvalidationCoordinator
import studio.acmenra.control.core.access.domain.AccessManager
import studio.acmenra.control.core.access.domain.TenantContextCoordinator
import studio.acmenra.control.core.access.domain.concurrent.TenantSingleFlight
import studio.acmenra.control.core.access.domain.repository.AccessRepository
import studio.acmenra.control.core.access.domain.usecase.CheckPermissionUseCase
import studio.acmenra.control.core.access.domain.usecase.ObserveGlobalAccessUseCase
import studio.acmenra.control.core.access.domain.usecase.ObserveTenantContextUseCase
import studio.acmenra.control.core.access.domain.usecase.RefreshTenantUseCase
import studio.acmenra.control.core.access.domain.usecase.SelectTenantUseCase
import studio.acmenra.control.core.access.domain.usecase.impl.CheckPermissionUseCaseImpl
import studio.acmenra.control.core.access.domain.usecase.impl.ObserveGlobalAccessUseCaseImpl
import studio.acmenra.control.core.access.domain.usecase.impl.ObserveTenantContextUseCaseImpl
import studio.acmenra.control.core.access.domain.usecase.impl.RefreshTenantUseCaseImpl
import studio.acmenra.control.core.access.domain.usecase.impl.SelectTenantUseCaseImpl
import studio.acmenra.control.core.network.auth.AccessInvalidationDispatcher
import studio.acmenra.control.core.network.auth.AccessInvalidationListener

val accessDataModule =
    module {
        single { AccessMapper() }
        single<AccessLocalDataSource> { InMemoryAccessLocalDataSource() }
        single<AccessRemoteDataSource> {
            KtorAccessRemoteDataSource(
                httpClient = get(named("api")),
                invalidationListener = get<AccessInvalidationListener>(),
            )
        }
        single<AccessRepository> {
            AccessRepositoryImpl(
                remoteDataSource = get(),
                localDataSource = get(),
                mapper = get(),
            )
        }
    }

val accessDomainModule =
    module {
        single { TenantSingleFlight() }
        single {
            AccessManager(
                accessRepository = get(),
                sessionManager = get(),
                mapper = get(),
            )
        }
        single {
            TenantContextCoordinator(
                accessRepository = get(),
                accessManager = get(),
                sessionManager = get(),
                mapper = get(),
                singleFlight = get(),
            )
        }
        single {
            AccessInvalidationCoordinator(
                accessManager = get(),
                tenantCoordinator = get(),
            ).also { coordinator ->
                get<AccessInvalidationDispatcher>().addListener(coordinator)
            }
        }
        factory<ObserveGlobalAccessUseCase> {
            ObserveGlobalAccessUseCaseImpl(
                accessManager = get(),
            )
        }
        factory<ObserveTenantContextUseCase> {
            ObserveTenantContextUseCaseImpl(
                coordinator = get(),
            )
        }
        factory<SelectTenantUseCase> {
            SelectTenantUseCaseImpl(
                coordinator = get(),
            )
        }
        factory<RefreshTenantUseCase> {
            RefreshTenantUseCaseImpl(
                coordinator = get(),
            )
        }
        factory<CheckPermissionUseCase> {
            CheckPermissionUseCaseImpl(
                coordinator = get(),
            )
        }
    }

val accessModule =
    module {
        includes(accessDataModule, accessDomainModule)
    }
