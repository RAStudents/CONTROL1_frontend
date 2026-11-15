package studio.acmenra.control.navigation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AppRouteTest {
    @Test
    fun loginRouteHasExpectedSerialName() {
        assertEquals("login", LoginRoute.serializer().descriptor.serialName)
    }

    @Test
    fun eventsRouteHasExpectedSerialName() {
        assertEquals("events", EventsRoute.serializer().descriptor.serialName)
    }

    @Test
    fun companiesDashboardRouteHasExpectedSerialName() {
        assertEquals("companies_dashboard", CompaniesDashboardRoute.serializer().descriptor.serialName)
    }

    @Test
    fun testLoginRouteSerializationRoundTrip() {
        val json = Json.encodeToString(LoginRoute)
        assertEquals("{}", json)
        val decoded = Json.decodeFromString<LoginRoute>(json)
        assertEquals(LoginRoute, decoded)
    }

    @Test
    fun testEventsRouteSerializationRoundTripDefault() {
        val json = Json.encodeToString(EventsRoute())
        assertEquals("{}", json)
        val decoded = Json.decodeFromString<EventsRoute>(json)
        assertEquals(EventsRoute(), decoded)
        assertEquals("", decoded.companyId)
    }

    @Test
    fun testEventsRouteSerializationRoundTripWithCompanyId() {
        val json = Json.encodeToString(EventsRoute(companyId = "company-123"))
        assertEquals("{\"companyId\":\"company-123\"}", json)
        val decoded = Json.decodeFromString<EventsRoute>(json)
        assertEquals("company-123", decoded.companyId)
    }

    @Test
    fun testCompaniesDashboardRouteSerializationRoundTrip() {
        val json = Json.encodeToString(CompaniesDashboardRoute)
        assertEquals("{}", json)
        val decoded = Json.decodeFromString<CompaniesDashboardRoute>(json)
        assertEquals(CompaniesDashboardRoute, decoded)
    }
}
