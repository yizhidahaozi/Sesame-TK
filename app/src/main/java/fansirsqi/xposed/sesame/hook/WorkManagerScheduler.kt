package fansirsqi.xposed.sesame.hook

import android.content.Context
import androidx.work.*
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/**
 * WorkManager 调度器 - 完全替代 AlarmManager
 * 
 * 优势：
 * 1. 无系统任务数量限制
 * 2. 自动处理应用重启
 * 3. 系统级优化，省电
 * 4. 支持约束条件
 * 5. 自动重试机制
 * 
 * 功能：
 * 1. 延迟执行任务
 * 2. 精确时间执行任务
 * 3. 定时唤醒任务
 * 4. 任务状态查询
 */
class WorkManagerScheduler(private val context: Context) {

    companion object {
        private const val TAG = "WorkManagerScheduler"
        
        // 工作任务唯一名称
        private const val WORK_MAIN_TASK = "sesame_main_task"
        private const val WORK_WAKEUP_PREFIX = "sesame_wakeup_"
        private const val WORK_EXACT_PREFIX = "sesame_exact_"
        
        /**
         * 初始化 WorkManager
         * 必须在第一次使用前调用
         */
        @JvmStatic
        fun initializeWorkManager(context: Context) {
            try {
                // 检查是否已经初始化
                try {
                    WorkManager.getInstance(context)
                    Log.debug(TAG, "WorkManager 已经初始化")
                    return
                } catch (e: IllegalStateException) {
                    // 未初始化，继续初始化
                }
                
                // 使用 applicationContext 并包装以避免资源冲突
                val appContext = context.applicationContext
                val safeContext = SafeContextWrapper(appContext)
                
                // 创建自定义配置 - 完全手动配置避免读取资源
                val configuration = Configuration.Builder()
                    .setMinimumLoggingLevel(android.util.Log.INFO)
                    .setExecutor(Executors.newFixedThreadPool(4))
                    .setTaskExecutor(Executors.newSingleThreadExecutor())
                    // 设置所有可能从资源读取的配置项
                    .setJobSchedulerJobIdRange(0, 1000)
                    .build()
                
                // 手动初始化 WorkManager，使用安全包装的 Context
                WorkManager.initialize(safeContext, configuration)
                Log.record(TAG, "✅ WorkManager 已成功初始化")
            } catch (e: Exception) {
                Log.error(TAG, "❌ WorkManager 初始化失败: ${e.message}")
                Log.printStackTrace(TAG, e)
                throw e
            }
        }
    }

    private val workManager: WorkManager by lazy {
        // 确保 WorkManager 已初始化
        val appContext = context.applicationContext
        initializeWorkManager(appContext)
        WorkManager.getInstance(appContext)
    }

    /**
     * 调度延迟执行任务
     * 
     * @param delayMillis 延迟时间（毫秒）
     * @return 任务唯一标识
     */
    fun scheduleDelayedExecution(delayMillis: Long): String {
        val taskId = WORK_MAIN_TASK
        val executionTime = System.currentTimeMillis() + delayMillis
        
        // 创建输入数据
        val inputData = workDataOf(
            TaskExecutionWorker.KEY_TASK_TYPE to TaskExecutionWorker.TASK_TYPE_DELAYED,
            TaskExecutionWorker.KEY_EXECUTION_TIME to executionTime,
            TaskExecutionWorker.KEY_REQUEST_CODE to generateRequestCode(executionTime),
            TaskExecutionWorker.KEY_IS_WAKEUP_ALARM to false
        )
        
        // 创建工作请求
        val workRequest = OneTimeWorkRequestBuilder<TaskExecutionWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10, TimeUnit.SECONDS
            )
            .addTag(taskId)
            .build()
        
        // 使用 REPLACE 策略，确保只有一个主任务
        workManager.enqueueUniqueWork(
            taskId,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        
        Log.record(TAG, "⏰ 已调度延迟执行: 延迟 ${delayMillis / 1000} 秒")
        Log.record(TAG, "预定时间: ${TimeUtil.getCommonDate(executionTime)}")
        
        return taskId
    }

    /**
     * 调度精确时间执行任务
     * 
     * @param delayMillis 延迟时间（毫秒）
     * @param exactTimeMillis 精确执行时间戳
     * @return 任务唯一标识
     */
    fun scheduleExactExecution(delayMillis: Long, exactTimeMillis: Long): String {
        val taskId = "${WORK_EXACT_PREFIX}${exactTimeMillis}"
        
        // 创建输入数据
        val inputData = workDataOf(
            TaskExecutionWorker.KEY_TASK_TYPE to TaskExecutionWorker.TASK_TYPE_EXACT,
            TaskExecutionWorker.KEY_EXECUTION_TIME to exactTimeMillis,
            TaskExecutionWorker.KEY_REQUEST_CODE to generateRequestCode(exactTimeMillis),
            TaskExecutionWorker.KEY_IS_WAKEUP_ALARM to true
        )
        
        // 创建工作请求
        val workRequest = OneTimeWorkRequestBuilder<TaskExecutionWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10, TimeUnit.SECONDS
            )
            .addTag(taskId)
            .build()
        
