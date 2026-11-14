package studio.acmenra.control.di

import org.koin.dsl.module
import studio.acmenra.control.core.access.di.accessModule
import studio.acmenra.control.core.network.di.networkModule
import studio.acmenra.control.core.ui.notice.coreUiModule
import studio.acmenra.control.feature.auth.di.authModule
import studio.acmenra.control.feature.events.di.eventsModule

val appModule =
    module {
        includes(
            networkModule,
            accessModule,
            authModule,
            eventsModule,
            coreUiModule,
        )
    }
