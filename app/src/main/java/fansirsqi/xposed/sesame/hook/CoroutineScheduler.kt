package fansirsqi.xposed.sesame.hook

import android.content.Context
import android.content.Intent
import fansirsqi.xposed.sesame.data.General
import fansirsqi.xposed.sesame.hook.keepalive.SmartSchedulerManager
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 协程调度器 - 使用协程实现精确定时（纯协程版）
 * 
 * 优势：
 * 1. 精确到毫秒级 - 不受系统省电策略影响
 * 2. 轻量高效 - 无系统调度开销
 * 3. 灵活控制 - 可随时调整间隔
 * 4. 零唤醒锁 - 极低功耗
 * 
 * 注意：
 * 1. 需要进程保活（前台服务）
 * 2. 建议加入电池优化白名单
 */
class CoroutineScheduler(private val context: Context) {

    companion object {
        private const val TAG = "CoroutineScheduler"
    }

    // 调度器协程作用域
    private val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 主任务调度 Job
    private var mainTaskJob: Job? = null
    
    // ✅ 唤醒任务调度 Jobs（线程安全版）
    private val wakeupJobs = ConcurrentHashMap<Int, Job>()
    
    // 调度器运行状态
    private val isRunning = AtomicBoolean(false)
    
    // 下次执行时间
    private val nextExecutionTime = AtomicLong(0)

    /**
     * 启动主任务调度（优化版）
     * 
     * @param initialDelay 初始延迟（毫秒）
     * @param targetTime 目标执行时间戳（0表示使用间隔）
     * 
     * 优化：合并日志输出减少 I/O
     */
    fun scheduleMainTask(initialDelay: Long, targetTime: Long = 0) {
        // 取消旧任务
        mainTaskJob?.cancel()
        
        // 使用传入的目标时间，如果没有则使用当前时间+延迟
        val actualTargetTime = if (targetTime > 0) targetTime else (System.currentTimeMillis() + initialDelay)
        nextExecutionTime.set(actualTargetTime)
        
        mainTaskJob = schedulerScope.launch {
            try {
                // 初始延迟
                if (initialDelay > 0) {
                    // ✅ 日志优化：合并为一行
                    Log.record(TAG, "⏰ 主任务将在 ${initialDelay / 1000}s 后执行 | 预定: ${TimeUtil.getCommonDate(actualTargetTime)}")
                    delay(initialDelay)
                }
                
                // 执行任务
                if (isActive) {
                    executeMainTask()
                }
                
            } catch (e: CancellationException) {
                Log.debug(TAG, "主任务调度已取消")
                throw e
            } catch (e: Exception) {
                Log.error(TAG, "主任务调度异常: ${e.message}")
                Log.printStackTrace(TAG, e)
            }
        }
        
        isRunning.set(true)
    }

    /**
     * 调度精确时间执行（优化版）
     * 
     * @param delayMillis 延迟时间（毫秒，已在外层补偿）
     * @param exactTimeMillis 精确执行时间戳
     * 
     * 优化：合并日志输出减少 I/O（3行 → 1行）
     */
    fun scheduleExactExecution(delayMillis: Long, exactTimeMillis: Long) {
        scheduleMainTask(delayMillis, exactTimeMillis)
        
        // ✅ 日志优化：合并为一行
        Log.record(TAG, "⏰ 已调度精确执行（协程）| 预定: ${TimeUtil.getCommonDate(exactTimeMillis)} | 延迟: ${delayMillis / 1000}s")
    }

    /**
     * 调度延迟执行
     * 
     * @param delayMillis 延迟时间（毫秒）
     */
    fun scheduleDelayedExecution(delayMillis: Long) {
        scheduleMainTask(delayMillis)
    }

