package fansirsqi.xposed.sesame.hook.keepalive

import android.annotation.SuppressLint
import android.content.Context
import fansirsqi.xposed.sesame.hook.CoroutineScheduler
import fansirsqi.xposed.sesame.hook.keepalive.SchedulerMonitor
import fansirsqi.xposed.sesame.hook.keepalive.WorkManagerScheduler
import fansirsqi.xposed.sesame.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/**
 * 智能调度器管理器
 *
 * 功能：
 * 1. 自动统计执行延迟
 * 2. 动态调整延迟补偿
 * 3. 智能切换调度器（协程 ↔ WorkManager）
 *
 * 策略：
 * - 平均延迟 < 1 分钟：减少补偿，保持协程
 * - 平均延迟 1-3 分钟：增加补偿，保持协程
 * - 平均延迟 3-5 分钟：最大补偿，准备切换
 * - 平均延迟 > 5 分钟：切换到 WorkManager
 */
object SmartSchedulerManager {

    private const val TAG = "SmartSchedulerManager"

    /**
     * 调度器类型
     */
    enum class SchedulerType {
        COROUTINE,      // 协程调度器（低内存，可补偿）
        WORK_MANAGER    // WorkManager（中等内存，系统优化）
    }

    /**
     * 延迟记录
     */
    data class DelayRecord(
        val expectedTime: Long,     // 预期执行时间
        val actualTime: Long,        // 实际执行时间
        val delayMs: Long            // 延迟时长（毫秒）
    )

    // 最近延迟记录（最多保存 10 条）
    private val delayHistory = ConcurrentLinkedQueue<DelayRecord>()
    private const val MAX_HISTORY_SIZE = 10

    // 当前使用的调度器类型
    @Volatile
    private var currentSchedulerType = SchedulerType.COROUTINE

    // 当前补偿值（毫秒）
    @Volatile
    private var currentCompensation = 120000L // 初始 2 分钟

    // 最小/最大补偿值
    private const val MIN_COMPENSATION = 0L          // 0 秒
    private const val MAX_COMPENSATION = 600000L     // 10 分钟（协程最大补偿）

    // 调度器实例
    @SuppressLint("StaticFieldLeak")
    private var coroutineScheduler: CoroutineScheduler? = null
    @SuppressLint("StaticFieldLeak")
    private var workManagerScheduler: WorkManagerScheduler? = null

    // 调度器监控器（实时检测延迟并调整补偿）
    @SuppressLint("StaticFieldLeak")
    private var schedulerMonitor: SchedulerMonitor? = null

    // 初始化标志（避免重复初始化）
    @Volatile
    private var initialized = false

