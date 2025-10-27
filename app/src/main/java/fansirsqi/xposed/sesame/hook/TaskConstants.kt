package fansirsqi.xposed.sesame.hook

/**
 * 任务相关常量定义
 * 用于广播通信和任务标识
 */
object TaskConstants {
    /**
     * 广播动作常量
     */
    const val ACTION_EXECUTE = "com.eg.android.AlipayGphone.sesame.execute"
    const val ALARM_CATEGORY = "fansirsqi.xposed.sesame.ALARM_CATEGORY"
    
    /**
     * 唤醒锁相关常量
     */
    const val WAKE_LOCK_SETUP_TIMEOUT = 5000L // 5秒
    const val WAKE_LOCK_EXECUTION_TIMEOUT = 15 * 60 * 1000L // 15分钟
}

