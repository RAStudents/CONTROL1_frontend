package studio.acmenra.control.feature.events.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import studio.acmenra.control.feature.events.presentation.EventsViewModel

val eventsModule =
    module {
        viewModel {
            EventsViewModel()
        }
    }
