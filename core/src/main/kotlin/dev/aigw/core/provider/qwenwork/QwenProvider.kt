package dev.aigw.core.provider.qwenwork

import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 千问办公（QwenWork）→ OpenAI 兼容。
 *
 * 与 Qoder 同一套 COSY 协议栈，但**没有每日签到**：免费额度每天 00:00（+08:00）
 * 自动重置，靠的是「日池」余额，没有可点的领取动作。所以这里只做对话、模型目录、积分查询。
 */
class QwenProvider(
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : Provider, DeviceCodeSupport {

    private val auth = QwenAuthClient()

    private val pendingAuth = ConcurrentHashMap<String, QwenLoginTicket>()

    private data class CatalogEntry(val at: Long, val models: List<ProviderModel>)

    @Volatile
    private var cachedCatalog: CatalogEntry? = null

    override val id: String = QwenConstants.ID
    override val displayName: String = "千问办公"
    override val authKind: AuthKind = AuthKind.DEVICE_CODE
    override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.CREDIT_REFRESH)

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        val parsed = account?.let { runCatching { QwenAccount.parse(it.secret) }.getOrNull() }
            // 未登录不是「拉取失败」：只给兜底列表，不报错
            ?: return ProviderModelCatalogView(fallbackModels(), fromFallback = true, error = "")
        val cached = cachedCatalog
        val now = nowMillis()
        if (cached != null && now - cached.at < CATALOG_TTL_MS) {
            return ProviderModelCatalogView(cached.models, fromFallback = false, error = "")
        }
        // 模型列表要 COSY 签名，走 ChatClient（它持有会话材料）
        val fetched = runCatching { QwenChatClient(parsed).fetchModels() }
        val error = fetched.exceptionOrNull()?.message.orEmpty()
        val live = fetched.getOrNull()
        if (live.isNullOrEmpty()) {
            return ProviderModelCatalogView(
                cached?.models ?: fallbackModels(),
                fromFallback = true,
                // 把上游原因带出去：只写「失败」用户没法排查
                error = error.ifEmpty { "上游没有返回可用模型，显示兜底列表" },
            )
        }
        val models = live.map { key ->
            ProviderModel(id = "$id/$key", name = key, contextWindow = QwenConstants.MAX_INPUT_TOKENS.toLong(), extra = emptyMap())
        }
        cachedCatalog = CatalogEntry(now, models)
        return ProviderModelCatalogView(models, fromFallback = false, error = "")
    }

    override fun invalidateModelCache() {
        cachedCatalog = null
    }

    private fun fallbackModels(): List<ProviderModel> = QwenConstants.FALLBACK_MODELS.map {
        ProviderModel(id = "$id/$it", name = it, contextWindow = QwenConstants.MAX_INPUT_TOKENS.toLong(), extra = emptyMap())
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim().substringAfter('/').substringAfter('$')
        return when (model) {
            "" -> QwenConstants.DEFAULT_MODEL
            "auto", "qwork-auto", "qwork-advanced" -> QwenConstants.DEFAULT_MODEL
            else -> model
        }
    }

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val parsed = runCatching { QwenAccount.parse(account.secret) }.getOrNull()
            ?: return FailedChatCall(401, "账号凭证无法解析")
        if (parsed.uid.isEmpty()) return FailedChatCall(401, "凭证缺少 uid（签名必需），请重新登录")
        return QwenChatClient(parsed).open(openAiBody)
    }

    // ------------------------------------------------------------------ 额度

    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val parsed = runCatching { QwenAccount.parse(account.secret) }.getOrNull() ?: return null
        return try {
            val credits = auth.credits(parsed)
            CreditInfo(
                balance = credits.total.toLong(),
                known = true,
                detail = credits.summary() + "（接口只给余额，不给每日上限）",
            )
        } catch (e: Exception) {
            CreditInfo(0, known = false, detail = e.message ?: "查询积分失败")
        }
    }

    // ------------------------------------------------------------------ 设备授权

    override fun startDeviceAuth(region: String): DeviceAuthTicket {
        val ticket = auth.beginLogin()
        pendingAuth[ticket.state] = ticket
        return DeviceAuthTicket(ticket.authUrl, ticket.state)
    }

    override fun pollDeviceAuth(state: String, region: String): DeviceAuthPoll {
        val ticket = pendingAuth[state] ?: return DeviceAuthPoll.Failed("授权会话不存在或已过期，请重新开始")
        return when (val result = auth.poll(ticket)) {
            is QwenPollResult.Pending -> DeviceAuthPoll.Pending
            is QwenPollResult.Expired -> {
                pendingAuth.remove(state)
                DeviceAuthPoll.Failed("授权超时（5 分钟），请重新开始")
            }
            is QwenPollResult.Failure -> {
                pendingAuth.remove(state)
                DeviceAuthPoll.Failed(result.message)
            }
            is QwenPollResult.Success -> {
                pendingAuth.remove(state)
                DeviceAuthPoll.Success(toProviderAccount(result.account))
            }
        }
    }

    private fun toProviderAccount(account: QwenAccount): ProviderAccount =
        ProviderAccount(id, account.uid, account.nickname, account.toJson().toString())

    override fun importCredentials(raw: String): ProviderAccount? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val account = runCatching {
            if (text.startsWith("{")) {
                QwenAccount.parse(text)
            } else {
                QwenAccount(
                    uid = "",
                    accessToken = text.removePrefix("Bearer").trim(),
                    refreshToken = "",
                    expiresAt = 0L,
                    nickname = "",
                    organizationId = "",
                    machineId = dev.aigw.core.provider.qoder.QoderAccount.newMachineId(),
                )
            }
        }.getOrElse { return null }
        val filled = runCatching { auth.accountContext(account) }.getOrDefault(account)
        if (filled.uid.isEmpty()) return null
        return ProviderAccount(id, filled.uid, filled.nickname, filled.toJson().toString())
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = runCatching {
            JsonParser.parseString(body).asJsonObject.stringOrNull("message").orEmpty()
        }.getOrDefault("").ifEmpty { "上游 HTTP $status" }
        val kind = when {
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 402 || status == 429 -> ErrorKind.QUOTA
            status == 404 -> ErrorKind.NOT_FOUND
            status in 500..599 -> ErrorKind.SERVER
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, message)
    }

    override fun hosts(): List<String> = listOf(
        QwenConstants.GATEWAY.removePrefix("https://"),
        QwenConstants.SITE_ORIGIN.removePrefix("https://"),
    )

    companion object {
        const val CATALOG_TTL_MS = 300_000L
    }
}
