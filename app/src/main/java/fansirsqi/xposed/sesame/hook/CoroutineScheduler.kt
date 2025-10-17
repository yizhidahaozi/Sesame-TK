package fansirsqi.xposed.sesame.hook

import android.content.Context
import android.os.PowerManager
import fansirsqi.xposed.sesame.hook.keepalive.AlipayComponentHelper
import fansirsqi.xposed.sesame.task.BaseTask
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.Notify
import fansirsqi.xposed.sesame.util.TimeUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 纯协程调度器（无闹钟版本）
 * 
 * ⚠️ 注意限制：
 * 1. 息屏时可能被系统挂起
 * 2. 进程被杀后无法自动恢复
 * 3. Doze 模式下会被冻结
 * 
 * 优势：
 * 1. 无闹钟泄漏问题
 * 2. 代码更简洁
 * 3. 配合 AlipayComponentHelper 可提升可靠性
 */
class CoroutineScheduler(private val context: Context) {
    
    private val isTaskExecutionPending = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    
    // 协程相关
    private val schedulerScope = CoroutineScope(
        SupervisorJob() +
        Dispatchers.Default +
        CoroutineName("CoroutineSchedulerScope")
    )
    private val executionMutex = Mutex()
    
    // 存储调度任务的 Job
    private val scheduledJobs = mutableMapOf<String, Job>()
    
    /**
     * 调度延迟执行任务
     */
    fun scheduleDelayedExecution(delayMillis: Long) {
        // 取消之前的任务
        scheduledJobs.values.forEach { it.cancel() }
        scheduledJobs.clear()
        
        isTaskExecutionPending.set(true)
        
        val executionTime = System.currentTimeMillis() + delayMillis
        
        // 主任务
        val mainJob = schedulerScope.launch {
            try {
                Log.record(TAG, "已调度任务，延迟: ${delayMillis / 1000}秒, 执行时间: ${TimeUtil.getTimeStr(executionTime)}")
                
                delay(delayMillis)
                
                if (isActive && isTaskExecutionPending.compareAndSet(true, false)) {
                    executeTask()
                }
            } catch (_: CancellationException) {
                Log.record(TAG, "任务被取消")
            } catch (e: Exception) {
                Log.error(TAG, "任务调度失败: ${e.message}")
            }
        }
        scheduledJobs["main"] = mainJob
        
        // 备份任务 1（多 15 秒）
        val backup1Job = schedulerScope.launch {
            try {
                delay(delayMillis + 15000)
                if (isActive && isTaskExecutionPending.compareAndSet(true, false)) {
                    Log.record(TAG, "备份任务1触发")
                    executeTask()
                }
            } catch (_: CancellationException) {
                // 正常取消
            }
        }
        scheduledJobs["backup1"] = backup1Job
        
        // 备份任务 2（多 35 秒）
        val backup2Job = schedulerScope.launch {
            try {
                delay(delayMillis + 35000)
                if (isActive && isTaskExecutionPending.compareAndSet(true, false)) {
                    Log.record(TAG, "备份任务2触发")
                    executeTask()
                }
            } catch (_: CancellationException) {
                // 正常取消
            }
        }
        scheduledJobs["backup2"] = backup2Job
        
        // 更新通知
        updateNotification(executionTime)
    }
    
    /**
     * 执行任务
     */
    private suspend fun executeTask() = withContext(Dispatchers.Main) {
        executionMutex.withLock {
            try {
                Log.record(TAG, "开始执行任务")
                // ✅ 唤醒支付宝进程
                try {
                    AlipayComponentHelper(context).wakeupAlipayLite()
                    Log.record(TAG, "已唤醒支付宝进程")
                } catch (e: Exception) {
                    Log.error(TAG, "唤醒支付宝失败: ${e.message}")
                }
                
                // 获取唤醒锁
                acquireWakeLock()
                
                // 通过反射调用 ApplicationHook 的方法
                val appHookClass = Class.forName("fansirsqi.xposed.sesame.hook.ApplicationHook")
                val getTaskMethod = appHookClass.getDeclaredMethod("getMainTask")
                getTaskMethod.isAccessible = true
                val mainTask = getTaskMethod.invoke(null)
                
                // 检查主任务是否已在运行
                if (mainTask is BaseTask) {
                    val taskThread = mainTask.thread
                    if (taskThread?.isAlive == true) {
                        Log.record(TAG, "主任务正在运行，跳过执行")
                        return@withLock
                    }
                }
                
                Log.record(TAG, "触发任务重启")
                val restartMethod = appHookClass.getDeclaredMethod("restartByBroadcast")
                restartMethod.isAccessible = true
                restartMethod.invoke(null)
                Log.record(TAG, "任务触发完成")
                
            } catch (e: Exception) {
                Log.error(TAG, "执行任务失败: ${e.message}")
            }
        }
    }
    
    /**
     * 获取唤醒锁
     */
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sesame:CoroutineWakeLock")
                wakeLock?.setReferenceCounted(false)
            }
            
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(15 * 60 * 1000L) // 15 分钟超时
                Log.record(TAG, "已获取唤醒锁")
            }
        } catch (e: Exception) {
            Log.error(TAG, "获取唤醒锁失败: ${e.message}")
        }
    }
    
    /**
     * 释放唤醒锁
     */
    fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
                Log.record(TAG, "唤醒锁已释放")
            } catch (e: Exception) {
                Log.error(TAG, "释放唤醒锁失败: ${e.message}")
            }
        }
    }
    
    /**
     * 更新通知
     */
    private fun updateNotification(executionTime: Long) {
        val nt = "⏰ 下次执行(协程) ${TimeUtil.getTimeStr(executionTime)}"
        Notify.updateNextExecText(executionTime)
        Toast.show(nt)
        Log.record(TAG, nt)
    }
    
    /**
     * 取消所有任务
     */
    fun cancelAllTasks() {
        scheduledJobs.values.forEach { it.cancel() }
        scheduledJobs.clear()
        isTaskExecutionPending.set(false)
        Log.record(TAG, "所有任务已取消")
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            cancelAllTasks()
            schedulerScope.cancel()
            releaseWakeLock()
            Log.record(TAG, "协程调度器资源已清理")
        } catch (e: Exception) {
            Log.error(TAG, "清理资源失败: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CoroutineScheduler"
    }
}

