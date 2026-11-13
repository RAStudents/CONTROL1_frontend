package studio.acmenra.control.core.ui.notice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoticeControllerTest {
    @Test
    fun testAddAndDismissNotice() {
        val controller = NoticeController()
        val notice =
            Notice(
                type = NoticeType.Info,
                title = NoticeText.Raw("Info title"),
                id = 10L,
            )

        controller.show(notice)
        assertEquals(1, controller.notices.value.size)
        assertEquals(
            10L,
            controller.notices.value
                .first()
                .id,
        )

        controller.dismiss(10L)
        assertTrue(controller.notices.value.isEmpty())
    }

    @Test
    fun testDeduplicationByKey() {
        val controller = NoticeController()
        val notice1 =
            Notice(
                type = NoticeType.Offline,
                title = NoticeText.Raw("Offline"),
                key = "offline",
            )
        val notice2 =
            Notice(
                type = NoticeType.Offline,
                title = NoticeText.Raw("Offline duplicate"),
                key = "offline",
            )

        controller.show(notice1)
        controller.show(notice2)

        assertEquals(1, controller.notices.value.size)
        assertEquals(
            "Offline",
            (
                controller.notices.value
                    .first()
                    .title as NoticeText.Raw
            ).value,
        )
    }

    @Test
    fun testMaxVisibleLimitEvictsOldestTemporary() {
        val controller = NoticeController()
        val n1 = Notice(type = NoticeType.Info, title = NoticeText.Raw("1"), durationMs = 4000L)
        val n2 = Notice(type = NoticeType.Info, title = NoticeText.Raw("2"), durationMs = 4000L)
        val n3 = Notice(type = NoticeType.Info, title = NoticeText.Raw("3"), durationMs = 4000L)
        val n4 = Notice(type = NoticeType.Info, title = NoticeText.Raw("4"), durationMs = 4000L)

        controller.show(n1)
        controller.show(n2)
        controller.show(n3)
        assertEquals(3, controller.notices.value.size)

        controller.show(n4)
        assertEquals(3, controller.notices.value.size)
        val titles = controller.notices.value.map { (it.title as NoticeText.Raw).value }
        assertEquals(listOf("2", "3", "4"), titles)
    }

    @Test
    fun testPersistentNoticeNotEvictedByTemporaryNotices() {
        val controller = NoticeController()
        val offline =
            Notice(
                type = NoticeType.Offline,
                title = NoticeText.Raw("Offline"),
                durationMs = null,
                key = "offline",
            )
        val t1 = Notice(type = NoticeType.Info, title = NoticeText.Raw("T1"), durationMs = 4000L)
        val t2 = Notice(type = NoticeType.Info, title = NoticeText.Raw("T2"), durationMs = 4000L)
        val t3 = Notice(type = NoticeType.Info, title = NoticeText.Raw("T3"), durationMs = 4000L)

        controller.show(offline)
        controller.show(t1)
        controller.show(t2)
        assertEquals(3, controller.notices.value.size)

        // Adding 4th notice: oldest temporary notice (T1) should be evicted, offline must stay
        controller.show(t3)
        assertEquals(3, controller.notices.value.size)

        val titles = controller.notices.value.map { (it.title as NoticeText.Raw).value }
        assertEquals(listOf("Offline", "T2", "T3"), titles)
        assertTrue(controller.notices.value.any { it.isPersistent })
    }

    @Test
    fun testDismissByKey() {
        val controller = NoticeController()
        val notice =
            Notice(
                type = NoticeType.Offline,
                title = NoticeText.Raw("Offline"),
                key = "offline",
            )
        controller.show(notice)
        assertEquals(1, controller.notices.value.size)

        controller.dismissKey("offline")
        assertTrue(controller.notices.value.isEmpty())
    }

    @Test
    fun testEvictOldestPersistentWhenAllArePersistent() {
        val controller = NoticeController()
        val p1 = Notice(type = NoticeType.Offline, title = NoticeText.Raw("P1"), durationMs = null)
        val p2 = Notice(type = NoticeType.Offline, title = NoticeText.Raw("P2"), durationMs = null)
        val p3 = Notice(type = NoticeType.Offline, title = NoticeText.Raw("P3"), durationMs = null)
        val p4 = Notice(type = NoticeType.Offline, title = NoticeText.Raw("P4"), durationMs = null)

        controller.show(p1)
        controller.show(p2)
        controller.show(p3)
        controller.show(p4)

        assertEquals(3, controller.notices.value.size)
        val titles = controller.notices.value.map { (it.title as NoticeText.Raw).value }
        assertEquals(listOf("P2", "P3", "P4"), titles)
    }

    @Test
    fun testAttachAndDetachHost() {
        val controller = NoticeController()
        val token1 = Any()
        val token2 = Any()

        controller.attachHost(token1)
        assertEquals(listOf(token1), controller.hosts.value)

        controller.attachHost(token2)
        assertEquals(listOf(token1, token2), controller.hosts.value)

        controller.detachHost(token1)
        assertEquals(listOf(token2), controller.hosts.value)
    }

    @Test
    fun testShowReturnsIdAndPreservesDeduplicatedId() {
        val controller = NoticeController()
        val notice1 = Notice(type = NoticeType.Info, title = NoticeText.Raw("N1"), key = "key1")
        val notice2 = Notice(type = NoticeType.Info, title = NoticeText.Raw("N2"), key = "key1")

        val id1 = controller.show(notice1)
        assertEquals(notice1.id, id1)

        val id2 = controller.show(notice2)
        assertEquals(id1, id2)
        assertEquals(1, controller.notices.value.size)
        assertEquals(
            id1,
            controller.notices.value
                .first()
                .id,
        )
    }

    @Test
    fun testDefaultIdIsPositiveAndUnique() {
        val n1 = Notice(type = NoticeType.Info, title = NoticeText.Raw("1"))
        val n2 = Notice(type = NoticeType.Info, title = NoticeText.Raw("2"))

        assertTrue(n1.id > 0L)
        assertTrue(n2.id > 0L)
        assertTrue(n1.id != n2.id)
    }
}
