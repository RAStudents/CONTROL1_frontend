package studio.acmenra.control.navigation

import studio.acmenra.control.core.access.domain.model.SupportGrantInfo
import studio.acmenra.control.core.access.domain.model.SupportGrantRuntimeState
import studio.acmenra.control.core.access.domain.model.SupportGrantStatus
import studio.acmenra.control.core.access.domain.model.SupportMode
import studio.acmenra.control.navigation.components.padZero
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

class SupportOverrideBannerTest {
    private fun createGrant(
        mode: SupportMode = SupportMode.READ_ONLY_SUPPORT,
        ticketReference: String? = "INC-1234",
        remainingTtlSec: Long = 3665L,
        runtimeState: SupportGrantRuntimeState = SupportGrantRuntimeState.Active,
    ): SupportGrantInfo =
        SupportGrantInfo(
            grantId = "g-1",
            status = SupportGrantStatus.ACTIVE,
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
    fun testSupportModes() {
        val readOnly = createGrant(mode = SupportMode.READ_ONLY_SUPPORT)
        val engineering = createGrant(mode = SupportMode.ENGINEERING_SUPPORT)
        val unknown = createGrant(mode = SupportMode.UNKNOWN)

        assertEquals(SupportMode.READ_ONLY_SUPPORT, readOnly.mode)
        assertEquals(SupportMode.ENGINEERING_SUPPORT, engineering.mode)
        assertEquals(SupportMode.UNKNOWN, unknown.mode)
    }

    @Test
    fun testTicketReference() {
        val withTicket = createGrant(ticketReference = "SD-777")
        val withoutTicket = createGrant(ticketReference = null)

        assertEquals("SD-777", withTicket.ticketReference)
        assertEquals(null, withoutTicket.ticketReference)
    }
}
