package dev.aigw.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * 内嵌网页登录：打开上游网站，用户自己登录，登录完成后把 Cookie 交回调用方。
 *
 * 为什么需要它：LongCat（美团龙猫）这类上游**没有开放 API**，凭证就是浏览器里的
 * Cookie（`passport_token_key`）。让用户去开发者工具里复制整串 Cookie 门槛太高，
 * 而且手机上根本没法开 DevTools —— 所以这里用 App 内嵌的 WebView 走一遍网页登录，
 * 登录态落在 WebView 自己的 Cookie 库里，完成后直接读出来。
 *
 * 兼容性（面向旧机器）：
 *  - 全程只用一个 WebView（不开多窗口），`onCreateWindow` 一律返回 false；
 *  - 不加 JS 注入、不依赖 `androidx.webkit`，只用 SDK 自带的 webkit；
 *  - WebView 缺失（极旧或精简 ROM）时降级为 Toast 提示，不崩溃。
 */
class CookieLoginActivity : Activity() {

    private var web: WebView? = null
    private var status: TextView? = null

    /** 判定「登录已完成」的 URL 前缀（Trae 51120 / Antigravity 51121 的回调地址）。 */
    private var successPrefixes: List<String> = emptyList()
    /** 需要等待出现的 Cookie 名（LongCat 这类站内跳转、没有回调地址的上游）。 */
    private var successCookieName: String = ""
    /**
     * 一键复制全部 Cookie 到剪贴板。
     *
     * WebView 登录链走不完时，用户可以直接复制全部 Cookie 自己粘贴导入。
     * 点「完成」走的是 collectAndFinish（只收 hosts 列表里的域），
     * 但有些 cookie 落在别的子域，逐域读可能漏掉——这里从 CookieManager 一次性取全部。
     */
    private fun copyCookiesToClipboard() {
        val manager = CookieManager.getInstance()
        manager.flush()
        // 已知域 + 常见父域/子域，一次性扫全
        val allHosts = pendingHosts.toList()
        val seen = LinkedHashSet<String>()
        val pairs = mutableListOf<String>()
        for (host in allHosts) {
            val raw = manager.getCookie(host).orEmpty()
            if (raw.isBlank()) continue
            for (pair in raw.split(';')) {
                val trimmed = pair.trim()
                if (trimmed.isEmpty()) continue
                val key = trimmed.substringBefore('=').trim().lowercase()
                if (seen.add(key)) pairs.add(trimmed)
            }
        }
        if (pairs.isEmpty()) {
            Toast.makeText(this, "还没有 Cookie，先登录", Toast.LENGTH_SHORT).show()
            return
        }
        val cookieStr = pairs.joinToString("; ")
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("登录 Cookie", cookieStr))
        Toast.makeText(this, "Cookie 已复制（${pairs.size} 条），去粘贴导入", Toast.LENGTH_LONG).show()
    }

    /** 已经触发过自动确认，避免重复。 */
    private var autoConfirmed = false

    /**
     * 每 2 秒探一次关键 cookie：有些 Set-Cookie 由页面内 XHR 下发，
     * 没有 URL 变化，onPageFinished 那条路径检不到——只能定时探。
     */
    private val cookieProbe = object : Runnable {
        override fun run() {
            if (isFinishing || autoConfirmed) return
            if (successCookieName.isNotEmpty() && hasCookieNamed(successCookieName)) {
                maybeAutoConfirm(web?.url.orEmpty())
            }
            web?.postDelayed(this, 2_000)
        }
    }
    /** 本次要读取 Cookie 的域名（保存下来给自动确认路径用）。 */
    private var pendingHosts: Array<String> = emptyArray()

    /** 是否用桌面 UA 打开（部分上游按 UA 判定「地区不开放」）。 */
    private var desktopUserAgent = false
    /**
     * 登录成功后必须**主动访问一次**的域名（用于让服务端种下跨域会话 cookie）。
     *
     * 有些会话 cookie 只在访问另一个（跨域）主机时才由服务端下发，
     * 只在登录域停留永远拿不到，而调业务接口恰恰依赖它（缺了一律 401）。
     */
    private var settleUrls: List<String> = emptyList()
    /** 已经跳过一次 settle 页面，避免反复刷新。 */
    private var settleIndex = 0

    /**
     * **页面内换票**模式用的接口路径。
     *
     * 非空时不再靠「关键 Cookie 出现」判断登录完成：会话 cookie 可能是 **HttpOnly**，
     * `CookieManager.getCookie()` 读不到，导出的 cookie 串永远缺这几项，服务端一律 401。
     * 改为在页面里发同源请求——HttpOnly cookie 由 WebView 自动带上——直接把票取回来。
     */
    private var tokenEndpoint: String = ""

    /** 页面内换票前要落到的页面，保证请求与登录态同源。 */
    private var tokenPageUrl: String = ""
    private var tokenPageHost: String = ""
    private var tokenPageOpened = false
    private var tokenProbeStarted = false
    private var tokenAttempts = 0
    private var tokenResult: String? = null

    /**
     * 从 WebView 自己发出的请求里抓到的 **Cookie 头原文**（含 HttpOnly 项）。
     *
     * `CookieManager.getCookie()` 只返回非 HttpOnly 的 cookie，百度那套会话
     * （`bce-long-term-sessionid` 之类）全在 HttpOnly 里，于是导出的串看着很长、
     * 拿去调接口一律 302/401。WebView 真正发请求时这些 cookie 是带上的，
     * 在 `shouldInterceptRequest` 里能把整串读出来——这是外部唯一能拿到完整会话的口子。
     */
    private val interceptedCookies = LinkedHashMap<String, String>()

    private val tokenValueRegex = Regex("\"token\"\\s*:\\s*\"([0-9A-Za-z_\\-]{16,})\"")
    private val expiredAtRegex = Regex("\"expired_at\"\\s*:\\s*\"([^\"]+)\"")

    /** 判断「关键 Cookie 是否出现」时要查的域名。 */
    private var cookieHostsForCheck: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startUrl = intent.getStringExtra(EXTRA_START_URL).orEmpty()
        val cookieHosts = intent.getStringArrayExtra(EXTRA_COOKIE_HOSTS) ?: emptyArray()
        successPrefixes = intent.getStringArrayExtra(EXTRA_SUCCESS_URL_PREFIXES)?.toList() ?: emptyList()
        successCookieName = intent.getStringExtra(EXTRA_SUCCESS_COOKIE).orEmpty()
        settleUrls = intent.getStringArrayExtra(EXTRA_SETTLE_URLS)?.toList() ?: emptyList()
        tokenEndpoint = intent.getStringExtra(EXTRA_TOKEN_ENDPOINT).orEmpty()
        tokenPageUrl = intent.getStringExtra(EXTRA_TOKEN_PAGE_URL).orEmpty()
        tokenPageHost = if (tokenPageUrl.isEmpty()) "" else {
            runCatching { android.net.Uri.parse(tokenPageUrl).host.orEmpty() }.getOrDefault("")
        }
        pendingHosts = cookieHosts
        cookieHostsForCheck = cookieHosts.toList()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "登录" }
        desktopUserAgent = intent.getBooleanExtra(EXTRA_DESKTOP_UA, false)


        if (startUrl.isEmpty()) {
            Toast.makeText(this, "缺少登录地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 跟随窗口主题（values / values-night 各一套），不再硬编码白底：
            // 原来的 Color.WHITE 在深色模式下是一整块刺眼的白板。
            setBackgroundColor(themeColor(android.R.attr.colorBackground))
        }

        // 顶部：说明 + 完成按钮（用户自己决定何时算登录完成）
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val label = TextView(this).apply {
            text = "若未自动跳转，请点右上角「完成」"
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 显式关闭入口：这个页面盖住了 App，用户不知道「怎么退出去」，
        // 而 Android 13+ 的预测性返回不保证走 onBackPressed，只靠返回键会让人以为登录丢了。
        val close = TextView(this).apply {
            text = "关闭"
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(8), dp(14), dp(8))
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        val done = Button(this).apply {
            text = "完成"
            textSize = 14f
            setOnClickListener { onDonePressed(cookieHosts) }
        }
        // 一键复制 Cookie：WebView 登录链走不完时（如百度搭子的 bce 会话在跳转链最后一跳才种上，
        // 而 WebView 对那段流程要么拦跨域、要么 UA 分流不种），让用户直接复制全部 Cookie 自己粘贴导入。
        val copyCookie = Button(this).apply {
            text = "复制 Cookie"
            textSize = 14f
            setOnClickListener { copyCookiesToClipboard() }
        }
        bar.addView(close)
        bar.addView(label)
        bar.addView(copyCookie)
        bar.addView(done)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }

        val view = try {
            WebView(this)
        } catch (e: Throwable) {
            // 极少数精简 ROM 没带 WebView 实现
            Toast.makeText(this, "本机缺少 WebView 组件，无法内嵌登录", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        view.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        )
        web = view
        // WebView 就绪后开始轮询关键 cookie（bce-sessionid 可能由页面内 XHR 种下，
        // 没有 URL 变化，onPageFinished 检不到——只能定时探）
        if (successCookieName.isNotEmpty()) {
            web?.postDelayed(cookieProbe, 2_000)
        }

        val statusBar = TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(dp(14), dp(4), dp(14), dp(6))
        }
        status = statusBar

        root.addView(bar)
        root.addView(progress)
        root.addView(statusBar)
        root.addView(view)
        setContentView(root)

        configure(view, progress)
        view.loadUrl(startUrl)
    }

    private fun configure(view: WebView, progress: ProgressBar) {
        val settings: WebSettings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        // **必须允许三方 cookie**：部分登录态（如百度搭子的 bce-sessionid）由
        // console.bce.baidu.com 等跨域请求种下。Android WebView 默认禁三方 cookie，
        // 结果就是 passport 登录成功（BDUSS 在）、跨域会话没种上（bce- 系全缺），
        // 上游判定登录失效——浏览器里能登成功、App 里登录「成功却抓不到」的差异就在这。
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        // 缩放相关一律保持默认：项目里试过 useWideViewPort + loadWithOverviewMode，
        // 在部分机器上会让整页缩放错乱、输入框文字镜像。默认值最稳。
        settings.setSupportZoom(true)
        settings.builtInZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // 桌面 UA：部分上游按 UA 判断客户端平台，手机 UA 会直接回「该地区不开放」，
        // 桌面 UA 同一个网络却能进。只在调用方显式要求时才改（默认保持移动端，
        // 因为有些站点的网页登录流程在桌面模式下反而更麻烦）。
        if (desktopUserAgent) {
            settings.userAgentString = DESKTOP_UA
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        // 站内跳转都留在本 WebView；外链（支付、帮助等）也不新开窗口，避免多窗口在旧机器上崩
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                maybeAutoConfirm(request?.url?.toString().orEmpty())
                return false
            }

            @Deprecated("仅老系统走这个重载")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                maybeAutoConfirm(url.orEmpty())
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                if (request != null) captureCookieHeader(request)
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                maybeAutoConfirm(url.orEmpty())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                status?.text = "已打开：${url.orEmpty().take(60)}"
                // 回调页渲染完再确认：给上游一个把 Set-Cookie 写完的窗口
                maybeAutoConfirm(url.orEmpty())
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
            }
        }
    }

    /**
     * 检测登录是否已完成，完成就自动收尾。
     *
     * 两种信号：
     *  1. **回调地址**（Trae 51120 / Antigravity 51121）—— 页面跳到 `http://127.0.0.1:5112x/...`
     *     说明服务端已经下发凭证，此时直接读 Cookie 收尾，用户不用再点「完成」；
     *  2. **关键 Cookie 出现**（LongCat 这类站内跳转、没有回调地址的上游）—— 只有登录成功后
     *     浏览器才会拿到 `passport_token_key`，看到它就说明登录完成了。
     */
    private fun maybeAutoConfirm(url: String) {
        if (autoConfirmed) return
        // 页面内换票模式：判断依据是「拿到 token」而不是「看到 cookie」
        if (tokenEndpoint.isNotEmpty()) {
            maybeTokenFlow(url)
            return
        }
        // 关键 cookie 还没出现、但有 settle 页面要访问 → 先去 settle 一次。
        // 跨域会话 cookie 只在真正访问那个域时才种下，光等是不会出现的。
        if (successCookieName.isNotEmpty() && !hasCookieNamed(successCookieName) &&
            settleIndex < settleUrls.size
        ) {
            val next = settleUrls[settleIndex++]
            status?.text = "正在激活会话（" + next.substringAfter("://").substringBefore("/") + "）…"
            web?.loadUrl(next)
            return
        }
        val hitPrefix = successPrefixes.any { url.startsWith(it) }
        val hitCookie = successCookieName.isNotEmpty() && hasCookieNamed(successCookieName)
        if (!hitPrefix && !hitCookie) return
        autoConfirmed = true
        status?.text = "登录成功，正在保存…"
        // 稍等一拍：让 Set-Cookie 落盘、页面别闪一下就关
        web?.postDelayed({ collectAndFinish(true) }, AUTO_CONFIRM_DELAY_MS)
    }

    /**
     * 页面内换票的三段流程：离开登录页 → 落到搭子页面 → 页内请求换票。
     *
     * 登录页上不动手（用户还在输账号）；一旦跳到别的域（console / 搭子），
     * 说明授权链走完了，此时才切到 tokenPageUrl 并开始同源换票。
     */
    private fun maybeTokenFlow(url: String) {
        if (tokenResult != null) return
        val host = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("")
        if (host.isEmpty()) return
        if (host.startsWith("login.") || host.startsWith("passport.") || host.startsWith("wappass.")) {
            return
        }
        if (!tokenPageOpened) {
            tokenPageOpened = true
            status?.text = "登录成功，正在建立搭子会话…"
            if (tokenPageUrl.isNotEmpty()) web?.loadUrl(tokenPageUrl)
            return
        }
        if (tokenPageHost.isNotEmpty() && host != tokenPageHost) return
        startTokenProbe()
    }

    private fun startTokenProbe() {
        if (tokenProbeStarted || tokenResult != null) return
        tokenProbeStarted = true
        status?.text = "正在获取对话 token…"
        web?.postDelayed(tokenProbe, TOKEN_PROBE_INTERVAL_MS)
    }

    /**
     * 每 2 秒在页面里跑一次同源 GET。
     *
     * 用同步 XHR 是为了让结果作为 evaluateJavascript 的返回值直接回来——
     * evaluateJavascript 不会 await Promise，async fetch 拿不到结果。
     */
    private val tokenProbe: Runnable = object : Runnable {
        override fun run() {
            val view = web ?: return
            if (tokenResult != null) return
            if (tokenAttempts++ >= TOKEN_PROBE_MAX) {
                status?.text = "没能自动取到 token，可点右上角「完成」或「复制 Cookie」手动导入"
                return
            }
            val js = TOKEN_PROBE_JS.replace("__ENDPOINT__", tokenEndpoint)
            runCatching {
                view.evaluateJavascript(js) { raw ->
                    val text = decodeJsString(raw)
                    if (parseToken(text) != null) {
                        tokenResult = text
                        status?.text = "已取得对话 token，正在保存…"
                        collectAndFinish(pendingHosts, true)
                    } else {
                        view.postDelayed(tokenProbe, TOKEN_PROBE_INTERVAL_MS)
                    }
                }
            }.onFailure { status?.text = "取 token 失败（页面已关闭？），请点「完成」保存 Cookie" }
        }
    }

    /** 用户点「完成」：token 模式下先补一次换票，取不到才退回 cookie 导出。 */
    private fun onDonePressed(hosts: Array<String>) {
        if (tokenEndpoint.isNotEmpty() && tokenResult == null) {
            tokenAttempts = 0
            tokenProbeStarted = false
            startTokenProbe()
            return
        }
        collectAndFinish(hosts)
    }

    /** 从换票响应里挑出 token（顶层 / result / data 三种形状都认）。 */
    private fun parseToken(body: String): String? {
        if (body.isBlank() || body.startsWith("ERR:")) return null
        return tokenValueRegex.find(body)?.groupValues?.getOrNull(1)
    }

    private fun parseExpiry(body: String): Long {
        val raw = expiredAtRegex.find(body)?.groupValues?.getOrNull(1).orEmpty()
        val parsed = runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
        // 取不到过期时间就按 50 分钟兜底：宁可早刷，不要卡在过期点上
        return parsed ?: (System.currentTimeMillis() + 50 * 60 * 1000L)
    }

    /**
     * 抓一条请求的 Cookie 头，按键合并进 [interceptedCookies]。
     *
     * 只收目标域名的请求：WebView 同一时刻可能有广告/统计域的请求，那些 cookie
     * 拼进凭证串只会污染（上游看到一堆不认识的名字）。
     */
    private fun captureCookieHeader(request: WebResourceRequest) {
        val host = request.url?.host.orEmpty()
        if (host.isEmpty() || cookieHostsForCheck.isEmpty()) return
        val matched = cookieHostsForCheck.any { raw ->
            val target = raw.trim().trimStart('.').lowercase()
            target.isNotEmpty() && (host == target || host.endsWith(".$target"))
        }
        if (!matched) return
        val header = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }
            ?.value
            .orEmpty()
        if (header.isBlank()) return
        for (pair in header.split(';')) {
            val trimmed = pair.trim()
            if (trimmed.isEmpty()) continue
            val key = trimmed.substringBefore('=').trim()
            if (key.isEmpty()) continue
            interceptedCookies[key] = trimmed.substringAfter('=').trim()
        }
    }

    /** 拦截到的完整 cookie 串（不含抓不到的键时为空）。 */
    private fun interceptedCookieString(): String =
        interceptedCookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }

    /** 目标域名下是否存在指定名字的 Cookie。 */
    private fun hasCookieNamed(name: String): Boolean {
        val manager = CookieManager.getInstance()
        for (host in cookieHostsForCheck) {
            val raw = manager.getCookie(host).orEmpty()
            if (raw.split(';').any { it.trim().startsWith("$name=") }) return true
        }
        return false
    }

    /** 读取目标域名下的 Cookie 并回传；拿不到就提示用户先登录。 */
    private fun collectAndFinish(hosts: Array<String>) = collectAndFinish(hosts, false)

    private fun collectAndFinish(fromAuto: Boolean) = collectAndFinish(pendingHosts, fromAuto)

    /**
     * 读取登录态并回传。
     *
     * 除了 Cookie，还会把 **localStorage / sessionStorage 里的键值**一起拼进来：
     * 现在的网站多半是 SPA，登录 token 存在 Web Storage 而不是 Cookie，
     * 只读 Cookie 会出现「页面明明登录了、宿主却什么都没读到」。
     * 拼接后的整串交给各供应商自己的正则去挑（Today 是 32 位 hex，LongCat 是 passport_token_key）。
     */
    private fun collectAndFinish(hosts: Array<String>, fromAuto: Boolean) {
        val manager = CookieManager.getInstance()
        manager.flush()
        // 逐 host 收集后**按键去重**：子域与父域各有一份同名 cookie（如 a.example.com
        // 与 .baidu.com 的 sessionid），拼在一起服务端拿到两份同名键、行为不确定。
        // 保留先出现的（hosts 列表里具体域在前、父域在后）。
        val seenKeys = LinkedHashSet<String>()
        val pairs = ArrayList<String>()
        for (host in hosts) {
            val cookie = manager.getCookie(host).orEmpty()
            if (cookie.isBlank()) continue
            for (pair in cookie.split(';')) {
                val trimmed = pair.trim()
                if (trimmed.isEmpty()) continue
                val key = trimmed.substringBefore('=').trim().lowercase()
                if (seenKeys.add(key)) pairs.add(trimmed)
            }
        }
        // **拦截到的完整 cookie 优先**：它含 HttpOnly 会话项，是唯一能让外部接口认账的那份。
        // CookieManager 那份照旧保留其余键（两边的键基本重合，拦截版更新更全）。
        if (interceptedCookies.isNotEmpty()) {
            val merged = LinkedHashMap<String, String>()
            for (pair in pairs) {
                merged[pair.substringBefore('=').trim()] = pair.substringAfter('=').trim()
            }
            merged.putAll(interceptedCookies)
            pairs.clear()
            for ((k, v) in merged) pairs.add("$k=$v")
        }
        val cookies = StringBuilder(pairs.joinToString("; "))
        val view = web
        if (view == null) {
            deliver(cookies.toString(), fromAuto)
            return
        }
        // evaluateJavascript 是异步的，必须在回调里收尾
        runCatching {
            view.evaluateJavascript(STORAGE_PROBE_JS) { raw ->
                val storage = decodeJsString(raw)
                val merged = when {
                    cookies.isEmpty() -> storage
                    storage.isEmpty() -> cookies.toString()
                    else -> "$cookies; $storage"
                }
                deliver(merged, fromAuto)
            }
        }.onFailure { deliver(cookies.toString(), fromAuto) }
    }

    /** 把读到的登录态交回宿主；拿不到时给出可操作的提示而不是静默返回。 */
    private fun deliver(raw: String, fromAuto: Boolean) {
        // 页内换票拿到了票：cookie 和 token 一起交回去（cookie 还要用于签到/额度）
        val tokenBody = tokenResult
        val token = if (tokenBody == null) null else parseToken(tokenBody)
        if (tokenBody != null && token != null) {
            val payload = com.google.gson.JsonObject().apply {
                addProperty("cookie", raw)
                addProperty("token", token)
                addProperty("tokenExpiresAt", parseExpiry(tokenBody))
            }
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_COOKIE, payload.toString()))
            finish()
            return
        }
        if (raw.isBlank()) {
            if (!fromAuto) {
                Toast.makeText(this, "还没读到登录信息，请先在页面里完成登录", Toast.LENGTH_LONG).show()
                return
            }
            // 自动确认命中但读不到任何登录态：多半是登录还没真正落盘（或域名配错了）。
            // 必须复位并给出可见提示，否则界面会永久停在「正在保存…」。
            autoConfirmed = false
            status?.text = "没读到登录信息，请确认已登录成功，再点右上角「完成」"
            return
        }
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_RESULT_COOKIE, raw),
        )
        finish()
    }

    /** WebView 的 evaluateJavascript 会把结果当 JSON 字面量返回（带引号、可能有 \u 转义）。 */
    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            com.google.gson.JsonParser.parseString(raw).asString
        }.getOrDefault("")
    }

    override fun onBackPressed() {
        val view = web
        if (view != null && view.canGoBack()) {
            view.goBack()
            return
        }
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        try {
            web?.stopLoading()
            web?.destroy()
        } catch (_: Throwable) {
        }
        web = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 读取当前窗口主题里的颜色属性。
     *
     * Activity 是原生 View（不是 Compose），拿不到 AiGatewayTheme 的配色，
     * 所以用主题属性代替硬编码色值：系统深色时自动换成深色底 + 浅色字。
     */
    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return FALLBACK_COLOR
        return if (value.resourceId != 0) {
            runCatching { resources.getColor(value.resourceId, theme) }.getOrDefault(FALLBACK_COLOR)
        } else {
            value.data
        }
    }

    companion object {
        const val EXTRA_START_URL = "start_url"
        const val EXTRA_COOKIE_HOSTS = "cookie_hosts"
        const val EXTRA_SETTLE_URLS = "settle_urls"

        /** 页面内换票的接口路径（同源相对路径）。 */
        const val EXTRA_TOKEN_ENDPOINT = "token_endpoint"

        /** 换票前要落到的页面（决定请求的 Origin）。 */
        const val EXTRA_TOKEN_PAGE_URL = "token_page_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_RESULT_COOKIE = "cookie"
        /** 命中任一前缀即视为登录完成（回调地址）。 */
        const val EXTRA_SUCCESS_URL_PREFIXES = "success_url_prefixes"
        /** 出现这个 Cookie 即视为登录完成（没有回调地址的上游）。 */
        const val EXTRA_SUCCESS_COOKIE = "success_cookie"

        /** 桌面 UA：绕开「移动端不允许」的地域/客户端判定。 */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** 用桌面 UA + 桌面视口打开登录页（Trae 国际版这类按 UA 拦人的上游）。 */
        const val EXTRA_DESKTOP_UA = "desktop_ua"

        /** 自动确认后等多久再收尾，给 Set-Cookie 落盘留时间。 */
        private const val AUTO_CONFIRM_DELAY_MS = 600L

        /** 换票轮询间隔：SPA 起完到接口可用要几秒，2 秒一探够快也不吵。 */
        private const val TOKEN_PROBE_INTERVAL_MS = 2000L

        /** 最多探 20 次（40 秒），超时就退回手动。 */
        private const val TOKEN_PROBE_MAX = 20

        /**
         * 页内换票脚本。
         *
         * 关键点是 withCredentials：让 HttpOnly 会话 cookie 随请求发出——
         * 那正是 CookieManager.getCookie() 拿不到、而服务端要求的东西。
         */
        private const val TOKEN_PROBE_JS =
            "(function(){try{" +
                "var x=new XMLHttpRequest();" +
                "x.open('GET','__ENDPOINT__',false);" +
                "x.withCredentials=true;" +
                "x.send(null);" +
                "return x.responseText;" +
                "}catch(e){return 'ERR:'+(e&&e.message?e.message:e);}})()"


        /**
         * 读取 Web Storage 的探针。
         *
         * 只收 500 字符以内的值：token 都很短，超长的是缓存正文，拼进来没意义还会把
         * 供应商那边的正则拖慢。整体用 try 包住——某些页面禁用了 storage，抛异常会让
         * evaluateJavascript 直接不回调。
         */
        private const val STORAGE_PROBE_JS = """
            (function(){try{
              var out=[];
              try{
                for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);var v=localStorage.getItem(k);if(v&&v.length<500){out.push(k+'='+v);}}
              }catch(e){}
              try{
                for(var j=0;j<sessionStorage.length;j++){var k2=sessionStorage.key(j);var v2=sessionStorage.getItem(k2);if(v2&&v2.length<500){out.push(k2+'='+v2);}}
              }catch(e){}
              return out.join(';');
            }catch(e){return '';}})()
        """
        const val REQUEST_CODE = 8803

        /** 主题属性解析失败时的中性灰：比「一片全黑/全白」更容易看出是降级了。 */
        private const val FALLBACK_COLOR = 0xFF888888.toInt()
    }
}
