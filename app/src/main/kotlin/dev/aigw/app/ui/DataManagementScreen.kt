package dev.aigw.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.usage.StorageAudit

/**
 * 数据管理：存储与清理。
 *
 * 调用记录会保存每次请求的输入/输出/思考链，是应用体积的主要来源，所以这里做两件事：
 * 让用户能改保留天数（服务运行时按此每日自动清理），以及提供几个手动清理动作。
 */
@Composable
fun DataManagementScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    var showDetail by remember { mutableStateOf(false) }
    val retention = state.settings.logRetentionDays
    val records = state.storage?.recordsBytes ?: 0L

    // 存储占用不挂在全局 refresh 里（要扫全量存储 + 遍历缓存目录，代价高），
    // 所以进入本页时才单独算一次；清理动作后也会由 refreshStorage 更新。
    LaunchedEffect(Unit) { viewModel.refreshStorage() }

    PageScaffold(
        title = "数据管理",
        subtitle = "存储与清理",
        leading = { BackButton(onBack) },
    ) {
        // 与 AppearanceScreen 对齐：二级页用 EditorialGroupHeader 编号
        EditorialGroupHeader("01", "使用记录 · RETENTION")
        SectionCard(enterIndex = 0) {
            Text(
                text = "记录会保存每次请求的输入/输出/思考链，是应用数据体积的主要来源；服务运行时按保留天数每日自动清理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RetentionField(retention) { viewModel.setLogRetentionDays(it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = "当前占用 ${StorageAudit.formatSize(records)} · ${state.storedCalls} 条记录",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "自动保留 $retention 天，服务运行时会每日自动清理",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        EditorialGroupHeader("02", "清理动作 · PURGE")
        SectionCard(enterIndex = 1) {
            // 展开后按钮文案要变，否则第二次点像没反应
            OutlineActionButton(if (showDetail) "收起存储明细" else "查看存储占用明细") { showDetail = !showDetail }
            if (showDetail) {
                StorageDetail(state, viewModel)
            }
            OutlineActionButton("截断超长记录内容") { viewModel.truncateLongRecords() }
            OutlineActionButton("清理过期记录") { viewModel.purgeExpiredRecords() }
        }
    }
}


/** 存储占用明细：按前缀归类，占用从大到小。 */
@Composable
private fun StorageDetail(state: AppUiState, viewModel: AppViewModel) {
    val report = state.storage
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (report == null) {
            EmptyHint("暂无法统计", "存储扫描要解密全部键值，代价较高；点下面重试。")
            // 空态要给下一步：原来只有一句「稍后再试」，用户不知道点哪
            OutlineActionButton("重新统计") { viewModel.refreshStorage() }
        } else {
            report.entries.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${entry.detail} · ${entry.items} 项",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = StorageAudit.formatSize(entry.bytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = "统计在应用内完成，不联网；「账号与凭证」含 token，请勿外传。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
