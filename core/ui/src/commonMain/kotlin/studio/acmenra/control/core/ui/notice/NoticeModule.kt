package studio.acmenra.control.core.ui.notice

import org.koin.core.module.Module
import org.koin.dsl.module

// Platform part: connectivity monitor for Android and for the browser
expect val connectivityModule: Module

// Include it in AppModule via includes(coreUiModule)
val coreUiModule: Module =
    module {
        single { NoticeController() }
        includes(connectivityModule)
    }
