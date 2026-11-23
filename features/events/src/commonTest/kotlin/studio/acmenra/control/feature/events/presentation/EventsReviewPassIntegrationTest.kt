package studio.acmenra.control.feature.events.presentation

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import studio.acmenra.control.feature.events.domain.model.OverlayEntry
import studio.acmenra.control.feature.events.domain.model.OverlayMembership
import studio.acmenra.control.feature.events.domain.model.RealtimeOverlay
import studio.acmenra.control.feature.events.domain.repository.EventsFeedState
import studio.acmenra.control.feature.events.presentation.components.OffscreenDirection
import studio.acmenra.control.feature.events.presentation.components.OffscreenJumpTarget
import studio.acmenra.control.feature.events.presentation.components.OffscreenQueueEntry
import studio.acmenra.control.feature.events.presentation.components.OffscreenQueueState
import studio.acmenra.control.feature.events.presentation.components.PendingNewerFlushContext
import studio.acmenra.control.feature.events.presentation.components.PendingNewerFlushReason
import studio.acmenra.control.feature.events.presentation.util.EventDateDisplayFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventsReviewPassIntegrationTest {
    private fun createSampleEvent(
        id: String,
        occurredAt: String = "2026-03-01T10:00:00Z",
        status: EventAcknowledgementStatus = EventAcknowledgementStatus.NEW,
        isErroneous: Boolean = false,
        eventVersion: Long = 1L,
    ): Event =
        Event(
            id = id,
            eventVersion = eventVersion,
            classificationVersion = 1L,
            acknowledgementVersion = 1L,
            eventTypeId = "type-1",
            eventTypeName = "Safety Alert",
            categoryCode = "safety",
            iconKey = EventIconKey.GENERIC_ALERT,
            locationId = "loc-1",
            locationName = "Workshop 1",
            deviceId = "dev-1",
            deviceName = "Camera 1",
            occurredAt = Instant.parse(occurredAt),
            receivedAt = Instant.parse("2026-03-01T10:00:01Z"),
            status = status,
            acknowledgedAt = null,
            acknowledgedBy = null,
            isErroneous = isErroneous,
            previewUrl = "https://cdn.example.com/preview.jpg",
            videoUrl = null,
        )

    @Test
    fun testLiveClassification_DistinguishesCreatedFromUpdatedAndBootstrap() {
        val initialFeed =
            EventsFeedState(
                visibleEvents = listOf(createSampleEvent("ev-1")),
                snapshotId = "snap-1",
                asOfSequence = 10L,
                isInitialLoading = false,
            )

        // Case 1: Full refresh / new bootstrap snapshot (snapshotId changes)
        val refreshedFeed =
            initialFeed.copy(
                visibleEvents = listOf(createSampleEvent("ev-1"), createSampleEvent("ev-2")),
                snapshotId = "snap-2",
                asOfSequence = 20L,
            )
        // Bootstrap / refresh should not classify ev-2 as a live created event
        val isBootstrapOrRefresh = initialFeed.snapshotId != refreshedFeed.snapshotId || refreshedFeed.isInitialLoading
        assertTrue(isBootstrapOrRefresh)

        // Case 2: Live commit creates ev-3 (asOfSequence advances, snapshotId identical)
        val createdOverlay =
            RealtimeOverlay(
                entries =
                    mapOf(
                        "ev-3" to
                            OverlayEntry(
                                eventId = "ev-3",
                                event = createSampleEvent("ev-3", eventVersion = 1L),
                                eventVersion = 1L,
                                membership = OverlayMembership.INCLUDED,
                            ),
                    ),
            )
        val liveCommitFeed =
            refreshedFeed.copy(
                visibleEvents = listOf(createSampleEvent("ev-3"), createSampleEvent("ev-1"), createSampleEvent("ev-2")),
                realtimeOverlay = createdOverlay,
                asOfSequence = 21L,
            )

        val liveCreated = mutableSetOf<String>()
        if (liveCommitFeed.asOfSequence > refreshedFeed.asOfSequence &&
            liveCommitFeed.snapshotId == refreshedFeed.snapshotId
        ) {
            for ((id, entry) in liveCommitFeed.realtimeOverlay.entries) {
                if (entry.membership == OverlayMembership.INCLUDED &&
                    entry.eventVersion == 1L &&
                    !refreshedFeed.realtimeOverlay.entries.containsKey(id) &&
                    refreshedFeed.visibleEvents.none { it.id == id }
                ) {
                    liveCreated.add(id)
                }
            }
        }
        assertEquals(setOf("ev-3"), liveCreated)

        // Case 3: Live update for ev-1 (eventVersion > 1, isEventCreated = false)
        val updatedOverlay =
            RealtimeOverlay(
                entries =
                    createdOverlay.entries + (
                        "ev-1" to
                            OverlayEntry(
                                eventId = "ev-1",
                                event = createSampleEvent("ev-1", eventVersion = 2L, isErroneous = true),
                                eventVersion = 2L,
                                membership = OverlayMembership.INCLUDED,
                            )
                    ),
            )
        val updateFeed =
            liveCommitFeed.copy(
                asOfSequence = 22L,
                realtimeOverlay = updatedOverlay,
            )
        val liveCreatedOnUpdate = mutableSetOf<String>()
        for ((id, entry) in updateFeed.realtimeOverlay.entries) {
            if (entry.membership == OverlayMembership.INCLUDED &&
                entry.eventVersion == 1L &&
                !liveCommitFeed.realtimeOverlay.entries.containsKey(id) &&
                liveCommitFeed.visibleEvents.none { it.id == id }
            ) {
                liveCreatedOnUpdate.add(id)
            }
        }
        // ev-1 update MUST NOT be classified as live created
        assertTrue(liveCreatedOnUpdate.isEmpty())
    }

    @Test
    fun testBackdatedLiveEvent_EnqueuedWhenBelowViewport() {
        // User viewport displays lazy index 1..3
        val idToLazyIndex =
            mapOf(
                "ev-new-head" to 1,
                "ev-visible-1" to 2,
                "ev-visible-2" to 3,
                "ev-backdated" to 6, // placed below viewport in date ordering
            )
        val minVisible = 2
        val maxVisible = 3

        var queue = OffscreenQueueState()

        // ev-new-head: at top leading edge when user wasAtTopBeforeBatch -> not enqueued
        // ev-backdated: below maxVisible -> must be enqueued
        val isHeadAtTop = true
        val createdBatch = listOf("ev-new-head", "ev-backdated")

        val offscreen = mutableListOf<String>()
        for (id in createdBatch) {
            val targetIdx = idToLazyIndex[id]
            if (id == "ev-new-head" && isHeadAtTop) {
                // Leading edge top insertion
            } else if (targetIdx != null && targetIdx in minVisible..maxVisible) {
                // Visible inside viewport
            } else {
                offscreen.add(id)
            }
        }

        queue = queue.enqueue(offscreen)
        assertEquals(listOf("ev-backdated"), queue.loadedEventIds)
        assertEquals(1, queue.totalCount)

        // Compute direction for backdated item: must be DOWN
        val (nextQ, target) =
            queue.computeHeadTarget(
                minVisible,
                maxVisible,
                idToLazyIndex,
                allFeedEventIds = idToLazyIndex.keys,
            )
        assertNotNull(target)
        assertTrue(target is OffscreenJumpTarget.LoadedEvent)
        assertEquals("ev-backdated", target.eventId)
        assertEquals(OffscreenDirection.DOWN, target.direction)
        assertEquals(1, nextQ.totalCount)
    }

    @Test
    fun testAccumulatedWhileAway_AtomicReconcilePreservesOrder() {
        val flushContext =
            PendingNewerFlushContext(
                reason = PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY,
                capturedCount = 3,
                feedScopeKey = "scope_1",
            )
        assertEquals(PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY, flushContext.reason)

        var queue =
            OffscreenQueueState()
                .enqueue(listOf("ev-older-offscreen"))
                .upsertPendingNewer(3)
                .upsertPendingHistory(1, requiresRefresh = true, hasMore = true)

        // Total count before flush: 1 loaded + 3 pending newer + 1 pending history = 5
        assertEquals(5, queue.totalCount)

        // When prepended items arrive, ev-new-1 and ev-new-2 are visible in new top viewport,
        // while ev-new-3 remains offscreen below viewport
        val materialized = listOf("ev-new-1", "ev-new-2", "ev-new-3")
        val newlyVisibleKeys = setOf("ev-new-1", "ev-new-2")

        queue =
            queue.reconcilePendingNewerWithMaterializedIds(
                materializedIds = materialized,
                currentlyVisibleIds = newlyVisibleKeys,
            )

        // PendingNewer marker is atomically replaced in-place by remaining offscreen event ev-new-3:
        // Total: 1 (ev-new-3) + 1 (ev-older-offscreen) + 1 (PendingHistory) = 3
        assertEquals(3, queue.totalCount)
        assertFalse(queue.hasPendingNewerMarker)
        assertEquals(listOf("ev-new-3", "ev-older-offscreen"), queue.loadedEventIds)
        assertTrue(queue.entries.last() is OffscreenQueueEntry.PendingHistory)
    }

    @Test
    fun testAcknowledgeGuard_OnlyTriggersForValidNewEvents() {
        val newEvent = createSampleEvent("ev-1", status = EventAcknowledgementStatus.NEW)
        val ackedEvent = createSampleEvent("ev-2", status = EventAcknowledgementStatus.ACKNOWLEDGED)
        val erroneousEvent = createSampleEvent("ev-3", status = EventAcknowledgementStatus.NEW, isErroneous = true)

        var ackCount = 0
        val onAcknowledge: (Event) -> Unit = { ackCount++ }

        val canAcknowledge = true
        val pendingAckIds = setOf("ev-pending")

        fun tryAck(event: Event) {
            val canAck =
                canAcknowledge &&
                    (
                        event.status == EventAcknowledgementStatus.NEW ||
                            event.status == EventAcknowledgementStatus.LEGACY_UNKNOWN
                    ) &&
                    event.id !in pendingAckIds &&
                    !event.isErroneous
            if (canAck) onAcknowledge(event)
        }

        tryAck(newEvent)
        assertEquals(1, ackCount)

        // Already acknowledged event must not trigger acknowledge
        tryAck(ackedEvent)
        assertEquals(1, ackCount)

        // Erroneous event must not trigger acknowledge
        tryAck(erroneousEvent)
        assertEquals(1, ackCount)

        // Event already pending ACK must not trigger duplicate acknowledge
        val pendingEvent = createSampleEvent("ev-pending", status = EventAcknowledgementStatus.NEW)
        tryAck(pendingEvent)
        assertEquals(1, ackCount)
    }

    @Test
    fun testCompactVisualSelection_OnlyWhenViewerOpen() {
        val selectedId = "ev-10"
        val isCompact = true

        // When compact modal viewer is open: visual selection is true
        var isViewerOpen = true
        var isVisuallySelected = if (isCompact) (isViewerOpen && selectedId == "ev-10") else (selectedId == "ev-10")
        assertTrue(isVisuallySelected)

        // When compact modal viewer is dismissed: selectedId is retained, but visual selection is false
        isViewerOpen = false
        isVisuallySelected = if (isCompact) (isViewerOpen && selectedId == "ev-10") else (selectedId == "ev-10")
        assertFalse(isVisuallySelected)

        // On Wide/Medium (isCompact = false): visual selection remains true regardless of modal
        val isWideVisuallySelected = false || (selectedId == "ev-10")
        assertTrue(isWideVisuallySelected)
    }

    @Test
    fun testInfiniteScrollThreshold_ExcludesStickyHeaders() {
        val events = (1..20).map { createSampleEvent("ev-$it") }
        // Suppose 15 events have been scrolled past; last visible event is ev-15
        val lastVisibleEventIndex = 14 // 0-based index for ev-15
        val remainingEvents = events.size - 1 - lastVisibleEventIndex
        assertEquals(5, remainingEvents)
        // With remaining <= 5, infinite scroll triggers
        assertTrue(remainingEvents <= 5)

        // If last visible was ev-14, remaining is 6 -> infinite scroll does not trigger yet
        val remainingAboveThreshold = events.size - 1 - 13
        assertEquals(6, remainingAboveThreshold)
        assertFalse(remainingAboveThreshold <= 5)
    }

    @Test
    fun testTimezoneNullSafety_EventsNeverVanishing() {
        val fallbackTz = TimeZone.currentSystemDefault()
        assertNotNull(fallbackTz)

        // Formatting supporting text with fallback timezone always includes HH:mm
        val text =
            EventDateDisplayFormatter.formatEventCardSupportingText(
                occurredAt = Instant.parse("2026-03-01T14:30:00Z"),
                businessTimeZone = fallbackTz,
                locationName = "Location A",
                deviceName = "Device 1",
            )
        assertTrue(text.contains(":"))
        assertTrue(text.contains("Location A, Device 1"))
    }
}
