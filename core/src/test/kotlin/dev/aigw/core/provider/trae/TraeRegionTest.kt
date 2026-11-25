package dev.aigw.core.provider.trae

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.ProviderAccount
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 区域推断：凭证里的 domain 既可能是区域 id（cn/global），也可能是主机名（trae.cn / www.trae.ai），
 * 缺字段时还会被 TraeAccount.parse 填成 trae.cn 默认值。因此 apiHost 必须参与判定——
 * 只认 domain 会把 apiHost 已是 api.trae.ai 的国际版凭证判成国内版，
 * 它的模型目录、对话与额度就都会打到国内 host。
 */
class TraeRegionTest {

    @Test
    fun `区域 id 优先，主机名参与兜底`() {
        assertEquals(TraeRegion.INTL, TraeRegion.infer("global", "https://api.trae.cn"))
        assertEquals(TraeRegion.CN, TraeRegion.infer("cn", "https://api.trae.ai"))
        assertEquals(TraeRegion.INTL, TraeRegion.infer("www.trae.ai", "https://api.trae.ai"))
        assertEquals(TraeRegion.CN, TraeRegion.infer("trae.cn", "https://api.trae.com.cn"))
        assertEquals(TraeRegion.INTL, TraeRegion.infer(null, "https://api.trae.ai"))
        assertEquals(TraeRegion.CN, TraeRegion.infer(null, null))
    }

    @Test
    fun `缺 domain 的凭证按 apiHost 判区域`() {
        val provider = TraeProvider(
            store = InMemoryKeyValueStore(),
            version = { TraeVersion() },
            defaultModel = { "glm-5.2" },
        )

        assertEquals(TraeRegion.INTL.id, provider.regionOf(accountWith(apiHost = "https://api.trae.ai")))
        assertEquals(TraeRegion.CN.id, provider.regionOf(accountWith(apiHost = "https://api.trae.com.cn")))
    }

    @Test
    fun `国际版 chat 与 auth 分属不同 host`() {
        // wangqi233/trae2api 的 realms.js 实证：国际版是独立部署，
        //   chat  coresg-normal.trae.ai（美区 coreva-normal.trae.ai）
        //   auth  growsg-normal.trae.ai
        // 混用会拿到 404 TLB 页（用户看到的「上游接口不存在」）。
        assertEquals("https://coresg-normal.trae.ai", TraeRegion.INTL.agentHost)
        assertEquals("https://growsg-normal.trae.ai", TraeRegion.INTL.oauthHost)
        // client_id 也分区域：混用会 401「refresh token is invalid」
        assertEquals("ono9krqynydwx5", TraeRegion.INTL.oauthClientId)
        assertEquals("en1oxy7wnw8j9n", TraeRegion.CN.oauthClientId)
    }

    @Test
    fun `代理域名清单同时覆盖国内与国际版`() {
        val provider = TraeProvider(
            store = InMemoryKeyValueStore(),
            version = { TraeVersion() },
            defaultModel = { "glm-5.2" },
        )

        val hosts = provider.hosts()
        // 国际版账号请求的是另外一套 host，漏掉就会绕过代理开关
        assertEquals(true, hosts.contains(TraeRegion.INTL.agentHost.removePrefix("https://")))
        assertEquals(true, hosts.contains(TraeRegion.INTL.ugHost.removePrefix("https://")))
        assertEquals(true, hosts.contains(TraeRegion.CN.agentHost.removePrefix("https://")))
    }

    @Test
    fun `代理域名清单按区域分开返回`() {
        val provider = TraeProvider(
            store = InMemoryKeyValueStore(),
            version = { TraeVersion() },
            defaultModel = { "glm-5.2" },
        )

        val cn = provider.hosts(TraeRegion.CN.id)
        val intl = provider.hosts(TraeRegion.INTL.id)
        // 国内版不应包含国际版 host：否则「国内直连、国外走代理」无法表达
        assertEquals(false, cn.contains(TraeRegion.INTL.agentHost.removePrefix("https://")))
        assertEquals(false, intl.contains(TraeRegion.CN.agentHost.removePrefix("https://")))
    }