    /**
     * 调度唤醒任务（0点定时，优化版）
     * 
     * @param triggerAtMillis 触发时间戳
     * @param requestCode 请求码
     * @param isMainAlarm 是否为主任务
     * @return 是否调度成功
     * 
     * 优化：
     * 1. 使用 ConcurrentHashMap（线程安全）
     * 2. 合并日志输出
     */
    fun scheduleWakeupAlarm(
        triggerAtMillis: Long,
        requestCode: Int,
        isMainAlarm: Boolean
    ): Boolean {
        return try {
            // ✅ ConcurrentHashMap 线程安全，可直接操作
            wakeupJobs[requestCode]?.cancel()
            
            val currentTime = System.currentTimeMillis()
            val delayMillis = (triggerAtMillis - currentTime).coerceAtLeast(0)
            
            val job = schedulerScope.launch {
                try {
                    // 延迟到指定时间
                    if (delayMillis > 0) {
                        delay(delayMillis)
                    }
                    
                    // 执行唤醒任务
                    if (isActive) {
                        executeWakeupTask(triggerAtMillis, isMainAlarm)
                    }
                    
                } catch (e: CancellationException) {
                    Log.debug(TAG, "唤醒任务[$requestCode]已取消")
                    throw e
                } catch (e: Exception) {
                    Log.error(TAG, "唤醒任务[$requestCode]异常: ${e.message}")
                    Log.printStackTrace(TAG, e)
                } finally {
                    // 执行完成后移除
                    wakeupJobs.remove(requestCode)
                }
            }
            
            wakeupJobs[requestCode] = job
            
            // ✅ 日志优化：合并为一行
            val taskType = if (isMainAlarm) "主定时" else "自定义定时"
            Log.record(TAG, "⏰ ${taskType}任务调度成功（协程）| ID=$requestCode | 触发: ${TimeUtil.getCommonDate(triggerAtMillis)}")
            
            true
            
        } catch (e: Exception) {
            Log.error(TAG, "调度唤醒任务失败: ${e.message}")
            Log.printStackTrace(TAG, e)
            false
        }
    }

    /**
     * 执行主任务（优化版）
     * 
     * 优化：合并日志输出减少 I/O（4行 → 1行）
     */
    private fun executeMainTask() {
        try {
            val actualTime = System.currentTimeMillis()
            val expectedTime = nextExecutionTime.get()
            val deviation = actualTime - expectedTime
            
            // 记录延迟到智能管理器
            if (expectedTime > 0) {
                SmartSchedulerManager.recordDelay(expectedTime, actualTime)
            }
            
            val intent = Intent(TaskConstants.ACTION_EXECUTE).apply {
                putExtra("alarm_triggered", true)
                putExtra("execution_time", expectedTime)
                putExtra("scheduled_at", actualTime)
                putExtra("from_coroutine_scheduler", true)
                setPackage(General.PACKAGE_NAME)
            }
            
            context.sendBroadcast(intent)
            
            // ✅ 日志优化：合并为一行
            val deviationStatus = if (deviation > 0) "延迟" else "提前"
            Log.record(TAG, "⏰ 主任务已触发（协程）| 预定: ${TimeUtil.getCommonDate(expectedTime)} | " +
                "偏差: ${deviation}ms ($deviationStatus)")
            
        } catch (e: Exception) {
            Log.error(TAG, "执行主任务失败: ${e.message}")
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 执行唤醒任务（优化版）
     * 
     * 优化：合并日志输出减少 I/O（4行 → 1行）
     */
    private fun executeWakeupTask(triggerTime: Long, isMainAlarm: Boolean) {
        try {
            val intent = Intent(TaskConstants.ACTION_EXECUTE).apply {
                putExtra("alarm_triggered", true)
                putExtra("waken_at_time", true)
                if (!isMainAlarm) {
                    putExtra("waken_time", TimeUtil.getTimeStr(triggerTime))
                }
                putExtra("from_coroutine_scheduler", true)
                setPackage(General.PACKAGE_NAME)
            }
            
            context.sendBroadcast(intent)
            
            val taskType = if (isMainAlarm) "0点唤醒" else "自定义唤醒"
            val actualTime = System.currentTimeMillis()
            val deviation = actualTime - triggerTime
            
            // ✅ 日志优化：合并为一行
            val deviationStatus = if (deviation > 0) "延迟" else "提前"
            Log.record(TAG, "⏰ ${taskType}任务已触发（协程）| 预定: ${TimeUtil.getCommonDate(triggerTime)} | " +
                "偏差: ${deviation}ms ($deviationStatus)")
            
        } catch (e: Exception) {
            Log.error(TAG, "执行唤醒任务失败: ${e.message}")
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 取消所有唤醒任务
     */
    fun cancelAllWakeupAlarms() {
        wakeupJobs.values.forEach { it.cancel() }
        wakeupJobs.clear()
        Log.record(TAG, "已取消所有唤醒任务")
    }

    /**
     * 取消所有任务
     */
    fun cancelAll() {
        mainTaskJob?.cancel()
        mainTaskJob = null
        cancelAllWakeupAlarms()
        isRunning.set(false)
        Log.record(TAG, "已取消所有协程调度任务")
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            Log.record(TAG, "🧹 开始清理协程调度器资源")
            cancelAll()
            schedulerScope.cancel()
            Log.record(TAG, "✅ 协程调度器资源清理完成")
        } catch (e: Exception) {
            Log.error(TAG, "❌ 清理协程调度器资源失败: ${e.message}")
        }
    }

}

