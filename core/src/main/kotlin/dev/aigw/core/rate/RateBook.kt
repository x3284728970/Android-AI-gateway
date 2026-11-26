package dev.aigw.core.rate

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.store.KeyValueStore
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** 一次倍率同步的状态快照，供界面与日志展示。 */
data class RateSyncStatus(
    /** 上次**成功**同步的时刻（epoch 毫秒）；0 表示从未成功。 */
    val lastSyncAt: Long = 0L,
    /** 上次尝试的时刻（含失败）。 */
    val lastAttemptAt: Long = 0L,
    /** 上次失败原因；空串表示上次成功。 */
    val lastError: String = "",
    /** 上次成功的线路标签（「GitHub 直连」/「gh-proxy.com」）。 */
    val lastSource: String = "",
    /** 当前台账里的条目数（远程 + 现场）。 */
    val entryCount: Int = 0,
    /** 远程表里写的更新日期，原样展示，便于判断数据新旧。 */
    val tableUpdated: String = "",
) {
    /** 从未同步且没有报错：界面显示「未同步」。 */
    val neverSynced: Boolean get() = lastSyncAt == 0L && lastError.isEmpty()
}

/**
 * 倍率台账：远程表 + 供应商现场数据，覆盖编译进 APK 的兜底表。
 *
 * 三层优先级（高 → 低）：**现场**（[RateOrigin.LIVE]）> **远程表**（[RateOrigin.REMOTE]）>
 * 各 provider 编译期常量（不在本类里，由 provider 自己写 extra）。
 *
 * 本类只做「查得到、存得住、别信脏数据」三件事，网络与调度在 [RateSync]。
 * 之所以把存储和网络拆开：前者要能在单测里用假存储直接验，后者要能注入假 fetcher。
 */
