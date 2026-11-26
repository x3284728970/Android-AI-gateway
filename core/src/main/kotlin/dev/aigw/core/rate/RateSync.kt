package dev.aigw.core.rate

import dev.aigw.core.update.UpdateSources
import dev.aigw.core.util.HttpSupport
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 倍率表同步器：按 [RateBook.REFRESH_INTERVAL_MS] 从仓库里的 `config/model-rates.json`
 * 拉一份最新倍率，写进 [RateBook]。
 *
 * 为什么要单独做这个而不是把倍率写死在 APK 里：倍率跟着官方调价、限时活动、会员档位变，
 * 一次调价发一版 APK 既慢又没必要。远程表 + 12 小时自动刷新，改数据只要提一个 JSON。
 *
 * 设计约束：
 * - **不阻塞**：调用方（看门狗 30 秒节拍）只做 `shouldRefresh` 的内存判断，真拉取在独立线程；
 * - **不重入**：CAS 保证同时只有一次同步在跑（看门狗与手动刷新可能撞车）；
 * - **不丢旧值**：任何失败都不清空台账，界面继续显示上一次成功的数据，只在状态里记错误；
 * - **多线路**：GitHub 直连在部分网络不可用，依次试 ghfast / gh-proxy / ghproxy / gh.llkk。
 */
class RateSync(
    private val book: RateBook,
    private val fetcher: (String) -> String? = ::httpGet,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** 一次同步的结果。 */
    data class Result(
        val ok: Boolean,
        /** 实际写入的条目数。 */
        val count: Int = 0,
        /** 命中的线路标签。 */
        val source: String = "",
        val error: String = "",
        /** true 表示按节流规则跳过（没到刷新时间 / 已有一次在跑）。 */
        val skipped: Boolean = false,
    )

    private val inFlight = AtomicBoolean(false)

    /**
     * 按节流规则决定是否同步。
     *
     * @param force 手动刷新传 true：跳过时间间隔判断，但仍然不允许重入。
     */
    fun maybeSync(force: Boolean = false): Result {
        if (!force && !book.shouldRefresh(nowMillis())) {
            return Result(ok = true, skipped = true, error = "")
        }
        if (!inFlight.compareAndSet(false, true)) {
            return Result(ok = true, skipped = true, error = "")
        }
        return try {
            runSync()
        } finally {
            inFlight.set(false)
        }
    }

    private fun runSync(): Result {
        val urls = candidateUrls()
        var lastError = "没有可用线路"
        var lastLabel = ""
        for ((label, url) in urls) {
            lastLabel = label
            val body = try {
                fetcher(url)
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
                null
            }
            if (body.isNullOrBlank()) {
                lastError = "线路 $label 返回空内容"
                continue
            }
            val count = try {
                book.importRemote(body)
            } catch (e: IllegalArgumentException) {
                lastError = "线路 $label 数据无效：${e.message.orEmpty()}"
                continue
            } catch (e: Exception) {
                lastError = "线路 $label 解析失败：${e.message.orEmpty()}"
                continue
            }
            if (count == 0) {
                lastError = "线路 $label 表里没有可用条目"
                continue
            }
            return Result(ok = true, count = count, source = label)
        }
        book.markFailure(lastError, lastLabel)
        return Result(ok = false, error = lastError, source = lastLabel)
    }

    /** 线路 → 地址。直连排第一（海外/代理环境最快），其余为国内可达的镜像。 */
    private fun candidateUrls(): List<Pair<String, String>> =
        UpdateSources.DOWNLOAD.map { mirror -> mirror.label to mirror.map(RateBook.REMOTE_URL) }

    private companion object {
        fun httpGet(url: String): String? {
            val conn = HttpSupport.open(
                url = url,
                method = "GET",
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to "AiGateway-RateSync/1.0",
                ),
                connectTimeoutMs = 8000,
                readTimeoutMs = 15000,
            )
            return try {
                val code = conn.responseCode
                if (code !in 200..299) null else HttpSupport.readBody(conn)
            } finally {
                conn.disconnect()
            }
        }
    }
}
