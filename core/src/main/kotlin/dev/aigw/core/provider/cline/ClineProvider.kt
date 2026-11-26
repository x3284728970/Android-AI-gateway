package dev.aigw.core.provider.cline

import com.google.gson.JsonArray
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
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.Locale

/**
 * Cline（cline.bot）供应商。
 *
 * 与其他供应商最大的不同：**认证和对话是两套服务**。
 * WorkOS 只负责「你是谁」，Cline 自己的 `/auth/register` 才发真正能用的令牌对；
 * 漏掉这一步，拿 WorkOS token 直接打 `/chat/completions` 会稳定 401。
 *
 * 模型目录是**免登录**的公开接口，所以没账号时也能列出来——但这里刻意在
 * 无账号时不返回目录：列出来的模型点了会失败，界面会误以为可用。
 */
class ClineProvider(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider, DeviceCodeSupport {

    override val id: String = ClineConstants.ID
    override val displayName: String = "Cline"
    override val authKind: AuthKind = AuthKind.DEVICE_CODE

    // ------------------------------------------------------------------ 登录

    override fun startDeviceAuth(region: String): DeviceAuthTicket {
        val body = "client_id=" + URLEncoder.encode(ClineConstants.WORKOS_CLIENT, "UTF-8")
        val (status, text) = postForm(ClineConstants.WORKOS_DEVICE, body, 15_000)
        if (status !in 200..299) throw IllegalStateException("Cline 设备授权申请失败（HTTP $status）")
        val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("Cline 设备授权响应不是 JSON")
        val deviceCode = json.str("device_code")
        if (deviceCode.isEmpty()) throw IllegalStateException("Cline 设备授权响应缺少 device_code")
        val verify = json.str("verification_uri_complete").ifEmpty {
            json.str("verification_uri").ifEmpty { "https://app.cline.bot" }
        }
        // state 用 device_code：轮询时原样送回 WorkOS，界面不需要理解这是两个概念
        return DeviceAuthTicket(loginUrl = verify, state = deviceCode)
    }

    override fun pollDeviceAuth(state: String, region: String): DeviceAuthPoll {
        val body = "grant_type=" + URLEncoder.encode(ClineConstants.DEVICE_GRANT, "UTF-8") +
            "&device_code=" + URLEncoder.encode(state, "UTF-8") +
            "&client_id=" + URLEncoder.encode(ClineConstants.WORKOS_CLIENT, "UTF-8")
        val (status, text) = postForm(ClineConstants.WORKOS_TOKEN, body, 20_000)
        val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        if (json == null) {
            return if (status in 200..299) DeviceAuthPoll.Failed("Cline 授权响应无法解析")
            else DeviceAuthPoll.Pending
        }
        when (json.str("error")) {
            "" -> Unit
            "authorization_pending" -> return DeviceAuthPoll.Pending
            "slow_down" -> return DeviceAuthPoll.Pending
            else -> return DeviceAuthPoll.Failed("授权被拒绝：${json.str("error_description").ifEmpty { json.str("error") }}")
        }
        val workosAccess = json.str("access_token")
        if (workosAccess.isEmpty()) return DeviceAuthPoll.Pending
        return runCatching { register(workosAccess, json.str("refresh_token")) }
            .fold(
                { DeviceAuthPoll.Success(it) },
                { DeviceAuthPoll.Failed("换取 Cline 账号令牌失败：${it.message}") },
            )
    }

    /** WorkOS token → Cline 真正的令牌对。**没有这一步登录等于没完成**。 */
    private fun register(workosAccess: String, workosRefresh: String): ProviderAccount {
        val body = JsonObject().apply {
            addProperty("accessToken", workosAccess)
            addProperty("refreshToken", workosRefresh)
        }
        val (status, text) = postJson(
            ClineConstants.API + ClineConstants.REGISTER_PATH,
            body.toString(),
            ClineConstants.clientHeaders(),
            30_000,
        )
        if (status !in 200..299) throw IllegalStateException("HTTP $status：${text.take(200)}")
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("响应不是 JSON")
        val data = root.objOrNull("data") ?: root
        val account = parseAuth(data, workosRefresh)
            ?: throw IllegalStateException("响应里没有 accessToken")
        return account.toProviderAccount()
    }

    private fun parseAuth(data: JsonObject, fallbackRefresh: String): ClineAccount? {
        val access = data.str("accessToken").ifEmpty { data.str("access_token") }
        if (access.isEmpty()) return null
        val refresh = data.str("refreshToken").ifEmpty { data.str("refresh_token") }.ifEmpty { fallbackRefresh }
        val user = data.objOrNull("userInfo") ?: data.objOrNull("user") ?: JsonObject()
        return ClineAccount(
            access = access,
            refresh = refresh,
            expiresAt = ClineAccount.isoToMillis(data.str("expiresAt")),
            uid = listOf("clineUserId", "subject", "id")
                .firstNotNullOfOrNull { key -> user.str(key).takeIf { it.isNotEmpty() } }
                .orEmpty(),
            email = user.str("email"),
            name = user.str("name"),
            type = ClineAccount.TYPE_OAUTH,
        )
    }

    override fun importCredentials(raw: String): ProviderAccount? {
        val account = ClineAccount.parse(raw) ?: return null
        // API Key 形态没法验证归属；直接收下，第一次对话失败会按 401 处理
        return account.toProviderAccount()
    }

    private fun ClineAccount.toProviderAccount() = ProviderAccount(
        providerId = id,
        uid = effectiveUid,
        nickname = effectiveNickname,
        secret = toJsonString(),
    )

    /**
     * 令牌保鲜。
     *
     * refresh token 是**一次性**的：刷新成功必须把新的 refresh 写回账号，
     * 否则下次刷新会因为旧的已被消费而直接登出。所以这里返回完整的新账号，
     * 由引擎原子写回（见 [Provider.refreshAccount] 契约）。
     */
    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val credential = ClineAccount.parse(account.secret) ?: return null
        if (credential.isApiKey || credential.refresh.isEmpty()) return null
        val skewMs = maxOf(skewSeconds * 1000, ClineConstants.REFRESH_SKEW_MS)
        if (credential.accessUsable(nowMillis(), skewMs)) return null

        val body = JsonObject().apply {
            addProperty("refreshToken", credential.refresh)
            // 部分部署要求同时带上旧 access；带上不会有害
            if (credential.access.isNotEmpty()) addProperty("accessToken", credential.access)
        }
        val (status, text) = postJson(
            ClineConstants.API + ClineConstants.REFRESH_PATH,
            body.toString(),
            ClineConstants.clientHeaders(),
            30_000,
        )
        if (status !in 200..299) return null
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        val data = root.objOrNull("data") ?: root
        val refreshed = parseAuth(data, credential.refresh) ?: return null
        // 保留原有身份信息：刷新响应不一定带 userInfo
        val merged = refreshed.copy(
            uid = refreshed.uid.ifEmpty { credential.uid },
            email = refreshed.email.ifEmpty { credential.email },
            name = refreshed.name.ifEmpty { credential.name },
        )
        return account.copy(
            uid = merged.effectiveUid,
            nickname = merged.effectiveNickname,
            secret = merged.toJsonString(),
        )
    }

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        if (account == null) return ProviderModelCatalogView(emptyList(), false, "")
        val models = fetchCatalog()
        if (models.isEmpty()) {
            return ProviderModelCatalogView(
                ClineConstants.FALLBACK_MODELS.map { ProviderModel(it, prettify(it), extra = rateExtraOf(it)) },
                true,
                "Cline 模型目录拉取失败，显示内置清单",
            )
        }
        return ProviderModelCatalogView(models, false, "")
    }

    /** 推荐目录（含免费 / Pass 分组）+ 云端全量目录（含上下文与输入能力），按 id 去重合并。 */
    private fun fetchCatalog(): List<ProviderModel> {
        val seen = LinkedHashMap<String, ProviderModel>()
        val (status, text) = getJson(ClineConstants.API + ClineConstants.RECOMMENDED_PATH, 15_000)
        if (status in 200..299) {
            val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            val feed = root?.objOrNull("data") ?: root
            for (group in listOf("recommended", "free", "clinePass")) {
                val list = feed?.arrayOrNull(group) ?: continue
                for (element in list) {
                    val (modelId, name, free) = readFeedItem(element, group)
                    if (modelId.isEmpty() || seen.containsKey(modelId)) continue
                    seen[modelId] = ProviderModel(
                        id = modelId,
                        name = name.ifEmpty { prettify(modelId) },
                        extra = buildMap {
                            if (free || isFreeModel(modelId)) {
                                put("rate", "免费")
                                put("rateSummary", "免费模型，不计费")
                                put("rateOrigin", "live")
                            } else if (modelId.startsWith(ClineConstants.PASS_PREFIX)) {
                                put("rateSummary", "Cline Pass 订阅内")
                            }
                        },
                    )
                }
            }
        }
        val (cStatus, cText) = getJson(ClineConstants.API + ClineConstants.CATALOG_PATH, 20_000)
        if (cStatus in 200..299) {
            val parsed = runCatching { JsonParser.parseString(cText) }.getOrNull()
            val list = when {
                parsed == null -> null
                parsed.isJsonArray -> parsed.asJsonArray
                parsed.isJsonObject -> parsed.asJsonObject.arrayOrNull("data")
                    ?: parsed.asJsonObject.arrayOrNull("models")
                else -> null
            }
            list?.forEach { element ->
                val item = element.asObjectOrNull() ?: return@forEach
                val modelId = item.str("id")
                if (modelId.isEmpty()) return@forEach
                val context = item.get("context_length")?.takeIf { it.isJsonPrimitive }?.asLong
                    ?: item.objOrNull("top_provider")?.get("context_length")?.takeIf { it.isJsonPrimitive }?.asLong
                    ?: item.get("context")?.takeIf { it.isJsonPrimitive }?.asLong
                    ?: 0L
                val existing = seen[modelId]
                // 云端目录带 pricing：prompt 价为 0 就是上游自己标的免费，比前缀启发式可靠
                val zeroPriced = item.objOrNull("pricing")?.get("prompt")
                    ?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull() == 0.0
                val extras = if (zeroPriced && !isFreeModel(modelId)) {
                    rateExtraOf(modelId) + mapOf("rate" to "免费", "rateSummary" to "免费模型，不计费", "rateOrigin" to "live")
                } else {
                    rateExtraOf(modelId)
                }
                val merged = (existing ?: ProviderModel(
                    id = modelId,
                    name = item.str("display_name").ifEmpty { item.str("displayName") }
                        .ifEmpty { item.str("name") }
                        .ifEmpty { prettify(modelId) },
                    extra = extras,
                )).copy(contextWindow = if (context > 0) context else (existing?.contextWindow ?: 0L))
                seen[modelId] = merged
            }
        }
        return seen.values.toList()
    }

    /** 推荐目录里的一项：兼容「纯字符串」与「对象」两种下发形态。 */
    private fun readFeedItem(element: com.google.gson.JsonElement, group: String): Triple<String, String, Boolean> {
        if (element.isJsonPrimitive) {
            val modelId = element.asString.trim()
            return Triple(modelId, "", group == "free")
        }
        val obj = element.asObjectOrNull() ?: return Triple("", "", false)
        val modelId = listOf("id", "model", "modelId", "model_id", "key", "slug")
            .firstNotNullOfOrNull { key -> obj.str(key).takeIf { it.isNotEmpty() } }
            .orEmpty()
        val name = listOf("display_name", "displayName", "name", "label")
            .firstNotNullOfOrNull { key -> obj.str(key).takeIf { it.isNotEmpty() } }
            .orEmpty()
        val free = group == "free" || obj.get("free")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        return Triple(modelId, name, free)
    }

    /**
     * 免费 / Pass 的标签；没有公开倍率的模型不给数字（不猜）。
     *
     * 上游有**两套模型命名**，两边都要认：
     * - 推荐流：`cline-free/nemotron-3.5-super-vl`、`nvidia/nemotron-3.5-lightning-free`
     * - 云端目录：`nvidia/nemotron-3.5-super-vl:free`（且 `pricing.prompt == "0"`）
     *
     * 只按 `cline-free/` 前缀判断会漏掉第二种，把免费模型显示成「无倍率」。
     */
    private fun rateExtraOf(modelId: String): Map<String, String> = when {
        isFreeModel(modelId) -> mapOf(
            "rate" to "免费",
            "rateSummary" to "免费模型，不计费",
            "rateOrigin" to "live",
        )
        modelId.startsWith(ClineConstants.PASS_PREFIX) -> mapOf("rateSummary" to "Cline Pass 订阅内")
        else -> emptyMap()
    }

    /** 免费模型判定：`cline-free/` 前缀、`-free` 结尾、或 `:free` 后缀。 */
    internal fun isFreeModel(modelId: String): Boolean =
        modelId.startsWith(ClineConstants.FREE_PREFIX) ||
            modelId.endsWith(":free") ||
            modelId.substringAfterLast('/').endsWith("-free")

    /** `cline-pass/qwen3.8-max` → `Qwen3.8-Max`。 */
    private fun prettify(modelId: String): String {
        val bare = modelId.substringAfterLast('/')
        if (bare.isEmpty()) return modelId
        return bare.split('-', '_').joinToString("-") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        // Cline 的模型 id 本身就带斜杠（anthropic/claude-...、cline-free/...、cline-pass/...），
        // 这里绝不能像别家那样 substringAfterLast('/')，否则会把免费/订阅档位前缀抹掉，
        // 请求打到一个不存在的裸模型名上。
        return model.ifEmpty { ClineConstants.FALLBACK_MODELS.first() }
    }

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = ClineAccount.parse(account.secret)
            ?: return FailedChatCall(401, "凭证无法解析，请重新登录 Cline")
        val token = credential.access
        if (token.isEmpty()) return FailedChatCall(401, "Cline 令牌为空，请重新登录")
        val streaming = isStreamingBody(openAiBody)
        val taskId = runCatching {
            JsonParser.parseString(openAiBody).asJsonObject.str("user").takeIf { it.isNotEmpty() }
        }.getOrNull().orEmpty()

        val headers = ClineConstants.clientHeaders(taskId) + mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json",
            "Accept" to if (streaming) "text/event-stream" else "application/json",
        )
        val conn = try {
            HttpSupport.open(
                url = ClineConstants.API + ClineConstants.CHAT_PATH,
                method = "POST",
                headers = headers,
                body = openAiBody,
                connectTimeoutMs = 20_000,
                readTimeoutMs = 300_000,
                // 客户端可能带 gzip，交给上游自行协商
                allowCompression = true,
            )
        } catch (e: Exception) {
            return FailedChatCall(0, "Cline 连接失败：${e.message}")
        }

        val status = runCatching { conn.responseCode }.getOrDefault(0)
        if (status !in 200..299) {
            val text = runCatching { HttpSupport.readBody(conn, 256 * 1024) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, text.ifEmpty { "Cline HTTP $status" })
        }

        val contentType = conn.contentType.orEmpty()
        if (!contentType.contains("event-stream", ignoreCase = true)) {
            val text = try {
                HttpSupport.readBody(conn, 4 * 1024 * 1024)
            } finally {
                conn.disconnect()
            }
            val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            if (obj != null && obj.objOrNull("choices") == null && obj.has("error")) {
                val message = obj.get("error")?.let { err ->
                    if (err.isJsonObject) err.asJsonObject.str("message") else if (err.isJsonPrimitive) err.asString else ""
                }.orEmpty().ifEmpty { "Cline 返回错误" }
                return StreamFailureChatCall(status, UpstreamError(classifyKind(status, message), message))
            }
            if (text.isBlank()) return StreamFailureChatCall(status, UpstreamError(ErrorKind.SERVER, "Cline 返回空响应"))
            return AggregatedChatCall(status, text)
        }

        val stream: InputStream = conn.inputStream
        return StreamingChatCall(status, stream) { conn.disconnect() }
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = runCatching {
            val obj = JsonParser.parseString(body).asJsonObject
            obj.objOrNull("error")?.str("message").orEmpty().ifEmpty { obj.str("message") }
        }.getOrNull().orEmpty().ifEmpty { body.take(200) }
        return UpstreamError(classifyKind(status, message), message.ifEmpty { "Cline HTTP $status" }, status.toLong())
    }

    private fun classifyKind(status: Int, message: String): ErrorKind = when {
        // 先判「订阅档位不够」再判 403：Cline 用它回 403，但这不是登录失效，
        // 按 SESSION_DEAD 处理会把好好的账号硬禁用掉。
        message.contains("not subscribed", ignoreCase = true) ||
            message.contains("requires a subscription", ignoreCase = true) -> ErrorKind.MODEL_LIMIT
        status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
        status == 402 -> ErrorKind.QUOTA
        status == 429 -> ErrorKind.SOFT_RATE
        status == 400 || status == 404 -> ErrorKind.CLIENT
        status >= 500 -> ErrorKind.SERVER
        else -> ErrorKind.CLIENT
    }

    override fun hosts(): List<String> = listOf(
        "cline.bot",
        "api.cline.bot",
        "app.cline.bot",
        "workos.com",
        "api.workos.com",
    )

    // ------------------------------------------------------------------ HTTP 底座

    private fun postForm(url: String, body: String, timeoutMs: Int): Pair<Int, String> {
        return try {
            val conn = HttpSupport.open(
                url = url,
                method = "POST",
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Accept" to "application/json",
                ),
                body = body,
                connectTimeoutMs = 15_000,
                readTimeoutMs = timeoutMs,
            )
            try {
                conn.responseCode to HttpSupport.readBody(conn, 512 * 1024)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            0 to ""
        }
    }

    private fun postJson(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): Pair<Int, String> {
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
                conn.responseCode to HttpSupport.readBody(conn, 2 * 1024 * 1024)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            0 to ""
        }
    }

    private fun getJson(url: String, timeoutMs: Int): Pair<Int, String> {
        return try {
            val conn: HttpURLConnection = HttpSupport.open(
                url = url,
                method = "GET",
                headers = ClineConstants.clientHeaders() + mapOf("Accept" to "application/json"),
                connectTimeoutMs = 12_000,
                readTimeoutMs = timeoutMs,
            )
            try {
                conn.responseCode to HttpSupport.readBody(conn, 4 * 1024 * 1024)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            0 to ""
        }
    }
}