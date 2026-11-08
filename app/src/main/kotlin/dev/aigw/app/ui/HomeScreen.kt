package dev.aigw.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aigw.app.ui.theme.DeepInk
import dev.aigw.app.ui.theme.LocalAppColors
import dev.aigw.app.ui.theme.WarningAmber
import dev.aigw.core.provider.RoutedModel
import dev.aigw.core.usage.CallRecord
import dev.aigw.core.usage.CallStatus
import dev.aigw.core.usage.UsageStats
import dev.aigw.core.util.startOfDay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 趋势图展示天数。 */
private const val TREND_DAYS = 14

/** 首页：数据仪表盘（网关控制台 + 消耗趋势 + 遥测指标 + 快捷操作 + 配额池）。 */
@Composable
fun HomeScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    openTaskCenter: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val gold = LocalAppColors.current.goldStart
    val goldText = LocalAppColors.current.goldText
    val lanUrl = state.lanUrls.firstOrNull()

    // Android 13+ 通知权限被拒时前台服务照常跑，但常驻通知会被系统静默隐藏，
    // 用户看起来就是「服务没起来」；启动前先补上授权请求。
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.startGateway() }

    fun startWithNotificationPermission() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startGateway()
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    PageScaffold(
        // 与桌面名（strings.xml）保持一致；「AI GATEWAY」作为版式装饰进副标题
        title = "网关助手",
        subtitle = "AI GATEWAY · 本地 OpenAI 兼容网关",
        actions = { StatusChip(state.running) },
    ) {
        ConsoleCard(
            state = state,
            lanUrl = lanUrl,
            onStart = { startWithNotificationPermission() },
            onStop = { viewModel.stopGateway() },
            onRefreshCredits = { viewModel.refreshAllCredits() },
            onCopy = { text, message ->
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            },
        )

        LiveRoutingTopologyCard(state, Modifier.staggeredAppear(1))

        TelemetryGrid(state, Modifier.staggeredAppear(2))

        // 批量动作进行中/结束：首页也要看得到进度与失败原因（签到按钮就在本页）
        BatchProgressHost(state.batchProgress)
        BatchOutcomeHost(state.batchOutcome) { viewModel.dismissBatchOutcome() }

        TrendCard(state)

        SectionCard(modifier = Modifier.staggeredAppear(4)) {
            SectionHeader("快捷操作 · ACTIONS")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.onSurface)
                        .clickable { viewModel.checkinAll() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(gold))
                        Text(
                            "批量签到",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.surface,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .border(1.dp, gold, MaterialTheme.shapes.medium)
                        .clickable { openTaskCenter() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "任务中心",
                        style = MaterialTheme.typography.labelLarge,
                        color = goldText,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        AntigravityQuotaSection(state, viewModel, baseIndex = 5)
    }
}

/** 控制台卡：状态行 + 接入地址 + 启停与刷额度。 */
@Composable
private fun ConsoleCard(
    state: AppUiState,
    lanUrl: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefreshCredits: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val gold = LocalAppColors.current.goldStart
    SectionCard(modifier = Modifier.staggeredAppear(0)) {
        SectionHeader("网关状态 · CORE CONSOLE")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(state.running)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (state.running) "SERVICE ACTIVE" else "SERVICE STOPPED",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Light,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                // 大字吃掉剩余宽度，右侧端口列按自身内容定宽；否则大字会把端口挤成 0 宽。
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "LISTEN PORT",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = ":${state.port}",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        val localUrl = state.localUrl.ifEmpty { "http://127.0.0.1:${state.port}/v1" }
        AddressRow("本机", localUrl) { onCopy(localUrl, "已复制本机地址") }
        AddressRow(
            label = "局域网",
            value = lanUrl ?: "未连接 Wi-Fi（局域网不可用）",
            onCopy = lanUrl?.let { url -> { onCopy(url, "已复制局域网地址") } },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.onSurface)
                    .clickable { if (state.running) onStop() else onStart() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(gold))
                    Text(
                        text = if (state.running) "停止网关服务" else "启动网关服务",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.surface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.medium)
                    .clickable(onClick = onRefreshCredits)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "刷新全部额度",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- 拓扑结构数据模型
private data class TopologyProviderBranch(
    val providerId: String,
    val displayName: String,
    val usableAccounts: Int,
    val totalAccounts: Int,
    val weight: Float,
    val hasActiveRequest: Boolean,
    val activeCount: Int,
    val colorIndex: Int,
)

/**
 * 实时调度拓扑卡（数据驱动的供应商分发视图）：
 * - 中枢节点：网关（显示 :port，运行中呼吸发光，停止时静止置灰）
 * - 分支节点：真实有账号的供应商，显示供应商名 + 可用账号数
 * - 分支权重（线宽/流动/亮度）：按最近 50 次请求里该供应商的请求数占比；无请求时退化为可用账号数占比
 * - 在途请求：所在供应商高亮金色并流动光斑，没有在途时不流动、不高亮
 * - 多分支折叠：超过 4 个供应商时前 3 个展示，其余合并为「+N 其它」
 * - 小屏（360dp）：自适应单列/紧凑布局
 */
@Composable
private fun LiveRoutingTopologyCard(state: AppUiState, modifier: Modifier = Modifier) {
    val gold = LocalAppColors.current.goldStart
    val goldEnd = LocalAppColors.current.goldEnd
    val palette = LocalAppColors.current.providerPalette
    val isRunning = state.running

    val recentCalls = remember(state.calls) { state.calls.take(50) }
    val activeRequests = state.activeRequests
    val hasActive = isRunning && activeRequests.isNotEmpty()

    // 统计最近 50 次请求中各供应商的请求数
    val callCountsByProvider = remember(recentCalls) {
        recentCalls.groupBy { it.providerId.lowercase() }.mapValues { it.value.size }
    }
    val totalRecentCalls = remember(callCountsByProvider) { callCountsByProvider.values.sum() }

    // 活跃在途请求按供应商分组统计
    val activeCountsByProvider = remember(activeRequests) {
        activeRequests.groupBy { it.providerId.lowercase() }.mapValues { it.value.size }
    }

    // 真实有账号的供应商
    val branches = remember(state.accounts, state.providers, callCountsByProvider, activeCountsByProvider, totalRecentCalls) {
        val groupedAccounts = state.accounts.groupBy { it.providerId.lowercase() }
        if (groupedAccounts.isEmpty()) {
            emptyList()
        } else {
            val totalUsableOverall = state.accounts.count { it.usable }.coerceAtLeast(1)
            val sortedProviderIds = groupedAccounts.keys.sortedByDescending { pid ->
                val callCount = callCountsByProvider[pid] ?: 0
                val usable = groupedAccounts[pid]?.count { it.usable } ?: 0
                // 优先按请求数排序，相同时按可用账号数
                (callCount.toLong() shl 16) + usable
            }

            sortedProviderIds.mapIndexed { index, pid ->
                val accs = groupedAccounts[pid] ?: emptyList()
                val usable = accs.count { it.usable }
                val provInfo = state.providers.firstOrNull { it.id.equals(pid, ignoreCase = true) }
                val dName = provInfo?.displayName?.ifEmpty { pid.uppercase() } ?: pid.uppercase()

                val weight = if (totalRecentCalls > 0) {
                    ((callCountsByProvider[pid] ?: 0).toFloat() / totalRecentCalls).coerceIn(0.08f, 1f)
                } else {
                    (usable.toFloat() / totalUsableOverall).coerceIn(0.08f, 1f)
                }

                val activeCount = activeCountsByProvider[pid] ?: 0

                TopologyProviderBranch(
                    providerId = pid,
                    displayName = dName,
                    usableAccounts = usable,
                    totalAccounts = accs.size,
                    weight = weight,
                    hasActiveRequest = activeCount > 0,
                    activeCount = activeCount,
                    colorIndex = index,
                )
            }
        }
    }

    val basisCaption = if (totalRecentCalls > 0) {
        "按近 50 次请求分布"
    } else if (state.accounts.isNotEmpty()) {
        "暂无请求，按可用账号分布"
    } else {
        "暂无配置账号"
    }

    // 在途流光：曲线上的光斑进度 0f→1f，只在绘制阶段读取，不逐帧重组。
    // 1.6s 一趟比原来的 1s 慢一些，长曲线上的光斑才不会显得在跳。
    val flowPhase: State<Float> = rememberInfiniteTransition(label = "topologyFlow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "topologyFlowPhase",
    )

    SectionCard(modifier = modifier) {
        // 顶部小节头 + 呼吸指示灯 + TODAY 大数字
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.breathingDot(
                    color = if (isRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    size = 7.dp,
                    breathing = isRunning,
                    haloSize = 15.dp,
                ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "实时调度拓扑 · LIVE TOPOLOGY",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = basisCaption,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }

            // 右侧在途/今日计数
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (activeRequests.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(gold.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "${activeRequests.size} 在途",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = goldEnd,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "TODAY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    AnimatedContent(
                        targetState = state.today.requests,
                        transitionSpec = {
                            slideInVertically(
                                animationSpec = tween(Motion.TAB_MILLIS, easing = FastOutSlowInEasing),
                            ) { it / 2 } + fadeIn() togetherWith
                                slideOutVertically(
                                    animationSpec = tween(Motion.TAB_MILLIS, easing = FastOutSlowInEasing),
                                ) { -it / 2 } + fadeOut()
                        },
                        label = "todayRequests",
                    ) { reqCount ->
                        Text(
                            text = if (reqCount > 0) formatCount(reqCount) else "—",
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = if (reqCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // 拓扑主体分发视图：
        // 中枢网关节点位于左侧，各供应商分支位于右侧
        if (branches.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无可用供应商账号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // 分支折叠逻辑：最多显示 3 个主要分支，超过则把剩余合并为「+N」
            val maxVisibleBranches = 3
            val displayBranches = if (branches.size > 4) branches.take(maxVisibleBranches) else branches
            val remainingCount = if (branches.size > 4) branches.size - maxVisibleBranches else 0

            // 连线要按各行的真实位置扇出，先把容器、中枢与每一行在 root 坐标系里的矩形量出来
            var containerRect by remember { mutableStateOf<Rect?>(null) }
            var hubRect by remember { mutableStateOf<Rect?>(null) }
            val rowRects = remember { mutableStateMapOf<String, Rect>() }
            var otherRect by remember { mutableStateOf<Rect?>(null) }
            val branchById = remember(branches) { branches.associateBy { it.providerId } }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { containerRect = it.boundsInRoot() },
            ) {
                // 连线画在最底层：曲线从中枢右缘扇出、终点落在各节点行左缘，端点被卡片盖住
                TopologyWiresCanvas(
                    container = containerRect,
                    hub = hubRect,
                    rows = rowRects,
                    branches = branchById,
                    other = otherRect,
                    isRunning = isRunning,
                    gold = gold,
                    goldEnd = goldEnd,
                    phase = flowPhase,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左侧中枢节点：网关 Hub，垂直居中于整列分支
                    GatewayHubNode(
                        isRunning = isRunning,
                        port = state.port,
                        gold = gold,
                        goldEnd = goldEnd,
                        hasActive = hasActive,
                        modifier = Modifier.onGloballyPositioned { hubRect = it.boundsInRoot() },
                    )

                    // 右侧分发分支列：左侧留出连线走廊，节点不再自己画线
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 44.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        displayBranches.forEach { branch ->
                            val branchColor = palette[branch.colorIndex % palette.size]
                            ProviderBranchRow(
                                branch = branch,
                                branchColor = branchColor,
                                gold = gold,
                                isRunning = isRunning,
                                modifier = Modifier.onGloballyPositioned {
                                    rowRects[branch.providerId] = it.boundsInRoot()
                                },
                            )
                        }

                        if (remainingCount > 0) {
                            val otherUsable = branches.drop(maxVisibleBranches).sumOf { it.usableAccounts }
                            Box(
                                modifier = Modifier
                                    .onGloballyPositioned { otherRect = it.boundsInRoot() }
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = "+$remainingCount 其它 (${otherUsable}可用)",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 2.dp),
        )

        // 块 2：最近一个请求的路由流水
        RecentCallRoutingFlow(state.calls.firstOrNull())

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 2.dp),
        )

        // 块 3 与 块 4：上下文容量监控 + 明细列表（按模型聚合近 50 次请求）
        ContextCapacityAndModelBreakdown(state)
    }
}

/**
 * 拓扑单个分支行：供应商节点胶囊。
 *
 * 连线不再由行内自己画——那正是从前「线悬空、没接到网关」的来源：每行的线只从自己左缘起笔，
 * 而网关卡片只有一行高，除正中一行外的线左侧都对着空白。现在曲线统一由 [TopologyWiresCanvas]
 * 从网关右缘扇出。
 */
@Composable
private fun ProviderBranchRow(
    branch: TopologyProviderBranch,
    branchColor: Color,
    gold: Color,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val isBranchActive = branch.hasActiveRequest && isRunning
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .drawBehind {
                if (isBranchActive) {
                    drawRoundRect(
                        color = gold.copy(alpha = 0.15f),
                        size = size,
                        cornerRadius = CornerRadius(8.dp.toPx()),
                    )
                }
            }
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (isBranchActive) 1.5.dp else 1.dp,
                color = if (isBranchActive) gold else branchColor.copy(alpha = 0.45f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(branchColor))
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = branch.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isBranchActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        maxLines = 1,
                    )
                    if (branch.activeCount > 0) {
                        Text(
                            text = "(${branch.activeCount}在途)",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                            color = gold,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    text = "${branch.usableAccounts}/${branch.totalAccounts} 可用",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 拓扑连线层：从中枢节点右缘向每个分支行的左缘扇出贝塞尔曲线。
 *
 * - 线宽与透明度都取分发权重（0.08–1.0）：旧实现只把权重映射到 1.7–4px 且透明度一致，肉眼分不出。
 * - 有在途请求的分支沿曲线跑一个光斑，进度用 sin 包络在两端淡入淡出，避免端点处突然出现。
 * - 进度以 [State] 传入、在绘制阶段读取，因此每帧只重绘，不重组拓扑卡。
 */
@Composable
private fun BoxScope.TopologyWiresCanvas(
    container: Rect?,
    hub: Rect?,
    rows: Map<String, Rect>,
    branches: Map<String, TopologyProviderBranch>,
    other: Rect?,
    isRunning: Boolean,
    gold: Color,
    goldEnd: Color,
    phase: State<Float>,
) {
    val box = container ?: return
    val hubBox = hub ?: return
    val idleColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = Modifier.matchParentSize()) {
        val startX = hubBox.right - box.left - 2.dp.toPx()
        val startY = hubBox.center.y - box.top
        val progress = phase.value

        val targets = ArrayList<Triple<Rect, Float, Boolean>>()
        rows.forEach { (pid, rect) ->
            val branch = branches[pid] ?: return@forEach
            targets.add(Triple(rect, branch.weight, branch.hasActiveRequest && isRunning))
        }
        other?.let { targets.add(Triple(it, 0.08f, false)) }

        targets.forEach { (rect, weight, active) ->
            val endX = rect.left - box.left
            val endY = rect.center.y - box.top
            val dx = endX - startX
            val path = Path().apply {
                moveTo(startX, startY)
                cubicTo(startX + dx * 0.45f, startY, endX - dx * 0.45f, endY, endX, endY)
            }
            val stroke = (1.1f + weight * 2.4f).dp.toPx()
            drawPath(
                path = path,
                color = (if (active) gold else idleColor).copy(alpha = 0.5f + weight * 0.5f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (active) {
                val measure = PathMeasure().apply { setPath(path, false) }
                if (measure.length > 0f) {
                    val envelope = kotlin.math.sin(progress * kotlin.math.PI).toFloat()
                    val center = measure.getPosition(progress * measure.length)
                    drawCircle(
                        color = goldEnd.copy(alpha = 0.28f * envelope),
                        radius = stroke + 4.dp.toPx(),
                        center = center,
                    )
                    drawCircle(
                        color = goldEnd.copy(alpha = envelope),
                        radius = stroke / 2f + 2.dp.toPx(),
                        center = center,
                    )
                }
            }
        }
    }
}

/**
 * 网关中枢节点：运行中时金色描边 + 微发光晕，停止时置灰。
 */
@Composable
private fun GatewayHubNode(
    isRunning: Boolean,
    port: Int,
    gold: Color,
    goldEnd: Color,
    hasActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .drawBehind {
                if (isRunning) {
                    val haloAlpha = if (hasActive) 0.22f else 0.10f
                    drawRoundRect(
                        color = gold.copy(alpha = haloAlpha),
                        size = size,
                        cornerRadius = CornerRadius(10.dp.toPx()),
                    )
                }
            }
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (hasActive) 1.5.dp else 1.dp,
                color = if (isRunning) gold else MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.medium,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "AI-GATEWAY",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = if (isRunning) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = ":$port",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.SemiBold,
                color = if (isRunning) goldEnd else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 块 2：最近一个请求的路由流水。
 * 三行流水（小圆点 + 文案）：
 * 1. 客户端请求 <model>
 * 2. 路由到 <PROVIDER·大写> · <accountNickname 或 accountUid>
 * 3. 结果 <成功/失败/中断> · HTTP <httpStatus> · 耗时 <x.x s> · tokens <in>→<out>
 */
@Composable
private fun RecentCallRoutingFlow(call: CallRecord?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "最近路由记录 · LATEST DISPATCH",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (call == null) {
            Text(
                text = "暂无请求记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            return@Column
        }

        val dotNeutral = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        val resultColor = when (call.status) {
            CallStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
            CallStatus.FAILED -> MaterialTheme.colorScheme.error
            CallStatus.ABORTED -> WarningAmber
        }
        val resultText = when (call.status) {
            CallStatus.SUCCESS -> "成功"
            CallStatus.FAILED -> "失败"
            CallStatus.ABORTED -> "客户端中断"
        }

        val modelName = call.model.ifEmpty { "未记录" }
        val accountDisplay = call.accountNickname.ifEmpty { call.accountUid.ifEmpty { "默认账号" } }
        val durationText = formatDuration(call.durationMillis)
        val promptTokensStr = formatTokens(call.promptTokens)
        val completionTokensStr = formatTokens(call.completionTokens)

        // 行 1：客户端请求
        RoutingFlowRow(
            dotColor = dotNeutral,
            label = "客户端请求",
            detail = modelName,
        )

        // 行 2：路由去向
        RoutingFlowRow(
            dotColor = LocalAppColors.current.goldStart,
            label = "路由到",
            detail = "${call.providerId.uppercase()} · $accountDisplay",
        )

        // 行 3：结果与耗时
        RoutingFlowRow(
            dotColor = resultColor,
            label = "结果",
            detail = "$resultText · HTTP ${call.httpStatus} · 耗时 $durationText",
        )

        // 行 4：开销 tokens
        val tokenDetail = buildString {
            append("输入 $promptTokensStr · 输出 $completionTokensStr")
            if (call.cachedTokens > 0) {
                append(" · 缓存 ${formatTokens(call.cachedTokens)}")
            }
        }
        RoutingFlowRow(
            dotColor = dotNeutral,
            label = "Token",
            detail = tokenDetail,
        )
    }
}

@Composable
private fun RoutingFlowRow(dotColor: Color, label: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 单个模型的近 50 次请求聚合统计。 */
private data class ModelTokenAggregation(
    val model: String,
    val requests: Int,
    val promptTokens: Long,
)

/**
 * 在 state.models 里匹配 contextWindow：
 * 1. 用 providerId + model (完全匹配 ProviderModel.id 或 name，并支持剥离 routePrefix)；
 * 2. 退化为任意 provider 下匹配模型名；
 * 3. 0 或未匹配返回 0L (表示未知)。
 */
private fun resolveContextWindow(
    providerId: String,
    modelName: String,
    models: List<RoutedModel>,
): Long {
    if (modelName.isEmpty() || models.isEmpty()) return 0L
    val cleanName = modelName.substringAfterLast('/')

    // 1. 精确匹配同 providerId
    val matched = models.firstOrNull { routed ->
        routed.providerId.equals(providerId, ignoreCase = true) &&
            (routed.model.id.equals(modelName, ignoreCase = true) ||
                routed.model.id.equals(cleanName, ignoreCase = true) ||
                routed.model.name.equals(cleanName, ignoreCase = true) ||
                routed.fullId.equals(modelName, ignoreCase = true))
    }
    if (matched != null && matched.model.contextWindow > 0L) {
        return matched.model.contextWindow
    }

    // 2. 任意 provider 跨域同名匹配
    val fallback = models.firstOrNull { routed ->
        routed.model.id.equals(cleanName, ignoreCase = true) ||
            routed.model.name.equals(cleanName, ignoreCase = true)
    }
    if (fallback != null && fallback.model.contextWindow > 0L) {
        return fallback.model.contextWindow
    }
    return 0L
}

/**
 * 块 3 与 块 4：上下文容量监控 + 明细列表（近 50 次请求按模型聚合）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextCapacityAndModelBreakdown(state: AppUiState) {
    val recentCalls = remember(state.calls) { state.calls.take(50) }
    val latestCall = recentCalls.firstOrNull()

    // 块 3：大数字计算
    val promptTokens = latestCall?.promptTokens ?: 0L
    val cachedTokens = latestCall?.cachedTokens ?: 0L
    val contextWindow = remember(latestCall, state.models) {
        if (latestCall != null) {
            resolveContextWindow(latestCall.providerId, latestCall.model, state.models)
        } else {
            0L
        }
    }

    // 近 50 次聚合列表（按 promptTokens 倒序）
    val aggregated = remember(recentCalls) {
        recentCalls
            .groupBy { it.model.ifEmpty { "未命名模型" } }
            .map { (model, calls) ->
                ModelTokenAggregation(
                    model = model,
                    requests = calls.size,
                    promptTokens = calls.sumOf { it.promptTokens },
                )
            }
            .sortedByDescending { it.promptTokens }
    }
    val totalPromptTokens = remember(aggregated) { aggregated.sumOf { it.promptTokens } }

    val palette = LocalAppColors.current.providerPalette
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 标题说明
        Text(
            text = "上下文容量与分布 · CONTEXT & MODELS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 块 3：左右双指标
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左列：输入 tokens，下方整合成一行小字说明上限与剩余
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (latestCall != null) formatTokens(promptTokens) else "—",
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val remainingText = if (contextWindow > 0L && contextWindow >= promptTokens) {
                    "剩余 ${formatTokens(contextWindow - promptTokens)}"
                } else if (contextWindow > 0L) {
                    "超出上限"
                } else {
                    "剩余未知"
                }
                val limitText = if (contextWindow > 0L) "上限 ${formatTokens(contextWindow)}" else "上限未知"
                Text(
                    text = "$limitText · $remainingText",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 右列：缓存命中率
            Column(
                horizontalAlignment = Alignment.End,
            ) {
                val hitRateText = if (promptTokens > 0L) {
                    String.format(Locale.US, "%.1f%%", (cachedTokens.toDouble() / promptTokens) * 100.0)
                } else {
                    "—"
                }
                Text(
                    text = hitRateText,
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = if (cachedTokens > 0L) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "缓存命中",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 分段条标题：严格口径
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "近 50 次请求的输入 token 按模型",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (totalPromptTokens > 0L) formatTokens(totalPromptTokens) else "暂无 token 数据",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 分段进度条与图例：无数据不画条；只有 1 个模型时省去分段条与图例（直接看下方明细，消除重复冗余）；≥2 个模型时才画条和图例
        if (totalPromptTokens > 0L && aggregated.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                aggregated.forEachIndexed { index, item ->
                    val fraction = item.promptTokens.toFloat() / totalPromptTokens
                    val animatedFraction by animateFloatAsState(
                        targetValue = fraction,
                        animationSpec = tween(Motion.BAR_FILL_MILLIS, easing = FastOutSlowInEasing),
                        label = "modelTokenBar$index",
                    )
                    val color = palette[index % palette.size]
                    Box(
                        modifier = Modifier
                            .weight(animatedFraction.coerceAtLeast(0.001f))
                            .fillMaxSize()
                            .background(color),
                    )
                }
            }

            // 图例一览（FlowRow 自适应换行，每项完整展示）
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                aggregated.take(4).forEachIndexed { index, item ->
                    val color = palette[index % palette.size]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                        Text(
                            text = "${item.model.substringAfterLast('/')} (${formatTokens(item.promptTokens)})",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (aggregated.size > 4) {
                    Text(
                        text = "+${aggregated.size - 4}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (totalPromptTokens <= 0L) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }

        // 块 4：明细列表（可折叠）
        if (aggregated.isNotEmpty()) {
            val visibleCount = if (expanded) aggregated.size else 5
            val displayItems = aggregated.take(visibleCount)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                displayItems.forEachIndexed { index, item ->
                    val color = palette[index % palette.size]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                        Text(
                            text = item.model,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${item.requests} 次",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatTokens(item.promptTokens),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // 展开/收起按钮
                if (aggregated.size > 5) {
                    Text(
                        text = if (expanded) "收起" else "显示全部 ${aggregated.size} 项",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAppColors.current.goldText,
                        modifier = Modifier
                            .clickable { expanded = !expanded }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** 状态圆点：运行中绿色带浅光环，停止灰色。 */
@Composable
private fun StatusDot(running: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        if (running) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)))
        }
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
        )
    }
}

/** 一行接入地址：浅灰底 8dp 圆角 + 等宽地址 + 黑色复制小块。 */
@Composable
private fun AddressRow(label: String, value: String, onCopy: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onCopy != null) {
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.onSurface)
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    "复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.surface,
                )
            }
        }
    }
}

/** 消耗趋势卡：近 14 天输入/输出 tokens 折线。 */
@Composable
private fun TrendCard(state: AppUiState) {
    val trend = remember(state.daily) { state.daily.takeLast(TREND_DAYS) }
    val drawable = remember(trend) {
        trend.size >= 2 && trend.any { it.promptTokens > 0 || it.completionTokens > 0 }
    }
    val gold = LocalAppColors.current.goldStart
    SectionCard(modifier = Modifier.staggeredAppear(3)) {
        SectionHeader("消耗趋势 · TREND", action = if (trend.isEmpty()) null else "近 ${trend.size} 天")
        if (drawable) {
            TrendChart(trend, startOfDay(System.currentTimeMillis()))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LegendDot(gold, "输入")
                LegendDot(MaterialTheme.colorScheme.onSurface, "输出")
            }
        } else {
            Text(
                text = "暂无足够历史，累积几天后显示趋势",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrendChart(daily: List<UsageStats>, todayStart: Long) {
    val gold = LocalAppColors.current.goldStart
    // 原来硬编码 #0D0D0D，深色卡片（#141414）上对比 1.05:1，整条曲线消失
    val dark = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val n = daily.size
    val maxValue = remember(daily) {
        daily.maxOf { maxOf(it.promptTokens, it.completionTokens) }.coerceAtLeast(1L)
    }
    val labels = remember(daily, todayStart) {
        List(n) { index -> dayLabel(todayStart - (n - 1 - index) * 86_400_000L) }
    }

    // 复用同一支画笔：绘制阶段只改对齐方式，不再每次重绘都新建
    val axisPaint = remember { android.graphics.Paint() }
    val inFractions = remember(daily, maxValue) {
        FloatArray(n) { daily[it].promptTokens.toFloat() / maxValue }
    }
    val outFractions = remember(daily, maxValue) {
        FloatArray(n) { daily[it].completionTokens.toFloat() / maxValue }
    }
    val xs = remember(n) { FloatArray(n) }
    val inY = remember(n) { FloatArray(n) }
    val outY = remember(n) { FloatArray(n) }
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val gutterLeft = 40.dp.toPx()
        val gutterBottom = 22.dp.toPx()
        val padTop = 6.dp.toPx()
        val padRight = 6.dp.toPx()
        val chartWidth = (size.width - gutterLeft - padRight).coerceAtLeast(1f)
        val chartHeight = (size.height - gutterBottom - padTop).coerceAtLeast(1f)
        val originY = padTop + chartHeight
        val stepX = chartWidth / (n - 1)
        for (i in 0 until n) {
            xs[i] = gutterLeft + i * stepX
            inY[i] = originY - inFractions[i] * chartHeight
            outY[i] = originY - outFractions[i] * chartHeight
        }

        drawLine(gridColor, Offset(gutterLeft, originY), Offset(gutterLeft + chartWidth, originY), strokeWidth = 1f)
        drawSeries(xs, inY, originY, gold)
        drawSeries(xs, outY, originY, dark)

        drawIntoCanvas { canvas ->
            axisPaint.isAntiAlias = true
            axisPaint.color = labelColor.toArgb()
            axisPaint.textSize = 9.sp.toPx()
            axisPaint.textAlign = android.graphics.Paint.Align.RIGHT
            canvas.nativeCanvas.drawText(formatAxis(maxValue), gutterLeft - 6.dp.toPx(), originY, axisPaint)
            axisPaint.textAlign = android.graphics.Paint.Align.CENTER
            val labelY = originY + 14.dp.toPx()
            for (index in listOf(0, (n - 1) / 2, n - 1).distinct()) {
                canvas.nativeCanvas.drawText(labels[index], xs[index], labelY, axisPaint)
            }
        }
    }
}

/** 一条序列：先画半透明填充，再画主线。 */
private fun DrawScope.drawSeries(xs: FloatArray, ys: FloatArray, baseline: Float, color: Color) {
    if (xs.size < 2) return
    val fillPath = Path().apply {
        moveTo(xs[0], baseline)
        for (i in xs.indices) lineTo(xs[i], ys[i])
        lineTo(xs.last(), baseline)
        close()
    }
    drawPath(fillPath, color = color.copy(alpha = 0.12f))
    val linePath = Path().apply {
        moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) lineTo(xs[i], ys[i])
    }
    drawPath(
        linePath,
        color = color,
        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** 2×3 遥测指标网格。 */
@Composable
private fun TelemetryGrid(state: AppUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProvidersTile(state, Modifier.weight(1f))
            ReadinessTile(state, Modifier.weight(1f))
            ModelsTile(state, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RequestsTile(state, Modifier.weight(1f))
            TodayTokensTile(state, Modifier.weight(1f))
            CreditsTile(state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .height(112.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        content()
    }
}

/** 大号细体数字 + 小号后缀。 */
@Composable
private fun BigNumber(value: String, suffix: String? = null, color: Color = Color.Unspecified) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Light,
            color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (suffix != null) {
            Text(
                text = suffix,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

/** 指标卡底部灰色小备注。 */
@Composable
private fun TileNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ProvidersTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "供应商 · PROVIDERS") {
        BigNumber(state.providers.size.toString())
        TileNote(
            if (state.providers.isEmpty()) "暂无供应商" else state.providers.joinToString(" · ") { it.displayName },
        )
    }
}

@Composable
private fun ReadinessTile(state: AppUiState, modifier: Modifier) {
    val total = state.pool.total
    val usable = state.pool.usable
    val percent = if (total > 0) (usable.toFloat() / total * 100).toInt() else 0
    Tile(modifier, "账号就绪 · READINESS") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BigNumber(usable.toString(), suffix = "/ $total")
                TileNote("池内可用账号")
            }
            ReadinessRing(percent, if (total > 0) usable.toFloat() / total else 0f)
        }
    }
}

/** 环形进度：金色弧 + 灰底环，中间百分比。 */
@Composable
private fun ReadinessRing(percent: Int, fraction: Float) {
    val ringColor = LocalAppColors.current.goldStart
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val strokeWidth = 4.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(strokeWidth),
            )
            if (fraction > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelsTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "聚合模型 · MODELS") {
        BigNumber(state.models.size.toString())
        TileNote(
            when {
                state.modelsError.isNotEmpty() -> state.modelsError
                state.models.isEmpty() -> "尚未拉取模型目录"
                else -> "各供应商模型目录合计"
            },
        )
    }
}

@Composable
private fun RequestsTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "今日请求 · REQUESTS") {
        BigNumber(formatCompact(state.today.requests))
        // 增长需要真实昨日数据：daily 末尾是今天，倒数第二个才是昨天。
        val yesterday = if (state.daily.size >= 2) state.daily[state.daily.size - 2] else null
        if (yesterday != null && yesterday.requests > 0) {
            val delta = (state.today.requests - yesterday.requests).toDouble() / yesterday.requests * 100
            Text(
                text = (if (delta >= 0) "↑ " else "↓ ") + String.format(Locale.US, "%.1f%%", kotlin.math.abs(delta)) + " 较昨日",
                style = MaterialTheme.typography.labelSmall,
                color = if (delta >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TodayTokensTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "今日 TOKENS · TOKENS") {
        BigNumber(formatTokens(state.today.totalTokens))
        TileNote("输入 ${formatTokens(state.today.promptTokens)} · 输出 ${formatTokens(state.today.completionTokens)}")
    }
}

@Composable
private fun CreditsTile(state: AppUiState, modifier: Modifier) {
    val known = state.pool.creditsKnown > 0
    val goldText = LocalAppColors.current.goldText
    Tile(modifier, "已知额度 · TOTAL CREDITS") {
        // 三分之一宽的格子里放不下 189,304，用紧凑写法；精确值留在备注里
        BigNumber(
            value = if (known) formatCompact(state.pool.totalCredits) else "—",
            color = if (known) goldText else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TileNote(if (known) "共 ${formatCount(state.pool.totalCredits)} · ${state.pool.creditsKnown} 账号" else "尚未同步额度")
    }
}

/** Antigravity 的模型组配额窗口（每组 × 每窗口一条），首页与账号详情共用。 */
@Composable
private fun AntigravityQuotaSection(state: AppUiState, viewModel: AppViewModel, baseIndex: Int) {
    val accounts = state.accountsOf("antigravity")
    if (accounts.isEmpty()) return

    // 进首页拉一次；账号集合变化时重拉
    LaunchedEffect(accounts.map { it.uid }) {
        accounts.forEach { viewModel.loadCreditPacks("antigravity", it.uid) }
    }

    SectionCard(modifier = Modifier.staggeredAppear(baseIndex)) {
        SectionHeader("上游配额池 · ANTIGRAVITY QUOTA")
        for (account in accounts) {
            val packs = state.creditPacks["antigravity/${account.uid}"].orEmpty()
            if (accounts.size > 1) {
                Text(
                    text = account.nickname.ifEmpty { account.uid },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (packs.isEmpty()) {
                Text(
                    text = if (account.detail.isNotEmpty()) account.detail else "尚未拉取配额",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (pack in packs) {
                        QuotaPackBar(pack)
                    }
                }
            }
        }
    }
}

/** 坐标轴上限缩写：1.2k / 3.4M。 */
private fun formatAxis(value: Long): String = when {
    value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private val DAY_LABEL_FORMAT = SimpleDateFormat("M/d", Locale.US)

private fun dayLabel(millis: Long): String = DAY_LABEL_FORMAT.format(Date(millis))
