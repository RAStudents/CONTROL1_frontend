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

private class FlightEntry(
    val token: Any,
    val deferred: Deferred<NetworkResult<CompanyAccessDomain>>,
)

class TenantSingleFlight(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<TenantRequestKey, FlightEntry>()

    suspend fun execute(
        key: TenantRequestKey,
        block: suspend () -> NetworkResult<CompanyAccessDomain>,
    ): NetworkResult<CompanyAccessDomain> {
        val deferred =
            mutex.withLock {
                val existing = inFlight[key]
                if (existing != null && existing.deferred.isActive) {
                    existing.deferred
                } else {
                    val token = Any()
                    val newDeferred =
                        scope.async {
                            try {
                                block()
                            } finally {
                                withContext(NonCancellable) {
                                    mutex.withLock {
                                        if (inFlight[key]?.token === token) {
                                            inFlight.remove(key)
                                        }
                                    }
                                }
                            }
                        }
                    inFlight[key] = FlightEntry(token, newDeferred)
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
