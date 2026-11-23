package studio.acmenra.control.feature.events.presentation.util

import androidx.compose.ui.input.key.Key
import studio.acmenra.control.feature.events.domain.model.Event

/**
 * Pure helper for Events list keyboard navigation (§9).
 */
object EventsKeyboardNavigationHelper {
    /**
     * Handles keyboard events for Up/Down/Escape navigation:
     * - KeyDown only; all modifier keys (Ctrl, Alt, Meta) are ignored.
     * - Down: moves selection from N to N+1; when at last item, retains selection and returns true.
     * - Up: moves selection from N to N-1; when at first item, retains selection and returns true.
     * - Escape: deselects active item in Wide/Medium modes (!isCompact).
     */
    fun handleNavigationKey(
        key: Key,
        isCtrlPressed: Boolean,
        isAltPressed: Boolean,
        isMetaPressed: Boolean,
        isKeyDown: Boolean,
        events: List<Event>,
        selectedEventId: String?,
        isCompact: Boolean,
        onSelectEvent: (String?) -> Unit,
        isShiftPressed: Boolean = false,
    ): Boolean {
        if (!isKeyDown || isCtrlPressed || isAltPressed || isMetaPressed || isShiftPressed) {
            return false
        }
        return when (key) {
            Key.DirectionDown -> {
                if (events.isEmpty()) {
                    false
                } else if (selectedEventId == null || events.none { it.id == selectedEventId }) {
                    onSelectEvent(events.first().id)
                    true
                } else {
                    val idx = events.indexOfFirst { it.id == selectedEventId }
                    if (idx < events.lastIndex) {
                        onSelectEvent(events[idx + 1].id)
                    }
                    true
                }
            }
            Key.DirectionUp -> {
                if (events.isEmpty() || selectedEventId == null || events.none { it.id == selectedEventId }) {
                    false
                } else {
                    val idx = events.indexOfFirst { it.id == selectedEventId }
                    if (idx > 0) {
                        onSelectEvent(events[idx - 1].id)
                    }
                    true
                }
            }
            Key.Escape -> {
                if (!isCompact && selectedEventId != null) {
                    onSelectEvent(null)
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    /**
     * Calculates the pixel delta needed to scroll an item into the safe viewport area (§9).
     *
     * @param itemTop Top offset of the item in LazyColumn viewport coordinates.
     * @param itemBottom Bottom offset of the item in LazyColumn viewport coordinates.
     * @param safeTopOffset Top boundary below any sticky date headers and safety margins.
     * @param safeBottomOffset Bottom boundary above any navigation bars and content padding.
     * @return Negative delta to scroll content downwards (bring top into view),
     *         positive delta to scroll content upwards (bring bottom into view),
     *         or 0f if the item is already within safe viewport bounds.
     */
    fun calculateScrollAdjustment(
        itemTop: Int,
        itemBottom: Int,
        safeTopOffset: Int,
        safeBottomOffset: Int,
    ): Float {
        val itemHeight = itemBottom - itemTop
        val safeHeight = safeBottomOffset - safeTopOffset
        return when {
            itemHeight > safeHeight -> {
                if (itemTop != safeTopOffset) {
                    (itemTop - safeTopOffset).toFloat()
                } else {
                    0f
                }
            }
            itemTop < safeTopOffset -> (itemTop - safeTopOffset).toFloat()
            itemBottom > safeBottomOffset -> (itemBottom - safeBottomOffset).toFloat()
            else -> 0f
        }
    }
}
