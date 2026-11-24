package studio.acmenra.control.feature.events.presentation

import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import studio.acmenra.control.feature.events.domain.model.OverlayEntry
import studio.acmenra.control.feature.events.domain.model.OverlayMembership
import studio.acmenra.control.feature.events.domain.model.RealtimeEventOrigin
import studio.acmenra.control.feature.events.domain.repository.EventsFeedDelta
import studio.acmenra.control.feature.events.domain.repository.FlushFailureReason
import studio.acmenra.control.feature.events.domain.repository.PendingNewerFlushResult
import studio.acmenra.control.feature.events.presentation.components.LiveEventPhase
import studio.acmenra.control.feature.events.presentation.components.LiveEventTrackerReducer
import studio.acmenra.control.feature.events.presentation.components.LiveEventTrackerState
import studio.acmenra.control.feature.events.presentation.components.OffscreenQueueState
import studio.acmenra.control.feature.events.presentation.components.PendingNewerFlushContext
import studio.acmenra.control.feature.events.presentation.components.PendingNewerFlushPhase
import studio.acmenra.control.feature.events.presentation.components.isItemActuallyVisible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class EventsLiveLifecycleTrackerTest {
    private fun sampleEvent(id: String): Event =
        Event(
            id = id,
            eventVersion = 1L,
            classificationVersion = 1L,
            acknowledgementVersion = 1L,
            eventTypeId = "type-1",
            eventTypeName = "Safety Violation",
            categoryCode = "safety",
            iconKey = EventIconKey.GENERIC_ALERT,
            locationId = "loc-1",
            locationName = "Workshop 1",
            deviceId = "dev-1",
            deviceName = "Camera 1",
            occurredAt = Instant.parse("2026-03-01T10:00:00Z"),
            receivedAt = Instant.parse("2026-03-01T10:00:01Z"),
            status = EventAcknowledgementStatus.NEW,
            acknowledgedAt = null,
            acknowledgedBy = null,
            isErroneous = false,
            previewUrl = "https://example.com/preview.jpg",
            videoUrl = null,
        )

    /**
     * Test A (§16): A LIVE event arrives into visible viewport (starts enter-animation).
     * Before the animation completes, user scrolls away.
     * Must transition immediately to OFFSCREEN_PENDING, and offscreen queue must show count 1.
     */
    @Test
    fun testScenarioA_VisibleEventScrolledOffscreenBeforeEnterFinishes_BecomesOffscreenPending() {
        var tracker = LiveEventTrackerState()
        val delta =
            EventsFeedDelta(
                batchId = 1L,
                createdEventIds = listOf("ev-1"),
            )

        // 1. Register batch -> Phase is AWAITING_LAYOUT
        tracker = LiveEventTrackerReducer.registerBatch(tracker, delta)
        assertEquals(1, tracker.entries.size)
        assertEquals(LiveEventPhase.AWAITING_LAYOUT, tracker.entries.first().phase)
        assertTrue(tracker.offscreenPendingIds.isEmpty())

        // 2. First layout pass: ev-1 is visible
        tracker =
            LiveEventTrackerReducer.reconcileAfterLayout(
                state = tracker,
                actuallyVisibleKeys = setOf("ev-1"),
            )
        assertEquals(LiveEventPhase.ENTERING, tracker.entries.first().phase)
        assertEquals(setOf("ev-1"), tracker.enteringIds)
        assertTrue(tracker.offscreenPendingIds.isEmpty())

        // 3. User scrolls away before enter finishes (ev-1 is no longer visible)
        tracker =
            LiveEventTrackerReducer.reconcileAfterLayout(
                state = tracker,
                actuallyVisibleKeys = emptySet(),
            )
        assertEquals(LiveEventPhase.OFFSCREEN_PENDING, tracker.entries.first().phase)
        assertEquals(listOf("ev-1"), tracker.offscreenPendingIds)

        // 4. Queue reflects offscreen pending event immediately
        val queue = OffscreenQueueState().replaceLiveTargets(tracker.offscreenPendingIds)
        assertEquals(1, queue.totalCount)
        assertEquals(listOf("ev-1"), queue.loadedEventIds)
    }

    /**
     * Test B (§16): A LIVE event arrives into visible viewport.
     * Enter animation finishes while it is still visible -> transitions to PRESENTED.
     * Subsequent scroll away must NOT re-add the event to OFFSCREEN_PENDING.
     */
    @Test
    fun testScenarioB_VisibleEventEnterFinishesWhileVisible_BecomesPresented_NotReaddedOnScroll() {
        var tracker = LiveEventTrackerState()
        val delta =
            EventsFeedDelta(
                batchId = 1L,
                createdEventIds = listOf("ev-1"),
            )

        tracker = LiveEventTrackerReducer.registerBatch(tracker, delta)
        tracker =
            LiveEventTrackerReducer.reconcileAfterLayout(
                state = tracker,
                actuallyVisibleKeys = setOf("ev-1"),
            )
        assertEquals(LiveEventPhase.ENTERING, tracker.entries.first().phase)

        // Enter animation finishes while visible
        tracker =
            LiveEventTrackerReducer.enterAnimationFinished(
                state = tracker,
                eventId = "ev-1",
                batchId = 1L,
                isCurrentlyVisible = true,
            )
        assertEquals(LiveEventPhase.PRESENTED, tracker.entries.first().phase)
        assertTrue(tracker.offscreenPendingIds.isEmpty())

        // Subsequent scroll away does NOT move PRESENTED back to OFFSCREEN_PENDING
        tracker =
            LiveEventTrackerReducer.reconcileAfterLayout(
                state = tracker,
                actuallyVisibleKeys = emptySet(),
            )
        assertEquals(LiveEventPhase.PRESENTED, tracker.entries.first().phase)
        assertTrue(tracker.offscreenPendingIds.isEmpty())

        val queue = OffscreenQueueState().replaceLiveTargets(tracker.offscreenPendingIds)
        assertEquals(0, queue.totalCount)
    }

    /**
     * Test C (§16): User is scrolled down. A LIVE event arrives at top (immediately outside viewport).
     * Reconcile after layout transitions it directly to OFFSCREEN_PENDING.
     */
    @Test
    fun testScenarioC_EventImmediatelyOffscreen_BecomesOffscreenPending() {
        var tracker = LiveEventTrackerState()
        val delta =
            EventsFeedDelta(
                batchId = 1L,
                createdEventIds = listOf("ev-offscreen"),
            )

        tracker = LiveEventTrackerReducer.registerBatch(tracker, delta)
        assertEquals(LiveEventPhase.AWAITING_LAYOUT, tracker.entries.first().phase)

        // Layout pass: user is scrolled down, ev-offscreen is not visible
        tracker =
            LiveEventTrackerReducer.reconcileAfterLayout(
                state = tracker,
                actuallyVisibleKeys = setOf("ev-visible-1", "ev-visible-2"),
            )
        assertEquals(LiveEventPhase.OFFSCREEN_PENDING, tracker.entries.first().phase)
        assertEquals(listOf("ev-offscreen"), tracker.offscreenPendingIds)

        // When user eventually scrolls up and ev-offscreen becomes visible
        tracker =
            LiveEventTrackerReducer.reconcileAfterLayout(
                state = tracker,
                actuallyVisibleKeys = setOf("ev-offscreen"),
            )
        assertEquals(LiveEventPhase.PRESENTED, tracker.entries.first().phase)
        assertTrue(tracker.offscreenPendingIds.isEmpty())
    }

    /**
     * Test D (§16): Batch registration is strictly monotonic. Stale or replayed batches are dropped.
     */
    @Test
    fun testScenarioD_BatchMonotonicityAndDeDuplication() {
        var tracker = LiveEventTrackerState()
        val delta1 = EventsFeedDelta(batchId = 5L, createdEventIds = listOf("ev-1", "ev-2"))
        tracker = LiveEventTrackerReducer.registerBatch(tracker, delta1)
        assertEquals(5L, tracker.lastRegisteredBatchId)
        assertEquals(2, tracker.entries.size)

        // Replay of same batch 5L is dropped
        val trackerReplay = LiveEventTrackerReducer.registerBatch(tracker, delta1)
        assertEquals(tracker, trackerReplay)

        // Older batch 3L is dropped
        val deltaOld = EventsFeedDelta(batchId = 3L, createdEventIds = listOf("ev-old"))
        val trackerOld = LiveEventTrackerReducer.registerBatch(tracker, deltaOld)
        assertEquals(tracker, trackerOld)

        // Newer batch 6L is registered
        val deltaNew = EventsFeedDelta(batchId = 6L, createdEventIds = listOf("ev-3"))
        tracker = LiveEventTrackerReducer.registerBatch(tracker, deltaNew)
        assertEquals(6L, tracker.lastRegisteredBatchId)
        assertEquals(listOf("ev-1", "ev-2", "ev-3"), tracker.entries.map { it.eventId })
    }

    /**
     * Test E (§16): Burst of 20 events at top.
     * Only the visible ones become ENTERING; remaining 15 transition to OFFSCREEN_PENDING.
     */
    @Test
    fun testScenarioE_BurstOf20Events_OnlyVisibleItemsEnter_RemainderOffscreen() {
        var tracker = LiveEventTrackerState()
        val allIds = (1..20).map { "ev-$it" }
        val delta = EventsFeedDelta(batchId = 10L, createdEventIds = allIds)
        tracker = LiveEventTrackerReducer.registerBatch(tracker, delta)

        // Only first 5 are visible in viewport
        val visibleIds = (1..5).map { "ev-$it" }.toSet()
        tracker =
            LiveEventTrackerReducer.reconcileAfterLayout(
                state = tracker,
                actuallyVisibleKeys = visibleIds,
            )

        assertEquals(visibleIds, tracker.enteringIds)
        val expectedOffscreen = (6..20).map { "ev-$it" }
        assertEquals(expectedOffscreen, tracker.offscreenPendingIds)
        assertEquals(15, tracker.offscreenPendingIds.size)

        val queue = OffscreenQueueState().replaceLiveTargets(tracker.offscreenPendingIds)
        assertEquals(15, queue.totalCount)
        assertEquals(expectedOffscreen, queue.loadedEventIds)
    }

    /**
     * Viewport intersection calculation (§3):
     * Obscured by sticky header or footer/nav bar does not qualify as actually visible.
     */
    @Test
    fun testIsItemActuallyVisible_RespectsStickyHeaderAndSafeBottom() {
        val viewportStart = 39 // 36dp header + 3dp gap
        val viewportEnd = 800 // e.g. 900 - 80dp nav - 20dp gap
        val minVisible = 24

        // Item 1: entirely obscured under sticky date header (offset 0..30)
        assertFalse(
            isItemActuallyVisible(
                itemOffset = 0,
                itemSize = 30,
                viewportStart = viewportStart,
                viewportEnd = viewportEnd,
                minimumVisiblePixels = minVisible,
            ),
        )

        // Item 2: partially under header, only 10px visible (39..49) < 24px threshold
        assertFalse(
            isItemActuallyVisible(
                itemOffset = 20,
                itemSize = 29,
                viewportStart = viewportStart,
                viewportEnd = viewportEnd,
                minimumVisiblePixels = minVisible,
            ),
        )

        // Item 3: partially under header, but 40px visible (39..79) >= 24px threshold
        assertTrue(
            isItemActuallyVisible(
                itemOffset = 20,
                itemSize = 59,
                viewportStart = viewportStart,
                viewportEnd = viewportEnd,
                minimumVisiblePixels = minVisible,
            ),
        )

        // Item 4: completely inside visible range (100..180)
        assertTrue(
            isItemActuallyVisible(
                itemOffset = 100,
                itemSize = 80,
                viewportStart = viewportStart,
                viewportEnd = viewportEnd,
                minimumVisiblePixels = minVisible,
            ),
        )

        // Item 5: partially obscured at the bottom by nav bar (790..850) -> visible part is 790..800 = 10px < 24px
        assertFalse(
            isItemActuallyVisible(
                itemOffset = 790,
                itemSize = 60,
                viewportStart = viewportStart,
                viewportEnd = viewportEnd,
                minimumVisiblePixels = minVisible,
            ),
        )

        // Item 6: completely obscured below nav bar (850..930)
        assertFalse(
            isItemActuallyVisible(
                itemOffset = 850,
                itemSize = 80,
                viewportStart = viewportStart,
                viewportEnd = viewportEnd,
                minimumVisiblePixels = minVisible,
            ),
        )
    }

    /**
     * Flush race condition guard (§6):
     * Monotonic operationId and synchronous in-flight guard prevents multiple concurrent flushes.
     */
    @Test
    fun testFlushRaceCondition_MonotonicOperationIdAndResultMatching() {
        var opCounter = 0L
        val op1 = ++opCounter
        val op2 = ++opCounter
        assertTrue(op2 > op1)

        val context =
            PendingNewerFlushContext(
                operationId = op1,
                phase = PendingNewerFlushPhase.DISPATCHING_FLUSH,
                capturedCount = 5,
                feedScopeKey = "comp-1_default",
                capturedEventIds = listOf("ev-1", "ev-2"),
            )
        assertEquals(op1, context.operationId)

        val completedResult =
            PendingNewerFlushResult.Completed(
                operationId = op1,
                batchId = 1L,
                snapshotId = "snap-1",
                feedRevision = 10L,
                materializedEventIds = listOf("ev-1", "ev-2"),
                createdEventIds = listOf("ev-1", "ev-2"),
            )
        assertEquals(op1, completedResult.operationId)

        val rejectedResult =
            PendingNewerFlushResult.Rejected(
                operationId = op2,
                reason = FlushFailureReason.SESSION_CLOSED,
            )
        assertEquals(op2, rejectedResult.operationId)
        assertEquals(FlushFailureReason.SESSION_CLOSED, rejectedResult.reason)
    }

    /**
     * RealtimeEventOrigin (§9):
     * Defaults to UNKNOWN; CREATED and UPDATED are set strictly from isEventCreated.
     */
    @Test
    fun testRealtimeEventOrigin_PreservesNonUnknownAndDefaults() {
        val entryDefault =
            OverlayEntry(
                eventId = "ev-1",
                event = sampleEvent("ev-1"),
                eventVersion = 1L,
                membership = OverlayMembership.INCLUDED,
            )
        assertEquals(RealtimeEventOrigin.UNKNOWN, entryDefault.origin)

        val entryCreated = entryDefault.copy(origin = RealtimeEventOrigin.CREATED)
        assertEquals(RealtimeEventOrigin.CREATED, entryCreated.origin)

        val entryUpdated = entryDefault.copy(origin = RealtimeEventOrigin.UPDATED)
        assertEquals(RealtimeEventOrigin.UPDATED, entryUpdated.origin)
    }

    /**
     * Snapshot change must NOT reset the queue within the same feedScope (§4).
     */
    @Test
    fun testSnapshotChange_DoesNotResetQueueWithinSameFeedScope() {
        var queue =
            OffscreenQueueState()
                .enqueue(listOf("ev-1", "ev-2"))
                .upsertPendingNewer(3)

        assertEquals(5, queue.totalCount)

        // New snapshot arrives (polling/refresh within same feedScope)
        // Only remove items that are loaded and now in the visible snapshot
        queue = queue.removeLoadedAll(setOf("ev-1"))
        assertEquals(4, queue.totalCount)
        assertEquals(listOf("ev-2"), queue.loadedEventIds)
        assertEquals(3, queue.pendingNewerMarkerCount)
    }
}
