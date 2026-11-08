package dev.aigw.core.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.aigw.core.provider.RoutedModel

/** OpenAI 兼容层的报文拼装。 */
internal object OpenAiApi {

    fun errorBody(code: String, message: String): String = JsonObject().apply {
        add("error", JsonObject().apply {
            addProperty("message", message)
            addProperty("type", "api_error")
            addProperty("code", code)
        })
    }.toString()

    /**
     * 模型列表。对外 id 带 `provider/` 前缀（如 `trae/Seed-2.1-Code`），
     * 客户端据此在同一个端点下选择任意供应商的模型。
     *
     * 能力字段一并输出（`capabilities` + 各家惯用的平铺字段）：客户端（如 Minis）
     * 靠它自动勾选「图片输入 / 思考」，否则只能猜。字段缺失表示上游没说，不是「不支持」。
     */
    fun modelList(models: List<RoutedModel>, pricing: ((String) -> dev.aigw.core.usage.ModelPrice?)? = null): String {
        val data = JsonArray()
        for (routed in models) {
            data.add(JsonObject().apply {
                addProperty("id", routed.fullId)
                addProperty("object", "model")
                addProperty("created", CREATED)
                addProperty("owned_by", routed.providerId)
                addProperty("display_name", routed.model.name.ifEmpty { routed.model.id })
                if (routed.model.contextWindow > 0) addProperty("context_length", routed.model.contextWindow)

                val extra = routed.model.extra
                // 能力/推理档位来自各供应商目录的 extra（各家自由 KV），只输出标准化的那几项：
                // 客户端据此决定是否显示「思考强度」选项，多余的私有字段不外泄。
                addProperty("supports_reasoning", extra.isTrue("reasoning"))
                addProperty("supports_tools", extra.isTrue("tools"))
                addProperty("supports_vision", extra.isTrue("vision"))
                addProperty("supports_audio", extra.isTrue("audio"))

                // 能力枚举：只有上游明确给了才加，避免把「没信息」说成「不支持」
                val caps = JsonArray()
                if (extra.isTrue("vision")) caps.add("vision")
                if (extra.isTrue("audio")) caps.add("audio")
                if (extra.isTrue("reasoning")) caps.add("reasoning")
                if (extra.isTrue("tools")) caps.add("tools")
                if (extra.isTrue("search")) caps.add("search")
                val maxOutput = extra["maxOutput"]?.toLongOrNull()
                if (maxOutput != null && maxOutput > 0) {
                    caps.add("text")
                    addProperty("max_output_tokens", maxOutput)
                }
                if (caps.size() > 0) add("capabilities", caps)

                // 平铺字段：不同客户端认不同的名字，都放过一份最省事
                if (extra.isTrue("vision")) {
                    addProperty("supports_images", true)
                    add("input_modalities", JsonArray().apply {
                        add("text")
                        add("image")
                    })
                }
                if (extra.isTrue("reasoning")) addProperty("reasoning", true)

                extra["note"]?.takeIf { it.isNotBlank() }?.let { addProperty("description", it) }
                extra["vendor"]?.takeIf { it.isNotBlank() }?.let { addProperty("vendor", it) }
                extra["rate"]?.takeIf { it.isNotBlank() }?.let { addProperty("rate", it) }

                // 价格按「每百万 token 美元」输出，与价格表同口径；
                // 未配价格的模型**不输出 pricing 字段**（不拿 0 假装免费）
                pricing?.invoke(routed.fullId)?.let { price ->
                    add("pricing", JsonObject().apply {
                        addProperty("prompt", price.promptPerMillion)
                        addProperty("completion", price.completionPerMillion)
                        addProperty("cache_read", price.cacheReadPerMillion)
                        addProperty("cache_write", price.cacheWritePerMillion)
                        addProperty("unit", "usd_per_million_tokens")
                    })
                }
            })
        }
        return JsonObject().apply {
            addProperty("object", "list")
            add("data", data)
        }.toString()
    }

    /**
     * extra 里的能力位可能写成 `"true"` / `"1"` / `"yes"` 三种（各家 provider 各写各的）。
     *
     * 缺失或空串一律返回 false——「上游没说」不等于「不支持」，调用方自行决定怎么处理。
     */
    private fun Map<String, String>.isTrue(key: String): Boolean {
        val v = this[key]?.trim()?.lowercase() ?: return false
        return v == "true" || v == "1" || v == "yes" || v == "y"
    }

    const val CREATED = 1_753_600_000L
}
