package studio.acmenra.control.feature.auth.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import studio.acmenra.control.feature.auth.presentation.LoginViewModel

val authModule =
    module {
        viewModel {
            LoginViewModel()
        }
    }
