package dev.aigw.core.provider

import kotlin.test.Test
import kotlin.test.assertEquals

class ThinkTagTest {

    @Test
    fun `带后缀的 think 标签拆成思考与正文`() {
        val (think, body) = ThinkTag.split("<think:6124c78e>先看代码</think:6124c78e>答案是 42")
        assertEquals("先看代码", think)
        assertEquals("答案是 42", body)
    }

    @Test
    fun `任意切分位置都要拼回同一结果`() {
        val full = "<think:ab12>CoT</think:ab12>Answer"
        for (cut in 1 until full.length) {
            val t = ThinkTag()
            val sb = StringBuilder()
            val bb = StringBuilder()
            for (piece in listOf(full.substring(0, cut), full.substring(cut))) {
                val (a, b) = t.feed(piece)
                sb.append(a)
                bb.append(b)
            }
            val (a2, b2) = t.flush()
            sb.append(a2)
            bb.append(b2)
            assertEquals("CoT", sb.toString(), "cut=$cut")
            assertEquals("Answer", bb.toString(), "cut=$cut")
        }
    }

    @Test
    fun `正文中间提到标签不改写`() {
        val s = "文档里写 <think:abc> 是控制 token"
        val (think, body) = ThinkTag.split(s)
        assertEquals("", think)
        assertEquals(s, body)
    }

    @Test
    fun `没命中时首片立即吐出`() {
        val t = ThinkTag()
        val (think, body) = t.feed("你好")
        assertEquals("", think)
        assertEquals("你好", body)
    }

    @Test
    fun `只有半截开头也要等齐再判定`() {
        val t = ThinkTag()
        assertEquals("" to "", t.feed("<thi"))
        val (think, body) = t.feed("nk:ab>CoT</think:ab>正文")
        assertEquals("CoT", think)
        assertEquals("正文", body)
    }
}
