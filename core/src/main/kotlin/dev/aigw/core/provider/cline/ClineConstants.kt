package dev.aigw.core.provider.cline

/**
 * Cline（cline.bot）上游常量。
 *
 * 两条认证途径：
 * 1. WorkOS 设备流：向 WorkOS 要 device_code → 浏览器授权 → 轮询拿 access_token →
 *    在 Cline 的 `/auth/register` 换成**账号自己的**令牌对（access + refresh）；
 * 2. Cline API Key：用户在 app.cline.bot 生成的个人 Key，当普通 Bearer 用。
 *
 * 对话走 OpenAI 兼容的 `/chat/completions`，但**必须带客户端指纹头**
 * （`X-CLIENT-TYPE` / `X-CLIENT-VERSION` / `X-PLATFORM` …），否则会被当成第三方客户端拒绝。
 */
object ClineConstants {

    const val ID = "cline"

    const val API = "https://api.cline.bot/api/v1"

    /** WorkOS 设备流的公开 client_id（官方 CLI 用的那个）。 */
    const val WORKOS_CLIENT = "client_01K3A541FN8TA3EPPHTD2325AR"
    const val WORKOS_DEVICE = "https://api.workos.com/user_management/authorize/device"
    const val WORKOS_TOKEN = "https://api.workos.com/user_management/authenticate"
    const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

    /** 客户端指纹；版本号参与上游的灰度与校验，改大了会被判为未知客户端。 */
    const val CLIENT_TYPE = "cline-cli"
    const val CLIENT_VERSION = "3.0.68"
    const val CLIENT_PLATFORM = "cli"
    const val CLIENT_CORE = "0.0.90"

    const val REGISTER_PATH = "/auth/register"
    const val REFRESH_PATH = "/auth/refresh"
    const val RECOMMENDED_PATH = "/ai/cline/recommended-models"
    const val CATALOG_PATH = "/ai/cline/models"
    const val CHAT_PATH = "/chat/completions"

    /** 免费档模型的 id 前缀（上游目录里也带 `free` 标记，两者互相印证）。 */
    const val FREE_PREFIX = "cline-free/"
    /** Cline Pass 订阅专属模型的 id 前缀。 */
    const val PASS_PREFIX = "cline-pass/"

    /** 令牌临期窗口：剩余不足 2 分钟就换，避免请求打到刚过期的 token 上。 */
    const val REFRESH_SKEW_MS = 2 * 60 * 1000L

    /**
     * 上游目录拉不到时的兜底模型（官方目录快照）。
     *
     * 只放确定存在的 id：宁可少列，也不要让用户选到一个必然 404 的模型。
     * 本清单按 `GET /api/v1/ai/cline/recommended-models` 的实时返回整理（2026-11-26 核对），
     * 三组分别是 recommended / free / clinePass。
     */
    val FALLBACK_MODELS = listOf(
        // recommended（官方推荐，付费按量）
        "meta/muse-ember",
        "anthropic/claude-sonnet-5.5",
        "google/gemini-4-argon",
        "anthropic/claude-opus-5.5",
        "openai/gpt-6-astra",
        "openai/gpt-6.1-sol",
        "moonshotai/kimi-k3",
        // free（官方免费档）
        "cline-free/nemotron-3.5-super-vl",
        "nvidia/nemotron-3.5-lightning-free",
        // clinePass（订阅内）
        "cline-pass/deepseek-v4.1-flash-1017",
        "cline-pass/deepseek-v4-pro",
        "cline-pass/glm-5.3",
        "cline-pass/glm-5.3-flash",
        "cline-pass/kimi-k3",
        "cline-pass/kimi-k3.1-pro",
        "cline-pass/qwen3.8-max",
        "cline-pass/minimax-m3.1-flash",
    )

    /** 客户端指纹头（Authorization 由调用方补）。 */
    fun clientHeaders(taskId: String = ""): Map<String, String> = buildMap {
        put("HTTP-Referer", "https://cline.bot")
        put("X-Title", "Cline")
        put("User-Agent", "Cline/$CLIENT_VERSION")
        put("X-IS-MULTIROOT", "false")
        put("X-CLIENT-TYPE", CLIENT_TYPE)
        put("X-CLIENT-VERSION", CLIENT_VERSION)
        put("X-PLATFORM", CLIENT_PLATFORM)
        put("X-PLATFORM-VERSION", CLIENT_VERSION)
        put("X-CORE-VERSION", CLIENT_CORE)
        if (taskId.isNotEmpty()) put("X-Task-ID", taskId)
    }
}