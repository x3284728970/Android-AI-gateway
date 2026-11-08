package dev.aigw.core.provider

/** 统一的上游错误分类，决定重试策略、换号/切换供应商与硬禁用。 */
enum class ErrorKind {
    /** 额度/积分耗尽：换号或切换供应商。 */
    QUOTA,

    /** 限流（429 等）：可退避重试，不累计错误次数。 */
    SOFT_RATE,

    /**
     * 上游排队中（CodeBuddy `6020` queue.waiting，带 `data.retry_after`）。
     *
     * 队列按 `client_user_type` **全局共享**：几毫秒内换号必然撞同一堵墙，只是把同一份
     * 排队请求重发 MAX_ROTATE 次。所以这里不换号、不罚号、不计熔断，直接把
     * `429 + Retry-After` 交给客户端。对照 `6021`（queue.full，无 retry_after）——
     * 那是间歇空窗，换号有机会碰上，仍走 [SOFT_RATE] 轮转。
     */
    QUEUE_WAIT,

    /** 凭证失效：硬禁用，必须重新登录。 */
    SESSION_DEAD,

    /** 路由不存在：换号或切换供应商。 */
    NOT_FOUND,

    /** 客户端问题（模型名/参数不对）：不该连累账号。 */
    CLIENT,

    /** 上游 5xx。 */
    SERVER,

    /** 网络层失败（连不上、超时）。 */
    NETWORK,

    /**
     * 该 (供应商, 模型) 级别暂时不可用（如 CodeBuddy 429/`6004`「该模型已超限」、
     * Trae `1005` 套餐权益拒绝）。换账号无用（所有账号撞同一道墙），
     * 所以不计账号健康、不计熔断，只对该模型做一段冷却。
     */
    MODEL_LIMIT,
}

/** 一次上游错误的分类结果；[message] 必须是能直接给用户看的人话。 */
data class UpstreamError(
    val kind: ErrorKind,
    val message: String,
    val code: Long = 0,
    /** 上游建议的重试等待秒数（[ErrorKind.QUEUE_WAIT] 才有意义）；0 表示上游没给。 */
    val retryAfterSeconds: Long = 0,
)
