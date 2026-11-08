package dev.aigw.core.provider

/**
 * 把漏进正文的思考控制标签摘出来。
 *
 * 上游的 reasoning parser 偶发把整段 CoT 当 `content` 下发：`usage.reasoning_tokens`
 * 是 0，正文首尾却带着模型自己的控制 token `<think:6124c78e>…</think:6124c78e>`
 * （SUF 是 checkpoint 后缀，不是请求 id）。OpenAI 协议客户端看到的是正文里混着 CoT；
 * Anthropic 客户端更糟——它只认 `thinking` 块，内联标签一律当正文显示。
 *
 * 只在 content **以** `<think:` 开头时启用：正文中间聊到这几个字不重写。
 * 只认带后缀的 `<think:SUF>` 形态，裸 `thinking` 不处理。
 *
 * 参考实现：misakano7545/workbuddy2api-panel `internal/upstream/thinktag.go`（issue #194）。
 */
internal class ThinkTag {

    /** 已判定这段 content 是否内联思考。 */
    private var decided = false

    /** 启用摘除。 */
    private var on = false

    /** 当前位置在标签内。 */
    private var inTag = false

    /** 尾部可能是半个标签，等下一片。 */
    private var pend = ""

    /**
     * 吃一片正文，返回本片里的思考与正文（按原顺序拼进各自通道）。
     *
     * 流式分片时同一实例要跨片复用，否则标签会被切碎漏到正文里。
     */
    fun feed(s: String): Pair<String, String> {
        if (s.isEmpty()) return "" to ""
        pend += s
        if (!decided) {
            // 开头还没看够：先等一片，别把 "<thi" 当正文发出去
            if (pend.length < THINK_OPEN.length && THINK_OPEN.startsWith(pend)) return "" to ""
            decided = true
            on = pend.startsWith(THINK_OPEN)
            if (!on) {
                val out = pend
                pend = ""
                return "" to out
            }
        }
        if (!on) {
            val out = pend
            pend = ""
            return "" to out
        }
        return scan(flush = false)
    }

    /** 吐出尾部残留。流结束（或非流式吃完一次）时必须调用，否则最后几个字符会丢。 */
    fun flush(): Pair<String, String> {
        if (pend.isEmpty()) return "" to ""
        if (!decided) {
            decided = true
            on = pend.startsWith(THINK_OPEN)
            if (!on) {
                val out = pend
                pend = ""
                return "" to out
            }
        }
        if (!on) {
            val out = pend
            pend = ""
            return "" to out
        }
        return scan(flush = true)
    }

    /** 消费 [pend]；flush=false 时把可能是半个标签的尾巴留下等下一片。 */
    private fun scan(flush: Boolean): Pair<String, String> {
        val think = StringBuilder()
        val body = StringBuilder()
        fun emit(s: String) {
            if (s.isEmpty()) return
            if (inTag) think.append(s) else body.append(s)
        }
        while (true) {
            val p = pend
            var idx = -1
            var closing = false
            for ((tag, isClosing) in listOf(THINK_OPEN to false, THINK_CLOSE to true)) {
                val k = p.indexOf(tag)
                if (k >= 0 && (idx < 0 || k < idx)) {
                    idx = k
                    closing = isClosing
                }
            }
            if (idx < 0) {
                var keep = 0
                if (!flush) {
                    // 尾巴可能是下一个标签的前半截（最长 len("</think:")-1）
                    val max = minOf(p.length, THINK_CLOSE.length - 1)
                    for (n in max downTo 1) {
                        val tail = p.substring(p.length - n)
                        if (THINK_CLOSE.startsWith(tail) || THINK_OPEN.startsWith(tail)) {
                            keep = n
                            break
                        }
                    }
                }
                emit(p.substring(0, p.length - keep))
                pend = p.substring(p.length - keep)
                return think.toString() to body.toString()
            }
            emit(p.substring(0, idx))
            val rest = p.substring(idx)
            val gt = rest.indexOf('>')
            if (gt < 0) {
                // 标签还没收全：留到下一片。flush 时上游已经结束了，按正文发出去，
                // 少一次改写好过丢内容。
                if (flush) {
                    emit(rest)
                    pend = ""
                } else {
                    pend = rest
                }
                return think.toString() to body.toString()
            }
            pend = rest.substring(gt + 1)
            inTag = !closing
        }
    }

    companion object {
        private const val THINK_OPEN = "<think:"
        private const val THINK_CLOSE = "</think:"

        /** 非流式的一次性版本（完整 content 进来，两段出去）。 */
        fun split(s: String): Pair<String, String> {
            val t = ThinkTag()
            val (a1, b1) = t.feed(s)
            val (a2, b2) = t.flush()
            return (a1 + a2) to (b1 + b2)
        }
    }
}
