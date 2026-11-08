package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonObject
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.ACTION_TASKS
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 成长任务的「接单 / 领奖」协议形状，以及不适用场景的口径。
 *
 * 参考实现：`ardeyouxipianyi/workbuddy2api-hub` 的 `wb_tasks.py`
 * （accept 发 `{"task_codes":[...]}`、领奖走 `POST /activity/growth/tasks/<code>/claim`）。
 */
class CodeBuddyGrowthActionTest {

    private val provider = CodeBuddyProvider(region = { CodeBuddyProvider.REGION_CN })

    private fun account(secret: String) = assertNotNull(provider.importCredentials(secret))

    private val cnAccount = account("""{"accessToken":"tok","uid":"u1","domain":"www.codebuddy.cn"}""")

    @Test
    fun `接单请求体是 task_codes 数组`() {
        val body = provider.acceptPayload(listOf("chat_5", "first_buddy"))
        val obj = com.google.gson.JsonParser.parseString(body).asJsonObject
        val codes = obj.getAsJsonArray("task_codes").map { it.asString }
        assertTrue(codes == listOf("chat_5", "first_buddy"), "实际：$codes")
        // 单数形式是旧的错误形状，上游按批接收
        assertFalse(obj.has("task_code"), "不应再发单个 task_code：$body")
    }

    @Test
    fun `国际版账号不适用签到与成长中心`() {
        val global = account(
            """{"accessToken":"tok","uid":"g1","domain":"workbuddy.ai"}""",
        )
        // 选目标时就该把它排掉，而不是跑完再报「国际版没有⋯」
        for (action in listOf(ACTION_CHECKIN, ACTION_TASKS)) {
            assertFalse(
                provider.supportsAction(global, action),
                "国际版没有签到/成长中心，不应进入批量：$action",
            )
        }
    }

    @Test
    fun `国内个人账号适用签到与成长中心`() {
        for (action in listOf(ACTION_CHECKIN, ACTION_TASKS)) {
            assertTrue(provider.supportsAction(cnAccount, action), action)
        }
    }

    @Test
    fun `企业账号不适用个人成长体系`() {
        val enterprise = account(
            """{"accessToken":"tok","uid":"e1","enterpriseId":"ent-1","domain":"www.codebuddy.cn"}""",
        )
        assertFalse(provider.supportsAction(enterprise, ACTION_CHECKIN))
        assertFalse(provider.supportsAction(enterprise, ACTION_TASKS))
    }

    @Test
    fun `不支持的动作用 unsupported 表示`() {
        val result = provider.performAction(cnAccount, "something-else", JsonObject())
        assertTrue(!result.ok && result.unsupported)
    }

    // ------------------------------------------------------- 授权登录请求头

    @Test
    fun `登录端点带声明无鉴权的必需头`() {
        // 缺这组头时上游会把请求当「缺凭据的普通请求」拒掉，表现为登录完回 App 报错
        val headers = provider.authHeaders("https://copilot.tencent.com", withBody = true)
        for (key in listOf(
            "X-No-Authorization",
            "X-No-User-Id",
            "X-No-Enterprise-Id",
            "X-No-Department-Info",
            "X-Requested-With",
            "X-Product",
            "X-Domain",
            "X-Request-ID",
        )) {
            assertTrue(headers.containsKey(key), "缺头 $key：$headers")
        }
        assertTrue(headers["X-No-Authorization"] == "true")
        assertTrue(headers.containsKey("Content-Type"), "POST 要带 Content-Type")
        // 轮询是 GET，不需要 Content-Type
        assertFalse(provider.authHeaders("https://copilot.tencent.com", withBody = false).containsKey("Content-Type"))
    }

    @Test
    fun `每次请求的 X-Request-ID 都不同`() {
        val first = provider.authHeaders("https://copilot.tencent.com", withBody = false)
        val second = provider.authHeaders("https://copilot.tencent.com", withBody = false)
        assertTrue(first["X-Request-ID"] != second["X-Request-ID"], "追踪 ID 不应固定")
    }
}
