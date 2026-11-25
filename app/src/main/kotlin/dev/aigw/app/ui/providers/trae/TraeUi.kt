package dev.aigw.app.ui.providers.trae

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.aigw.app.ui.AccountCardShell
import dev.aigw.app.ui.ChoiceChip
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.PagedAccountList
import dev.aigw.app.ui.RegionSelector
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.accountStatusText
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.trae.TraeRegion

object TraeUi : ProviderUi {

    override val id: String = "trae"

    // 国内 / 国际是两套独立账号：账号列表由本组件按滑块区域过滤后自行渲染
    override val managesOwnAccounts: Boolean get() = true

    override fun description(): String = "网页登录导入，走 SOLO 免费通道（国内/国际）"

    @Composable
    override fun Icon(modifier: Modifier) {
        MaterialIcon(
            imageVector = Icons.Filled.SmartToy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var expanded by remember { mutableStateOf(false) }
        var raw by remember { mutableStateOf("") }
        // 滑块状态持久化在供应商设置里（region=cn/global）；引擎开登录页时按它选 host
        var global by rememberSaveable {
            mutableStateOf(actions.providerOptionOf(id, "region", "cn") == "global")
        }
        val region = if (global) TraeRegion.INTL.id else TraeRegion.CN.id

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(enterIndex = 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MaterialIcon(
                            Icons.Filled.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        SectionLabel("服务区域")
                    }
                    RegionSelector(
                        isGlobal = global,
                        onSelectGlobal = { isGlobal ->
                            global = isGlobal
                            actions.onUpdateProviderOption(id, "region", if (isGlobal) "global" else "cn")
                        },
                    )
                }
                if (global) {
                    // 国际版不给网页登录入口。实测（2026-10-28）：网页登录拿到的只有
                    // passport 会话 cookie / 会话 token，CheckLogin 认它、GetUserToken 和
                    // /api/ide 全部 401 code 1001——只能读账号，不能对话。
                    // 社区项目（wangqi233/trae2api、autumnsentiment）也都不走这条路，
                    // 一律读桌面客户端凭证或粘贴 Cloud-IDE-JWT。留个进不去的按钮只会浪费用户时间。
                    SectionLabel("登录 · 国际版 trae.ai")
                    Text(
                        text = "国际版网页登录拿到的凭证没有模型调用权限，所以不提供网页登录。" +
                            "请按下面任一方式取凭证后粘贴导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GlobalCredentialGuide()
                } else {
                    SectionLabel("登录 · 国内版 trae.cn")
                    Button(
                        onClick = { actions.onWebLogin(id, region) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("在浏览器中打开 Trae 登录页") }
                }
                OutlineActionButton(
                    text = when {
                        expanded -> "收起粘贴导入"
                        global -> "粘贴 IDE JWT / 凭证 JSON"
                        else -> "粘贴凭证 JSON 导入"
                    },
                ) { expanded = !expanded }
                if (expanded) {
                    OutlinedTextField(
                        value = raw,
                        onValueChange = { raw = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                        label = {
                            Text(if (global) "Cloud-IDE-JWT 或凭证 JSON" else "回调链接 / 凭证 JSON")
                        },
                    )
                    Button(
                        onClick = {
                            actions.onImport(id, raw.trim())
                            raw = ""
                        },
                        enabled = raw.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("导入")
                    }
                }
            }
            if (accounts.isNotEmpty()) {
                AccountRegionSection(global, accounts, actions)
            }
        }
    }

    /**
     * 国际版凭证获取教程。
     *
     * 只讲两条实测能走通的路：网页会话 cookie（sessionid/sid_tt）在 IDE 接口上
     * 一律 401 code 1001，所以不再提供网页登录入口。
     */
    @Composable
    private fun GlobalCredentialGuide() {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "① 浏览器取 IDE JWT（最快）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "电脑浏览器打开 www.trae.ai 登录 → F12 → Network（钩 Fetch/XHR）→ " +
                    "在网页里发起一次 AI 对话 → 找到请求头 " +
                    "Authorization: Cloud-IDE-JWT eyJ… → 复制整串，粘贴到下面导入。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "② 桌面客户端凭证（最稳，能长期用）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "电脑装 Trae 国际版并登录，取 globalStorage/storage.json 里的凭证" +
                    "（含 refreshToken）粘贴导入。带 refreshToken 可自动续期；" +
                    "网页 token 约 17 小时过期，过期要重新取。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /** 按当前滑块区域过滤账号：国内版只列 trae.cn 账号，国际版只列 trae.ai 账号。 */
    @Composable
    private fun AccountRegionSection(global: Boolean, accounts: List<AccountStatus>, actions: ProviderUiActions) {
        val current = if (global) "global" else "cn"
        val scoped = remember(accounts, current) { accounts.filter { actions.regionOf(id, it.uid) == current } }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (scoped.isEmpty()) {
                SectionCard(enterIndex = 1) {
                    SectionLabel("账号")
                    Text(
                        text = if (global) "还没有国际版账号" else "还没有国内版账号，先登录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 空态必须给出下一步：原来只有一行灰字，用户得自己去别的卡片里找登录按钮
                    if (global) {
                        // 国际版没有可用的网页登录，指向上面的粘贴导入
                        Text(
                            text = "国际版请在卡片上方「粘贴 IDE JWT / 凭证 JSON」导入。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlineActionButton("去登录国内版") {
                            actions.onWebLogin(id, current)
                        }
                    }
                }
                return
            }
            SectionCard(enterIndex = 1) {
                SectionLabel("账号 · ${scoped.size} 个")
                PagedAccountList(scoped, keyOf = { it.uid }) { _, account ->
                    AccountBlock(account, actions)
                }
                OutlineActionButton("刷新额度") { actions.onRefreshCredits(id, null) }
            }
        }
    }

    @Composable
    private fun AccountBlock(status: AccountStatus, actions: ProviderUiActions) {
        AccountCardShell(
            account = status,
            providerId = id,
            actions = actions,
            extra = { AccountExtra(status, actions) },
        )
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        val deviceId = actions.deviceIdOf(id, status.uid)
        var manual by remember(status.uid) { mutableStateOf("") }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status.detail.isNotEmpty()) {
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 国际版没有签到制度，只给国内账号渲染签到入口（设备指纹校验也只对国内有效）
            if (actions.regionOf(id, status.uid) != "global") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("签到", false) {
                        actions.onAction(id, status.uid, ACTION_CHECKIN, JsonObject())
                    }
                }
            }
            Text("设备指纹", style = MaterialTheme.typography.titleSmall)
            Text(
                text = deviceId.ifEmpty { "—" },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "设备指纹用于签到校验，非数字格式会被上游拒绝。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("重新生成", false) { actions.onRegenerateDeviceId(id, status.uid) }
            }
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("手动填写设备指纹") },
            )
            Button(
                onClick = {
                    actions.onSetDeviceId(id, status.uid, manual.trim())
                    manual = ""
                },
                enabled = manual.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
        }
    }
}
