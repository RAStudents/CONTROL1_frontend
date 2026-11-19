package studio.acmenra.control.feature.events.presentation.platform

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload
import java.io.File
import java.io.FileOutputStream

class AndroidExportFileSaver(
    private val context: Context,
) : ExportFileSaver {
    override suspend fun saveExportFile(download: EventsExportDownload): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, download.filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, download.contentType.ifBlank { "text/csv" })
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(download.bytes)
                    out.flush()
                }
                true
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetFile = File(downloadsDir, download.filename)
                FileOutputStream(targetFile).use { out ->
                    out.write(download.bytes)
                    out.flush()
                }
                true
            }
        } catch (e: Throwable) {
            runCatching {
                val fallbackFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), download.filename)
                FileOutputStream(fallbackFile).use { out ->
                    out.write(download.bytes)
                    out.flush()
                }
                true
            }.getOrDefault(false)
        }
    }
}

actual val exportFileSaverModule: Module =
    module {
        single<ExportFileSaver> {
            val context = runCatching { androidContext() }.getOrNull()
            if (context != null) {
                AndroidExportFileSaver(context)
            } else {
                object : ExportFileSaver {
                    override suspend fun saveExportFile(download: EventsExportDownload): Boolean = false
                }
            }
        }
    }
