package studio.acmenra.control.core.network.client

data class ApiConfig(
    val apiBaseUrl: String = studio.acmenra.control.NetworkConfig.BASE_URL,
    val enableLogs: Boolean = studio.acmenra.control.NetworkConfig.ENABLE_LOGS,
    val debugMode: Boolean = studio.acmenra.control.NetworkConfig.DEBUG_MODE,
    val allowInsecureTransport: Boolean = studio.acmenra.control.NetworkConfig.ALLOW_INSECURE_TRANSPORT,
)
