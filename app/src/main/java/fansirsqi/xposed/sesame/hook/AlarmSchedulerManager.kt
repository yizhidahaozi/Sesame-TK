package fansirsqi.xposed.sesame.hook

import android.content.Context
import android.os.Handler
import fansirsqi.xposed.sesame.util.Log

/**
 * AlarmScheduler管理器 - 封装所有AlarmScheduler相关操作
 * 提供统一的接口和错误处理机制
 *
 * 主要功能：
 * 1. AlarmScheduler的生命周期管理
 * 2. 统一的错误处理和重试机制
 * 3. 自动故障恢复
 * 4. 详细的日志记录
 */
class AlarmSchedulerManager {
    
    // 使用 Kotlin 属性语法，自动生成 getter/setter
    var alarmScheduler: AlarmScheduler? = null
        private set
    
    var appContext: Context? = null
    var mainHandler: Handler? = null
    
    // 计算属性，替代 isAlarmSchedulerAvailable() 方法
    val isAlarmSchedulerAvailable: Boolean
        get() = alarmScheduler != null
    
    // 计算属性，替代 getStatus() 方法
    val status: String
        get() = alarmScheduler?.let { 
            "AlarmScheduler: 已初始化"
        } ?: "AlarmScheduler: 未初始化"

    /**
     * 设置依赖项 - 使用 Kotlin 的简洁语法
     */
    fun setDependencies(alarmScheduler: AlarmScheduler?, appContext: Context?, mainHandler: Handler?) {
        this.alarmScheduler = alarmScheduler
        this.appContext = appContext
        this.mainHandler = mainHandler
    }

    /**
     * 安全地初始化AlarmScheduler
     */
    fun initializeAlarmScheduler(context: Context?): Boolean {
        context ?: run {
            Log.error(ALARM_TAG, "初始化AlarmScheduler失败: Context为null")
            return false
        }

        return try {
            // 清理旧实例
            alarmScheduler?.let {
                Log.record(ALARM_TAG, "AlarmScheduler已存在，先清理旧实例")
                cleanupAlarmScheduler()
            }

            // 创建新实例
            alarmScheduler = AlarmScheduler(context)
            appContext = context

            Log.record(ALARM_TAG, "✅ AlarmScheduler初始化成功")
            true
        } catch (e: Exception) {
            Log.error(ALARM_TAG, "❌ AlarmScheduler初始化失败: ${e.message}")
            Log.printStackTrace(ALARM_TAG, e)
            false
        }
    }

    /**
     * 安全地清理AlarmScheduler
     */
    fun cleanupAlarmScheduler() {
        alarmScheduler?.let { scheduler ->
            try {
                Log.record(ALARM_TAG, "🧹 开始清理AlarmScheduler")
                scheduler.cleanup()
                Log.record(ALARM_TAG, "✅ AlarmScheduler清理完成")
            } catch (e: Exception) {
                Log.error(ALARM_TAG, "❌ 清理AlarmScheduler失败: ${e.message}")
                Log.printStackTrace(ALARM_TAG, e)
            } finally {
                alarmScheduler = null
            }
        }
    }

    /**
     * 安全地调度精确执行
     */
    fun scheduleExactExecution(delayMillis: Long, exactTimeMillis: Long) {
        executeWithAlarmScheduler("调度精确执行") { scheduler ->
            scheduler.scheduleExactExecution(delayMillis, exactTimeMillis)
            Log.record(ALARM_TAG, "⏰ 精确执行调度成功: 延迟${delayMillis}ms")
        }
    }

    /**
     * 安全地调度延迟执行
     */
    fun scheduleDelayedExecution(delayMillis: Long): Boolean {
        return executeWithAlarmScheduler("调度延迟执行") { scheduler ->
            scheduler.scheduleDelayedExecution(delayMillis)
            Log.record(ALARM_TAG, "⏰ 延迟执行调度成功: 延迟${delayMillis}ms")
            true
        } ?: false
    }

