package studio.acmenra.control.core.network.client

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import studio.acmenra.control.NetworkConfig

data class ApiConfig(
    val apiBaseUrl: String =
        validateBaseUrl(
            raw = NetworkConfig.BASE_URL,
            allowInsecureTransport = NetworkConfig.ALLOW_INSECURE_TRANSPORT,
        ),
    val enableLogs: Boolean = NetworkConfig.ENABLE_LOGS,
    val debugMode: Boolean = NetworkConfig.DEBUG_MODE,
    val allowInsecureTransport: Boolean = NetworkConfig.ALLOW_INSECURE_TRANSPORT,
) {
    companion object {
        fun validateBaseUrl(
            raw: String,
            allowInsecureTransport: Boolean,
        ): String {
            require(raw.isNotBlank()) {
                "API base URL must not be blank. Check BASE_URL in local.properties or NetworkConfig."
            }
            val trimmed = raw.trim()
            val url =
                requireNotNull(runCatching { Url(trimmed) }.getOrNull()) {
                    "Invalid API base URL: '$raw'"
                }
            require(url.protocol == URLProtocol.HTTPS || (allowInsecureTransport && url.protocol == URLProtocol.HTTP)) {
                if (url.protocol == URLProtocol.HTTP) {
                    "HTTP is only permitted when allowInsecureTransport=true. Got: $raw"
                } else {
                    "Unsupported API URL protocol '${url.protocol.name}'. Only HTTPS is supported in production."
                }
            }
            require(url.host.isNotBlank()) {
                "API host must not be empty. Got: $raw"
            }
            return url.toString().trimEnd('/') + "/"
        }
    }
}
