package studio.acmenra.control.core.access.data.datasource

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AccessLocalDataSource {
    suspend fun get(
        userId: String,
        companyId: String,
    ): CachedCompanyAccess?

    suspend fun put(
        userId: String,
        companyId: String,
        access: CachedCompanyAccess,
    )

    suspend fun remove(
        userId: String,
        companyId: String,
    )

    suspend fun clear()
}

class InMemoryAccessLocalDataSource : AccessLocalDataSource {
    private val mutex = Mutex()
    private val cache = mutableMapOf<TenantCacheKey, CachedCompanyAccess>()

    override suspend fun get(
        userId: String,
        companyId: String,
    ): CachedCompanyAccess? = mutex.withLock { cache[TenantCacheKey(userId, companyId)] }

    override suspend fun put(
        userId: String,
        companyId: String,
        access: CachedCompanyAccess,
    ) {
        mutex.withLock { cache[TenantCacheKey(userId, companyId)] = access }
    }

    override suspend fun remove(
        userId: String,
        companyId: String,
    ) {
        mutex.withLock { cache.remove(TenantCacheKey(userId, companyId)) }
    }

    override suspend fun clear() {
        mutex.withLock { cache.clear() }
    }
}
