package studio.acmenra.control.navigation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AppRouteTest {
    @Test
    fun testLoginRouteSerialization() {
        val json = Json.encodeToString(LoginRoute)
        assertEquals("{}", json)
    }

    @Test
    fun testEventsRouteSerialization() {
        val json = Json.encodeToString(EventsRoute)
        assertEquals("{}", json)
    }
}
