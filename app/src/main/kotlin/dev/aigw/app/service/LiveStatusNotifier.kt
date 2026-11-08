package dev.aigw.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import dev.aigw.app.AiGatewayApp
import dev.aigw.app.MainActivity
import dev.aigw.app.R
import dev.aigw.core.gateway.ActiveRequest
import dev.aigw.core.gateway.ActiveRequests
import java.util.Locale

/**
 * 「实时状态」通知：把网关当前在途请求显示成 Android 16 的 Live Updates 常驻通知。
 *
 * 存在条件与网关的运行状态绑定：**网关服务在跑且确实有请求在途时才有这条通知**，
 * 无在途时撤销（留一小段宽限避免连续两笔之间闪烁），服务停止时一并撤销
 * （见 [GatewayService] 的 start/onDestroy）。它只做展示，不参与任何调度：
 * 数据全部来自 [ActiveRequests] 的快照，不含任何上游协议细节。
 *
 * 线程约定：[ActiveRequests] 的监听器会在请求线程被回调，这里统一 post 到主线程再处理，
 * 内部状态只在主线程读写。
 */
object LiveStatusNotifier {

    /** 与前台服务通知（1001）分开，互不干扰。 */
    const val NOTIFICATION_ID = 1002

    /**
     * 请求升格为「提升型常驻通知」（Live Updates）的 extras 布尔键。
     *
     * `Notification.Builder.setRequestPromotedOngoing` 是 API 36.1 才有的 API，本项目
     * compileSdk 为 36.0 无法直接调用；AOSP 与 AndroidX 的等效做法就是往 extras 里写这个键
     * （值与 setRequestPromotedOngoing(true) 完全一致），所以在 build() 之前直接写 extras。
     */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    /**
     * 无在途时的宽限：连续两笔请求之间的空洞不闪一下通知。
     *
     * 不用定时器重发来刷新「已用时长」——那种每秒重发会让通知/状态栏胶囊看着发抖，
     * 而且每帧都要走一次 IPC。时间交给系统的 chronometer 自己走，我方只在
     * 「谁在途 + 各自阶段」变化时重发（见 [onChanged]）。
     */
    private const val CANCEL_GRACE_MS = 1500L

    /** 状态栏胶囊最多 96dp，超长只会退化成图标，所以这里主动截断。 */
    private const val SHORT_TEXT_MAX = 16

    private const val UNKNOWN_MODEL = "未知模型"

    private val handler = Handler(Looper.getMainLooper())

    /** 用户开关（来自 KeepAlivePrefs）；关闭时既不发布也不保留通知。 */
    @Volatile
    private var enabled = true

    private var started = false
    private var appContext: Context? = null
    private var source: ActiveRequests? = null
    private var latest: List<ActiveRequest> = emptyList()
    private var lastSignature = ""
    private var promotionLogged = false
    private var log: (String) -> Unit = {}

    private val listener: (List<ActiveRequest>) -> Unit = { list -> handler.post { onChanged(list) } }

    private val cancelRunnable = Runnable { removeNotification() }

    /**
     * 开始跟踪在途请求（由前台服务在启动时调用）。
     *
     * [enabled] 为用户的开关值：关闭时只是不发布通知，监听关系仍保留，
     * 这样开关打开后无需重启服务即可立即生效。
     */
    fun start(context: Context, requests: ActiveRequests, enabled: Boolean, log: (String) -> Unit = {}) {
        this.log = log
        this.enabled = enabled
        if (started) return
        started = true
        appContext = context.applicationContext
        source = requests
        requests.addListener(listener)
        val current = requests.snapshot()
        latest = current
        if (current.isNotEmpty()) publish(current)
    }

