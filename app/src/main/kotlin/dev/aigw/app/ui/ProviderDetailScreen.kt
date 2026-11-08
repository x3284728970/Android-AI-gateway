package dev.aigw.app.ui

import androidx.compose.foundation.clickable
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.app.ui.providers.ProviderUiRegistry
/**
 * 供应商详情：登录入口 + 账号列表 + 专属设置。
 *
 * 账号卡片里的「专属信息区」由该供应商自己的 UI 组件渲染（[ProviderUiRegistry]），
 * 所以这里不需要认识设备指纹、签到、兑换码这些概念。
 */
@Composable
fun ProviderDetailScreen(
    providerId: String,
    state: AppUiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onEditCustom: (String) -> Unit,
) {
    val ui = remember(providerId) { ProviderUiRegistry.require(providerId) }
    val info = state.providerOf(providerId)
    val accounts = state.accountsOf(providerId)

    // 内嵌网页登录（LongCat / Today 这类「凭证即 Cookie」的上游）：
    // 记住这次是给哪个供应商登录 —— Activity 回调只会带回 Cookie，不带 providerId。
    val context = LocalContext.current
    var pendingCookieProvider by remember { mutableStateOf("") }
    // 页内积分（搭子）：记住这次是给哪个供应商执行的
    var pendingInPagePoints by remember { mutableStateOf("") }
    val cookieLogin = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            pendingCookieProvider = ""
            return@rememberLauncherForActivityResult
        }
        val cookie = result.data?.getStringExtra(CookieLoginActivity.EXTRA_RESULT_COOKIE).orEmpty()
        val target = pendingCookieProvider
        if (cookie.isNotEmpty() && target.isNotEmpty()) {
            // 页内换票模式回来的是整份凭证 JSON（cookie + token + 过期时间），原样交给供应商；
            // 其余上游回来的还是裸 cookie 串，按老样子包一层。
            val json = if (cookie.trimStart().startsWith("{")) cookie
            else JsonObject().apply { addProperty("cookie", cookie) }.toString()
            viewModel.importCredentials(target, json)
        }
        pendingCookieProvider = ""
    }

    val inPagePoints = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val target = pendingInPagePoints
        pendingInPagePoints = ""
        if (result.resultCode != Activity.RESULT_OK || target.isEmpty()) {
            return@rememberLauncherForActivityResult
        }
        val message = result.data
            ?.getStringExtra(DumatePointsActivity.EXTRA_RESULT_MESSAGE)
            .orEmpty()
        if (message.isNotEmpty()) viewModel.notice(message)
        // 余额/签到状态变了，让卡片重拉一次（额度数字在 notice 里展示）
        viewModel.refresh()
    }

    // 缓存回调集合：30+ 个 lambda 每次重组重建会让账号卡片永远无法跳过重组。
    // creditPacks/taskLists 随 state 变化，用 rememberUpdatedState 读取最新值。
    val creditPacks = rememberUpdatedState(state.creditPacks)
    val taskLists = rememberUpdatedState(state.taskLists)
    val pointsBills = rememberUpdatedState(state.pointsBills)
    val actions = remember(viewModel, providerId) {
        ProviderUiActions(
            // 登录一律交给系统浏览器：本机 WebView 渲染异常（整页缩放错乱、输入框文字镜像），
            // 网关会在本地临时监听回调端口接住登录结果。
            onWebLogin = { id, region -> viewModel.beginBrowserLogin(id, region) },
            onCookieLogin = { id, url, hosts, title, cookieName, settleUrls ->
                pendingCookieProvider = id
                val intent = Intent(context, CookieLoginActivity::class.java).apply {
                    putExtra(CookieLoginActivity.EXTRA_START_URL, url)
                    putExtra(CookieLoginActivity.EXTRA_COOKIE_HOSTS, hosts.toTypedArray())
                    putExtra(CookieLoginActivity.EXTRA_TITLE, title)
                    // 等关键会话 cookie 出现再完成（搭子的 bce-sessionid 在跳转链最后一跳）
                    if (cookieName.isNotEmpty()) {
                        putExtra(CookieLoginActivity.EXTRA_SUCCESS_COOKIE, cookieName)
                    }
                    // 跨域会话 cookie 必须真正访问那个域才会种下（bce 系要在 console 域）
                    if (settleUrls.isNotEmpty()) {
                        putExtra(CookieLoginActivity.EXTRA_SETTLE_URLS, settleUrls.toTypedArray())
                    }
                }
                cookieLogin.launch(intent)
            },
            onDesktopCookieLogin = { id, url, hosts, title, cookieName, settleUrls, tokenEndpoint, tokenPageUrl ->
                pendingCookieProvider = id
                val intent = Intent(context, CookieLoginActivity::class.java).apply {
                    putExtra(CookieLoginActivity.EXTRA_START_URL, url)
                    putExtra(CookieLoginActivity.EXTRA_COOKIE_HOSTS, hosts.toTypedArray())
                    putExtra(CookieLoginActivity.EXTRA_TITLE, title)
                    // 桌面 UA：Trae 国际版按 UA 拦「地区不开放」，桌面模式同网能进
                    putExtra(CookieLoginActivity.EXTRA_DESKTOP_UA, true)
                    if (cookieName.isNotEmpty()) {
                        putExtra(CookieLoginActivity.EXTRA_SUCCESS_COOKIE, cookieName)
                    }
                    if (settleUrls.isNotEmpty()) {
                        putExtra(CookieLoginActivity.EXTRA_SETTLE_URLS, settleUrls.toTypedArray())
                    }
                    // 页内换票：cookie 是 HttpOnly 的、导不出来，只能在页面里换
                    if (tokenEndpoint.isNotEmpty()) {
                        putExtra(CookieLoginActivity.EXTRA_TOKEN_ENDPOINT, tokenEndpoint)
                        putExtra(CookieLoginActivity.EXTRA_TOKEN_PAGE_URL, tokenPageUrl)
                    }
                }
                cookieLogin.launch(intent)
            },
            onDeviceLogin = { id, region -> viewModel.beginDeviceLogin(id, region) },
            onSmsLogin = { id, phone, code, ccode, onResult ->
                if (code.isEmpty()) {
                    viewModel.sendSmsCode(id, phone, ccode, onResult)
                } else {
                    viewModel.smsLogin(id, phone, code, ccode, onResult)
                }
            },
            onImport = { id, raw -> viewModel.importCredentials(id, raw) },
            onAddCustomAccount = { id, nickname, apiKey, models -> viewModel.addCustomAccount(id, nickname, apiKey, models) },
            onRenameAccount = { id, uid, nickname -> viewModel.renameAccount(id, uid, nickname) },
            accountApiKeyOf = { id, uid -> viewModel.accountApiKey(id, uid) },
            customModelsOf = { id -> viewModel.customModels(id) },
            customBaseUrlOf = { id -> viewModel.customBaseUrl(id) },
            onFetchCustomModels = { baseUrl, apiKey, onResult ->
                viewModel.fetchCustomModels(baseUrl, apiKey, onResult)
            },
            onRefreshCredits = { id, uid -> viewModel.refreshCredits(id, uid) },
            onAction = { id, uid, action, payload -> viewModel.performAction(id, uid, action, payload) },
            onInPagePoints = { id, uid ->
                pendingInPagePoints = id
                inPagePoints.launch(Intent(context, DumatePointsActivity::class.java))
            },
            onToggleAccount = { id, uid, enabled -> viewModel.setAccountEnabled(id, uid, enabled) },
            onRemoveAccount = { id, uid -> viewModel.removeAccount(id, uid) },
            onMoveAccountPriority = { id, uid, up -> viewModel.moveAccountPriority(id, uid, up) },
            onUpdateSecret = { id, uid, secret, notice ->
                viewModel.updateAccountSecret(id, uid, secret)
                viewModel.notice(notice)
            },
            deviceIdOf = { id, uid -> viewModel.deviceIdOf(id, uid) },
            regionOf = { id, uid -> viewModel.accountRegion(id, uid) },
            providerOptionOf = { id, key, fallback -> viewModel.providerOption(id, key, fallback) },
            onUpdateProviderOption = { id, key, value -> viewModel.updateProviderOption(id, key, value) },
            creditPacksOf = { id, uid ->
                creditPacks.value["$id/$uid"].orEmpty()
            },
            onLoadCreditPacks = { id, uid -> viewModel.loadCreditPacks(id, uid) },
            taskListOf = { id, uid -> taskLists.value["$id/$uid"] },
            onLoadTaskList = { id, uid -> viewModel.loadTaskList(id, uid) },
            pointsBillsOf = { id, uid -> pointsBills.value["$id/$uid"] },
            onLoadPointsBills = { id, uid -> viewModel.loadPointsBills(id, uid) },
            onRegenerateDeviceId = { id, uid -> viewModel.regenerateDeviceId(id, uid) },
            onSetDeviceId = { id, uid, value -> viewModel.setDeviceId(id, uid, value) },
            onNotice = { viewModel.notice(it) },
        )
    }

    PageScaffold(
        title = info?.displayName ?: providerId,
        subtitle = "${accounts.size} 个账号 · 可用 ${accounts.count { it.usable }}",
        leading = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(6.dp),
            )
        },
        actions = {
            if (providerId.startsWith(ProviderUiRegistry.CUSTOM_PREFIX)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "编辑供应商",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onEditCustom(providerId) }
                        .padding(6.dp),
                )
            }
        },
    ) {
        // LoginEntry 自带卡片，这里不再包一层，避免双层卡片
        ui.LoginEntry(accounts, actions)

        // 账号列表由供应商组件自管（如 WorkBuddy 按国内外分区）时，不再重复渲染通用账号卡
        if (accounts.isNotEmpty() && !ui.managesOwnAccounts) {
            SectionCard(enterIndex = 0) {
                SectionLabel("账号")
                PagedAccountList(accounts, keyOf = { it.uid }) { pageIndex, account ->
                    AccountRow(account, ui, actions, index = pageIndex)
                }
                OutlineActionButton("刷新额度") { actions.onRefreshCredits(providerId, null) }
            }
        }

        ui.SettingsSection(viewModel.providerSettings(providerId)) { updated ->
            viewModel.updateProviderSettings(providerId, updated)
        }
    }
}

@Composable
private fun AccountRow(
    account: dev.aigw.core.pool.AccountStatus,
    ui: dev.aigw.app.ui.providers.ProviderUi,
    actions: ProviderUiActions,
    index: Int,
) {
    AccountCardShell(
        account = account,
        providerId = account.providerId,
        actions = actions,
        modifier = Modifier.fillMaxWidth().staggeredAppear(index),
        extra = { ui.AccountExtra(account, actions) },
        footer = {
            var confirmRemove by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LinkText("删除账号") { confirmRemove = true }
            }
            if (confirmRemove) {
                ConfirmDestructiveDialog(
                    title = "删除账号「${account.nickname.ifEmpty { account.uid }}」？",
                    body = "本地保存的凭证会被清除，之后要重新登录才能再用这个账号。此操作不可撤销。",
                    onConfirm = { actions.onRemoveAccount(account.providerId, account.uid) },
                    onDismiss = { confirmRemove = false },
                )
            }
        },
    )
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick),
    )
}
