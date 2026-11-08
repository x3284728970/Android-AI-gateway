package dev.aigw.core.gateway

import com.google.gson.JsonObject
import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 批量动作有界并发的回归。
 *
 * 背景：批量签到 / 批量任务原先是逐个账号串行 + 每个账号动作成功后立刻补刷额度，
 * 多账号下既慢又对上游打了两遍。这里锁住新语义：
 * - 并发度有上限（默认 3，避免多账号齐发触发风控）；
 * - 每个账号只执行一次动作；
 * - 单账号失败隔离，结果结构化区分；
 * - 批量动作后不再「每账号立刻」打额度接口，而是在动作全部结束后统一刷一轮；
 * - 并发版 refreshAllCredits 与串行版结果集合一致。
 */
class BatchActionConcurrencyTest {

    /**
     * 假 Provider：
     * - [actionCalls] 记录每个 uid 的动作次数；
     * - [maxConcurrency] 记录动作同时执行过的峰值线程数（验证并发上限）；
     * - [creditCalls] 记录 creditInfo 调用次数；
     * - [creditCallsBeforeAllActions] 记录「动作还没全部结束时就发生的额度刷新」次数
     *   （>0 即说明还在按账号立刻补刷）。
     */
    private class BatchProvider(
        override val id: String = "batch",
        private val failUids: Set<String> = emptySet(),
        private val throwUids: Set<String> = emptySet(),
        private val unsupportedUids: Set<String> = emptySet(),
    ) : Provider {
        override val displayName = "批量测试"
        override val authKind = AuthKind.NONE

        private val activeActions = AtomicInteger(0)
        val maxConcurrency = AtomicInteger(0)
        val actionCalls = ConcurrentHashMap<String, AtomicInteger>()
        val creditCalls = AtomicInteger(0)
        val creditCallsBeforeAllActions = AtomicInteger(0)

        /** 已完成动作个数，供 creditInfo 判断是否还在动作阶段。 */
        val completedActions = AtomicInteger(0)

        /** 动作总目标数：creditInfo 若在其全部完成前被调用，说明存在「每账号立刻补刷」。 */
        @Volatile
        var expectedActions: Int = 0

        /** 动作执行时持有的闸门，便于在测试里制造并发重叠。 */
        @Volatile
        var actionGate: CountDownLatch? = null

        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            throw UnsupportedOperationException()

        override fun performAction(
            account: ProviderAccount,
            action: String,
            payload: JsonObject,
        ): ProviderActionResult {
            actionCalls.getOrPut(account.uid) { AtomicInteger(0) }.incrementAndGet()
            val now = activeActions.incrementAndGet()
            // 记并发峰值（CAS 循环，避免丢更新）
            while (true) {
                val prev = maxConcurrency.get()
                if (now <= prev || maxConcurrency.compareAndSet(prev, now)) break
            }
            try {
                actionGate?.await(5, TimeUnit.SECONDS)
                if (account.uid in throwUids) throw RuntimeException("上游炸了")
                if (account.uid in unsupportedUids) return ProviderActionResult.unsupported("tasks")
                if (account.uid in failUids) return ProviderActionResult.failure("签到被拒")
                return ProviderActionResult.success("签到成功")
            } finally {
                activeActions.decrementAndGet()
                completedActions.incrementAndGet()
            }
        }

        override fun creditInfo(account: ProviderAccount): CreditInfo {
            creditCalls.incrementAndGet()
            // 有任何一个账号的动作还没跑完时就刷额度 = 旧「每账号立刻补刷」的坏味道。
            if (completedActions.get() < expectedActions) creditCallsBeforeAllActions.incrementAndGet()
            return CreditInfo(100, true, "测试额度")
        }
    }

    private fun engineWith(provider: BatchProvider): GatewayEngine {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(provider)
        return engine
    }

    private fun targets(vararg uids: String): List<BatchTarget> = uids.map { BatchTarget("batch", it) }