    /** 停止跟踪并撤销通知（前台服务 onDestroy）。 */
    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacksAndMessages(null)
        source?.removeListener(listener)
        source = null
        latest = emptyList()
        removeNotification()
        appContext = null
        promotionLogged = false
    }

    /**
     * 用户在「我的」页切换开关：关闭时立即撤销正在显示的通知；
     * 打开时若网关正好有请求在途，立刻补发一条，不必等下一次阶段变化。
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        this.enabled = enabled
        appContext = context.applicationContext
        if (!enabled) {
            handler.removeCallbacksAndMessages(null)
            removeNotification()
            return
        }
        if (started && latest.isNotEmpty()) publish(latest)
    }

    // ------------------------------------------------------------------ 内部（均在主线程）

    private fun onChanged(list: List<ActiveRequest>) {
        if (!started) return
        // 先记快照再判开关：开关关着时积攒的状态也不能过期，
        // 否则重新打开开关会拿旧列表补发一条早已结束的请求。
        latest = list
        if (!enabled) return
        handler.removeCallbacks(cancelRunnable)
        if (list.isEmpty()) {
            handler.postDelayed(cancelRunnable, CANCEL_GRACE_MS)
            return
        }
        // 只在「谁在途 + 各自阶段」变化时重发：时长由系统 chronometer 自己走，
        // 不靠每秒重发（那会让通知与胶囊看着发抖）。
        if (signatureOf(list) != lastSignature) publish(list)
    }

    private fun publish(list: List<ActiveRequest>) {
        if (!started || !enabled || list.isEmpty()) return
        val context = appContext ?: return
        lastSignature = signatureOf(list)
        val compat = NotificationManagerCompat.from(context)
        // 通知权限被拒时静默跳过（发也发不出去），不影响网关转发。
        if (!compat.areNotificationsEnabled()) {
            if (!promotionLogged) {
                promotionLogged = true
                log("实时状态通知：没有通知权限，已跳过发布")
            }
            return
        }
        val notification = buildNotification(context, list)
        runCatching { compat.notify(NOTIFICATION_ID, notification) }
            .onFailure { log("实时状态通知发布失败：${it.message ?: "未知错误"}") }
        if (!promotionLogged) {
            promotionLogged = true
            logPromotion(context, notification)
        }
    }

    private fun removeNotification() {
        val context = appContext ?: return
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
        lastSignature = ""
    }

    /** 签名只含「谁在途 + 各自阶段」：时间流逝不进签名，否则节流形同虚设。 */
    private fun signatureOf(list: List<ActiveRequest>): String =
        list.joinToString(",") { "${it.id}:${it.phase}" }

    private fun buildNotification(context: Context, list: List<ActiveRequest>): Notification {
        // snapshot 已按开始时间升序，第一笔就是最早开始的
        val primary = list.first()
        val model = primary.clientModel.ifBlank { UNKNOWN_MODEL }
        val title = if (list.size == 1) "正在处理 $model" else "${list.size} 个请求进行中"
        val account = primary.nickname.ifBlank { primary.uid }.ifBlank { "未知账号" }
        val text = "${primary.providerId.uppercase(Locale.ROOT)} · $account"

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = Notification.Builder(context, AiGatewayApp.LIVE_CHANNEL_ID)
            // 官方实时更新要件：ongoing + contentTitle，且不得是 group summary / colorized / 自定义 RemoteViews
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            // 时长交给系统的 chronometer 自己走：不再每秒重发通知（那会让通知与状态栏胶囊看着发抖）
            .setShowWhen(true)
            .setWhen(primary.startedAtMillis)
            .setUsesChronometer(true)

        // 提升为 Live Updates（等效于 API 36.1 的 setRequestPromotedOngoing(true)）
        builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // 状态栏胶囊文案；ProgressStyle 是官方实时更新的必备样式
            builder.setShortCriticalText(shortCriticalText(list))
            builder.setStyle(progressStyle(primary))
        } else {
            // Android 16 以下没有 Live Updates：退化成普通的常驻不确定进度通知，不设胶囊
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun progressStyle(primary: ActiveRequest): Notification.ProgressStyle {
        val style = Notification.ProgressStyle()
        val progress = stageProgress(primary.phase)
        if (progress == null) {
            // 刚向上游发请求，还不知道要多久：用不确定进度，不假装有百分比
            style.setProgressIndeterminate(true)
        } else {
            style.setProgress(progress)
        }
        return style
    }

    /**
     * 阶段 → 进度条的粗粒度位次。
     *
     * 这不是真实完成度（流式输出没有总量可算），只表示「走到哪一步」；
     * 未接到上游应答的阶段返回 null（改用不确定进度，避免编造百分比）。
     */
    private fun stageProgress(phase: ActiveRequest.Phase): Int? = when (phase) {
        ActiveRequest.Phase.CONNECTING -> null
        ActiveRequest.Phase.UPSTREAM_ANSWERED -> 45
        ActiveRequest.Phase.STREAMING -> 75
    }

    /** 胶囊文字：单笔用模型名，多笔用「N 个请求」。 */
    private fun shortCriticalText(list: List<ActiveRequest>): String {
        if (list.size > 1) return "${list.size} 个请求"
        // 客户端模型名可能带 "provider/" 前缀，胶囊上只留模型本体
        val model = list.first().clientModel.substringAfterLast('/').ifBlank { UNKNOWN_MODEL }
        return if (model.length <= SHORT_TEXT_MAX) model else model.take(SHORT_TEXT_MAX - 1) + "…"
    }

    /** 记录一次「为什么（没）被提升」。只写日志，不做开关：发不发这条通知与它无关。 */
    private fun logPromotion(context: Context, notification: Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            log("系统低于 Android 16：实时状态按普通常驻通知展示，无状态栏胶囊")
            return
        }
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            when {
                !notification.hasPromotableCharacteristics() ->
                    log("实时状态通知未满足提升要件，将按普通常驻通知展示")
                manager?.canPostPromotedNotifications() != true ->
                    log("系统未允许提升型实时通知（用户可在系统设置中开启），本次按普通通知展示")
                else -> log("实时状态通知已按 Android 16 Live Updates 发布")
            }
        }.onFailure { log("实时状态提升判定失败：${it.message ?: "未知错误"}") }
    }
}
