package studio.acmenra.control.core.access.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.network.auth.AccessInvalidationListener

class AccessInvalidationCoordinator(
    private val accessManager: AccessManager,
    private val tenantCoordinator: TenantContextCoordinator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AccessInvalidationListener {
    internal var onBeforeFinallyHook: (suspend () -> Unit)? = null
    private val targetMutex = Mutex()
    private val inProgressTargets = mutableMapOf<String, Any>()
    private val pendingRetryTargets = mutableMapOf<String, Pair<String?, String?>>()

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
        val ownerToken = Any()
        val acquired =
            targetMutex.withLock {
                if (inProgressTargets.containsKey(targetKey)) {
                    pendingRetryTargets[targetKey] = errorCode to companyId
                    false
                } else {
                    inProgressTargets[targetKey] = ownerToken
                    true
                }
            }

        if (!acquired) {
            return
        }

        try {
            var currentErrorCode = errorCode
            var currentCompanyId = companyId
            while (true) {
                executeInvalidation(currentErrorCode, currentCompanyId)

                val next =
                    targetMutex.withLock {
                        val pending = pendingRetryTargets.remove(targetKey)
                        if (pending == null) {
                            if (inProgressTargets[targetKey] === ownerToken) {
                                inProgressTargets.remove(targetKey)
                            }
                        }
                        pending
                    }

                if (next != null) {
                    currentErrorCode = next.first
                    currentCompanyId = next.second
                } else {
                    break
                }
            }
            onBeforeFinallyHook?.invoke()
        } finally {
            withContext(NonCancellable) {
                val pendingToRecover =
                    targetMutex.withLock {
                        if (inProgressTargets[targetKey] === ownerToken) {
                            inProgressTargets.remove(targetKey)
                            pendingRetryTargets.remove(targetKey)
                        } else {
                            null
                        }
                    }
                if (pendingToRecover != null) {
                    scope.launch {
                        processInvalidation(pendingToRecover.first, pendingToRecover.second)
                    }
                }
            }
        }
    }

    private suspend fun executeInvalidation(
        errorCode: String?,
        companyId: String?,
    ) {
        when (errorCode) {
            "ACCOUNT_FROZEN" -> {
                accessManager.refreshGlobalContext(force = true)
            }

            else -> {
                tenantCoordinator.refreshTenant(targetCompanyId = companyId, force = true)
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
