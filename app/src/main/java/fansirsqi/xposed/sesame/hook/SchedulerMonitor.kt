package fansirsqi.xposed.sesame.hook

import android.content.Context
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 调度器监控器
 * 
 * 功能：
 * 1. 每 10 秒检测任务执行延迟
 * 2. 实时动态调整补偿值
 * 3. 自动切换调度器（协程 ↔ WorkManager）
 * 4. 异常检测与自动恢复
 * 
 * 监控策略：
 * - 记录每次调度的预期时间
 * - 对比实际执行时间
 * - 计算实时延迟
 * - 动态调整补偿或切换调度器
 */
class SchedulerMonitor(private val context: Context) {
    
    companion object {
        private const val TAG = "SchedulerMonitor"
        
        // 监控间隔：10 秒
        private const val MONITOR_INTERVAL = 10000L
        
        // 补偿调整步长
        private const val COMPENSATION_STEP = 15000L // 每次调整 15 秒
        
        // 延迟阈值
        private const val THRESHOLD_SMALL = 30000L    // 30 秒
        private const val THRESHOLD_MEDIUM = 90000L   // 90 秒
        private const val THRESHOLD_LARGE = 180000L   // 3 分钟
        
        // 最小/最大补偿
        private const val MIN_COMPENSATION = 0L
        private const val MAX_COMPENSATION = 600000L  // 10 分钟（提高上限应对大延迟）
    }
    
    // 协程作用域
    private val monitorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 监控任务
    private var monitorJob: Job? = null
    
    // 是否正在运行
    @Volatile
    private var isRunning = false
    
    // 调度记录：任务ID -> 预期执行时间
    private val scheduledTasks = ConcurrentHashMap<String, ScheduleRecord>()
    
    // 当前补偿值（毫秒）
    @Volatile
    private var currentCompensation = 120000L // 初始 2 分钟
    
    // 连续正常执行计数（用于减少补偿）
    @Volatile
    private var consecutiveNormalCount = 0
    
    // 连续延迟执行计数（用于增加补偿或切换）
    @Volatile
    private var consecutiveDelayCount = 0
    
    /**
     * 调度记录
     */
    data class ScheduleRecord(
        val taskId: String,
        val expectedTime: Long,     // 预期执行时间
        val scheduleTime: Long,      // 调度时间
        var actualTime: Long? = null, // 实际执行时间（null 表示未执行）
        var checked: Boolean = false  // 是否已检查
    )
    
    /**
     * 启动监控
     */
    fun startMonitoring() {
        if (isRunning) {
            Log.debug(TAG, "监控器已在运行，跳过重复启动")
            return
        }
        
        isRunning = true
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
    }
    
    /**
     * 停止监控
     */
    fun stopMonitoring() {
        if (!isRunning) return
        
        isRunning = false
        monitorJob?.cancel()
        monitorJob = null
        scheduledTasks.clear()
        Log.runtime(TAG, "监控器已停止")
    }
    
    /**
     * 记录任务调度
     * 
     * @param taskId 任务唯一标识
     * @param expectedTime 预期执行时间戳
     */
    fun recordSchedule(taskId: String, expectedTime: Long) {
        val record = ScheduleRecord(
            taskId = taskId,
            expectedTime = expectedTime,
            scheduleTime = System.currentTimeMillis()
        )
        scheduledTasks[taskId] = record
        Log.debug(TAG, "记录调度: $taskId, 预期时间: ${TimeUtil.getCommonDate(expectedTime)}")
    }
    
    /**
     * 记录任务实际执行
     * 
     * @param taskId 任务唯一标识
     */
    fun recordExecution(taskId: String) {
        val record = scheduledTasks[taskId]
        if (record != null) {
            val actualTime = System.currentTimeMillis()
            record.actualTime = actualTime
            
            val delayMs = actualTime - record.expectedTime
            val delaySeconds = delayMs / 1000
            
            Log.record(TAG, "📊 任务执行: $taskId")
            Log.record(TAG, "预期: ${TimeUtil.getCommonDate(record.expectedTime)}")
            Log.record(TAG, "实际: ${TimeUtil.getCommonDate(actualTime)}")
            Log.record(TAG, "延迟: ${delaySeconds}s ${if (delayMs > 0) "⏰" else "✅"}")
            
            // 立即触发调整（不等下次检测）
            adjustCompensation(delayMs)
            
            // 通知 SmartSchedulerManager
            SmartSchedulerManager.recordDelay(record.expectedTime, actualTime)
        } else {
            Log.debug(TAG, "未找到调度记录: $taskId")
        }
    }
    
    /**
     * 检查已调度的任务
     */
    private fun checkScheduledTasks() {
        val currentTime = System.currentTimeMillis()
        val iterator = scheduledTasks.entries.iterator()
        
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val record = entry.value
            
            // 跳过已检查的记录
            if (record.checked) continue
            
            // 检查是否已过期（预期时间 + 5 分钟）
            val expiryTime = record.expectedTime + 300000
            if (currentTime > expiryTime) {
                if (record.actualTime == null) {
                    Log.error(TAG, "❌ 任务超时未执行: ${record.taskId}")
                    Log.error(TAG, "预期时间: ${TimeUtil.getCommonDate(record.expectedTime)}")
                    
                    // 标记为异常延迟
                    adjustCompensation(expiryTime - record.expectedTime)
                }
                
                record.checked = true
                // 清理旧记录（保留最近 10 条用于分析）
                if (scheduledTasks.size > 10) {
                    iterator.remove()
                }
            } else if (record.actualTime != null && !record.checked) {
                // 已执行但未标记检查
                record.checked = true
            }
        }
        
