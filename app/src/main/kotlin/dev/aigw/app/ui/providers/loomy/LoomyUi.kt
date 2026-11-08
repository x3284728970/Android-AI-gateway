package dev.aigw.app.ui.providers.loomy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import dev.aigw.app.ui.ChoiceChip
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.ProviderTask
import dev.aigw.core.provider.loomy.LoomyProvider

object LoomyUi : ProviderUi {

    override val id: String = "loomy"

    /** 验证码重发间隔（秒）。 */
    private const val RESEND_SECONDS = 60

    override fun description(): String = "手机号短信登录，走免费积分通道"

    @Composable
    override fun Icon(modifier: Modifier) {
        MaterialIcon(
            imageVector = Icons.Filled.Sms,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        // 国家码：讯飞按它路由短信通道，写死 86 会让非大陆号码收不到验证码
        var ccode by rememberSaveable { mutableStateOf("86") }
        var phone by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var countdown by remember { mutableStateOf(0) }

        // 发送后 60 秒内不允许重发：上游有频率限制，连点只会被拒
        LaunchedEffect(countdown) {
            if (countdown > 0) {
                delay(1_000)
                countdown -= 1
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(enterIndex = 0) {
                SectionLabel("登录")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = ccode,
                        onValueChange = { value ->
                            // 国家码只留数字，最多 4 位（最长的是 +1 区外的三位码）
                            ccode = value.filter(Char::isDigit).take(4)
                        },
                        modifier = Modifier.width(88.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("国家码") },
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it.filter(Char::isDigit).take(15) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        label = { Text("手机号") },
                    )
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("验证码") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            // 先开倒计时，按钮立刻有反馈；发送失败再恢复可点
                            countdown = RESEND_SECONDS
                            actions.onSmsLogin(id, phone.trim(), "", ccode.trim()) { error ->
                                if (error.isNotEmpty()) countdown = 0
                            }
                        },
                        enabled = phone.isNotBlank() && countdown == 0,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (countdown > 0) "${countdown} 秒后重发" else "发送验证码")
                    }
                    Button(
                        onClick = { actions.onSmsLogin(id, phone.trim(), code.trim(), ccode.trim()) { } },
                        enabled = phone.isNotBlank() && code.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("登录")
                    }
                }
            }
        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        // 预填一个可用邀请码：邀请码是双向的（双方都得积分），预填省得用户自己去复制
        var invite by remember(status.uid) { mutableStateOf(DEFAULT_INVITE_CODE) }
        var code by remember(status.uid) { mutableStateOf("") }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status.detail.isNotEmpty()) {
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = invite,
                onValueChange = { invite = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("兑换邀请码") },
            )
            Text(
                text = "已预填邀请码，也可以换成别人的",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    val value = invite.trim()
                    actions.onAction(
                        id,
                        status.uid,
                        LoomyProvider.ACTION_REDEEM_INVITE,
                        JsonObject().apply { addProperty("inviteCode", value) },
                    )
                    // 重置为默认值而不是清空：下次打开仍是预填状态
                    invite = DEFAULT_INVITE_CODE
                },
                enabled = invite.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("兑换邀请码")
            }

            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("兑换码") },
            )
            Button(
                onClick = {
                    val value = code.trim()
                    actions.onAction(
                        id,
                        status.uid,
                        LoomyProvider.ACTION_REDEEM_CODE,
                        JsonObject().apply { addProperty("code", value) },
                    )
                    code = ""
                },
                enabled = code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("兑换码")
            }

            OutlineActionButton("一键完成新手任务") {
                actions.onAction(id, status.uid, ACTION_TASKS, JsonObject())
            }

            TaskListSection(status, actions)
        }
    }

    /**
     * 新手任务明细：拉取该账号的任务清单，逐条展示完成状态与积分。
     *
     * 「一键完成新手任务」是盲跑的（提交前只知道哪些没完成），这里把清单摊开：
     * 哪些做完了、哪些还没做、每条值多少积分，一眼看得见。单条的「完成」只提交这一条。
     */
    @Composable
    private fun TaskListSection(status: AccountStatus, actions: ProviderUiActions) {
        val view = actions.taskListOf(id, status.uid)
        // 进入账号块时若无缓存则拉一次；换账号重拉
        LaunchedEffect(status.uid) {
            if (view == null) actions.onLoadTaskList(id, status.uid)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("新手任务")
            MaterialIcon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "刷新任务列表",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { actions.onLoadTaskList(id, status.uid) }
                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
            )
        }

        when {
            view == null -> Text(
                text = "正在加载任务列表…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            view.error.isNotEmpty() && view.tasks.isEmpty() -> Text(
                text = "加载任务失败：${view.error}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            view.tasks.isEmpty() -> Text(
                text = "上游没有返回任务",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val done = view.tasks.count { it.completed }
                val earned = view.tasks.filter { it.completed }.sumOf { it.rewardCredit }
                Text(
                    text = "共 ${view.tasks.size} 个 · 已完成 $done 个 · 已得 $earned 积分",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                view.tasks.forEach { task -> TaskRow(task, status.uid, actions) }
            }
        }
    }

    @Composable
    private fun TaskRow(task: ProviderTask, uid: String, actions: ProviderUiActions) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (task.completed) "已完成" else "未完成",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (task.completed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.rewardCredit > 0) {
                Text(
                    text = "奖励 ${task.rewardCredit} 积分",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!task.completed) {
                // 只提交这一条；任务对应真实动作，服务端会校验，失败时上游原话会透出来
                ChoiceChip("完成", false) {
                    actions.onAction(
                        id,
                        uid,
                        LoomyProvider.ACTION_COMPLETE_TASK,
                        JsonObject().apply { addProperty("key", task.code) },
                    )
                }
            }
        }
    }
}

/**
 * 默认预填的 Loomy 邀请码。
 *
 * 邀请码是双向的：填了双方都得积分。这里预填的只是一个默认值，用户随时可以换成别人的。
 * 想清空默认值就把这里置成空串。
 */
private const val DEFAULT_INVITE_CODE = "R3HFVJ"
