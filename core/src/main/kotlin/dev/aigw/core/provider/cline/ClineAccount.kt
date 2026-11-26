package dev.aigw.core.provider.cline

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.str
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Cline 账号凭证。
 *
 * 两种形态共用一个结构：
 * - OAuth：有 [refresh]（长期）与 [access]（短效，约 1 小时）；
 * - API Key：只有 [access]，[@type] = `api`，没有刷新概念。
 *
 * 之所以不拆成两个类：账号池只认 [ProviderAccount.secret] 一段 JSON，
 * 一个结构能把「有没有 refresh」这件事表达清楚就够了。
 */
data class ClineAccount(
    val access: String = "",
    val refresh: String = "",
    /** access 的到期时间（epoch 毫秒）；0 表示上游没给，按「未知但可用」处理。 */
    val expiresAt: Long = 0L,
    val uid: String = "",
    val email: String = "",
    val name: String = "",
    /** `oauth` / `api`。 */
    val type: String = TYPE_OAUTH,
) {
    val isApiKey: Boolean get() = type == TYPE_API

    /** 账号唯一号：uid → email → token 哈希。 */
    val effectiveUid: String
        get() = uid.ifEmpty { email }.ifEmpty { "cline-" + access.hashCode().toUInt().toString(16) }

    val effectiveNickname: String
        get() = name.ifEmpty { email }.ifEmpty { if (isApiKey) "Cline API Key" else "Cline 账号" }

    /** access 是否还能用（留出刷新窗口）；API Key 恒为 true。 */
    fun accessUsable(now: Long, skewMs: Long = ClineConstants.REFRESH_SKEW_MS): Boolean {
        if (access.isEmpty()) return false
        if (isApiKey || expiresAt <= 0L) return true
        return expiresAt - skewMs > now
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("access", access)
        addProperty("refresh", refresh)
        addProperty("expiresAt", expiresAt)
        addProperty("uid", uid)
        addProperty("email", email)
        addProperty("name", name)
        addProperty("type", type)
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        const val TYPE_OAUTH = "oauth"
        const val TYPE_API = "api"

        fun parse(raw: String): ClineAccount? {
            if (raw.isBlank()) return null
            val trimmed = raw.trim()
            // 裸 API Key：用户从 app.cline.bot 复制的那一串
            if (!trimmed.startsWith("{")) {
                return ClineAccount(access = trimmed, type = TYPE_API)
            }
            val obj = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull() ?: return null
            val access = obj.str("access").ifEmpty { obj.str("accessToken") }
            if (access.isEmpty()) return null
            val type = obj.str("type").ifEmpty {
                if (obj.str("refresh").ifEmpty { obj.str("refreshToken") }.isEmpty()) TYPE_API else TYPE_OAUTH
            }
            return ClineAccount(
                access = access,
                refresh = obj.str("refresh").ifEmpty { obj.str("refreshToken") },
                expiresAt = obj.get("expiresAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                uid = obj.str("uid"),
                email = obj.str("email"),
                name = obj.str("name"),
                type = type,
            )
        }

        /** ISO-8601（`2026-11-26T12:00:00.000Z`）→ epoch 毫秒；解析不了返回 0。 */
        fun isoToMillis(value: String): Long {
            if (value.isBlank()) return 0L
            val patterns = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
            )
            for (pattern in patterns) {
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.parse(value)?.time
                }.getOrNull()
                if (parsed != null) return parsed
            }
            return 0L
        }
    }
}