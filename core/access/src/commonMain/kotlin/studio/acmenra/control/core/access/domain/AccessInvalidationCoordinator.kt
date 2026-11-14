package studio.acmenra.control.core.access.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.network.auth.AccessInvalidationListener

class AccessInvalidationCoordinator(
    private val accessManager: AccessManager,
    private val tenantCoordinator: TenantContextCoordinator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AccessInvalidationListener {
    private val targetMutex = Mutex()
    private val inProgressTargets = mutableSetOf<String>()

    override suspend fun onAccessInvalidated(
        errorCode: String?,
        companyId: String?,
    ) {
        onAccessInvalidatedAsync(errorCode, companyId)
    }

    fun onAccessInvalidatedAsync(
        errorCode: String?,
        companyId: String?,
    ): Job =
        scope.launch {
            processInvalidation(errorCode, companyId)
        }

    suspend fun processInvalidation(
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
    ): String {
        if (errorCode == "ACCOUNT_FROZEN") return "global"
        val activeCompanyId =
            when (val s = tenantCoordinator.tenantState.value) {
                is TenantContextState.Authorized -> s.context.companyId
                is TenantContextState.Restricted -> s.companyId
                is TenantContextState.Error -> s.companyId
                is TenantContextState.Loading -> s.requestedCompanyId
                is TenantContextState.NotSelected -> null
            }
        val resolvedCompany = companyId ?: activeCompanyId ?: "current"
        return "company:$resolvedCompany"
    }
}
