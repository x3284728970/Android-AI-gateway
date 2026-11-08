package dev.aigw.app.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import dev.aigw.app.MainActivity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 「悬浮窗保活」：在屏幕上留一个极小的悬浮标签。
 *
 * 原理：带可见悬浮窗（TYPE_APPLICATION_OVERLAY）的应用会被系统视为前台可见进程，
 * 后台回收优先级低于纯后台服务；它只影响系统回收，不改变任何上游行为。
 *
 * 全部方法必须在主线程调用；窗口用 try/catch 包住——权限被收回或系统拒绝时只返回 false，
 * 不影响网关本身运行。
 */
object FloatingKeepAlive {

    private const val PREF_X = "overlay_x"
    private const val PREF_Y = "overlay_y"

    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    /** 是否已获得「显示在其他应用上层」权限。 */
    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isShowing(): Boolean = view != null

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .setData(android.net.Uri.parse("package:${context.packageName}"))

    /**
     * 显示悬浮窗；未授权或添加失败时返回 false（调用方据此提示用户）。
     * [label] 展示在悬浮窗上的文字（如「网关 8790」）。
     */
    fun show(context: Context, label: String): Boolean {
        if (view != null) {
            view?.text = label
            return true
        }
        if (!canDraw(context)) return false
        val app = context.applicationContext
        val windowManager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        return try {
            val params = buildParams(app)
            val text = TextView(app).apply {
                this.text = label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 11f
                setPadding(dp(app, 10), dp(app, 6), dp(app, 10), dp(app, 6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(app, 14).toFloat()
                    setColor(0xB3222222.toInt())
                }
                setOnClickListener {
                    runCatching {
                        app.startActivity(
                            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                attachDrag(this, windowManager, params, app)
            }
            windowManager.addView(text, params)
            view = text
            this.params = params
            true
        } catch (_: Exception) {
            view = null
            params = null
            false
        }
    }

    fun hide(context: Context) {
        val current = view ?: return
        val windowManager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { windowManager?.removeView(current) }
        view = null
        params = null
    }

    private fun buildParams(app: Context): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val stored = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // 不可获焦：不影响任何输入
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = stored.getInt(PREF_X, dp(app, 12))
            y = stored.getInt(PREF_Y, dp(app, 160))
        }
        // 存的位置可能是上个分辨率/旋转方向下的：先夹回当前屏幕内，避免启动后“看不到”
        clampToScreen(app, params, 0, 0)
        return params
    }

    /**
     * 把悬浮窗位置夹在屏幕内。
     *
     * 保活依赖「窗口真的可见」：拖到屏幕外会既找不回来、又可能不被系统计为可见进程，
     * 所以这里不允许拖出屏幕；尺寸未知（首帧布局前）时按 0 处理，至少保证左上角在屏内。
     */
    private fun clampToScreen(context: Context, params: WindowManager.LayoutParams, viewW: Int, viewH: Int) {
        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - viewW).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - viewH).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    /** 拖动移动悬浮窗，松手落盘位置（下次启动回到原处）。 */
    private fun attachDrag(
        target: TextView,
        windowManager: WindowManager,
        params: WindowManager.LayoutParams,
        app: Context,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        target.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > dp(app, 4) || abs(dy) > dp(app, 4)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        clampToScreen(app, params, target.width, target.height)
                        runCatching { windowManager.updateViewLayout(target, params) }
                    }
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putInt(PREF_X, params.x)
                            .putInt(PREF_Y, params.y)
                            .apply()
                    }
                    // 拖动后抬手不触发点击（返回 true 消费事件），未拖动则交给点击回调
                    dragging
                }
                else -> false
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private const val PREFS = "aigw_overlay"
}
