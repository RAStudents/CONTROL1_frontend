package studio.acmenra.control.core.access.domain.concurrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.network.model.NetworkResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TenantSingleFlightTest {
    private val singleFlight = TenantSingleFlight()

    private fun dummyDomain(companyId: String) =
        CompanyAccessDomain(
            companyId = companyId,
            companyName = "Company $companyId",
            serverTime = Instant.fromEpochMilliseconds(0),
            revision = "r1",
            companyStatus = CompanyStatus.ACTIVE,
            accessStatus = studio.acmenra.control.core.access.domain.model.AccessStatus.ACTIVE,
            activeOrigins = emptySet(),
            restriction = null,
            tenantPermissions = emptySet(),
            supportPermissions = emptySet(),
            permissionSources = emptyMap(),
            supportGrant = null,
        )

    @Test
    fun concurrentRequestsDeduplicateToSingleExecution() =
        runTest {
            val key = TenantRequestKey(sessionRevision = 1L, userId = "u1", companyId = "c1")
            var executionCount = 0
            val leaderStarted = CompletableDeferred<Unit>()
            val barrier = CompletableDeferred<Unit>()

            val jobs =
                List(10) {
                    async {
                        singleFlight.execute(key) {
                            leaderStarted.complete(Unit)
                            barrier.await()
                            executionCount++
                            NetworkResult.Success(dummyDomain("c1"))
                        }
                    }
                }

            leaderStarted.await()
            runCurrent()
            barrier.complete(Unit)
            val results = jobs.awaitAll()

            assertEquals(1, executionCount)
            assertEquals(10, results.size)
            assertTrue(results.all { it is NetworkResult.Success && it.data.companyId == "c1" })
            assertEquals(0, singleFlight.activeCount())
        }

    @Test
    fun differentKeysExecuteSeparately() =
        runTest {
            val key1 = TenantRequestKey(sessionRevision = 1L, userId = "u1", companyId = "c1")
            val key2 = TenantRequestKey(sessionRevision = 1L, userId = "u1", companyId = "c2")
            var count = 0

            val job1 =
                async {
                    singleFlight.execute(key1) {
                        count++
                        NetworkResult.Success(dummyDomain("c1"))
                    }
                }

            val job2 =
                async {
                    singleFlight.execute(key2) {
                        count++
                        NetworkResult.Success(dummyDomain("c2"))
                    }
                }

            val res1 = job1.await()
            val res2 = job2.await()

            assertEquals(2, count)
            assertEquals("c1", (res1 as NetworkResult.Success).data.companyId)
            assertEquals("c2", (res2 as NetworkResult.Success).data.companyId)
        }

    @Test
    fun failureInLeaderPropagatesToAllWaiters() =
        runTest {
            val key = TenantRequestKey(sessionRevision = 1L, userId = "u1", companyId = "c1")
            val leaderStarted = CompletableDeferred<Unit>()
            val barrier = CompletableDeferred<Unit>()

            supervisorScope {
                val jobs =
                    List(5) {
                        async {
                            singleFlight.execute(key) {
                                leaderStarted.complete(Unit)
                                barrier.await()
                                throw IllegalStateException("Leader failed")
                            }
                        }
                    }

                leaderStarted.await()
                runCurrent()
                barrier.complete(Unit)

                jobs.forEach { deferred ->
                    assertFailsWith<IllegalStateException> {
                        deferred.await()
                    }
                }
            }
        }

    @Test
    fun cancellationOfCallerDoesNotAbortExecutionForWaitersAndCleansUp() =
        runTest {
            val key = TenantRequestKey(sessionRevision = 1L, userId = "u1", companyId = "c1")
            val leaderEntered = CompletableDeferred<Unit>()
            val barrier = CompletableDeferred<Unit>()

            val leaderScope = CoroutineScope(Dispatchers.Unconfined)
            val leaderJob =
                leaderScope.launch {
                    singleFlight.execute(key) {
                        leaderEntered.complete(Unit)
                        barrier.await()
                        NetworkResult.Success(dummyDomain("c1"))
                    }
                }

            leaderEntered.await()

            val waiter =
                async {
                    singleFlight.execute(key) {
                        NetworkResult.Success(dummyDomain("c1"))
                    }
                }

            leaderJob.cancel()

            barrier.complete(Unit)

            val waiterResult = waiter.await()
            assertTrue(waiterResult is NetworkResult.Success && waiterResult.data.companyId == "c1")
            assertEquals(0, singleFlight.activeCount())
        }

    @Test
    fun forceRefreshTrueDoesNotCoalesceWithForceRefreshFalse() =
        runTest {
            val keyRegular =
                TenantRequestKey(
                    sessionRevision = 1L,
                    userId = "u1",
                    companyId = "c1",
                    forceRefresh = false,
                )
            val keyForce =
                TenantRequestKey(
                    sessionRevision = 1L,
                    userId = "u1",
                    companyId = "c1",
                    forceRefresh = true,
                )
            var count = 0
            val barrier = CompletableDeferred<Unit>()

            val jobRegular =
                async {
                    singleFlight.execute(keyRegular) {
                        barrier.await()
                        count++
                        NetworkResult.Success(dummyDomain("c1_cached"))
                    }
                }

            val jobForce =
                async {
                    singleFlight.execute(keyForce) {
                        barrier.await()
                        count++
                        NetworkResult.Success(dummyDomain("c1_fresh"))
                    }
                }

            barrier.complete(Unit)
            val resRegular = jobRegular.await()
            val resForce = jobForce.await()

            assertEquals(2, count)
            assertEquals("c1_cached", (resRegular as NetworkResult.Success).data.companyId)
            assertEquals("c1_fresh", (resForce as NetworkResult.Success).data.companyId)
        }

    @Test
    fun executionWithUnconfinedDispatcherCleansUpWithoutLateinitError() =
        runTest {
            val unconfinedFlight = TenantSingleFlight(CoroutineScope(UnconfinedTestDispatcher()))
            val key = TenantRequestKey(sessionRevision = 1L, userId = "u1", companyId = "c1")

            val result =
                unconfinedFlight.execute(key) {
                    NetworkResult.Success(dummyDomain("c1"))
                }

            assertTrue(result is NetworkResult.Success)
            assertEquals(0, unconfinedFlight.activeCount())
        }

    @Test
    fun failureWithExceptionCleansUpInFlight() =
        runTest {
            val key = TenantRequestKey(sessionRevision = 1L, userId = "u1", companyId = "c1")
            assertFailsWith<IllegalStateException> {
                singleFlight.execute(key) {
                    throw IllegalStateException("Failure in execution")
                }
            }
            assertEquals(0, singleFlight.activeCount())
        }

    @Test
    fun repeatedSequentialRequestsCleanUpAndExecuteEachTime() =
        runTest {
            val key = TenantRequestKey(sessionRevision = 1L, userId = "u1", companyId = "c1")
            var count = 0

            val res1 =
                singleFlight.execute(key) {
                    count++
                    NetworkResult.Success(dummyDomain("c1"))
                }
            assertTrue(res1 is NetworkResult.Success)
            assertEquals(1, count)
            assertEquals(0, singleFlight.activeCount())

            val res2 =
                singleFlight.execute(key) {
                    count++
                    NetworkResult.Success(dummyDomain("c1"))
                }
            assertTrue(res2 is NetworkResult.Success)
            assertEquals(2, count)
            assertEquals(0, singleFlight.activeCount())
        }

    @Test
    fun concurrentRequestsWithDifferentKeysCleanUpIndependently() =
        runTest {
            val key1 = TenantRequestKey(sessionRevision = 1L, userId = "u1", companyId = "c1")
            val key2 = TenantRequestKey(sessionRevision = 1L, userId = "u1", companyId = "c2")

            val barrier = CompletableDeferred<Unit>()
            val job1 =
                async {
                    singleFlight.execute(key1) {
                        barrier.await()
                        NetworkResult.Success(dummyDomain("c1"))
                    }
                }
            val job2 =
                async {
                    singleFlight.execute(key2) {
                        barrier.await()
                        NetworkResult.Success(dummyDomain("c2"))
                    }
                }

            barrier.complete(Unit)
            job1.await()
            job2.await()

            assertEquals(0, singleFlight.activeCount())
        }

    @Test
    fun cancellationOfSingleCallerWithoutWaitersCleansUpImmediately() =
        runTest {
            val key = TenantRequestKey(sessionRevision = 1L, userId = "u1", companyId = "c1")
            val entered = CompletableDeferred<Unit>()
            val barrier = CompletableDeferred<Unit>()

            val job =
                launch {
                    singleFlight.execute(key) {
                        entered.complete(Unit)
                        barrier.await()
                        NetworkResult.Success(dummyDomain("c1"))
                    }
                }

            entered.await()
            assertEquals(1, singleFlight.activeCount())

            job.cancel()
            barrier.complete(Unit)
            job.join()

            assertEquals(0, singleFlight.activeCount())
            val nextResult =
                singleFlight.execute(key) {
                    NetworkResult.Success(dummyDomain("c1_subsequent"))
                }
            assertTrue(nextResult is NetworkResult.Success && nextResult.data.companyId == "c1_subsequent")
            assertEquals(0, singleFlight.activeCount())
        }
}
