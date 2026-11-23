package studio.acmenra.control.feature.events.presentation

import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_offscreen_notice_title
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.events.presentation.components.OffscreenQueueState
import studio.acmenra.control.feature.events.presentation.components.PendingNewerFlushContext
import studio.acmenra.control.feature.events.presentation.components.PendingNewerFlushReason
import studio.acmenra.control.feature.events.presentation.util.EventsScrollAnchorHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventsTopFlushAndAnchorTest {
    @Test
    fun testScrollAnchor_OffsetInversionAndFallback() {
        // Compose LazyListState offset sign inversion verification:
        // Visual offset +150px downwards from top must translate to -150px
        assertEquals(-150, EventsScrollAnchorHelper.calculateScrollOffset(150))
        assertEquals(0, EventsScrollAnchorHelper.calculateScrollOffset(0))
        assertEquals(50, EventsScrollAnchorHelper.calculateScrollOffset(-50))

        val priorSnapshot = listOf("ev-1", "ev-2", "ev-3", "ev-4", "ev-5")

        // 1. Anchor event exists in current map: exact match
        val currentMap1 = mapOf("ev-1" to 1, "ev-2" to 2, "ev-3" to 3)
        assertEquals(2, EventsScrollAnchorHelper.resolveFallbackIndex("ev-2", priorSnapshot, currentMap1))

        // 2. Anchor event "ev-3" was removed; next item "ev-4" is available
        val currentMap2 = mapOf("ev-1" to 1, "ev-4" to 3, "ev-5" to 4)
        assertEquals(3, EventsScrollAnchorHelper.resolveFallbackIndex("ev-3", priorSnapshot, currentMap2))

        // 3. Anchor event "ev-4" and following "ev-5" removed; backward search finds "ev-2"
        val currentMap3 = mapOf("ev-1" to 1, "ev-2" to 2)
        assertEquals(2, EventsScrollAnchorHelper.resolveFallbackIndex("ev-4", priorSnapshot, currentMap3))

        // 4. All items from prior snapshot removed; falls back to index 0 (sticky header)
        val currentMap4 = mapOf("ev-100" to 1, "ev-101" to 2)
        assertEquals(0, EventsScrollAnchorHelper.resolveFallbackIndex("ev-3", priorSnapshot, currentMap4))

        // 5. Anchor was null; falls back to index 0
        assertEquals(0, EventsScrollAnchorHelper.resolveFallbackIndex(null, priorSnapshot, currentMap1))
    }

    @Test
    fun testTopFlush_TransientAtTopSemantics() {
        // In TRANSIENT_AT_TOP, the viewport was already at the top when pendingNewer arrived.
        val flushContext =
            PendingNewerFlushContext(
                reason = PendingNewerFlushReason.TRANSIENT_AT_TOP,
                capturedCount = 3,
                feedGeneration = 1L,
            )
        assertEquals(PendingNewerFlushReason.TRANSIENT_AT_TOP, flushContext.reason)

        // Queue state does not keep a pending newer marker
        val queue = OffscreenQueueState()
        assertFalse(queue.hasPendingNewerMarker)
        assertEquals(0, queue.pendingNewerMarkerCount)
        assertFalse(queue.isVisible)
    }

    @Test
    fun testTopFlush_AccumulatedWhileAwaySemantics() {
        // In ACCUMULATED_WHILE_AWAY, pending items accumulated while user was away from top.
        val flushContext =
            PendingNewerFlushContext(
                reason = PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY,
                capturedCount = 5,
                feedGeneration = 2L,
            )
        assertEquals(PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY, flushContext.reason)

        var queue = OffscreenQueueState().upsertPendingNewer(5)
        assertTrue(queue.hasPendingNewerMarker)
        assertEquals(5, queue.pendingNewerMarkerCount)
        assertTrue(queue.isVisible)

        // During arrival of feed snapshot, marker is reconciled with offscreen loaded events
        // without the badge count dropping to 0
        queue =
            queue.reconcilePendingNewerWithMaterializedIds(
                materializedIds = listOf("ev-10", "ev-11", "ev-12", "ev-13", "ev-14"),
                currentlyVisibleIds = emptySet(),
            )
        assertFalse(queue.hasPendingNewerMarker)
        assertEquals(5, queue.totalCount)
        assertEquals(5, queue.loadedEventIds.size)
    }

    @Test
    fun testOffscreenLiveEventsNotice_DeduplicatedByKey() {
        // Notice key deduplication logic:
        // Identical batch key should produce a Notice with that key for NoticeController deduplication
        val key = "live_batch_12345"
        val notice =
            Notice.info(
                title = Res.string.events_offscreen_notice_title,
                key = key,
            )
        assertNotNull(notice.key)
        assertEquals(key, notice.key)
    }
}
