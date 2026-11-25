package dev.aigw.core.provider.copilot

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.DeviceAuthTicket
import dev.aigw.core.provider.DeviceCodeSupport
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * GitHub Copilot。
 *
 * 登录走 GitHub 官方 OAuth **设备流**（无需在手机上开回调端口）：
 *  1. `POST /login/device/code` 拿 device_code + user_code；
 *  2. 用户在浏览器打开 `github.com/login/device` 输入 user_code；
 *  3. 轮询 `POST /login/oauth/access_token`，成功后得到 GitHub 用户 token；
 *  4. 用用户 token 换 Copilot 短期 token（约 30 分钟），真正请求时用后者。
 *
 * 对话端点是 [OI] 兼容的 `api.githubcopilot.com`，所以请求体可以直接透传；
 * 但**必须**带 Copilot 客户端指纹头（Integration-Id / Editor-Version / UA），
 * 否则上游按「非官方客户端」直接 401。
 */
class CopilotProvider(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider, DeviceCodeSupport {

    override val id: String = CopilotConstants.ID
    override val displayName: String = "GitHub Copilot"
    override val authKind: AuthKind = AuthKind.DEVICE_CODE

    // ------------------------------------------------------------------ 登录

    override fun startDeviceAuth(region: String): DeviceAuthTicket {
        val body = JsonObject().apply {
            addProperty("client_id", CopilotConstants.CLIENT_ID)
            addProperty("scope", CopilotConstants.SCOPE)
        }.toString()
        val conn = HttpSupport.open(
            CopilotConstants.DEVICE_CODE_URL,
            "POST",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "User-Agent" to CopilotConstants.USER_AGENT,
            ),
            body = body,
            connectTimeoutMs = 15_000,
            readTimeoutMs = 20_000,
        )
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            throw IllegalStateException("请求设备码失败：${e.message}")
        }
        val text = HttpSupport.readBody(conn)
        conn.disconnect()
        if (status !in 200..299) throw IllegalStateException("请求设备码失败（HTTP $status）：${text.take(160)}")
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("设备码响应不是合法 JSON")
        val deviceCode = obj.str("device_code")
        val userCode = obj.str("user_code")
        val uri = obj.str("verification_uri").ifEmpty { "https://github.com/login/device" }
        if (deviceCode.isEmpty() || userCode.isEmpty()) {
            throw IllegalStateException("设备码响应缺少字段：${text.take(200)}")
        }
        // state 里同时带 device_code 与 user_code：轮询只要前者，但界面要把后者显示给用户
        return DeviceAuthTicket(loginUrl = "$uri?user_code=${URLEncoder.encode(userCode, "UTF-8")}", state = deviceCode)
    }

    override fun pollDeviceAuth(state: String, region: String): DeviceAuthPoll {
        val body = JsonObject().apply {
            addProperty("client_id", CopilotConstants.CLIENT_ID)
            addProperty("device_code", state)
            addProperty("grant_type", CopilotConstants.GRANT_DEVICE)
        }.toString()
        val conn = HttpSupport.open(
            CopilotConstants.ACCESS_TOKEN_URL,
            "POST",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "User-Agent" to CopilotConstants.USER_AGENT,
            ),
            body = body,
            connectTimeoutMs = 15_000,
            readTimeoutMs = 20_000,
        )
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            // 单次网络抖动不算失败：设备码有效期 15 分钟，继续轮询
            return DeviceAuthPoll.Pending
        }
        val text = HttpSupport.readBody(conn)
        conn.disconnect()
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return DeviceAuthPoll.Pending
        val githubToken = obj.str("access_token")
        if (githubToken.isEmpty()) {
            return when (obj.str("error")) {
                "authorization_pending", "" -> DeviceAuthPoll.Pending
                // 用户点得比轮询还快：GitHub 要求放慢节奏，继续等
                "slow_down" -> DeviceAuthPoll.Pending
                else -> DeviceAuthPoll.Failed("GitHub 拒绝授权：${obj.str("error")}")
            }
        }

        val login = fetchLogin(githubToken)
        val token = exchangeCopilotToken(githubToken)
        if (token == null) {
            return DeviceAuthPoll.Failed(
                "GitHub 授权成功，但换不到 Copilot token：这个账号没有 Copilot 订阅，或组织未启用。",
            )
        }
        val account = CopilotAccount(
            githubToken = githubToken,
            copilotToken = token.first,
            expiresAt = token.second,
            login = login,
        )
        return DeviceAuthPoll.Success(toProviderAccount(account))
    }

    /** GitHub 登录名；失败不影响登录，只是账号卡片显示差一点。 */
    private fun fetchLogin(githubToken: String): String = runCatching {
        val conn = HttpSupport.open(
            CopilotConstants.USER_URL,
            "GET",
            headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to CopilotConstants.USER_AGENT,
                "Authorization" to "Bearer $githubToken",
            ),
            connectTimeoutMs = 10_000,
            readTimeoutMs = 10_000,
        )
        val text = HttpSupport.readBody(conn)
        conn.disconnect()
        JsonParser.parseString(text).asJsonObject.str("login")
    }.getOrDefault("")

    /** GitHub 用户 token → Copilot 短期 token，返回 (token, 到期毫秒)；失败返回 null。 */
    private fun exchangeCopilotToken(githubToken: String): Pair<String, Long>? {
        val conn = HttpSupport.open(
            CopilotConstants.COPILOT_TOKEN_URL,
            "GET",
            headers = clientHeaders() + mapOf("Authorization" to "Bearer $githubToken"),
            connectTimeoutMs = 15_000,
            readTimeoutMs = 20_000,
        )
        val status = runCatching { conn.responseCode }.getOrDefault(0)
        val text = HttpSupport.readBody(conn)
        conn.disconnect()
        if (status !in 200..299) return null
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        val token = obj.str("token")
        if (token.isEmpty()) return null
        val expiresSec = obj.get("expires_at")?.asLong ?: 0L
        val expires = if (expiresSec > 0) expiresSec * 1000 else nowMillis() + 25 * 60 * 1000
        return token to expires
    }

    /** 保证账号手里有一个可用 token；临期就重换。换不到时回退旧 token（让上游自己报 401）。 */
    private fun ensureToken(account: CopilotAccount): String? {
        if (account.tokenUsable(nowMillis())) return account.copilotToken
        if (account.githubToken.isEmpty()) {
            return account.copilotToken.ifEmpty { null }
        }
        return exchangeCopilotToken(account.githubToken)?.first ?: account.copilotToken.ifEmpty { null }
    }

    /**
     * 粘贴导入。
     *
     * 支持两种输入：裸的 GitHub token（`ghu_` / `ghp_` / `github_pat_` 开头），
     * 或本应用的账号 JSON（含 `githubToken`）；也兼容别处导出的 `access_token` / `token` 字段。
     * 导入时会立刻换一次 Copilot token——换不到说明该账号没有 Copilot 订阅，直接报错比存进去再 401 好。
     */
    override fun importCredentials(raw: String): ProviderAccount? {
        val text = raw.trim()
        if (text.isEmpty()) throw IllegalStateException("token 不能为空")

        val githubToken = if (text.startsWith("{")) {
            val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                ?: throw IllegalStateException("不是合法的 JSON")
            CopilotAccount.parse(text)?.githubToken
                ?: obj.str("access_token").ifEmpty { obj.str("token") }
        } else {
            text
        }
        if (githubToken.isEmpty()) throw IllegalStateException("没找到 GitHub token（githubToken / access_token / token）")

        val token = exchangeCopilotToken(githubToken)
            ?: throw IllegalStateException("换不到 Copilot token：该账号没有 Copilot 订阅，或组织未启用")
        val credential = CopilotAccount(
            githubToken = githubToken,
            copilotToken = token.first,
            expiresAt = token.second,
            login = fetchLogin(githubToken),
        )
        return toProviderAccount(credential)
    }

    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val credential = CopilotAccount.parse(account.secret) ?: return null
        val now = nowMillis()
        // 只有临期才刷新：网关会按请求频率调这个函数，无脑换票会白白打 GitHub
        if (credential.expiresAt > 0 && credential.expiresAt - now > skewSeconds * 1000) return null
        val fresh = exchangeCopilotToken(credential.githubToken) ?: return null
        return toProviderAccount(
            credential.copy(copilotToken = fresh.first, expiresAt = fresh.second),
        )
    }

    private fun toProviderAccount(credential: CopilotAccount): ProviderAccount = ProviderAccount(
        providerId = id,
        uid = credential.uid,
        nickname = credential.login.ifEmpty { "Copilot" },
        secret = credential.toJsonString(),
    )

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        // 没有账号时不报错：登录前本就不该有目录，界面显示空列表即可
        if (account == null) return ProviderModelCatalogView(emptyList(), false, "")
        val credential = CopilotAccount.parse(account.secret)
            ?: return ProviderModelCatalogView(emptyList(), false, "凭证无法解析")
        val token = ensureToken(credential)
            ?: return ProviderModelCatalogView(emptyList(), false, "Copilot token 不可用，请重新登录")
        val conn = try {
            HttpSupport.open(
                "${CopilotConstants.BASE}/models",
                "GET",
                headers = clientHeaders() + mapOf("Authorization" to "Bearer $token"),
                connectTimeoutMs = 15_000,
                readTimeoutMs = 30_000,
                allowCompression = true,
            )
        } catch (e: Exception) {
            return ProviderModelCatalogView(emptyList(), false, "模型接口请求失败：${e.message}")
        }
        val status = runCatching { conn.responseCode }.getOrDefault(0)
        val text = HttpSupport.readBody(conn)
        conn.disconnect()
        if (status !in 200..299) {
            return ProviderModelCatalogView(
                emptyList(),
                false,
                "模型接口 HTTP $status：${text.take(160)}",
            )
        }
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        val list = obj?.arrayOrNull("data") ?: obj?.arrayOrNull("models")
        val models = list?.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val modelId = item.str("id")
            if (modelId.isEmpty()) return@mapNotNull null
            val caps = item.objOrNull("capabilities")
            ProviderModel(
                id = modelId,
                name = item.str("name").ifEmpty { modelId },
                contextWindow = item.objOrNull("capabilities")?.get("limits")?.let { limits ->
                    limits.takeIf { it.isJsonObject }?.asJsonObject?.get("max_context_window_tokens")?.asLong
                } ?: 0L,
                extra = buildMap {
                    if (caps?.get("supports")?.let { supports ->
                            supports.takeIf { it.isJsonObject }?.asJsonObject?.get("vision")?.asBoolean
                        } == true
                    ) {
                        put("vision", "true")
                    }
                    if (caps?.get("supports")?.let { supports ->
                            supports.takeIf { it.isJsonObject }?.asJsonObject?.get("tool_calls")?.asBoolean
                        } == true
                    ) {
                        put("tools", "true")
                    }
                },
            )
        }.orEmpty()

        // 上游目录为空（部分订阅只回空列表）：给一份兜底名单，至少能选到模型
        if (models.isEmpty()) {
            return ProviderModelCatalogView(
                CopilotConstants.FALLBACK_MODELS.map { ProviderModel(it, it) },
                true,
                "",
            )
        }
        return ProviderModelCatalogView(models, false, "")
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        return model.substringAfterLast('/').ifEmpty { CopilotConstants.FALLBACK_MODELS.first() }
    }

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = CopilotAccount.parse(account.secret)
            ?: return FailedChatCall(401, "凭证无法解析")
        val token = ensureToken(credential)
            ?: return FailedChatCall(401, "Copilot token 不可用，请重新登录")
        val streaming = isStreamingBody(openAiBody)

        val conn = try {
            HttpSupport.open(
                "${CopilotConstants.BASE}/chat/completions",
                "POST",
                headers = clientHeaders() + mapOf(
                    "Authorization" to "Bearer $token",
                    "Content-Type" to "application/json",
                    "Accept" to if (streaming) "text/event-stream" else "application/json",
                    // agent 续写（最后一条是 tool/assistant）要单独标注，否则部分模型会拒
                    "X-Initiator" to if (isAgentCall(openAiBody)) "agent" else "user",
                ) + if (hasImageInput(openAiBody)) mapOf("Copilot-Vision-Request" to "true") else emptyMap(),
                body = openAiBody,
                connectTimeoutMs = 20_000,
                readTimeoutMs = 300_000,
            )
        } catch (e: Exception) {
            return FailedChatCall(0, e.message ?: "连接失败")
        }

        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        if (status !in 200..299) {
            val errorBody = runCatching { HttpSupport.readBody(conn) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, errorBody)
        }

        val contentType = conn.contentType.orEmpty()
        if (!contentType.contains("event-stream", ignoreCase = true)) {
            // 200 但回 JSON：可能是错误信封，也可能是聚合结果
            val text = try {
                HttpSupport.readBody(conn)
            } finally {
                conn.disconnect()
            }
            val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            if (obj != null && obj.objOrNull("choices") == null && obj.has("error")) {
                val message = obj.get("error")?.let { err ->
                    if (err.isJsonObject) err.asJsonObject.str("message") else err.asString
                }.orEmpty().ifEmpty { "上游返回错误" }
                return StreamFailureChatCall(status, UpstreamError(classifyKind(status, message), message))
            }
            if (text.isBlank()) {
                return StreamFailureChatCall(status, UpstreamError(ErrorKind.SERVER, "上游返回空响应"))
            }
            return AggregatedChatCall(status, text)
        }

        val stream: InputStream = conn.inputStream
        return StreamingChatCall(status, stream) { conn.disconnect() }
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = runCatching {
            JsonParser.parseString(body).asJsonObject.objOrNull("error")?.str("message")
        }.getOrNull().orEmpty().ifEmpty { body.take(200) }
        return UpstreamError(classifyKind(status, message), message.ifEmpty { "上游 HTTP $status" }, status.toLong())
    }

    private fun classifyKind(status: Int, message: String): ErrorKind = when {
        status == 401 || status == 403 && message.contains("token", ignoreCase = true) -> ErrorKind.SESSION_DEAD
        status == 429 -> ErrorKind.SOFT_RATE
        status == 402 || message.contains("quota", ignoreCase = true) -> ErrorKind.QUOTA
        status >= 500 -> ErrorKind.SERVER
        else -> ErrorKind.CLIENT
    }

    override fun hosts(): List<String> = listOf(
        "github.com",
        "api.github.com",
        "api.githubcopilot.com",
    )

    /** Copilot 客户端指纹头（Authorization 由调用方补）。 */
    private fun clientHeaders(): Map<String, String> = mapOf(
        "User-Agent" to CopilotConstants.USER_AGENT,
        "Editor-Version" to CopilotConstants.EDITOR_VERSION,
        "Editor-Plugin-Version" to CopilotConstants.PLUGIN_VERSION,
        "Copilot-Integration-Id" to CopilotConstants.INTEGRATION_ID,
        "Openai-Intent" to "conversation-edits",
    )

    /** 最后一条是 tool/assistant，说明这是 agent 的工具回环续写。 */
    private fun isAgentCall(body: String): Boolean = runCatching {
        val messages = JsonParser.parseString(body).asJsonObject.arrayOrNull("messages") ?: return false
        if (messages.size() == 0) return false
        val last = messages[messages.size() - 1].takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val role = last.str("role")
        role == "tool" || role == "assistant"
    }.getOrDefault(false)

    /** 请求里是否带图（带图要额外声明，否则上游按纯文本处理会报错）。 */
    private fun hasImageInput(body: String): Boolean = runCatching {
        val messages = JsonParser.parseString(body).asJsonObject.arrayOrNull("messages") ?: return false
        messages.any { element ->
            val content = element.takeIf { it.isJsonObject }?.asJsonObject?.arrayOrNull("content")
            content?.any { part ->
                val type = part.takeIf { it.isJsonObject }?.asJsonObject?.str("type").orEmpty()
                type == "image_url" || type == "input_image"
            } == true
        }
    }.getOrDefault(false)

    companion object {
        /** 上游把 user_code 放在 state 里一起带回，界面直接用它展示。 */
        fun userCodeOf(state: String): String = state

        internal fun decodeUtf8(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)
    }
}
