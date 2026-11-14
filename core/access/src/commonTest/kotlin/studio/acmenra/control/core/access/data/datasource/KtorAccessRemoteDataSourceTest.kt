package studio.acmenra.control.core.access.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KtorAccessRemoteDataSourceTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private fun createTestClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            defaultRequest {
                headers.append(HttpHeaders.Accept, ContentType.Application.Json.toString())
            }
            install(ContentNegotiation) {
                json(json)
            }
        }

    @Test
    fun getGlobalContextParsesJsonSuccessfully() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals("/api/v1/auth/context", request.url.encodedPath)
                    respond(
                        content =
                            """
                            {
                                "user_id": "user_123",
                                "server_time": "2026-11-14T12:00:00Z",
                                "global_permissions": ["COMPANIES_MONITOR"],
                                "default_company_id": "c1",
                                "can_browse_all_companies": false,
                                "member_companies": [
                                    {"id": "c1", "name": "Company One", "status": "ACTIVE"}
                                ]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

            val dataSource = KtorAccessRemoteDataSource(createTestClient(engine))
            val result = dataSource.getGlobalContext()

            assertTrue(result is NetworkResult.Success)
            assertEquals("user_123", result.data.userId)
            assertEquals(1, result.data.memberCompanies.size)
        }

    @Test
    fun getCompanyAccessReturnsModifiedWithEtag() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals("/api/v1/companies/c100/access", request.url.encodedPath)
                    assertEquals("W/\"prev_etag\"", request.headers[HttpHeaders.IfNoneMatch])
                    respond(
                        content =
                            """
                            {
                                "company_id": "c100",
                                "company_name": "Test Co",
                                "server_time": "2026-11-14T12:00:00Z",
                                "revision": "rev_200",
                                "company_status": "ACTIVE",
                                "access_status": "ACTIVE",
                                "active_origins": ["DIRECT_MEMBERSHIP"],
                                "tenant_permissions": ["EVENTS_READ"],
                                "support_permissions": [],
                                "permission_sources": {"EVENTS_READ": ["DIRECT_MEMBERSHIP"]}
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                                HttpHeaders.ETag to listOf("W/\"new_etag\""),
                            ),
                    )
                }

            val dataSource = KtorAccessRemoteDataSource(createTestClient(engine))
            val result = dataSource.getCompanyAccess("c100", ifNoneMatch = "W/\"prev_etag\"")

            assertTrue(result is NetworkResult.Success)
            val data = result.data
            assertTrue(data is AccessFetchResult.Modified)
            assertEquals("c100", data.dto.companyId)
            assertEquals("W/\"new_etag\"", data.etag)
        }

    @Test
    fun getCompanyAccessReturnsNotModifiedOn304() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals("/api/v1/companies/c100/access", request.url.encodedPath)
                    assertEquals("W/\"etag_current\"", request.headers[HttpHeaders.IfNoneMatch])
                    respond(
                        content = "",
                        status = HttpStatusCode.NotModified,
                    )
                }

            val dataSource = KtorAccessRemoteDataSource(createTestClient(engine))
            val result = dataSource.getCompanyAccess("c100", ifNoneMatch = "W/\"etag_current\"")

            assertTrue(result is NetworkResult.Success)
            assertEquals(304, result.statusCode)
            assertTrue(result.data is AccessFetchResult.NotModified)
        }

    @Test
    fun getCompanyAccessReturnsHttpFailureOnError() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = """{"error":"COMPANY_RESTRICTED","message":"Company is restricted"}""",
                        status = HttpStatusCode.Forbidden,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

            val dataSource = KtorAccessRemoteDataSource(createTestClient(engine))
            val result = dataSource.getCompanyAccess("c100", ifNoneMatch = null)

            assertTrue(result is NetworkResult.Failure)
            val error = result.error
            assertTrue(error is NetworkError.Http)
            assertEquals(403, error.statusCode)
            assertTrue(error.errorBody?.contains("COMPANY_RESTRICTED") == true)
        }
}
