package studio.acmenra.control.core.ui.notice

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val connectivityModule: Module =
    module {
        single<ConnectivityMonitor> { AndroidConnectivityMonitor(androidContext()) }
    }

// Requires ACCESS_NETWORK_STATE in the androidApp manifest.
// Checks VALIDATED, so Wi-Fi without internet access counts as offline.
class AndroidConnectivityMonitor(
    context: Context,
) : ConnectivityMonitor {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override val isOnline: Flow<Boolean> =
        callbackFlow {
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        trySend(capabilities.isValidated())
                    }

                    override fun onLost(network: Network) {
                        trySend(false)
                    }
                }
            manager.registerDefaultNetworkCallback(callback)
            trySend(currentState())
            awaitClose { manager.unregisterNetworkCallback(callback) }
        }.distinctUntilChanged()

    private fun currentState(): Boolean {
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.isValidated() ?: false
    }

    private fun NetworkCapabilities.isValidated(): Boolean = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
