package dev.aigw.core.provider.antigravity

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Antigravity 客户端版本号。
 *
 * 上游按 `User-Agent` 里的客户端版本决定可用模型：**低于 2.23.0 的客户端拿不到新模型**
 * （CLIProxyAPI 的注释明写这一点：`Cloud Code rejects newer models for clients below 2.23.0`；
 * MIT，router-for-me，https://github.com/router-for-me/CLIProxyAPI），
 * 版本写死会让账号随着上游推进逐渐失效。这里改成从官方自动更新清单取，并缓存 [TTL_MILLIS]。
 *
 * 取不到时用 [FALLBACK_VERSION]（等于 CLIProxyAPI 的兜底值），绝不因此让请求失败。
 * 只做一次轻量 GET 且结果缓存，不会给每个请求增加往返。
 */
internal object AntigravityVersion {

    /** 官方自动更新清单（macOS arm64），内容是 YAML，只解析 `version:` 一行。 */
    private const val MANIFEST_URL =
        "https://antigravity-hub-auto-updater-974169037036.us-central1.run.app/manifest/latest-arm64-mac.yml"

    /** 兜底版本：必须 ≥ 上游的下限 2.23.0，否则新模型会被直接拒绝。 */
    const val FALLBACK_VERSION = "2.23.0"

    private const val TTL_MILLIS = 6L * 60 * 60 * 1000
    private const val TIMEOUT_MS = 10_000

    private val cachedHub = AtomicReference<Pair<String, Long>>(FALLBACK_VERSION to 0L)

    /** 当前生效的版本号（缓存过期才回源）。 */
    fun current(nowMillis: Long = System.currentTimeMillis()): String {
        val (version, fetchedAt) = cachedHub.get()
        if (nowMillis - fetchedAt < TTL_MILLIS) return version
        val fetched = fetchLatest(MANIFEST_URL)
        val next = fetched ?: FALLBACK_VERSION
        cachedHub.set(next to nowMillis)
        return next
    }

    /** 解析清单里的 `version:`；失败返回 null（调用方用兜底值）。 */
    internal fun parseManifest(text: String): String? =
        text.lineSequence()
            .firstOrNull { it.trimStart().startsWith("version:") }
            ?.substringAfter(':')
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotEmpty() }

    private fun fetchLatest(url: String = MANIFEST_URL): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
        }
        try {
            if (conn.responseCode !in 200..299) {
                null
            } else {
                parseManifest(conn.inputStream.bufferedReader().readText())
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
