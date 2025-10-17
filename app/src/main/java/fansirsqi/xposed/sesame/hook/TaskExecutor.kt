package fansirsqi.xposed.sesame.hook

/**
 * 任务执行器接口
 * 用于解耦 AlarmScheduler 和 ApplicationHook，避免反射调用
 *
 * 职责：
 * 1. 检查主任务运行状态
 * 2. 执行任务重启操作
 */
interface TaskExecutor {
    /**
     * 检查主任务是否正在运行
     * @return true 如果任务正在运行，false 否则
     */
    fun isTaskRunning(): Boolean

    /**
     * 通过广播重启任务
     * 用于备份机制触发时重启任务执行
     */
    fun restartTask()
}

