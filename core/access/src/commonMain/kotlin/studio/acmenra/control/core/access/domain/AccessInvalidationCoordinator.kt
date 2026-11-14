package studio.acmenra.control.core.access.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.acmenra.control.core.network.auth.AccessInvalidationListener

class AccessInvalidationCoordinator(
    private val accessManager: AccessManager,
    private val tenantCoordinator: TenantContextCoordinator,
) : AccessInvalidationListener {
    private val targetMutex = Mutex()
    private val inProgressTargets = mutableSetOf<String>()

    override suspend fun onAccessInvalidated(
        errorCode: String?,
        companyId: String?,
    ) {
        val targetKey = targetKey(errorCode, companyId)
        val acquired =
            targetMutex.withLock {
                if (targetKey in inProgressTargets) {
                    false
                } else {
                    inProgressTargets.add(targetKey)
                    true
                }
            }

        if (!acquired) {
            return
        }

        try {
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
                    tenantCoordinator.refreshTenant(targetCompanyId = companyId, force = true)
                }

                else -> {
                    tenantCoordinator.refreshTenant(targetCompanyId = companyId, force = true)
                }
            }
        } finally {
            targetMutex.withLock {
                inProgressTargets.remove(targetKey)
            }
        }
    }

    private fun targetKey(
        errorCode: String?,
        companyId: String?,
    ): String = if (errorCode == "ACCOUNT_FROZEN") "global" else "company:${companyId ?: "current"}"
}
