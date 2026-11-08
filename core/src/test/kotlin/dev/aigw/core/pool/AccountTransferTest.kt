package dev.aigw.core.pool

import dev.aigw.core.provider.ProviderAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 账号导入导出的信封编解码：只测纯转换（不认识账号池），
 * 重点是「挡得住不相干的 JSON」与「不造出没有凭证的空账号」。
 */
class AccountTransferTest {

    private fun account(uid: String, providerId: String = "trae") = ProviderAccount(
        providerId = providerId,
        uid = uid,
        nickname = "昵称$uid",
        secret = """{"uid":"$uid","accessToken":"at-$uid"}""",
    )

    @Test
    fun `导出再导入能拿回同样的账号`() {
        val source = listOf(account("a"), account("b", providerId = "loomy"))
        val json = AccountTransfer.encode(source, exportedAt = 123L)
        val envelope = AccountTransfer.decode(json)
        assertEquals(AccountTransfer.VERSION, envelope.version)
        assertEquals(123L, envelope.exportedAt)
        assertEquals(source, envelope.accounts)
    }

    @Test
    fun `不是本应用导出的文件要被拒绝`() {
        val e = assertFailsWith<IllegalArgumentException> {
            AccountTransfer.decode("""{"foo":"bar","accounts":[]}""")
        }
        assertTrue(e.message!!.contains("不是本应用导出的账号文件"), e.message!!)
    }

    @Test
    fun `高于当前版本的文件要被拒绝`() {
        val json = """{"format":"aigw-accounts","version":99,"exportedAt":0,"accounts":[]}"""
        val e = assertFailsWith<IllegalArgumentException> { AccountTransfer.decode(json) }
        assertTrue(e.message!!.contains("版本 99 不支持"), e.message!!)
    }

    @Test
    fun `缺字段或空凭证的条目被丢掉，而不是造出空账号`() {
        val json = """
            {"format":"aigw-accounts","version":1,"exportedAt":0,"accounts":[
              {"providerId":"trae","uid":"a","nickname":"甲","secret":"{x}"},
              {"providerId":"trae","uid":"b","nickname":"乙"},
              {"providerId":"","uid":"c","secret":"{x}"}
            ]}
        """.trimIndent()
        val envelope = AccountTransfer.decode(json)
        assertEquals(1, envelope.accounts.size)
        assertEquals("a", envelope.accounts[0].uid)
    }

    @Test
    fun `空内容与非法 JSON 给可读报错`() {
        assertTrue(
            assertFailsWith<IllegalArgumentException> { AccountTransfer.decode("  ") }
                .message!!.contains("内容为空"),
        )
        assertTrue(
            assertFailsWith<IllegalArgumentException> { AccountTransfer.decode("{oops") }
                .message!!.contains("不是合法的 JSON"),
        )
    }

    @Test
    fun `解析出全部条目都不可用时报错而不是静默成功`() {
        val json = """{"format":"aigw-accounts","version":1,"exportedAt":0,"accounts":[{"providerId":"trae","uid":"a"}]}"""
        val e = assertFailsWith<IllegalArgumentException> { AccountTransfer.decode(json) }
        assertTrue(e.message!!.contains("没有可用的账号"), e.message!!)
    }
}
