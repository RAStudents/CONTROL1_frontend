package studio.acmenra.control.feature.events.presentation.platform

import org.koin.core.module.Module

interface ExternalMediaOpener {
    fun openMedia(url: String)
}

expect val externalMediaOpenerModule: Module
