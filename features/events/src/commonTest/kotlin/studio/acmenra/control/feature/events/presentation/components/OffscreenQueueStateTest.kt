package studio.acmenra.control.feature.events.presentation.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OffscreenQueueStateTest {
    @Test
    fun testFifoOrder_LoadedEvents() {
        val q0 = OffscreenQueueState()
        assertEquals(0, q0.totalCount)
        assertFalse(q0.isVisible)

        // Enqueue batch 1
        val q1 = q0.enqueue(listOf("ev-1", "ev-2"))
        assertEquals(2, q1.totalCount)
        assertEquals(listOf("ev-1", "ev-2"), q1.loadedEventIds)

        // Enqueue batch 2: strictly FIFO appended
        val q2 = q1.enqueue(listOf("ev-3", "ev-4"))
        assertEquals(4, q2.totalCount)
        assertEquals(listOf("ev-1", "ev-2", "ev-3", "ev-4"), q2.loadedEventIds)

        // Remove head event: next in line becomes head
        val q3 = q2.removeLoaded("ev-1")
        assertEquals(3, q3.totalCount)
        assertEquals(listOf("ev-2", "ev-3", "ev-4"), q3.loadedEventIds)
    }

    @Test
    fun testActiveSetAndBatchDeduplication() {
        val q0 = OffscreenQueueState().enqueue(listOf("ev-1", "ev-2"))

        // Attempting to enqueue duplicate IDs or batch-internal duplicates
        val q1 = q0.enqueue(listOf("ev-2", "ev-3", "ev-3", "ev-1", "ev-4"))
        assertEquals(4, q1.totalCount)
        assertEquals(listOf("ev-1", "ev-2", "ev-3", "ev-4"), q1.loadedEventIds)
    }

    @Test
    fun testPendingNewerAtTop_SingletonSemantics() {
        var q = OffscreenQueueState()
        assertFalse(q.hasPendingNewerMarker)
        assertEquals(0, q.pendingNewerMarkerCount)

        // Upsert count = 3
        q = q.upsertPendingNewer(3)
        assertTrue(q.hasPendingNewerMarker)
        assertEquals(3, q.pendingNewerMarkerCount)
        assertEquals(1, q.entries.size)

        // Upsert count = 7: must update in-place, staying exactly 1 element
        q = q.upsertPendingNewer(7)
        assertTrue(q.hasPendingNewerMarker)
        assertEquals(7, q.pendingNewerMarkerCount)
        assertEquals(1, q.entries.size)

        // Upsert count = 0: removes marker
        q = q.upsertPendingNewer(0)
        assertFalse(q.hasPendingNewerMarker)
        assertEquals(0, q.pendingNewerMarkerCount)
        assertEquals(0, q.entries.size)
    }

    @Test
    fun testPendingHistory_SingletonSemantics() {
        var q = OffscreenQueueState()
        assertFalse(q.hasPendingHistoryMarker)

        // Valid conditions: count > 0, requiresRefresh = true, hasMore = true
        q = q.upsertPendingHistory(count = 5, requiresRefresh = true, hasMore = true)
        assertTrue(q.hasPendingHistoryMarker)
        assertEquals(1, q.entries.size)
        assertEquals(5, q.totalCount)

        // Update count: stays singleton at tail
        q = q.upsertPendingHistory(count = 12, requiresRefresh = true, hasMore = true)
        assertTrue(q.hasPendingHistoryMarker)
        assertEquals(1, q.entries.size)
        assertEquals(12, q.totalCount)

        // Invalid conditions: requiresRefresh = false -> removed
        q = q.upsertPendingHistory(count = 12, requiresRefresh = false, hasMore = true)
        assertFalse(q.hasPendingHistoryMarker)
        assertEquals(0, q.totalCount)

        // Invalid conditions: hasMore = false -> removed
        q = q.upsertPendingHistory(count = 5, requiresRefresh = true, hasMore = false)
        assertFalse(q.hasPendingHistoryMarker)
        assertEquals(0, q.totalCount)

        // Invalid conditions: count = 0 -> removed
        q = q.upsertPendingHistory(count = 0, requiresRefresh = true, hasMore = true)
        assertFalse(q.hasPendingHistoryMarker)
        assertEquals(0, q.totalCount)
    }

    @Test
    fun testReconcilePendingNewerWithMaterializedIds_NoBadgeDropToZero() {
        var q = OffscreenQueueState()
        q = q.upsertPendingNewer(2)
        assertEquals(2, q.totalCount)

        // When feed delivers the 2 items ("ev-101", "ev-102") and viewport is not yet at top (neither is visible)
        val visibleIds = setOf("ev-1", "ev-2", "ev-3")
        q =
            q.reconcilePendingNewerWithMaterializedIds(
                materializedIds = listOf("ev-101", "ev-102"),
                currentlyVisibleIds = visibleIds,
            )

        // Marker is replaced with the 2 offscreen loaded events atomically: count never dropped to 0
        assertFalse(q.hasPendingNewerMarker)
        assertEquals(2, q.totalCount)
        assertEquals(listOf("ev-101", "ev-102"), q.loadedEventIds)

        // If one of the materialized events was already within viewport, only the offscreen one is enqueued
        var q2 = OffscreenQueueState().upsertPendingNewer(2)
        q2 =
            q2.reconcilePendingNewerWithMaterializedIds(
                materializedIds = listOf("ev-101", "ev-102"),
                currentlyVisibleIds = setOf("ev-101"),
            )
        assertEquals(1, q2.totalCount)
        assertEquals(listOf("ev-102"), q2.loadedEventIds)
    }

    @Test
    fun testComputeHeadTarget_LazySpaceAndPruning() {
        // Layout:
        // Lazy index 0: Sticky Header Date 1
        // Lazy index 1: "ev-1" (above viewport)
        // Lazy index 2: "ev-2" (visible)
        // Lazy index 3: "ev-3" (visible)
        // Lazy index 4: Sticky Header Date 2
        // Lazy index 5: "ev-4" (below viewport)
        val idToLazyIndex =
            mapOf(
                "ev-1" to 1,
                "ev-2" to 2,
                "ev-3" to 3,
                "ev-4" to 5,
            )
        val minVisibleLazy = 2
        val maxVisibleLazy = 3

        // 1. Target is above viewport: returns UP target
        val qUp = OffscreenQueueState().enqueue(listOf("ev-1"))
        val (qUpNext, targetUp) = qUp.computeHeadTarget(minVisibleLazy, maxVisibleLazy, idToLazyIndex)
        assertNotNull(targetUp)
        assertTrue(targetUp is OffscreenJumpTarget.LoadedEvent)
        assertEquals("ev-1", targetUp.eventId)
        assertEquals(1, targetUp.lazyItemIndex)
        assertEquals(OffscreenDirection.UP, targetUp.direction)
        assertEquals(1, qUpNext.totalCount)

        // 2. Target is below viewport: returns DOWN target
        val qDown = OffscreenQueueState().enqueue(listOf("ev-4"))
        val (qDownNext, targetDown) = qDown.computeHeadTarget(minVisibleLazy, maxVisibleLazy, idToLazyIndex)
        assertNotNull(targetDown)
        assertTrue(targetDown is OffscreenJumpTarget.LoadedEvent)
        assertEquals("ev-4", targetDown.eventId)
        assertEquals(5, targetDown.lazyItemIndex)
        assertEquals(OffscreenDirection.DOWN, targetDown.direction)
        assertEquals(1, qDownNext.totalCount)

        // 3. Head target is already in viewport: auto-prunes head and evaluates next
        val qPrune = OffscreenQueueState().enqueue(listOf("ev-2", "ev-4"))
        val (qPruned, targetAfterPrune) = qPrune.computeHeadTarget(minVisibleLazy, maxVisibleLazy, idToLazyIndex)
        assertEquals(1, qPruned.totalCount)
        assertEquals(listOf("ev-4"), qPruned.loadedEventIds)
        assertNotNull(targetAfterPrune)
        assertTrue(targetAfterPrune is OffscreenJumpTarget.LoadedEvent)
        assertEquals("ev-4", targetAfterPrune.eventId)

        // 4. PendingNewerAtTop target: always heads to top
        val qNewer = OffscreenQueueState().upsertPendingNewer(3)
        val (_, targetNewer) = qNewer.computeHeadTarget(minVisibleLazy, maxVisibleLazy, idToLazyIndex)
        assertEquals(OffscreenJumpTarget.PendingNewerAtTop, targetNewer)

        // 5. PendingHistory target: signals refresh
        val qHistory = OffscreenQueueState().upsertPendingHistory(5, requiresRefresh = true, hasMore = true)
        val (_, targetHistory) = qHistory.computeHeadTarget(minVisibleLazy, maxVisibleLazy, idToLazyIndex)
        assertEquals(OffscreenJumpTarget.PendingHistoryRefresh, targetHistory)

        // 6. Target not yet measured / index missing in layout: hold queue and return null
        val qUnmeasured = OffscreenQueueState().enqueue(listOf("ev-unmeasured"))
        val (qUnmeasuredHeld, targetUnmeasured) =
            qUnmeasured.computeHeadTarget(
                minVisibleLazy,
                maxVisibleLazy,
                idToLazyIndex,
            )
        assertNull(targetUnmeasured)
        assertEquals(1, qUnmeasuredHeld.totalCount)
    }
}
