package studio.acmenra.control.feature.events.data.realtime

import studio.acmenra.control.feature.events.data.model.MAX_SAFE_JS_INTEGER

sealed interface SequenceValidationResult {
    data object InOrder : SequenceValidationResult

    data object Duplicate : SequenceValidationResult

    data class GapDetected(
        val expected: Long,
        val received: Long,
    ) : SequenceValidationResult

    data class InvalidSequence(
        val reason: String,
    ) : SequenceValidationResult
}

class TenantSequenceProcessor(
    private var lastProcessedSequence: Long = 0L,
    private var activeReplayId: Long = 1L,
) {
    val currentSequence: Long get() = lastProcessedSequence
    val currentReplayId: Long get() = activeReplayId

    fun resetSequence(
        newSequence: Long,
        newReplayId: Long = activeReplayId,
    ) {
        require(isValidSafeInteger(newSequence)) { "Sequence $newSequence exceeds safe integer bounds" }
        require(isValidSafeInteger(newReplayId)) { "ReplayId $newReplayId exceeds safe integer bounds" }
        lastProcessedSequence = newSequence
        activeReplayId = newReplayId
    }

    fun nextReplayId(): Long {
        val next = checkedAdd(activeReplayId, 1L)
        activeReplayId = next
        return next
    }

    fun checkIncomingSequence(
        incomingReplayId: Long,
        incomingSequence: Long,
    ): SequenceValidationResult {
        if (!isValidSafeInteger(incomingReplayId) || !isValidSafeInteger(incomingSequence)) {
            return SequenceValidationResult.InvalidSequence("Value exceeds MAX_SAFE_JS_INTEGER or is negative")
        }
        if (incomingReplayId != activeReplayId) {
            return SequenceValidationResult.InvalidSequence(
                "Stale replayId: expected $activeReplayId, got $incomingReplayId",
            )
        }

        val expectedNext =
            try {
                checkedAdd(lastProcessedSequence, 1L)
            } catch (e: ArithmeticException) {
                return SequenceValidationResult.InvalidSequence("Sequence overflow past MAX_SAFE_JS_INTEGER")
            }

        return when {
            incomingSequence <= lastProcessedSequence -> SequenceValidationResult.Duplicate
            incomingSequence == expectedNext -> SequenceValidationResult.InOrder
            else -> SequenceValidationResult.GapDetected(expected = expectedNext, received = incomingSequence)
        }
    }

    fun advanceSequence(appliedSequence: Long) {
        require(appliedSequence == checkedAdd(lastProcessedSequence, 1L)) {
            "Cannot advance to $appliedSequence: expected ${lastProcessedSequence + 1}"
        }
        lastProcessedSequence = appliedSequence
    }

    companion object {
        fun isValidSafeInteger(value: Long): Boolean = value in 0..MAX_SAFE_JS_INTEGER

        fun checkedAdd(
            a: Long,
            b: Long,
        ): Long {
            if (a < 0 || b < 0) throw ArithmeticException("Negative numbers not allowed in safe sequence: a=$a, b=$b")
            val sum = a + b
            if (sum < a || sum > MAX_SAFE_JS_INTEGER) {
                throw ArithmeticException("Safe integer overflow: $a + $b > $MAX_SAFE_JS_INTEGER")
            }
            return sum
        }
    }
}
