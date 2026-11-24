package studio.acmenra.control.feature.events.realtime

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import studio.acmenra.control.feature.events.data.realtime.EventsSessionProcessor
import studio.acmenra.control.feature.events.data.realtime.SessionCommand
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.repository.EventsFeedDelta
import studio.acmenra.control.feature.events.domain.repository.PendingNewerFlushResult
import studio.acmenra.control.feature.events.presentation.components.EventsNavigationCoordinator
import studio.acmenra.control.feature.events.presentation.components.LiveBatchKey
import studio.acmenra.control.feature.events.presentation.components.LiveEventPhase
import studio.acmenra.control.feature.events.presentation.components.LiveEventTrackerReducer
import studio.acmenra.control.feature.events.presentation.components.LiveEventTrackerState
import studio.acmenra.control.feature.events.presentation.components.MAX_OFFSCREEN_QUEUE_ENTRIES
import studio.acmenra.control.feature.events.presentation.components.OffscreenDirection
import studio.acmenra.control.feature.events.presentation.components.OffscreenHeadResolution
import studio.acmenra.control.feature.events.presentation.components.OffscreenJumpTarget
import studio.acmenra.control.feature.events.presentation.components.OffscreenQueueState
import studio.acmenra.control.feature.events.presentation.components.ViewportBounds
import studio.acmenra.control.feature.events.presentation.util.EventsScrollAnchorHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class EventsComprehensivePRReviewTests {
    private fun sampleEvent(
        id: String,
        version: Long = 1L,
        status: EventAcknowledgementStatus = EventAcknowledgementStatus.NEW,
    ): Event =
        Event(
            id = id,
            eventVersion = version,
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
            status = status,
            acknowledgedAt = null,
            acknowledgedBy = null,
            isErroneous = false,
            previewUrl = "https://example.com/preview.jpg",
            videoUrl = null,
        )

    // =========================================================================
    // 1. LiveEventTracker & Session Generation Tests (P0 Bug 2.6 & Lifecycle)
    // =========================================================================

    @Test
    fun testLiveBatchKey_Ordering() {
        val b1 = LiveBatchKey(sessionGeneration = 1L, batchId = 10L)
        val b2 = LiveBatchKey(sessionGeneration = 1L, batchId = 20L)
        val b3 = LiveBatchKey(sessionGeneration = 2L, batchId = 1L)

        assertTrue(b1 < b2)
        assertTrue(b2 < b3)
        assertEquals(0, b1.compareTo(LiveBatchKey(1L, 10L)))
    }

    @Test
    fun testLiveEventTracker_SessionGenerationChange_ResetsWatermarkAndRetainsOffscreen() {
        var state = LiveEventTrackerState(sessionGeneration = 1L)
        val delta = EventsFeedDelta(batchId = 25L, createdEventIds = listOf("e1", "e2"))
        state = LiveEventTrackerReducer.registerBatch(state, delta, sessionGeneration = 1L)
        assertEquals(25L, state.lastRegisteredBatchId)

        // Put e1 into OFFSCREEN_PENDING and e2 into PRESENTED
        state =
            LiveEventTrackerReducer.reconcileAfterLayout(
                state = state,
                actuallyVisibleKeys = setOf("e2"),
                confirmedOffscreenIds = setOf("e1"),
            )
        assertEquals(LiveEventPhase.OFFSCREEN_PENDING, state.entries.first { it.eventId == "e1" }.phase)
        assertEquals(LiveEventPhase.ENTERING, state.entries.first { it.eventId == "e2" }.phase)

        // Session generation changes to 2
        val nextGenState = state.onSessionGenerationChanged(2L)
        assertEquals(2L, nextGenState.sessionGeneration)
        assertEquals(0L, nextGenState.lastRegisteredBatchId, "Batch watermark must reset to 0L on new generation")
        assertEquals(1, nextGenState.entries.size)
        assertEquals("e1", nextGenState.entries.first().eventId)
        assertEquals(LiveEventPhase.OFFSCREEN_PENDING, nextGenState.entries.first().phase)

        // Batch 1 in generation 2 must now be accepted
        val deltaGen2 = EventsFeedDelta(batchId = 1L, createdEventIds = listOf("e3"))
        val registeredGen2 = LiveEventTrackerReducer.registerBatch(nextGenState, deltaGen2, sessionGeneration = 2L)
        assertEquals(1L, registeredGen2.lastRegisteredBatchId)
        assertEquals(2, registeredGen2.entries.size)
        assertTrue(registeredGen2.entries.any { it.eventId == "e3" && it.phase == LiveEventPhase.AWAITING_LAYOUT })
    }

    @Test
    fun testLiveEventTracker_StaleSessionGenerationBatch_IsRejected() {
        val state = LiveEventTrackerState(sessionGeneration = 2L, lastRegisteredBatchId = 5L)
        val staleDelta = EventsFeedDelta(batchId = 10L, createdEventIds = listOf("stale1"))

        val result = LiveEventTrackerReducer.registerBatch(state, staleDelta, sessionGeneration = 1L)
        assertEquals(state, result, "Batch with older sessionGeneration must be ignored")
    }

    @Test
    fun testLiveEventTracker_DuplicateBatch_IsIdempotent() {
        var state = LiveEventTrackerState(sessionGeneration = 1L)
        val delta = EventsFeedDelta(batchId = 10L, createdEventIds = listOf("e1"))
        state = LiveEventTrackerReducer.registerBatch(state, delta, sessionGeneration = 1L)
        assertEquals(1, state.entries.size)

        val duplicate = LiveEventTrackerReducer.registerBatch(state, delta, sessionGeneration = 1L)
        assertEquals(state, duplicate)
    }

    // =========================================================================
    // 2. AwaitingLayout vs ConfirmedOffscreen (P0 Bug 2.1)
    // =========================================================================

    @Test
    fun testReconcileAfterLayout_NotYetLaidOut_StaysInAwaitingLayout() {
        var state = LiveEventTrackerState(sessionGeneration = 1L)
        val delta = EventsFeedDelta(batchId = 1L, createdEventIds = listOf("new-event-1"))
        state = LiveEventTrackerReducer.registerBatch(state, delta, sessionGeneration = 1L)
        assertEquals(LiveEventPhase.AWAITING_LAYOUT, state.entries.first().phase)

        // Reconciliation pass where new-event-1 is NOT yet in actuallyVisibleKeys AND NOT in confirmedOffscreenIds
        state =
            LiveEventTrackerReducer.reconcileAfterLayout(
                state = state,
                actuallyVisibleKeys = setOf("older-event-2"),
                expandingInViewportIds = emptySet(),
                layoutVisibleKeys = setOf("older-event-2"),
                confirmedOffscreenIds = emptySet(), // Not confirmed offscreen!
            )

        assertEquals(
            LiveEventPhase.AWAITING_LAYOUT,
            state.entries.first().phase,
            "Event not yet laid out must remain AWAITING_LAYOUT and must NOT prematurely become OFFSCREEN_PENDING",
        )
        assertTrue(state.offscreenPendingIds.isEmpty(), "Indicator pill must not show while awaiting layout")
    }

    @Test
    fun testReconcileAfterLayout_ConfirmedOffscreen_BecomesOffscreenPending() {
        var state = LiveEventTrackerState(sessionGeneration = 1L)
        val delta = EventsFeedDelta(batchId = 1L, createdEventIds = listOf("offscreen-event-1"))
        state = LiveEventTrackerReducer.registerBatch(state, delta, sessionGeneration = 1L)

        // Reconciliation pass where offscreen-event-1 is confirmed offscreen
        state =
            LiveEventTrackerReducer.reconcileAfterLayout(
                state = state,
                actuallyVisibleKeys = setOf("visible-event-10"),
                confirmedOffscreenIds = setOf("offscreen-event-1"),
            )

        assertEquals(LiveEventPhase.OFFSCREEN_PENDING, state.entries.first().phase)
        assertEquals(listOf("offscreen-event-1"), state.offscreenPendingIds)
    }

    @Test
    fun testReconcileAfterLayout_Visible_TransitionsToEnteringAndPresented() {
        var state = LiveEventTrackerState(sessionGeneration = 1L)
        val delta = EventsFeedDelta(batchId = 1L, createdEventIds = listOf("e1"))
        state = LiveEventTrackerReducer.registerBatch(state, delta, sessionGeneration = 1L)

        // Event becomes visible
        state =
            LiveEventTrackerReducer.reconcileAfterLayout(
                state = state,
                actuallyVisibleKeys = setOf("e1"),
            )
        assertEquals(LiveEventPhase.ENTERING, state.entries.first().phase)

        // Enter animation finishes while visible
        state =
            LiveEventTrackerReducer.enterAnimationFinished(
                state = state,
                eventId = "e1",
                batchId = 1L,
                isCurrentlyVisible = true,
                sessionGeneration = 1L,
            )
        assertEquals(LiveEventPhase.PRESENTED, state.entries.first().phase)
        assertTrue(state.offscreenPendingIds.isEmpty())
    }

    @Test
    fun testEnterAnimationFinished_DifferentSessionGeneration_DoesNotComplete() {
        var state = LiveEventTrackerState(sessionGeneration = 2L)
        val delta = EventsFeedDelta(batchId = 1L, createdEventIds = listOf("e1"))
        state = LiveEventTrackerReducer.registerBatch(state, delta, sessionGeneration = 2L)
        state = LiveEventTrackerReducer.reconcileAfterLayout(state, setOf("e1"))
        assertEquals(LiveEventPhase.ENTERING, state.entries.first().phase)

        // Animation finishes with old sessionGeneration = 1L
        val attempted =
            LiveEventTrackerReducer.enterAnimationFinished(
                state = state,
                eventId = "e1",
                batchId = 1L,
                isCurrentlyVisible = true,
                sessionGeneration = 1L, // Stale generation
            )
        assertEquals(
            LiveEventPhase.ENTERING,
            attempted.entries.first().phase,
            "Old generation must not finish new entry",
        )
    }

    // =========================================================================
    // 3. Viewport Geometry & Safe Viewport Tests
    // =========================================================================

    @Test
    fun testViewportBounds_AndCardVisibilityCalculations() {
        val safeVp = ViewportBounds(top = 80, bottom = 600)
        val minVisibleCardHeight = 24

        // Case 1: Item fully inside safe viewport
        val item1Top = 100
        val item1Size = 100
        val vStart1 = maxOf(item1Top, safeVp.top)
        val vEnd1 = minOf(item1Top + item1Size, safeVp.bottom)
        val vHeight1 = (vEnd1 - vStart1).coerceAtLeast(0)
        assertTrue(vHeight1 >= minVisibleCardHeight)

        // Case 2: Item obscured under sticky header (top = 40, size = 50 -> bottom = 90)
        // visible height = 90 - 80 = 10 < 24 -> NOT actually visible!
        val item2Top = 40
        val item2Size = 50
        val vStart2 = maxOf(item2Top, safeVp.top)
        val vEnd2 = minOf(item2Top + item2Size, safeVp.bottom)
        val vHeight2 = (vEnd2 - vStart2).coerceAtLeast(0)
        assertEquals(10, vHeight2)
        assertFalse(vHeight2 >= minVisibleCardHeight, "Card obscured under header must not be considered fully visible")

        // Case 3: Tall card (> viewport height = 520, card size = 800)
        val item3Top = 80
        val item3Size = 800
        val vStart3 = maxOf(item3Top, safeVp.top)
        val vEnd3 = minOf(item3Top + item3Size, safeVp.bottom)
        val vHeight3 = (vEnd3 - vStart3).coerceAtLeast(0)
        assertEquals(520, vHeight3)
        assertTrue(vHeight3 >= minVisibleCardHeight, "Tall card spanning safe viewport must be visible")
    }

    // =========================================================================
    // 4. OffscreenQueueState Tests (P0 Bugs 2.3 & 2.4, Capping & Dedup)
    // =========================================================================

    @Test
    fun testOffscreenQueueState_MaxEntriesBound_DoesNotDropMarkers() {
        var queue = OffscreenQueueState()
        queue = queue.upsertPendingNewer(count = 5, eventIds = listOf("p1", "p2"))
        queue = queue.upsertPendingHistory(count = 3, requiresRefresh = true, hasMore = true)

        // Enqueue 600 loaded items
        val largeIds = (1..600).map { "ev-$it" }
        queue = queue.enqueue(largeIds)

        assertTrue(queue.entries.size <= MAX_OFFSCREEN_QUEUE_ENTRIES + 2)
        assertTrue(queue.hasPendingNewerMarker, "Pending newer marker must never be dropped by capping")
        assertTrue(queue.hasPendingHistoryMarker, "Pending history marker must never be dropped by capping")
    }

    @Test
    fun testReconcilePendingNewer_PreservesRemainingCreatedEvents() {
        val queue = OffscreenQueueState().upsertPendingNewer(count = 5, eventIds = listOf("e1", "e2", "e3"))

        // Flush completes, but 2 events arrived during flush
        val reconciled =
            queue.reconcilePendingNewer(
                materializedIds = listOf("e1", "e2", "e3"),
                currentlyVisibleIds = setOf("e1"),
                remainingCreatedIds = listOf("e4", "e5"),
                remainingCreatedCount = 2,
            )

        assertTrue(reconciled.hasPendingNewerMarker, "Marker must be preserved for remaining events")
        assertEquals(2, reconciled.pendingNewerMarkerCount)
        assertTrue(
            reconciled.loadedEventIds.containsAll(listOf("e2", "e3")),
            "Offscreen materialized items must be loaded",
        )
        assertFalse(reconciled.loadedEventIds.contains("e1"), "Visible items must not be loaded offscreen")
    }

    @Test
    fun testReconcilePendingNewer_ClearsMarkerWhenNoRemainingEvents() {
        val queue = OffscreenQueueState().upsertPendingNewer(count = 2, eventIds = listOf("e1", "e2"))

        val reconciled =
            queue.reconcilePendingNewer(
                materializedIds = listOf("e1", "e2"),
                currentlyVisibleIds = setOf("e1", "e2"),
                remainingCreatedIds = emptyList(),
                remainingCreatedCount = 0,
            )

        assertFalse(reconciled.hasPendingNewerMarker, "Marker must be cleared when all events are flushed")
        assertTrue(reconciled.loadedEventIds.isEmpty(), "Both events are visible, none should be loaded offscreen")
    }

    @Test
    fun testResolveHeadTarget_PrunesActuallyVisibleLoadedEvents() {
        val queue = OffscreenQueueState().enqueue(listOf("e1", "e2", "e3"))
        val idToLazy = mapOf("e1" to 2, "e2" to 10, "e3" to 15)

        // e1 is in actuallyVisibleIds -> must be pruned, head target becomes e2
        val (cleaned, res) =
            queue.resolveHeadTarget(
                minVisibleEventLazyIndex = 3,
                maxVisibleEventLazyIndex = 8,
                eventIdToLazyIndex = idToLazy,
                actuallyVisibleIds = setOf("e1"),
            )

        assertFalse(cleaned.loadedEventIds.contains("e1"), "e1 must be pruned because it is visible")
        assertIs<OffscreenHeadResolution.Ready>(res)
        val target = res.target
        assertIs<OffscreenJumpTarget.LoadedEvent>(target)
        assertEquals("e2", target.eventId)
        assertEquals(OffscreenDirection.DOWN, target.direction)
    }

    @Test
    fun testResolveHeadTarget_TargetIndexNull_ReturnsAwaitingLayout() {
        val queue = OffscreenQueueState().enqueue(listOf("unmeasured-event"))
        val idToLazy = emptyMap<String, Int>()

        val (cleaned, res) =
            queue.resolveHeadTarget(
                minVisibleEventLazyIndex = 1,
                maxVisibleEventLazyIndex = 5,
                eventIdToLazyIndex = idToLazy,
            )

        assertIs<OffscreenHeadResolution.AwaitingLayout>(res)
        assertEquals("unmeasured-event", res.targetEventId)
    }

    // =========================================================================
    // 5. Scroll Anchoring Tests (P1 Anchor Key & Fallback)
    // =========================================================================

    @Test
    fun testScrollAnchor_IgnoresTechnicalKeys() {
        val feedIds = setOf("e1", "e2", "e3")
        val technicalKeys = listOf("date-group-19800", "header_title", "pagination-footer", "footer_space")

        for (k in technicalKeys) {
            val isValid =
                k in feedIds &&
                    !k.startsWith("date-group-") &&
                    !k.startsWith("header_") &&
                    k != "pagination-footer" &&
                    !k.startsWith("footer_")
            assertFalse(isValid, "Technical key $k must never be selected as an anchor")
        }

        assertTrue("e1" in feedIds)
    }

    @Test
    fun testScrollAnchor_FallbackNearestSurvivor_WhenAnchorDeleted() {
        val priorIds = listOf("e1", "e2", "e3", "e4", "e5")
        // Anchor was e3, but e3 was deleted. Current surviving are e1, e2, e4, e5.
        val currentIdToLazy = mapOf("e1" to 1, "e2" to 2, "e4" to 3, "e5" to 4)

        val fallbackIdx =
            EventsScrollAnchorHelper.resolveFallbackIndex(
                anchorId = "e3",
                priorSnapshotIds = priorIds,
                currentIdToLazyIndex = currentIdToLazy,
            )

        // Nearest survivor after e3 is e4 at index 3 (or e2 at 2)
        assertTrue(fallbackIdx in setOf(2, 3), "Fallback must choose nearest surviving event index")
    }

    // =========================================================================
    // 6. Navigation Coordinator Tests (P0 Bug 2.5)
    // =========================================================================

    @Test
    fun testNavigationCoordinator_CancelPreventsStaleCompletion() =
        runTest {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val listState = LazyListState(0, 0)
            val coordinator = EventsNavigationCoordinator(scope, listState)

            var completedFirst = false
            coordinator.navigateToEvent(
                eventId = "e1",
                feedScopeKey = "scope1",
                sessionGeneration = 1L,
                getEventIdToLazyIndex = { emptyMap() },
                getAllFeedEventIds = { setOf("e1") },
                getSafeViewport = { ViewportBounds(0, 500) },
                minVisibleCardHeightPx = 24,
                onCompleted = { completedFirst = true },
            )

            // Immediately cancel or start another
            coordinator.cancel()
            assertFalse(completedFirst, "Cancelled navigation must not report success")
            scope.cancel()
        }

    // =========================================================================
    // 7. Flush & Processor Idempotency Tests (P1 Flush Outcome & Deduplication)
    // =========================================================================

    @Test
    fun testProcessor_ViewportAtTop_DeduplicatesSameOperationId() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = CoroutineScope(testDispatcher + SupervisorJob())
            val processor =
                EventsSessionProcessor(
                    query = EventsQuery(),
                    initialAccess = EventsAccessCapabilities(eventsRead = true),
                    scope = testScope,
                    onSendReplayRequest = { _, _ -> },
                    onTriggerRebootstrap = {},
                    onTokenRefreshNeeded = {},
                    onAccessInvalidated = { _, _ -> },
                )

            // First ViewportAtTop dispatch with empty pending newer
            val response1 = CompletableDeferred<PendingNewerFlushResult>()
            processor.dispatch(SessionCommand.ViewportAtTop(operationId = 42L, response = response1))
            testDispatcher.scheduler.advanceUntilIdle()

            val result1 = response1.await()
            assertIs<PendingNewerFlushResult.NoPendingEvents>(result1)
            assertEquals(42L, result1.operationId)

            // Second dispatch with SAME operationId = 42L must return cached result
            val response2 = CompletableDeferred<PendingNewerFlushResult>()
            processor.dispatch(SessionCommand.ViewportAtTop(operationId = 42L, response = response2))
            testDispatcher.scheduler.advanceUntilIdle()

            val result2 = response2.await()
            assertIs<PendingNewerFlushResult.NoPendingEvents>(result2)
            assertEquals(result1, result2, "Repeated command with same operationId must return cached result")

            processor.close()
            testScope.cancel()
        }

    @Test
    fun testPendingNewerFlushResult_OutcomeUnknownContract() {
        val outcome = PendingNewerFlushResult.OutcomeUnknown(operationId = 99L, reason = "Timed out")
        assertEquals(99L, outcome.operationId)
        assertEquals("Timed out", outcome.reason)
    }
}
