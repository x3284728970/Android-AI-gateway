package dev.aigw.core.rate

import com.google.gson.JsonObject
import java.util.Locale

/** 一条倍率数据的来源层级。 */
enum class RateOrigin {
    /** 编译进 APK 的兜底表（各 provider 自己的常量），只在没有更新的数据时使用。 */
    BUNDLED,

    /** 从远程倍率表拉下来的（[RateSync]），会覆盖内置值。 */
    REMOTE,

    /** 供应商自己的接口现场给的（如妙手模型目录的 rateMultiplier），优先级最高。 */
    LIVE,
}

/**
 * 一个模型的计费倍率。
 *
 * 与 Trae 的 [dev.aigw.core.provider.trae.ModelRate] 的区别：那个是 Trae 专属、
 * 带 `RateSource` 枚举与「积分」语义的展示模型；这里是**跨供应商**的统一数据结构，
 * 只回答「几倍 / 有没有折扣 / 数据哪来的」，界面文案由各 provider 自己决定。
 *
 * 之所以要这一层，是因为倍率会随官方调价、活动、会员档位变化，而它既不在上游接口里，
 * 也不值得为每次调价发一个 APK——把它做成可远程更新的数据，比硬编码在代码里靠谱。
 */
data class RateEntry(
    /** 基准倍率；0 表示免费/不计费。 */
    val base: Double,
    /** 限时折扣后的倍率；没有折扣为 null。 */
    val discounted: Double? = null,
    /** 折扣条件短标签（「会员5折」「空闲5折」）。 */
    val discountTag: String? = null,
    /** 数据来源标注（「官方」「公告」「实测」「上游接口」）。 */
    val source: String = "",
    /** 完整口径说明，含数据日期。 */
    val note: String = "",
    /** 这条数据的产生时间（epoch 毫秒）；0 表示未知。 */
    val updatedAt: Long = 0L,
    val origin: RateOrigin = RateOrigin.BUNDLED,
) {
    val isFree: Boolean get() = base == 0.0

    /** 主标签，如 `0.78×` / `免费`。 */
    fun baseLabel(): String = if (isFree) "免费" else "${formatMultiplier(base)}×"

    /** 折后标签，如 `0.39×`；无折扣为 null。 */
    fun discountLabel(): String? = discounted?.let { if (it == 0.0) "免费" else "${formatMultiplier(it)}×" }

    /** 一行式摘要：带折扣时写成「原价 → 现价」，避免同一模型出现两个孤立的数字。 */
    fun summaryLabel(): String = when {
        isFree -> "免费"
        discounted == 0.0 -> "限时免费（基准 ${baseLabel()}）"
        discounted != null -> "限时 ${discountLabel()}（${discountTag ?: "折扣"}，基准 ${baseLabel()}）"
        else -> "基准 ${baseLabel()}"
    }

    /**
     * 转成模型条目上的展示字段。
     *
     * 键名沿用 `ProviderModel.extra` 已有约定（ModelsScreen 读 `rate` / `rateDiscount`），
     * 这样新数据不需要改界面就能显示出来。
     */
    fun toExtras(): Map<String, String> = buildMap {
        put("rate", baseLabel())
        put("rateSummary", summaryLabel())
        discountLabel()?.let { put("rateDiscount", it) }
        discountTag?.takeIf { it.isNotEmpty() }?.let { put("rateDiscountTag", it) }
        if (note.isNotEmpty()) put("rateNote", note)
        if (source.isNotEmpty()) put("rateSource", source)
        put("rateOrigin", origin.name.lowercase(Locale.US))
        if (updatedAt > 0) put("rateUpdatedAt", updatedAt.toString())
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("base", base)
        discounted?.let { addProperty("discounted", it) }
        discountTag?.takeIf { it.isNotEmpty() }?.let { addProperty("tag", it) }
        if (source.isNotEmpty()) addProperty("source", source)
        if (note.isNotEmpty()) addProperty("note", note)
        if (updatedAt > 0) addProperty("updatedAt", updatedAt)
    }

    companion object {
        /** 倍率上限：超过这个数几乎一定是上游/表里的脏数据，宁可丢弃也不要显示成「9999×」。 */
        const val MAX_REASONABLE = 1000.0

        /**
         * 解析一条倍率。非法输入返回 null（不猜、不补默认值）：
         * NaN / 无穷 / 负数 / 非数字 / 超过 [MAX_REASONABLE] 都算脏数据。
         *
         * 远程表是**外部输入**，Gson 的 `asDouble` 遇到 `"oops"` 会直接抛异常，
         * 所以每个字段都用 runCatching 兜住——一颗脏数据不能把整张表连带丢掉。
         */
        fun parse(obj: JsonObject?, origin: RateOrigin = RateOrigin.REMOTE, now: Long = 0L): RateEntry? {
            if (obj == null) return null
            val base = doubleOrNull(obj.get("base")) ?: return null
            if (!base.isFinite() || base < 0 || base > MAX_REASONABLE) return null
            val discounted = doubleOrNull(obj.get("discounted"))
                ?.takeIf { it.isFinite() && it >= 0 && it <= MAX_REASONABLE }
            return RateEntry(
                base = base,
                discounted = discounted,
                discountTag = stringOrNull(obj.get("tag"))?.takeIf { it.isNotBlank() },
                source = stringOrNull(obj.get("source")).orEmpty(),
                note = stringOrNull(obj.get("note")).orEmpty(),
                updatedAt = obj.get("updatedAt")?.let { runCatching { it.asLong }.getOrNull() } ?: now,
                origin = origin,
            )
        }

        private fun doubleOrNull(element: com.google.gson.JsonElement?): Double? {
            if (element == null || !element.isJsonPrimitive) return null
            return runCatching { element.asDouble }.getOrNull()
        }

        private fun stringOrNull(element: com.google.gson.JsonElement?): String? {
            if (element == null || !element.isJsonPrimitive) return null
            return runCatching { element.asString }.getOrNull()
        }
    }
}

/** `0.78` → `"0.78"`、`1.5` → `"1.5"`；固定 Locale.US，避免地区小数点差异。 */
fun formatMultiplier(value: Double): String =
    "%.2f".format(Locale.US, value).trimEnd('0').trimEnd('.')