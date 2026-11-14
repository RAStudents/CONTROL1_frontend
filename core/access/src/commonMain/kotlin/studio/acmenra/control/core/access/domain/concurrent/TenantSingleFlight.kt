package studio.acmenra.control.core.access.domain.concurrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
import studio.acmenra.control.core.network.model.NetworkResult

data class TenantRequestKey(
    val sessionRevision: Long,
    val userId: String,
    val companyId: String,
)

class TenantSingleFlight {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<TenantRequestKey, Deferred<NetworkResult<CompanyAccessDomain>>>()

    suspend fun execute(
        key: TenantRequestKey,
        block: suspend () -> NetworkResult<CompanyAccessDomain>,
    ): NetworkResult<CompanyAccessDomain> {
        val (deferred, isLeader) =
            mutex.withLock {
                val active = inFlight[key]
                if (active != null) {
                    active to false
                } else {
                    val newDeferred = CompletableDeferred<NetworkResult<CompanyAccessDomain>>()
                    inFlight[key] = newDeferred
                    newDeferred to true
                }
            }

        if (!isLeader) {
            return deferred.await()
        }

        val result =
            try {
                block()
            } catch (e: Throwable) {
                (deferred as CompletableDeferred).completeExceptionally(e)
                mutex.withLock { inFlight.remove(key) }
                throw e
            }

        (deferred as CompletableDeferred).complete(result)
        mutex.withLock { inFlight.remove(key) }
        return result
    }
}
