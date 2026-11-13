package studio.acmenra.control.core.network.client

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import studio.acmenra.control.NetworkConfig

object NetworkTimeouts {
    val CONNECT_TIMEOUT_MILLIS: Long = NetworkConfig.CONNECT_TIMEOUT_MILLIS
    val REQUEST_TIMEOUT_MILLIS: Long = NetworkConfig.REQUEST_TIMEOUT_MILLIS
    val SOCKET_TIMEOUT_MILLIS: Long = NetworkConfig.SOCKET_TIMEOUT_MILLIS
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
