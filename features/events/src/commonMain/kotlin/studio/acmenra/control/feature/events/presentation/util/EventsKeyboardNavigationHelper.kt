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
}
