package studio.acmenra.control.feature.events.data

import studio.acmenra.control.feature.events.data.realtime.SequenceValidationResult
import studio.acmenra.control.feature.events.data.realtime.TenantSequenceProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TenantSequenceProcessorTest {
    @Test
    fun testInitialSequenceAndInOrderAdvance() {
        val processor = TenantSequenceProcessor()
        processor.resetSequence(100L)
        assertEquals(100L, processor.currentSequence)

        val check101 = processor.checkIncomingSequence(incomingReplayId = 1L, incomingSequence = 101L)
        assertEquals(SequenceValidationResult.InOrder, check101)

        processor.advanceSequence(101L)
        assertEquals(101L, processor.currentSequence)
    }

    @Test
    fun testDuplicateSequence() {
        val processor = TenantSequenceProcessor()
        processor.resetSequence(100L)

        val check100 = processor.checkIncomingSequence(incomingReplayId = 1L, incomingSequence = 100L)
        assertEquals(SequenceValidationResult.Duplicate, check100)

        val check99 = processor.checkIncomingSequence(incomingReplayId = 1L, incomingSequence = 99L)
        assertEquals(SequenceValidationResult.Duplicate, check99)
    }

    @Test
    fun testGapDetected() {
        val processor = TenantSequenceProcessor()
        processor.resetSequence(100L)

        val check105 = processor.checkIncomingSequence(incomingReplayId = 1L, incomingSequence = 105L)
        assertTrue(check105 is SequenceValidationResult.GapDetected)
        assertEquals(101L, check105.expected)
        assertEquals(105L, check105.received)
    }

    @Test
    fun testReplayIdIncrement() {
        val processor = TenantSequenceProcessor()
        val r1 = processor.currentReplayId
        val r2 = processor.nextReplayId()
        assertEquals(r1 + 1, r2)
        assertEquals(r2, processor.currentReplayId)
    }

    @Test
    fun testSafe53BitBoundary() {
        val processor = TenantSequenceProcessor()
        val maxSafe = 9_007_199_254_740_991L
        processor.resetSequence(maxSafe - 1)

        val inOrder = processor.checkIncomingSequence(incomingReplayId = 1L, incomingSequence = maxSafe)
        assertEquals(SequenceValidationResult.InOrder, inOrder)

        val overflow = processor.checkIncomingSequence(incomingReplayId = 1L, incomingSequence = maxSafe + 1)
        assertTrue(overflow is SequenceValidationResult.InvalidSequence)
    }
}
