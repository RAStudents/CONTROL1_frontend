package studio.acmenra.control.feature.events.domain.model

import kotlin.jvm.JvmInline

/** Numeric difference between the business timezone's and Moscow's UTC offsets, in seconds. */
@JvmInline
value class MskOffset(
    val totalSecondsFromMoscow: Int,
) {
    val isZero: Boolean
        get() = totalSecondsFromMoscow == 0
}
