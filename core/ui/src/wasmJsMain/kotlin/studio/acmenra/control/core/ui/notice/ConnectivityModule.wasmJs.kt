package studio.acmenra.control.core.ui.notice

import kotlinx.browser.window
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.core.module.Module
import org.koin.dsl.module
import org.w3c.dom.events.Event

actual val connectivityModule: Module =
    module {
        single<ConnectivityMonitor> { WebConnectivityMonitor() }
    }

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun navigatorOnLine(): Boolean = js("navigator.onLine")

// navigator.onLine reports the device network link, not backend availability.
class WebConnectivityMonitor : ConnectivityMonitor {
    override val isOnline: Flow<Boolean> =
        callbackFlow {
            val onlineListener: (Event) -> Unit = { trySend(true) }
            val offlineListener: (Event) -> Unit = { trySend(false) }

            window.addEventListener("online", onlineListener)
            window.addEventListener("offline", offlineListener)

            trySend(navigatorOnLine())

            awaitClose {
                window.removeEventListener("online", onlineListener)
                window.removeEventListener("offline", offlineListener)
            }
        }.distinctUntilChanged()
}
