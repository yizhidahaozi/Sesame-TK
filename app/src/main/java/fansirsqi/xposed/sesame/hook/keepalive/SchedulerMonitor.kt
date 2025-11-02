package fansirsqi.xposed.sesame.hook.keepalive

import android.content.Context
import fansirsqi.xposed.sesame.hook.ApplicationHook
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.PriorityQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 调度器监控器（纯协程版）
 *
 * 功能：
 * 1. 每 10 秒检测任务执行延迟
 * 2. 实时动态调整补偿值
 * 3. 异常检测与自动恢复
 *
 * 监控策略：
 * - 记录每次调度的预期时间
 * - 对比实际执行时间
 * - 计算实时延迟
 * - 动态调整补偿值
 */
class SchedulerMonitor(private val context: Context) {

    companion object {
        private const val TAG = "SchedulerMonitor"

        // 监控间隔：10 秒
        private const val MONITOR_INTERVAL = 10000L
    }

    // 协程作用域
    private val monitorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 监控任务
    private var monitorJob: Job? = null

    // 连续延迟计数器（用于动态调整补偿策略）
    @Volatile
    private var consecutiveDelayCount = 0

    // 是否正在运行
    @Volatile
    private var isRunning = false

    // 调度记录：任务ID -> 预期执行时间
    private val scheduledTasks = ConcurrentHashMap<String, ScheduleRecord>()
    
    // 性能优化：维护已排序的任务队列（避免重复filter+sort）
    // ✅ 修复：使用读写锁保护 PriorityQueue 的并发访问
    private val queueLock = ReentrantReadWriteLock()
    private val upcomingTasksQueue = PriorityQueue<ScheduleRecord>(
        compareBy { it.expectedTime }
    )

    /**
     * 调度记录（内存优化版）
     * 
     * 优化：使用位标志减少内存占用
     * - 原始: ~88 bytes (String + 4xLong + Boolean)
     * - 优化: ~56 bytes (String + 3xLong + Byte)
     * - 内存节省: ~36%
     */
    data class ScheduleRecord(
        val taskId: String,
        val expectedTime: Long,     // 预期执行时间
        val scheduleTime: Long,      // 调度时间
        var actualTime: Long = 0L,   // 实际执行时间（0 表示未执行）
        var status: Byte = 0         // 位标志: bit0=checked, bit1=executed
    ) {
        val isChecked: Boolean get() = (status.toInt() and 1) != 0
        val isExecuted: Boolean get() = actualTime > 0
        
        fun markChecked() { status = (status.toInt() or 1).toByte() }
        fun markExecuted(time: Long) { 
            actualTime = time
            status = (status.toInt() or 2).toByte()
        }
    }

    /**
     * 启动监控
     */
    fun startMonitoring() {
        if (isRunning) {
            Log.debug(TAG, "监控器已在运行，跳过重复启动")
            return
        }

        isRunning = true

        // 启动监控任务
        monitorJob = monitorScope.launch {
            Log.runtime(TAG, "🔍 调度器监控器已启动")
            Log.runtime(TAG, "监控间隔: ${MONITOR_INTERVAL / 1000}s")

            while (isActive && isRunning) {
                try {
                    checkScheduledTasks()
                    delay(MONITOR_INTERVAL)
                } catch (e: CancellationException) {
                    throw e // 重新抛出取消异常
                } catch (e: Exception) {
                    Log.error(TAG, "监控异常: ${e.message}")
                    Log.printStackTrace(TAG, e)
                }
            }

            Log.runtime(TAG, "🔍 调度器监控器已停止")
        }

        // 启动保活助手（Android 9+）
        startKeepAliveHelper()
    }

    /**
     * 停止监控（并发安全版）
     */
    fun stopMonitoring() {
        if (!isRunning) return

        isRunning = false
        monitorJob?.cancel()
        monitorJob = null

        // ✅ 使用写锁保护清理操作
        queueLock.write {
            scheduledTasks.clear()
            upcomingTasksQueue.clear()
        }
        Log.runtime(TAG, "监控器已停止")
    }

