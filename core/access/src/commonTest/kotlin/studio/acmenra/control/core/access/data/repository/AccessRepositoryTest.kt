package studio.acmenra.control.core.access.data.repository

import kotlinx.coroutines.test.runTest
import studio.acmenra.control.core.access.data.datasource.AccessFetchResult
import studio.acmenra.control.core.access.data.datasource.AccessLocalDataSource
import studio.acmenra.control.core.access.data.datasource.AccessRemoteDataSource
import studio.acmenra.control.core.access.data.datasource.CachedCompanyAccess
import studio.acmenra.control.core.access.data.datasource.InMemoryAccessLocalDataSource
import studio.acmenra.control.core.access.data.mapper.AccessMapper
import studio.acmenra.control.core.access.data.model.CompanyAccessResponseDto
import studio.acmenra.control.core.access.data.model.GlobalContextResponseDto
import studio.acmenra.control.core.access.data.model.SupportGrantDto
import studio.acmenra.control.core.access.domain.model.SupportGrantStatus
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class AccessRepositoryTest {
    private class FakeRemoteDataSource : AccessRemoteDataSource {
        var globalResult: NetworkResult<GlobalContextResponseDto> =
            NetworkResult.Success(
                GlobalContextResponseDto(
                    userId = "u1",
                    serverTime = "2026-11-14T12:00:00Z",
                ),
            )

        var companyAccessHandler:
            suspend (
                companyId: String,
                ifNoneMatch: String?,
            ) -> NetworkResult<AccessFetchResult> =
            { _, _ ->
                NetworkResult.Success(
                    AccessFetchResult.Modified(
                        CompanyAccessResponseDto(
                            companyId = "c1",
                            companyName = "Test Co",
                            serverTime = "2026-11-14T12:00:00Z",
                            revision = "rev_1",
                        ),
                        etag = "W/\"rev_1\"",
                    ),
                )
            }

        val requestedIfNoneMatch = mutableListOf<String?>()

        override suspend fun getGlobalContext(): NetworkResult<GlobalContextResponseDto> = globalResult

        override suspend fun getCompanyAccess(
            companyId: String,
            ifNoneMatch: String?,
        ): NetworkResult<AccessFetchResult> {
            requestedIfNoneMatch.add(ifNoneMatch)
            return companyAccessHandler(companyId, ifNoneMatch)
        }
    }

    private val remote = FakeRemoteDataSource()
    private val local: AccessLocalDataSource = InMemoryAccessLocalDataSource()
    private val mapper = AccessMapper()
    private val repository = AccessRepositoryImpl(remote, local, mapper)

    @BeforeTest
    fun setUp() =
        runTest {
            remote.requestedIfNoneMatch.clear()
            local.clear()
        }

    @Test
    fun getGlobalContextMapsToDomain() =
        runTest {
            val result = repository.getGlobalContext()
            assertTrue(result is NetworkResult.Success)
            assertEquals("u1", result.data.userId)
        }

    @Test
    fun standardFetchSavesToCacheAndReturnsDomain() =
        runTest {
            val result = repository.getCompanyAccess(userId = "u1", companyId = "c1")
            assertTrue(result is NetworkResult.Success)
            assertEquals("c1", result.data.companyId)
            assertEquals("Test Co", result.data.companyName)

            val cached = repository.getCachedCompanyAccess("u1", "c1")
            assertNotNull(cached)
            assertEquals("W/\"rev_1\"", cached.etag)
            assertEquals("c1", cached.domain.companyId)
            assertEquals(listOf<String?>(null), remote.requestedIfNoneMatch)
        }

    @Test
    fun cacheHitWith304ReturnsCachedDomain() =
        runTest {
            // First fetch populates cache
            repository.getCompanyAccess("u1", "c1")

            // Second fetch: remote returns NotModified
            remote.companyAccessHandler =
                { _, _ -> NetworkResult.Success(AccessFetchResult.NotModified, statusCode = 304) }

            val result = repository.getCompanyAccess("u1", "c1")
            assertTrue(result is NetworkResult.Success)
            assertEquals(304, result.statusCode)
            assertEquals("c1", result.data.companyId)
            assertEquals("W/\"rev_1\"", remote.requestedIfNoneMatch.last())
        }

    @Test
    fun cacheMissOn304RetriesWithoutIfNoneMatch() =
        runTest {
            local.put(
                "u1",
                "c1",
                CachedCompanyAccess(
                    domain =
                        mapper.mapCompanyAccess(
                            CompanyAccessResponseDto(
                                companyId = "c1",
                                companyName = "Old",
                                serverTime = "2026-11-14T12:00:00Z",
                                revision = "rev_old",
                            ),
                        ),
                    etag = "W/\"rev_old\"",
                    cachedAtMark = TimeSource.Monotonic.markNow(),
                ),
            )

            // Override remote handler to clear local cache on first invocation!
            remote.companyAccessHandler = { _, ifNoneMatch ->
                if (ifNoneMatch != null) {
                    local.clear()
                    NetworkResult.Success(AccessFetchResult.NotModified, 304)
                } else {
                    NetworkResult.Success(
                        AccessFetchResult.Modified(
                            CompanyAccessResponseDto(
                                companyId = "c1",
                                companyName = "Refetched Body",
                                serverTime = "2026-11-14T12:00:00Z",
                                revision = "rev_refetched",
                            ),
                            etag = "W/\"rev_refetched\"",
                        ),
                    )
                }
            }

            val result = repository.getCompanyAccess("u1", "c1")
            assertTrue(result is NetworkResult.Success)
            assertEquals("Refetched Body", result.data.companyName)
            assertEquals(listOf("W/\"rev_old\"", null), remote.requestedIfNoneMatch)
        }

    @Test
    fun activeSupportGrantInCacheOmitsIfNoneMatch() =
        runTest {
            // Populate cache with an active support grant
            val grantDto =
                SupportGrantDto(
                    grantId = "g1",
                    status = SupportGrantStatus.ACTIVE,
                    remainingTtlSec = 300,
                )
            val domainWithGrant =
                mapper.mapCompanyAccess(
                    CompanyAccessResponseDto(
                        companyId = "c1",
                        companyName = "Active Grant Co",
                        serverTime = "2026-11-14T12:00:00Z",
                        revision = "rev_grant",
                        supportGrant = grantDto,
                    ),
                )
            local.put(
                "u1",
                "c1",
                CachedCompanyAccess(
                    domain = domainWithGrant,
                    etag = "W/\"rev_grant\"",
                    cachedAtMark = TimeSource.Monotonic.markNow(),
                ),
            )

            // Fetch again without forceRefresh
            repository.getCompanyAccess("u1", "c1", forceRefresh = false)

            // Should have sent null as If-None-Match to guarantee fresh TTL
            assertEquals(listOf<String?>(null), remote.requestedIfNoneMatch)
        }

    @Test
    fun forceRefreshOmitsIfNoneMatchEvenWithoutGrant() =
        runTest {
            // Cache entry without grant
            repository.getCompanyAccess("u1", "c1")

            // Fetch with forceRefresh = true
            repository.getCompanyAccess("u1", "c1", forceRefresh = true)

            assertEquals(listOf<String?>(null, null), remote.requestedIfNoneMatch)
        }

    @Test
    fun clearCacheRemovesAllEntries() =
        runTest {
            repository.getCompanyAccess("u1", "c1")
            assertNotNull(repository.getCachedCompanyAccess("u1", "c1"))

            repository.clearCache()
            assertNull(repository.getCachedCompanyAccess("u1", "c1"))
        }

    @Test
    fun remoteFailureIsPropagated() =
        runTest {
            remote.companyAccessHandler = { _, _ ->
                NetworkResult.Failure(NetworkError.Http(statusCode = 403, message = "Forbidden"))
            }
            val result = repository.getCompanyAccess("u1", "c1")
            assertTrue(result is NetworkResult.Failure)
            assertEquals(403, (result.error as NetworkError.Http).statusCode)
        }
}
