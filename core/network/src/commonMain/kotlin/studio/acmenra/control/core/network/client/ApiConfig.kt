package studio.acmenra.control.core.network.client

data class ApiConfig(
    val apiBaseUrl: String = defaultBaseUrl(),
    val enableLogs: Boolean = studio.acmenra.control.NetworkConfig.ENABLE_LOGS,
    val debugMode: Boolean = studio.acmenra.control.NetworkConfig.DEBUG_MODE,
    val allowInsecureTransport: Boolean = studio.acmenra.control.NetworkConfig.ALLOW_INSECURE_TRANSPORT,
) {
    companion object {
        private fun defaultBaseUrl(): String {
            val configured =
                studio.acmenra.control.NetworkConfig.BASE_URL
                    .trim()
            val isValid =
                configured.isNotEmpty() &&
                    (
                        configured.startsWith("https://", ignoreCase = true) ||
                            configured.startsWith("http://", ignoreCase = true)
                    )
            return if (isValid) configured else "https://api.example.com"
        }
    }
}
