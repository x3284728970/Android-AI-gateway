package dev.aigw.core.provider.loomy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.ProviderAccount
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 新手任务链路回归：列表映射，以及「一键完成」遇到单条被上游拒时的行为。
 *
 * 这些任务本身对应真实动作（首次对话、生成 PPT 等），服务端会校验，所以「一键」必然有失败的可能；
 * 关键是不能让一条失败拖垮整批，也不能把已经完成的那几条一起丢掉。
 */
class LoomyTaskTest {

    /** 八个任务 key 全列出、只留 [pending] 里的未完成：任务清单是客户端写死的注册表，服务端只回完成状态。 */
    private fun tasksBody(vararg pending: String): String {
        val done = LoomyTaskClient.TASK_POINTS.keys.filter { it !in pending }
        val entries = done.joinToString(",") { """"$it":true""" }
        return """{"code":"000000","data":{"tasks":{$entries}}}"""
    }

    private fun account(): ProviderAccount = ProviderAccount(
        providerId = LoomyProvider.ID,
        uid = "u1",
        nickname = "测试",
        secret = LoomyAccount(uid = "u1", phone = "13800000000", session = "st").toJson().toString(),
    )

    private fun provider(base: String) = LoomyProvider(
        defaultModel = { "x1" },
        taskClient = LoomyTaskClient(base),
    )

    /** 假上游：任务列表固定回 body，完成任务按 key 回对应响应（缺省为成功）。 */
    private fun withTaskUpstream(
        tasksJson: String,
        completeByKey: Map<String, String> = emptyMap(),
        block: (baseUrl: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(LoomyConstants.PATH_ONBOARDING_TASKS) { exchange ->
            respond(exchange, tasksJson)
        }
        server.createContext(LoomyConstants.PATH_ONBOARDING_COMPLETE) { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val key = runCatching {
                JsonParser.parseString(body).asJsonObject.get("key").asString
            }.getOrDefault("")
            respond(exchange, completeByKey[key] ?: """{"code":"000000","data":{}}""")
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Test
    fun `taskList 映射完成状态与积分`() = withTaskUpstream(tasksJson = tasksBody("pick_skill")) { base ->
        val view = assertNotNull(provider(base).taskList(account()))
        assertEquals("", view.error)
        assertEquals(LoomyTaskClient.TASK_POINTS.size, view.tasks.size)
        val first = view.tasks.first { it.code == "first_message" }
        assertTrue(first.completed, "服务端说完成了，界面就该显示完成")
        assertEquals(500L, first.rewardCredit)
        val pick = view.tasks.first { it.code == "pick_skill" }
        assertFalse(pick.completed)
        assertEquals(1000L, pick.rewardCredit)
    }

    @Test
    fun `一键完成遇到单条被拒时不中断整批`() = withTaskUpstream(
        tasksJson = tasksBody("first_message", "pick_skill"),
        completeByKey = mapOf(
            "first_message" to """{"code":"000000","data":{}}""",
            // 上游用业务错误码拒掉这一条（HTTP 仍是 200）
            "pick_skill" to """{"code":"120001","desc":"task not satisfied"}""",
        ),
    ) { base ->
        val result = provider(base).performAction(account(), ACTION_TASKS, JsonObject())
        assertFalse(result.ok, "有任务没完成就不该报成功：${result.message}")
        assertTrue(result.message.contains("完成首次对话（已完成）"), result.message)
        assertTrue(result.message.contains("未能完成"), result.message)
        assertTrue(result.message.contains("选择技能"), "失败的那条也要点名：${result.message}")
    }

    @Test
    fun `一键完成全部成功时报成功`() = withTaskUpstream(
        tasksJson = tasksBody("first_message", "pick_skill"),
    ) { base ->
        val result = provider(base).performAction(account(), ACTION_TASKS, JsonObject())
        assertTrue(result.ok, result.message)
        // 没有一条被拒时不该出现失败段
        assertFalse(result.message.contains("未能完成"), result.message)
    }

    @Test
    fun `所有任务已完成时不打上游写接口直接返回`() = withTaskUpstream(
        tasksJson = tasksBody(),
    ) { base ->
        val result = provider(base).performAction(account(), ACTION_TASKS, JsonObject())
        assertTrue(result.ok, result.message)
        assertEquals("所有任务都已完成", result.message)
    }
}
