package fansirsqi.xposed.sesame.hook

import android.content.Context
import android.os.Handler
import fansirsqi.xposed.sesame.util.Log
import kotlinx.coroutines.*

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
    var taskExecutor: TaskExecutor? = null  // 任务执行器，用于依赖注入

    // 管理器协程作用域（用于重试等异步操作）
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 计算属性，替代 isAlarmSchedulerAvailable() 方法
    val isAlarmSchedulerAvailable: Boolean
        get() = alarmScheduler != null

    // 计算属性，替代 getStatus() 方法
    val status: String
        get() = alarmScheduler?.let {
            try {
                "AlarmScheduler: 已初始化, ${it.getCoroutineStatus()}"
            } catch (e: Exception) {
                "AlarmScheduler: 状态获取失败 - ${e.message}"
            }
        } ?: "AlarmScheduler: 未初始化"

    /**
     * 设置外部依赖项
     * @param appContext 应用上下文
     * @param mainHandler 主线程 Handler
     *
     * 注意：appContext 和 mainHandler 是 var 属性，
     * Kotlin 会自动生成 setAppContext() 和 setMainHandler() 方法供 Java 调用
     */
    fun setDependencies(appContext: Context?, mainHandler: Handler?) {
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

            // 创建新实例，注入 TaskExecutor 依赖
            alarmScheduler = AlarmScheduler(context, taskExecutor)
            appContext = context

            val executorStatus = if (taskExecutor != null) "已注入 TaskExecutor" else "未注入 TaskExecutor（备份功能受限）"
            Log.record(ALARM_TAG, "✅ AlarmScheduler初始化成功 ($executorStatus)")
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
                val status = scheduler.getCoroutineStatus()
                Log.record(ALARM_TAG, "🧹 开始清理AlarmScheduler: $status")
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
     * 注意：日志由 AlarmScheduler 层统一记录
     */
    fun scheduleExactExecution(delayMillis: Long, exactTimeMillis: Long) {
        executeWithAlarmScheduler("调度精确执行") { scheduler ->
            scheduler.scheduleExactExecution(delayMillis, exactTimeMillis)
        }
    }

    /**
     * 安全地调度延迟执行
     * 注意：日志由 AlarmScheduler 层统一记录
     */
    fun scheduleDelayedExecution(delayMillis: Long): Boolean {
        return executeWithAlarmScheduler("调度延迟执行") { scheduler ->
            scheduler.scheduleDelayedExecution(delayMillis)
            true
        } ?: false
    }

    /**
     * 安全地调度唤醒闹钟
     * 注意：日志由 AlarmScheduler 层统一记录
     */
    fun scheduleWakeupAlarm(triggerAtMillis: Long, requestCode: Int, isMainAlarm: Boolean): Boolean {
        return executeWithAlarmScheduler("调度唤醒闹钟") { scheduler ->
            scheduler.scheduleWakeupAlarm(triggerAtMillis, requestCode, isMainAlarm)
        } ?: false
    }

    /**
     * 处理闹钟触发
     * 注意：日志由 AlarmScheduler 层统一记录
     */
    fun handleAlarmTrigger(requestCode: Int) {
        executeWithAlarmScheduler("处理闹钟触发") { scheduler ->
            scheduler.handleAlarmTrigger()
            scheduler.consumeAlarm(requestCode)
        }
    }

    /**
     * 取消所有已设置的闹钟
     */
    fun cancelAllAlarms() {
        executeWithAlarmScheduler("取消所有闹钟") { scheduler ->
            scheduler.cancelAllAlarms()
        }
    }

    /**
     * 释放唤醒锁
     */
    fun releaseWakeLock() {
        executeWithAlarmScheduler("释放唤醒锁") { scheduler ->
            scheduler.releaseWakeLock()
        }
    }

    /**
     * 带重试机制的延迟执行调度（协程版本）
     */
    fun scheduleDelayedExecutionWithRetry(delayMillis: Long, operation: String) {
        managerScope.launch {
            retryWithBackoff(MAX_RETRY_COUNT) { attempt ->
                if (scheduleDelayedExecution(delayMillis)) {
                    true // 成功
                } else {
                    Log.runtime(ALARM_TAG, "⏳ ${operation}失败，准备重试 (第${attempt + 1}次)")
                    
                    // 重试前尝试重新初始化
                    if (!isAlarmSchedulerAvailable) {
                        initializeAlarmScheduler(appContext)
                    }
                    false // 失败，需要重试
                }
            }.onFailure {
                Log.error(ALARM_TAG, "❌ ${operation}重试超过最大次数，操作失败")
            }.onSuccess {
                Log.record(ALARM_TAG, "✅ ${operation}重试成功")
            }
        }
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
     * 带指数退避的重试工具方法
     * @param maxRetries 最大重试次数
     * @param block 要执行的操作，返回 true 表示成功，false 表示需要重试
     */
    private suspend fun retryWithBackoff(
        maxRetries: Int,
        block: suspend (attempt: Int) -> Boolean
    ): Result<Unit> {
        repeat(maxRetries) { attempt ->
            if (block(attempt)) {
                return Result.success(Unit)
            }
            if (attempt < maxRetries - 1) {
                val delayTime = RETRY_DELAY_BASE * (attempt + 1)
                delay(delayTime)
            }
        }
        return Result.failure(Exception("重试超过最大次数"))
    }

    companion object {
        private const val ALARM_TAG = "AlarmManager"
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_BASE = 2000L // 2秒基础延迟
    }
}