package dev.aigw.app.ui.providers.cline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
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
 * Cline 界面组件。
 *
 * 两条路：WorkOS 设备授权（推荐，点按钮在浏览器里确认即可）与 Cline API Key
 * （在 app.cline.bot 生成后粘贴）。设备授权拿到的令牌有刷新链，
 * 过期前引擎会自动换新，用户不用管。
 */
object ClineUi : ProviderUi {

    override val id: String = "cline"

    override val managesOwnAccounts: Boolean get() = true

    override fun description() = "Cline（cline.bot），WorkOS 设备授权或 API Key，含免费档与 Cline Pass 模型"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Terminal,
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
            SectionLabel("登录 · Cline")
            Text(
                text = "点下面按钮会打开 Cline 授权页，确认后回到 App，账号会自动出现。" +
                    "带 cline-free/ 前缀的模型免费，cline-pass/ 需要 Cline Pass 订阅。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { actions.onDeviceLogin(id, "") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开 Cline 授权页")
            }

            OutlineActionButton(
                text = if (expanded) "收起 API Key 导入" else "粘贴 API Key 导入",
            ) { expanded = !expanded }
            if (expanded) {
                Text(
                    text = "在 app.cline.bot 的账号设置里生成 API Key 后粘贴进来；也可以粘贴本应用的账号 JSON。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                    label = { Text("Cline API Key") },
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