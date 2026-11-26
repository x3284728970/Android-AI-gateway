package dev.aigw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.core.rate.RateSyncStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 倍率数据同步条。
 *
 * 倍率（模型计费倍数）不是上游接口的字段，也不值得为每次调价发版，
 * 所以放在仓库的 `config/model-rates.json` 里，App 每 12 小时自动拉一次。
 * 这里把「数据什么时候更新的、拉到了几条、上次为什么失败」摊开给用户看——
 * 一个看不见更新时间的倍率数字，用户没法判断该不该信。
 *
 * 注意：右侧的「更新」不能用 [OutlineActionButton]，那个组件内部写死 `fillMaxWidth()`，
 * 放进 Row 会吃光整行宽度，把左边的文字列压成 0 宽（表现为一个字符一行竖排）。
 * 这里用按内容收窄的描边小按钮。
 */
@Composable
fun RateSyncCard(status: RateSyncStatus, syncing: Boolean, onSync: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "倍率数据",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = rateSummary(status),
                style = MaterialTheme.typography.labelSmall,
                color = if (status.lastError.isNotEmpty()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            // 数据来源只在成功同步、且远程表真有更新日期时展示，避免一句话塞太满
            val staleness = stalenessNote(status.tableUpdated)
            if (status.lastError.isEmpty() && status.tableUpdated.isNotEmpty()) {
                Text(
                    text = "数据表更新日期 ${status.tableUpdated}${staleness?.let { " · $it" }.orEmpty()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (staleness != null) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    },
                )
            }
        }
        if (syncing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                text = "更新",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
                    .clickable(onClick = onSync)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        // 状态点：有数据时淡色，失败时红色，不带文字也能一眼扫到
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    if (status.lastError.isNotEmpty()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    },
                ),
        )
    }
}

private fun rateSummary(status: RateSyncStatus): String = when {
    status.lastError.isNotEmpty() -> "上次更新失败：${status.lastError}"
    status.neverSynced -> "尚未同步，当前显示各供应商内置倍率"
    status.entryCount == 0 -> "远程表为空，当前显示各供应商内置倍率"
    else -> buildString {
        append("更新于 ${formatStamp(status.lastSyncAt)}")
        append(" · ${status.entryCount} 条")
        status.lastSource.takeIf { it.isNotEmpty() }?.let { append(" · $it") }
    }
}

/**
 * 远程表的日期超过 45 天就提醒「可能已过时」。
 *
 * 自动更新只能保证「拿到的是仓库里最新的」，保证不了「仓库里的是最新的」——
 * 平台调价是外部事件，没人盯着就不会更新。把这件事说出来，比让用户以为数字永远准确要好。
 */
private fun stalenessNote(tableUpdated: String): String? {
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(tableUpdated)?.time
    }.getOrNull() ?: return null
    val days = (System.currentTimeMillis() - parsed) / (24 * 60 * 60 * 1000L)
    return if (days > STALE_AFTER_DAYS) "已 ${days} 天未核对，可能已过时" else null
}

private const val STALE_AFTER_DAYS = 45L

private fun formatStamp(millis: Long): String =
    if (millis <= 0L) "未知" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))