package studio.acmenra.control.feature.events.domain.model

enum class OverlayMembership {
    INCLUDED,
    REMOVED,
    PENDING_NEWER,
    PENDING_HISTORY,
}

enum class RealtimeEventOrigin {
    CREATED,
    UPDATED,
}

data class OverlayEntry(
    val eventId: String,
    val event: Event,
    val eventVersion: Long,
    val membership: OverlayMembership,
    val origin: RealtimeEventOrigin = RealtimeEventOrigin.CREATED,
)

data class RealtimeOverlay(
    val entries: Map<String, OverlayEntry> = emptyMap(),
    val pendingAckIds: Set<String> = emptySet(),
    val requiresRefresh: Boolean = false,
)

enum class RealtimeConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    REPLAYING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}
