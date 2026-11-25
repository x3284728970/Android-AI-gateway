package dev.aigw.core.provider.copilot

/** GitHub Copilot 上游常量。取值来自公开的客户端协议实测，改动需重新验证。 */
object CopilotConstants {

    const val ID = "github-copilot"

    /** GitHub 官方 OAuth 设备流。 */
    const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
    const val USER_URL = "https://api.github.com/user"
    const val COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token"
    const val ALLOWANCE_URL = "https://api.github.com/copilot_internal/user"

    /** 对话与模型目录端点（[OI] 兼容）。 */
    const val BASE = "https://api.githubcopilot.com"

    /** GitHub 的 Copilot 插件公开 client_id（VS Code 扩展用的那一个）。 */
    const val CLIENT_ID = "Iv1.b507a08c87ecfe98"
    const val SCOPE = "read:user"
    const val GRANT_DEVICE = "urn:ietf:params:oauth:grant-type:device_code"

    /**
     * 客户端指纹头。
     *
     * 除 Authorization 外**必须**带这些头，否则上游直接 401：Copilot 后端按
     * Integration-Id + Editor 版本判定调用方是不是官方客户端。
     */
    const val USER_AGENT = "GitHubCopilotChat/0.68.0"
    const val EDITOR_VERSION = "vscode/1.140.0"
    const val PLUGIN_VERSION = "copilot-chat/0.68.0"
    const val INTEGRATION_ID = "vscode-chat"

    /** Copilot 短期 token 的提前刷新窗口：临期 5 分钟内就重换，避免请求打到过期 token。 */
    const val TOKEN_SKEW_MS = 5 * 60 * 1000L

    /** 设备码轮询的最小间隔（GitHub 建议 ≥5s，取 3s 兜底防止上游返回 0）。 */
    const val MIN_POLL_INTERVAL_MS = 3_000L

    /** 上游没给目录时的兜底模型名，保证首次登录后列表不是空的。 */
    val FALLBACK_MODELS = listOf(
        "gpt-5.1",
        "gpt-5.1-codex",
        "gpt-5",
        "gpt-5-mini",
        "gemini-3-pro-preview",
        "o3",
        "o4-mini",
    )
}
