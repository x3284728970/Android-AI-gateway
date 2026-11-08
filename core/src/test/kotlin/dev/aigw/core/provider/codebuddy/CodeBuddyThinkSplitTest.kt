package dev.aigw.core.provider.codebuddy

import dev.aigw.core.provider.ThinkTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeBuddyThinkSplitTest {

    @Test
    fun `流式跨片标签拆回 reasoning_content`() {
        val frames = listOf(
            """{"id":"c","choices":[{"index":0,"delta":{"content":"<thi"}}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{"content":"nk:6124c78e>CoT-A</think:6124c78e>Ans"}}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{"content":"wer"}}]}""",
        )
        val tag = ThinkTag()
        var out = ""
        for (f in frames) out += splitThinkInFrame(f, tag) + "\n"
        assertTrue("<think" !in out && "6124c78e" !in out, "控制 token 泄漏：$out")
        assertTrue(""""reasoning_content":"CoT-A"""" in out, "思考应进 reasoning_content：$out")
        assertTrue(""""content":"Ans"""" in out && """"content":"wer"""" in out, "正文应完整：$out")
    }

    @Test
    fun `纯正文帧原样返回`() {
        val tag = ThinkTag()
        val frame = """{"id":"c","choices":[{"index":0,"delta":{"content":"普通正文"}}]}"""
        assertEquals(frame, splitThinkInFrame(frame, tag))
    }

    @Test
    fun `非 JSON 帧原样返回`() {
        val tag = ThinkTag()
        assertEquals("not json", splitThinkInFrame("not json", tag))
    }

    @Test
    fun `非流式整段拆分`() {
        val body = """{"choices":[{"index":0,"message":{"role":"assistant","content":"<think:ab>CoT</think:ab>Answer"}}]}"""
        val out = CodeBuddyProviderSplitAccess.split(body)
        assertTrue(""""reasoning_content":"CoT"""" in out, out)
        assertTrue(""""content":"Answer"""" in out, out)
        assertTrue("<think" !in out, out)
    }

    @Test
    fun `正文中间提到标签不改写`() {
        val body = """{"choices":[{"index":0,"message":{"role":"assistant","content":"说明里提到 <think:abc> 这种 token"}}]}"""
        assertEquals(body, CodeBuddyProviderSplitAccess.split(body))
    }
}

/** splitThinkInAggregated 是 private，测试经由这个薄入口。 */
object CodeBuddyProviderSplitAccess {
    fun split(body: String): String = splitThinkForTest(body)
}
