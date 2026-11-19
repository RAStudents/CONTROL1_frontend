package studio.acmenra.control.feature.events.domain.model

data class EventTypeOption(
    val id: String,
    val name: String,
    val iconKey: EventIconKey,
)

data class DeviceFilterOption(
    val id: String,
    val name: String,
)

data class LocationFilterGroup(
    val id: String,
    val name: String,
    val devices: List<DeviceFilterOption>,
)

data class EventsFilterOptions(
    val revision: String = "",
    val eventTypes: List<EventTypeOption> = emptyList(),
    val locations: List<LocationFilterGroup> = emptyList(),
)

enum class LocationToggleState {
    OFF,
    ON,
    INDETERMINATE,
}

fun locationToggleState(
    location: LocationFilterGroup,
    selectedDeviceIds: Set<String>,
): LocationToggleState {
    val childIds = location.devices.map { it.id }.toSet()
    if (childIds.isEmpty()) return LocationToggleState.OFF
    val selectedCount = childIds.count { it in selectedDeviceIds }
    return when {
        selectedCount == 0 -> LocationToggleState.OFF
        selectedCount == childIds.size -> LocationToggleState.ON
        else -> LocationToggleState.INDETERMINATE
    }
}
