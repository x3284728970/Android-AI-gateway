package dev.aigw.core.provider.qoder

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.cosy.Cosy
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.newCompletionId
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * Qoder 推理通道（COSY 签名 + SSE）。
 *
 * 链路：构造 Qoder body → COSY 签名 → POST 网关 → **剥外层信封**
 * （上游把每帧包成 `{"body":"<内层 OpenAI chunk>"}`）→ 转发内层 chunk。
 *
 * 两个实测坑（来自 dumate2api `src/qoder/chat.js`）：
 * 1. **排队时先回一帧空内容再沉默**——只看「有没有数据」会白等两分钟，
 *    判据必须是「有没有真正的内容帧」。
 * 2. 限流会在**接受连接后一直不回**（180s 才 abort），首字节要单独设上限。
 */
class QoderChatClient(
    private val account: QoderAccount,
    private val sessionProvider: (QoderAccount) -> CosySession,
) {

    /** COSY 会话材料（cosyKey / info / 身份），按账号缓存。 */
    data class CosySession(val cosyKey: String, val info: String)

    fun open(openAiBody: String): ChatCall {
        val request = runCatching { JsonParser.parseString(openAiBody).asJsonObject }
            .getOrElse { return FailedChatCall(400, "请求体不是合法 JSON") }
        val modelKey = request.stringOrNull("model").orEmpty().ifEmpty { "pro" }
        val json = buildBody(modelKey, request)
        // **body 必须过自定义 base64（URL 上的 Encode=1）**：上游期望的是编码后的
        // 字符串，直接发原始 JSON 会被 400 拒。签名里用的也是编码后的 body。
        val body = Cosy.encode(json)

        val session = sessionProvider(account)
        val payloadB64 = Cosy.buildPayloadB64(session.info, QoderConstants.COSY_VERSION)
        val date = (System.currentTimeMillis() / 1000).toString()
        val sig = Cosy.signRequest(payloadB64, session.cosyKey, date, body, CHAT_PATH_SIG)
        val bearer = Cosy.composeBearer(payloadB64, sig)

        // 头清单逐条对齐 dumate2api `src/qoder/cosy.js:55-77`：
        // scene/business/product 这三个缺了服务端会按别的产品线解读。
        val headers = mapOf(
            "cosy-data-policy" to "agree",
            "content-type" to "application/json",
            "cosy-machinetype" to QoderConstants.MACHINE_TYPE,
            "cosy-clienttype" to QoderConstants.CLIENT_TYPE,
            "cosy-date" to date,
            "cosy-user" to account.uid,
            "cosy-key" to session.cosyKey,
            "cosy-version" to QoderConstants.COSY_VERSION,
            "cosy-machineid" to account.machineId,
            "cosy-machinetoken" to "",
            "login-version" to QoderConstants.LOGIN_VERSION,
            "cosy-scene" to QoderConstants.SCENE,
            "cosy-business-product" to QoderConstants.BUSINESS_PRODUCT,
            "cosy-business-type" to QoderConstants.BUSINESS_TYPE,
            "cache-control" to "no-cache",
            "accept" to "text/event-stream",
            "accept-encoding" to "identity",
            "authorization" to bearer,
            "user-agent" to QoderConstants.UA,
        )

        val conn = try {
            HttpSupport.open(
                url = account.region.gateway + QoderConstants.CHAT_PATH,
                method = "POST",
                headers = headers,
                body = body,
                connectTimeoutMs = QoderConstants.CONNECT_TIMEOUT_MS,
                readTimeoutMs = QoderConstants.READ_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            return FailedChatCall(502, "连接 Qoder 失败：${e.message}")
        }

        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(502, "Qoder 未返回响应：${e.message}")
        }
        if (status !in 200..299) {
            val text = runCatching { HttpSupport.readBody(conn) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, text.ifEmpty { "上游 HTTP $status" })
        }
        val stream: InputStream = try {
            conn.inputStream
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(status, "读取流失败：${e.message}")
        }
        return StreamingChatCall(status, UnwrapStream(stream), onClose = { conn.disconnect() })
    }

    /**
     * 拉模型目录。**必须走 COSY 签名**（裸 Bearer 会被 401 拒），
     * 条目里 `key` 是模型名、`price_factor` 是倍率。
     *
     * 响应按 **scene 分桶**（`chat` / `app` …），个别模型只出现在单一 scene 里
     * （QoDuck=`lmodel` 只在 `app`），只读 `chat` 会把它们整条丢掉——不只是列表里看不见，
     * 路由也会判成不支持。这里按 scene 顺序合并、同名以先出现的为准（chat 优先）。
     * 参考：EchoPing07/Qoder-2API-Go 2026-11-08 的同名修复。
     */
    fun fetchModels(): List<String>? {
        val sess = sessionProvider(account)
        val payloadB64 = Cosy.buildPayloadB64(sess.info, QoderConstants.COSY_VERSION)
        val date = (System.currentTimeMillis() / 1000).toString()
        val sig = Cosy.signRequest(payloadB64, sess.cosyKey, date, "", MODELS_PATH_SIG)
        val headers = mapOf(
            "cosy-data-policy" to "agree",
            "cosy-machinetype" to QoderConstants.MACHINE_TYPE,
            "cosy-clienttype" to QoderConstants.CLIENT_TYPE,
            "cosy-date" to date,
            "cosy-user" to account.uid,
            "cosy-key" to sess.cosyKey,
            "cosy-version" to QoderConstants.COSY_VERSION,
            "cosy-machineid" to account.machineId,
            "cosy-machinetoken" to "",
            "login-version" to QoderConstants.LOGIN_VERSION,
            "cosy-scene" to QoderConstants.SCENE,
            "cosy-business-product" to QoderConstants.BUSINESS_PRODUCT,
            "cosy-business-type" to QoderConstants.BUSINESS_TYPE,
            "accept" to "application/json",
            "accept-encoding" to "identity",
            "authorization" to Cosy.composeBearer(payloadB64, sig),
            "user-agent" to QoderConstants.UA,
        )
        val conn = try {
            HttpSupport.open(
                url = account.region.gateway + QoderConstants.MODELS_PATH,
                method = "GET",
                headers = headers,
                body = null,
                connectTimeoutMs = QoderConstants.CONNECT_TIMEOUT_MS,
                readTimeoutMs = 20_000,
            )
        } catch (e: Exception) {
            throw IllegalStateException("拉模型目录失败：${e.message}")
        }
        val status = conn.responseCode
        val body = runCatching { HttpSupport.readBody(conn) }.getOrDefault("")
        conn.disconnect()
        if (status !in 200..299) {
            throw IllegalStateException("拉模型目录失败：HTTP $status ${body.take(120)}")
        }
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("模型目录响应不是 JSON：${body.take(120)}")
        val scenes = listOf("chat", "app")
        val keys = LinkedHashSet<String>()
        for (scene in scenes) {
            val list = root.arrayOrNull(scene) ?: continue
            for (item in list) {
                val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                if (obj.get("enable")?.let { it.isJsonPrimitive && !it.asBoolean } == true) continue
                val key = obj.stringOrNull("key").orEmpty()
                if (key.isEmpty() || key == "auto") continue
                keys += key
            }
        }
        if (keys.isEmpty()) {
            // 老响应形态：直接给数组（data 或裸数组），没有 scene 分桶
            val list = root.arrayOrNull("data") ?: return null
            list.mapNotNull { item ->
                val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                if (obj.get("enable")?.let { it.isJsonPrimitive && !it.asBoolean } == true) return@mapNotNull null
                obj.stringOrNull("key").orEmpty().takeIf { it.isNotEmpty() }
            }.ifEmpty { null }?.let { return it }
        }
        return keys.toList().ifEmpty { null }
    }

    /**
     * 把上游的帧流转换成本项目其它供应商统一的「内层 OpenAI chunk」流。
     *
     * 上游每帧形如 `data: {"body":"{\"choices\":[...]}"}`；`body` 里才是真 chunk。
     * 空帧（`{}` / 空 delta）直接丢掉——它们在排队阶段会连续出现。
     */
    private class UnwrapStream(private val source: InputStream) : InputStream() {
        private val buffer = ArrayDeque<Byte>()
        private val reader = source.bufferedReader(Charsets.UTF_8)
        private var finished = false

        override fun read(): Int {
            if (buffer.isEmpty() && !fill()) return -1
            return buffer.removeFirst().toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (buffer.isEmpty() && !fill()) return -1
            var count = 0
            while (count < len && buffer.isNotEmpty()) {
                b[off + count] = buffer.removeFirst()
                count++
            }
            return count
        }

        override fun close() {
            runCatching { source.close() }
        }

        /** 读一行上游数据，转换后压进输出缓冲；没有可输出内容就继续读。 */
        private fun fill(): Boolean {
            while (!finished) {
                val line = reader.readLine() ?: run {
                    finished = true
                    return false
                }
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (!trimmed.startsWith("data:")) continue
                val payload = trimmed.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") {
                    emit("data: [DONE]\n\n")
                    return true
                }
                val inner = unwrap(payload) ?: continue
                if (inner.isEmpty()) continue
                emit("data: " + inner + "\n\n")
                return true
            }
            return false
        }

        private fun emit(text: String) {
            for (byte in text.toByteArray(Charsets.UTF_8)) buffer.addLast(byte)
        }
    }

    // ------------------------------------------------------------------ body

    /**
     * 构造 Qoder 的请求体。
     *
     * 骨架来自 dumate2api `src/qoder/chat.js:96-190`（与官方 baseprompt 对齐），
     * 只保留对话必需字段：会话体会把 `messages` 原样透传（上游认 messages 里的
     * system 角色），`chat_context.text` 放最后一条用户消息。
     */
    private fun buildBody(modelKey: String, request: JsonObject): String {
        val requestId = Cosy.newUuid()
        val messages = request.arrayOrNull("messages") ?: JsonArray()
        val system = request.stringOrNull("system").orEmpty()
        val lastUser = messages
            .mapNotNull { it.takeIf { m -> m.isJsonObject }?.asJsonObject }
            .lastOrNull { it.stringOrNull("role") == "user" }
            ?.stringOrNull("content")
            .orEmpty()

        val parameters = JsonObject().apply {
            for (key in listOf("temperature", "top_p", "max_tokens", "presence_penalty", "frequency_penalty")) {
                request.get(key)?.let { add(key, it) }
            }
            if (!has("max_tokens")) addProperty("max_tokens", 32_000)
        }
        // 工具只留标准 OpenAI 形状：Codex 附带的 namespace/web_search 会让整轮失败
        val tools = JsonArray()
        request.arrayOrNull("tools")?.forEach { item ->
            val obj = item.takeIf { t -> t.isJsonObject }?.asJsonObject ?: return@forEach
            val fn = obj.objOrNull("function") ?: return@forEach
            if (fn.stringOrNull("name").isNullOrEmpty()) return@forEach
            tools.add(obj)
        }

        val body = JsonObject().apply {
            addProperty("request_id", requestId)
            addProperty("request_set_id", requestId)
            addProperty("chat_record_id", requestId)
            addProperty("session_id", request.stringOrNull("session_id").orEmpty().ifEmpty { Cosy.newUuid() })
            addProperty("stream", true) // 上游只有 SSE，非流式由网关聚合
            addProperty("chat_task", "FREE_INPUT")
            add("chat_context", JsonObject().apply {
                addProperty("text", lastUser)
                add("features", JsonArray())
                add("extra", JsonObject().apply {
                    add("context", JsonArray())
                    add("modelConfig", JsonObject().apply {
                        addProperty("key", modelKey)
                        addProperty("is_reasoning", false)
                    })
                    addProperty("originalContent", lastUser)
                    addProperty("chatPrompt", "")
                })
                addProperty("chatPrompt", "")
                add("imageUrls", com.google.gson.JsonNull.INSTANCE)
            })
            addProperty("is_reply", true)
            addProperty("is_retry", false)
            addProperty("source", 1)
            addProperty("version", "3")
            addProperty("agent_id", "agent_common")
            addProperty("task_id", "common")
            addProperty("session_type", "qoder_work")
            addProperty("aliyun_user_type", "")
            add("model_config", JsonObject().apply {
                addProperty("key", modelKey)
                addProperty("display_name", modelKey)
                addProperty("model", "")
                addProperty("format", "openai")
                addProperty("is_vl", true)
                addProperty("is_reasoning", false)
                addProperty("api_key", "")
                addProperty("url", "")
                addProperty("source", "system")
                addProperty("max_input_tokens", 200_000)
            })
            add("business", JsonObject().apply {
                addProperty("product", "qoder_work")
                addProperty("version", QoderConstants.COSY_VERSION)
                addProperty("type", "agent")
                addProperty("id", Cosy.newUuid())
                addProperty("name", "")
                addProperty("begin_at", System.currentTimeMillis())
                addProperty("stage", "processing")
            })
            addProperty("system", system)
            add("messages", messages)
            add("tools", tools)
            add("parameters", parameters)
        }
        return body.toString()
    }

    companion object {
        /** pathSig 是去掉 `/algo` 前缀的 path（参考实现 constants.js:43）。 */
        private const val CHAT_PATH_SIG = "/api/v2/service/pro/sse/agent_chat_generation"

        /** 模型列表的 pathSig。 */
        private const val MODELS_PATH_SIG = "/api/v2/model/list"

        /**
         * 剥外层信封，返回内层 chunk 文本；空帧返回 null。
         *
         * 上游三种形状都要认：直接是 chunk、包在 `body` 里、`body` 是对象。
         */
        fun unwrap(raw: String): String? {
            val text = raw.trim()
            if (text.isEmpty() || text == "{}" || text == "null" || text == "[DONE]") return null
            val outer = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                ?: return text
            if (outer.has("choices") || outer.stringOrNull("object").orEmpty().startsWith("chat.completion")) {
                return text
            }
            val inner = outer.get("body") ?: return null
            if (inner.isJsonNull) return null
            val dumped = if (inner.isJsonPrimitive) inner.asString else inner.toString()
            val trimmed = dumped.trim()
            if (trimmed.isEmpty() || trimmed == "{}" || trimmed == "[]" || trimmed == "null") return null
            return trimmed
        }

        /** 这一帧里有没有真正的内容（文本或 reasoning）；排队阶段全是空 delta。 */
        fun hasContent(chunk: String): Boolean {
            val obj = runCatching { JsonParser.parseString(chunk).asJsonObject }.getOrNull() ?: return false
            val choice = obj.arrayOrNull("choices")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return false
            val delta = choice.objOrNull("delta") ?: choice.objOrNull("message") ?: return false
            return !delta.stringOrNull("content").isNullOrEmpty() ||
                !delta.stringOrNull("reasoning_content").isNullOrEmpty()
        }

        fun completionId(): String = newCompletionId()
    }
}
