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

    // 部分唤醒锁（防止 CPU 休眠）
    private var partialWakeLock: PowerManager.WakeLock? = null

    // PowerManager
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

        // 释放 CPU WakeLock（如果有）
        releasePartialWakeLock()

        // 取消支付宝的 keepScreenOn
        AlipayMethodHelper.callKeepScreenOn(false)

        Log.runtime(TAG, "保活助手已停止")
    }

    /**
     * 注册系统广播监听
     */
    private fun registerSystemBroadcast() {
        try {
            // 如果已经注册过，先注销
            unregisterSystemBroadcast()

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
     * 处理系统事件
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
                // 每分钟触发一次，静默保活
            }
        }
    }

    /**
     * 检查并防止息屏（如果有即将执行的任务）
     */
    private fun checkAndPreventScreenOff() {
        try {
            // 这里可以通过回调查询是否有即将执行的任务
            // 如果有，则保持屏幕常亮
            Log.debug(TAG, "检查是否需要防止息屏...")

            // 通知外部检查任务
            onUpcomingTask(0)
            
            // 调用支付宝唤醒方法
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
     */
    fun preventScreenOff() {
        try {
            // 调用支付宝的 keepScreenOn 方法
            AlipayMethodHelper.callKeepScreenOn(true)
            
            Log.record(TAG, "🔆 已调用支付宝防止息屏")

        } catch (e: Exception) {
            Log.error(TAG, "防止息屏失败: ${e.message}")
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 唤醒屏幕（立即点亮屏幕）
     * 
     * 使用支付宝的 keepScreenOn 方法
     */
    fun wakeUpScreen() {
        try {
            // 检查屏幕是否已经点亮
            val isScreenOn = powerManager?.isInteractive ?: false

            if (isScreenOn) {
                Log.debug(TAG, "屏幕已点亮，无需唤醒")
                return
            }

            // 调用支付宝的 keepScreenOn 方法
            AlipayMethodHelper.callKeepScreenOn(true)
            
            Log.record(TAG, "💡 已调用支付宝唤醒屏幕")

        } catch (e: Exception) {
            Log.error(TAG, "唤醒屏幕失败: ${e.message}")
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 保持 CPU 唤醒（防止深度休眠）
     *
     * @param durationMillis 保持时长（毫秒）
     */
    fun keepCpuAwake(durationMillis: Long = WAKELOCK_TIMEOUT) {
        try {
            if (powerManager == null) {
                Log.error(TAG, "PowerManager 为 null，无法保持 CPU 唤醒")
                return
            }

            // 释放旧的 WakeLock
            releasePartialWakeLock()

            // 创建新的部分唤醒锁（仅保持 CPU，不点亮屏幕）
            partialWakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Sesame:KeepCpuAwake"
            )?.apply {
                setReferenceCounted(false)
                acquire(durationMillis)
                Log.record(TAG, "⚡ 已保持 CPU 唤醒 ${durationMillis / 1000}秒")
            }

        } catch (e: Exception) {
            Log.error(TAG, "保持 CPU 唤醒失败: ${e.message}")
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 释放部分 WakeLock
     */
    private fun releasePartialWakeLock() {
        try {
            partialWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.debug(TAG, "已释放 CPU WakeLock")
                }
                partialWakeLock = null
            }
        } catch (e: Exception) {
            Log.debug(TAG, "释放 CPU WakeLock 失败: ${e.message}")
        }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        stop()
        Log.runtime(TAG, "保活助手资源已清理")
    }
}