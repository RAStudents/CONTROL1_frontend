package studio.acmenra.control.core.network.auth

fun interface AccessInvalidationListener {
    suspend fun onAccessInvalidated(
        errorCode: String?,
        companyId: String?,
    )
}
