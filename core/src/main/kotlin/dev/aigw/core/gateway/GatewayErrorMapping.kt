package dev.aigw.core.gateway

import dev.aigw.core.provider.ErrorKind

/** 网关耗尽候选后要返回给客户端的错误（状态码 + 错误码 + 给人看的一句话）。 */
data class GatewayFailure(val status: Int, val code: String, val headline: String)

/**
 * 把「试过哪些供应商、各自为什么失败」映射成对客户端成立的状态码与错误码。
 *
 * 从前不论额度耗尽、凭证全失效还是上游超时，一律回 503 `no_healthy_account`，
 * 于是 agent 端看到的永远是「没有可用账号」——哪怕账号都在、只是上游在限流。
 * 这里按**出现次数最多**取主因，同票数按严重度定序，保证同一个真实原因每次得到同一个码。
 */
object GatewayErrorMapping {

    /** 同票数时的优先级：越靠前越「该先告诉用户」。 */
    private val severity = listOf(
        ErrorKind.SESSION_DEAD,
        ErrorKind.QUOTA,
        ErrorKind.SOFT_RATE,
        ErrorKind.SERVER,
        ErrorKind.NETWORK,
        ErrorKind.NOT_FOUND,
        ErrorKind.MODEL_LIMIT,
        ErrorKind.CLIENT,
    )

    /**
     * @param kinds 本次请求所有失败尝试的分类（可重复，重复次数即出现次数）。
     * @param attemptedUpstream 是否真的向上游发起过请求：没发起过就是「压根没账号可用」，
     *   与「账号都在但都失败」是两回事，错误码要分开。
     * @param poolSummary 账号池摘要文案，只在「没有可用账号」时拼进消息。
     */
    fun map(kinds: List<ErrorKind>, attemptedUpstream: Boolean, poolSummary: String = ""): GatewayFailure {
        if (!attemptedUpstream) {
            val detail = if (poolSummary.isEmpty()) "" else "（$poolSummary）"
            return GatewayFailure(503, "no_healthy_account", "没有可用账号$detail")
        }
        val dominant = kinds
            .groupingBy { it }
            .eachCount()
            .entries
            .maxWithOrNull(
                compareBy({ it.value }, { -severity.indexOf(it.key) }),
            )
            ?.key
        return when (dominant) {
            ErrorKind.SESSION_DEAD ->
                GatewayFailure(401, "all_accounts_unauthorized", "所有候选账号的登录凭证都已失效，请在应用里重新登录")
            ErrorKind.QUOTA ->
                GatewayFailure(429, "insufficient_quota", "所有候选账号的额度都已耗尽")
            ErrorKind.SOFT_RATE ->
                GatewayFailure(429, "rate_limit_exceeded", "上游限流：候选账号都在被限流")
            ErrorKind.NETWORK ->
                GatewayFailure(502, "upstream_unreachable", "连不上上游：请检查网络或代理设置")
            ErrorKind.SERVER ->
                GatewayFailure(502, "upstream_error", "上游服务异常")
            ErrorKind.NOT_FOUND ->
                GatewayFailure(404, "model_not_available", "上游没有这个模型")
            ErrorKind.MODEL_LIMIT ->
                GatewayFailure(429, "model_unavailable", "该模型在所有候选账号上都不可用")
            ErrorKind.CLIENT ->
                GatewayFailure(400, "invalid_request", "上游拒绝了该请求：参数或内容不合规")
            null ->
                GatewayFailure(503, "all_providers_failed", "所有候选供应商均不可用")
        }
    }
}
