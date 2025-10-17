package fansirsqi.xposed.sesame.hook

import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 闹钟备份机制管理器
 * 
 * 职责：
 * 1. 管理多级协程备份任务
 * 2. 管理备份闹钟
 * 3. 控制任务执行状态
 * 4. 在主闹钟失效时触发备份机制
 *
 * 设计思路：
 * - 三级备份：第一级协程备份 → 第二级协程备份 → 备份闹钟
 * - 原子操作：使用 AtomicBoolean 确保只执行一次
 * - 自动清理：提供统一的清理接口
 */
class AlarmBackupManager(
    private val alarmScope: CoroutineScope,
    private val taskExecutor: TaskExecutor?
) {
    // 存储备份任务的 Job
    private val backupJobs = ConcurrentHashMap<String, Job>()
    
    // 任务执行待处理标志
    private val isTaskExecutionPending = AtomicBoolean(false)

    /**
     * 备份延迟常量
     */
    object BackupDelays {
        const val FIRST_BACKUP_DELAY = 15000L   // 15秒，第一级协程备份
        const val SECOND_BACKUP_DELAY = 35000L  // 35秒，第二级协程备份
    }

    /**
     * 设置任务待处理状态
     */
    fun setTaskPending(pending: Boolean) {
        isTaskExecutionPending.set(pending)
    }

    /**
     * 调度备份机制
     * @param delayMillis 基础延迟时间
     */
    fun scheduleBackups(
        delayMillis: Long
    ) {
        val backupKey = "backup_${System.currentTimeMillis()}"
        
        // 取消之前的备份任务
        cancelAllBackups()
        
        // 1. 第一级协程备份
        backupJobs["${backupKey}_first"] = createBackupJob(
            delayMillis = delayMillis,
            additionalDelay = BackupDelays.FIRST_BACKUP_DELAY,
            levelName = "第一级"
        )

        // 2. 第二级协程备份
        backupJobs["${backupKey}_second"] = createBackupJob(
            delayMillis = delayMillis,
            additionalDelay = BackupDelays.SECOND_BACKUP_DELAY,
            levelName = "第二级"
        )
        
        Log.record(TAG, "已设置两级协程备份机制")
    }

    /**
     * 创建备份任务协程
     */
    private fun createBackupJob(
        delayMillis: Long,
        additionalDelay: Long,
        levelName: String
    ): Job = alarmScope.launch {
        delay(delayMillis + additionalDelay)
        if (isActive && isTaskExecutionPending.compareAndSet(true, false)) {
            Log.record(TAG, "${levelName}协程备份触发，时间: ${TimeUtil.getTimeStr(System.currentTimeMillis())}")
            executeBackupTask()
        }
    }

    /**
     * 执行备份任务
     */
    private suspend fun executeBackupTask() = withContext(Dispatchers.Main) {
        if (taskExecutor == null) {
            Log.error(TAG, "TaskExecutor 未设置，无法执行备份任务")
            return@withContext
        }

        try {
            // 检查主任务是否已在运行
            if (taskExecutor.isTaskRunning()) {
                Log.record(TAG, "主任务正在运行，备份任务跳过执行")
                return@withContext
            }

            Log.record(TAG, "通过协程备份重启任务")
            taskExecutor.restartTask()
            Log.record(TAG, "协程备份任务触发完成")
        } catch (e: Exception) {
            Log.error(TAG, "执行协程备份任务失败: ${e.message}")
        }
    }

    /**
     * 处理闹钟触发
     * 当主闹钟或备份闹钟触发时调用
     */
    fun handleAlarmTrigger() {
        if (isTaskExecutionPending.compareAndSet(true, false)) {
            Log.record(TAG, "闹钟触发，标记任务为已触发")
        } else {
            Log.record(TAG, "闹钟触发，但任务已由其他机制触发")
        }
    }

    /**
     * 取消所有备份任务
     */
    fun cancelAllBackups() {
        val activeCount = backupJobs.values.count { it.isActive }
        backupJobs.values.forEach { job ->
            if (job.isActive) {
                job.cancel("AlarmBackupManager cleanup")
            }
        }
        backupJobs.clear()
        
        if (activeCount > 0) {
            Log.record(TAG, "已取消 $activeCount 个备份任务")
        }
    }

    /**
     * 获取备份任务状态
     */
    fun getStatus(): String {
        val activeJobs = backupJobs.values.count { it.isActive }
        val completedJobs = backupJobs.values.count { it.isCompleted }
        val pending = isTaskExecutionPending.get()
        
        return "备份状态: 待处理=$pending, 活跃任务=$activeJobs, 完成任务=$completedJobs"
    }

    companion object {
        private const val TAG = "AlarmBackup"
    }
}

