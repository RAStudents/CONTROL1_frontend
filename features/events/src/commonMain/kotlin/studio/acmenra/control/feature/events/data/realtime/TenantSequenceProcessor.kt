package studio.acmenra.control.feature.events.data.realtime

import studio.acmenra.control.feature.events.data.model.MAX_SAFE_JS_INTEGER

sealed interface SequenceValidationResult {
    data object InOrder : SequenceValidationResult

    data object Duplicate : SequenceValidationResult

    data class IntegrityViolation(
        val reason: String,
    ) : SequenceValidationResult

    data class GapDetected(
        val expected: Long,
        val received: Long,
    ) : SequenceValidationResult

    data class InvalidSequence(
        val reason: String,
    ) : SequenceValidationResult
}

sealed interface ReplayGenerationResult {
    data class Next(
        val replayId: Long,
    ) : ReplayGenerationResult

    data class RequiresFreshAuth(
        val initialReplayId: Long = 1L,
    ) : ReplayGenerationResult
}

class TenantSequenceProcessor(
    private var lastProcessedSequence: Long = 0L,
    private var activeReplayId: Long = 1L,
) {
    val currentSequence: Long get() = lastProcessedSequence
    val currentReplayId: Long get() = activeReplayId

    private val maxRecentHashes = 1000
    private val recentHashes = LinkedHashMap<Long, String>()

    private fun recordHash(
        sequence: Long,
        hash: String,
    ) {
        val normalized = hash.lowercase()
        recentHashes.remove(sequence)
        recentHashes[sequence] = normalized
        if (recentHashes.size > maxRecentHashes) {
            val iterator = recentHashes.keys.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    fun startReplay(
        replayId: Long,
        afterSequence: Long,
    ) {
        require(isValidSafeInteger(replayId)) { "ReplayId $replayId exceeds safe integer bounds" }
        require(isValidSafeInteger(afterSequence)) { "Sequence $afterSequence exceeds safe integer bounds" }
        activeReplayId = replayId
        lastProcessedSequence = afterSequence
        recentHashes.clear()
    }

    fun resetSequence(
        newSequence: Long,
        newReplayId: Long = activeReplayId,
    ) {
        require(isValidSafeInteger(newSequence)) { "Sequence $newSequence exceeds safe integer bounds" }
        require(isValidSafeInteger(newReplayId)) { "ReplayId $newReplayId exceeds safe integer bounds" }
        lastProcessedSequence = newSequence
        activeReplayId = newReplayId
        recentHashes.clear()
    }

    fun tryNextReplayGeneration(): ReplayGenerationResult =
        if (activeReplayId >= MAX_SAFE_JS_INTEGER) {
            ReplayGenerationResult.RequiresFreshAuth(1L)
        } else {
            val next = activeReplayId + 1L
            activeReplayId = next
            ReplayGenerationResult.Next(next)
        }

    fun nextReplayGeneration(): Long =
        when (val res = tryNextReplayGeneration()) {
            is ReplayGenerationResult.Next -> res.replayId
            is ReplayGenerationResult.RequiresFreshAuth ->
                throw ArithmeticException("Safe integer overflow: replayId $activeReplayId >= $MAX_SAFE_JS_INTEGER")
        }

    fun nextReplayId(): Long = nextReplayGeneration()

    fun checkIncomingSequence(
        incomingReplayId: Long,
        incomingSequence: Long,
        payloadSha256: String? = null,
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
            incomingSequence <= lastProcessedSequence -> {
                if (payloadSha256 != null && recentHashes.containsKey(incomingSequence)) {
                    val existingHash = recentHashes[incomingSequence]
                    if (existingHash != null && existingHash.lowercase() != payloadSha256.lowercase()) {
                        return SequenceValidationResult.IntegrityViolation(
                            "Payload SHA256 mismatch for sequence $incomingSequence: expected $existingHash, got $payloadSha256",
                        )
                    }
                }
                SequenceValidationResult.Duplicate
            }
            incomingSequence == expectedNext -> SequenceValidationResult.InOrder
            else -> SequenceValidationResult.GapDetected(expected = expectedNext, received = incomingSequence)
        }
    }

    fun advanceSequence(
        appliedSequence: Long,
        payloadSha256: String? = null,
    ) {
        require(appliedSequence == checkedAdd(lastProcessedSequence, 1L)) {
            "Cannot advance to $appliedSequence: expected ${lastProcessedSequence + 1}"
        }
        lastProcessedSequence = appliedSequence
        if (payloadSha256 != null) {
            recordHash(appliedSequence, payloadSha256)
        }
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
