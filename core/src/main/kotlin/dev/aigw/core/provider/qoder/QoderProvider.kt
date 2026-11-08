package dev.aigw.core.provider.qoder

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.DeviceAuthTicket
import dev.aigw.core.provider.DeviceCodeSupport
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.RegionAwareSupport
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.cosy.Cosy
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Qoder（阿里 AI 编程 IDE）→ OpenAI 兼容。
 *
 * 能力：对话（COSY 签名）、模型目录、额度、每日签到。
 *
 * 两个区域是**独立部署**（qoder.com.cn / qoder.sh）：client_id、授权页、推理网关都不同，
 * 账号也不通用。区域随账号凭证一起存（`realm` 字段）。
 *
 * 关键：**零额度时对话会挂起**（上游不报错，只是不出内容），所以签到是通道可用的前提。
 */
class QoderProvider(
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : Provider, DeviceCodeSupport, RegionAwareSupport {

    private val auth = QoderAuthClient()

    /** 账号 uid → COSY 会话材料。access token 变了要重建。 */
    private data class SessionEntry(val tokenHash: Int, val session: QoderChatClient.CosySession)

    private val sessions = ConcurrentHashMap<String, SessionEntry>()

    /** 进行中的设备授权（state → 票据）。轮询要用它取 verifier/nonce。 */
    private val pendingAuth = ConcurrentHashMap<String, QoderLoginTicket>()

    /** 区域 → 模型目录缓存（按区域隔离，两个区的模型集不同）。 */
    private data class CatalogEntry(val at: Long, val models: List<ProviderModel>)

    private val catalogs = ConcurrentHashMap<String, CatalogEntry>()

    override val id: String = ID
    override val displayName: String = "Qoder"
    override val authKind: AuthKind = AuthKind.DEVICE_CODE
    override val capabilities: Set<ProviderCapability> =
        setOf(ProviderCapability.CREDIT_REFRESH, ProviderCapability.CHECKIN)

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        val parsed = account?.let { runCatching { QoderAccount.parse(it.secret) }.getOrNull() }
            // 未登录不是「拉取失败」：模型页只该显示兜底列表，不该挂红字错误
            ?: return ProviderModelCatalogView(fallbackModels(), fromFallback = true, error = "")
        val realm = parsed.region
        val cached = catalogs[realm.id]
        val now = nowMillis()
        if (cached != null && now - cached.at < CATALOG_TTL_MS) {
            return ProviderModelCatalogView(cached.models, fromFallback = false, error = "")
        }
        // 模型列表要 COSY 签名（裸 Bearer 会 401），交给 ChatClient
        val fetched = runCatching { QoderChatClient(parsed) { acc -> sessionFor(acc) }.fetchModels() }
        val live = fetched.getOrNull()
        if (live.isNullOrEmpty()) {
            val fallback = cached?.models ?: fallbackModels()
            val reason = fetched.exceptionOrNull()?.message.orEmpty()
                .ifEmpty { "上游没有返回可用模型，显示兜底列表" }
            return ProviderModelCatalogView(fallback, fromFallback = true, error = reason)
        }
        val models = live.map { key ->
            ProviderModel(
                id = "${id}/$key",
                name = key,
                contextWindow = 200_000,
                extra = emptyMap(),
            )
        }
        catalogs[realm.id] = CatalogEntry(now, models)
        return ProviderModelCatalogView(models, fromFallback = false, error = "")
    }

    override fun invalidateModelCache() {
        catalogs.clear()
    }

    private fun fallbackModels(): List<ProviderModel> = QoderConstants.FALLBACK_MODELS.map { key ->
        ProviderModel(id = "$id/$key", name = key, contextWindow = 200_000, extra = emptyMap())
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim().substringAfter('$').substringAfter('/')
        return model.ifEmpty { "pro" }
    }

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val parsed = runCatching { QoderAccount.parse(account.secret) }.getOrNull()
            ?: return FailedChatCall(401, "账号凭证无法解析")
        if (parsed.accessToken.isEmpty()) return FailedChatCall(401, "凭证缺少 accessToken")
        return QoderChatClient(parsed) { acc -> sessionFor(acc) }.open(openAiBody)
    }

    /**
     * COSY 会话材料（每次进程内复用）。
     *
     * 身份 JSON 的键**必须字母序**（[Cosy.sortedJson] 保证），字段集合来自
     * dumate2api `src/qoder/cosy.js:identityJson`：`aid/name/refreshToken/securityOauthToken/uid/userType`。
     */
    private fun sessionFor(account: QoderAccount): QoderChatClient.CosySession {
        val key = account.uid.ifEmpty { account.accessToken.take(16) }
        val tokenHash = account.accessToken.hashCode()
        sessions[key]?.takeIf { it.tokenHash == tokenHash }?.let { return it.session }

        val identity = Cosy.sortedJson(
            mapOf(
                "aid" to account.uid,
                "name" to account.nickname,
                "refreshToken" to account.refreshToken,
                "securityOauthToken" to account.accessToken,
                "uid" to account.uid,
                "userType" to account.userType.ifEmpty { "personal_standard" },
            ),
        )
        val session = Cosy.newSession(identity)
        val entry = SessionEntry(tokenHash, QoderChatClient.CosySession(session.cosyKey, session.info))
        sessions[key] = entry
        return entry.session
    }

    // ------------------------------------------------------------------ 额度 / 动作

    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val parsed = runCatching { QoderAccount.parse(account.secret) }.getOrNull() ?: return null
        return try {
            val quota = auth.quota(parsed)
            CreditInfo(quota.remaining.coerceAtLeast(0), known = quota.remaining >= 0, detail = quota.summary())
        } catch (e: Exception) {
            CreditInfo(0, known = false, detail = e.message ?: "查询额度失败")
        }
    }

    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult = when (action) {
        ACTION_CHECKIN -> checkin(account)
        else -> ProviderActionResult.unsupported(action)
    }

    /**
     * 每日签到。
     *
     * 顺序：先查状态（该区可能没有这个接口）→ 未签再领 → 领完复查。
     * 国际版 `/sash/api/v1/me/daily-check-in/` 下的接口历史上返回 404（活动改走 campaign 平台），
     * 这种情况如实说「该区没有签到接口」，不要报成「已签到」。
     */
    private fun checkin(account: ProviderAccount): ProviderActionResult {
        val parsed = runCatching { QoderAccount.parse(account.secret) }.getOrNull()
            ?: return ProviderActionResult.failure("凭证无法解析")
        return try {
            val status = auth.checkinStatus(parsed)
            if (status == true) return ProviderActionResult.success("今日已签到")
            val (ok, message) = auth.claimCheckin(parsed)
            if (!ok) ProviderActionResult.failure(message) else ProviderActionResult.success(message)
        } catch (e: Exception) {
            ProviderActionResult.failure(e.message ?: "签到失败")
        }
    }

    // ------------------------------------------------------------------ 其它

    // ------------------------------------------------------------------ 设备授权登录

    /**
     * 开始设备授权：生成 PKCE 票据，返回授权页 URL 与本地 state。
     *
     * 用户在弹出的浏览器里完成登录后，上层按 [pollDeviceAuth] 轮询换取凭证。
     * `region` 传 `cn` / `global`，决定 client_id 与授权页域名。
     */
    override fun startDeviceAuth(region: String): DeviceAuthTicket {
        val realm = QoderRealm.of(region.ifEmpty { QoderRealm.CN.id })
        val ticket = auth.beginLogin(realm)
        pendingAuth[ticket.state] = ticket
        return DeviceAuthTicket(ticket.authUrl, ticket.state)
    }

    override fun pollDeviceAuth(state: String, region: String): DeviceAuthPoll {
        val ticket = pendingAuth[state]
            ?: return DeviceAuthPoll.Failed("授权会话不存在或已过期，请重新开始")
        return when (val result = auth.poll(ticket)) {
            // 404/202 = 用户还没点完，继续等——最容易写错的一步
            is QoderPollResult.Pending -> DeviceAuthPoll.Pending
            is QoderPollResult.Expired -> {
                pendingAuth.remove(state)
                DeviceAuthPoll.Failed("授权超时（10 分钟），请重新开始")
            }
            is QoderPollResult.Failure -> {
                pendingAuth.remove(state)
                DeviceAuthPoll.Failed(result.message)
            }
            is QoderPollResult.Success -> {
                pendingAuth.remove(state)
                DeviceAuthPoll.Success(toProviderAccount(result.account))
            }
        }
    }

    private fun toProviderAccount(account: QoderAccount): ProviderAccount =
        ProviderAccount(id, account.uid, account.nickname, account.toJson().toString())

    override fun importCredentials(raw: String): ProviderAccount? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // 支持三种输入：完整 JSON、裸 access token、带 Bearer 前缀
        val account = runCatching {
            if (text.startsWith("{")) {
                QoderAccount.parse(text)
            } else {
                val token = text.removePrefix("Bearer").removePrefix("bearer").trim()
                QoderAccount(
                    uid = "",
                    accessToken = token,
                    refreshToken = "",
                    expiresAt = 0L,
                    realm = QoderRealm.CN.id,
                    nickname = "",
                    userType = "",
                    organizationId = "",
                    machineId = QoderAccount.newMachineId(),
                )
            }
        }.getOrElse { return null }
        val filled = runCatching { auth.userInfo(account) }.getOrDefault(account)
        if (filled.uid.isEmpty()) return null
        return ProviderAccount(
            providerId = id,
            uid = filled.uid,
            nickname = filled.nickname,
            secret = filled.toJson().toString(),
        )
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = runCatching {
            JsonParser.parseString(body).asJsonObject.stringOrNull("message").orEmpty()
        }.getOrDefault("")
            .ifEmpty { runCatching {
                JsonParser.parseString(body).asJsonObject.stringOrNull("msg").orEmpty()
            }.getOrDefault("") }
            .ifEmpty { "上游 HTTP $status" }
        val kind = when {
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 402 || status == 429 -> ErrorKind.QUOTA
            status == 404 -> ErrorKind.NOT_FOUND
            status in 500..599 -> ErrorKind.SERVER
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, message)
    }

    // ------------------------------------------------------------------ 区域

    /** 账号属哪个区：凭证里的 `realm`（cn / global）。 */
    override fun regionOf(account: ProviderAccount): String? =
        runCatching { QoderAccount.parse(account.secret).realm }.getOrNull()

    override fun regions(): List<String> = QoderRealm.entries.map { it.id }

    override fun hosts(): List<String> =
        QoderRealm.entries
            .flatMap { listOf(it.openapi, it.gateway) + it.gatewayFallbacks }
            .map { it.removePrefix("https://") }
            .filter { it.isNotEmpty() }
            .distinct()

    companion object {
        const val ID = "qoder"

        /** 模型目录缓存 5 分钟（上游版本级变化，不需要实时）。 */
        const val CATALOG_TTL_MS = 300_000L
    }
}
