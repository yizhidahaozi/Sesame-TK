package fansirsqi.xposed.sesame.hook

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
// import android.os.Handler // 不再需要Handler
import android.os.PowerManager
import androidx.annotation.RequiresApi
import fansirsqi.xposed.sesame.data.General
import fansirsqi.xposed.sesame.newutil.DataStore
import fansirsqi.xposed.sesame.task.BaseTask
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.Notify
import fansirsqi.xposed.sesame.util.TimeUtil
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import androidx.core.net.toUri
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 统一的闹钟调度管理器（协程版本）
 *
 * 负责管理所有闹钟相关功能，包括：
 * 1. 闹钟的设置和取消
 * 2. 权限检查和处理
 * 3. 协程备份机制管理
 * 4. 唤醒锁管理
 * 
 * @param context Android上下文
 */
class AlarmScheduler(private val context: Context) {
    private val scheduledAlarms: MutableMap<Int, PendingIntent> = ConcurrentHashMap()
    private val isTaskExecutionPending = AtomicBoolean(false)
    
    // 协程相关
    private val alarmScope = CoroutineScope(
        SupervisorJob() + 
        Dispatchers.Default + 
        CoroutineName("AlarmSchedulerScope")
    )
    private val executionMutex = Mutex()
    
    // 存储备份任务的Job，用于取消
    private val backupJobs = ConcurrentHashMap<String, Job>()
    
    // 存储备份闹钟的请求码，用于追踪和取消
    private val backupAlarmRequestCodes = ConcurrentHashMap.newKeySet<Int>()
    
    // 调度计数器（用于定期诊断）
    private var scheduleCount = 0
    
    // 标记是否正在执行任务（用于优雅关闭）
    private val isExecutingTask = AtomicBoolean(false)

    /**
     * 闹钟相关常量
     */
    object Constants {
        const val WAKE_LOCK_SETUP_TIMEOUT = 5000L // 5秒
        const val FIRST_BACKUP_DELAY = 15000L // 15秒，协程版本可以更快响应
        const val SECOND_BACKUP_DELAY = 35000L // 35秒，优化备份时间
        const val BACKUP_ALARM_DELAY = 12000L // 12秒，更快的备份闹钟
        const val BACKUP_REQUEST_CODE_OFFSET = 10000
    }

    /**
     * 广播动作常量
     */
    object Actions {
        const val EXECUTE = "com.eg.android.AlipayGphone.sesame.execute"
        const val ALARM_CATEGORY = "fansirsqi.xposed.sesame.ALARM_CATEGORY"
    }

    /**
     * 设置延迟执行闹钟（简化版本）
     */
    fun scheduleDelayedExecution(delayMillis: Long) {
        val exactTimeMillis = System.currentTimeMillis() + delayMillis
        val requestCode = generateRequestCode(exactTimeMillis + 1) // +1避免与其他闹钟ID冲突
        val intent = createExecutionIntent(exactTimeMillis, requestCode).apply {
            putExtra("delayed_execution", true)
        }
        scheduleAlarmWithBackup(exactTimeMillis, intent, requestCode, delayMillis)
    }

    /**
     * 设置精确时间执行闹钟（完整版本）
     */
    fun scheduleExactExecution(delayMillis: Long, exactTimeMillis: Long) {
        // 检查权限
        if (!checkAndRequestAlarmPermissions()) {
            // 权限不足时回退到简化版本
            scheduleDelayedExecution(delayMillis)
            return
        }
        val requestCode = generateRequestCode(exactTimeMillis)
        val intent = createExecutionIntent(exactTimeMillis, requestCode)
        scheduleAlarmWithBackup(exactTimeMillis, intent, requestCode, delayMillis)
    }

