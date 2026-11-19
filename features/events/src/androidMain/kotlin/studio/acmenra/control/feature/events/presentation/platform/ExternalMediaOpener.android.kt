package studio.acmenra.control.feature.events.presentation.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

class AndroidExternalMediaOpener(
    private val context: Context,
) : ExternalMediaOpener {
    override fun openMedia(url: String) {
        try {
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
    }
}

actual val externalMediaOpenerModule: Module =
    module {
        single<ExternalMediaOpener> {
            val context = runCatching { androidContext() }.getOrNull()
            if (context != null) {
                AndroidExternalMediaOpener(context)
            } else {
                object : ExternalMediaOpener {
                    override fun openMedia(url: String) {}
                }
            }
        }
    }
