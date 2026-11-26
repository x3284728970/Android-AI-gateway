package dev.aigw.app.ui

import androidx.compose.foundation.background
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
 */
@Composable
fun RateSyncCard(status: RateSyncStatus, syncing: Boolean, onSync: () -> Unit) {
    val stamp = remember0(status.lastSyncAt)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = "倍率数据",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = rateSummary(status, stamp),
                style = MaterialTheme.typography.labelSmall,
                color = if (status.lastError.isNotEmpty()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (syncing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            OutlineActionButton("更新") { onSync() }
        }
        // 圆形指示点：有数据时绿色，纯装饰；失败时红色提示，不带文字也能一眼看到
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

private fun remember0(value: Long): String =
    if (value <= 0L) "" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(value))

private fun rateSummary(status: RateSyncStatus, stamp: String): String = when {
    status.lastError.isNotEmpty() -> "上次更新失败：${status.lastError}"
    status.neverSynced -> "尚未同步，当前显示各供应商内置倍率"
    status.entryCount == 0 -> "远程表为空，当前显示各供应商内置倍率"
    stamp.isNotEmpty() -> "更新于 $stamp · ${status.entryCount} 条${status.lastSource.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()}"
    else -> "${status.entryCount} 条"
}