package dev.aigw.core.gateway

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 一次在途请求的对外快照。
 *
 * 只含路由级信息（客户端模型名、命中供应商与账号、开始时间、阶段），**不含任何上游协议细节**：
 * 它是给状态展示（通知、界面）用的，供应商差异不得从这里泄漏出去。
 */
data class ActiveRequest(
    val id: String,
    val clientModel: String,
    val providerId: String,
    val uid: String,
    val nickname: String,
    val startedAtMillis: Long,
    val phase: Phase,
) {
    /** 处理阶段；只影响展示文案（如「等待上游应答」/「输出中」），不参与任何判断。 */
    enum class Phase {
        /** 已选中账号、正在向上游发起请求。 */
        CONNECTING,

        /** 上游已回应（拿到响应头），正在读第一个数据块。 */
        UPSTREAM_ANSWERED,

        /** 正在向前端转发内容。 */
        STREAMING,
    }
}

/**
 * 在途请求表：网关当前正在处理的请求（含整个流式转发阶段）。
 *
 * 与 [dev.aigw.core.failover.ConcurrencyGate] 的分工：闸门管「能不能发」（限流、排队），
 * 这里管「谁正在发」。**本类不参与任何调度决策**，只用于状态展示——所以它读不到数据也不影响转发。
 *
 * 线程安全：请求在各自的网关线程上增删；[addListener] 的监听器可能在任意线程被回调，
 * 实现方需自行切到自己的线程（如主线程、Handler）。
 */
class ActiveRequests(private val nowMillis: () -> Long = System::currentTimeMillis) {

    private val entries = ConcurrentHashMap<String, ActiveRequest>()
    private val listeners = CopyOnWriteArrayList<(List<ActiveRequest>) -> Unit>()
    private val seq = AtomicLong(0)

    /** 登记一次请求开始，返回它的 id（后续 [update]/[finish] 用它）。 */
    fun begin(clientModel: String, providerId: String, uid: String, nickname: String): String {
        val id = "req-${seq.incrementAndGet()}"
        entries[id] = ActiveRequest(
            id = id,
            clientModel = clientModel,
            providerId = providerId,
            uid = uid,
            nickname = nickname,
            startedAtMillis = nowMillis(),
            phase = ActiveRequest.Phase.CONNECTING,
        )
        notifyChanged()
        return id
    }

    /** 更新阶段；id 不存在（已结束）时静默忽略。 */
    fun update(id: String, phase: ActiveRequest.Phase) {
        val current = entries[id] ?: return
        if (current.phase == phase) return
        entries[id] = current.copy(phase = phase)
        notifyChanged()
    }

    /** 请求结束（无论成败）时移除；重复调用无副作用。 */
    fun finish(id: String) {
        if (entries.remove(id) != null) notifyChanged()
    }

    /** 当前在途快照，按开始时间升序（先发起的在前）。 */
    fun snapshot(): List<ActiveRequest> = entries.values.sortedBy { it.startedAtMillis }

    fun count(): Int = entries.size

    fun addListener(listener: (List<ActiveRequest>) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (List<ActiveRequest>) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        if (listeners.isEmpty()) return
        val current = snapshot()
        for (listener in listeners) {
            // 监听器抛异常不能影响请求转发：状态展示坏了不该拖垮网关
            runCatching { listener(current) }
        }
    }
}
