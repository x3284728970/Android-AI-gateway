package dev.aigw.core.provider.trae

/**
 * Trae 两种发行版的上游地址。
 *
 *  - **国内版**（`trae.cn`）：字节 Trae SOLO 通道。
 *  - **国际版**（`trae.ai`）：海外发行版，额度接口在 **v1**（国内是 v2），且**没有签到**。
 *
 * 实测（2026-10-10）：
 *  - `POST api.trae.ai/cloudide/api/v3/trae/oauth/ExchangeToken` 对国内 client_id 返回
 *    401「refresh token is invalid」——端点真实，且 client_id 国内国际通用；
 *  - `POST ug-normal.trae.ai/trae/api/v1/pay/user_current_entitlement_list` 对假 JWT 返回
 *    401 `{"code":1001,...,"user_entitlement_pack_list":[]}` ——额度接口在 v1；
 *  - `www.trae.ai/trae/api/v2/pay/...` 返回 200 但那是 SPA 兜底页（所有路径都 200），**不是接口**。
 */
enum class TraeRegion(
    val id: String,
    val label: String,
    val agentHost: String,
    val ugHost: String,
    val oauthHost: String,
    val consoleHost: String,
    /** ExchangeToken 用的 OAuth client_id：**两个区域不同**，混用会 401。 */
    val oauthClientId: String,
) {
    CN(
        id = "cn",
        label = "国内版",
        agentHost = "https://trae-api-cn.mchost.guru",
        ugHost = "https://api.trae.cn",
        // ExchangeToken / GetUserInfo 在 auth host 上，**不是 chat host**（混用会得到 404）。
        // api.trae.cn 实测 400（端点存在）；api.trae.com.cn 是控制台域名，历史上也能通，
        // 但社区（wangqi233/trae2api realms.js）以 api.trae.cn 为准。
        oauthHost = "https://api.trae.cn",
        consoleHost = "https://www.trae.cn",
        oauthClientId = "en1oxy7wnw8j9n",
    ),
    INTL(
        id = "global",
        label = "国际版",
        // 国际版是独立部署（realm），chat 与 auth 分属两个 host（mingqi233/trae2api realms.js 实证）：
        //   chat  coresg-normal.trae.ai（美区 coreva-normal.trae.ai）
        //   auth  growsg-normal.trae.ai
        // 混用会得到 404 TLB 页 —— 就是用户看到的「上游接口不存在」。
        // trae-api-sg.mchost.guru 是旧猜测值，实测 chat 虽然 401（存在）但 auth 404。
        agentHost = "https://coresg-normal.trae.ai",
        ugHost = "https://ug-normal.trae.ai",
        oauthHost = "https://growsg-normal.trae.ai",
        consoleHost = "https://www.trae.ai",
        // 国际版用**另一个 client_id**：拿国内的 en1oxy7wnw8j9n 去换 refresh token 会 401
        // （「refresh token is invalid」），用户看到的就是「换不出 accessToken」。
        oauthClientId = "ono9krqynydwx5",
    ),
    ;

    companion object {
        fun of(id: String?): TraeRegion =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: CN

        /**
         * 推断区域。
         *
         * 两个入参都参与判定：`explicit` 是凭证里的 `domain`（历史上既写过区域 id `cn`/`global`，
         * 也写过主机名 `trae.cn`，缺字段时还会被 TraeAccount.parse 填成 `trae.cn`），
         * `domain` 是 `apiHost` 或登录回调里的 host。
         * 只认第一个参数会把 apiHost 已是 `api.trae.ai` 的国际版凭证判成国内版。
         */
        fun infer(explicit: String?, domain: String?): TraeRegion {
            val values = listOf(explicit, domain).map { it?.trim().orEmpty() }
            if (values.any { it.equals(CN.id, ignoreCase = true) }) return CN
            if (values.any { it.equals(INTL.id, ignoreCase = true) }) return INTL
            return if (values.any {
                    val v = it.lowercase()
                    v.contains("trae.ai") || v.contains("growsg") || v.contains("coresg") ||
                        v.contains("coreva") || v.contains("trae-api-sg") ||
                        v.contains("ug-normal.us") || v == "us-east" || v == "us"
                }
            ) INTL else CN
        }
    }
}

/**
 * 一个账号实际要打的三个上游 host。
 *
 * 国际版不是「一个区域」，而是**新加坡 / 美区两套部署**（凭证里的 `region: US-East`），
 * chat、ug、auth 的域名各不相同。只按「国内/国际」两个粒度取 host，会让美区账号
 * 全部打到新加坡，得到 404——用户看到的「上游接口不存在」就是这么来的。
 */
