package dev.aigw.core.provider.codebuddy

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WorkBuddy 刷新响应的解析与过期时间。
 *
 * 背景（用户报「国际版最近经常登录失效」）：刷新响应从前只认 camelCase 的 `data.accessToken`
 * 且要求信封 `code == 0`，解析不出来就当作刷新失败；失败后进入冷却、继续拿旧 token 发请求，
 * 上游直接 401 → 网关把账号硬禁用 —— 用户看到的就是「登录失效」，而且会重复发生。
 */
class CodeBuddyRefreshTest {

    private val provider = CodeBuddyProvider()

    private fun jwt(payloadJson: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        return "eyJhbGciOiJIUzI1NiJ9.$encoded.signature"
    }

    @Test
    fun `刷新响应 camelCase 与 snake_case 都能取到`() {
        val camel = assertNotNull(
            provider.refreshEnvelopeData("""{"code":0,"data":{"accessToken":"a1","expiresIn":3600}}"""),
        )
        assertEquals("a1", camel.get("accessToken").asString)

        val snake = assertNotNull(
            provider.refreshEnvelopeData("""{"code":0,"data":{"access_token":"a2","expires_in":3600}}"""),
        )
        assertEquals("a2", snake.get("access_token").asString)
    }

    @Test
    fun `没有 code 字段的刷新响应也认，带非零 code 仍算失败`() {
        // 有的站点回 200 却不带 code：当成失败会让刷新永远失败
        assertNotNull(provider.refreshEnvelopeData("""{"data":{"accessToken":"a"}}"""))
        // 平铺在顶层（无 data 包装）也认，但必须真的像凭证
        assertNotNull(provider.refreshEnvelopeData("""{"access_token":"a"}"""))
        // 业务错误码不能放行
        assertNull(provider.refreshEnvelopeData("""{"code":11217,"data":{"accessToken":"a"}}"""))
        // 空壳响应不能当成 token
        assertNull(provider.refreshEnvelopeData("""{"code":0}"""))
    }

    @Test
    fun `刷新响应给了过期时间就用它`() {
        val now = 1_000_000L
        assertEquals(now + 3600, provider.resolveRefreshedExpiry(now + 3600, jwt("""{"exp":1}"""), now))
    }

    @Test
    fun `没给过期时间时用新 token 的 JWT exp，而不是沿用旧值`() {
        val now = 1_000_000L
        val token = jwt("""{"exp":1009999}""")
        assertEquals(1_009_999L, provider.resolveRefreshedExpiry(0L, token, now))
    }

    @Test
    fun `既没给过期时间也不是 JWT 时用兜底 TTL`() {
        val now = 1_000_000L
        val resolved = provider.resolveRefreshedExpiry(0L, "not-a-jwt", now)
        assertEquals(now + CodeBuddyProvider.REFRESH_FALLBACK_TTL_SECONDS, resolved)
        assertTrue(resolved > now, "兜底值必须在未来，否则下一个请求又会去刷")
    }

    @Test
    fun `凭证已过期且刷新刚失败时拦住这次请求，避免被当成凭证失效`() {
        val now = 1_000_000L
        val expired = now - 10
        // 没失败过（streak 0，可能是刚过期还没轮到刷新）：不拦，让它正常去刷/去上游
        assertTrue(!provider.shouldShieldExpiredCredential(expired, streak = 0, nowSeconds = now))
        // 刷新失败 1~2 次：拦，报可重试错误而不是拿 401 把账号禁用
        assertTrue(provider.shouldShieldExpiredCredential(expired, streak = 1, nowSeconds = now))
        assertTrue(provider.shouldShieldExpiredCredential(expired, streak = CodeBuddyProvider.MAX_SHIELD_STREAK, nowSeconds = now))
        // 连续失败超过上限：不再护着，让真正的死会话暴露出来（走 401 → 禁用 → 提醒重登）
        assertTrue(!provider.shouldShieldExpiredCredential(expired, streak = CodeBuddyProvider.MAX_SHIELD_STREAK + 1, nowSeconds = now))
        // 还没过期：不拦
        assertTrue(!provider.shouldShieldExpiredCredential(now + 60, streak = 1, nowSeconds = now))
        // 过期时间未知（0）：不拦
        assertTrue(!provider.shouldShieldExpiredCredential(0L, streak = 1, nowSeconds = now))
    }
}