    /**
     * 安全地调度唤醒闹钟
     */
    fun scheduleWakeupAlarm(triggerAtMillis: Long, requestCode: Int, isMainAlarm: Boolean): Boolean {
        return executeWithAlarmScheduler("调度唤醒闹钟") { scheduler ->
            val success = scheduler.scheduleWakeupAlarm(triggerAtMillis, requestCode, isMainAlarm)
            val alarmType = if (isMainAlarm) "主闹钟" else "自定义闹钟"
            
            if (success) {
                Log.record(ALARM_TAG, "⏰ ${alarmType}设置成功: ID=$requestCode")
            } else {
                Log.runtime(ALARM_TAG, "⚠️ 闹钟设置返回false: ID=$requestCode")
            }
            success
        } ?: false
    }

    /**
     * 处理闹钟触发
     */
    fun handleAlarmTrigger(requestCode: Int) {
        executeWithAlarmScheduler("处理闹钟触发") { scheduler ->
            scheduler.handleAlarmTrigger()
            scheduler.consumeAlarm(requestCode)
            Log.record(ALARM_TAG, "✅ 闹钟触发处理完成: ID=$requestCode")
        }
    }

    /**
     * 带重试机制的延迟执行调度
     */
    fun scheduleDelayedExecutionWithRetry(delayMillis: Long, operation: String) {
        scheduleDelayedExecutionWithRetry(delayMillis, operation, 0)
    }

    /**
     * 核心辅助方法：安全执行 AlarmScheduler 操作
     */
    private inline fun <T> executeWithAlarmScheduler(operation: String, action: (AlarmScheduler) -> T): T? {
        // 检查并确保 AlarmScheduler 可用
        if (!ensureAlarmSchedulerAvailable(operation)) {
            return null
        }
        
        return try {
            alarmScheduler?.let(action)
        } catch (e: Exception) {
            Log.error(ALARM_TAG, "❌ ${operation}失败: ${e.message}")
            Log.printStackTrace(ALARM_TAG, e)
            null
        }
    }
    
    /**
     * 确保 AlarmScheduler 可用，如果不可用则尝试重新初始化
     */
    private fun ensureAlarmSchedulerAvailable(operation: String): Boolean {
        if (isAlarmSchedulerAvailable) return true
        
        Log.runtime(ALARM_TAG, "⚠️ $operation: AlarmScheduler不可用，尝试重新初始化")
        
        return if (appContext != null && initializeAlarmScheduler(appContext)) {
            true // 重新初始化成功
        } else {
            Log.error(ALARM_TAG, "❌ $operation: AlarmScheduler重新初始化失败")
            false // 重新初始化失败
        }
    }

    /**
     * 带重试机制的延迟执行调度（内部方法）
     */
    private fun scheduleDelayedExecutionWithRetry(delayMillis: Long, operation: String, retryCount: Int) {
        if (scheduleDelayedExecution(delayMillis)) return // 成功则返回

        if (retryCount < MAX_RETRY_COUNT && mainHandler != null) {
            val retryDelay = RETRY_DELAY_BASE * (retryCount + 1)
            Log.runtime(ALARM_TAG, "⏳ ${operation}失败，${retryDelay}ms后重试 (第${retryCount + 1}次)")

            mainHandler?.postDelayed({
                // 重试前尝试重新初始化AlarmScheduler
                if (!isAlarmSchedulerAvailable) {
                    initializeAlarmScheduler(appContext)
                }
                scheduleDelayedExecutionWithRetry(delayMillis, operation, retryCount + 1)
            }, retryDelay)
        } else {
            Log.error(ALARM_TAG, "❌ ${operation}重试超过最大次数，操作失败")
        }
    }

    companion object {
        private const val ALARM_TAG = "AlarmManager"
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_BASE = 2000L // 2秒基础延迟
    }
}
