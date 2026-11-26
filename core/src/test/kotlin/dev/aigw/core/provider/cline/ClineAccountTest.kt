package dev.aigw.core.provider.cline

import dev.aigw.core.provider.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Cline 的凭证形态、模型名保留与错误分类。 */
class ClineAccountTest {

    @Test
    fun `裸 API Key 被识别为 api 形态`() {
        val account = ClineAccount.parse("sk-cline-abcdef")!!
        assertTrue(account.isApiKey)
        assertEquals("sk-cline-abcdef", account.access)
        // API Key 没有刷新链，永远算可用
        assertTrue(account.accessUsable(now = Long.MAX_VALUE - 1))
    }

    @Test
    fun `OAuth 令牌按到期时间判断可用性`() {
        val account = ClineAccount(
            access = "a",
            refresh = "r",
            expiresAt = 1_000_000L,
            type = ClineAccount.TYPE_OAUTH,
        )
        assertTrue(account.accessUsable(now = 800_000L))
        // 进入刷新窗口（2 分钟）即视为需要刷新
        assertFalse(account.accessUsable(now = 1_000_000L - 60_000L))
        assertFalse(account.accessUsable(now = 2_000_000L))
    }

    @Test
    fun `账号 JSON 缺 type 时按有无 refresh 推断`() {
        val oauth = ClineAccount.parse("""{"access":"a","refresh":"r"}""")!!
        assertEquals(ClineAccount.TYPE_OAUTH, oauth.type)
        val api = ClineAccount.parse("""{"access":"a"}""")!!
        assertEquals(ClineAccount.TYPE_API, api.type)
    }

    @Test
    fun `ISO 时间解析支持带毫秒与不带毫秒`() {
        assertTrue(ClineAccount.isoToMillis("2026-11-26T12:00:00.000Z") > 0)
        assertTrue(ClineAccount.isoToMillis("2026-11-26T12:00:00Z") > 0)
        assertEquals(0L, ClineAccount.isoToMillis("not-a-date"))
    }

    @Test
    fun `模型名里的斜杠必须保留否则会抹掉档位前缀`() {
        val provider = ClineProvider()
        assertEquals("cline-free/deepseek-v4.1-flash", provider.resolveModel("cline-free/deepseek-v4.1-flash"))
        assertEquals("anthropic/claude-sonnet-5.5", provider.resolveModel("anthropic/claude-sonnet-5.5"))
        // 空名回落兜底，避免把空串发给上游
        assertEquals(ClineConstants.FALLBACK_MODELS.first(), provider.resolveModel("  "))
    }

    @Test
    fun `订阅档位不足归到模型级限制而不是账号失效`() {
        val provider = ClineProvider()
        val error = provider.classify(403, """{"error":{"message":"The user is not subscribed to required model plan"}}""")
        assertEquals(ErrorKind.MODEL_LIMIT, error.kind)
        assertEquals(ErrorKind.SESSION_DEAD, provider.classify(401, "{}").kind)
        assertEquals(ErrorKind.SOFT_RATE, provider.classify(429, "{}").kind)
        assertNotNull(provider.classify(500, "boom").message)
    }

    @Test
    fun `免费模型两套命名都要认出来`() {
        val provider = ClineProvider()
        // 推荐流命名
        assertTrue(provider.isFreeModel("cline-free/nemotron-3.5-super-vl"))
        assertTrue(provider.isFreeModel("nvidia/nemotron-3.5-lightning-free"))
        // 云端目录命名（pricing.prompt == 0 的那批）
        assertTrue(provider.isFreeModel("nvidia/nemotron-3.5-super-vl:free"))
        assertTrue(provider.isFreeModel("google/gemma-4-26b-a4b-it:free"))
        // 付费模型不能被误判成免费
        assertFalse(provider.isFreeModel("anthropic/claude-sonnet-5.5"))
        assertFalse(provider.isFreeModel("cline-pass/deepseek-v4-pro"))
        assertFalse(provider.isFreeModel("meta/muse-ember"))
    }

    @Test
    fun `客户端指纹头齐全且版本号一致`() {
        val headers = ClineConstants.clientHeaders()
        assertEquals(ClineConstants.CLIENT_TYPE, headers["X-CLIENT-TYPE"])
        assertEquals(ClineConstants.CLIENT_VERSION, headers["X-CLIENT-VERSION"])
        assertEquals(ClineConstants.CLIENT_CORE, headers["X-CORE-VERSION"])
        assertEquals("Cline/${ClineConstants.CLIENT_VERSION}", headers["User-Agent"])
    }
}