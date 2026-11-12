package studio.acmenra.control.core.network.model

sealed interface NetworkError {
    /**
     * HTTP error with status code (e.g. 400, 403, 404, 500)
     */
    data class Http(
        val statusCode: Int,
        val message: String? = null,
        val errorBody: String? = null,
    ) : NetworkError

    /**
     * Connection failure (no internet, DNS lookup failure, connection timeout)
     */
    data class Connectivity(
        val message: String? = null,
        val cause: Throwable? = null,
    ) : NetworkError

    /**
     * Data deserialization / format mismatch error
     */
    data class Serialization(
        val message: String? = null,
        val cause: Throwable? = null,
    ) : NetworkError

    /**
     * Unexpected or unknown exception
     */
    data class Unknown(
        val message: String? = null,
        val cause: Throwable? = null,
    ) : NetworkError
}
