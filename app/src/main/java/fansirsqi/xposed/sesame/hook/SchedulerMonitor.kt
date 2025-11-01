package fansirsqi.xposed.sesame.hook

import android.content.Context
import fansirsqi.xposed.sesame.hook.keepalive.AlipayMethodHelper
import fansirsqi.xposed.sesame.hook.keepalive.KeepAliveHelper
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
        
        // 支付宝唤醒间隔：60 秒
        private const val ALIPAY_WAKEUP_INTERVAL = 60000L
        
        // 提前唤醒阈值：5 分钟
        private const val EARLY_WAKEUP_THRESHOLD = 300000L // 5 分钟内的任务会被提前唤醒
        
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
    
    // 支付宝唤醒任务
    private var alipayWakeupJob: Job? = null
    
    // 保活助手（Android 9+）
    private var keepAliveHelper: KeepAliveHelper? = null
    
    // 连续延迟计数器（用于动态调整唤醒策略）
    @Volatile
    private var consecutiveDelayCount = 0
    
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
        
        // 启动支付宝唤醒任务
        startAlipayWakeup()
        
        // 启动保活助手（Android 9+）
        startKeepAliveHelper()
    }
    
    /**
     * 停止监控
     */
    fun stopMonitoring() {
        if (!isRunning) return
        
        isRunning = false
        monitorJob?.cancel()
        monitorJob = null
        alipayWakeupJob?.cancel()
        alipayWakeupJob = null
        
        // 停止保活助手
        keepAliveHelper?.stop()
        
        scheduledTasks.clear()
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
            
            keepAliveHelper = KeepAliveHelper(alipayContext) { timeUntilExecution ->
                // 回调：当检测到即将执行的任务时
                handleUpcomingTask(timeUntilExecution)
            }
            
            if (keepAliveHelper?.isSupported() == true) {
                keepAliveHelper?.start()
            } else {
                Log.record(TAG, "⚠️ 当前系统版本不支持保活助手（需要 Android 9+）")
                keepAliveHelper = null
            }
            
        } catch (e: Exception) {
            Log.error(TAG, "启动保活助手失败: ${e.message}")
            Log.printStackTrace(TAG, e)
        }
    }
    
    /**
     * 处理即将执行的任务
     */
    private fun handleUpcomingTask(timeUntilExecution: Long) {
        try {
            val currentTime = System.currentTimeMillis()
            
            // 查找即将执行的任务（10 分钟内，扩大范围）
            val upcomingTasks = scheduledTasks.values.filter { record ->
                record.actualTime == null && 
                record.expectedTime > currentTime && 
                (record.expectedTime - currentTime) <= 600000L // 10 分钟
            }.sortedBy { it.expectedTime }
            
            if (upcomingTasks.isEmpty()) {
                return
            }
            
            val nearestTask = upcomingTasks.first()
            val timeUntil = nearestTask.expectedTime - currentTime
            val minutesUntil = timeUntil / 60000
            
            Log.record(TAG, "🔔 检测到即将执行的任务")
            Log.record(TAG, "任务 ID: ${nearestTask.taskId}")
            Log.record(TAG, "预期时间: ${TimeUtil.getCommonDate(nearestTask.expectedTime)}")
            Log.record(TAG, "距离执行: $minutesUntil 分钟")
            
            // 根据时间决定操作（优化版：减少屏幕唤醒，节省电量）
            when {
                timeUntil <= 30000 -> { // 30 秒内 - 最高优先级（只在最后 30 秒保持屏幕）
                    Log.record(TAG, "⏰ 任务即将执行（30秒内），保持屏幕+CPU")
                    keepAliveHelper?.preventScreenOff() // 仅阻止息屏，不主动唤醒
                    keepAliveHelper?.keepCpuAwake(timeUntil + 60000)
                    // 连续唤醒3次，确保进程活跃
                    repeat(3) {
                        AlipayMethodHelper.callWakeup()
                        AlipayMethodHelper.callPushBerserkerSetup()
                    }
                }
                timeUntil <= 120000 -> { // 30秒-2分钟内 - 高优先级（仅 CPU）
                    Log.record(TAG, "⏱️ 任务在 2 分钟内，保持 CPU 活跃")
                    keepAliveHelper?.keepCpuAwake(timeUntil + 30000)
                    repeat(2) {
                        AlipayMethodHelper.callWakeup()
                        AlipayMethodHelper.callPushBerserkerSetup()
                    }
                }
                timeUntil <= 300000 -> { // 2-5 分钟内 - 中优先级（仅 CPU）
                    Log.record(TAG, "📅 任务在 $minutesUntil 分钟内，保持 CPU")
                    keepAliveHelper?.keepCpuAwake(timeUntil)
                    AlipayMethodHelper.callWakeup()
                    AlipayMethodHelper.callPushBerserkerSetup()
                }
                timeUntil <= 600000 -> { // 5-10 分钟内 - 预防性唤醒（仅进程）
                    Log.record(TAG, "🔔 任务在 $minutesUntil 分钟内，预防性唤醒进程")
                    keepAliveHelper?.keepCpuAwake(300000L) // 保持5分钟 CPU
                    AlipayMethodHelper.callWakeup()
                }
            }
            
        } catch (e: Exception) {
            Log.error(TAG, "处理即将执行的任务异常: ${e.message}")
            Log.printStackTrace(TAG, e)
        }
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
                    val delayMinutes = (currentTime - record.expectedTime) / 60000
                    Log.runtime(TAG, "❌ 任务超时未执行: ${record.taskId}")
                    Log.runtime(TAG, "预期时间: ${TimeUtil.getCommonDate(record.expectedTime)}")
                    Log.runtime(TAG, "延迟时间: ${delayMinutes}分钟")

                    // 标记为异常延迟
                    adjustCompensation(expiryTime - record.expectedTime)
                    
                    // 连续延迟时，立即采取激进措施
                    if (consecutiveDelayCount >= 2) {
                        Log.record(TAG, "⚠️ 检测到连续延迟 $consecutiveDelayCount 次，触发紧急恢复！")
                        triggerEmergencyWakeup()
                        
                        // 强制重新初始化系统
                        if (consecutiveDelayCount >= 3) {
                            Log.record(TAG, "🔄 连续超时 $consecutiveDelayCount 次，强制重新初始化！")
                            forceReinitialize()
                        }
                    }
                }
                
                record.checked = true
                // 清理旧记录（保留最近 10 条用于分析）
                if (scheduledTasks.size > 10) {
                    iterator.remove()
                }
            } else if (record.actualTime != null) {
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
                    
                }
            }
            
            // 延迟很大（> 3 分钟）：建议切换 WorkManager
            else -> {
                consecutiveNormalCount = 0
                consecutiveDelayCount++
                
                if (currentCompensation < MAX_COMPENSATION) {
                    currentCompensation = MAX_COMPENSATION
                    Log.record(TAG, "❗ 延迟超过 3 分钟，使用最大补偿: ${currentCompensation / 1000}s")
                    
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
     * 启动支付宝唤醒任务
     */
    private fun startAlipayWakeup() {
        alipayWakeupJob = monitorScope.launch {
            Log.runtime(TAG, "🔔 支付宝唤醒任务已启动")
            Log.runtime(TAG, "唤醒间隔: ${ALIPAY_WAKEUP_INTERVAL / 1000}s")
            
            while (isActive && isRunning) {
                try {
                    AlipayMethodHelper.callWakeup()
                    AlipayMethodHelper.callPushBerserkerSetup()
                    AlipayMethodHelper.startPushServices()
                    delay(ALIPAY_WAKEUP_INTERVAL)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.error(TAG, "支付宝唤醒异常: ${e.message}")
                    Log.printStackTrace(TAG, e)
                }
            }
            
            Log.runtime(TAG, "🔔 支付宝唤醒任务已停止")
        }
    }
    
    /**
     * 触发紧急唤醒（连续延迟时采取激进措施）
     * 
     * 优化版：仅使用 CPU 唤醒，不强制屏幕常亮，减少电量消耗
     */
    private fun triggerEmergencyWakeup() {
        try {
            Log.record(TAG, "🚨 触发紧急唤醒模式（省电版）")
            
            // 1. CPU 保持唤醒 10 分钟
            keepAliveHelper?.keepCpuAwake(600000L)
            Log.record(TAG, "✅ CPU 保持唤醒 10 分钟")
            
            // 2. 连续调用支付宝唤醒方法 5 次
            repeat(5) {
                AlipayMethodHelper.callWakeup()
                AlipayMethodHelper.callPushBerserkerSetup()
                Thread.sleep(200) // 每次间隔 200ms
            }
            Log.record(TAG, "✅ 已连续唤醒进程 5 次")
            
            // 3. 启动所有推送服务
            AlipayMethodHelper.startPushServices()
            Log.record(TAG, "✅ 推送服务已启动")
            
            Log.record(TAG, "✅ 紧急唤醒完成（未开启屏幕常亮，省电）")
            
        } catch (e: Exception) {
            Log.error(TAG, "紧急唤醒失败: ${e.message}")
            Log.printStackTrace(TAG, e)
        }
    }
    
    /**
     * 强制重新初始化（连续超时3次时触发）
     */
    private fun forceReinitialize() {
        monitorScope.launch {
            try {
                Log.record(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.record(TAG, "🔄 开始强制重新初始化系统...")
                Log.record(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                var waitCount = 0
                while (ApplicationHook.getIsTaskRunning() && waitCount < 60) {
                    delay(1000)
                    waitCount++
                }
                
                if (waitCount >= 60) {
                    Log.record(TAG, "⚠️ 等待超时，强制继续初始化")
                } else if (waitCount > 0) {
                    Log.record(TAG, "✅ 任务已完成，继续初始化")
                }

                triggerEmergencyWakeup()
                delay(1000) // 等待1秒

                // 2. 清空所有调度记录
                scheduledTasks.clear()
                consecutiveDelayCount = 0
                consecutiveNormalCount = 0

                // 3. 重置补偿值
                currentCompensation = 120000L // 重置为2分钟
                SmartSchedulerManager.resetCompensation()
                
                // 4. 立即执行任务并重新调度
                try {
                    // 4.1 立即执行一次任务
                    ApplicationHook.executeByBroadcast()
                    delay(2000) // 等待2秒让任务执行
                    
                    // 4.2 重新调度下一次任务
                    ApplicationHook.scheduleNextExecution()
                } catch (e: Exception) {
                    Log.error(TAG, "执行任务或重新调度失败: ${e.message}")
                    Log.printStackTrace(TAG, e)
                }
                // 5. 重新启动保活机制
                keepAliveHelper?.stop()
                delay(500)
                keepAliveHelper?.start()
                
                Log.record(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.record(TAG, "✅ 系统重新初始化完成！")
                Log.record(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            } catch (e: Exception) {
                Log.error(TAG, "强制重新初始化失败: ${e.message}")
                Log.printStackTrace(TAG, e)
            }
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stopMonitoring()
        keepAliveHelper?.cleanup()
        keepAliveHelper = null
        monitorScope.cancel()
        scheduledTasks.clear()
        consecutiveDelayCount = 0
        Log.runtime(TAG, "监控器资源已清理")
    }
}