        // 使用 REPLACE 策略
        workManager.enqueueUniqueWork(
            taskId,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        
        Log.record(TAG, "⏰ 已调度精确执行: 时间 ${TimeUtil.getCommonDate(exactTimeMillis)}")
        Log.record(TAG, "延迟: ${delayMillis / 1000} 秒")
        
        return taskId
    }

    /**
     * 调度定时唤醒任务
     * 
     * @param triggerAtMillis 触发时间戳
     * @param requestCode 请求码
     * @param isMainAlarm 是否为主任务（0点唤醒）
     * @return 是否调度成功
     */
    fun scheduleWakeupAlarm(
        triggerAtMillis: Long, 
        requestCode: Int, 
        isMainAlarm: Boolean
    ): Boolean {
        return try {
            val currentTime = System.currentTimeMillis()
            val delayMillis = (triggerAtMillis - currentTime).coerceAtLeast(0)
            val taskId = "${WORK_WAKEUP_PREFIX}${requestCode}"
            
            // 创建输入数据
            val inputData = workDataOf(
                TaskExecutionWorker.KEY_TASK_TYPE to TaskExecutionWorker.TASK_TYPE_WAKEUP,
                TaskExecutionWorker.KEY_EXECUTION_TIME to triggerAtMillis,
                TaskExecutionWorker.KEY_REQUEST_CODE to requestCode,
                TaskExecutionWorker.KEY_IS_WAKEUP_ALARM to true,
                TaskExecutionWorker.KEY_IS_MAIN_ALARM to isMainAlarm,
                TaskExecutionWorker.KEY_WAKEN_TIME to if (isMainAlarm) null else TimeUtil.getTimeStr(triggerAtMillis)
            )
            
            // 创建工作请求
            val workRequest = OneTimeWorkRequestBuilder<TaskExecutionWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10, TimeUnit.SECONDS
                )
                .addTag(taskId)
                .addTag("wakeup")
                .build()
            
            // 使用 REPLACE 策略
            workManager.enqueueUniqueWork(
                taskId,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            
            val taskType = if (isMainAlarm) "主定时任务" else "自定义定时任务"
            Log.record(TAG, "⏰ ${taskType}调度成功: ID=$requestCode")
            Log.record(TAG, "触发时间: ${TimeUtil.getCommonDate(triggerAtMillis)}")
            
            true
            
        } catch (e: Exception) {
            Log.error(TAG, "调度唤醒任务失败: ${e.message}")
            Log.printStackTrace(TAG, e)
            false
        }
    }

    /**
     * 取消指定任务
     * 
     * @param taskId 任务唯一标识
     */
    fun cancelWork(taskId: String) {
        try {
            workManager.cancelUniqueWork(taskId)
            Log.record(TAG, "已取消任务: $taskId")
        } catch (e: Exception) {
            Log.error(TAG, "取消任务失败: ${e.message}")
        }
    }

    /**
     * 取消所有唤醒任务
     */
    fun cancelAllWakeupAlarms() {
        try {
            workManager.cancelAllWorkByTag("wakeup")
            Log.record(TAG, "已取消所有唤醒任务")
        } catch (e: Exception) {
            Log.error(TAG, "取消唤醒任务失败: ${e.message}")
        }
    }

    /**
     * 取消所有任务
     */
    fun cancelAll() {
        try {
            workManager.cancelAllWork()
            Log.record(TAG, "已取消所有 WorkManager 任务")
        } catch (e: Exception) {
            Log.error(TAG, "取消所有任务失败: ${e.message}")
        }
    }

    /**
     * 检查是否有任务正在运行或排队
     * 
     * @return true 表示有活跃任务
     */
    fun hasActiveWork(): Boolean {
        return try {
            val workInfos = workManager.getWorkInfosByTag(WORK_MAIN_TASK).get()
            workInfos.any { 
                it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED 
            }
        } catch (e: Exception) {
            Log.error(TAG, "检查活跃任务失败: ${e.message}")
            false
        }
    }

    /**
     * 生成请求码
     */
    private fun generateRequestCode(timeMillis: Long): Int {
        return (timeMillis % 10000 * 10 + kotlin.random.Random.nextInt(10)).toInt()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            Log.record(TAG, "🧹 开始清理 WorkManager 资源")
            cancelAll()
            Log.record(TAG, "✅ WorkManager 资源清理完成")
        } catch (e: Exception) {
            Log.error(TAG, "❌ 清理 WorkManager 资源失败: ${e.message}")
        }
    }
}

