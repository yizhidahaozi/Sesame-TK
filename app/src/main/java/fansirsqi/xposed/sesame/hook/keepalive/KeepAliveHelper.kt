package fansirsqi.xposed.sesame.hook.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import fansirsqi.xposed.sesame.util.Log

/**
 * 保活助手 (Android 9+)
 *
 * 功能：
 * 1. 防止息屏（检测到即将执行任务时保持屏幕常亮）
 * 2. 监听系统广播保持进程活跃
 * 3. 提前唤醒机制
 * 4. WakeLock 管理
 *
 * 系统要求：Android 9.0 (API 28) 及以上
 */
class KeepAliveHelper(
    private val context: Context,
    private val onUpcomingTask: (timeUntilExecution: Long) -> Unit
) {

    companion object {
        private const val TAG = "KeepAliveHelper"

        // 最低 API 级别
        private const val MIN_API_LEVEL = Build.VERSION_CODES.P // Android 9.0

        // 提前唤醒阈值
        private const val EARLY_WAKEUP_THRESHOLD = 300000L // 5 分钟
        private const val IMMEDIATE_WAKEUP_THRESHOLD = 120000L // 2 分钟

        // WakeLock 超时时间
        private const val WAKELOCK_TIMEOUT = 10 * 60 * 1000L // 10 分钟
    }

    // 系统广播接收器
    private var systemBroadcastReceiver: BroadcastReceiver? = null

    // PowerManager（用于屏幕状态检测）
    private val powerManager: PowerManager? by lazy {
        try {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        } catch (e: Exception) {
            Log.error(TAG, "获取 PowerManager 失败: ${e.message}")
            null
        }
    }

    // 是否已启动
    @Volatile
    private var isRunning = false
    
    // 性能优化：TIME_TICK 节流（避免过度保活）
    @Volatile
    private var lastTimeTickHandled = 0L
    private val TIME_TICK_THROTTLE = 60000L // 1分钟节流

    /**
     * 检查系统版本是否支持
     */
    fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= MIN_API_LEVEL
    }

    /**
     * 启动保活助手
     */
    fun start() {
        if (!isSupported()) {
            Log.record(TAG, "⚠️ 当前系统版本 Android ${Build.VERSION.SDK_INT}，需要 Android 9+ (API 28+)")
            return
        }

        if (isRunning) {
            Log.debug(TAG, "保活助手已在运行")
            return
        }

        isRunning = true

        // 注册系统广播
        registerSystemBroadcast()

        Log.runtime(TAG, "✅ 保活助手已启动 (Android ${Build.VERSION.SDK_INT})")
    }

    /**
     * 停止保活助手
     */
    fun stop() {
        if (!isRunning) return

        isRunning = false

        // 注销系统广播
        unregisterSystemBroadcast()

        // 释放唤醒锁（通过统一管理器）
        try {
            WakeLockManager.release("CPU保活-停止")
        } catch (e: Exception) {
            Log.debug(TAG, "释放唤醒锁失败: ${e.message}")
        }

        // 取消支付宝的 keepScreenOn
        AlipayMethodHelper.callKeepScreenOn(false)

        Log.runtime(TAG, "保活助手已停止")
    }

    /**
     * 注册系统广播监听（性能优化版）
     * 
     * 优化：只在已注册时才注销，避免不必要的操作
     */
    private fun registerSystemBroadcast() {
        try {
            // 性能优化：只在已注册时才注销
            if (systemBroadcastReceiver != null) {
                unregisterSystemBroadcast()
            }

            // 创建广播接收器
            systemBroadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    try {
                        val action = intent?.action ?: return
                        handleSystemEvent(action)
                    } catch (e: Exception) {
                        Log.error(TAG, "处理系统广播异常: ${e.message}")
                    }
                }
            }

            // 构建 IntentFilter
            val filter = IntentFilter().apply {
                // 屏幕相关
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)

                // 时间相关（保活核心）
                addAction(Intent.ACTION_TIME_TICK)  // 每分钟

                // 网络相关
                addAction("android.net.conn.CONNECTIVITY_CHANGE")
            }

            // 注册广播（Android 9+ 使用非导出模式）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    systemBroadcastReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(systemBroadcastReceiver, filter)
            }

            Log.runtime(TAG, "✅ 已注册系统广播监听")
            Log.debug(TAG, "监听事件：亮屏/息屏/解锁/时间/网络")

        } catch (e: Exception) {
            Log.error(TAG, "注册系统广播失败: ${e.message}")
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 注销系统广播监听
     */
    private fun unregisterSystemBroadcast() {
        try {
            systemBroadcastReceiver?.let { receiver ->
                context.unregisterReceiver(receiver)
                systemBroadcastReceiver = null
                Log.runtime(TAG, "已注销系统广播监听")
            }
        } catch (e: Exception) {
            Log.debug(TAG, "注销系统广播失败: ${e.message}")
        }
    }

    /**
     * 处理系统事件（性能优化版）
     * 
     * 优化：TIME_TICK 添加节流和轻量级保活，降低耗电
     */
    private fun handleSystemEvent(action: String) {
        val currentTime = System.currentTimeMillis()

        when (action) {
            Intent.ACTION_SCREEN_ON -> {
                Log.debug(TAG, "📱 系统广播: 屏幕点亮")
                onUpcomingTask(0) // 通知可能有即将执行的任务
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.debug(TAG, "📱 系统广播: 屏幕息屏")
                // 息屏时检查是否有即将执行的任务
                checkAndPreventScreenOff()
            }
            Intent.ACTION_USER_PRESENT -> {
                Log.debug(TAG, "📱 系统广播: 用户解锁")
                onUpcomingTask(0)
            }
            "android.net.conn.CONNECTIVITY_CHANGE" -> {
                Log.debug(TAG, "📱 系统广播: 网络状态变化")
            }
            Intent.ACTION_TIME_TICK -> {
                // 性能优化：TIME_TICK 节流 + 轻量级保活
                // 每分钟触发一次，做最小必要的保活操作
                if (currentTime - lastTimeTickHandled >= TIME_TICK_THROTTLE) {
                    lastTimeTickHandled = currentTime
                    // 轻量级保活：只调用一次唤醒，不做复杂操作
                    AlipayMethodHelper.callWakeup()
                    // 降低日志级别，避免日志刷屏
                    Log.debug(TAG, "⏰ TIME_TICK 保活")
                }
            }
        }
    }

    /**
     * 检查并防止息屏（如果有即将执行的任务）
     * 
     * 优化版：仅通知外部检查任务，不主动阻止息屏
     * 避免频繁开启屏幕保持导致费电
     */
    private fun checkAndPreventScreenOff() {
        try {
            Log.debug(TAG, "检查是否需要防止息屏...")

            // 通知外部检查任务（由外部决定是否需要保持屏幕）
            onUpcomingTask(0)
            
            // 仅调用进程唤醒，不强制屏幕常亮
            AlipayMethodHelper.callWakeup()

        } catch (e: Exception) {
            Log.error(TAG, "检查防止息屏异常: ${e.message}")
        }
    }

    /**
     * 防止息屏（保持屏幕常亮）
     * 
     * 使用支付宝的 keepScreenOn 方法
     * 
     * ⚠️ 注意：此方法会持续保持屏幕常亮，请谨慎使用！
     * 仅在任务即将执行（30秒内）时调用
     */
    fun preventScreenOff() {
        try {
            // 检查屏幕是否已经点亮
            val isScreenOn = powerManager?.isInteractive ?: false

            if (!isScreenOn) {
                // 屏幕已息屏，调用支付宝的 keepScreenOn 方法
                AlipayMethodHelper.callKeepScreenOn(true)
                Log.record(TAG, "🔆 屏幕已息屏，调用支付宝防止息屏")
            } else {
                Log.debug(TAG, "屏幕已点亮，无需防止息屏")
            }

        } catch (e: Exception) {
            Log.error(TAG, "防止息屏失败: ${e.message}")
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 唤醒屏幕（立即点亮屏幕）
     * 
     * ⚠️ 已禁用：屏幕唤醒会严重费电
     * 改为仅使用 CPU 唤醒，不主动点亮屏幕
     */
    fun wakeUpScreen() {
        try {
            // 检查屏幕是否已经点亮
            val isScreenOn = powerManager?.isInteractive ?: false

            if (isScreenOn) {
                Log.debug(TAG, "屏幕已点亮，无需唤醒")
                return
            }

            // 优化：不主动唤醒屏幕，仅唤醒进程
            AlipayMethodHelper.callWakeup()
            Log.debug(TAG, "💡 已唤醒进程（未点亮屏幕，省电）")

        } catch (e: Exception) {
            Log.error(TAG, "唤醒进程失败: ${e.message}")
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 保持 CPU 唤醒（防止深度休眠）
     * 
     * 优化：使用统一唤醒锁管理器，避免重复创建
     * 默认持有时间：10分钟 → 5分钟
     *
     * @param durationMillis 保持时长（毫秒）
     */
    fun keepCpuAwake(durationMillis: Long = WAKELOCK_TIMEOUT) {
        try {
            // 使用统一唤醒锁管理器（优化持有时间）
            val safeDuration = durationMillis.coerceAtMost(5 * 60 * 1000L)
            WakeLockManager.acquire("CPU保活", safeDuration)
            Log.record(TAG, "⚡ 已保持 CPU 唤醒 ${safeDuration / 1000}秒（统一管理）")
        } catch (e: Exception) {
            Log.error(TAG, "保持 CPU 唤醒失败: ${e.message}")
            Log.printStackTrace(TAG, e)
        }
    }


    /**
     * 清理资源
     */
    fun cleanup() {
        stop()
        lastTimeTickHandled = 0L
        Log.runtime(TAG, "保活助手资源已清理")
    }
}