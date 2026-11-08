package dev.aigw.core.util

/**
 * 上游只回了状态码、没有可读消息时，附一段响应片段。
 *
 * 否则报错就只有一句「上游 HTTP 500」——用户与 agent 都无法判断是上游崩了、
 * 还是网关把请求发错了。片段截断到 160 字符：排查够用，又不会把整页 HTML 灌进调用记录。
 */
fun bodySnippet(body: String, limit: Int = 160): String {
    val text = body.trim().replace(Regex("\\s+"), " ")
    if (text.isEmpty()) return ""
    val head = text.take(limit)
    return if (text.length > limit) "：$head…" else "：$head"
}