class RateBook(
    private val store: KeyValueStore? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    companion object {
        const val KEY_ENTRIES = "rates/book.json"
        const val KEY_STATUS = "rates/status.json"

        /** 远程表的地址：放在本仓库 `config/` 下，改数据不必发版。 */
        const val REMOTE_URL =
            "https://raw.githubusercontent.com/x3284728970/Android-AI-gateway/main/config/model-rates.json"

        /** 同步周期：12 小时。倍率不是分秒级变化的东西，太频繁只会白耗流量。 */
        const val REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000L

        /** 失败后的退避：30 分钟内不再重试，避免离线时每 30 秒撞一次墙。 */
        const val RETRY_INTERVAL_MS = 30 * 60 * 1000L

        /** 台账键：`provider/model`，两边都小写，避免大小写差异造成查不到。 */
        fun keyOf(providerId: String, modelId: String): String =
            providerId.trim().lowercase(Locale.US) + "/" + modelId.trim().lowercase(Locale.US)
    }

    /** 台账本体：key → 条目。读多写少，用并发表保证读路径不加锁。 */
    private val entries = ConcurrentHashMap<String, RateEntry>()

    private val lock = Any()

    @Volatile
    private var status: RateSyncStatus = RateSyncStatus()

    init {
        load()
    }

    /** 查倍率；只有远程/现场数据才会返回，编译期兜底表由各 provider 自己负责。 */
    fun rate(providerId: String, modelId: String): RateEntry? =
        entries[keyOf(providerId, modelId)]

    fun count(): Int = entries.size

    fun status(): RateSyncStatus = status.copy(entryCount = entries.size)

    /** 全部条目快照（调试/导出用）。 */
    fun snapshot(): Map<String, RateEntry> = entries.toMap()

    /**
     * 导入远程表。
     *
     * 语义是**整体替换远程层**：表里没有的模型视为「官方删掉了」，不能让旧值一直留着；
     * 现场层（LIVE）不受影响——它来自供应商当下的接口，比任何静态表都可靠。
     *
     * 返回实际写入的条目数；JSON 结构不对时抛 [IllegalArgumentException]，由调用方记成同步失败。
     */
    fun importRemote(json: String): Int {
        val root = JsonParser.parseString(json)
        if (!root.isJsonObject) throw IllegalArgumentException("倍率表不是 JSON 对象")
        val obj = root.asJsonObject
        val providers = obj.get("providers")
        if (providers == null || !providers.isJsonObject) {
            throw IllegalArgumentException("倍率表缺少 providers 字段")
        }
        val now = nowMillis()
        val fresh = LinkedHashMap<String, RateEntry>()
        for ((providerId, modelsEl) in providers.asJsonObject.entrySet()) {
            if (!modelsEl.isJsonObject) continue
            for ((modelId, entryEl) in modelsEl.asJsonObject.entrySet()) {
                if (!entryEl.isJsonObject) continue
                val entry = RateEntry.parse(entryEl.asJsonObject, RateOrigin.REMOTE, now) ?: continue
                fresh[keyOf(providerId, modelId)] = entry
            }
        }
        synchronized(lock) {
            entries.entries.removeIf { it.value.origin == RateOrigin.REMOTE }
            // 现场数据（LIVE）比任何静态表都可靠：远程表不覆盖它，否则一次同步就会把
            // 供应商接口刚返回的真实倍率顶掉。
            entries.putAll(fresh.filterKeys { key -> entries[key]?.origin != RateOrigin.LIVE })
            persistEntries()
            status = status.copy(
                lastSyncAt = now,
                lastAttemptAt = now,
                lastError = "",
                tableUpdated = obj.get("updated")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                entryCount = entries.size,
            )
            persistStatus()
        }
        return fresh.size
    }

    /** 记一条供应商现场数据（如妙手目录里的 rateMultiplier）。 */
    fun recordLive(providerId: String, modelId: String, entry: RateEntry) {
        val live = entry.copy(origin = RateOrigin.LIVE, updatedAt = if (entry.updatedAt > 0) entry.updatedAt else nowMillis())
        synchronized(lock) {
            entries[keyOf(providerId, modelId)] = live
            persistEntries()
        }
    }

    /** 一次失败尝试：只记状态，不动台账数据。 */
    fun markFailure(reason: String, source: String = "") {
        synchronized(lock) {
            status = status.copy(
                lastAttemptAt = nowMillis(),
                lastError = reason,
                lastSource = source.ifEmpty { status.lastSource },
                entryCount = entries.size,
            )
            persistStatus()
        }
    }

    /** 距上次成功同步是否已超过 [REFRESH_INTERVAL_MS]；失败时按 [RETRY_INTERVAL_MS] 退避。 */
    fun shouldRefresh(now: Long = nowMillis()): Boolean {
        val s = status
        if (s.lastSyncAt == 0L && s.lastAttemptAt == 0L) return true
        val anchor = if (s.lastError.isEmpty()) s.lastSyncAt else s.lastAttemptAt
        if (anchor == 0L) return true
        val interval = if (s.lastError.isEmpty()) REFRESH_INTERVAL_MS else RETRY_INTERVAL_MS
        return now - anchor >= interval
    }

    /** 清空全部台账（界面「清除倍率数据」用）。 */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            status = RateSyncStatus()
            store?.delete(KEY_ENTRIES)
            store?.delete(KEY_STATUS)
        }
    }

    // ------------------------------------------------------------------ 持久化

    private fun load() {
        runCatching { store?.read(KEY_ENTRIES) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching {
                val root = JsonParser.parseString(raw).asJsonObject
                val map = root.get("entries")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@runCatching
                val loaded = HashMap<String, RateEntry>()
                for ((key, el) in map.entrySet()) {
                    if (!el.isJsonObject) continue
                    val entry = RateEntry.parse(el.asJsonObject, RateOrigin.REMOTE, 0L) ?: continue
                    val origin = el.asJsonObject.get("origin")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.let { name -> RateOrigin.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                        ?: RateOrigin.REMOTE
                    loaded[key] = entry.copy(origin = origin)
                }
                entries.putAll(loaded)
            }
        }
        runCatching { store?.read(KEY_STATUS) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching {
                val o = JsonParser.parseString(raw).asJsonObject
                status = RateSyncStatus(
                    lastSyncAt = o.get("lastSyncAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                    lastAttemptAt = o.get("lastAttemptAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                    lastError = o.get("lastError")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    lastSource = o.get("lastSource")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    entryCount = entries.size,
                    tableUpdated = o.get("tableUpdated")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                )
            }
        }
    }

    private fun persistEntries() {
        val target = store ?: return
        val root = JsonObject()
        root.addProperty("version", 1)
        val map = JsonObject()
        for ((key, entry) in entries) {
            val o = entry.toJson()
            o.addProperty("origin", entry.origin.name.lowercase(Locale.US))
            map.add(key, o)
        }
        root.add("entries", map)
        runCatching { target.write(KEY_ENTRIES, root.toString()) }
    }

    private fun persistStatus() {
        val target = store ?: return
        val s = status
        val o = JsonObject().apply {
            addProperty("lastSyncAt", s.lastSyncAt)
            addProperty("lastAttemptAt", s.lastAttemptAt)
            addProperty("lastError", s.lastError)
            addProperty("lastSource", s.lastSource)
            addProperty("tableUpdated", s.tableUpdated)
        }
        runCatching { target.write(KEY_STATUS, o.toString()) }
    }
}
