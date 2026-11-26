package dev.aigw.core.provider.catpaw

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.CreditUnit
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.DeviceAuthTicket
import dev.aigw.core.provider.DeviceCodeSupport
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 妙手 CatPaw（美团）供应商。
 *
 * ## 协议形状
 *
 * 一次对话要连打三个接口，缺一步 upstream 就不出内容：
 *
 * ```
 * POST /api/agent/conversation/round  → 建会话（拿 conversationId）
 * POST /api/agent/conversation/event  → 标记 running
 * POST /api/agent/conversation/turn   → SSE，答案在这里
 * ```
 *
 * **turn 的 SSE 是「累积式」的**：每一帧给的是「到目前为止的全文」，不是增量。
 * 所以必须与上一帧做前缀差分才能还原 delta——直接透传会让客户端看到
 * 重复叠加的文本（今天 A、明天 A明天 A明天 B）。
 *
 * ## 能力边界（不是 bug，是上游限制）
 *
 * - 只接受扁平文本：工具定义、工具调用历史、图片都进不去，一律显式拒绝（400），
 *   由网关换号或让客户端知道该降级，而不是悄悄丢掉内容后给一个看似正常的回答。
 * - 没有 `/v1/models` 之外的图片/语音能力。
 */
class CatPawProvider(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider, DeviceCodeSupport {

    override val id: String = CatPawConstants.ID
    override val displayName: String = "妙手 CatPaw"
    override val authKind: AuthKind = AuthKind.DEVICE_CODE
    override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.CREDIT_REFRESH)

    // ------------------------------------------------------------------ 登录

    /**
     * 走官方网页通道，但**不在本机监听回调端口**。
     *
     * 妙手自己有两条通道，这里只用 poll-token 那条：浏览器登录占用 sid 之后，
     * 服务端 GET poll-token?sid= 直接拿到 token（服务端对服务端），
     * 既不用监听本机端口，也不受浏览器私有网络检查（PNA）影响。
     */
    override fun startDeviceAuth(region: String): DeviceAuthTicket {
        val cfg = getJson(CatPawConstants.PASSPORT + CatPawConstants.LOGIN_CONFIG_PATH, null, 10_000)
            ?: throw IllegalStateException("无法连接妙手登录服务，请检查网络后重试")
        val entry = cfg.objOrNull("data")?.str("loginEntryUrl").orEmpty()
        if (entry.isEmpty()) throw IllegalStateException("妙手登录服务未返回登录入口地址")
        val state = hex32()
        val sid = hex32()
        val sep = if (entry.contains('?')) '&' else '?'
        val redirect = URLEncoder.encode("http://127.0.0.1:9/catpaw-callback", "UTF-8")
        // sid 既是票号也是轮询参数，用 state 作为对外的 ticket state 让界面不必理解两个概念
        val url = "$entry$sep" + "state=$state&sid=$sid&redirect=$redirect"
        return DeviceAuthTicket(loginUrl = url, state = sid)
    }

    override fun pollDeviceAuth(state: String, region: String): DeviceAuthPoll {
        val url = CatPawConstants.PASSPORT + CatPawConstants.POLL_TOKEN_PATH +
            "?sid=" + URLEncoder.encode(state, "UTF-8")
        val json = getJson(url, null, 5_000) ?: return DeviceAuthPoll.Pending
        val data = json.get("data")
        val token = when {
            data == null || data.isJsonNull -> ""
            data.isJsonPrimitive -> data.asString.trim()
            data.isJsonObject -> data.asJsonObject.str("token").ifEmpty { data.asJsonObject.str("passportToken") }
            else -> ""
        }
        if (token.isEmpty()) return DeviceAuthPoll.Pending
        val (uid, name) = currentUser(token)
        val account = CatPawAccount(token = token, uid = uid, nickname = name)
        return DeviceAuthPoll.Success(
            ProviderAccount(
                providerId = id,
                uid = account.effectiveUid,
                nickname = account.effectiveNickname,
                secret = account.toJsonString(),
            ),
        )
    }

    /** 补 uid / 昵称；失败不影响登录（token 已经在手）。 */
    private fun currentUser(token: String): Pair<String, String> {
        val json = getJson(
            CatPawConstants.PASSPORT + CatPawConstants.CURRENT_USER_PATH,
            mapOf("Accept" to "application/json", "X-Auth-Token" to token),
            10_000,
        ) ?: return "" to ""
        val data = json.objOrNull("data") ?: return "" to ""
        val uid = data.str("userId").takeIf { it != "null" }.orEmpty()
        val name = data.str("userName")
        return uid to name
    }

    override fun importCredentials(raw: String): ProviderAccount? {
        val account = CatPawAccount.parse(raw) ?: return null
        val (uid, name) = currentUser(account.token)
        val merged = account.copy(uid = account.uid.ifEmpty { uid }, nickname = account.nickname.ifEmpty { name })
        // 粘贴的 token 可能已失效：这里不主动判死，交给第一次对话时的 401 处理，
        // 否则一次网络抖动就会让用户以为导入失败。
        return ProviderAccount(
            providerId = id,
            uid = merged.effectiveUid,
            nickname = merged.effectiveNickname,
            secret = merged.toJsonString(),
        )
    }

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        if (account == null) return ProviderModelCatalogView(emptyList(), false, "")
        val credential = CatPawAccount.parse(account.secret)
            ?: return ProviderModelCatalogView(emptyList(), false, "凭证无法解析")
        val body = JsonObject().apply {
            addProperty("tenant", CatPawConstants.CATALOG_TENANT)
            addProperty("scene", CatPawConstants.CATALOG_SCENE)
            addProperty("env", CatPawConstants.CATALOG_ENV)
        }
        val response = postJson(
            CatPawConstants.ORIGIN + CatPawConstants.CATALOG_PATH,
            body.toString(),
            gatewayHeaders(credential.token),
            15_000,
        ) ?: return fallback("模型目录请求失败")
        if (!response.ok) {
            return fallback(
                if (response.status == 401) "登录态已过期，请重新登录妙手"
                else "模型目录 HTTP ${response.status}",
            )
        }
        val root = runCatching { JsonParser.parseString(response.body).asJsonObject }.getOrNull()
            ?: return fallback("模型目录返回非 JSON")
        val code = root.get("code")?.takeIf { it.isJsonPrimitive }?.asInt
        if (code != null && code != 0 && code != 200) {
            return fallback(if (code == 4010 || code == 4011) "登录态已过期，请重新登录妙手" else "模型目录业务拒绝（code=$code）")
        }
        val rows = root.arrayOrNull("data") ?: return fallback("模型目录缺少 data")
        val models = mutableListOf<ProviderModel>()
        for (element in rows) {
            val row = element.asObjectOrNull() ?: continue
            val type = row.get("modelTypeId")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
            if (type < 0 || row.str("provider") == "USER_CUSTOM") continue
            val name = row.str("modelTypeName").trim()
            val modelId = CatPawConstants.idOfType(type)
                ?: name.lowercase().replace(Regex("\\s+"), "-").takeIf { it.isNotEmpty() }
                ?: continue
            if (models.any { it.id == modelId }) continue
            val display = CatPawConstants.displayNameOf(modelId).ifEmpty { name.ifEmpty { modelId } }
            val multiplier = rateMultiplierOf(row)
            models += ProviderModel(
                id = modelId,
                name = display,
                extra = buildMap {
                    if (row.get("supportImage")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) put("vision", "true")
                    // 上游倍率（rateMultiplier）是相对计费倍数：0 表示免费
                    multiplier?.let { m ->
                        put("rate", if (m == 0.0) "免费" else "${formatRate(m)}×")
                        if (m == 0.0) put("rateSummary", "免费模型")
                        put("rateOrigin", "live")
                        put("rateSource", "上游接口")
                    }
                },
            )
        }
        if (models.isEmpty()) return fallback("模型目录为空")
        return ProviderModelCatalogView(models, false, "")
    }

    /** 目录里的 `extendedInfo.rateMultiplier`，允许是数字或 `"0.5x"` 这种字符串。 */
    private fun rateMultiplierOf(row: JsonObject): Double? {
        var ext = row.objOrNull("extendedInfo")
        if (ext == null) {
            val raw = row.get("extendedInfo")?.takeIf { it.isJsonPrimitive }?.asString
            if (raw != null) ext = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        }
        val value = ext?.get("rateMultiplier") ?: return null
        val text = if (value.isJsonPrimitive) value.asString.trim() else return null
        val number = text.replaceFirst(Regex("(?i)\\s*[x×]$"), "").toDoubleOrNull() ?: return null
        if (!number.isFinite() || number < 0 || number > 1000) return null
        return number
    }

    private fun fallback(error: String): ProviderModelCatalogView {
        val models = CatPawConstants.FALLBACK_MODELS.map { modelId ->
            ProviderModel(
                id = modelId,
                name = CatPawConstants.displayNameOf(modelId),
                extra = if (CatPawConstants.acceptsImage(modelId)) mapOf("vision" to "true") else emptyMap(),
            )
        }
        return ProviderModelCatalogView(models, true, error)
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim().substringAfterLast('/')
        return if (model.isEmpty()) CatPawConstants.DEFAULT_MODEL else model
    }

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = CatPawAccount.parse(account.secret)
            ?: return FailedChatCall(401, "凭证无法解析，请重新登录妙手")
        val model = resolveModel(requestedModelOf(openAiBody))
        val parsed = runCatching { JsonParser.parseString(openAiBody).asJsonObject }.getOrNull()
            ?: return FailedChatCall(400, "请求体不是合法 JSON")

        // 上游只吃扁平文本：带工具/图片的请求必须明说不行，不能悄悄截断
        unsupportedReason(parsed)?.let { return FailedChatCall(400, it) }
        val prompt = buildPrompt(parsed).ifEmpty { return FailedChatCall(400, "请求里没有可发送的文本内容") }
        val streaming = isStreamingBody(openAiBody)
        val modelType = CatPawConstants.modelTypeOf(model)
        val conversationId = UUID.randomUUID().toString()
        val messageId = UUID.randomUUID().toString()

        // ① round：建会话
        val roundBody = JsonObject().apply {
            addProperty("conversationId", conversationId)
            add("message", userMessage(messageId, prompt))
            addProperty("modelType", modelType)
            addProperty("source", CatPawConstants.SOURCE)
            addProperty("mode", CatPawConstants.MODE)
            addProperty("permissionMode", "unsafeBypassPermissions")
            addProperty("toolVersion", CatPawConstants.TOOL_VERSION)
            add("repositories", JsonArray())
        }
        val round = postJson(
            CatPawConstants.ORIGIN + CatPawConstants.ROUND_PATH,
            roundBody.toString(),
            gatewayHeaders(credential.token) + spanHeaders("0.2", "conversation.round"),
            30_000,
        ) ?: return FailedChatCall(0, "妙手 round 请求失败（网络）")
        roundFailure(round)?.let { return it }

        // ② event：标记会话运行中
        val eventBody = JsonObject().apply {
            addProperty("conversationId", conversationId)
            addProperty("eventType", "conversation")
            add("data", JsonObject().apply { addProperty("status", "running") })
        }
        val event = postJson(
            CatPawConstants.ORIGIN + CatPawConstants.EVENT_PATH,
            eventBody.toString(),
            gatewayHeaders(credential.token) + spanHeaders("0.3", "conversation.event"),
            30_000,
        ) ?: return FailedChatCall(0, "妙手 event 请求失败（网络）")
        if (event.ok) {
            val evJson = runCatching { JsonParser.parseString(event.body).asJsonObject }.getOrNull()
            val evCode = evJson?.get("code")?.takeIf { it.isJsonPrimitive }?.asInt
            if (evCode != null && evCode != 0) return FailedChatCall(businessStatus(evCode), "妙手会话状态设置失败（code=$evCode）")
        }

        // ③ turn：SSE
        val turnBody = JsonObject().apply {
            addProperty("conversationId", conversationId)
            addProperty("turnRequestId", UUID.randomUUID().toString())
            add("message", userMessage(messageId, prompt))
            addProperty("modelType", modelType)
            addProperty("source", CatPawConstants.SOURCE)
            addProperty("mode", CatPawConstants.MODE)
            addProperty("permissionMode", "unsafeBypassPermissions")
            add("toolConfigs", JsonArray())
            addProperty("toolVersion", CatPawConstants.TOOL_VERSION)
        }
        val turnHeaders = gatewayHeaders(credential.token) + spanHeaders("0.4.1", "conversation.turn") + mapOf(
            "Accept" to "text/event-stream",
            "enableheartbeat" to "true",
        )
        val conn = try {
            HttpSupport.open(
                url = CatPawConstants.ORIGIN + CatPawConstants.TURN_PATH,
                method = "POST",
                headers = turnHeaders,
                body = turnBody.toString(),
                connectTimeoutMs = 15_000,
                readTimeoutMs = TURN_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            return FailedChatCall(0, "妙手 turn 连接失败：${e.message}")
        }
        val status = runCatching { conn.responseCode }.getOrDefault(0)
        if (status !in 200..299) {
            val text = runCatching { HttpSupport.readBody(conn, 64 * 1024) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(businessStatus(status), "妙手 turn HTTP $status：${text.take(200)}")
        }

        val reader = try {
            BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, "妙手 turn 读取失败：${e.message}")
        }

        val turn = TurnStream(reader)
        // 先同步等到第一段内容：此刻还没吐流，失败还能换号
        val first = try {
            turn.advance()
        } catch (e: TurnFailure) {
            closeQuietly(turn, conn)
            return FailedChatCall(e.status, e.message.orEmpty())
        }
        if (first == null) {
            closeQuietly(turn, conn)
            return StreamFailureChatCall(502, UpstreamError(ErrorKind.SERVER, "妙手没有返回任何内容", 502))
        }

        val modelName = CatPawConstants.displayNameOf(model)
        return if (streaming) {
            streamCall(turn, conn, modelName, first)
        } else {
            try {
                val full = turn.drain(first)
                AggregatedChatCall(200, completionJson(modelName, full.text, full.reasoning))
            } catch (e: TurnFailure) {
                FailedChatCall(e.status, e.message.orEmpty())
            } finally {
                closeQuietly(turn, conn)
            }
        }
    }

    /** 上游拒绝（非 2xx 或业务 code 非 0）转成统一失败结果。 */
    private fun roundFailure(response: HttpResult): ChatCall? {
        if (!response.ok) {
            val code = runCatching {
                JsonParser.parseString(response.body).asJsonObject.get("code")?.asInt
            }.getOrNull()
            return FailedChatCall(businessStatus(code ?: response.status), "妙手 HTTP ${response.status}：${response.body.take(200)}")
        }
        val json = runCatching { JsonParser.parseString(response.body).asJsonObject }.getOrNull()
            ?: return FailedChatCall(502, "妙手返回非 JSON")
        val code = json.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: return FailedChatCall(502, "妙手 round 响应缺少 code")
        if (code == 0) return null
        return FailedChatCall(businessStatus(code), "妙手 round 失败（code=$code）")
    }

    private fun businessStatus(code: Int): Int = when (code) {
        400, 401, 402, 403, 429 -> code
        4010, 4011 -> 401
        else -> 502
    }

    // ------------------------------------------------------------------ 请求体兼容

    /** 上游表达不了的结构：工具、工具历史、非文本块。返回原因，null 表示可以发。 */
    private fun unsupportedReason(body: JsonObject): String? {
        val tools = body.arrayOrNull("tools")
        if (tools != null && tools.size() > 0) return "妙手不支持工具调用（tools），请改用支持工具的供应商"
        val messages = body.arrayOrNull("messages") ?: return "请求缺少 messages"
        if (messages.size() == 0) return "请求缺少 messages"
        for (element in messages) {
            val message = element.asObjectOrNull() ?: return "消息格式无效"
            val role = message.str("role")
            if (role !in setOf("system", "developer", "user", "assistant")) return "妙手不支持消息角色：$role"
            if (message.has("tool_calls") || message.has("function_call") || message.has("tool_call_id")) {
                return "妙手不支持工具调用历史"
            }
            val content = message.get("content") ?: continue
            if (content.isJsonPrimitive) continue
            if (!content.isJsonArray) return "妙手只支持文本内容"
            for (part in content.asJsonArray) {
                val obj = part.asObjectOrNull() ?: return "妙手只支持文本内容"
                if (obj.str("type") != "text" || obj.get("text")?.isJsonPrimitive != true) {
                    return "妙手只支持文本内容（不接受图片/音频/文件）"
                }
            }
        }
        return null
    }

    /** 把 messages 压成协议唯一能承载的一段文本。单条 user 消息原样发，避免多余的标签。 */
    private fun buildPrompt(body: JsonObject): String {
        val messages = body.arrayOrNull("messages") ?: return ""
        val parts = ArrayList<String>(messages.size())
        for (element in messages) {
            val message = element.asObjectOrNull() ?: continue
            val role = message.str("role", "user")
            val text = contentText(message)
            if (text.isEmpty()) continue
            val label = when (role) {
                "system" -> "[System Instructions]"
                "developer" -> "[System Instructions]"
                "user" -> "[User]"
                "assistant" -> "[Assistant]"
                else -> "[$role]"
            }
            parts += "$label\n$text"
        }
        if (messages.size() == 1 && messages[0].asObjectOrNull()?.str("role") == "user") {
            return contentText(messages[0].asObjectOrNull()!!)
        }
        return parts.joinToString("\n\n")
    }

    private fun contentText(message: JsonObject): String {
        val content = message.get("content") ?: return ""
        if (content.isJsonPrimitive) return content.asString
        val array = content as? JsonArray ?: return ""
        val sb = StringBuilder()
        for (element in array) {
            val part = element.asObjectOrNull() ?: continue
            when (part.str("type")) {
                "text" -> sb.append(part.str("text"))
                else -> if (part.has("text")) sb.append(part.str("text"))
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ SSE

    /** 一帧内容快照：累积全文 + 累积思考。 */
    private data class Snapshot(val text: String, val reasoning: String)

    /** 流内业务错误（上游 200 但帧里带 error / 余额不足）。 */
    private class TurnFailure(val status: Int, message: String?) : Exception(message)

    /**
     * 累积式 SSE 读取器。
     *
     * 上游每帧给全文，这里只负责「读到新的一帧」；差分由调用方做。
     * 学到两条规矩（与官方 npm 客户端一致）：`data: [DONE]` 才是终止，
     * EOF 不能当成「完整结束」——半截 JSON 前缀如果被当成结果，会把工具名当成正文吐出去。
     */
    private class TurnStream(private val reader: BufferedReader) {
        var text: String = ""
            private set
        var reasoning: String = ""
            private set
        var done: Boolean = false
            private set
        var malformed: Boolean = false
            private set
        private var visible = false

        /** 读下一帧内容；EOF 返回 null。 */
        fun advance(): Snapshot? {
            while (true) {
                val line = reader.readLine() ?: return null
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") {
                    done = true
                    return null
                }
                val frame = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull()
                if (frame == null) {
                    malformed = true
                    continue
                }
                if (frame.has("error") && !frame.get("error").isJsonNull) {
                    val err = frame.get("error")
                    val message = when {
                        err.isJsonObject -> err.asJsonObject.str("message")
                        err.isJsonPrimitive -> err.asString
                        else -> "妙手返回错误"
                    }
                    val code = err.takeIf { it.isJsonObject }?.asJsonObject?.get("code")
                        ?.takeIf { it.isJsonPrimitive }?.asInt
                    if (message.contains(CatPawConstants.INSUFFICIENT_BALANCE)) throw TurnFailure(402, message)
                    throw TurnFailure(businessStatusOf(code), message)
                }
                val message = frame.objOrNull("message") ?: continue
                val content = message.arrayOrNull("content") ?: continue
                var changed = false
                for (element in content) {
                    val c = element.asObjectOrNull() ?: continue
                    if (c.str("type") != "text") continue
                    val t = c.get("text")
                    if (t != null && t.isJsonPrimitive) {
                        val value = t.asString
                        val head = value.trimStart()
                        // 只有「无 model 字段的首帧」才是余额闸门；模型复述这句话是真回答
                        val modelLess = message.get("model") == null || message.get("model").isJsonNull
                        if (!visible && modelLess && head.startsWith(CatPawConstants.INSUFFICIENT_BALANCE)) {
                            throw TurnFailure(402, CatPawConstants.INSUFFICIENT_BALANCE)
                        }
                        if (value != text) {
                            text = value
                            changed = true
                        }
                        if (head.isNotEmpty() && !(modelLess && CatPawConstants.INSUFFICIENT_BALANCE.startsWith(head))) {
                            visible = true
                        }
                    }
                    val r = c.get("reasoningContent")
                    if (r != null && r.isJsonPrimitive) {
                        val value = r.asString
                        if (value != reasoning) {
                            reasoning = value
                            changed = true
                        }
                    }
                }
                if (changed) return Snapshot(text, reasoning)
            }
        }

        /** 读完剩余帧，返回最终快照。 */
        fun drain(from: Snapshot): Snapshot {
            var last = from
            while (true) {
                val next = advance() ?: break
                last = next
            }
            return Snapshot(text.ifEmpty { last.text }, reasoning.ifEmpty { last.reasoning })
        }

        fun close() {
            runCatching { reader.close() }
        }

        private fun businessStatusOf(code: Int?): Int = when {
            code == null -> 502
            code == 400 || code == 401 || code == 402 || code == 403 || code == 429 -> code
            code == 4010 || code == 4011 -> 401
            else -> 502
        }
    }

    /**
     * 伪流式：上游每帧给全文，这里逐帧差分后按 OpenAI SSE 吐出去。
     *
     * 与 TodayProvider 同一套做法——先拿到第一帧再返回流，保证「返回流」时上游确实有内容，
     * 之后在独立线程里继续读，不占网关工作线程。
     */
    private fun streamCall(turn: TurnStream, conn: HttpURLConnection, model: String, first: Snapshot): ChatCall {
        val pipeIn = PipedInputStream(PIPE_BUFFER)
        val pipeOut = PipedOutputStream(pipeIn)
        val closed = AtomicBoolean(false)
        val cid = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")
        val created = nowMillis() / 1000

        val worker = Thread {
            var sentText = ""
            var sentReasoning = ""
            try {
                pipeOut.write(roleChunk(cid, created, model))
                sentText = emitDelta(pipeOut, cid, created, model, first.text, sentText, false)
                sentReasoning = emitDelta(pipeOut, cid, created, model, first.reasoning, sentReasoning, true)
                pipeOut.flush()

                while (!closed.get()) {
                    val next = turn.advance() ?: break
                    sentText = emitDelta(pipeOut, cid, created, model, next.text, sentText, false)
                    sentReasoning = emitDelta(pipeOut, cid, created, model, next.reasoning, sentReasoning, true)
                    pipeOut.flush()
                }
                pipeOut.write(finishChunk(cid, created, model, sentText.length / 3))
                pipeOut.write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
                pipeOut.flush()
            } catch (_: Exception) {
                // 客户端断开（管道关闭）是正常路径，不当作错误
            } finally {
                closeQuietly(turn, conn)
                runCatching { pipeOut.close() }
            }
        }
        worker.isDaemon = true
        worker.name = "catpaw-stream"
        worker.start()

        return StreamingChatCall(200, pipeIn) {
            closed.set(true)
            worker.interrupt()
        }
    }

    /** 累积文本 → 增量。返回本次累计值，供下一帧做基准。 */
    private fun emitDelta(
        out: PipedOutputStream,
        cid: String,
        created: Long,
        model: String,
        cumulative: String,
        sent: String,
        reasoning: Boolean,
    ): String {
        if (cumulative == sent) return sent
        val delta = when {
            cumulative.startsWith(sent) -> cumulative.substring(sent.length)
            // 上游偶尔整段重写（极少见）：把整段当新内容推一次，总比丢字好
            else -> cumulative
        }
        if (delta.isNotEmpty()) {
            out.write(deltaChunk(cid, created, model, delta, reasoning))
            out.flush()
        }
        return cumulative
    }

    // ------------------------------------------------------------------ SSE 输出

    private fun baseChunk(cid: String, created: Long, model: String): JsonObject = JsonObject().apply {
        addProperty("id", cid)
        addProperty("object", "chat.completion.chunk")
        addProperty("created", created)
        addProperty("model", model)
    }

    private fun roleChunk(cid: String, created: Long, model: String): ByteArray {
        val obj = baseChunk(cid, created, model)
        obj.add("choices", choices(JsonObject().apply { addProperty("role", "assistant") }, null))
        return sse(obj)
    }

    private fun deltaChunk(cid: String, created: Long, model: String, delta: String, reasoning: Boolean): ByteArray {
        if (delta.isEmpty()) return ByteArray(0)
        val obj = baseChunk(cid, created, model)
        val payload = JsonObject().apply {
            // 思考内容放在 reasoning_content（DeepSeek/多数客户端认这个键），别塞进正文
            addProperty(if (reasoning) "reasoning_content" else "content", delta)
        }
        obj.add("choices", choices(payload, null))
        return sse(obj)
    }

    private fun finishChunk(cid: String, created: Long, model: String, completionTokens: Int): ByteArray {
        val obj = baseChunk(cid, created, model)
        obj.add("choices", choices(JsonObject(), "stop"))
        obj.add(
            "usage",
            JsonObject().apply {
                addProperty("prompt_tokens", 0)
                addProperty("completion_tokens", completionTokens)
                addProperty("total_tokens", completionTokens)
            },
        )
        return sse(obj)
    }

    private fun choices(delta: JsonObject, finish: String?): JsonArray = JsonArray().apply {
        add(
            JsonObject().apply {
                addProperty("index", 0)
                add("delta", delta)
                if (finish == null) add("finish_reason", JsonNull.INSTANCE) else addProperty("finish_reason", finish)
            },
        )
    }

    private fun completionJson(model: String, text: String, reasoning: String): String {
        val message = JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", text)
            if (reasoning.isNotEmpty()) addProperty("reasoning_content", reasoning)
        }
        val obj = JsonObject().apply {
            addProperty("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""))
            addProperty("object", "chat.completion")
            addProperty("created", nowMillis() / 1000)
            addProperty("model", model)
            add(
                "choices",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("index", 0)
                            add("message", message)
                            addProperty("finish_reason", "stop")
                        },
                    )
                },
            )
        }
        return obj.toString()
    }

    private fun sse(obj: JsonObject): ByteArray = ("data: $obj\n\n").toByteArray(StandardCharsets.UTF_8)

    // ------------------------------------------------------------------ 额度

    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val credential = CatPawAccount.parse(account.secret) ?: return null
        val json = getJson(
            CatPawConstants.BALANCE_URL,
            mapOf(
                "Accept" to "application/json",
                // 余额接口只认 X-Auth-Token，不是聊天用的 Cookie
                "X-Auth-Token" to credential.token,
                "gray-set" to "new-agent-sdk",
            ),
            20_000,
        ) ?: return CreditInfo(0, known = false, detail = "余额查询失败（网络）")
        val code = json.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
        if (code == 4010 || code == 4011) {
            return CreditInfo(0, known = false, detail = "登录态已过期，请重新登录妙手")
        }
        if (code != 0) return CreditInfo(0, known = false, detail = "余额查询被拒（code=$code）")
        val data = json.objOrNull("data") ?: return CreditInfo(0, known = false, detail = "余额响应缺少 data")
        val credit = listOf("availableCredits", "credit", "balance", "available")
            .firstNotNullOfOrNull { key -> data.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
            ?: return CreditInfo(0, known = false, detail = "余额响应没有额度字段")
        val plan = data.objOrNull("userPlan")?.str("planName").orEmpty()
        val value = credit.toDoubleOrNull()?.toLong() ?: 0L
        return CreditInfo(
            balance = value,
            known = true,
            detail = if (plan.isEmpty()) "妙手余额" else "妙手余额 · $plan",
            unit = CreditUnit.CREDITS,
        )
    }

    // ------------------------------------------------------------------ 错误与域名

    override fun classify(status: Int, body: String): UpstreamError {
        val message = runCatching {
            val obj = JsonParser.parseString(body).asJsonObject
            obj.objOrNull("error")?.str("message").orEmpty().ifEmpty { obj.str("message") }
        }.getOrNull().orEmpty().ifEmpty { body.take(200) }
        val kind = when {
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 402 || message.contains(CatPawConstants.INSUFFICIENT_BALANCE) -> ErrorKind.QUOTA
            status == 429 -> ErrorKind.SOFT_RATE
            status == 400 -> ErrorKind.CLIENT
            status >= 500 -> ErrorKind.SERVER
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, message.ifEmpty { "妙手 HTTP $status" }, status.toLong())
    }

    override fun hosts(): List<String> = listOf(
        "catpaw.meituan.com",
        "nocode.cn",
    )

    // ------------------------------------------------------------------ HTTP 底座

    private data class HttpResult(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    /** 每次请求都带客户端原样的一套头；`m-host`（本机局域网 IP）不能伪造，直接不发。 */
    private fun gatewayHeaders(token: String): Map<String, String> {
        val trace = hex32()
        return linkedMapOf(
            "Content-Type" to "application/json",
            "Cookie" to "X-Passport-Token=$token",
            "User-Agent" to CatPawConstants.USER_AGENT,
            "m-appkey" to CatPawConstants.APP_KEY,
            "m-debug" to "true",
            "m-traceid" to trace,
            "x-request-id" to UUID.randomUUID().toString(),
            "x-trace-id" to trace,
            "x-agent-version" to CatPawConstants.AGENT_VERSION,
        )
    }

    private fun spanHeaders(spanId: String, name: String): Map<String, String> =
        mapOf("m-spanid" to spanId, "m-spanname" to name)

    private fun userMessage(messageId: String, prompt: String): JsonObject = JsonObject().apply {
        addProperty("type", "user")
        addProperty("messageId", messageId)
        add(
            "content",
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", prompt)
                    },
                )
            },
        )
        addProperty("finished", true)
    }

    private fun postJson(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): HttpResult? {
        return try {
            val conn = HttpSupport.open(
                url = url,
                method = "POST",
                headers = headers,
                body = body,
                connectTimeoutMs = 15_000,
                readTimeoutMs = timeoutMs,
            )
            try {
                val status = conn.responseCode
                HttpResult(status, HttpSupport.readBody(conn, 2 * 1024 * 1024))
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getJson(url: String, headers: Map<String, String>?, timeoutMs: Int): JsonObject? {
        return try {
            val conn = HttpSupport.open(
                url = url,
                method = "GET",
                headers = headers.orEmpty(),
                connectTimeoutMs = minOf(timeoutMs, 15_000),
                readTimeoutMs = timeoutMs,
            )
            val text = try {
                HttpSupport.readBody(conn, 2 * 1024 * 1024)
            } finally {
                conn.disconnect()
            }
            runCatching { JsonParser.parseString(text.ifEmpty { "{}" }).asJsonObject }.getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun closeQuietly(turn: TurnStream, conn: HttpURLConnection) {
        runCatching { turn.close() }
        runCatching { conn.disconnect() }
    }

    private fun hex32(): String = UUID.randomUUID().toString().replace("-", "")

    // ------------------------------------------------------------------ 测试接缝

    /** 仅供单测：把请求体拍平成上游能吃的单段文本。 */
    internal fun debugBuildPrompt(body: JsonObject): String = buildPrompt(body)

    /** 仅供单测：返回上游表达不了的结构的原因（null 表示可以发）。 */
    internal fun debugUnsupportedReason(body: JsonObject): String? = unsupportedReason(body)

    private companion object {
        const val PIPE_BUFFER = 64 * 1024

        /** turn 是长任务（上游最长给 15 分钟）。 */
        const val TURN_TIMEOUT_MS = 15 * 60 * 1000

        fun formatRate(value: Double): String =
            "%.2f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
    }
}