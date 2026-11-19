package studio.acmenra.control.feature.events.presentation.platform

import org.koin.core.module.Module
import org.koin.dsl.module

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun openInNewTab(url: String) {
    js(
        """(() => {
        try {
            window.open(url, '_blank', 'noopener,noreferrer');
        } catch (e) {
            console.error('Failed to open url:', e);
        }
        })()""",
    )
}

class WasmExternalMediaOpener : ExternalMediaOpener {
    override fun openMedia(url: String) {
        openInNewTab(url)
    }
}

actual val externalMediaOpenerModule: Module =
    module {
        single<ExternalMediaOpener> { WasmExternalMediaOpener() }
    }
