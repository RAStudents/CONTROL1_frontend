package studio.acmenra.control.core.ui.notice

import kotlinx.browser.window
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.core.module.Module
import org.koin.dsl.module

actual val connectivityModule: Module =
    module {
        single<ConnectivityMonitor> { WebConnectivityMonitor() }
    }

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun navigatorOnLine(): Boolean = js("navigator.onLine")

// navigator.onLine reports the device network link, not backend availability.
// Listeners are never removed: the monitor lives as long as the tab does.
class WebConnectivityMonitor : ConnectivityMonitor {
    override val isOnline: Flow<Boolean> =
        callbackFlow {
            trySend(navigatorOnLine())
            window.addEventListener("online") { trySend(true) }
            window.addEventListener("offline") { trySend(false) }
            awaitClose { }
        }.distinctUntilChanged()
}
