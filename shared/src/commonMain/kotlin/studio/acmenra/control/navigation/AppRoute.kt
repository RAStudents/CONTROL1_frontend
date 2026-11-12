package studio.acmenra.control.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("login")
data object LoginRoute

@Serializable
@SerialName("events")
data object EventsRoute
