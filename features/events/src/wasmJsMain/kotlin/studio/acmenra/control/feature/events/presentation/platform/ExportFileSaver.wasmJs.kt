package studio.acmenra.control.feature.events.presentation.platform

import io.ktor.util.toJsArray
import kotlinx.coroutines.CancellationException
import org.koin.core.module.Module
import org.koin.dsl.module
import studio.acmenra.control.feature.events.data.datasource.ExportPayloadRegistry
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload

/**
 * WasmJS implementation of [ExportFileSaver].
 *
 * Memory model and lifecycle:
 * - The payload is referenced via the opaque [downloadHandle] from [ExportPayloadRegistry].
 * - Direct transformation to [io.ktor.util.toJsArray] produces a JS `Int8Array` without intermediate Base64 encoding.
 * - In the browser JavaScript heap, a `Blob` wraps the `Int8Array`.
 * - Peak transient allocation is approximately 2-3x payload size (Kotlin ByteArray + JS TypedArray + Blob)
 *   reaching ~200-300 MiB for a maximum 100 MiB export.
 * - The DOM anchor element is immediately removed after click, and `URL.revokeObjectURL` is scheduled to reclaim memory.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(filename, contentType, int8Array) => {
    var url = null;
    var a = null;
    try {
        var blob = new Blob([int8Array], { type: contentType });
        url = URL.createObjectURL(blob);
        a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        return true;
    } catch (e) {
        console.error('Failed to trigger download:', e);
        return false;
    } finally {
        if (a && a.parentNode) {
            a.parentNode.removeChild(a);
        }
        if (url) {
            setTimeout(function() { URL.revokeObjectURL(url); }, 2000);
        }
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
            val payload = ExportPayloadRegistry.consume(download.downloadHandle) ?: return false
            val jsArray = payload.bytes.toJsArray()
            triggerBrowserDownload(
                filename = download.fileName,
                contentType = payload.contentType.ifBlank { "text/csv" },
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
