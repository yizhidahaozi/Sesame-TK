package fansirsqi.xposed.sesame.hook

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil

/**
 * JobScheduler 调度助手
 * 
 * 使用系统 JobScheduler 服务进行任务调度
 * 优势：
 * 1. 系统级服务，支持所有进程（包括子进程）
 * 2. 无任务数量限制
 * 3. 省电优化
 * 4. 支持精确时间调度（Android 8.0+）
 */
object JobSchedulerHelper {
    
    private const val TAG = "JobSchedulerHelper"
    
    // Job ID 范围
    private const val JOB_ID_MAIN_TASK = 10001
    private const val JOB_ID_WAKEUP_BASE = 20000
    
    /**
     * 调度主任务
     * 
     * @param context 上下文
     * @param delayMillis 延迟时间（毫秒）
     * @param exactTimeMillis 精确执行时间戳（用于日志）
     * @return 是否调度成功
     */
    fun scheduleMainTask(context: Context, delayMillis: Long, exactTimeMillis: Long): Boolean {
        return try {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            
            // 创建 JobInfo
            val jobInfo = JobInfo.Builder(
                JOB_ID_MAIN_TASK,
                ComponentName(context, TaskJobService::class.java)
            ).apply {
                // 设置延迟执行
                setMinimumLatency(delayMillis)
                setOverrideDeadline(delayMillis + 30000) // 最多延迟30秒
                
                // 设置网络条件（支付宝任务需要网络）
                setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)

                // 持久化任务（设备重启后保留）
                setPersisted(true)
                
                // 传递数据
                val extras = android.os.PersistableBundle().apply {
                    putString("task_type", "main_task")
                    putLong("execution_time", exactTimeMillis)
                    putBoolean("alarm_triggered", true)
                }
                setExtras(extras)
            }.build()
            
            val result = jobScheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.record(TAG, "⏰ JobScheduler 主任务调度成功")
                Log.record(TAG, "预定时间: ${TimeUtil.getCommonDate(exactTimeMillis)}")
                Log.record(TAG, "延迟: ${delayMillis / 1000} 秒")
                true
            } else {
                Log.error(TAG, "❌ JobScheduler 主任务调度失败")
                false
            }
        } catch (e: Exception) {
            Log.error(TAG, "JobScheduler 调度异常: ${e.message}")
            Log.printStackTrace(TAG, e)
            false
        }
    }
    
    /**
     * 调度唤醒任务（0点定时）
     * 
     * @param context 上下文
     * @param triggerAtMillis 触发时间戳
     * @param requestCode 请求码
     * @param isMainAlarm 是否为主任务（0点唤醒）
     * @return 是否调度成功
     */
    fun scheduleWakeupTask(
        context: Context,
        triggerAtMillis: Long,
        requestCode: Int,
        isMainAlarm: Boolean
    ): Boolean {
        return try {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val delayMillis = (triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
            val jobId = JOB_ID_WAKEUP_BASE + requestCode
            
            val jobInfo = JobInfo.Builder(
                jobId,
                ComponentName(context, TaskJobService::class.java)
            ).apply {
                setMinimumLatency(delayMillis)
                setOverrideDeadline(delayMillis + 60000) // 最多延迟1分钟

                setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)

                setPersisted(true)
                
                val extras = android.os.PersistableBundle().apply {
                    putString("task_type", "wakeup_task")
                    putString("waken_time", TimeUtil.getTimeStr(triggerAtMillis))
                    putBoolean("waken_at_time", true)
                    putBoolean("alarm_triggered", true)
                }
                setExtras(extras)
            }.build()
            
            val result = jobScheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                val taskType = if (isMainAlarm) "主定时任务" else "自定义定时任务"
                Log.record(TAG, "⏰ ${taskType}调度成功: ID=$requestCode")
                Log.record(TAG, "触发时间: ${TimeUtil.getCommonDate(triggerAtMillis)}")
                true
            } else {
                Log.error(TAG, "❌ 唤醒任务调度失败")
                false
            }
        } catch (e: Exception) {
            Log.error(TAG, "JobScheduler 唤醒任务调度异常: ${e.message}")
            Log.printStackTrace(TAG, e)
            false
        }
    }

    /**
     * 取消所有唤醒任务
     */
    fun cancelAllWakeupTasks(context: Context) {
        try {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            // 取消所有唤醒任务（ID范围：20000-29999）
            for (jobId in JOB_ID_WAKEUP_BASE until JOB_ID_WAKEUP_BASE + 10000) {
                jobScheduler.cancel(jobId)
            }
            Log.record(TAG, "已取消所有唤醒任务")
        } catch (e: Exception) {
            Log.error(TAG, "取消唤醒任务失败: ${e.message}")
        }
    }

}

/**
 * JobService 实现
 * 用于执行调度的任务
 */
@SuppressLint("SpecifyJobSchedulerIdRange")
class TaskJobService : JobService() {
    
    companion object {
        private const val TAG = "TaskJobService"
    }
    
    override fun onStartJob(params: JobParameters): Boolean {
        Log.record(TAG, "⏰ JobService 任务开始执行, Job ID: ${params.jobId}")
        
        try {
            val extras = params.extras
            val taskType = extras.getString("task_type", "main_task")
            val alarmTriggered = extras.getBoolean("alarm_triggered", false)
            
            Log.record(TAG, "任务类型: $taskType")
            
            // 发送广播触发任务执行
            val intent = android.content.Intent(TaskConstants.ACTION_EXECUTE).apply {
                putExtra("alarm_triggered", alarmTriggered)
                putExtra("from_job_scheduler", true)
                
                when (taskType) {
                    "main_task" -> {
                        val executionTime = extras.getLong("execution_time", 0)
                        putExtra("execution_time", executionTime)
                        putExtra("scheduled_at", System.currentTimeMillis())
                    }
                    "wakeup_task" -> {
                        putExtra("waken_at_time", extras.getBoolean("waken_at_time", false))
                        val wakenTime = extras.getString("waken_time")
                        if (wakenTime != null) {
                            putExtra("waken_time", wakenTime)
                        }
                    }
                }
                
                setPackage(fansirsqi.xposed.sesame.data.General.PACKAGE_NAME)
            }
            
            sendBroadcast(intent)
            Log.record(TAG, "✅ 已发送任务执行广播")
            
        } catch (e: Exception) {
            Log.error(TAG, "JobService 执行失败: ${e.message}")
            Log.printStackTrace(TAG, e)
        }
        
        // 返回 false 表示任务已完成，不需要重试
        jobFinished(params, false)
        return false
    }
    
    override fun onStopJob(params: JobParameters): Boolean {
        Log.record(TAG, "JobService 任务被停止")
        // 返回 true 表示需要重新调度
        return true
    }
}

