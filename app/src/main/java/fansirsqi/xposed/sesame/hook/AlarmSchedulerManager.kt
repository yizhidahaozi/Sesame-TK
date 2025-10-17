package fansirsqi.xposed.sesame.hook

import android.content.Context
import android.os.Handler
import fansirsqi.xposed.sesame.util.Log

/**
 * 纯协程调度器管理器 - 无闹钟版本
 * 
 * ⚠️ 重要变更：已移除所有 AlarmManager 依赖
 * 
 * 主要功能：
 * 1. CoroutineScheduler的生命周期管理
 * 2. 统一的错误处理和重试机制
 * 3. 自动故障恢复
 * 4. 详细的日志记录
 * 
 * 限制：
 * - 息屏时可能被系统挂起
 * - 进程被杀后无法自动恢复
 * - Doze 模式下会被冻结
 */
class AlarmSchedulerManager {

    // 使用纯协程调度器
    var coroutineScheduler: CoroutineScheduler? = null
        private set

    var appContext: Context? = null
    var mainHandler: Handler? = null

    // 计算属性，替代 isAlarmSchedulerAvailable() 方法
    val isAlarmSchedulerAvailable: Boolean
        get() = coroutineScheduler != null

    // 计算属性，替代 getStatus() 方法
    val status: String
        get() = coroutineScheduler?.let {
            "协程调度器: 已初始化 (无闹钟模式)"
        } ?: "协程调度器: 未初始化"

    /**
     * 设置依赖项 - 兼容旧接口（已移除 AlarmScheduler 参数）
     */
    @Deprecated("直接使用 setAppContext 和 setMainHandler", level = DeprecationLevel.WARNING)
    fun setDependencies(appContext: Context?, mainHandler: Handler?) {
        this.appContext = appContext
        this.mainHandler = mainHandler
    }

    /**
     * 安全地初始化协程调度器
     */
    fun initializeAlarmScheduler(context: Context?): Boolean {
        context ?: run {
            Log.error(SCHEDULER_TAG, "初始化协程调度器失败: Context为null")
            return false
        }

        return try {
            // 清理旧实例
            coroutineScheduler?.let {
                Log.record(SCHEDULER_TAG, "协程调度器已存在，先清理旧实例")
                cleanupAlarmScheduler()
            }

            // 创建新实例
            coroutineScheduler = CoroutineScheduler(context)
            appContext = context

            Log.record(SCHEDULER_TAG, "✅ 协程调度器初始化成功 (无闹钟模式)")
            true
        } catch (e: Exception) {
            Log.error(SCHEDULER_TAG, "❌ 协程调度器初始化失败: ${e.message}")
            Log.printStackTrace(SCHEDULER_TAG, e)
            false
        }
    }

    /**
     * 安全地清理协程调度器
     */
    fun cleanupAlarmScheduler() {
        coroutineScheduler?.let { scheduler ->
            try {
                Log.record(SCHEDULER_TAG, "🧹 开始清理协程调度器")
                scheduler.cleanup()
                Log.record(SCHEDULER_TAG, "✅ 协程调度器清理完成")
            } catch (e: Exception) {
                Log.error(SCHEDULER_TAG, "❌ 清理协程调度器失败: ${e.message}")
                Log.printStackTrace(SCHEDULER_TAG, e)
            } finally {
                coroutineScheduler = null
            }
        }
    }

    /**
     * 安全地调度精确执行（无闹钟版本，直接转换为延迟执行）
     * 
     * @param delayMillis 延迟毫秒数
     * @param exactTimeMillis 精确时间戳（无闹钟版本不使用，保留参数仅为兼容性）
     */
    @Deprecated("参数 exactTimeMillis 未使用，建议直接调用 scheduleDelayedExecution", ReplaceWith("scheduleDelayedExecution(delayMillis)"))
    fun scheduleExactExecution(delayMillis: Long, @Suppress("UNUSED_PARAMETER") exactTimeMillis: Long) {
        scheduleDelayedExecution(delayMillis)
    }

    /**
     * 安全地调度延迟执行
     */
    fun scheduleDelayedExecution(delayMillis: Long): Boolean {
        return executeWithScheduler { scheduler ->
            scheduler.scheduleDelayedExecution(delayMillis)
            Log.record(SCHEDULER_TAG, "⏰ 延迟执行调度成功: 延迟${delayMillis}ms (协程模式)")
            true
        } ?: false
    }

    // ⚠️ 已彻底移除闹钟相关方法（scheduleWakeupAlarm, handleAlarmTrigger）

    /**
     * 带重试机制的延迟执行调度
     */
    fun scheduleDelayedExecutionWithRetry(delayMillis: Long, operation: String) {
        scheduleDelayedExecutionWithRetry(delayMillis, operation, 0)
    }
    

    /**
     * 核心辅助方法：安全执行协程调度器操作
     */
    private inline fun <T> executeWithScheduler(action: (CoroutineScheduler) -> T): T? {
        // 检查并确保协程调度器可用
        if (!ensureSchedulerAvailable()) {
            return null
        }

        return try {
            coroutineScheduler?.let(action)
        } catch (e: Exception) {
            Log.error(SCHEDULER_TAG, "❌ 调度操作失败: ${e.message}")
            Log.printStackTrace(SCHEDULER_TAG, e)
            null
        }
    }

    /**
     * 确保协程调度器可用，如果不可用则尝试重新初始化
     */
    private fun ensureSchedulerAvailable(): Boolean {
        if (isAlarmSchedulerAvailable) return true

        Log.runtime(SCHEDULER_TAG, "⚠️ 协程调度器不可用，尝试重新初始化")

        return if (appContext != null && initializeAlarmScheduler(appContext)) {
            true // 重新初始化成功
        } else {
            Log.error(SCHEDULER_TAG, "❌ 协程调度器重新初始化失败")
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
            Log.runtime(SCHEDULER_TAG, "⏳ ${operation}失败，${retryDelay}ms后重试 (第${retryCount + 1}次)")

            mainHandler?.postDelayed({
                // 重试前尝试重新初始化协程调度器
                if (!isAlarmSchedulerAvailable) {
                    initializeAlarmScheduler(appContext)
                }
                scheduleDelayedExecutionWithRetry(delayMillis, operation, retryCount + 1)
            }, retryDelay)
        } else {
            Log.error(SCHEDULER_TAG, "❌ ${operation}重试超过最大次数，操作失败")
        }
    }

    companion object {
        private const val SCHEDULER_TAG = "CoroutineScheduler"
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_BASE = 2000L // 2秒基础延迟
    }
}
