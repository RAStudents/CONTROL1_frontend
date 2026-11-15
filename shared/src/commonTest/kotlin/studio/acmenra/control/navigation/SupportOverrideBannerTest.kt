package studio.acmenra.control.navigation

import studio.acmenra.control.core.access.domain.model.SupportGrantInfo
import studio.acmenra.control.core.access.domain.model.SupportGrantRuntimeState
import studio.acmenra.control.core.access.domain.model.SupportGrantStatus
import studio.acmenra.control.core.access.domain.model.SupportMode
import studio.acmenra.control.navigation.components.formatRemainingDuration
import studio.acmenra.control.navigation.components.padZero
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SupportOverrideBannerTest {
    private fun createGrant(
        mode: SupportMode = SupportMode.READ_ONLY_SUPPORT,
        ticketReference: String? = "INC-1234",
        remainingTtlSec: Long = 3665L,
        runtimeState: SupportGrantRuntimeState = SupportGrantRuntimeState.Active,
        status: SupportGrantStatus = SupportGrantStatus.ACTIVE,
    ): SupportGrantInfo =
        SupportGrantInfo(
            grantId = "g-1",
            status = status,
            mode = mode,
            ticketReference = ticketReference,
            allowBillingOverride = false,
            grantedByUserId = "admin",
            grantedAt = Instant.fromEpochMilliseconds(0),
            expiresAt = Instant.fromEpochMilliseconds(100000),
            remainingTtlSec = remainingTtlSec,
            revokedAt = null,
            runtimeState = runtimeState,
        )

    @Test
    fun testPadZeroFormatting() {
        assertEquals("00", 0L.padZero())
        assertEquals("05", 5L.padZero())
        assertEquals("12", 12L.padZero())
        assertEquals("59", 59L.padZero())
    }

    @Test
    fun testFormatRemainingDurationFormatting() {
        assertEquals("00:00", formatRemainingDuration(Duration.ZERO))
        assertEquals("00:00", formatRemainingDuration((-15).seconds))
        assertEquals("00:09", formatRemainingDuration(9.seconds))
        assertEquals("00:59", formatRemainingDuration(59.seconds))
        assertEquals("01:00", formatRemainingDuration(60.seconds))
        assertEquals("05:30", formatRemainingDuration(5.minutes + 30.seconds))
        assertEquals("01:00:00", formatRemainingDuration(1.hours))
        assertEquals("01:05:09", formatRemainingDuration(1.hours + 5.minutes + 9.seconds))
        assertEquals("12:34:56", formatRemainingDuration(12.hours + 34.minutes + 56.seconds))
    }

    @Test
    fun testRemainingDurationCalculationForActiveGrant() {
        val grant = createGrant(remainingTtlSec = 3600L)
        val remaining = grant.remainingDurationNow()
        assertTrue(remaining > Duration.ZERO)
        assertTrue(remaining.inWholeSeconds <= 3600L)
        assertFalse(grant.isExpiredNow())
    }

    @Test
    fun testRemainingDurationCalculationForExpiredGrant() {
        val grant = createGrant(remainingTtlSec = 0L)
        assertEquals(Duration.ZERO, grant.remainingDurationNow())
        assertTrue(grant.isExpiredNow())
    }

    @Test
    fun testRemainingDurationWhenLocallyExpired() {
        val grant = createGrant(runtimeState = SupportGrantRuntimeState.ExpiredLocally)
        assertEquals(Duration.ZERO, grant.remainingDurationNow())
        assertTrue(grant.isExpiredNow())
    }

    @Test
    fun testRemainingDurationWhenRevoked() {
        val grant = createGrant(status = SupportGrantStatus.REVOKED)
        assertEquals(Duration.ZERO, grant.remainingDurationNow())
        assertTrue(grant.isExpiredNow())
    }

    @Test
    fun testRemainingDurationWhenStatusExpired() {
        val grant = createGrant(status = SupportGrantStatus.EXPIRED)
        assertEquals(Duration.ZERO, grant.remainingDurationNow())
        assertTrue(grant.isExpiredNow())
    }
}
