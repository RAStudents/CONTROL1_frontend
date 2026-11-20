package studio.acmenra.control.feature.events.presentation.platform

import io.ktor.util.toJsArray
import kotlinx.coroutines.CancellationException
import org.koin.core.module.Module
import org.koin.dsl.module
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(filename, contentType, int8Array) => {
    try {
        var blob = new Blob([int8Array], { type: contentType });
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
    int8Array: JsAny,
): Boolean

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
class WasmExportFileSaver : ExportFileSaver {
    override suspend fun saveExportFile(download: EventsExportDownload): Boolean =
        try {
            val jsArray = download.bytes.toJsArray()
            triggerBrowserDownload(
                filename = download.filename,
                contentType = download.contentType.ifBlank { "text/csv" },
                int8Array = jsArray,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
}

actual val exportFileSaverModule: Module =
    module {
        single<ExportFileSaver> { WasmExportFileSaver() }
    }
