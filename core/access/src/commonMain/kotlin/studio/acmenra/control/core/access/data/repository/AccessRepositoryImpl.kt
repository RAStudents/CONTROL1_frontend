package studio.acmenra.control.core.access.data.repository

import studio.acmenra.control.core.access.data.datasource.AccessFetchResult
import studio.acmenra.control.core.access.data.datasource.AccessLocalDataSource
import studio.acmenra.control.core.access.data.datasource.AccessRemoteDataSource
import studio.acmenra.control.core.access.data.datasource.CachedCompanyAccess
import studio.acmenra.control.core.access.data.mapper.AccessMapper
import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.repository.AccessRepository
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.core.network.model.map
import kotlin.time.TimeSource

class AccessRepositoryImpl(
    private val remoteDataSource: AccessRemoteDataSource,
    private val localDataSource: AccessLocalDataSource,
    private val mapper: AccessMapper,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : AccessRepository {
    override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> =
        remoteDataSource.getGlobalContext().map(mapper::mapGlobalContext)

    override suspend fun getCompanyAccess(
        userId: String,
        companyId: String,
        forceRefresh: Boolean,
    ): NetworkResult<CompanyAccessDomain> {
        val cached = localDataSource.get(userId, companyId)

        val ifNoneMatch =
            when {
                forceRefresh -> null
                cached == null -> null
                // Не отправляем If-None-Match, если в кэше активен SupportGrant (гарантия свежего remaining_ttl_sec)
                cached.domain.supportGrant != null -> null
                else -> cached.etag ?: cached.domain.revision
            }

        return when (val remoteResult = remoteDataSource.getCompanyAccess(companyId, ifNoneMatch)) {
            is NetworkResult.Success -> {
                when (val fetchResult = remoteResult.data) {
                    is AccessFetchResult.Modified -> {
                        val mark = timeSource.markNow()
                        val domain = mapper.mapCompanyAccess(fetchResult.dto, mark)
                        localDataSource.put(
                            userId = userId,
                            companyId = companyId,
                            access =
                                CachedCompanyAccess(
                                    domain = domain,
                                    etag = fetchResult.etag,
                                    cachedAtMark = mark,
                                ),
                        )
                        NetworkResult.Success(domain, remoteResult.statusCode)
                    }

                    is AccessFetchResult.NotModified -> {
                        val currentCached = localDataSource.get(userId, companyId)
                        if (currentCached != null) {
                            NetworkResult.Success(currentCached.domain, remoteResult.statusCode)
                        } else {
                            // 304 без локального кэша: безусловный повтор без If-None-Match
                            retryWithoutIfNoneMatch(userId, companyId)
                        }
                    }
                }
            }

            is NetworkResult.Failure -> remoteResult
        }
    }

    override suspend fun getCachedCompanyAccess(
        userId: String,
        companyId: String,
    ): CompanyAccessDomain? = localDataSource.get(userId, companyId)?.domain

    override suspend fun clearCache() {
        localDataSource.clear()
    }

    private suspend fun retryWithoutIfNoneMatch(
        userId: String,
        companyId: String,
    ): NetworkResult<CompanyAccessDomain> =
        when (val retryResult = remoteDataSource.getCompanyAccess(companyId, ifNoneMatch = null)) {
            is NetworkResult.Success -> {
                when (val retryData = retryResult.data) {
                    is AccessFetchResult.Modified -> {
                        val mark = timeSource.markNow()
                        val domain = mapper.mapCompanyAccess(retryData.dto, mark)
                        localDataSource.put(
                            userId = userId,
                            companyId = companyId,
                            access =
                                CachedCompanyAccess(
                                    domain = domain,
                                    etag = retryData.etag,
                                    cachedAtMark = mark,
                                ),
                        )
                        NetworkResult.Success(domain, retryResult.statusCode)
                    }

                    is AccessFetchResult.NotModified -> {
                        NetworkResult.Failure(
                            NetworkError.Unknown(
                                message = "Received 304 Not Modified without If-None-Match header",
                            ),
                        )
                    }
                }
            }

            is NetworkResult.Failure -> retryResult
        }
}
