package dev.aigw.core.provider.copilot

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.str

/**
 * GitHub Copilot 账号凭证。
 *
 * 两层 token：
 *  - [githubToken]：GitHub 用户的长期 OAuth token，用来换 Copilot token。账号的 refresh。
 *  - [copilotToken]：Copilot 的短期 token（约 30 分钟），真正转发请求时用。
 *
 * 只存 [githubToken] 也能工作（每次现换），但每次请求都多一次上游往返，
 * 所以把短期 token 与到期时间一起存下来，临期才换。
 */
data class CopilotAccount(
    val githubToken: String = "",
    val copilotToken: String = "",
    /** Copilot token 到期时间（epoch 毫秒）。0 表示未知。 */
    val expiresAt: Long = 0L,
    /** GitHub 登录名，用于账号卡片显示。 */
    val login: String = "",
) {
    /** 账号唯一号：GitHub 登录名优先，否则用 token 的稳定哈希（同一 token 映射同一账号）。 */
    val uid: String
        get() = if (login.isNotEmpty()) login else "gh-" + githubToken.hashCode().toUInt().toString(16)

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("githubToken", githubToken)
        addProperty("copilotToken", copilotToken)
        addProperty("expiresAt", expiresAt)
        addProperty("login", login)
    }

    fun toJsonString(): String = toJson().toString()

    /** 短期 token 是否还能用（留出刷新窗口）。 */
    fun tokenUsable(now: Long): Boolean =
        copilotToken.isNotEmpty() && expiresAt > now + CopilotConstants.TOKEN_SKEW_MS

    companion object {
        fun parse(raw: String): CopilotAccount? {
            if (raw.isBlank()) return null
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
            val github = obj.str("githubToken")
            if (github.isEmpty()) return null
            return CopilotAccount(
                githubToken = github,
                copilotToken = obj.str("copilotToken"),
                expiresAt = obj.get("expiresAt")?.asLong ?: 0L,
                login = obj.str("login"),
            )
        }
    }
}
