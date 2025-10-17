package fansirsqi.xposed.sesame.hook

// import android.os.Handler // 不再需要Handler
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import fansirsqi.xposed.sesame.data.General
import fansirsqi.xposed.sesame.newutil.DataStore
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.Notify
import fansirsqi.xposed.sesame.util.TimeUtil
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

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
 * @param taskExecutor 任务执行器（可选），用于备份机制触发任务重启
 */
class AlarmScheduler(
    private val context: Context,
    private val taskExecutor: TaskExecutor? = null
) {
    private val scheduledAlarms: MutableMap<Int, PendingIntent> = ConcurrentHashMap()

    // 协程相关
    // 使用 Dispatchers.Default 因为闹钟调度主要是计算密集型任务（时间计算、状态管理）
    // 而非 IO 密集型（文件、网络），具体 IO 操作会在内部切换到合适的调度器
    private val alarmScope = CoroutineScope(
        SupervisorJob() +
                Dispatchers.Default +
                CoroutineName("AlarmSchedulerScope")
    )

    // 备份管理器（组合模式）
    private val backupManager = AlarmBackupManager(alarmScope, taskExecutor)
    
    // 实例级唤醒锁管理（避免静态字段的多实例问题）
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 闹钟相关常量
     */
    object Constants {
        // 唤醒锁相关
        const val WAKE_LOCK_SETUP_TIMEOUT = 5000L // 5秒，设置闹钟时的临时唤醒锁超时
        const val WAKE_LOCK_EXECUTION_TIMEOUT = 15 * 60 * 1000L // 15分钟，任务执行时的唤醒锁超时

        const val BACKUP_ALARM_DELAY = 12000L // 12秒，备份闹钟延迟
        
        // 请求码相关
        const val REQUEST_CODE_MODULO = 10000 // 请求码取模基数
        const val REQUEST_CODE_MULTIPLIER = 10 // 请求码乘数
        const val BACKUP_REQUEST_CODE_OFFSET = 10000 // 备份闹钟请求码偏移量
    }

    /**
     * 广播动作常量
     */
    object Actions {
        const val EXECUTE = "com.eg.android.AlipayGphone.sesame.execute"
        const val ALARM_CATEGORY = "fansirsqi.xposed.sesame.ALARM_CATEGORY"
    }

    /**
     * 异常处理工具方法
     */
    private inline fun <T> safeExecute(
        operation: String,
        printStackTrace: Boolean = false,
        defaultValue: T? = null,
        block: () -> T
    ): T? = try {
        block()
    } catch (e: Exception) {
        Log.error(TAG, "$operation 失败: ${e.message}")
        if (printStackTrace) {
            Log.printStackTrace(TAG, e)
        }
        defaultValue
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
        safeExecute("取消闹钟", printStackTrace = true) {
            if (pendingIntent != null) {
                alarmManager?.cancel(pendingIntent)
            }
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
     * 取消所有已设置的闹钟
     * 注意：此方法只取消闹钟，不清理协程资源
     */
    fun cancelAllAlarms() {
        if (scheduledAlarms.isEmpty()) {
            Log.record(TAG, "没有需要取消的闹钟")
            return
        }

        val totalAlarms = scheduledAlarms.size
        scheduledAlarms.forEach { (requestCode, pendingIntent) ->
            try {
                cancelAlarm(pendingIntent)
            } catch (e: Exception) {
                Log.error(TAG, "取消闹钟失败: ID=$requestCode, ${e.message}")
            }
        }
        scheduledAlarms.clear()
        Log.record(TAG, "已取消所有闹钟，共${totalAlarms}个")
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
                // 根据Android版本和权限选择合适的闹钟类型
                // 1. 使用setAlarmClock以获得最高优先级
                val alarmClockInfo = AlarmManager.AlarmClockInfo(
                    triggerAtMillis,  // 创建一个用于显示闹钟设置界面的PendingIntent
                    PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
                )
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                // 2. 同时设置一个备用的精确闹钟
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                // 3. 获取PowerManager.WakeLock
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Sesame:AlarmWakeLock:$requestCode"
                )
                wakeLock.acquire(Constants.WAKE_LOCK_SETUP_TIMEOUT) // 持有指定时间以确保闹钟设置成功
                Log.record(
                    TAG,
                    "已设置多重保护闹钟: ID=$requestCode, 预定时间=${TimeUtil.getTimeStr(triggerAtMillis)}"
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
            // 创建主闹钟
            val pendingIntent = PendingIntent.getBroadcast(
                context, requestCode, intent,
                pendingIntentFlags or PendingIntent.FLAG_CANCEL_CURRENT
            )
            val success = setAlarm(exactTimeMillis, pendingIntent, requestCode)
            if (success) {
                // 设置待处理标志并启动备份机制
                backupManager.setTaskPending(true)
                backupManager.scheduleBackups(delayMillis)  // 协程备份已足够
                // 同时设置传统的备份闹钟（可选的第三级备份）
                scheduleBackupAlarm(exactTimeMillis)
                // 更新通知
                updateNotification(exactTimeMillis)
                // 保存执行状态
                saveExecutionState(System.currentTimeMillis(), exactTimeMillis)
                Log.runtime(
                    TAG, "已设置闹钟唤醒执行，ID=" + requestCode +
                            "，时间：" + TimeUtil.getCommonDate(exactTimeMillis) +
                            "，延迟：" + delayMillis / 1000 + "秒"
                )
            }
        } catch (e: Exception) {
            Log.error(TAG, "设置闹钟备份失败：" + e.message)
            Log.printStackTrace(e)

            // 失败时仍然启动备份机制
            backupManager.setTaskPending(true)
            backupManager.scheduleBackups(delayMillis)
        }
    }

    /**
     * 设置备份闹钟（第三级备份）
     */
    @SuppressLint("DefaultLocale")
    private fun scheduleBackupAlarm(exactTimeMillis: Long) {
        safeExecute("设置备份闹钟") {
            // 备份闹钟使用随机请求码以避免冲突
            val backupRequestCode = (System.currentTimeMillis() % Constants.REQUEST_CODE_MODULO).toInt() + Constants.BACKUP_REQUEST_CODE_OFFSET
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
                Log.runtime(
                    TAG,
                    "已设置备份闹钟: ID=$backupRequestCode, 预定时间=${TimeUtil.getTimeStr(backupTriggerTime)} (+${Constants.BACKUP_ALARM_DELAY / 1000}秒)"
                )
            }
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
     * 基于时间戳生成，确保唯一性
     */
    private fun generateRequestCode(timeMillis: Long): Int {
        return (timeMillis % Constants.REQUEST_CODE_MODULO * Constants.REQUEST_CODE_MULTIPLIER 
                + Random.nextInt(Constants.REQUEST_CODE_MULTIPLIER)).toInt()
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
        safeExecute("请求精确闹钟权限") {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = ("package:" + General.PACKAGE_NAME).toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.record(TAG, "已发送精确闹钟权限请求，等待用户授权")
            Notify.updateStatusText("请授予精确闹钟权限以确保定时任务正常执行")
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
     * 获取AlarmManager实例（使用 lazy 缓存，避免重复获取）
     */
    private val alarmManager: AlarmManager? by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    }

    /**
     * 获取PendingIntent标志
     */
    private val pendingIntentFlags: Int
        get() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT



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
        } catch (_: IllegalStateException) {
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
        // 通过 backupManager 处理闹钟触发
        backupManager.handleAlarmTrigger()
        // 获取唤醒锁确保任务执行
        acquireWakeLock()
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
            // 设置超时，防止任务卡死导致无法释放
            wakeLock?.acquire(Constants.WAKE_LOCK_EXECUTION_TIMEOUT)
            Log.record(TAG, "闹钟触发，已获取唤醒锁以确保任务持续执行 (超时: ${Constants.WAKE_LOCK_EXECUTION_TIMEOUT / 60000}分钟)")
        }
    }

    /**
     * 获取协程状态信息
     */
    fun getCoroutineStatus(): String {
        return try {
            val scopeActive = alarmScope.isActive
            val backupStatus = backupManager.getStatus()
            "协程状态: 作用域=${if (scopeActive) "活跃" else "非活跃"}, $backupStatus"
        } catch (e: Exception) {
            "协程状态获取失败: ${e.message}"
        }
    }

    /**
     * 清理所有资源（包括闹钟、协程、唤醒锁）
     * 使用 runCatching 确保所有清理步骤都能执行，不会因某个步骤失败而中断
     */
    fun cleanup() {
        val errors = mutableListOf<String>()
        val totalAlarms = scheduledAlarms.size

        // 1. 取消所有已设置的闹钟
        runCatching {
            var successCount = 0
            scheduledAlarms.forEach { (requestCode, pendingIntent) ->
                runCatching {
                    cancelAlarm(pendingIntent)
                    successCount++
                }.onFailure { e ->
                    Log.error(TAG, "取消闹钟失败: ID=$requestCode, ${e.message}")
                }
            }
            scheduledAlarms.clear()
            Log.record(TAG, "已取消 $successCount/$totalAlarms 个闹钟")
        }.onFailure { e ->
            errors.add("闹钟清理异常: ${e.message}")
        }

        // 2. 取消所有备份任务（通过 backupManager）
        runCatching {
            backupManager.cancelAllBackups()
        }.onFailure { e ->
            errors.add("备份任务清理异常: ${e.message}")
        }

        // 3. 取消协程作用域
        runCatching {
            alarmScope.cancel("AlarmScheduler cleanup")
            Log.record(TAG, "已取消协程作用域")
        }.onFailure { e ->
            errors.add("协程作用域取消异常: ${e.message}")
        }

        // 4. 重置待处理标志（通过 backupManager）
        runCatching {
            backupManager.setTaskPending(false)
        }.onFailure { e ->
            errors.add("重置标志异常: ${e.message}")
        }

        // 5. 释放唤醒锁
        runCatching {
            releaseWakeLock()
        }.onFailure { e ->
            errors.add("唤醒锁释放异常: ${e.message}")
        }

        // 汇总清理结果
        if (errors.isEmpty()) {
            Log.record(TAG, "✅ AlarmScheduler资源已全部清理 (闹钟:${totalAlarms}个)")
        } else {
            Log.error(TAG, "⚠️ AlarmScheduler清理完成，但遇到 ${errors.size} 个错误:")
            errors.forEach { Log.error(TAG, "  - $it") }
        }
    }

    /**
     * 释放唤醒锁（实例方法）
     */
    fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            safeExecute("释放唤醒锁") {
                wakeLock?.release()
                Log.record(TAG, "唤醒锁已释放")
            }
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "AlarmScheduler"
    }
}