data class TraeUpstream(
    val chatHost: String,
    val ugHost: String,
    val authHost: String,
) {
    companion object {
        /** 美区的 ug/auth 都在 us 子域；chat 在 coreva。 */
        private val US = TraeUpstream(
            chatHost = "https://coreva-normal.trae.ai",
            ugHost = "https://ug-normal.us.trae.ai",
            authHost = "https://ug-normal.us.trae.ai",
        )

        private val SG = TraeUpstream(
            chatHost = "https://coresg-normal.trae.ai",
            ugHost = "https://ug-normal.trae.ai",
            authHost = "https://growsg-normal.trae.ai",
        )

        private val CN = TraeUpstream(
            chatHost = "https://trae-api-cn.mchost.guru",
            ugHost = "https://api.trae.cn",
            authHost = "https://api.trae.cn",
        )

        /** 按区域 + 账号区解析；[TraeRegion.INTL] 内部再分美区/新加坡。 */
        fun of(region: TraeRegion, userRegion: String): TraeUpstream {
            if (region == TraeRegion.CN) return CN
            val code = userRegion.trim().uppercase()
            return if (code.startsWith("US") || code.startsWith("VA") || code.contains("US-")) US else SG
        }
    }
}

/** Trae 上游常量。取值来自实测，改动需重新验证。 */
object TraeConstants {
    // 上游 host 一律从 [TraeRegion] 取（各地区一套），不要在这里再定义国内别名：
    // 它们看起来像「默认值」，实际只对国内成立，用作缺省值会让国际版账号打到国内站点。

    const val CLIENT_ID = "en1oxy7wnw8j9n"
    const val APP_ID = "6eefa01c-1036-4c7e-9ca5-d891f63bfcd8"

    /** 上游只放行该 function 取值，work / solo / work_lite 均无效。 */
    const val FUNCTION = "solo_work_lite"

    const val EP_CHAT = "/api/agent/v3/llm_utils_chat"
    const val EP_MODELS = "/api/ide/v1/get_detail_param"
    const val EP_EXCHANGE = "/cloudide/api/v3/trae/oauth/ExchangeToken"
    const val EP_USER_INFO = "/cloudide/api/v3/trae/GetUserInfo"
    const val EP_CHECK_LOGIN = "/cloudide/api/v3/trae/CheckLogin"

    /** 网页会话用 Cookie 换会话 token（桌面模式登录后走这条）。 */
    const val EP_GET_USER_TOKEN = "/cloudide/api/v3/common/GetUserToken"
    const val EP_CHECKIN_STATUS = "/trae/api/v2/ug/checkin_credits/status"
    const val EP_CHECKIN_CLAIM = "/trae/api/v2/ug/checkin_credits/claim"

    /**
     * 额度查询的候选接口（国内版 v2）。不同入口（网页版 / IDE）暴露的路径不一样，按顺序试：
     *  - web_user_ent_usage：网页版积分接口，响应最完整（含 usage_summary 与额度包明细）
     *  - ide_user_ent_usage：IDE 侧同名接口
     *  - user_current_entitlement_list：需 require_usage 才会带 usage
     *
     * 国际版只有 [EP_ENTITLEMENT_INTL] / [EP_ENT_USAGE_INTL] 两个 v1 接口，**没有 web 版**。
     */
    const val EP_ENT_USAGE_WEB = "/trae/api/v2/pay/web_user_ent_usage"
    const val EP_ENT_USAGE = "/trae/api/v2/pay/ide_user_ent_usage"
    const val EP_ENTITLEMENT_LIST = "/trae/api/v2/pay/user_current_entitlement_list"

    /** 国际版（v1）额度接口。 */
    const val EP_ENTITLEMENT_INTL = "/trae/api/v1/pay/user_current_entitlement_list"
    const val EP_ENT_USAGE_INTL = "/trae/api/v1/pay/ide_user_ent_usage"

    /** 请求未指定模型时使用的 config_name。 */
    const val DEFAULT_CONFIG_NAME = "glm-5.2"

    /** SOLO 通道计费落在 ide_credits 上（work_credits 不可反代）。 */
    const val REGION_CN = "trae.cn"
}

/**
 * 客户端版本号。上游按 X-Ide-Version 决定模型可用性，版本过旧会返回 4001，
 * 因此做成可配置项而不是硬编码常量。
 */
data class TraeVersion(
    val ideVersion: String = "0.1.52",
    val ideVersionCode: String = "20260811",
    val pluginVersion: String = "2.3.62834",
    val deviceBrand: String = "83DG",
    val osVersion: String = "Windows 11 Pro",
)
