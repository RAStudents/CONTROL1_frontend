package studio.acmenra.control.core.access.data.parser

import studio.acmenra.control.core.access.domain.model.AccessDomainError
import studio.acmenra.control.core.access.domain.model.RestrictionType
import studio.acmenra.control.core.network.model.NetworkError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonAccessErrorParserTest {
    private val parser = JsonAccessErrorParser()

    @Test
    fun parsesCompanyAccessDenied() {
        val error =
            NetworkError.Http(
                statusCode = 403,
                errorBody = """{"error":"COMPANY_ACCESS_DENIED","company_id":"c123"}""",
            )
        val result = parser.parse(error)
        assertTrue(result is AccessDomainError.AccessDenied)
        assertEquals("c123", result.companyId)
    }

    @Test
    fun parsesLegacyAccessDenied() {
        val error =
            NetworkError.Http(
                statusCode = 403,
                errorBody = """{"error":"ACCESS_DENIED","company_id":"c456"}""",
            )
        val result = parser.parse(error)
        assertTrue(result is AccessDomainError.AccessDenied)
        assertEquals("c456", result.companyId)
    }

    @Test
    fun parsesAccountFrozen() {
        val json =
            """{"error":"ACCOUNT_FROZEN","restriction":{"type":"BILLING_DEBT","reason":"Unpaid"}}"""
        val error = NetworkError.Http(statusCode = 403, errorBody = json)
        val result = parser.parse(error)
        assertTrue(result is AccessDomainError.AccountFrozen)
        assertEquals(RestrictionType.BILLING_DEBT, result.restriction?.type)
        assertEquals("Unpaid", result.restriction?.reason)
    }

    @Test
    fun parsesCompanyRestricted() {
        val json =
            """{"error":"COMPANY_RESTRICTED","company_id":"c789","restriction":{"type":"MAINTENANCE"}}"""
        val error = NetworkError.Http(statusCode = 403, errorBody = json)
        val result = parser.parse(error)
        assertTrue(result is AccessDomainError.CompanyRestricted)
        assertEquals("c789", result.companyId)
        assertEquals(RestrictionType.MAINTENANCE, result.restriction?.type)
    }

    @Test
    fun non403ReturnsUnknown() {
        val error = NetworkError.Http(statusCode = 404, errorBody = """{"error":"NOT_FOUND"}""")
        val result = parser.parse(error)
        assertTrue(result is AccessDomainError.Unknown)
    }

    @Test
    fun malformedJsonReturnsUnknown() {
        val error = NetworkError.Http(statusCode = 403, errorBody = "not a json")
        val result = parser.parse(error)
        assertTrue(result is AccessDomainError.Unknown)
    }

    @Test
    fun unknownErrorCodeReturnsUnknown() {
        val error = NetworkError.Http(statusCode = 403, errorBody = """{"error":"SOMETHING_ELSE"}""")
        val result = parser.parse(error)
        assertTrue(result is AccessDomainError.Unknown)
    }
}