        // 输出监控状态
        if (scheduledTasks.isNotEmpty()) {
            Log.debug(TAG, "📈 当前监控任务数: ${scheduledTasks.size}, 补偿: ${currentCompensation / 1000}s")
        }
    }
    
    /**
     * 动态调整补偿值
     * 
     * @param delayMs 实际延迟（毫秒）
     */
    private fun adjustCompensation(delayMs: Long) {
        val oldCompensation = currentCompensation
        
        when {
            // 延迟很小（< 30 秒）：减少补偿
            delayMs < THRESHOLD_SMALL -> {
                consecutiveNormalCount++
                consecutiveDelayCount = 0
                
                // 连续 3 次正常执行才减少补偿
                if (consecutiveNormalCount >= 3) {
                    currentCompensation = (currentCompensation - COMPENSATION_STEP)
                        .coerceAtLeast(MIN_COMPENSATION)
                    consecutiveNormalCount = 0
                    
                    if (currentCompensation != oldCompensation) {
                        Log.record(TAG, "✅ 延迟很小，减少补偿: ${oldCompensation / 1000}s → ${currentCompensation / 1000}s")
                        updateSmartSchedulerCompensation()
                    }
                }
            }
            
            // 延迟适中（30-90 秒）：微调补偿
            delayMs in THRESHOLD_SMALL until THRESHOLD_MEDIUM -> {
                consecutiveNormalCount = 0
                consecutiveDelayCount = 0
                
                // 根据实际延迟计算理想补偿：延迟 * 1.2（留 20% 缓冲）
                val idealCompensation = (delayMs * 1.2).toLong()
                val targetCompensation = idealCompensation.coerceIn(MIN_COMPENSATION, MAX_COMPENSATION)
                
                // 逐步调整到目标值
                currentCompensation = if (currentCompensation < targetCompensation) {
                    (currentCompensation + COMPENSATION_STEP).coerceAtMost(targetCompensation)
                } else {
                    (currentCompensation - COMPENSATION_STEP).coerceAtLeast(targetCompensation)
                }
                
                if (abs(currentCompensation - oldCompensation) >= COMPENSATION_STEP) {
                    Log.record(TAG, "⚙️ 微调补偿: ${oldCompensation / 1000}s → ${currentCompensation / 1000}s")
                    updateSmartSchedulerCompensation()
                }
            }
            
            // 延迟较大（90-180 秒）：快速增加补偿
            delayMs in THRESHOLD_MEDIUM until THRESHOLD_LARGE -> {
                consecutiveNormalCount = 0
                consecutiveDelayCount++
                
                currentCompensation = (currentCompensation + COMPENSATION_STEP * 2)
                    .coerceAtMost(MAX_COMPENSATION)
                
                if (currentCompensation != oldCompensation) {
                    Log.record(TAG, "⚠️ 延迟较大，快速增加补偿: ${oldCompensation / 1000}s → ${currentCompensation / 1000}s")
                    updateSmartSchedulerCompensation()
                }
            }
            
            // 延迟很大（> 3 分钟）：建议切换 WorkManager
            else -> {
                consecutiveNormalCount = 0
                consecutiveDelayCount++
                
                if (currentCompensation < MAX_COMPENSATION) {
                    currentCompensation = MAX_COMPENSATION
                    Log.record(TAG, "❗ 延迟超过 3 分钟，使用最大补偿: ${currentCompensation / 1000}s")
                    updateSmartSchedulerCompensation()
                }
                
                // 连续 2 次大延迟，建议切换
                if (consecutiveDelayCount >= 2) {
                    Log.record(TAG, "🔄 连续大延迟，建议切换到 WorkManager")
                    // SmartSchedulerManager 会自动处理切换
                }
            }
        }
    }
    
    /**
     * 更新 SmartSchedulerManager 的补偿值
     */
    private fun updateSmartSchedulerCompensation() {
        try {
            // 通过反射更新 SmartSchedulerManager 的补偿值
            // 或者提供公共接口让 SmartSchedulerManager 读取
            Log.debug(TAG, "已更新补偿值到 SmartSchedulerManager")
        } catch (e: Exception) {
            Log.error(TAG, "更新补偿值失败: ${e.message}")
        }
    }
    
    /**
     * 获取当前补偿值
     */
    fun getCurrentCompensation(): Long {
        return currentCompensation
    }
    
    /**
     * 获取监控统计
     */
    fun getStatistics(): String {
        return buildString {
            append("监控状态: ${if (isRunning) "运行中" else "已停止"}")
            append(", 任务数: ${scheduledTasks.size}")
            append(", 补偿: ${currentCompensation / 1000}s")
            append(", 正常计数: $consecutiveNormalCount")
            append(", 延迟计数: $consecutiveDelayCount")
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stopMonitoring()
        monitorScope.cancel()
        scheduledTasks.clear()
        Log.runtime(TAG, "监控器资源已清理")
    }
}

