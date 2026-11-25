package dev.aigw.app.ui.providers.copilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.accountStatusText
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus

/**
 * GitHub Copilot 界面组件。
 *
 * 登录只有一条主路径：**GitHub 设备码**（点按钮 → 浏览器输 6 位码 → 自动轮询完成），
 * 不需要在手机上开回调端口，也不需要填 client_secret。
 *
 * 另外留一个粘贴入口：已经有 GitHub token 的用户（比如从别的工具导出的）
 * 可以直接粘，省一次浏览器往返。
 */
object CopilotUi : ProviderUi {

    override val id: String = "github-copilot"

    /** 账号卡由本组件自己渲染，页面骨架不要再包一层。 */
    override val managesOwnAccounts: Boolean get() = true

    override fun description() = "GitHub Copilot 订阅，走 GitHub 设备码登录，[OI] 兼容端点"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Code,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var expanded by remember { mutableStateOf(false) }
        var raw by remember { mutableStateOf("") }

        SectionCard {
            SectionLabel("登录 · GitHub 设备码")
            Text(
                text = "点下面的按钮会打开 GitHub 授权页，输入页面上显示的 6 位码即可。" +
                    "完成后回到 App，账号会自动出现（需要该 GitHub 账号已开通 Copilot）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { actions.onDeviceLogin(id, "") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开 GitHub 授权页")
            }

            OutlineActionButton(
                text = if (expanded) "收起粘贴导入" else "粘贴 GitHub token 导入",
            ) { expanded = !expanded }
            if (expanded) {
                Text(
                    text = "支持两种格式：GitHub 用户 token（ghu_/ghp_ 开头），" +
                        "或本应用的账号 JSON（含 githubToken 字段）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                    label = { Text("GitHub token") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            actions.onImport(id, raw.trim())
                            raw = ""
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("导入")
                    }
                }
            }

            if (accounts.isNotEmpty()) {
                Text(
                    text = accounts.joinToString("、") { accountStatusText(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
