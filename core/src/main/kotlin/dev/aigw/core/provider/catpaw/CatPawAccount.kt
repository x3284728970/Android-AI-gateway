package dev.aigw.core.provider.catpaw

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.str

/**
 * 妙手 CatPaw 账号凭证。
 *
 * 只有一条凭据：[token]（网页登录拿到的 `X-Passport-Token`）。
 * 它既是聊天的 `Cookie: X-Passport-Token`，也是余额接口的 `X-Auth-Token`——
 * 同一个值，两种带法，混用会 401（见 [CatPawConstants] 类注释）。
 */
data class CatPawAccount(
    val token: String = "",
    val uid: String = "",
    val nickname: String = "",
) {
    /** 账号唯一号：优先上游 userId，缺失时用 token 的稳定哈希。 */
    val effectiveUid: String
        get() = uid.ifEmpty { "catpaw-" + token.hashCode().toUInt().toString(16) }

    val effectiveNickname: String
        get() = nickname.ifEmpty { "妙手网页登录" }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("token", token)
        addProperty("uid", uid)
        addProperty("nickname", nickname)
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun parse(raw: String): CatPawAccount? {
            if (raw.isBlank()) return null
            // 裸 token（用户从抓包/浏览器复制）：不强制 JSON，省一步转换
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{")) return CatPawAccount(token = trimmed)
            val obj = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull() ?: return null
            val token = obj.str("token").ifEmpty { obj.str("accessToken") }
            if (token.isEmpty()) return null
            return CatPawAccount(
                token = token,
                uid = obj.str("uid"),
                nickname = obj.str("nickname"),
            )
        }
    }
}