package dev.aigw.app.ui.providers

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.gson.JsonObject
import dev.aigw.core.gateway.ProviderSettings
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.ProviderTaskListView
import dev.aigw.core.provider.PointsBillListView
import dev.aigw.core.provider.QuotaPack

/**
 * 供应商 UI 组件对外可用的动作。
 *
 * 组件不直接依赖 ViewModel：只通过这些回调发起操作，
 * 因此新增/替换一个供应商的实现不会牵动页面骨架与其它供应商。
 */
class ProviderUiActions(
    /**
     * 拉起浏览器登录（Trae 回调型 / Antigravity OAuth / 小浣熊）。
     *
     * region 由组件按滑块传入：供应商设置是异步写入的，登录流程不能把正确性押在它上面
     * （区域型供应商会按它选登录端点）。无区域概念时传空串。
     */
    val onWebLogin: (providerId: String, region: String) -> Unit = { _, _ -> },
    /**
     * 内嵌网页登录：打开 [url]，用户登录完成后读取 [hosts] 的 Cookie 交回。
     *
     * 与 [onWebLogin] 的分工：这个在 **App 内**用 WebView 完成，不跳系统浏览器、
     * 也不需要网关在本地开回调端口。适合「凭证就是 Cookie」的上游（LongCat / Today）。
     */
    /**
     * 内嵌网页登录：打开 [url]，用户登录完成后读取 [hosts] 的 Cookie 交回。
     *
     * [cookieName] 非空时，必须等到这个 cookie 出现才自动完成——
     * 用于「登录跳转链很长、最后一跳才种下关键会话」的上游
     * （百度搭子：BDUSS 在 passport 登录后就有，但 bce-sessionid
     * 要等整个跳转链走完才种上，提前收就只能拿到半套）。
     */
    val onCookieLogin: (
        providerId: String,
        url: String,
        hosts: List<String>,
        title: String,
        cookieName: String,
        settleUrls: List<String>,
    ) -> Unit = { _, _, _, _, _, _ -> },

    /**
     * 内嵌网页登录（桌面 UA 版）。
     *
     * 部分上游按 UA 判断客户端平台，手机 UA 直接回「该地区不开放」，桌面 UA 同网却能进
     * （Trae 国际版就是）。需要时用它，别去改全局 UA。
     */
    /**
     * 同 [onCookieLogin]，但 WebView 用桌面 UA（对 UA 敏感的上游用）。
     *
     * [tokenEndpoint] 非空时走**页内换票**：不导出 cookie，而是在登录后的页面里发一条
     * 同源 GET，让 WebView 自己把 HttpOnly 会话 cookie 带上，直接把 token 取回来。
     * 百度搭子必须这么走——它的 `BDUSS` / `bce-long-term-sessionid` 是 HttpOnly，
     * `CookieManager.getCookie()` 读不到，导出的 cookie 串服务端一律判「登录态已失效」。
     * [tokenPageUrl] 决定换票请求的 Origin（要和登录态同域）。
     */
    val onDesktopCookieLogin: (
        providerId: String,
        url: String,
        hosts: List<String>,
        title: String,
        cookieName: String,
        settleUrls: List<String>,
        tokenEndpoint: String,
        tokenPageUrl: String,
    ) -> Unit = { _, _, _, _, _, _, _, _ -> },
    /** 设备授权登录（WorkBuddy）：拉起 WebView 并开始轮询；region 由组件按滑块传入。 */
    val onDeviceLogin: (providerId: String, region: String) -> Unit = { _, _ -> },
    /** 读供应商私有设置项（同步，取引擎内存值）。 */
    val providerOptionOf: (providerId: String, key: String, fallback: String) -> String =
        { _, _, fallback -> fallback },
    /** 写供应商私有设置项（如 WorkBuddy 的 region 滑块）。 */
    val onUpdateProviderOption: (providerId: String, key: String, value: String) -> Unit = { _, _, _ -> },
    /**
     * 短信登录：code 为空表示「先发验证码」；[onResult] 回传错误信息（空串表示成功）。
     *
     * [ccode] 是国家码（不带 `+`）；上游按它路由短信通道，非大陆号码必须填对。
     */
    val onSmsLogin:
        (providerId: String, phone: String, code: String, ccode: String, onResult: (String) -> Unit) -> Unit =
        { _, _, _, _, _ -> },
    /** 粘贴凭证 JSON 导入。 */
    val onImport: (providerId: String, raw: String) -> Unit = { _, _ -> },
    /** 刷新额度；uid 为空表示刷新该供应商全部账号。 */
    val onRefreshCredits: (providerId: String, uid: String?) -> Unit = { _, _ -> },
    /** 执行供应商专属动作（签到、兑换码等）。 */
    val onAction: (providerId: String, uid: String, action: String, payload: JsonObject) -> Unit = { _, _, _, _ -> },

    /**
     * **页内积分**：签到/查额度这类依赖 HttpOnly 会话 cookie 的动作，在 WebView 里执行。
     *
     * 搭子实证：cookie 导出永远缺 HttpOnly 会话项，外部发请求一律 302；
     * 只有页面里发的同源请求才会被服务端认账。结果走 Activity 回传 + notice。
     */
    val onInPagePoints: (providerId: String, uid: String) -> Unit = { _, _ -> },
    /** 调整账号使用优先级（↑/↓）：排在前面的先用，同级仍按余额。 */
    val onMoveAccountPriority: (providerId: String, uid: String, up: Boolean) -> Unit = { _, _, _ -> },
    val onToggleAccount: (providerId: String, uid: String, enabled: Boolean) -> Unit = { _, _, _ -> },
    val onRemoveAccount: (providerId: String, uid: String) -> Unit = { _, _ -> },
    /** 账号凭证级修改（如 Trae 的设备指纹）：直接替换 secret。 */
    val onUpdateSecret: (providerId: String, uid: String, secret: String, notice: String) -> Unit =
        { _, _, _, _ -> },
    /** 读账号的当前设备指纹（仅 Trae 用；其它供应商返回空串）。 */
    val deviceIdOf: (providerId: String, uid: String) -> String = { _, _ -> "" },
    /** 读账号所属区域（如 WorkBuddy 的 cn/global；无区域概念返回空串）。 */
    val regionOf: (providerId: String, uid: String) -> String = { _, _ -> "" },
    /** 读已拉取的额度包明细（键 `providerId/uid`；需要拉取时调 [onLoadCreditPacks]）。 */
    val creditPacksOf: (providerId: String, uid: String) -> List<QuotaPack> = { _, _ -> emptyList() },
    /** 异步拉取账号的额度包明细（打上游，结果回到 [creditPacksOf]）。 */
    val onLoadCreditPacks: (providerId: String, uid: String) -> Unit = { _, _ -> },
    /** 读已拉取的成长任务明细（键 `providerId/uid`；需要拉取时调 [onLoadTaskList]）。 */
    val taskListOf: (providerId: String, uid: String) -> ProviderTaskListView? = { _, _ -> null },
    /** 异步拉取账号的成长任务明细（打上游，结果回到 [taskListOf]）。 */
    val onLoadTaskList: (providerId: String, uid: String) -> Unit = { _, _ -> },
    /** 读已拉取的积分流水明细（键 `providerId/uid`；需要拉取时调 [onLoadPointsBills]）。 */
    val pointsBillsOf: (providerId: String, uid: String) -> PointsBillListView? = { _, _ -> null },
    /** 异步拉取账号的积分流水明细（打上游，结果回到 [pointsBillsOf]）。 */
    val onLoadPointsBills: (providerId: String, uid: String) -> Unit = { _, _ -> },
    /** 重新生成一套设备指纹（仅 Trae 用）。 */
    val onRegenerateDeviceId: (providerId: String, uid: String) -> Unit = { _, _ -> },
    /** 手动填入设备指纹（仅 Trae 用）。 */
    val onSetDeviceId: (providerId: String, uid: String, value: String) -> Unit = { _, _, _ -> },
    /** 添加自定义供应商账号（名称可选，默认同供应商名）；连同模型列表一并保存。 */
    val onAddCustomAccount: (providerId: String, nickname: String, apiKey: String, models: List<String>) -> Unit =
        { _, _, _, _ -> },
    /** 改账号显示名称（自定义供应商用）。 */
    val onRenameAccount: (providerId: String, uid: String, nickname: String) -> Unit = { _, _, _ -> },
    /** 读账号的 API Key（仅自定义供应商）。 */
    val accountApiKeyOf: (providerId: String, uid: String) -> String = { _, _ -> "" },
    /** 读自定义供应商已保存的模型列表。 */
    val customModelsOf: (providerId: String) -> List<String> = { _ -> emptyList() },
    /** 读自定义供应商的接口地址（拉模型用）。 */
    val customBaseUrlOf: (providerId: String) -> String = { _ -> "" },
    /** 用任意 key 从云端拉模型列表（自定义供应商）。 */
    val onFetchCustomModels: (baseUrl: String, apiKey: String, onResult: (List<String>, String) -> Unit) -> Unit =
        { _, _, _ -> },
    val onNotice: (String) -> Unit = {},
)

/**
 * 一个供应商的界面组件。
 *
 * 每个供应商实现自己的图标、登录入口、账号附加信息与专属设置，
 * 由 [ProviderUiRegistry] 登记；页面骨架（列表/详情）不感知具体差异。
 */
interface ProviderUi {
    /** 与 `Provider.id` 一致（如 `trae`、`custom:<key>`）。 */
    val id: String

    /** 账号列表由 LoginEntry 自管时，页面骨架不再渲染通用「账号」卡。 */
    val managesOwnAccounts: Boolean get() = false

    /** 列表卡片上的说明文案。 */
    fun description(): String

    @Composable
    fun Icon(modifier: Modifier)

    /** 登录/添加账号入口。 */
    @Composable
    fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions)

    /** 账号详情里的专属信息区（额度明细、设备指纹、签到等）。默认不渲染。 */
    @Composable
    fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) = Unit

    /** 该供应商的专属设置区。默认不渲染。 */
    @Composable
    fun SettingsSection(settings: ProviderSettings, onUpdate: (ProviderSettings) -> Unit) = Unit
}
