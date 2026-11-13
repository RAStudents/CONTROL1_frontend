package studio.acmenra.control.core.network.client

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import studio.acmenra.control.NetworkConfig

class ApiConfig(
    apiBaseUrl: String = NetworkConfig.BASE_URL,
    val enableLogs: Boolean = NetworkConfig.ENABLE_LOGS,
    val debugMode: Boolean = NetworkConfig.DEBUG_MODE,
    val allowInsecureTransport: Boolean = NetworkConfig.ALLOW_INSECURE_TRANSPORT,
    val connectTimeoutMillis: Long = NetworkConfig.CONNECT_TIMEOUT_MILLIS,
    val requestTimeoutMillis: Long = NetworkConfig.REQUEST_TIMEOUT_MILLIS,
    val socketTimeoutMillis: Long = NetworkConfig.SOCKET_TIMEOUT_MILLIS,
    val refreshPath: String = NetworkConfig.REFRESH_PATH,
    val loginPath: String = NetworkConfig.LOGIN_PATH,
) {
    val apiBaseUrl: String =
        validateBaseUrl(
            raw = apiBaseUrl,
            allowInsecureTransport = allowInsecureTransport,
        )

    fun copy(
        apiBaseUrl: String = this.apiBaseUrl,
        enableLogs: Boolean = this.enableLogs,
        debugMode: Boolean = this.debugMode,
        allowInsecureTransport: Boolean = this.allowInsecureTransport,
        connectTimeoutMillis: Long = this.connectTimeoutMillis,
        requestTimeoutMillis: Long = this.requestTimeoutMillis,
        socketTimeoutMillis: Long = this.socketTimeoutMillis,
        refreshPath: String = this.refreshPath,
        loginPath: String = this.loginPath,
    ): ApiConfig =
        ApiConfig(
            apiBaseUrl = apiBaseUrl,
            enableLogs = enableLogs,
            debugMode = debugMode,
            allowInsecureTransport = allowInsecureTransport,
            connectTimeoutMillis = connectTimeoutMillis,
            requestTimeoutMillis = requestTimeoutMillis,
            socketTimeoutMillis = socketTimeoutMillis,
            refreshPath = refreshPath,
            loginPath = loginPath,
        )

    override fun equals(other: Any?): Boolean =
        other is ApiConfig &&
            apiBaseUrl == other.apiBaseUrl &&
            enableLogs == other.enableLogs &&
            debugMode == other.debugMode &&
            allowInsecureTransport == other.allowInsecureTransport &&
            connectTimeoutMillis == other.connectTimeoutMillis &&
            requestTimeoutMillis == other.requestTimeoutMillis &&
            socketTimeoutMillis == other.socketTimeoutMillis &&
            refreshPath == other.refreshPath &&
            loginPath == other.loginPath

    override fun hashCode(): Int {
        var result = apiBaseUrl.hashCode()
        result = 31 * result + enableLogs.hashCode()
        result = 31 * result + debugMode.hashCode()
        result = 31 * result + allowInsecureTransport.hashCode()
        result = 31 * result + connectTimeoutMillis.hashCode()
        result = 31 * result + requestTimeoutMillis.hashCode()
        result = 31 * result + socketTimeoutMillis.hashCode()
        result = 31 * result + refreshPath.hashCode()
        result = 31 * result + loginPath.hashCode()
        return result
    }

    override fun toString(): String =
        "ApiConfig(apiBaseUrl=$apiBaseUrl, enableLogs=$enableLogs, debugMode=$debugMode, allowInsecureTransport=$allowInsecureTransport, connectTimeoutMillis=$connectTimeoutMillis, requestTimeoutMillis=$requestTimeoutMillis, socketTimeoutMillis=$socketTimeoutMillis, refreshPath=$refreshPath, loginPath=$loginPath)"

    companion object {
        fun validateBaseUrl(
            raw: String,
            allowInsecureTransport: Boolean,
        ): String {
            require(raw.isNotBlank()) {
                "API base URL must not be blank. Check BASE_URL in local.properties or NetworkConfig."
            }
            val trimmed = raw.trim()
            val schemeDelimiter = trimmed.indexOf("://")
            require(schemeDelimiter > 0) {
                "API base URL must include a protocol (e.g. https://). Got: $raw"
            }
            val authority =
                trimmed
                    .substring(
                        schemeDelimiter + 3,
                    ).substringBefore('/')
                    .substringBefore('?')
                    .substringBefore('#')
            val hostAndPort = authority.substringAfter('@')
            val rawHost =
                if (hostAndPort.startsWith('[')) {
                    hostAndPort.substringBefore(']').removePrefix("[")
                } else {
                    hostAndPort.substringBefore(':')
                }
            require(rawHost.isNotBlank()) {
                "API base URL host must not be empty. Got: $raw"
            }
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
            require(url.parameters.isEmpty()) {
                "API base URL must not contain query parameters. Got: $raw"
            }
            require(url.fragment.isBlank()) {
                "API base URL must not contain fragment. Got: $raw"
            }
            require(url.user == null && url.password == null) {
                "API base URL must not contain user credentials. Got: $raw"
            }
            return url.toString().trimEnd('/') + "/"
        }
    }
}
