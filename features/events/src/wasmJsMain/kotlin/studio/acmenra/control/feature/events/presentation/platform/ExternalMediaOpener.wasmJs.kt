package studio.acmenra.control.feature.events.presentation.platform

import org.koin.core.module.Module
import org.koin.dsl.module

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(url) => {
    try {
        var parsed = new URL(url);
        if (parsed.protocol === 'https:' && parsed.hostname && parsed.hostname !== 'localhost') {
            window.open(url, '_blank', 'noopener,noreferrer');
        } else {
            console.warn('Rejected non-https or invalid external media URL:', url);
        }
    } catch (e) {
        console.error('Failed to open url:', e);
    }
}""",
)
private external fun openInNewTab(url: String)

class WasmExternalMediaOpener : ExternalMediaOpener {
    override fun openMedia(url: String) {
        if (url.startsWith("https://", ignoreCase = true)) {
            openInNewTab(url)
        }
    }
}

actual val externalMediaOpenerModule: Module =
    module {
        single<ExternalMediaOpener> { WasmExternalMediaOpener() }
    }
