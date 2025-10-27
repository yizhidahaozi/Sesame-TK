package fansirsqi.xposed.sesame.hook

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import fansirsqi.xposed.sesame.data.General
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil

/**
 * WorkManager 任务执行 Worker
 * 
 * 用于替代 AlarmManager 进行后台任务调度
 * 支持：
 * 1. 延迟执行任务
 * 2. 定时执行任务
 * 3. 唤醒锁管理
 * 4. 自动重试机制
 */
class TaskExecutionWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "TaskExecutionWorker"
        
        // 输入参数 Key
        const val KEY_TASK_TYPE = "task_type"
        const val KEY_EXECUTION_TIME = "execution_time"
        const val KEY_REQUEST_CODE = "request_code"
        const val KEY_IS_WAKEUP_ALARM = "is_wakeup_alarm"
        const val KEY_IS_MAIN_ALARM = "is_main_alarm"
        const val KEY_WAKEN_TIME = "waken_time"
        
        // 任务类型
        const val TASK_TYPE_DELAYED = "delayed"
        const val TASK_TYPE_EXACT = "exact"
        const val TASK_TYPE_WAKEUP = "wakeup"
    }

    override suspend fun doWork(): Result {
        return try {
            // 获取唤醒锁，确保任务完成
            val wakeLock = acquireWakeLock()
            
            try {
                // 获取任务参数
                val taskType = inputData.getString(KEY_TASK_TYPE) ?: TASK_TYPE_DELAYED
                val executionTime = inputData.getLong(KEY_EXECUTION_TIME, System.currentTimeMillis())
                val requestCode = inputData.getInt(KEY_REQUEST_CODE, -1)
                val isWakeupAlarm = inputData.getBoolean(KEY_IS_WAKEUP_ALARM, false)
                
                Log.record(TAG, "⏰ WorkManager 任务开始执行")
                Log.record(TAG, "任务类型: $taskType, 请求码: $requestCode")
                Log.record(TAG, "预定时间: ${TimeUtil.getCommonDate(executionTime)}")
                
                // 根据任务类型执行
                when (taskType) {
                    TASK_TYPE_WAKEUP -> executeWakeupTask()
                    TASK_TYPE_EXACT, TASK_TYPE_DELAYED -> executeMainTask(isWakeupAlarm)
                }
                
                Log.record(TAG, "✅ WorkManager 任务执行完成")
                Result.success()
                
            } finally {
                // 释放唤醒锁
                releaseWakeLock(wakeLock)
            }
            
        } catch (e: Exception) {
            Log.error(TAG, "❌ WorkManager 任务执行失败: ${e.message}")
            Log.printStackTrace(TAG, e)
            
            // 根据运行次数决定是否重试
            if (runAttemptCount < 3) {
                Log.record(TAG, "⏳ 任务将重试 (第 ${runAttemptCount + 1} 次)")
                Result.retry()
            } else {
                Log.error(TAG, "❌ 任务重试次数超限，放弃执行")
                Result.failure()
            }
        }
    }

    /**
     * 执行主任务
     */
    private fun executeMainTask(isAlarmTriggered: Boolean) {
        try {
            val intent = Intent(TaskConstants.ACTION_EXECUTE).apply {
                putExtra("alarm_triggered", isAlarmTriggered)
                putExtra("execution_time", inputData.getLong(KEY_EXECUTION_TIME, 0))
                putExtra("request_code", inputData.getInt(KEY_REQUEST_CODE, -1))
                putExtra("scheduled_at", System.currentTimeMillis())
                putExtra("from_work_manager", true)
                setPackage(General.PACKAGE_NAME)
            }
            
            context.sendBroadcast(intent)
            Log.record(TAG, "已发送任务执行广播")
            
        } catch (e: Exception) {
            Log.error(TAG, "执行主任务失败: ${e.message}")
            throw e
        }
    }

    /**
     * 执行唤醒任务
     */
    private fun executeWakeupTask() {
        try {
            val intent = Intent(TaskConstants.ACTION_EXECUTE).apply {
                putExtra("alarm_triggered", true)
                putExtra("waken_at_time", true)
                val wakenTime = inputData.getString(KEY_WAKEN_TIME)
                if (wakenTime != null) {
                    putExtra("waken_time", wakenTime)
                }
                putExtra("from_work_manager", true)
                setPackage(General.PACKAGE_NAME)
            }
            
            context.sendBroadcast(intent)
            Log.record(TAG, "已发送唤醒任务广播")
            
        } catch (e: Exception) {
            Log.error(TAG, "执行唤醒任务失败: ${e.message}")
            throw e
        }
    }

    /**
     * 获取唤醒锁
     */
    private fun acquireWakeLock(): PowerManager.WakeLock? {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Sesame:WorkManager:${inputData.getInt(KEY_REQUEST_CODE, 0)}"
            )
            wakeLock.setReferenceCounted(false)
            wakeLock.acquire(15 * 60 * 1000L) // 最长持有 15 分钟
            
            Log.record(TAG, "🔓 已获取唤醒锁")
            wakeLock
            
        } catch (e: Exception) {
            Log.error(TAG, "获取唤醒锁失败: ${e.message}")
            null
        }
    }

    /**
     * 释放唤醒锁
     */
    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
                Log.record(TAG, "🔒 已释放唤醒锁")
            }
        } catch (e: Exception) {
            Log.error(TAG, "释放唤醒锁失败: ${e.message}")
        }
    }
}

