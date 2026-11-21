package studio.acmenra.control.feature.events.di

import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import studio.acmenra.control.core.network.auth.AccessInvalidationDispatcher
import studio.acmenra.control.core.network.client.ApiConfig
import studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.EventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.KtorEventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.KtorEventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.MockEventsDataStore
import studio.acmenra.control.feature.events.data.datasource.MockEventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.MockEventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.repository.EventsRepositoryImpl
import studio.acmenra.control.feature.events.domain.repository.EventsConnectivityProvider
import studio.acmenra.control.feature.events.domain.repository.EventsRepository
import studio.acmenra.control.feature.events.domain.usecase.AcknowledgeEventUseCase
import studio.acmenra.control.feature.events.domain.usecase.CreateEventsExportUseCase
import studio.acmenra.control.feature.events.domain.usecase.DownloadEventsExportUseCase
import studio.acmenra.control.feature.events.domain.usecase.LoadEventFilterOptionsUseCase
import studio.acmenra.control.feature.events.domain.usecase.LoadEventsBootstrapUseCase
import studio.acmenra.control.feature.events.domain.usecase.LoadEventsPageUseCase
import studio.acmenra.control.feature.events.domain.usecase.MarkEventErroneousUseCase
import studio.acmenra.control.feature.events.domain.usecase.ObserveEventsExportStatusUseCase
import studio.acmenra.control.feature.events.domain.usecase.ObserveEventsFeedUseCase
import studio.acmenra.control.feature.events.domain.usecase.impl.AcknowledgeEventUseCaseImpl
import studio.acmenra.control.feature.events.domain.usecase.impl.CreateEventsExportUseCaseImpl
import studio.acmenra.control.feature.events.domain.usecase.impl.DownloadEventsExportUseCaseImpl
import studio.acmenra.control.feature.events.domain.usecase.impl.LoadEventFilterOptionsUseCaseImpl
import studio.acmenra.control.feature.events.domain.usecase.impl.LoadEventsBootstrapUseCaseImpl
import studio.acmenra.control.feature.events.domain.usecase.impl.LoadEventsPageUseCaseImpl
import studio.acmenra.control.feature.events.domain.usecase.impl.MarkEventErroneousUseCaseImpl
import studio.acmenra.control.feature.events.domain.usecase.impl.ObserveEventsExportStatusUseCaseImpl
import studio.acmenra.control.feature.events.domain.usecase.impl.ObserveEventsFeedUseCaseImpl
import studio.acmenra.control.feature.events.presentation.EventsViewModel
import studio.acmenra.control.feature.events.presentation.platform.exportFileSaverModule
import studio.acmenra.control.feature.events.presentation.platform.externalMediaOpenerModule

val eventsModule =
    module {
        includes(exportFileSaverModule, externalMediaOpenerModule)

        single<EventsRemoteDataSource> {
            KtorEventsRemoteDataSource(
                apiClient = get(named("api")),
            )
        }

        factory<EventsWebSocketDataSource> {
            val config = get<ApiConfig>()
            KtorEventsWebSocketDataSource(
                realtimeClient = get(named("realtime")),
                wsEndpointBase = config.apiBaseUrl,
            )
        }

        single<EventsConnectivityProvider> {
            CoreUiEventsConnectivityAdapter(
                monitor = get(),
            )
        }

        single<EventsRepository> {
            EventsRepositoryImpl(
                remoteDataSource = get(),
                webSocketDataSourceFactory = { get() },
                sessionManager = get(),
                connectivityProvider = get(),
                accessInvalidationListener = getOrNull<AccessInvalidationDispatcher>(),
            )
        }

        single<ObserveEventsFeedUseCase> {
            ObserveEventsFeedUseCaseImpl(repository = get())
        }

        single<LoadEventsBootstrapUseCase> {
            LoadEventsBootstrapUseCaseImpl(repository = get())
        }

        single<LoadEventsPageUseCase> {
            LoadEventsPageUseCaseImpl(repository = get())
        }

        single<LoadEventFilterOptionsUseCase> {
            LoadEventFilterOptionsUseCaseImpl(repository = get())
        }

        single<AcknowledgeEventUseCase> {
            AcknowledgeEventUseCaseImpl(repository = get())
        }

        single<MarkEventErroneousUseCase> {
            MarkEventErroneousUseCaseImpl(repository = get())
        }

        single<CreateEventsExportUseCase> {
            CreateEventsExportUseCaseImpl(repository = get())
        }

        single<ObserveEventsExportStatusUseCase> {
            ObserveEventsExportStatusUseCaseImpl(repository = get())
        }

        single<DownloadEventsExportUseCase> {
            DownloadEventsExportUseCaseImpl(repository = get())
        }

        viewModel {
            EventsViewModel(
                observeEventsFeedUseCase = get(),
                loadEventFilterOptionsUseCase = get(),
                acknowledgeEventUseCase = get(),
                markEventErroneousUseCase = get(),
                createEventsExportUseCase = get(),
                observeEventsExportStatusUseCase = get(),
                downloadEventsExportUseCase = get(),
                exportFileSaver = get(),
            )
        }
    }

val eventsMockModule =
    module {
        single {
            MockEventsDataStore()
        }

        single<EventsRemoteDataSource>(named("mock")) {
            MockEventsRemoteDataSource(
                dataStore = get(),
            )
        }

        factory<EventsWebSocketDataSource>(named("mock")) {
            MockEventsWebSocketDataSource(
                dataStore = get(),
            )
        }
    }
