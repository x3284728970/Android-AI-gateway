package dev.aigw.core.pool

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.util.intOrNull
import dev.aigw.core.util.stringOrNull

/** 账号导出文件的格式标识：只在「导入」时用来挡住不相干的 JSON。 */
const val ACCOUNT_TRANSFER_FORMAT = "aigw-accounts"

/** 解析出来的一份账号导出内容。 */
data class AccountTransferEnvelope(
    val version: Int,
    val exportedAt: Long,
    val accounts: List<ProviderAccount>,
)

/**
 * 账号导入导出的信封编解码。
 *
 * 只做「一段 JSON ↔ 一批账号」的纯转换，不认识账号池，也不碰存储；
 * 粒度（导出/导入哪些账号）由调用方先选好再传进来。
 *
 * 凭证是明文：格式里不做加密，靠界面明确警告「含 token，勿外传」。
 */
object AccountTransfer {

    /** 当前版本；导入时高于它的文件一律拒绝（宁可报错也不猜字段）。 */
    const val VERSION = 1

    fun encode(accounts: List<ProviderAccount>, exportedAt: Long): String {
        val array = JsonArray()
        for (account in accounts) {
            array.add(JsonObject().apply {
                addProperty("providerId", account.providerId)
                addProperty("uid", account.uid)
                addProperty("nickname", account.nickname)
                addProperty("secret", account.secret)
            })
        }
        return JsonObject().apply {
            addProperty("format", ACCOUNT_TRANSFER_FORMAT)
            addProperty("version", VERSION)
            addProperty("exportedAt", exportedAt)
            add("accounts", array)
        }.toString()
    }

    /**
     * 解析导入内容。
     *
     * 抛出的 [IllegalArgumentException] 消息是给用户看的原话，界面可以直接显示。
     */
    fun decode(json: String): AccountTransferEnvelope {
        val text = json.trim()
        if (text.isEmpty()) throw IllegalArgumentException("内容为空，先粘贴导出内容或选择一个文件")
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalArgumentException("不是合法的 JSON")
        if (obj.stringOrNull("format") != ACCOUNT_TRANSFER_FORMAT) {
            throw IllegalArgumentException("不是本应用导出的账号文件")
        }
        val version = obj.intOrNull("version") ?: 0
        if (version <= 0 || version > VERSION) {
            throw IllegalArgumentException("文件版本 $version 不支持（当前支持到 $VERSION）")
        }
        val array = obj.get("accounts")
        if (array == null || !array.isJsonArray) throw IllegalArgumentException("文件里没有账号列表")

        val accounts = ArrayList<ProviderAccount>(array.asJsonArray.size())
        for (element in array.asJsonArray) {
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val providerId = item.stringOrNull("providerId").orEmpty()
            val uid = item.stringOrNull("uid").orEmpty()
            // 凭证缺失的条目直接丢掉，不要用空串造出一个「能登录但必然失败」的账号
            val secret = item.stringOrNull("secret").orEmpty()
            if (providerId.isEmpty() || uid.isEmpty() || secret.isEmpty()) continue
            accounts.add(
                ProviderAccount(
                    providerId = providerId,
                    uid = uid,
                    nickname = item.stringOrNull("nickname").orEmpty(),
                    secret = secret,
                ),
            )
        }
        if (accounts.isEmpty()) throw IllegalArgumentException("文件里没有可用的账号（凭证为空或字段缺失）")
        return AccountTransferEnvelope(
            version = version,
            exportedAt = obj.get("exportedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            accounts = accounts,
        )
    }
}

/** 一次导入的逐条结果，供界面汇总展示。 */
data class AccountImportResult(
    val added: List<String>,
    val updated: List<String>,
    val skipped: List<String>,
    /** providerId 未注册（如导入了别人机器上独有的供应商）。 */
    val unknownProviders: List<String>,
) {
    val total: Int get() = added.size + updated.size + skipped.size + unknownProviders.size
}
