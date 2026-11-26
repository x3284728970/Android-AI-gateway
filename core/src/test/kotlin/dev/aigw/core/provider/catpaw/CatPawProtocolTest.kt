package dev.aigw.core.provider.catpaw

import dev.aigw.core.provider.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 妙手协议的纯逻辑部分：提示词拍平、能力边界判定、累积帧差分。 */
class CatPawProtocolTest {

    private fun body(json: String) = com.google.gson.JsonParser.parseString(json).asJsonObject

    @Test
    fun `单条用户消息原样发送不加标签`() {
        val provider = CatPawProvider()
        val prompt = provider.debugBuildPrompt(
            body("""{"messages":[{"role":"user","content":"你好"}]}"""),
        )
        assertEquals("你好", prompt)
    }

    @Test
    fun `多轮消息按角色标签拍平`() {
        val provider = CatPawProvider()
        val prompt = provider.debugBuildPrompt(
            body(
                """
                {"messages":[
                  {"role":"system","content":"你是助手"},
                  {"role":"user","content":"问"},
                  {"role":"assistant","content":"答"}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals("[System Instructions]\n你是助手\n\n[User]\n问\n\n[Assistant]\n答", prompt)
    }

    @Test
    fun `工具定义与工具历史被明确拒绝`() {
        val provider = CatPawProvider()
        assertNotNull(provider.debugUnsupportedReason(
            body("""{"messages":[{"role":"user","content":"hi"}],"tools":[{"type":"function"}]}"""),
        ))
        assertNotNull(provider.debugUnsupportedReason(
            body("""{"messages":[{"role":"assistant","content":"","tool_calls":[]}]}"""),
        ))
        assertNull(provider.debugUnsupportedReason(
            body("""{"messages":[{"role":"user","content":"hi"}]}"""),
        ))
    }

    @Test
    fun `图片分块被拒绝而文本分块通过`() {
        val provider = CatPawProvider()
        assertNotNull(
            provider.debugUnsupportedReason(
                body(
                    """{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"x"}}]}]}""",
                ),
            ),
        )
        assertNull(
            provider.debugUnsupportedReason(
                body("""{"messages":[{"role":"user","content":[{"type":"text","text":"a"}]}]}"""),
            ),
        )
    }

    @Test
    fun `错误分类把余额不足归到额度`() {
        val provider = CatPawProvider()
        val error = provider.classify(402, """{"error":{"message":"当前余额不足以完成本次操作"}}""")
        assertEquals(ErrorKind.QUOTA, error.kind)
        assertEquals(ErrorKind.SESSION_DEAD, provider.classify(401, "{}").kind)
        assertEquals(ErrorKind.SOFT_RATE, provider.classify(429, "{}").kind)
    }
}