package studio.acmenra.control.feature.events.presentation.platform

import org.koin.core.module.Module
import org.koin.dsl.module
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(filename, contentType, base64Data) => {
    try {
        var byteChars = atob(base64Data);
        var byteNumbers = new Array(byteChars.length);
        for (var i = 0; i < byteChars.length; i++) {
            byteNumbers[i] = byteChars.charCodeAt(i);
        }
        var byteArray = new Uint8Array(byteNumbers);
        var blob = new Blob([byteArray], { type: contentType });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(function() { URL.revokeObjectURL(url); }, 5000);
        return true;
    } catch (e) {
        console.error('Failed to trigger download:', e);
        return false;
    }
}""",
)
private external fun triggerBrowserDownload(
    filename: String,
    contentType: String,
    base64Data: String,
): Boolean

class WasmExportFileSaver : ExportFileSaver {
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun saveExportFile(download: EventsExportDownload): Boolean =
        try {
            val base64 = Base64.Default.encode(download.bytes)
            triggerBrowserDownload(
                filename = download.filename,
                contentType = download.contentType.ifBlank { "text/csv" },
                base64Data = base64,
            )
        } catch (e: Throwable) {
            false
        }
}

actual val exportFileSaverModule: Module =
    module {
        single<ExportFileSaver> { WasmExportFileSaver() }
    }