    @Test
    fun `国际版账号不会用国内鉴权 host`() {
        val client = TraeAuthClient(TraeVersion())
        val international = TraeAccount(
            uid = "u1",
            accessToken = "at",
            refreshToken = "rt",
            expiresAt = 0,
            nickname = "",
            enterpriseId = "",
            machineId = "m".repeat(64),
            deviceId = "1700000000000000",
            domain = TraeRegion.INTL.id,
            apiHost = TraeRegion.CN.oauthHost,
        )

        // 存量数据：旧版把国内 OAuth host 当缺省值写进了国际版凭证
        assertEquals(TraeRegion.INTL.oauthHost, client.authHost(international))
        // 凭证没带 apiHost 时按区域补
        assertEquals(TraeRegion.INTL.oauthHost, client.authHost(international.copy(apiHost = "")))
        // 用户自己配的 host 不被改写
        assertEquals(
            "https://trae-mirror.example.com",
            client.authHost(international.copy(apiHost = "https://trae-mirror.example.com")),
        )
        // 国内账号保持原样
        assertEquals(
            TraeRegion.CN.oauthHost,
            client.authHost(international.copy(domain = TraeRegion.CN.id)),
        )
    }

    @Test
    fun `美区凭证按 apiBase 与 region 解析到美区 host`() {
        // 浏览器导出的国际版凭证形状：扁平结构 + apiBase + region=US-East
        val raw = """
            {"accessToken":"a.b.c","tokenType":"Bearer","userID":"7698408278820537358",
             "tenantID":"7o2d894p7dr0o4","region":"US-East",
             "apiBase":"https://ug-normal.us.trae.ai","isLoggedIn":true}
        """.trimIndent()
        val account = TraeAccount.parse(raw)
        assertEquals("https://ug-normal.us.trae.ai", account.apiHost)
        assertEquals("US-East", account.userRegion)

        // 区域判为国际版，且 host 解析到美区（chat 在 coreva、ug/auth 在 us 子域）
        assertEquals(TraeRegion.INTL, TraeRegion.infer(account.domain, account.apiHost))
        val hosts = TraeUpstream.of(TraeRegion.INTL, account.userRegion)
        assertEquals("https://coreva-normal.trae.ai", hosts.chatHost)
        assertEquals("https://ug-normal.us.trae.ai", hosts.ugHost)
        assertEquals("https://ug-normal.us.trae.ai", hosts.authHost)

        // 没有 region 的国际版账号走新加坡
        val sg = TraeUpstream.of(TraeRegion.INTL, "")
        assertEquals("https://coresg-normal.trae.ai", sg.chatHost)
        assertEquals("https://growsg-normal.trae.ai", sg.authHost)
    }

    @Test
    fun `登录时选国际版，回调不带 loginHost 也按国际版处理`() {
        // 回归背景（issue #14）：completeWebLogin 只根据回调参数猜区域，
        // 回调不带 loginHost 时会被默认判成国内版，接着把换 token 的请求发到 api.trae.com.cn，
        // 国际版账号因此一直「登录失败」。区域必须以用户点登录时选的那个为准。
        val provider = TraeProvider(
            store = InMemoryKeyValueStore(),
            version = { TraeVersion() },
            defaultModel = { "glm-5.2" },
        )
        val ticket = provider.beginWebLogin("http://127.0.0.1:51120/authorize", TraeRegion.INTL.id)
        assertEquals(true, ticket.loginUrl.startsWith(TraeRegion.INTL.consoleHost))

        // 回调只带 traceId，不带任何主机信息：仍应回到国际版
        val callback = "http://127.0.0.1:51120/authorize?loginTraceID=${ticket.id}"
        assertEquals(TraeRegion.INTL, provider.resolveCallbackRegion(callback))
    }

    @Test
    fun `没有登录记录时按回调里的主机判区域`() {
        val provider = TraeProvider(
            store = InMemoryKeyValueStore(),
            version = { TraeVersion() },
            defaultModel = { "glm-5.2" },
        )
        // 进程被杀、pending 丢失的兼容路径：只能靠回调参数
        assertEquals(
            TraeRegion.INTL,
            provider.resolveCallbackRegion("http://127.0.0.1:51120/authorize?loginHost=www.trae.ai"),
        )
        assertEquals(
            TraeRegion.CN,
            provider.resolveCallbackRegion("http://127.0.0.1:51120/authorize?loginHost=www.trae.cn"),
        )
    }

    private fun accountWith(apiHost: String): ProviderAccount {
        val secret = """
            {"auth":{"accessToken":"at","refreshToken":"rt","expiresAt":0,"apiHost":"$apiHost",
             "machineId":"${"m".repeat(64)}","deviceId":"1700000000000000"},
             "account":{"uid":"u1","enterpriseId":"","nickname":"昵称"}}
        """.trimIndent()
        return ProviderAccount(TraeProvider.ID, "u1", "昵称", secret)
    }
}