    /**
     * 启动保活助手
     */
    private fun startKeepAliveHelper() {
        try {
            val alipayContext = ApplicationHook.getAppContext()
            if (alipayContext == null) {
                Log.debug(TAG, "支付宝 Context 为 null，无法启动保活助手")
                return
            }

            Log.record(TAG, "✅ 使用纯协程调度，无需保活助手")

        } catch (e: Exception) {
            Log.error(TAG, "启动保活助手失败: ${e.message}")
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 记录任务调度（性能优化版 v3 - 并发安全）
     *
     * @param taskId 任务唯一标识
     * @param expectedTime 预期执行时间戳
     * 
     * 优化：
     * 1. 同时维护 HashMap 和 PriorityQueue
     * 2. 使用读写锁保证并发安全
     * 3. 合并日志输出减少 I/O
     */
    fun recordSchedule(taskId: String, expectedTime: Long) {
        val record = ScheduleRecord(
            taskId = taskId,
            expectedTime = expectedTime,
            scheduleTime = System.currentTimeMillis()
        )
        
        // ✅ 使用写锁保护并发访问
        queueLock.write {
            scheduledTasks[taskId] = record
            upcomingTasksQueue.offer(record) // O(log n) 自动排序
        }
        
        // 日志优化：使用 debug 级别输出
        Log.debug(TAG, "记录调度: $taskId @ ${TimeUtil.getCommonDate(expectedTime)}")
    }

    /**
     * 记录任务实际执行（性能优化版）
     *
     * @param taskId 任务唯一标识
     * 
     * 优化：合并日志输出，减少 I/O 次数 75%（4行 → 1行）
     */
    fun recordExecution(taskId: String) {
        val record = scheduledTasks[taskId]
        if (record != null) {
            val actualTime = System.currentTimeMillis()
            record.markExecuted(actualTime)

            val delayMs = actualTime - record.expectedTime
            val delaySeconds = delayMs / 1000

            // ✅ 日志优化：合并为一行，减少 I/O 开销
            Log.record(TAG, "📊 任务执行: $taskId | 延迟: ${delaySeconds}s ${if (delayMs > 0) "⏰" else "✅"} | " +
                "预期: ${TimeUtil.getCommonDate(record.expectedTime)}")

            // 通知 SmartSchedulerManager 记录并调整
            SmartSchedulerManager.recordDelay(record.expectedTime, actualTime)
        } else {
            Log.debug(TAG, "未找到调度记录: $taskId")
        }
    }

    /**
     * 检查已调度的任务（性能优化版 v2 - 并发安全）
     * 
     * 优化：
     * 1. 使用 PriorityQueue 只检查即将过期的任务（O(k) 而非 O(n)）
     * 2. 使用读写锁保证并发安全
     * 3. 合并日志输出减少 I/O
     */
    private fun checkScheduledTasks() {
        val currentTime = System.currentTimeMillis()
        var checkedCount = 0
        var timeoutCount = 0

        // ✅ 使用写锁保护队列操作
        queueLock.write {
            // 使用 PriorityQueue 只检查即将过期的任务
            while (upcomingTasksQueue.isNotEmpty()) {
                val record = upcomingTasksQueue.peek() ?: break
                
                // 如果最早的任务还没到检查时间，后面的任务更不需要检查
                // 过期时间 = 预期时间 + 5 分钟
                if (record.expectedTime + 300000 > currentTime) {
                    break // 剩余任务都不需要检查
                }
                
                // 移除已到检查时间的任务
                upcomingTasksQueue.poll()
                checkedCount++
                
                // 跳过已检查或已执行的记录
                if (record.isChecked || record.isExecuted) {
                    continue
                }
                
                // 处理超时未执行的任务
                val delayMinutes = (currentTime - record.expectedTime) / 60000
                
                // ✅ 日志优化：合并为一行
                Log.runtime(TAG, "❌ 任务超时: ${record.taskId} | 延迟: ${delayMinutes}分钟 | " +
                    "预期: ${TimeUtil.getCommonDate(record.expectedTime)}")
                
                timeoutCount++
                consecutiveDelayCount++

                // 通知 SmartSchedulerManager 记录延迟
                val expiryTime = record.expectedTime + 300000
                SmartSchedulerManager.recordDelay(record.expectedTime, expiryTime)

                // 连续延迟时，强制重新初始化
                if (consecutiveDelayCount >= 3) {
                    Log.record(TAG, "🔄 连续超时 $consecutiveDelayCount 次，强制重新初始化！")
                    forceReinitialize()
                    break
                }

                record.markChecked()
            }
            
            // 清理 HashMap 中的旧记录（保留最近 10 条）
            if (scheduledTasks.size > 10) {
                val iterator = scheduledTasks.entries.iterator()
                var removed = 0
                while (iterator.hasNext() && scheduledTasks.size - removed > 10) {
                    val entry = iterator.next()
                    if (entry.value.isChecked || entry.value.isExecuted) {
                        iterator.remove()
                        removed++
                    }
                }
            }
        }

        // 输出监控状态（性能优化：只在有内容时输出）
        if (checkedCount > 0 || scheduledTasks.isNotEmpty()) {
            val compensation = SmartSchedulerManager.getCurrentCompensation()
            // ✅ 日志优化：合并为一行
            if (checkedCount > 0 || scheduledTasks.isNotEmpty()) {
                Log.debug(TAG, "📈 监控: 检查=$checkedCount, 超时=$timeoutCount, " +
                    "任务数=${scheduledTasks.size}, 补偿=${compensation / 1000}s")
            }
        }
    }

    /**
     * 获取监控统计
     */
    fun getStatistics(): String {
        val compensation = SmartSchedulerManager.getCurrentCompensation()
        return buildString {
            append("监控状态: ${if (isRunning) "运行中" else "已停止"}")
            append(", 任务数: ${scheduledTasks.size}")
            append(", 补偿: ${compensation / 1000}s")
            append(", 连续延迟: $consecutiveDelayCount 次")
        }
    }

    /**
     * 强制重新初始化（连续超时3次时触发，并发安全版）
     */
    private fun forceReinitialize() {
        monitorScope.launch {
            try {
                Log.record(TAG, "🚨 强制重新初始化调度器（纯协程）")

                // ✅ 使用写锁清空所有调度记录
                queueLock.write {
                    scheduledTasks.clear()
                    upcomingTasksQueue.clear()
                }
                consecutiveDelayCount = 0

                // 重置补偿值
                SmartSchedulerManager.resetCompensation()

                // 重新调度任务
                try {
                    ApplicationHook.scheduleNextExecution()
                    Log.record(TAG, "✅ 重新调度完成")
                } catch (e: Exception) {
                    Log.error(TAG, "重新调度失败: ${e.message}")
                    Log.printStackTrace(TAG, e)
                }

            } catch (e: Exception) {
                Log.error(TAG, "强制重新初始化失败: ${e.message}")
                Log.printStackTrace(TAG, e)
            }
        }
    }

    /**
     * 清理资源（并发安全版）
     */
    fun cleanup() {
        stopMonitoring()
        monitorScope.cancel()
        
        // ✅ 使用写锁保护清理操作
        queueLock.write {
            scheduledTasks.clear()
            upcomingTasksQueue.clear()
        }
        consecutiveDelayCount = 0
        Log.runtime(TAG, "监控器资源已清理")
    }
}