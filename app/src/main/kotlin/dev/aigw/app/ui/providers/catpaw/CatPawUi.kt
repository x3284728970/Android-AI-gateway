package dev.aigw.app.ui.providers.catpaw

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pets
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
 * 妙手 CatPaw 界面组件。
 *
 * 登录走官方网页通道的设备流：点按钮 → App 内打开妙手登录页 → 登录完成后
 * 服务端扫码轮询（poll-token）自动拿 token，**不需要本机监听回调端口**，
 * 也不受浏览器私有网络检查影响。用户只需要「登录」，不需要做什么额外操作。
 *
 * 另外留粘贴入口：抓包/浏览器里已有的 `X-Passport-Token` 可以直接粘。
 */
object CatPawUi : ProviderUi {

    override val id: String = "catpaw"

    override val managesOwnAccounts: Boolean get() = true

    override fun description() = "美团妙手 CatPaw，网页登录，支持 LongCat / GLM / DeepSeek / Kimi 等模型（仅文本对话）"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Pets,
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
            SectionLabel("登录 · 妙手网页")
            Text(
                text = "点下面按钮打开妙手登录页，登录成功后回到 App，账号会自动出现。" +
                    "妙手只支持纯文本对话：工具调用、图片、文件都发送不了，带这些内容的请求会被明确拒绝。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { actions.onDeviceLogin(id, "") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开妙手登录页")
            }

            OutlineActionButton(
                text = if (expanded) "收起粘贴导入" else "粘贴 token 导入",
            ) { expanded = !expanded }
            if (expanded) {
                Text(
                    text = "支持裸 token（抓包里的 X-Passport-Token），或本应用的账号 JSON（含 token 字段）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                    label = { Text("X-Passport-Token") },
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