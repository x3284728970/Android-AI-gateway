package dev.aigw.core.failover

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConcurrencyGateTest {

    @Test
    fun `disabled gate always admits and release is a no-op`() {
        // 显式禁用（两个上限都为 0）；默认值现在是单账号串行，不再是「禁用」
        val gate = ConcurrencyGate { ConcurrencyConfig(maxPerAccount = 0, maxGlobal = 0) }
        val h1 = gate.tryAcquire("p/a")
        assertNotNull(h1)
        val h2 = gate.tryAcquire("p/a")
        assertNotNull(h2)
        // 未启用时句柄不排占位：释放/重复释放都安全
        h1.release()
        h1.release()
        h2.release()
        assertEquals(0, gate.inFlightGlobal())
    }

    @Test
    fun `默认配置按账号串行`() {
        // 默认 maxPerAccount = 1：同一账号第二条在途请求要排队而不是一起发上游
        assertEquals(1, ConcurrencyConfig().maxPerAccount)
    }

    @Test
    fun `global limit rejects beyond capacity after timeout`() {
        val gate = ConcurrencyGate { ConcurrencyConfig(maxGlobal = 1, queueTimeoutMs = 100) }
        val h1 = assertNotNull(gate.tryAcquire("p/a"))
        // 另一账号应超时被拒
        assertNull(gate.tryAcquire("p/b"))
        h1.release()
        // 释放后可再进入
        val h2 = assertNotNull(gate.tryAcquire("p/b"))
        h2.release()
        assertEquals(0, gate.inFlightGlobal())
    }

    @Test
    fun `per-account limit does not affect other accounts`() {
        val gate = ConcurrencyGate { ConcurrencyConfig(maxPerAccount = 1, queueTimeoutMs = 100) }
        val ha1 = assertNotNull(gate.tryAcquire("p/a"))
        assertNull(gate.tryAcquire("p/a"))
        val hb = assertNotNull(gate.tryAcquire("p/b"))
        ha1.release()
        hb.release()
    }

    @Test
    fun `queue succeeds when a permit is released within timeout`() {
        val gate = ConcurrencyGate { ConcurrencyConfig(maxPerAccount = 1, queueTimeoutMs = 5_000) }
        val h1 = assertNotNull(gate.tryAcquire("p/a"))
        val waiter = thread {
            Thread.sleep(150)
            h1.release()
        }
        val h2 = assertNotNull(gate.tryAcquire("p/a"))
        h2.release()
        waiter.join(2_000)
    }

    @Test
    fun `release is idempotent and account counter returns to zero`() {
        val gate = ConcurrencyGate { ConcurrencyConfig(maxPerAccount = 2, maxGlobal = 2, queueTimeoutMs = 50) }
        val h = assertNotNull(gate.tryAcquire("p/a"))
        assertEquals(1, gate.inFlightOf("p/a"))
        assertTrue(h.release())
        assertFalse(h.release())
        assertEquals(0, gate.inFlightOf("p/a"))
        assertEquals(0, gate.inFlightGlobal())
    }

    @Test
    fun `concurrent callers never exceed limits and all released handles drain to zero`() {
        val gate = ConcurrencyGate { ConcurrencyConfig(maxPerAccount = 2, maxGlobal = 3, queueTimeoutMs = 10_000) }
        val pool = Executors.newFixedThreadPool(8)
        val peakGlobal = AtomicInteger(0)
        val overshoot = AtomicInteger(0)
        val done = CountDownLatch(40)
        repeat(40) { i ->
            pool.submit {
                try {
                    val key = "p/${i % 3}"
                    val h = gate.tryAcquire(key)
                    if (h == null) {
                        overshoot.incrementAndGet()
                        return@submit
                    }
                    try {
                        val nowGlobal = gate.inFlightGlobal()
                        peakGlobal.accumulateAndGet(nowGlobal) { a, b -> maxOf(a, b) }
                        if (gate.inFlightOf(key) > 2) overshoot.incrementAndGet()
                        Thread.sleep(5)
                    } finally {
                        h.release()
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, overshoot.get(), "在途数不应超过配置上限")
        assertTrue(peakGlobal.get() in 1..3, "实测峰值应落在 1..3，实际 $peakGlobal")
        assertEquals(0, gate.inFlightGlobal())
    }

    @Test
    fun `interrupt while waiting does not corrupt global counter`() {
        val gate = ConcurrencyGate { ConcurrencyConfig(maxGlobal = 1, queueTimeoutMs = 30_000) }
        val holder = assertNotNull(gate.tryAcquire("p/a"))
        assertEquals(1, gate.inFlightGlobal())

        // 另一线程在等待全局许可时被中断：不应把计数多减（旧实现会减成 -1，等效放大上限）
        val waiter = thread(name = "gate-waiter") {
            runCatching { gate.tryAcquire("p/b") }
        }
        Thread.sleep(120)
        waiter.interrupt()
        waiter.join(2_000)

        holder.release()
        assertEquals(0, gate.inFlightGlobal(), "中断回滚不得使全局计数漂移")
        val again = assertNotNull(gate.tryAcquire("p/c"))
        again.release()
        assertEquals(0, gate.inFlightGlobal())
    }

    @Test
    fun `per-account limit with global disabled never drives global counter negative`() {
        // 回归：maxGlobal<=0（全局不限）而 maxPerAccount>0 时，账号许可路径的句柄曾无条件递减全局计数，
        // 让 inFlightGlobal() 变成负数（等效放大上限）。
        val gate = ConcurrencyGate { ConcurrencyConfig(maxPerAccount = 1, maxGlobal = 0, queueTimeoutMs = 100) }
        val h = assertNotNull(gate.tryAcquire("p/a"))
        assertEquals(1, gate.inFlightOf("p/a"))
        assertEquals(0, gate.inFlightGlobal(), "全局不限时不应占用全局计数")

        assertTrue(h.release())
        assertEquals(0, gate.inFlightOf("p/a"))
        assertEquals(0, gate.inFlightGlobal(), "释放后全局计数必须为 0，不得为负")
        assertFalse(h.release())
        assertEquals(0, gate.inFlightGlobal())
    }

    @Test
    fun `both limits enabled drain counters to zero under concurrency`() {
        val gate = ConcurrencyGate { ConcurrencyConfig(maxPerAccount = 1, maxGlobal = 1, queueTimeoutMs = 10_000) }
        val pool = Executors.newFixedThreadPool(6)
        val rejected = AtomicInteger(0)
        val done = CountDownLatch(12)
        repeat(12) {
            pool.submit {
                try {
                    val h = gate.tryAcquire("p/a")
                    if (h == null) {
                        rejected.incrementAndGet()
                        return@submit
                    }
                    try {
                        Thread.sleep(5)
                    } finally {
                        h.release()
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, rejected.get(), "10s 队列超时内不应有被拒的请求")
        assertEquals(0, gate.inFlightGlobal())
        assertEquals(0, gate.inFlightOf("p/a"))
    }

    @Test
    fun `disabled handle never changes any counter`() {
        val gate = ConcurrencyGate { ConcurrencyConfig(maxPerAccount = 0, maxGlobal = 0) }
        val h = assertNotNull(gate.tryAcquire("p/a"))
        assertEquals(0, gate.inFlightGlobal())
        assertEquals(0, gate.inFlightOf("p/a"))

        h.release()
        assertEquals(0, gate.inFlightGlobal())
        assertEquals(0, gate.inFlightOf("p/a"))
    }

    @Test
    fun `config clamps and survives json roundtrip with legacy payload`() {
        val cfg = ConcurrencyConfig.fromJson(
            com.google.gson.JsonParser.parseString(
                """{"maxPerAccount":999,"maxGlobal":-5,"queueTimeoutMs":999999}""",
            ).asJsonObject,
        )
        assertEquals(ConcurrencyConfig.MAX_PER_ACCOUNT, cfg.maxPerAccount)
        assertEquals(ConcurrencyConfig.MIN_GLOBAL, cfg.maxGlobal)
        assertEquals(ConcurrencyConfig.MAX_QUEUE_TIMEOUT_MS, cfg.queueTimeoutMs)

        val restored = ConcurrencyConfig.fromJson(cfg.toJson())
        assertEquals(cfg, restored)

        // 旧版 settings/failover.json 没有 concurrency 字段：读出默认值（不限），不得报错
        val legacy = ModelFailoverSettings.fromJson("""{"enabled":true}""")
        assertEquals(ConcurrencyConfig(), legacy.concurrency)
    }
}
