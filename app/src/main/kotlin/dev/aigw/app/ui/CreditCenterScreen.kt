package dev.aigw.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.core.provider.ProviderCapability

/**
 * 额度中心：列出有账号的供应商，点进去看该供应商各账号的额度包明细。
 */
@Composable
fun CreditCenterScreen(state: AppUiState, onOpen: (String) -> Unit, onBack: () -> Unit) {
    PageScaffold(
        title = "额度中心",
        subtitle = "按供应商查看额度包",
        leading = { BackButton(onBack) },
    ) {
        val providers = state.providers.filter { state.accountsOf(it.id).isNotEmpty() }
        if (providers.isEmpty()) {
            SectionCard(enterIndex = 0) {
                EmptyHint("还没有账号", "先去「供应商」页添加账号。")
            }
            return@PageScaffold
        }

        for ((index, info) in providers.withIndex()) {
            val accounts = state.accountsOf(info.id)
            val known = accounts.filter { it.creditsKnown }
            SectionCard(
                enterIndex = index,
                pressable = true,
                modifier = Modifier.clickable { onOpen(info.id) },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = info.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${accounts.size} 个账号 · 额度合计 " +
                                if (known.isEmpty()) "—" else known.sumOf { it.credits }.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 可用账号占比：纯数字看不出「这个供应商到底健不健康」，加一条细条一眼可扫
                        ThinRatioBar(
                            fraction = if (accounts.isEmpty()) null else accounts.count { it.usable }.toFloat() / accounts.size,
                            color = if (accounts.any { !it.usable }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/**
 * 某个供应商各账号的额度明细：额度包 + （有账本的供应商）逐笔积分流水。
 *
 * 小浣熊这类供应商没有额度包概念，额度就是积分余额，逐笔明细全在流水里；
 * 只列额度包会让它在额度中心里看起来「没有明细」。
 */
@Composable
fun ProviderCreditsScreen(
    providerId: String,
    state: AppUiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val accounts = state.accountsOf(providerId)
    val displayName = state.providerOf(providerId)?.displayName ?: providerId
    val hasLedger = state.providerOf(providerId)?.capabilities?.contains(ProviderCapability.POINTS_BILLS) == true

    // 进页面拉一次；账号变化时重拉
    LaunchedEffect(providerId, accounts.map { it.uid }) {
        accounts.forEach {
            viewModel.loadCreditPacks(providerId, it.uid)
            if (hasLedger) viewModel.loadPointsBills(providerId, it.uid)
        }
    }

    PageScaffold(
        title = displayName,
        subtitle = "额度包明细",
        leading = { BackButton(onBack) },
    ) {
        if (accounts.isEmpty()) {
            SectionCard(enterIndex = 1) {
                EmptyHint("还没有账号", "先去该供应商页添加账号。")
            }
            return@PageScaffold
        }

        for (account in accounts) {
            val packs = state.creditPacks["$providerId/${account.uid}"].orEmpty()
            val bills = state.pointsBills["$providerId/${account.uid}"]
            SectionCard(enterIndex = 2) {
                SectionLabel(account.nickname.ifEmpty { account.uid })
                Text(
                    text = "余额 ${if (account.creditsKnown) account.credits.toString() else "—"} · ${accountStatusText(account)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (packs.isEmpty()) {
                    Text(
                        text = when {
                            // 账本型供应商（如小浣熊）没有额度包，额度就是积分余额，明细在下方流水里
                            hasLedger -> "该供应商没有额度包，积分流水见下"
                            !account.creditsKnown && account.detail.isNotEmpty() -> account.detail
                            else -> "没有额度包明细"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    for (pack in packs) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (pack.group.isEmpty()) pack.name else "${pack.name}（${pack.group}）",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = buildString {
                                    if (pack.remain >= 0) {
                                        append("剩余 ")
                                        append(pack.remain)
                                    } else {
                                        append("剩余 —")
                                    }
                                    if (pack.limit > 0) append(" / ").append(pack.limit)
                                    if (pack.used > 0) append(" · 已用 ").append(pack.used)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (hasLedger) {
                    SectionLabel("积分明细")
                    when {
                        bills == null -> PointsBillsHint("正在加载积分明细…")
                        bills.error.isNotEmpty() -> PointsBillsHint(
                            text = "加载失败：${bills.error}",
                            error = true,
                            onRetry = { viewModel.loadPointsBills(providerId, account.uid) },
                        )
                        bills.bills.isEmpty() -> PointsBillsHint("暂无积分明细")
                        else -> for (bill in bills.bills) PointsBillRow(bill)
                    }
                }
                OutlineActionButton("刷新") {
                    viewModel.refreshCredits(providerId, account.uid)
                    viewModel.loadCreditPacks(providerId, account.uid)
                    if (hasLedger) viewModel.loadPointsBills(providerId, account.uid)
                }
            }
        }
    }
}
