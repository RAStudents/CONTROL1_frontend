package studio.acmenra.control.core.access.domain.concurrent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
import studio.acmenra.control.core.network.model.NetworkResult

data class TenantRequestKey(
    val sessionRevision: Long,
    val userId: String,
    val companyId: String,
    val forceRefresh: Boolean = false,
)

class TenantSingleFlight(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<TenantRequestKey, Deferred<NetworkResult<CompanyAccessDomain>>>()

    suspend fun execute(
        key: TenantRequestKey,
        block: suspend () -> NetworkResult<CompanyAccessDomain>,
    ): NetworkResult<CompanyAccessDomain> {
        val deferred =
            mutex.withLock {
                val existing = inFlight[key]
                if (existing != null && existing.isActive) {
                    existing
                } else {
                    lateinit var newDeferred: Deferred<NetworkResult<CompanyAccessDomain>>
                    newDeferred =
                        scope.async {
                            try {
                                block()
                            } finally {
                                withContext(NonCancellable) {
                                    mutex.withLock {
                                        if (inFlight[key] === newDeferred) {
                                            inFlight.remove(key)
                                        }
                                    }
                                }
                            }
                        }
                    inFlight[key] = newDeferred
                    newDeferred
                }
            }

        return deferred.await()
    }

    suspend fun activeCount(): Int =
        mutex.withLock {
            inFlight.size
        }
}
