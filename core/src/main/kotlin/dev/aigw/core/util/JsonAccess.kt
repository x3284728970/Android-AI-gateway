package dev.aigw.core.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

/**
 * Gson 的 `getAsJsonObject(name)` / `getAsJsonArray(name)` 是裸强转（`(JsonObject) members.get(name)`），
 * 字段存在但值为 `null`（JsonNull）或类型不符时会抛 ClassCastException；
 * `JsonNull.asString` 之类同样会抛 UnsupportedOperationException。
 *
 * 上游很常见这类数据：标准 OpenAI 兼容服务的流式 chunk 每个都带 `"usage": null`。
 * 取字段一律走这里，别用 Gson 的裸转换——在流式转发路径上抛异常会直接掐断整条流。
 */

fun JsonObject.objOrNull(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonObject.arrayOrNull(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray

fun JsonObject.stringOrNull(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

/**
 * 取整数字段。
 *
 * **只在值确实是数字时返回**：上游/用户粘贴的 JSON 里 `expiresAt` 常写成
 * ISO 字符串（`2026-11-27T16:30:01Z`），直接 `asLong` 会抛
 * `NumberFormatException: For input string: "2026-11-27T16:30:01Z"`——
 * 用户看到的是一句莫名其妙的英文，而不是「时间格式不支持」。
 */
fun JsonObject.longOrNull(name: String): Long? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

fun JsonObject.intOrNull(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive }?.asInt

fun JsonObject.boolOrNull(name: String): Boolean? = get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean

/** 数组元素的安全取值。 */
fun JsonElement.asObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

/** 取字符串字段，缺字段/JsonNull/类型不符时回退 [fallback]。 */
fun JsonObject.str(key: String, fallback: String = ""): String {
    val v = get(key) ?: return fallback
    if (v.isJsonNull) return fallback
    return runCatching { v.asString }.getOrDefault(fallback)
}

fun JsonObject.long(key: String, fallback: Long = 0L): Long {
    val v = get(key) ?: return fallback
    if (v.isJsonNull) return fallback
    return runCatching { v.asLong }.getOrDefault(fallback)
}

fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean {
    val v = get(key) ?: return fallback
    if (v.isJsonNull) return fallback
    return runCatching { v.asBoolean }.getOrDefault(fallback)
}

/** 按顺序取第一个非空的字符串字段（兼容 camelCase / snake_case 两套命名）。 */
fun JsonObject.firstString(vararg keys: String): String {
    for (key in keys) {
        val value = get(key) ?: continue
        if (!value.isJsonPrimitive) continue
        val text = runCatching { value.asString }.getOrNull().orEmpty()
        if (text.isNotEmpty()) return text
    }
    return ""
}

/**
 * 按顺序取第一个**大于 0** 的整数字段。
 *
 * 注意与 [long]（取第一个存在的值，含 0）语义不同：这里把 0 视为「未提供」，
 * 用于 `expiresAt` 这类 0 = 未知的字段。
 */
fun JsonObject.firstLong(vararg keys: String): Long {
    for (key in keys) {
        val value = get(key) ?: continue
        if (!value.isJsonPrimitive) continue
        val number = runCatching { value.asLong }.getOrNull()
        if (number != null && number > 0) return number
    }
    return 0L
}

/** 解析 JWT 的 payload；格式不合法时返回 null。 */
private fun jwtPayload(accessToken: String): JsonObject? {
    val payload = accessToken.split('.').getOrNull(1) ?: return null
    if (payload.isEmpty()) return null
    val json = runCatching {
        String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
    }.getOrNull() ?: return null
    return runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
}

/**
 * 从 access_token（JWT）的 payload 里按序取第一个非空声明；取不到返回空串。
 *
 * 登录响应未必直接带 uid，但 access_token 常是 JWT，可从这里兜底。
 */
fun jwtClaim(accessToken: String, vararg keys: String): String {
    val obj = jwtPayload(accessToken) ?: return ""
    return obj.firstString(*keys)
}

/** 从 JWT 的 payload 里取某个数值声明（如 exp）；取不到返回 null。 */
fun jwtClaimLong(accessToken: String, key: String): Long? {
    val obj = jwtPayload(accessToken) ?: return null
    return obj.get(key)?.takeIf { it.isJsonPrimitive }?.asLong
}
