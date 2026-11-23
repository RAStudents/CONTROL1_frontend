package studio.acmenra.control.feature.events.presentation.util

/**
 * Pure helper for scroll anchor preservation (§7, §11, C.3).
 */
object EventsScrollAnchorHelper {
    /**
     * Inverts the item offset sign for Compose LazyListState.
     * In Compose LazyListState.scrollToItem(index, scrollOffset), a positive scrollOffset scrolls
     * the item upwards (out of view). To position an item at visible distance +itemOffsetPx
     * from the viewport top edge, scrollOffset must be -itemOffsetPx.
     */
    fun calculateScrollOffset(itemOffsetPx: Int): Int = -itemOffsetPx

    /**
     * Resolves the target Lazy item index using anchor preservation fallback rules:
     * 1. If anchorId is present in currentIdToLazyIndex, return its index.
     * 2. If anchorId was removed/evicted, look forward in the prior snapshot for the nearest available item.
     * 3. If no forward item is present, look backward in the prior snapshot for the nearest available item.
     * 4. If none found, fallback to index 0 (top header).
     */
    fun resolveFallbackIndex(
        anchorId: String?,
        priorSnapshotIds: List<String>,
        currentIdToLazyIndex: Map<String, Int>,
    ): Int {
        if (anchorId != null && currentIdToLazyIndex.containsKey(anchorId)) {
            return currentIdToLazyIndex.getValue(anchorId)
        }
        val anchorIdx = priorSnapshotIds.indexOf(anchorId)
        if (anchorIdx != -1) {
            // Forward search
            for (i in (anchorIdx + 1)..priorSnapshotIds.lastIndex) {
                val nextId = priorSnapshotIds[i]
                if (currentIdToLazyIndex.containsKey(nextId)) {
                    return currentIdToLazyIndex.getValue(nextId)
                }
            }
            // Backward search
            for (i in (anchorIdx - 1) downTo 0) {
                val prevId = priorSnapshotIds[i]
                if (currentIdToLazyIndex.containsKey(prevId)) {
                    return currentIdToLazyIndex.getValue(prevId)
                }
            }
        }
        return 0
    }
}