    /**
     * 初始化调度器
     */
    @Synchronized
    fun initialize(context: Context) {
        if (initialized) {
            Log.debug(TAG, "调度器已经初始化，跳过重复初始化")
            return
        }

        try {
            // 预创建两个调度器实例
            coroutineScheduler = CoroutineScheduler(context)
            workManagerScheduler = WorkManagerScheduler(context)

            // 创建并启动监控器
            schedulerMonitor = SchedulerMonitor(context)
            schedulerMonitor?.startMonitoring()

            initialized = true
            Log.runtime(TAG, "✅ 智能调度器管理器已初始化")
            Log.runtime(TAG, "当前调度器: ${currentSchedulerType.name}")
            Log.runtime(TAG, "初始补偿: ${currentCompensation / 1000} 秒")
            Log.runtime(TAG, "监控器: 已启动（每 10 秒检测）")
        } catch (e: Exception) {
            Log.error(TAG, "初始化失败: ${e.message}")
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 记录执行延迟
     *
     * @param expectedTime 预期执行时间戳
     * @param actualTime 实际执行时间戳
     */
    fun recordDelay(expectedTime: Long, actualTime: Long) {
        val delayMs = actualTime - expectedTime
        val record = DelayRecord(expectedTime, actualTime, delayMs)

        // 添加记录
        delayHistory.offer(record)

        // 限制历史记录数量
        while (delayHistory.size > MAX_HISTORY_SIZE) {
            delayHistory.poll()
        }

        // 记录日志
        val delaySeconds = delayMs / 1000
        Log.record(TAG, "📊 记录延迟: ${delaySeconds} 秒 (${if (delayMs > 0) "延迟" else "提前"})")

        // 触发智能调整
        adjustStrategy()
    }

    /**
     * 智能调整策略
     */
    private fun adjustStrategy() {
        if (delayHistory.size < 3) {
            Log.debug(TAG, "历史记录不足，暂不调整策略")
            return
        }

        // 计算平均延迟
        val averageDelay = calculateAverageDelay()
        val averageDelaySeconds = averageDelay / 1000

        Log.record(TAG, "📈 最近 ${delayHistory.size} 次平均延迟: ${averageDelaySeconds} 秒")

        // 根据延迟调整策略
        when {
            // 延迟很小（< 30 秒）：减少补偿
            averageDelay < 30000 -> {
                val oldCompensation = currentCompensation
                currentCompensation = (currentCompensation - 30000).coerceAtLeast(MIN_COMPENSATION)
                if (currentCompensation != oldCompensation) {
                    Log.record(TAG, "✅ 延迟很小，减少补偿: ${oldCompensation / 1000}s → ${currentCompensation / 1000}s")
                }
                // 如果当前是 WorkManager，考虑切回协程
                if (currentSchedulerType == SchedulerType.WORK_MANAGER) {
                    switchToCoroutine()
                }
            }

            // 延迟适中（30秒 - 90秒）：微调补偿
            averageDelay in 30000..90000 -> {
                val oldCompensation = currentCompensation
                // 根据实际延迟微调：补偿 = 当前补偿 + (平均延迟 - 60秒) * 0.8
                val adjustment = ((averageDelay - 60000) * 0.8).toLong()
                currentCompensation = (currentCompensation + adjustment).coerceIn(MIN_COMPENSATION, MAX_COMPENSATION)
                if (abs(currentCompensation - oldCompensation) > 10000) {
                    Log.record(TAG, "⚙️ 微调补偿: ${oldCompensation / 1000}s → ${currentCompensation / 1000}s")
                }
            }

            // 延迟较大（90秒 - 180秒）：增加补偿
            averageDelay in 90000..180000 -> {
                val oldCompensation = currentCompensation
                currentCompensation = (currentCompensation + 30000).coerceAtMost(MAX_COMPENSATION)
                if (currentCompensation != oldCompensation) {
                    Log.record(TAG, "⚠️ 延迟较大，增加补偿: ${oldCompensation / 1000}s → ${currentCompensation / 1000}s")
                }
            }

            // 延迟超过 3 分钟：使用最大补偿（不切换 WorkManager）
            true -> {
                val oldCompensation = currentCompensation
                if (currentCompensation < MAX_COMPENSATION) {
                    currentCompensation = MAX_COMPENSATION
                    Log.record(TAG, "❗ 平均延迟 > 3 分钟，使用最大补偿: ${oldCompensation / 1000}s → ${currentCompensation / 1000}s")
                } else {
                    Log.runtime(TAG, "📊 已使用最大补偿 ${MAX_COMPENSATION / 1000}s，平均延迟: ${averageDelaySeconds}s")
                }
            }
        }
    }

    /**
     * 计算平均延迟
     */
    private fun calculateAverageDelay(): Long {
        if (delayHistory.isEmpty()) return 0L
        return delayHistory.map { it.delayMs }.average().toLong()
    }

    /**
     * 切换到 WorkManager
     */
    private fun switchToWorkManager() {
        if (currentSchedulerType == SchedulerType.WORK_MANAGER) return

        Log.record(TAG, "🔄 切换调度器: COROUTINE → WORK_MANAGER")
        Log.record(TAG, "原因: 平均延迟超过 3 分钟，WorkManager 更稳定")

        currentSchedulerType = SchedulerType.WORK_MANAGER
        currentCompensation = 0L // WorkManager 不需要补偿

        // 清空历史记录，重新统计
        delayHistory.clear()
    }

    /**
     * 切换到协程
     */
    private fun switchToCoroutine() {
        if (currentSchedulerType == SchedulerType.COROUTINE) return

        Log.record(TAG, "🔄 切换调度器: WORK_MANAGER → COROUTINE")
        Log.record(TAG, "原因: WorkManager 延迟已降低，协程更省内存")

        currentSchedulerType = SchedulerType.COROUTINE
        currentCompensation = 60000L // 重新从 1 分钟补偿开始

        // 清空历史记录，重新统计
        delayHistory.clear()
    }

    /**
     * 获取当前补偿值（优先使用监控器的实时补偿）
     */
    fun getCurrentCompensation(): Long {
        return if (currentSchedulerType == SchedulerType.COROUTINE) {
            // 优先使用监控器的实时补偿值
            schedulerMonitor?.getCurrentCompensation() ?: currentCompensation
        } else {
            0L // WorkManager 不需要补偿
        }
    }

    /**
     * 重置补偿值（强制重新初始化时调用）
     */
    fun resetCompensation() {
        try {
            currentCompensation = 120000L // 重置为初始值 2 分钟
            delayHistory.clear() // 清空延迟历史
            Log.record(TAG, "✅ 补偿值已重置为: ${currentCompensation / 1000}s")
        } catch (e: Exception) {
            Log.error(TAG, "重置补偿值失败: ${e.message}")
        }
    }

    /**
     * 调度精确执行
     *
     * 策略：
     * - 延迟 < 10 分钟：协程 + 智能补偿（监控器动态调整）
     * - 延迟 > 10 分钟：WorkManager（系统长期调度更可靠）
     */
    fun scheduleExactExecution(delayMillis: Long, exactTimeMillis: Long) {
        // 智能选择调度器：超过 10 分钟才使用 WorkManager
        val shouldUseWorkManager = delayMillis > 600000 // 10 分钟

        if (shouldUseWorkManager && currentSchedulerType == SchedulerType.COROUTINE) {
            Log.record(TAG, "📊 延迟 ${delayMillis / 1000}s > 10 分钟，切换 WorkManager")
            switchToWorkManager()
        } else if (!shouldUseWorkManager && currentSchedulerType == SchedulerType.WORK_MANAGER) {
            // 延迟 < 10 分钟，切回协程（更精确）
            Log.record(TAG, "📊 延迟 ${delayMillis / 1000}s < 10 分钟，切回协程模式（更精确）")
            switchToCoroutine()
        }

        // 应用补偿（优先使用监控器的实时补偿）
        val compensation = getCurrentCompensation()
        val compensatedDelay = (delayMillis - compensation).coerceAtLeast(0)

        // 记录到监控器
        val taskId = "task_${exactTimeMillis}"
        schedulerMonitor?.recordSchedule(taskId, exactTimeMillis)

        // 根据当前调度器类型调用
        when (currentSchedulerType) {
            SchedulerType.COROUTINE -> {
                coroutineScheduler?.scheduleExactExecution(compensatedDelay, exactTimeMillis)
                    ?: Log.error(TAG, "协程调度器未初始化")
            }
            SchedulerType.WORK_MANAGER -> {
                workManagerScheduler?.scheduleExactExecution(delayMillis, exactTimeMillis)
                    ?: Log.error(TAG, "WorkManager 调度器未初始化")
            }
        }

        // 记录调度信息
        Log.record(TAG, "📅 已调度 (${currentSchedulerType.name})")
        if (currentSchedulerType == SchedulerType.COROUTINE && compensation > 0) {
            Log.record(TAG, "补偿: ${compensation / 1000}s, 实际延迟: ${compensatedDelay / 1000}s")
        } else if (currentSchedulerType == SchedulerType.WORK_MANAGER) {
            Log.record(TAG, "延迟: ${delayMillis / 1000}s (无需补偿)")
        }
    }

    /**
     * 调度延迟执行
     */
    fun scheduleDelayedExecution(delayMillis: Long) {
        when (currentSchedulerType) {
            SchedulerType.COROUTINE -> coroutineScheduler?.scheduleDelayedExecution(delayMillis)
            SchedulerType.WORK_MANAGER -> workManagerScheduler?.scheduleDelayedExecution(delayMillis)
        }
    }

    /**
     * 调度唤醒任务
     */
    fun scheduleWakeupAlarm(triggerAtMillis: Long, requestCode: Int, isMainAlarm: Boolean): Boolean {
        return when (currentSchedulerType) {
            SchedulerType.COROUTINE -> coroutineScheduler?.scheduleWakeupAlarm(triggerAtMillis, requestCode, isMainAlarm) ?: false
            SchedulerType.WORK_MANAGER -> workManagerScheduler?.scheduleWakeupAlarm(triggerAtMillis, requestCode, isMainAlarm) ?: false
        }
    }

    /**
     * 取消所有唤醒任务
     */
    fun cancelAllWakeupAlarms() {
        when (currentSchedulerType) {
            SchedulerType.COROUTINE -> coroutineScheduler?.cancelAllWakeupAlarms()
            SchedulerType.WORK_MANAGER -> workManagerScheduler?.cancelAllWakeupAlarms()
        }
    }

    /**
     * 通知任务开始执行
     *
     * @param taskId 任务唯一标识
     */
    fun notifyTaskExecution(taskId: String) {
        schedulerMonitor?.recordExecution(taskId)
    }

    /**
     * 获取统计信息
     */
    fun getStatistics(): String {
        val avgDelay = if (delayHistory.isNotEmpty()) {
            calculateAverageDelay() / 1000
        } else {
            0L
        }

        val monitorStats = schedulerMonitor?.getStatistics() ?: "监控器未启动"

        return buildString {
            append("调度器: ${currentSchedulerType.name}")
            append(", 补偿: ${currentCompensation / 1000}s")
            append(", 平均延迟: ${avgDelay}s")
            append(", 记录数: ${delayHistory.size}")
            append("\n监控: $monitorStats")
        }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        schedulerMonitor?.cleanup()
        Log.runtime(TAG, "智能调度器管理器已清理")
    }
}