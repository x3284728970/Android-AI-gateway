package dev.aigw.core.provider.custom

/**
 * 「新建供应商」页的快速预设。
 *
 * 只收录**官方、[OI] 兼容**的接口：这些渠道的凭证就是一把 API Key，不需要登录流程，
 * 填地址 + Key 就能用。账号型渠道（订阅 / 网页会话）不走预设，它们各自有 Provider 实现。
 *
 * [models] 只放**确定存在**的模型名；留空表示让用户点「获取模型」按自己账号的权限拉取，
 * 比猜一串模型名更可靠（不同套餐能用的模型不一样）。
 */
data class CustomPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val models: List<String> = emptyList(),
    /** 一句话说明，显示在预设被选中之后。 */
    val note: String = "",
    /** Key 的形态提示，显示在 Key 输入框的占位符里。 */
    val keyHint: String = "",
)

object CustomPresets {

    val ALL: List<CustomPreset> = listOf(
        CustomPreset(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            models = listOf("deepseek-chat", "deepseek-reasoner"),
            note = "官方 API，按 token 计费",
            keyHint = "sk-...",
        ),
        CustomPreset(
            id = "siliconflow",
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            note = "聚合多家开源模型，国内直连",
            keyHint = "sk-...",
        ),
        CustomPreset(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            note = "聚合海外模型，模型 id 带厂商前缀（如 openai/gpt-5）",
            keyHint = "sk-or-v1-...",
        ),
        CustomPreset(
            id = "hunyuan",
            name = "腾讯混元",
            baseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
            models = listOf("hunyuan-turbos-latest"),
            note = "腾讯云官方 API",
            keyHint = "混元 API Key",
        ),
        CustomPreset(
            id = "zcode",
            name = "智谱 GLM Coding",
            baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
            note = "GLM 编程套餐的 Key，[OI] 兼容端点",
            keyHint = "智谱 API Key",
        ),
        CustomPreset(
            id = "openai",
            name = "[OI]",
            baseUrl = "https://api.openai.com/v1",
            note = "官方端点；国内网络通常需要给网关配代理",
            keyHint = "sk-...",
        ),
        CustomPreset(
            id = "workbuddy-ai",
            name = "WorkBuddy AI",
            baseUrl = "https://www.workbuddy.ai/v2",
            note = "国际版，Key 在 profile/keys 页面获取",
            keyHint = "WorkBuddy Key",
        ),
    )

    fun byId(id: String): CustomPreset? = ALL.firstOrNull { it.id == id }
}
