package fansirsqi.xposed.sesame.hook

import android.annotation.SuppressLint
import android.content.Context
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
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
    private const val MAX_COMPENSATION = 240000L     // 4 分钟
    
    // 调度器实例
    @SuppressLint("StaticFieldLeak")
    private var coroutineScheduler: CoroutineScheduler? = null
    @SuppressLint("StaticFieldLeak")
    private var workManagerScheduler: WorkManagerScheduler? = null
    
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
            initialized = true
            Log.record(TAG, "✅ 智能调度器管理器已初始化")
            Log.record(TAG, "当前调度器: ${currentSchedulerType.name}")
            Log.record(TAG, "初始补偿: ${currentCompensation / 1000} 秒")
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
            
            // 延迟很大（180秒 - 300秒）：最大补偿
            averageDelay in 180000..300000 -> {
                if (currentCompensation < MAX_COMPENSATION) {
                    currentCompensation = MAX_COMPENSATION
                    Log.record(TAG, "❗ 延迟很大，使用最大补偿: ${currentCompensation / 1000}s")
                }
                // 如果协程补偿已达上限仍延迟大，考虑切换
                if (currentSchedulerType == SchedulerType.COROUTINE) {
                    Log.record(TAG, "⚠️ 协程补偿已达上限，准备切换到 WorkManager")
                }
            }
            
            // 延迟极大（> 5 分钟）：切换调度器
            true -> {
                if (currentSchedulerType == SchedulerType.COROUTINE) {
                    switchToWorkManager()
                } else {
                    Log.error(TAG, "❌ WorkManager 延迟仍然很大，建议检查系统设置")
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
        Log.record(TAG, "原因: 协程延迟过大，即使最大补偿仍无法满足")
        
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
     * 获取当前补偿值
     */
    fun getCurrentCompensation(): Long {
        return if (currentSchedulerType == SchedulerType.COROUTINE) {
            currentCompensation
        } else {
            0L // WorkManager 不需要补偿
        }
    }
    
    /**
     * 调度精确执行
     */
    fun scheduleExactExecution(delayMillis: Long, exactTimeMillis: Long) {
        // 应用补偿
        val compensation = getCurrentCompensation()
        val compensatedDelay = (delayMillis - compensation).coerceAtLeast(0)
        
        // 根据当前调度器类型调用
        when (currentSchedulerType) {
            SchedulerType.COROUTINE -> {
                coroutineScheduler?.scheduleExactExecution(compensatedDelay, exactTimeMillis)
                    ?: Log.error(TAG, "协程调度器未初始化")
            }
            SchedulerType.WORK_MANAGER -> {
                workManagerScheduler?.scheduleExactExecution(compensatedDelay, exactTimeMillis)
                    ?: Log.error(TAG, "WorkManager 调度器未初始化")
            }
        }
        
        // 记录调度信息
        Log.record(TAG, "📅 已调度 (${currentSchedulerType.name})")
        if (compensation > 0) {
            Log.record(TAG, "补偿: ${compensation / 1000}s, 实际延迟: ${compensatedDelay / 1000}s")
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
     * 获取统计信息
     */
    fun getStatistics(): String {
        val avgDelay = if (delayHistory.isNotEmpty()) {
            calculateAverageDelay() / 1000
        } else {
            0L
        }
        
        return buildString {
            append("调度器: ${currentSchedulerType.name}")
            append(", 补偿: ${currentCompensation / 1000}s")
            append(", 平均延迟: ${avgDelay}s")
            append(", 记录数: ${delayHistory.size}")
        }
    }
}

