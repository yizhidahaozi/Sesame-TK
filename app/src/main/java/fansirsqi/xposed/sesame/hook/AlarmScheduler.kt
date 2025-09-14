package fansirsqi.xposed.sesame.hook

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
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

/**
 * 统一的闹钟调度管理器
 *
 *
 * 负责管理所有闹钟相关功能，包括：
 * 1. 闹钟的设置和取消
 * 2. 权限检查和处理
 * 3. 备份机制管理
 * 4. 唤醒锁管理
 */
class AlarmScheduler(private val context: Context, private val mainHandler: Handler?) {
    private val scheduledAlarms: MutableMap<Int, PendingIntent> = ConcurrentHashMap()
    private val isTaskExecutionPending = AtomicBoolean(false)

    /**
     * 闹钟相关常量
     */
    object Constants {
        const val WAKE_LOCK_SETUP_TIMEOUT = 5000L // 5秒
        const val FIRST_BACKUP_DELAY = 20000L // 20秒，缩短第一级备份延迟
        const val SECOND_BACKUP_DELAY = 50000L // 50秒，缩短第二级备份延迟
        const val BACKUP_ALARM_DELAY = 15000L // 15秒，缩短备份闹钟延迟
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
                wakeLock.acquire(5000) // 持有5秒钟以确保闹钟设置成功
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
                // 设置一个待处理任务
                isTaskExecutionPending.set(true)
                // 设置备份机制
                scheduleBackupMechanisms(exactTimeMillis, delayMillis)
                // 更新通知
                updateNotification(exactTimeMillis)
                // 保存执行状态
                saveExecutionState(System.currentTimeMillis(), exactTimeMillis)
                Log.record(
                    TAG, "已设置闹钟唤醒执行，ID=" + requestCode +
                            "，时间：" + TimeUtil.getCommonDate(exactTimeMillis) +
                            "，延迟：" + delayMillis / 1000 + "秒"
                )
            }
        } catch (e: Exception) {
            Log.error(TAG, "设置闹钟备份失败：" + e.message)
            Log.printStackTrace(e)

            // 失败时使用Handler备份
            scheduleHandlerBackup(delayMillis)
        }
    }

    /**
     * 设置备份机制
     */
    private fun scheduleBackupMechanisms(exactTimeMillis: Long, delayMillis: Long) {
        val scheduledTimeStr = TimeUtil.getTimeStr(exactTimeMillis)
        // 1. Handler第一级备份
        mainHandler?.postDelayed({
            if (isTaskExecutionPending.compareAndSet(true, false)) {
                val now = System.currentTimeMillis()
                val nowStr = TimeUtil.getTimeStr(now)
                val delay = now - exactTimeMillis
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val isIgnoringOptimizations =
                    powerManager.isIgnoringBatteryOptimizations(General.PACKAGE_NAME)

                val batteryOptLog = if (isIgnoringOptimizations) {
                    "true (已忽略原生安卓优化, 但延迟仍然发生。这很可能是由手机厂商自带的省电策略导致。请检查手机管家/安全中心/电池设置中针对支付宝的后台活动、自启动等权限。)"
                } else {
                    "false (这是导致延迟的主要原因, 请在系统设置中为支付宝开启“无限制”或“允许后台活动”权限)"
                }

                val reason = """
                - 预定时间: $scheduledTimeStr
                - 备份触发时间: $nowStr
                - 延迟: ${delay}ms
                - 是否已忽略电池优化: $batteryOptLog
                """.trimIndent()
                Log.error(TAG, "闹钟未在预期内触发，使用Handler备份执行 (第一级备份)。可能原因分析:\n$reason")
                executeBackupTask()
            }
        }, delayMillis + Constants.FIRST_BACKUP_DELAY)

        // 2. Handler第二级备份
        mainHandler?.postDelayed({
            if (isTaskExecutionPending.compareAndSet(true, false)) {
                val now = System.currentTimeMillis()
                val nowStr = TimeUtil.getTimeStr(now)
                val delay = now - exactTimeMillis
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val isIgnoringOptimizations =
                    powerManager.isIgnoringBatteryOptimizations(General.PACKAGE_NAME)

                val batteryOptLog = if (isIgnoringOptimizations) {
                    "true (已忽略原生安卓优化, 但延迟仍然发生。这很可能是由手机厂商自带的省电策略导致。请检查手机管家/安全中心/电池设置中针对支付宝的后台活动、自启动等权限。)"
                } else {
                    "false (这是导致延迟的主要原因, 请在系统设置中为支付宝开启“无限制”或“允许后台活动”权限)"
                }

                val reason = """
                - 预定时间: $scheduledTimeStr
                - 备份触发时间: $nowStr
                - 延迟: ${delay}ms
                - 是否已忽略电池优化: $batteryOptLog
                """.trimIndent()
                Log.error(TAG, "闹钟和第一级备份可能都未触发，使用Handler备份执行 (第二级备份)。可能原因分析:\n$reason")
                executeBackupTask()
            }
        }, delayMillis + Constants.SECOND_BACKUP_DELAY)
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
                Log.record(
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
            Log.debug(TAG, "已取消旧闹钟: ID=$requestCode")
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
     * 执行备份任务
     */
    private fun executeBackupTask() {
        try {
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
                    return
                }
            }

            Log.record(TAG, "通过广播重启任务")
            val restartMethod = appHookClass.getDeclaredMethod("restartByBroadcast")
            restartMethod.isAccessible = true
            restartMethod.invoke(null)
            Log.record(TAG, "备份任务触发完成")
        } catch (e: Exception) {
            Log.error(TAG, "执行备份任务失败: " + e.message)
        }
    }

    /**
     * Handler备份执行
     */
    private fun scheduleHandlerBackup(delayMillis: Long) {
        mainHandler?.postDelayed({
            Log.record(TAG, "闹钟设置失败，使用Handler备份执行")
            executeBackupTask()
        }, delayMillis)
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
            Log.record(TAG, "已保存执行状态: $stateJson")
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

    companion object {
        private const val TAG = "AlarmScheduler"
        private var wakeLock: PowerManager.WakeLock? = null
    }
}
