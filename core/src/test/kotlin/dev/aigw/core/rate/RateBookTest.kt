package dev.aigw.core.rate

import dev.aigw.core.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 倍率台账：解析、分层覆盖、持久化与节流。 */
class RateBookTest {

    private val table = """
        {
          "version": 1,
          "updated": "2026-11-26",
          "providers": {
            "trae": {
              "glm-5.3": {"base": 0.78, "discounted": 0.39, "tag": "会员5折", "source": "官方", "note": "docs.trae.cn"},
              "kimi-k3": {"base": 1.65, "source": "实测"}
            },
            "today": {
              "glm-5.3": {"base": 4, "source": "上游"}
            }
          }
        }
    """.trimIndent()

    @Test
    fun `远程表按下发条目解析并按供应商隔离`() {
        val book = RateBook(null)
        assertEquals(3, book.importRemote(table))
        assertEquals(0.78, book.rate("trae", "glm-5.3")!!.base, 1e-9)
        // 同名模型在不同供应商下是两条数据，不能串
        assertEquals(4.0, book.rate("today", "glm-5.3")!!.base, 1e-9)
        assertNull(book.rate("trae", "unknown-model"))
    }

    @Test
    fun `模型 ID 大小写不敏感`() {
        val book = RateBook(null)
        book.importRemote(table)
        assertNotNull(book.rate("TRAE", "GLM-5.3"))
        assertNotNull(book.rate("trae", "Glm-5.3"))
    }

    @Test
    fun `脏数据整条丢弃而不是猜一个值`() {
        val book = RateBook(null)
        val count = book.importRemote(
            """
            {"version":1,"providers":{"trae":{
              "a":{"base":-1},
              "b":{"base":0},
              "c":{"base":99999},
              "d":{"base":"oops"},
              "e":{"discounted":0.5},
              "f":{"base":1.2,"discounted":1.5}
            }}}
            """.trimIndent(),
        )
        // 只有 b（免费）与 f（小数正常，即使折扣比基准高也照收）能通过校验
        assertEquals(2, count)
        assertNull(book.rate("trae", "a"))
        assertEquals(0.0, book.rate("trae", "b")!!.base, 1e-9)
        assertNull(book.rate("trae", "c"))
        assertNull(book.rate("trae", "d"))
        assertNull(book.rate("trae", "e"))
        assertEquals(1.2, book.rate("trae", "f")!!.base, 1e-9)
    }

    @Test
    fun `远程层整体替换但保留供应商现场数据`() {
        val book = RateBook(null)
        book.importRemote(table)
        book.recordLive("catpaw", "longcat-2.0", RateEntry(base = 2.0, source = "上游"))
        // 新表里没有 kimi-k3，也没有 today/glm-5.3 —— 旧远程值必须消失
        book.importRemote("""{"version":1,"providers":{"trae":{"glm-5.3":{"base":0.9}}}}""")
        assertEquals(0.9, book.rate("trae", "glm-5.3")!!.base, 1e-9)
        assertNull(book.rate("trae", "kimi-k3"))
        assertNull(book.rate("today", "glm-5.3"))
        // 现场数据不受远程表影响
        assertEquals(2.0, book.rate("catpaw", "longcat-2.0")!!.base, 1e-9)
        assertEquals(RateOrigin.LIVE, book.rate("catpaw", "longcat-2.0")!!.origin)
    }

    @Test
    fun `远程层覆盖现场层之外的所有模型`() {
        val book = RateBook(null)
        book.recordLive("trae", "glm-5.3", RateEntry(base = 0.5))
        book.importRemote(table)
        // 现场值还在，优先级高于远程表（这条是刻意保留的行为：接口数据比静态表可信）
        assertEquals(0.5, book.rate("trae", "glm-5.3")!!.base, 1e-9)
    }

    @Test
    fun `台账跨实例持久化`() {
        val store = InMemoryKeyValueStore()
        RateBook(store).importRemote(table)
        val reopened = RateBook(store)
        assertEquals(3, reopened.count())
        assertEquals(1.65, reopened.rate("trae", "kimi-k3")!!.base, 1e-9)
        assertEquals("2026-11-26", reopened.status().tableUpdated)
    }

    @Test
    fun `节流按 12 小时成功周期与 30 分钟失败退避计算`() {
        var now = 1_000_000L
        val book = RateBook(null) { now }
        assertTrue(book.shouldRefresh())
        book.importRemote(table)
        assertFalse(book.shouldRefresh())
        now += RateBook.REFRESH_INTERVAL_MS - 1
        assertFalse(book.shouldRefresh())
        now += 1
        assertTrue(book.shouldRefresh())

        // 失败后按更短的周期退避，避免离线时每轮都撞墙
        book.markFailure("timeout")
        assertFalse(book.shouldRefresh())
        now += RateBook.RETRY_INTERVAL_MS
        assertTrue(book.shouldRefresh())
    }

    @Test
    fun `摘要标签把折扣写成原价到现价的关系`() {
        val entry = RateEntry(base = 0.78, discounted = 0.39, discountTag = "会员5折")
        assertEquals("限时 0.39×（会员5折，基准 0.78×）", entry.summaryLabel())
        assertEquals("免费", RateEntry(base = 0.0).summaryLabel())
        assertEquals("基准 1.65×", RateEntry(base = 1.65).summaryLabel())
    }
}

/** 同步器：多线路回退、失败不清空、强制刷新跳过节流。 */
class RateSyncTest {

    private val body = """{"version":1,"providers":{"trae":{"glm-5.3":{"base":0.78}}}}"""

    @Test
    fun `直连失败时回退到镜像线路`() {
        val book = RateBook(null)
        val tried = mutableListOf<String>()
        val sync = RateSync(book, fetcher = { url ->
            tried += url
            if (tried.size == 1) null else body
        })
        val result = sync.maybeSync()
        assertTrue(result.ok)
        assertEquals(1, result.count)
        assertEquals(2, tried.size)
        // 第二条肯定是镜像线路（直连为第一条）
        assertTrue(tried[1].endsWith(RateBook.REMOTE_URL))
    }

    @Test
    fun `全部线路失败时保留旧数据并记录错误`() {
        val book = RateBook(null)
        book.importRemote(body)
        val sync = RateSync(book, fetcher = { null })
        val result = sync.maybeSync(force = true)
        assertFalse(result.ok)
        assertTrue(result.error.isNotEmpty())
        // 旧数据还在：失败不能让界面从「有倍率」变成「没倍率」
        assertEquals(1, book.count())
        assertTrue(book.status().lastError.isNotEmpty())
    }

    @Test
    fun `非法的表结构不会被当成成功`() {
        val book = RateBook(null)
        val sync = RateSync(book, fetcher = { """{"version":1}""" })
        val result = sync.maybeSync(force = true)
        assertFalse(result.ok)
        assertEquals(0, book.count())
    }

    @Test
    fun `未到刷新时间时直接跳过不发请求`() {
        var now = 1_000_000L
        val book = RateBook(null) { now }
        book.importRemote(body)
        var calls = 0
        val sync = RateSync(book, fetcher = { calls++; body }, nowMillis = { now })
        val skipped = sync.maybeSync()
        assertTrue(skipped.skipped)
        assertEquals(0, calls)
        // force 跳过节流
        assertTrue(sync.maybeSync(force = true).ok)
        assertEquals(1, calls)
    }
}