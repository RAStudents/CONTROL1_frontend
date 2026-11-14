package studio.acmenra.control.core.access.domain

import studio.acmenra.control.core.network.auth.AccessInvalidationListener

class AccessInvalidationCoordinator(
    private val accessManager: AccessManager,
    private val tenantCoordinator: TenantContextCoordinator,
) : AccessInvalidationListener {
    override suspend fun onAccessInvalidated(
        errorCode: String?,
        companyId: String?,
    ) {
        when (errorCode) {
            "ACCOUNT_FROZEN" -> {
                accessManager.refreshGlobalContext(force = true)
            }

            "COMPANY_ACCESS_DENIED",
            "COMPANY_RESTRICTED",
            "PERMISSION_DENIED",
            "SUPPORT_GRANT_EXPIRED",
            "SUPPORT_GRANT_REVOKED",
            -> {
                tenantCoordinator.refreshCurrentTenant(force = true)
            }

            else -> {
                tenantCoordinator.refreshCurrentTenant(force = true)
            }
        }
    }
}