    @Test
    fun `并发度不超过上限`() {
        val provider = BatchProvider()
        val engine = engineWith(provider)
        (1..6).forEach { engine.pool.upsert(ProviderAccount("batch", "u$it", "账号$it", "{}")) }
        provider.expectedActions = 6
        // 闸门先关住：让先启动的任务全部挤在动作里等待，才能观察到真正的并发峰值
        provider.actionGate = CountDownLatch(1)

        val worker = Thread {
            engine.performActionAll(targets("u1", "u2", "u3", "u4", "u5", "u6"), "checkin", parallelism = 3)
        }
        worker.start()

        // 等并发峰值涨到 3（上限）后放行
        val deadline = System.currentTimeMillis() + 5_000
        while (provider.maxConcurrency.get() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(3, provider.maxConcurrency.get(), "并发度应恰好达到上限 3")
        provider.actionGate?.countDown()
        worker.join(5_000)

        assertTrue(provider.maxConcurrency.get() <= 3, "并发度不得超过上限，实际 ${provider.maxConcurrency.get()}")
        assertEquals(6, provider.actionCalls.values.sumOf { it.get() })
    }

    @Test
    fun `每个账号恰好执行一次动作`() {
        val provider = BatchProvider()
        val engine = engineWith(provider)
        engine.pool.upsert(ProviderAccount("batch", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("batch", "u2", "账号2", "{}"))
        provider.expectedActions = 2

        // 故意塞入重复目标：去重后每个账号仍只执行一次
        val results = engine.performActionAll(
            targets("u1", "u2", "u1", "u2", "u1"),
            "checkin",
        )

        assertEquals(2, results.size, "重复目标应被去重")
        assertEquals(1, provider.actionCalls["u1"]?.get(), "u1 应只执行一次")
        assertEquals(1, provider.actionCalls["u2"]?.get(), "u2 应只执行一次")
    }

    @Test
    fun `单账号失败不影响其它账号且结果可区分`() {
        val provider = BatchProvider(failUids = setOf("u2"), throwUids = setOf("u3"))
        val engine = engineWith(provider)
        (1..4).forEach { engine.pool.upsert(ProviderAccount("batch", "u$it", "账号$it", "{}")) }
        provider.expectedActions = 4

        val results = engine.performActionAll(targets("u1", "u2", "u3", "u4"), "checkin")

        assertEquals(4, results.size)
        assertTrue(results.single { it.uid == "u1" }.ok)
        assertTrue(results.single { it.uid == "u4" }.ok)
        // 业务失败：带回上游原话
        val failed = results.single { it.uid == "u2" }
        assertEquals(false, failed.ok)
        assertEquals("签到被拒", failed.message)
        // 抛异常：就地转成失败，不拖垮整批
        val thrown = results.single { it.uid == "u3" }
        assertEquals(false, thrown.ok)
        assertTrue(thrown.message.contains("上游炸了"), thrown.message)
        // 其它账号照常执行
        assertEquals(4, provider.actionCalls.values.count { it.get() == 1 })
    }

    @Test
    fun `动作结束后才统一刷额度而非每账号立刻补刷`() {
        val provider = BatchProvider()
        val engine = engineWith(provider)
        (1..3).forEach { engine.pool.upsert(ProviderAccount("batch", "u$it", "账号$it", "{}")) }
        provider.expectedActions = 3

        val results = engine.performActionAll(targets("u1", "u2", "u3"), "checkin")
        // 让统一刷额度这一轮跑完（并行执行，稍等即可）
        val deadline = System.currentTimeMillis() + 5_000
        while (provider.creditCalls.get() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertTrue(results.all { it.ok })
        assertEquals(0, provider.creditCallsBeforeAllActions.get(), "动作阶段不应有任何额度刷新（旧的每账号立刻补刷）")
        assertEquals(3, provider.creditCalls.get(), "动作全部结束后应统一刷一轮：每个成功账号恰好一次")
    }

    @Test
    fun `并发版刷新额度与串行版结果一致`() {
        val provider = BatchProvider()
        val engine = engineWith(provider)
        (1..5).forEach { engine.pool.upsert(ProviderAccount("batch", "u$it", "账号$it", "{}")) }

        val serial = engine.refreshAllCredits("batch", parallelism = 1)
        val parallel = engine.refreshAllCredits("batch", parallelism = 3)

        // 集合相等（顺序也应一致：按下标回填）
        assertEquals(serial.size, parallel.size)
        assertEquals(serial.map { Triple(it.providerId, it.uid, it.balance) }, parallel.map { Triple(it.providerId, it.uid, it.balance) })
        assertEquals(serial.map { it.error }, parallel.map { it.error })
        // 5 个账号两轮共 10 次额度查询，无重复无遗漏
        assertEquals(5, serial.size)
        assertEquals(10, provider.creditCalls.get())
    }

    @Test
    fun `批量动作回传进度且计数单调到 N`() {
        val provider = BatchProvider()
        val engine = engineWith(provider)
        (1..3).forEach { engine.pool.upsert(ProviderAccount("batch", "u$it", "账号$it", "{}")) }
        provider.expectedActions = 3

        val progress = CopyOnWriteArrayList<BatchProgress>()
        engine.performActionAll(targets("u1", "u2", "u3"), "checkin", onProgress = { progress.add(it) })

        assertEquals(3, progress.size, "每个账号完成时应回调一次进度")
        assertEquals(setOf(1, 2, 3), progress.map { it.done }.toSet(), "已完成数应从 1 递增到 N")
        assertTrue(progress.all { it.total == 3 })
        assertTrue(progress.all { it.providerId == "batch" && it.nickname.isNotEmpty() })
    }

    @Test
    fun `供应商不支持的动作不计入失败`() {
        // 「该供应商不支持」是选目标时就应该排掉的，不该出现在失败面板里刷屏
        val provider = BatchProvider(unsupportedUids = setOf("u2"))
        val engine = engineWith(provider)
        (1..3).forEach { engine.pool.upsert(ProviderAccount("batch", "u$it", "账号$it", "{}")) }
        provider.expectedActions = 3

        val results = engine.performActionAll(targets("u1", "u2", "u3"), "tasks")
        assertEquals(3, results.size)
        assertTrue(results.all { it.ok }, "不支持不能算失败：${results.filter { !it.ok }}")
        assertTrue(results.none { it.message.contains("不支持") }, "不应把不支持文案带回界面")
    }
}
