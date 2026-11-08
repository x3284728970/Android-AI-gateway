package dev.aigw.core.gateway

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActiveRequestsTest {

    @Test
    fun `begin 登记后可按快照与计数读到`() {
        val requests = ActiveRequests()
        val id1 = requests.begin("gpt-4o", "trae", "u1", "一号")
        val id2 = requests.begin("claude-3-7", "loomy", "u2", "二号")
        assertTrue(id1 != id2, "每次 begin 应生成不同 id")
        assertEquals(2, requests.count())

        val first = assertNotNull(requests.snapshot().firstOrNull { it.id == id1 })
        assertEquals("gpt-4o", first.clientModel)
        assertEquals("trae", first.providerId)
        assertEquals("u1", first.uid)
        assertEquals("一号", first.nickname)
        assertEquals(ActiveRequest.Phase.CONNECTING, first.phase)
    }

    @Test
    fun `update 到相同 phase 不重复回调`() {
        val requests = ActiveRequests()
        var callbacks = 0
        requests.addListener { callbacks++ }

        val id = requests.begin("m", "p", "u", "n")
        assertEquals(1, callbacks, "begin 应通知一次")

        requests.update(id, ActiveRequest.Phase.UPSTREAM_ANSWERED)
        assertEquals(2, callbacks)

        requests.update(id, ActiveRequest.Phase.UPSTREAM_ANSWERED)
        assertEquals(2, callbacks, "phase 未变化时不应回调")
    }

    @Test
    fun `update 与 finish 未知 id 不抛异常`() {
        val requests = ActiveRequests()
        requests.addListener { }
        requests.update("不存在", ActiveRequest.Phase.STREAMING)
        requests.finish("不存在")
        assertEquals(0, requests.count())
    }

    @Test
    fun `finish 后快照不再包含该请求且重复 finish 无副作用`() {
        val requests = ActiveRequests()
        val id = requests.begin("m", "p", "u", "n")
        requests.finish(id)
        assertEquals(0, requests.count())
        assertNull(requests.snapshot().firstOrNull { it.id == id })

        requests.finish(id)
        assertEquals(0, requests.count())
    }

    @Test
    fun `监听器能收到变更且抛异常的监听器不影响其它监听器与调用方`() {
        val requests = ActiveRequests()
        val sizes = ArrayList<Int>()
        requests.addListener { sizes.add(it.size) }
        requests.addListener { throw IllegalStateException("监听器炸了") }

        val id = requests.begin("m", "p", "u", "n")
        requests.update(id, ActiveRequest.Phase.STREAMING)
        requests.finish(id)

        assertEquals(listOf(1, 1, 0), sizes, "抛异常的监听器不应阻断后续通知")
    }

    @Test
    fun `多个请求按 startedAt 升序排列`() {
        var now = 100L
        val requests = ActiveRequests(nowMillis = { now })
        val a = requests.begin("a", "p", "u", "n")
        now = 300L
        val b = requests.begin("b", "p", "u", "n")
        now = 200L
        val c = requests.begin("c", "p", "u", "n")

        assertEquals(listOf(a, c, b), requests.snapshot().map { it.id })
    }
}