    /**
     * 设置定时唤醒闹钟
     */
    fun scheduleWakeupAlarm(triggerAtMillis: Long, requestCode: Int, isMainAlarm: Boolean): Boolean {
        val intent = Intent(Actions.EXECUTE).apply {
            putExtra("alarm_triggered", true)
            putExtra("waken_at_time", true)
            if (!isMainAlarm) {
                putExtra("waken_time", TimeUtil.getTimeStr(triggerAtMillis))
            }
        }
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, pendingIntentFlags)
        return setAlarm(triggerAtMillis, pendingIntent, requestCode)
    }

    /**
     * 取消指定闹钟
     */
    fun cancelAlarm(pendingIntent: PendingIntent?) {
        try {
            if (pendingIntent != null) {
                alarmManager?.cancel(pendingIntent)
            }
        } catch (e: Exception) {
            Log.error(TAG, "取消闹钟失败: " + e.message)
            Log.printStackTrace(e)
        }
    }

    /**
     * 消费并取消一个已触发的闹钟
     * @param requestCode 闹钟的请求码
     */
    fun consumeAlarm(requestCode: Int) {
        if (requestCode == -1) {
            return
        }
        val pendingIntent = scheduledAlarms[requestCode]
        if (pendingIntent != null) {
            cancelAlarm(pendingIntent)
            scheduledAlarms.remove(requestCode)
            Log.record(TAG, "已消费并取消闹钟: ID=$requestCode")
        }
    }

    /**
     * 核心闹钟设置方法
     */
    @SuppressLint("DefaultLocale")
    private fun setAlarm(triggerAtMillis: Long, pendingIntent: PendingIntent, requestCode: Int): Boolean {
        try {
            val alarmManager = this.alarmManager ?: return false
            // 取消旧闹钟（如果存在）
            cancelOldAlarm(requestCode)
            
            // 获取临时唤醒锁
            WakeLockManager(context, Constants.WAKE_LOCK_SETUP_TIMEOUT).use {
                // 使用 setAlarmClock 以获得最高优先级（只设置一次，避免重复）
                val alarmClockInfo = AlarmManager.AlarmClockInfo(
                    triggerAtMillis,
                    PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
                )
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                
                Log.record(
                    TAG,
                    "✅ 已设置闹钟: ID=$requestCode, 预定时间=${TimeUtil.getTimeStr(triggerAtMillis)}"
                )
                // 保存闹钟引用
                scheduledAlarms[requestCode] = pendingIntent
                return true
            }
        } catch (e: Exception) {
            Log.error(TAG, "设置闹钟失败: " + e.message)
            Log.printStackTrace(e)
        }
        return false
    }

    /**
     * 设置闹钟并配置备份机制
     */
    private fun scheduleAlarmWithBackup(exactTimeMillis: Long, intent: Intent, requestCode: Int, delayMillis: Long) {
        try {
            // 先清理所有旧的备份任务和闹钟，防止泄漏
            // graceful = true：如果有任务正在执行，等待其完成
            cleanupAllBackups(graceful = true)
            
            // 创建主闹钟
            val pendingIntent = PendingIntent.getBroadcast(
                context, requestCode, intent,
                pendingIntentFlags or PendingIntent.FLAG_CANCEL_CURRENT
            )
            val success = setAlarm(exactTimeMillis, pendingIntent, requestCode)
            if (success) {
                // 设置一个待处理任务
                isTaskExecutionPending.set(true)
                // 设置备份机制
                scheduleBackupMechanisms(exactTimeMillis, delayMillis)
                // 更新通知
                updateNotification(exactTimeMillis)
                // 保存执行状态
                saveExecutionState(System.currentTimeMillis(), exactTimeMillis)
                Log.runtime(
                    TAG, "已设置闹钟唤醒执行，ID=" + requestCode +
                            "，时间：" + TimeUtil.getCommonDate(exactTimeMillis) +
                            "，延迟：" + delayMillis / 1000 + "秒"
                )
                
                // 定期输出诊断信息（每10次设置输出一次）
                scheduleCount++
                if (scheduleCount % 10 == 0) {
                    Log.debug(TAG, diagnoseMemoryAndResources())
                }
            }
        } catch (e: Exception) {
            Log.error(TAG, "设置闹钟备份失败：" + e.message)
            Log.printStackTrace(e)

            // 失败时使用协程备份
            scheduleCoroutineBackup(delayMillis)
        }
    }

    /**
     * 设置备份机制（协程版本）
     */
    private fun scheduleBackupMechanisms(exactTimeMillis: Long, delayMillis: Long) {
        val scheduledTimeStr = TimeUtil.getTimeStr(exactTimeMillis)
        val backupKey = "backup_${System.currentTimeMillis()}"
        // 取消之前的备份任务
        backupJobs.values.forEach { it.cancel() }
        backupJobs.clear()
        // 1. 协程第一级备份
        val firstBackupJob = alarmScope.launch {
            delay(delayMillis + Constants.FIRST_BACKUP_DELAY)
            if (isActive && isTaskExecutionPending.compareAndSet(true, false)) {
                val now = System.currentTimeMillis()
                TimeUtil.getTimeStr(now)
                now - exactTimeMillis
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(General.PACKAGE_NAME)
                executeBackupTaskSuspend()
            }
        }
        backupJobs["${backupKey}_first"] = firstBackupJob

        // 2. 协程第二级备份
        val secondBackupJob = alarmScope.launch {
            delay(delayMillis + Constants.SECOND_BACKUP_DELAY)
            if (isActive && isTaskExecutionPending.compareAndSet(true, false)) {
                val now = System.currentTimeMillis()
                TimeUtil.getTimeStr(now)
                now - exactTimeMillis
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(General.PACKAGE_NAME)

                executeBackupTaskSuspend()
            }
        }
        backupJobs["${backupKey}_second"] = secondBackupJob
        // 3. 备份闹钟
        scheduleBackupAlarm(exactTimeMillis)
    }


    /**
     * 设置备份闹钟
     */
    @SuppressLint("DefaultLocale")
    private fun scheduleBackupAlarm(exactTimeMillis: Long) {
        try {
            // 备份闹钟使用随机请求码以避免冲突
            val backupRequestCode = (System.currentTimeMillis() % 10000).toInt() + Constants.BACKUP_REQUEST_CODE_OFFSET
            val backupTriggerTime = exactTimeMillis + Constants.BACKUP_ALARM_DELAY
            val backupIntent = Intent(Actions.EXECUTE).apply {
                putExtra("execution_time", backupTriggerTime)
                putExtra("request_code", backupRequestCode)
                putExtra("scheduled_at", System.currentTimeMillis())
                putExtra("alarm_triggered", true)
                putExtra("is_backup_alarm", true)
                setPackage(General.PACKAGE_NAME)
            }

            val backupPendingIntent =
                PendingIntent.getBroadcast(context, backupRequestCode, backupIntent, pendingIntentFlags)
            alarmManager?.let {
                // 备份闹钟也使用AlarmClock以确保可靠性
                val backupAlarmInfo = AlarmManager.AlarmClockInfo(
                    backupTriggerTime,
                    PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
                )
                it.setAlarmClock(backupAlarmInfo, backupPendingIntent)
                scheduledAlarms[backupRequestCode] = backupPendingIntent
                // 追踪备份闹钟请求码
                backupAlarmRequestCodes.add(backupRequestCode)
                Log.runtime(
                    TAG,
                    "已设置备份闹钟: ID=$backupRequestCode, 预定时间=${TimeUtil.getTimeStr(backupTriggerTime)} (+${Constants.BACKUP_ALARM_DELAY / 1000}秒)"
                )
            }
        } catch (e: Exception) {
            Log.error(TAG, "设置备份闹钟失败: " + e.message)
        }
    }


    /**
     * 创建执行Intent
     */
    private fun createExecutionIntent(exactTimeMillis: Long, requestCode: Int): Intent {
        return Intent(Actions.EXECUTE).apply {
            putExtra("execution_time", exactTimeMillis)
            putExtra("request_code", requestCode)
            putExtra("scheduled_at", System.currentTimeMillis())
            putExtra("alarm_triggered", true)
            putExtra("unique_id", "${System.currentTimeMillis()}_$requestCode")
            setPackage(General.PACKAGE_NAME)
            addCategory(Actions.ALARM_CATEGORY)
        }
    }

    /**
     * 生成唯一请求码
     */
    private fun generateRequestCode(timeMillis: Long): Int {
        return (timeMillis % 10000 * 10 + Random.nextInt(10)).toInt()
    }

    /**
     * 检查并请求闹钟权限
     */
    private fun checkAndRequestAlarmPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager?.canScheduleExactAlarms() == false) {
                Log.record(TAG, "闹钟不可用(无权限), 准备请求。")
                requestAlarmPermission()
                return false
            }
        }

        return true
    }

    /**
     * 请求闹钟权限
     */
    @RequiresApi(api = Build.VERSION_CODES.S)
    private fun requestAlarmPermission() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = ("package:" + General.PACKAGE_NAME).toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.record(TAG, "已发送精确闹钟权限请求，等待用户授权")
            Notify.updateStatusText("请授予精确闹钟权限以确保定时任务正常执行")
        } catch (e: Exception) {
            Log.error(TAG, "请求精确闹钟权限失败: " + e.message)
        }
    }

    /**
     * 取消旧闹钟
     */
    private fun cancelOldAlarm(requestCode: Int) {
        scheduledAlarms[requestCode]?.let { oldPendingIntent ->
            alarmManager?.cancel(oldPendingIntent)
            scheduledAlarms.remove(requestCode)
        }
    }
    
    /**
     * 清理所有备份任务和闹钟
     * 防止闹钟泄漏，在设置新闹钟前调用
     * 
     * @param graceful 是否优雅关闭（等待正在执行的任务完成）
     */
    private fun cleanupAllBackups(graceful: Boolean = false) {
        try {
            // 1. 智能清理协程任务
            val cancelledJobs = backupJobs.values.count { it.isActive }
            
            // 优雅关闭：只在确实有任务正在执行时才等待
            if (graceful && isExecutingTask.get()) {
                Log.debug(TAG, "⏳ 检测到正在执行的任务，等待完成...")
                var waitTime = 0L
                val maxWaitTime = 2000L  // 最多等待2秒（备份任务通常很快）
                
                while (isExecutingTask.get() && waitTime < maxWaitTime) {
                    Thread.sleep(50)  // 更频繁检查，快速响应
                    waitTime += 50
                }
                
                if (isExecutingTask.get()) {
                    Log.debug(TAG, "⚠️ 等待${waitTime}ms后超时，强制取消")
                } else {
                    Log.debug(TAG, "✅ 任务已完成（耗时${waitTime}ms）")
                }
            }
            
            // 取消所有备份任务（delay等待中的会被立即取消，这是安全的）
            backupJobs.values.forEach { job ->
                if (job.isActive) {
                    job.cancel("设置新闹钟，清理旧备份")
                }
            }
            backupJobs.clear()
            
            // 2. 取消所有备份闹钟
            val cancelledAlarms = backupAlarmRequestCodes.size
            backupAlarmRequestCodes.forEach { requestCode ->
                scheduledAlarms[requestCode]?.let { pendingIntent ->
                    alarmManager?.cancel(pendingIntent)
                    scheduledAlarms.remove(requestCode)
                }
            }
            backupAlarmRequestCodes.clear()
            
            if (cancelledJobs > 0 || cancelledAlarms > 0) {
                Log.debug(TAG, "🧹 清理备份：取消${cancelledJobs}个协程任务，${cancelledAlarms}个备份闹钟")
            }
        } catch (e: Exception) {
            Log.error(TAG, "清理备份失败: ${e.message}")
        }
    }

    /**
     * 获取AlarmManager实例
     */
    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * 获取PendingIntent标志
     */
    private val pendingIntentFlags: Int
        get() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /**
     * 执行备份任务（协程版本）
     */
    private suspend fun executeBackupTaskSuspend() = withContext(Dispatchers.Main) {
        executionMutex.withLock {
            try {
                // 标记开始执行
                isExecutingTask.set(true)
                
                // 通过反射调用ApplicationHook的方法，避免循环依赖
                val appHookClass = Class.forName("fansirsqi.xposed.sesame.hook.ApplicationHook")
                val getTaskMethod = appHookClass.getDeclaredMethod("getMainTask")
                getTaskMethod.isAccessible = true
                val mainTask = getTaskMethod.invoke(null)

                // 检查主任务是否已在运行
                if (mainTask is BaseTask) {
                    val taskThread = mainTask.thread
                    if (taskThread?.isAlive == true) {
                        Log.record(TAG, "主任务正在运行，备份任务跳过执行。")
                        return@withLock
                    }
                }

                Log.record(TAG, "通过协程备份重启任务")
                val restartMethod = appHookClass.getDeclaredMethod("restartByBroadcast")
                restartMethod.isAccessible = true
                restartMethod.invoke(null)
                Log.record(TAG, "协程备份任务触发完成")
            } catch (e: Exception) {
                Log.error(TAG, "执行协程备份任务失败: " + e.message)
            } finally {
                // 标记执行结束
                isExecutingTask.set(false)
            }
        }
    }
    
    /**
     * 执行备份任务（兼容旧版本）
     */
    private fun executeBackupTask() {
        // 启动协程版本
        alarmScope.launch {
            executeBackupTaskSuspend()
        }
    }

    /**
     * 协程备份执行（替代Handler）
     */
    private fun scheduleCoroutineBackup(delayMillis: Long) {
        alarmScope.launch {
            delay(delayMillis)
            Log.record(TAG, "闹钟设置失败，使用协程备份执行")
            executeBackupTaskSuspend()
        }
    }
    

    /**
     * 更新通知
     */
    private fun updateNotification(exactTimeMillis: Long) {
        val nt = "⏰ 下次执行(Alarm) " + TimeUtil.getTimeStr(exactTimeMillis)
        Notify.updateNextExecText(exactTimeMillis)
        Toast.show(nt)
        Log.record(TAG, nt)
    }

    /**
     * 保存执行状态
     */
    private fun saveExecutionState(lastExecTime: Long, nextExecTime: Long) {
        try {
            val state = JSONObject().apply {
                put("lastExecTime", lastExecTime)
                put("nextExecTime", nextExecTime)
                put("timestamp", System.currentTimeMillis())
            }
            val stateJson = state.toString()
            DataStore.put("execution_state", stateJson)
           // Log.record(TAG, "已保存执行状态: $stateJson")
        } catch (e: IllegalStateException) {
            // DataStore 未初始化，这是正常的，在应用启动早期可能会发生
            Log.debug(TAG, "DataStore 尚未初始化，执行状态仅保存在内存中")
        } catch (e: Exception) {
            Log.error(TAG, "保存执行状态失败: " + e.message)
        }
    }

    /**
     * 唤醒锁管理器 - 自动释放资源
     */
    private class WakeLockManager(context: Context, timeout: Long) : AutoCloseable {
        private val wakeLock: PowerManager.WakeLock? = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sesame:AlarmSetupWakeLock").apply {
                acquire(timeout)
            }
        } catch (e: Exception) {
            Log.error(TAG, "获取唤醒锁失败: " + e.message)
            null
        }

        override fun close() {
            if (wakeLock?.isHeld == true) {
                try {
                    wakeLock.release()
                } catch (e: Exception) {
                    Log.error(TAG, "释放唤醒锁失败: " + e.message)
                }
            }
        }
    }

    /**
     * 由BroadcastReceiver调用，用于处理闹钟触发
     */
    fun handleAlarmTrigger() {
        if (isTaskExecutionPending.compareAndSet(true, false)) {
            Log.record(TAG, "闹钟触发，开始执行任务。")
            // 获取唤醒锁
            acquireWakeLock()
            // 执行任务
            executeBackupTask()
        } else {
            Log.record(TAG, "闹钟触发，但任务已由其他机制启动，跳过执行。")
        }
    }

    /**
     * 获取唤醒锁
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sesame:AlarmExecutionWakeLock").apply {
                setReferenceCounted(false)
            }
        }
        if (wakeLock?.isHeld == false) {
            // 设置15分钟超时，防止任务卡死导致无法释放
            wakeLock?.acquire(15 * 60 * 1000L)
            Log.record(TAG, "闹钟触发，已获取唤醒锁以确保任务持续执行")
        }
    }

    /**
     * 内存和资源诊断
     * 用于排查性能问题和资源泄漏
     */
    fun diagnoseMemoryAndResources(): String {
        return try {
            val runtime = Runtime.getRuntime()
            val totalMemory = runtime.totalMemory() / 1024 / 1024  // MB
            val freeMemory = runtime.freeMemory() / 1024 / 1024    // MB
            val usedMemory = totalMemory - freeMemory               // MB
            val maxMemory = runtime.maxMemory() / 1024 / 1024      // MB
            val memoryUsagePercent = (usedMemory * 100.0 / maxMemory).toInt()
            
            // 闹钟状态
            val totalAlarms = scheduledAlarms.size
            val backupAlarms = backupAlarmRequestCodes.size
            val mainAlarms = totalAlarms - backupAlarms
            
            // 协程状态
            val activeJobs = backupJobs.values.count { it.isActive }
            val completedJobs = backupJobs.values.count { it.isCompleted }
            val cancelledJobs = backupJobs.values.count { it.isCancelled }
            val totalJobs = backupJobs.size
            
            // 判断健康状态
            val alarmHealth = when {
                totalAlarms > 100 -> "🔴 严重泄漏"
                totalAlarms > 50 -> "⚠️ 警告"
                totalAlarms > 10 -> "⚠️ 注意"
                else -> "🟢 正常"
            }
            
            val memoryHealth = when {
                memoryUsagePercent > 85 -> "🔴 严重"
                memoryUsagePercent > 70 -> "⚠️ 警告"
                memoryUsagePercent > 50 -> "⚠️ 注意"
                else -> "🟢 正常"
            }
            
            """
            |═══════════════════════════════════
            |📊 AlarmScheduler 资源诊断报告
            |═══════════════════════════════════
            |【内存状态】$memoryHealth
            |  使用内存: ${usedMemory}MB / ${maxMemory}MB (${memoryUsagePercent}%)
            |  剩余内存: ${freeMemory}MB
            |  总分配: ${totalMemory}MB
            |
            |【闹钟状态】$alarmHealth
            |  主闹钟: $mainAlarms 个
            |  备份闹钟: $backupAlarms 个
            |  总计: $totalAlarms 个
            |  ${if (totalAlarms > 10) "⚠️ 闹钟数量偏多，可能存在泄漏！" else "✅ 闹钟数量正常"}
            |
            |【协程状态】
            |  活跃任务: $activeJobs 个
            |  完成任务: $completedJobs 个
            |  取消任务: $cancelledJobs 个
            |  总任务数: $totalJobs 个
            |  作用域: ${if (alarmScope.isActive) "✅ 活跃" else "❌ 非活跃"}
            |
            |【其他状态】
            |  待执行标记: ${if (isTaskExecutionPending.get()) "⏳ 是" else "✅ 否"}
            |  正在执行任务: ${if (isExecutingTask.get()) "⚠️ 是" else "✅ 否"}
            |  WakeLock持有: ${if (wakeLock?.isHeld == true) "⚠️ 是" else "✅ 否"}
            |
            |【建议】
            |${getDiagnosticAdvice(totalAlarms, memoryUsagePercent, activeJobs)}
            |═══════════════════════════════════
            """.trimMargin()
        } catch (e: Exception) {
            "诊断失败: ${e.message}"
        }
    }
    
    /**
     * 根据诊断结果给出建议
     */
    private fun getDiagnosticAdvice(alarms: Int, memoryPercent: Int, activeJobs: Int): String {
        val advice = mutableListOf<String>()
        
        if (alarms > 100) {
            advice.add("  🔴 闹钟严重泄漏！建议立即重启应用")
        } else if (alarms > 50) {
            advice.add("  ⚠️ 闹钟数量过多，建议调用 cleanup() 清理")
        }
        
        if (memoryPercent > 85) {
            advice.add("  🔴 内存严重不足！可能即将 OOM")
        } else if (memoryPercent > 70) {
            advice.add("  ⚠️ 内存压力较大，建议释放资源")
        }
        
        if (activeJobs > 10) {
            advice.add("  ⚠️ 活跃协程过多，可能影响性能")
        }
        
        if (advice.isEmpty()) {
            advice.add("  ✅ 系统运行正常，无异常")
        }
        
        return advice.joinToString("\n")
    }
    
    /**
     * 清理协程资源和所有闹钟
     * 
     * @param graceful 是否优雅关闭（等待正在执行的任务完成），默认 true
     * @param timeoutMillis 优雅关闭超时时间（毫秒），默认 5秒
     */
    fun cleanup(graceful: Boolean = true, timeoutMillis: Long = 5000L) {
        try {
            val totalJobs = backupJobs.size
            val totalAlarms = scheduledAlarms.size
            
            Log.record(TAG, "🧹 开始清理 AlarmScheduler 资源 (优雅模式: $graceful)...")
            
            // 1. 优雅关闭协程任务
            if (graceful) {
                val activeJobs = backupJobs.values.filter { it.isActive }
                if (activeJobs.isNotEmpty()) {
                    Log.debug(TAG, "⏳ 等待 ${activeJobs.size} 个活跃任务完成（最多 ${timeoutMillis/1000} 秒）...")
                    // 使用 runBlocking 等待任务完成
                    runBlocking {
                        withTimeoutOrNull(timeoutMillis) {
                            // 等待所有活跃任务完成
                            activeJobs.forEach { job ->
                                try {
                                    job.join()
                                } catch (e: Exception) {
                                    // 任务被取消或其他异常，继续下一个
                                }
                            }
                        } ?: run {
                            Log.debug(TAG, "⚠️ 等待超时，强制取消剩余任务")
                        }
                    }
                }
            }
            
            // 2. 取消所有备份任务（清理残余）
            backupJobs.values.forEach { job ->
                if (job.isActive) {
                    job.cancel("AlarmScheduler cleanup")
                }
            }
            backupJobs.clear()
            
            // 3. 取消所有闹钟（包括主闹钟和备份闹钟）
            scheduledAlarms.forEach { (requestCode, pendingIntent) ->
                try {
                    alarmManager?.cancel(pendingIntent)
                } catch (e: Exception) {
                    Log.debug(TAG, "取消闹钟${requestCode}失败: ${e.message}")
                }
            }
            scheduledAlarms.clear()
            backupAlarmRequestCodes.clear()
            // 4. 取消协程作用域
            alarmScope.cancel("AlarmScheduler cleanup")
            Log.record(TAG, "✅ AlarmScheduler资源已清理：${totalJobs}个协程任务，${totalAlarms}个闹钟")
        } catch (e: Exception) {
            Log.error(TAG, "清理AlarmScheduler资源失败: " + e.message)
            Log.printStackTrace(e)
        }
    }

    companion object {
        private const val TAG = "AlarmScheduler"
        private var wakeLock: PowerManager.WakeLock? = null

        @JvmStatic
        fun releaseWakeLock() {
            if (wakeLock?.isHeld == true) {
                try {
                    wakeLock?.release()
                    Log.record(TAG, "唤醒锁已释放")
                } catch (e: Exception) {
                    Log.error(TAG, "释放唤醒锁失败: " + e.message)
                }
            }
        }
    }
}
