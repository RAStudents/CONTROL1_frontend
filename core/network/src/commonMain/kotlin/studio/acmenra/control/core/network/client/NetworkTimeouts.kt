package studio.acmenra.control.core.network.client

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout

object NetworkTimeouts {
    const val CONNECT_TIMEOUT_MILLIS = 15_000L
    const val REQUEST_TIMEOUT_MILLIS = 30_000L
    const val SOCKET_TIMEOUT_MILLIS = 30_000L
}

fun HttpClientConfig<*>.installNetworkTimeouts(
    connectTimeoutMillis: Long = NetworkTimeouts.CONNECT_TIMEOUT_MILLIS,
    requestTimeoutMillis: Long = NetworkTimeouts.REQUEST_TIMEOUT_MILLIS,
    socketTimeoutMillis: Long = NetworkTimeouts.SOCKET_TIMEOUT_MILLIS,
) {
    install(HttpTimeout) {
        this.connectTimeoutMillis = connectTimeoutMillis
        this.requestTimeoutMillis = requestTimeoutMillis
        this.socketTimeoutMillis = socketTimeoutMillis
    }
}
