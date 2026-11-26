package dev.aigw.core.provider.catpaw

import java.util.Locale

/**
 * 妙手 CatPaw（美团 `ai.catpaw.meituan.com`）的上游常量。
 *
 * 取值全部来自 All-in（magpie-community/plugins 的 packages/catpaw，MIT）与本项目实测，
 * 有几处**不能按常识优化**：
 *
 * 1. 会话请求体里的 `source` / `mode` 必须是 `CatX` / `CATX_APP`。
 *    抓包里出现的 `CatPawCLI` / `CLI` 已被网关停用（返回 `code=400 source 不存在或未启用`）。
 * 2. 余额接口在**另一个域名**（`catx.nocode.cn`）且只认 `X-Auth-Token`；
 *    把聊天用的 `Cookie: X-Passport-Token` 发过去会稳定 401 `code=4010`。
 * 3. 会话协议只收一段扁平文本，工具定义 / 工具结果 / 图片都送不进去，
 *    带这些的请求必须显式拒绝，不能悄悄丢掉——那等于用错误的方式回答用户。
 */
object CatPawConstants {

    const val ID = "catpaw"

    const val ORIGIN = "https://ai.catpaw.meituan.com"
    const val PASSPORT = "https://catx.nocode.cn"
    const val BALANCE_URL = "https://catx.nocode.cn/api/gateway/credit/balance"

    const val CATALOG_PATH = "/api/agent/maas/model-types"
    const val ROUND_PATH = "/api/agent/conversation/round"
    const val EVENT_PATH = "/api/agent/conversation/event"
    const val TURN_PATH = "/api/agent/conversation/turn"

    const val LOGIN_CONFIG_PATH = "/api/gateway/passport/login-config"
    const val POLL_TOKEN_PATH = "/api/gateway/passport/poll-token"
    const val CURRENT_USER_PATH = "/api/gateway/passport/current-user"

    /** 官方客户端的应用标识；改动会被上游判成未知客户端。 */
    const val APP_KEY = "com.sankuai.mcopilot.cli"
    const val AGENT_VERSION = "1.2.0-beta.6"
    const val USER_AGENT = "axios/1.19.0"

    /** 会话协议版本；与客户端抓包一致。 */
    const val TOOL_VERSION = "2.0.2"

    /** 上游只认这两个值（见类注释第 1 条）。 */
    const val SOURCE = "CatX"
    const val MODE = "CATX_APP"

    /** 登录配置里的 tenant / scene / env 是固定的移动端组合。 */
    const val CATALOG_SCENE = "CATX_APP"
    const val CATALOG_TENANT = "CatDesk"
    const val CATALOG_ENV = "EXTERNAL"

    /** 余额不够时上游的固定话术，命中即按 402 处理（换号而不是重试）。 */
    const val INSUFFICIENT_BALANCE = "当前余额不足以完成本次操作"

    /**
     * 桌面客户端的模型表：id → (展示名, modelType, 是否收图)。
     *
     * `modelType` 是上游的模型编号，turn 请求里用它指定模型；未知 id 回落到
     * [DEFAULT_MODEL_TYPE]（longcat-2.0 的编号），与官方插件行为一致。
     */
    private val MODELS: Map<String, CatPawModel> = linkedMapOf(
        "longcat-2.0" to CatPawModel("LongCat-2.0", 77, true),
        "glm-5.3-flashx" to CatPawModel("GLM-5.3-FlashX", 98, true),
        "glm-5.3-flash" to CatPawModel("GLM-5.3-Flash", 91, true),
        "glm-5.3" to CatPawModel("GLM-5.3", 89, false),
        "deepseek-v4-flash" to CatPawModel("DeepSeek-V4-Flash", 63, false),
        "deepseek-v4-pro" to CatPawModel("DeepSeek-V4-Pro", 64, false),
        "minimax-m3" to CatPawModel("MiniMax-M3", 70, true),
        "kimi-k3" to CatPawModel("Kimi-K3", 83, true),
        "auto" to CatPawModel("Auto (自动选择)", 0, false),
    )

    const val DEFAULT_MODEL_TYPE = 77
    const val DEFAULT_MODEL = "longcat-2.0"

    /** 兜底模型表（无法拉到目录时至少能选到模型）。 */
    val FALLBACK_MODELS: List<String> get() = MODELS.keys.toList()

    fun model(id: String): CatPawModel? = MODELS[id.trim().lowercase(Locale.US)]

    fun displayNameOf(id: String): String = model(id)?.name ?: id

    fun modelTypeOf(id: String): Int = model(id)?.modelType ?: DEFAULT_MODEL_TYPE

    /** 目录里按 modelType 反查已知 id（上游给的是编号不是名字）。 */
    fun idOfType(type: Int): String? = MODELS.entries.firstOrNull { it.value.modelType == type }?.key

    /** 该模型是否收图（用于给界面标能力位）。 */
    fun acceptsImage(id: String): Boolean = model(id)?.image == true
}

/** 一个上游模型条目。 */
data class CatPawModel(
    val name: String,
    val modelType: Int,
    val image: Boolean,
)