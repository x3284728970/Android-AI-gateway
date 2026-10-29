package dev.aigw.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 百度搭子的**页内积分**执行器。
 *
 * 为什么存在：搭子的积分/签到接口在 console.bce.baidu.com，鉴权依赖一组 HttpOnly
 * 会话 cookie。WebView 外面拿不到它们（CookieManager 不吐 HttpOnly，拦截请求头在
 * 部分 ROM 上也不给 Cookie 项），所以签到/查额度只能在**页面里**发同源请求——
 * WebView 的 cookie 罐子是全局持久的，只要登录过一次，这里就是已登录状态。
 *
 * 流程：加载 console 域任意页面 → onPageFinished 后每 1.5s 跑一次探针脚本
 * （查签到状态 → 未签就领 → 查余额）→ 拿到 JSON 响应就组装人话并回传。
 *
 * 结果通过 [EXTRA_RESULT_MESSAGE]（人话）与 [EXTRA_RESULT_RAW]（原始响应）回传，
 * 宿主只负责 notice + 刷新，不再发 HTTP。
 */
class DumatePointsActivity : Activity() {

    private var web: WebView? = null
    private var status: TextView? = null
    private var probeAttempts = 0
    private var delivered = false

    private val probe: Runnable = object : Runnable {
        override fun run() {
            val view = web ?: return
            if (delivered) return
            if (probeAttempts++ >= PROBE_MAX) {
                status?.text = "页内也没拿到响应（登录态可能已失效），请重新在 App 内登录"
                web?.postDelayed({ finish() }, 2500)
                return
            }
            runCatching {
                view.evaluateJavascript(POINTS_SCRIPT) { raw ->
                    val text = decodeJsString(raw)
                    if (hasUsableResponse(text)) {
                        delivered = true
                        status?.text = "完成，正在回传…"
                        deliver(text)
                    } else {
                        status?.text = "正在页面内执行（第 $probeAttempts 次）…"
                        view.postDelayed(this, PROBE_INTERVAL_MS)
                    }
                }
            }.onFailure {
                status?.text = "页面执行失败：${it.message}"
                web?.postDelayed(this, PROBE_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val label = TextView(this).apply {
            text = "正在打开百度云控制台（页内签到）…"
            textSize = 14f
            setPadding(32, 48, 32, 16)
        }
        status = TextView(this).apply {
            textSize = 12f
            setPadding(32, 0, 32, 16)
        }
        val view = try {
            WebView(this)
        } catch (e: Throwable) {
            Toast.makeText(this, "本机缺少 WebView 组件", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        view.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1,
        )
        root.addView(label)
        root.addView(status)
        root.addView(view)
        setContentView(root)
        web = view

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true

        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val host = runCatching { android.net.Uri.parse(url.orEmpty()).host.orEmpty() }.getOrDefault("")
                when {
                    host.startsWith("passport.") || host.startsWith("login.") -> {
                        status?.text = "控制台把我们带回了登录页：登录态已失效，请先「在 App 内登录」"
                        web?.postDelayed({ finish() }, 2500)
                    }
                    host == "console.bce.baidu.com" -> {
                        status?.text = "页面已就绪，正在执行签到/查余额…"
                        web?.postDelayed(probe, 800)
                    }
                }
            }
        }
        view.loadUrl("https://console.bce.baidu.com/")
    }

    /** 三段响应里只要有一段是 JSON 对象就算拿到了可用数据。 */
    private fun hasUsableResponse(raw: String): Boolean {
        if (raw.isBlank() || !raw.trimStart().startsWith("{")) return false
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return false
        for (key in listOf("info", "claim", "quota")) {
            val part = root.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val body = part.substringAfter('\u0001', "").trim()
            if (body.startsWith("{")) return true
        }
        return false
    }

    /** 把三段响应拼成人话：签到结果在前，余额在后，原始响应压到最后兜底。 */
    private fun buildMessage(raw: String): String {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return "页内签到：响应无法解析"
        val parts = ArrayList<String>()

        val infoBody = bodyOf(root, "info")
        if (infoBody.isNotEmpty()) {
            val info = runCatching { JsonParser.parseString(infoBody) }.getOrNull()
            val message = runCatching { info?.asJsonObject?.stringLike("message") }.getOrNull()
                ?: runCatching { info?.asJsonObject?.stringLike("msg") }.getOrNull().orEmpty()
            if (message.isNotEmpty()) parts.add(message)
        }

        val claimBody = bodyOf(root, "claim")
        if (claimBody.isNotEmpty()) {
            val claim = runCatching { JsonParser.parseString(claimBody) }.getOrNull()?.asJsonObject
            val message = runCatching { claim?.stringLike("message") }.getOrNull()
                ?: runCatching { claim?.stringLike("msg") }.getOrNull().orEmpty()
            val points = claim?.longLike("points") ?: claim?.longLike("score") ?: -1L
            parts.add(
                when {
                    points > 0 -> "签到成功 +$points"
                    message.isNotEmpty() -> message
                    else -> "签到请求已提交"
                },
            )
        }

        val quotaBody = bodyOf(root, "quota")
        if (quotaBody.startsWith("{")) {
            val quota = runCatching { JsonParser.parseString(quotaBody) }.getOrNull()?.asJsonObject
            val remain = quota?.findLong(listOf("remain_points", "remaining", "remain", "available", "balance"))
            val total = quota?.findLong(listOf("total_points", "total", "quota", "limit"))
            when {
                remain != null && total != null && total > 0 -> parts.add("余额 $remain/$total")
                remain != null -> parts.add("余额 $remain")
                // 认不出字段名时把原始响应带上：界面上的提示是唯一的诊断入口，
                // 让它自证比让用户去翻日志靠谱。
                else -> parts.add("余额解析失败，原始响应：" + quotaBody.take(160))
            }
        }

        if (parts.isEmpty()) parts.add("页内签到完成，但没解析出结果：${raw.take(200)}")
        return "页内签到：" + parts.joinToString("；")
    }

    private fun bodyOf(root: com.google.gson.JsonObject, key: String): String {
        val part = root.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        return part.substringAfter('\u0001', "").trim()
    }

    private fun JsonObject.stringLike(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.longLike(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

    /** 在整棵对象树里找第一个能当数字读的键（响应形状不固定）。 */
    private fun JsonObject.findLong(keys: List<String>): Long? {
        for (entry in entrySet()) {
            if (entry.key in keys && entry.value.isJsonPrimitive) {
                val v = runCatching { entry.value.asJsonPrimitive.asLong }.getOrNull()
                if (v != null) return v
            }
            if (entry.value.isJsonObject) {
                val nested = entry.value.asJsonObject.findLong(keys)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun deliver(raw: String) {
        CookieManager.getInstance().flush()
        setResult(
            RESULT_OK,
            android.content.Intent()
                .putExtra(EXTRA_RESULT_MESSAGE, buildMessage(raw))
                .putExtra(EXTRA_RESULT_RAW, raw),
        )
        finish()
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { JsonParser.parseString(raw).asString }.getOrDefault("")
    }

    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_RESULT_MESSAGE = "result_message"
        const val EXTRA_RESULT_RAW = "result_raw"

        private const val PROBE_INTERVAL_MS = 1500L
        private const val PROBE_MAX = 20

        /**
         * 页内一次跑完三件事：查余额 → 查签到状态 → 未签就领。
         *
         * 同步 XHR 是为了让结果能作为 evaluateJavascript 的返回值直接回来。
         * `\u0001` 做状态码与响应体的分隔符，避开所有常见字符。
         */
        private const val POINTS_SCRIPT =
            "(function(){" +
                "function x(m,u,b){var r=new XMLHttpRequest();r.open(m,u,false);r.withCredentials=true;" +
                "if(b!==null){r.setRequestHeader('Content-Type','application/json');}" +
                "r.send(b===null?null:b);return r.status+'\\u0001'+r.responseText;}" +
                "var out={};" +
                "try{out.quota=x('GET','/api/dumate/points/quota_overview?timezone=Asia/Shanghai" +
                "&clientType=desktop&ignoreLoginBonus=true',null);}catch(e){out.quota='ERR:'+e;}" +
                "try{out.info=x('GET','/api/dumate/points/loginBonusInfo',null);}catch(e){out.info='ERR:'+e;}" +
                "out.claim='';" +
                "try{var st=(out.info||'').split('\\u0001')[1]||'';var j=JSON.parse(st);" +
                "var d=j.result||j.data||j;" +
                "var rec=(d.received===true)||(d.claimed===true)||(d.is_receive===true)||(d.today_received===true);" +
                "if(!rec){out.claim=x('POST','/api/dumate/points/loginBonus','{}');}}" +
                "catch(e){out.claim='PARSE:'+e;}" +
                "return JSON.stringify(out);})()"
    }
}
