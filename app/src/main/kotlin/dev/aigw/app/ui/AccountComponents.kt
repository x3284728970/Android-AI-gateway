package dev.aigw.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus

/**
 * 账号标题列：上层为昵称（昵称为空时回退到 UID），下层为账号状态文案（可用 / 已停用 / 凭证失效）。
 */
@Composable
fun AccountTitleColumn(
    account: AccountStatus,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = account.nickname.ifEmpty { account.uid },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = accountStatusText(account),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 账号卡片头部标题行：左侧账号信息（[AccountTitleColumn]），右侧挂接操作控件（默认开关或展开图标）。
 */
@Composable
fun AccountTitleRow(
    account: AccountStatus,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountTitleColumn(
            account = account,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * 账号优先级箭头（↑/↓）：绑 [ProviderUiActions.onMoveAccountPriority]。
 *
 * 排在前面的先用，同级仍按余额（见 AccountPool.pick）。被手动提到默认位置之上的账号额外标一枚「优先」，
 * 否则用户看不出哪些号是手动提上来的。
 *
 * 判据用 [AccountStatus.raised] 而不是 priority：↑/↓ 是交换两个账号的优先级，
 * 退回原顺序时高值会跑到另一个账号身上，直接看 priority 会让标记带着跑。
 */
@Composable
fun AccountPriorityArrows(
    account: AccountStatus,
    providerId: String,
    actions: ProviderUiActions,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (account.raised) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = "优先",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Icon(
            Icons.Filled.KeyboardArrowUp,
            contentDescription = "提高优先级",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { actions.onMoveAccountPriority(providerId, account.uid, true) }
                .padding(2.dp)
                .size(20.dp),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = "降低优先级",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { actions.onMoveAccountPriority(providerId, account.uid, false) }
                .padding(2.dp)
                .size(20.dp),
        )
    }
}

/**
 * 账号启用/停用开关：绑定 [ProviderUiActions.onToggleAccount]。
 */
@Composable
fun AccountEnableSwitch(
    account: AccountStatus,
    providerId: String,
    actions: ProviderUiActions,
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = account.enabled,
        onCheckedChange = { actions.onToggleAccount(providerId, account.uid, it) },
        modifier = modifier,
    )
}

/**
 * 账号删除按钮行（ChoiceChip 胶囊样式）：Trae、CodeBuddy 与 Custom 共享该外观。
 */
@Composable
fun AccountDeleteChipRow(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf(false) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ChoiceChip(
            text = "删除账号",
            selected = false,
            // 删除不可恢复：这一步只开确认框，真正的删除在对话框的确认按钮里
            onClick = { confirm = true },
        )
    }
    if (confirm) {
        ConfirmDestructiveDialog(
            title = "删除这个账号？",
            body = "本地保存的凭证会被清除，之后要重新登录才能再用。此操作不可撤销。",
            onConfirm = onRemove,
            onDismiss = { confirm = false },
        )
    }
}

/**
 * 统一账号卡片外壳：
 * 头部标题行（昵称 + 状态 + [trailing] 开关）+ [extra] 供应商专属信息区 + [footer] 删除行。
 *
 * - [ProviderDetailScreen]、[TraeUi]、[CodeBuddyUi] 的账号项均基于此外壳实现。
 * - [trailing] 默认为 [AccountEnableSwitch]，可传入展开图标等自定义内容；为 null 时使用默认开关。
 * - [extra] 对应各供应商自有的专属信息（额度行、detail 文案、签到/任务入口等）。
 * - [footer] 默认为 [AccountDeleteChipRow]；传 null 时隐藏；ProviderDetailScreen 可传自定义 LinkText 文本。
 */
@Composable
fun AccountCardShell(
    account: AccountStatus,
    providerId: String,
    actions: ProviderUiActions,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    extra: @Composable () -> Unit = {},
    footer: (@Composable () -> Unit)? = {
        AccountDeleteChipRow(onRemove = { actions.onRemoveAccount(providerId, account.uid) })
    },
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AccountTitleRow(
            account = account,
            trailing = trailing ?: {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccountPriorityArrows(
                        account = account,
                        providerId = providerId,
                        actions = actions,
                    )
                    AccountEnableSwitch(
                        account = account,
                        providerId = providerId,
                        actions = actions,
                    )
                }
            },
        )
        extra()
        footer?.invoke()
    }
}

/**
 * 优雅的微胶囊分段滑块（高弹性物理动效）。
 *
 * 长条一分为二，指示块在两半之间滑动指示选中侧。
 * 用于 Trae、CodeBuddy 等多区域供应商切换「国内 / 国外」站点与登录目标。
 */
@Composable
fun RegionSelector(
    isGlobal: Boolean,
    onSelectGlobal: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    labels: List<String> = listOf("国内", "国外"),
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = modifier.clip(CircleShape),
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            labels.forEachIndexed { index, label ->
                val selected = (index == 1) == isGlobal
                val animProgress by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 600f),
                    label = "regionIndicator$index",
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = animProgress))
                        .clickable { onSelectGlobal(index == 1) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
