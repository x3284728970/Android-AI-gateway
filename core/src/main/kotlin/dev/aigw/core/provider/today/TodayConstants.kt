package dev.aigw.core.provider.today

/**
 * Today（api.todayai.cn）的常量与模型映射。
 *
 * 协议来自 today-proxy 系列项目的实测抓包（github.com/Main8986/today-proxy-android）：
 *
 *   session token ──GET /api/auth/token──> ES256 JWT（有效期以响应 expiresIn 为准，
 *   桌面实测 600s，Android 线更短）──Bearer──> api.todayai.cn/v1/…
 *
 * 与其它供应商不同，Today **没有 SSE 流式接口**：发一句话拿 threadId，再轮询
 * `/v1/conversations/messages` 直到 state=completed。所以 [TodayProvider] 的流式是
 * 「轮询出来的增量 + 本地拼 SSE」，客户端看到的是标准 OpenAI 流。
 */
object TodayConstants {

    const val AUTH_BASE = "https://auth.todayai.cn"
    const val API_BASE = "https://api.todayai.cn"

    /** 上游客户端版本号：写死在请求头里，改大了会被判客户端不合法。 */
    const val APP_VERSION = "1.23.0"

    /** 上游的模型是用「选项 id」表达的，不是 model 字段。 */
    const val OPT_DEEPSEEK = "model.deepseek-v4.1-flash"
    const val OPT_GLM = "model.glm-5.3"

    /**
     * MiniMax M3.1 Flash（限时免费，倍率 0×）。
     *
     * optionId 按上游的命名规则推得（`model.` + 短名小写）。万一猜错也不致命：
     * [TodayProvider] 会从 `/v1/pricing/status` 的响应里**自动发现**真实存在的
     * `model.*` 选项，这里的常量只是「没账号 / 拉取失败」时的兜底。
     */
    // 实证 id（自动发现捕获）；此前猜的 model.minimax-m3.1-flash 上游 400
    const val OPT_MINIMAX = "model.minimax-m31-flash"

    /** 暴露给客户端的短名。 */
    const val MODEL_DEEPSEEK = "deepseek-v4.1-flash"
    const val MODEL_GLM = "glm-5.3"
    const val MODEL_MINIMAX = "minimax-m31-flash"

    /** 兜底模型表（无账号或上游拉取失败时用）。 */
    val MODEL_IDS = listOf(MODEL_DEEPSEEK, MODEL_MINIMAX, MODEL_GLM)

    /** 倍率表：上游按「同类任务消耗倍数」计价，0 表示限时免费。界面据此标注。 */
    private val RATES = mapOf(
        MODEL_DEEPSEEK to "1×",
        // 2026-10-29 用户反馈：M3.1 的限时免费已结束，上游按 1× 计
        MODEL_MINIMAX to "1×",
        MODEL_GLM to "4×",
    )

    fun rateOf(model: String): String = RATES[model].orEmpty()

    /** 已知模型的展示说明。 */
    private val NOTES = mapOf(
        MODEL_DEEPSEEK to "DeepSeek V4.1 Flash",
        MODEL_MINIMAX to "MiniMax M3.1 Flash",
        MODEL_GLM to "智谱 GLM 5.3",
    )

    fun noteOf(model: String): String = NOTES[model].orEmpty()

    /** optionId → 短名（去掉 `model.` 前缀）。 */
    fun shortNameOf(optionId: String): String = optionId.removePrefix("model.")

    private const val CAPS =
        "chat.browser.v1,connectors.account_management.v1,chat.brief_card.v1," +
            "chat.qa_cardflow_single.v1,chat.health_metric_card.v1,chat.automation_card.v1," +
            "chat.follow_up_recommendations.v1,chat.message_reactions.unicode.v1"

    fun capabilities(): String = CAPS

    /**
     * 上游按 UA 里的版本号与设备串做灰度，UA 必须和 APP_VERSION 对得上。
     *
     * 设备串刻意保持成一台固定机型：漂移的 UA 会被当成「未知客户端」，
     * 部分账号会被降级到不含 v4.1 选项的旧界面。
     */
    fun userAgent(): String =
        "Today/$APP_VERSION Version/$APP_VERSION Build/2768 " +
            "Device/(OnePlus;OP5D2BL1)(PKG110; Android 15) NetType/WiFi"

    /** 把客户端传来的模型名归一到上游 optionId；不认识的返回 null（交给调用方报错）。 */
    fun optionIdOf(model: String): String? {
        val s = model.trim().lowercase()
        if (s.isEmpty()) return OPT_DEEPSEEK
        // 允许带供应商前缀：today/deepseek-v4.1-flash
        val bare = s.substringAfter('/', s).substringAfter(':')
        // 分隔符归一：用户可能写 minimax-m3.1 也可能写 minimax-m3-1（上游两种都出现过）
        return when {
            bare == "glm" || bare == MODEL_GLM || bare == OPT_GLM || bare.startsWith("glm-") -> OPT_GLM
            bare == "minimax" || bare == MODEL_MINIMAX || bare == OPT_MINIMAX ||
                bare.startsWith("minimax") || bare == "m3.1" || bare == "m3-1" -> OPT_MINIMAX
            bare == "deepseek" || bare == MODEL_DEEPSEEK || bare == OPT_DEEPSEEK ||
                bare.startsWith("deepseek") -> OPT_DEEPSEEK
            // 上游随时可能上新模型：`model.xxx` 原样放行；其它看起来像模型名的短名
            // 按上游命名规则补上 `model.` 前缀。真实是否存在交给上游校验——
            // 拼错时会拿到一条带可用列表的错误，比这里静默回退到 DeepSeek 好排查。
            bare.startsWith("model.") -> bare
            bare.isNotEmpty() && bare.length <= 64 && bare.none { it.isWhitespace() } -> "model.$bare"
            else -> null
        }
    }

    /** optionId → 短名；未知选项按上游命名规则去前缀，不再一律落到 DeepSeek。 */
    fun displayNameOf(optionId: String): String = when (optionId) {
        OPT_GLM -> MODEL_GLM
        OPT_MINIMAX -> MODEL_MINIMAX
        OPT_DEEPSEEK -> MODEL_DEEPSEEK
        else -> shortNameOf(optionId)
    }
}
