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
    fun testLoginRouteSerializationRoundTrip() {
        val json = Json.encodeToString(LoginRoute)
        assertEquals("{}", json)
        val decoded = Json.decodeFromString<LoginRoute>(json)
        assertEquals(LoginRoute, decoded)
    }

    @Test
    fun testEventsRouteSerializationRoundTrip() {
        val json = Json.encodeToString(EventsRoute)
        assertEquals("{}", json)
        val decoded = Json.decodeFromString<EventsRoute>(json)
        assertEquals(EventsRoute, decoded)
    }
}
