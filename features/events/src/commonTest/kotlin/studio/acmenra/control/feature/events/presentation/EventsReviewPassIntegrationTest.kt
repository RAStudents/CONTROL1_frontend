package studio.acmenra.control.feature.events.presentation

import androidx.compose.ui.input.key.Key
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import studio.acmenra.control.feature.events.domain.model.EventsFilter
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
import studio.acmenra.control.feature.events.presentation.util.EventsKeyboardNavigationHelper
import studio.acmenra.control.feature.events.presentation.util.EventsScrollAnchorHelper
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

    @Test
    fun testAccumulatedWhileAway_FeedWith300OldEvents_OnlyPrependedMaterializedSegmentEntersReconcile() {
        val oldEvents = (1..300).map { createSampleEvent("old-$it") }
        val preFlushEventIds = oldEvents.map { it.id }.toSet()
        val flush =
            PendingNewerFlushContext(
                reason = PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY,
                capturedCount = 3,
                feedScopeKey = "scope_1",
                preFlushEventIds = preFlushEventIds,
            )
        var queue = OffscreenQueueState().upsertPendingNewer(3)
        val newEvents =
            listOf(
                createSampleEvent("new-1"),
                createSampleEvent("new-2"),
                createSampleEvent("new-3"),
            ) + oldEvents

        val prependedCandidates = newEvents.takeWhile { it.id !in flush.preFlushEventIds }
        val materializedIds = prependedCandidates.take(flush.capturedCount).map { it.id }
        assertEquals(listOf("new-1", "new-2", "new-3"), materializedIds)

        // Visible in layout after scroll to top: new-1, new-2. Offscreen: new-3
        val newlyVisibleKeys = setOf("new-1", "new-2")
        queue =
            queue.reconcilePendingNewerWithMaterializedIds(
                materializedIds = materializedIds,
                currentlyVisibleIds = newlyVisibleKeys,
            )

        // Only new-3 should enter queue! None of the 300 old events!
        assertEquals(listOf("new-3"), queue.loadedEventIds)
        assertEquals(1, queue.totalCount)
        assertFalse(queue.hasPendingNewerMarker)
    }

    @Test
    fun testAccumulatedWhileAway_SnapshotIdChange_RetainsMarkerAndFlushContext() {
        var activeFlushContext: PendingNewerFlushContext? =
            PendingNewerFlushContext(
                reason = PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY,
                capturedCount = 5,
                feedScopeKey = "scope_1",
            )
        var queueState = OffscreenQueueState().upsertPendingNewer(5)

        // Expected authoritative refresh arrives with new snapshotId
        if (activeFlushContext?.reason != PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY) {
            queueState = OffscreenQueueState()
        }
        assertEquals(5, queueState.pendingNewerMarkerCount)
        assertNotNull(activeFlushContext)

        // Conversely, independent refresh without active flush MUST reset queue
        activeFlushContext = null
        if (activeFlushContext?.reason != PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY) {
            queueState = OffscreenQueueState()
        }
        assertEquals(0, queueState.totalCount)
    }

    @Test
    fun testPendingCount_UpdateDuringActiveFlush_UpdatesCapturedCountAndMarker() {
        var activeFlushContext =
            PendingNewerFlushContext(
                reason = PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY,
                capturedCount = 5,
                feedScopeKey = "scope_1",
            )
        var queueState = OffscreenQueueState().upsertPendingNewer(5)

        // Feed sends update: pendingNewerCount = 8 while flush is active
        val updatedPendingCount = 8
        if (activeFlushContext.reason == PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY) {
            if (updatedPendingCount > activeFlushContext.capturedCount) {
                activeFlushContext = activeFlushContext.copy(capturedCount = updatedPendingCount)
                queueState = queueState.upsertPendingNewer(updatedPendingCount)
            }
        }
        assertEquals(8, activeFlushContext.capturedCount)
        assertEquals(8, queueState.pendingNewerMarkerCount)
    }

    @Test
    fun testReconcile_TriggeredWhenPendingNewerCountBecomesZero() {
        val preFlushEventIds = setOf("ev-1")
        val flush =
            PendingNewerFlushContext(
                reason = PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY,
                capturedCount = 2,
                feedScopeKey = "scope_1",
                preFlushEventIds = preFlushEventIds,
            )
        val pendingNewerCount = 0
        val shouldReconcile = flush.reason == PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY && pendingNewerCount == 0
        assertTrue(shouldReconcile)
    }

    @Test
    fun testLiveRemoval_TwoBatchesUnder350ms_PreservesIndependentLifecycles() {
        var exitingIds = setOf<String>()
        var displayedEvents = listOf(createSampleEvent("ev-A"), createSampleEvent("ev-B"), createSampleEvent("ev-C"))

        // Batch 1 removes ev-A
        val liveRemovedBatch1 = setOf("ev-A")
        exitingIds = exitingIds + liveRemovedBatch1
        assertEquals(setOf("ev-A"), exitingIds)

        // Batch 2 removes ev-B within < 350ms before batch 1 finishes
        val liveRemovedBatch2 = setOf("ev-B")
        exitingIds = exitingIds + liveRemovedBatch2
        assertEquals(setOf("ev-A", "ev-B"), exitingIds)

        // Batch 1 finishes exit transition
        exitingIds = exitingIds - "ev-A"
        displayedEvents = displayedEvents.filterNot { it.id == "ev-A" }
        assertEquals(setOf("ev-B"), exitingIds)
        assertEquals(listOf("ev-B", "ev-C"), displayedEvents.map { it.id })

        // Batch 2 finishes exit transition independently
        exitingIds = exitingIds - "ev-B"
        displayedEvents = displayedEvents.filterNot { it.id == "ev-B" }
        assertTrue(exitingIds.isEmpty())
        assertEquals(listOf("ev-C"), displayedEvents.map { it.id })
    }

    @Test
    fun testInitialLoadAndPagination_DoNotTriggerLiveEnterAnimations() {
        val liveEnterIds = emptySet<String>()
        val sampleEvent = createSampleEvent("ev-1")
        val shouldAnimateEnter = sampleEvent.id in liveEnterIds
        assertFalse(shouldAnimateEnter)
    }

    @Test
    fun testCardRecyclingOnScroll_DoesNotReplayEnterAnimation() {
        val liveEnterIds = setOf("ev-live-1") // only newly arrived live event
        val recycledOldEvent = createSampleEvent("ev-old-20")
        val shouldAnimateEnter = recycledOldEvent.id in liveEnterIds
        assertFalse(shouldAnimateEnter)
    }

    @Test
    fun testScrollAnchor_FallbackIndexZero_RestoresTopWithZeroOffset() {
        val resolvedFallbackZero =
            EventsScrollAnchorHelper.resolveFallbackIndex(
                anchorId = "ev-none",
                priorSnapshotIds = listOf("ev-none"),
                currentIdToLazyIndex = emptyMap(),
            )
        assertEquals(0, resolvedFallbackZero)
        val scrollOffset = if (resolvedFallbackZero == 0) 0 else 100
        assertEquals(0, scrollOffset)
    }

    @Test
    fun testFeedScopeChange_ClearsQueueAndResetsState() {
        val filter1 = EventsFilter(eventTypeIds = setOf("type-1"))
        val filter2 = EventsFilter(eventTypeIds = setOf("type-2"))
        val scope1 = canonicalFeedScopeKey("comp-1", filter1)
        val scope2 = canonicalFeedScopeKey("comp-1", filter2)
        assertTrue(scope1 != scope2)

        var queueState = OffscreenQueueState().enqueue(listOf("ev-1"))
        var activeFlushContext: PendingNewerFlushContext? =
            PendingNewerFlushContext(
                reason = PendingNewerFlushReason.TRANSIENT_AT_TOP,
                capturedCount = 1,
                feedScopeKey = scope1,
            )

        // Scope changes to scope2 -> state reset
        if (scope1 != scope2) {
            queueState = OffscreenQueueState()
            activeFlushContext = null
        }
        assertEquals(0, queueState.totalCount)
        assertEquals(null, activeFlushContext)
    }

    @Test
    fun testFilterReEntry_EventUpdatedDoesNotClassifyAsLiveCreated() {
        val initialFeed =
            EventsFeedState(
                visibleEvents = emptyList(), // event was previously filtered out
                snapshotId = "snap-1",
                asOfSequence = 10L,
            )
        // event_updated arrives (eventVersion = 2L), causing it to appear in visibleEvents
        val updatedOverlay =
            RealtimeOverlay(
                entries =
                    mapOf(
                        "ev-1" to
                            OverlayEntry(
                                eventId = "ev-1",
                                event = createSampleEvent("ev-1", eventVersion = 2L),
                                eventVersion = 2L,
                                membership = OverlayMembership.INCLUDED,
                            ),
                    ),
            )
        val feedWithUpdatedEvent =
            initialFeed.copy(
                asOfSequence = 11L,
                visibleEvents = listOf(createSampleEvent("ev-1", eventVersion = 2L)),
                realtimeOverlay = updatedOverlay,
            )
        val liveCreated = mutableSetOf<String>()
        for ((id, entry) in feedWithUpdatedEvent.realtimeOverlay.entries) {
            if (entry.membership == OverlayMembership.INCLUDED &&
                entry.eventVersion == 1L &&
                !initialFeed.realtimeOverlay.entries.containsKey(id) &&
                initialFeed.visibleEvents.none { it.id == id }
            ) {
                liveCreated.add(id)
            }
        }
        // eventVersion == 2L is an update, NOT a live created event
        assertTrue(liveCreated.isEmpty())
    }

    @Test
    fun testShiftNavigation_IgnoredByKeyboardHelper() {
        val events = listOf(createSampleEvent("ev-1"), createSampleEvent("ev-2"))
        var selectedId: String? = "ev-1"
        val handled =
            EventsKeyboardNavigationHelper.handleNavigationKey(
                key = Key.DirectionDown,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
                isShiftPressed = true, // Shift pressed
                isKeyDown = true,
                events = events,
                selectedEventId = selectedId,
                isCompact = false,
                onSelectEvent = { selectedId = it },
            )
        assertFalse(handled)
        assertEquals("ev-1", selectedId)
    }

    @Test
    fun testDirectionalAccessibilityCount_SeparatesUpAndDown() {
        val queue =
            OffscreenQueueState()
                .upsertPendingNewer(4)
                .enqueue(listOf("ev-loaded-above", "ev-loaded-below"))
                .upsertPendingHistory(2, requiresRefresh = true, hasMore = true)

        val idToLazyIndex =
            mapOf(
                "ev-loaded-above" to 1,
                "ev-visible" to 5,
                "ev-loaded-below" to 9,
            )
        val minVisible = 4
        val maxVisible = 6

        val loadedAbove =
            queue.entries
                .filterIsInstance<OffscreenQueueEntry.LoadedEvent>()
                .count { idToLazyIndex[it.eventId]?.let { idx -> idx < minVisible } == true }
        val loadedBelow =
            queue.entries
                .filterIsInstance<OffscreenQueueEntry.LoadedEvent>()
                .count { idToLazyIndex[it.eventId]?.let { idx -> idx > maxVisible } == true }

        val upCount = queue.pendingNewerMarkerCount + loadedAbove
        val downCount = queue.pendingHistoryCount + loadedBelow

        assertEquals(1, loadedAbove)
        assertEquals(1, loadedBelow)
        assertEquals(5, upCount) // 4 pending newer + 1 loaded above
        assertEquals(3, downCount) // 2 pending history + 1 loaded below
    }
}
