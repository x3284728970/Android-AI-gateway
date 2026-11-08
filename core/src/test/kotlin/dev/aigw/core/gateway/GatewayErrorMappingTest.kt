package dev.aigw.core.gateway

import dev.aigw.core.provider.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 网关耗尽候选后给客户端的错误映射。
 *
 * 重点是「同一个真实原因得到同一个码」，以及「压根没账号」与「账号都在但都失败」分开——
 * 从前两者都是 503 `no_healthy_account`，agent 端看不出到底是哪种。
 */
class GatewayErrorMappingTest {

    @Test
    fun `没向上游发起过请求时才算没有可用账号`() {
        val failure = GatewayErrorMapping.map(
            kinds = emptyList(),
            attemptedUpstream = false,
            poolSummary = "总数 3，可用 0，停用 3",
        )
        assertEquals(503, failure.status)
        assertEquals("no_healthy_account", failure.code)
        assertEquals(true, failure.headline.contains("总数 3"))
    }

    @Test
    fun `账号都在但上游限流时不再报没有可用账号`() {
        val failure = GatewayErrorMapping.map(
            kinds = listOf(ErrorKind.SOFT_RATE, ErrorKind.SOFT_RATE),
            attemptedUpstream = true,
        )
        assertEquals(429, failure.status)
        assertEquals("rate_limit_exceeded", failure.code)
    }

    @Test
    fun `额度耗尽与凭证失效各自映射到可识别的状态码`() {
        val quota = GatewayErrorMapping.map(listOf(ErrorKind.QUOTA), attemptedUpstream = true)
        assertEquals(429, quota.status)
        assertEquals("insufficient_quota", quota.code)

        val dead = GatewayErrorMapping.map(listOf(ErrorKind.SESSION_DEAD), attemptedUpstream = true)
        assertEquals(401, dead.status)
        assertEquals("all_accounts_unauthorized", dead.code)
        assertEquals(true, dead.headline.contains("重新登录"))
    }

    @Test
    fun `按出现次数取主因，同票按严重度定序`() {
        // 网络失败 2 次、凭证失效 1 次：多数派是网络
        val mostlyNetwork = GatewayErrorMapping.map(
            listOf(ErrorKind.NETWORK, ErrorKind.NETWORK, ErrorKind.SESSION_DEAD),
            attemptedUpstream = true,
        )
        assertEquals("upstream_unreachable", mostlyNetwork.code)

        // 各一次时取严重度更高的凭证失效
        val tie = GatewayErrorMapping.map(
            listOf(ErrorKind.NETWORK, ErrorKind.SESSION_DEAD),
            attemptedUpstream = true,
        )
        assertEquals("all_accounts_unauthorized", tie.code)
    }

    @Test
    fun `上游异常与模型不可用分别映射`() {
        assertEquals(502, GatewayErrorMapping.map(listOf(ErrorKind.SERVER), true).status)
        assertEquals(404, GatewayErrorMapping.map(listOf(ErrorKind.NOT_FOUND), true).status)
        assertEquals(400, GatewayErrorMapping.map(listOf(ErrorKind.CLIENT), true).status)
        assertEquals("model_unavailable", GatewayErrorMapping.map(listOf(ErrorKind.MODEL_LIMIT), true).code)
    }

    @Test
    fun `空分类且有上游尝试时用兜底码而不是 no_healthy_account`() {
        val failure = GatewayErrorMapping.map(emptyList(), attemptedUpstream = true)
        assertEquals(503, failure.status)
        assertEquals("all_providers_failed", failure.code)
    }
}
