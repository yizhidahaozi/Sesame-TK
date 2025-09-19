package fansirsqi.xposed.sesame.task

import android.annotation.SuppressLint
import fansirsqi.xposed.sesame.hook.ApplicationHook
import fansirsqi.xposed.sesame.model.BaseModel
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 基于协程的任务执行器类
 * 
 * 该类替代原有的TaskRunner，提供基于Kotlin协程的任务执行能力。
 * 仅支持顺序执行模式，确保任务执行的稳定性和可靠性。
 * 
 * 主要特性:
 * 1. **协程驱动**: 使用Kotlin协程进行任务调度和执行
 * 2. **结构化并发**: 通过协程作用域管理任务生命周期
 * 3. **顺序执行**: 按顺序一个接一个执行任务，避免并发冲突
 * 4. **多轮执行**: 支持配置任务执行轮数
 * 5. **统计监控**: 提供详细的执行统计和状态监控
 * 6. **错误处理**: 完善的异常处理和恢复机制
 * 7. **自动恢复**: 任务超时自动恢复机制
 */
class CoroutineTaskRunner(allModels: List<Model>) {
    companion object {
        private const val TAG = "CoroutineTaskRunner"
        
        // 默认任务超时设置（毫秒）- 当配置为-1时使用
        private const val DEFAULT_TASK_TIMEOUT = 180_000L // 默认3分钟
        
        /**
         * 获取动态任务超时时间
         * @return 超时时间（毫秒），-1表示无限等待
         */
        private fun getTaskTimeout(): Long {
            return try {
                val waitTimeMinutes = BaseModel.taskWaitTime.value
                if (waitTimeMinutes == -1) {
                    -1L // 无限等待
                } else {
                    waitTimeMinutes * 60 * 1000L // 转换为毫秒
                }
            } catch (e: Exception) {
                Log.error(TAG, "获取任务等待时间配置失败，使用默认值: ${e.message}")
                DEFAULT_TASK_TIMEOUT
            }
        }
        
        // 恢复任务的超时设置（毫秒）- 只用于日志提示，不会取消恢复任务
        private const val RECOVERY_TIMEOUT = 30_000L // 增加到30秒
        
        // 恢复前的延迟时间（毫秒）
        private const val RECOVERY_DELAY = 3_000L // 增加到3秒，给任务更多清理时间
        
        // 最大恢复尝试次数
        private const val MAX_RECOVERY_ATTEMPTS = 3 // 增加到3次
        
        // 恢复任务的最大运行时间（毫秒）- 超过此时间后任务会被自动标记为完成
        private const val MAX_RECOVERY_RUNTIME = 10 * 60 * 1000L // 10分钟
    }

    private val taskList: List<ModelTask> = allModels.filterIsInstance<ModelTask>()
    private val successCount = AtomicInteger(0)
    private val failureCount = AtomicInteger(0)
    private val skippedCount = AtomicInteger(0)
    
    // 记录任务恢复尝试次数
    private val recoveryAttempts = ConcurrentHashMap<String, Int>()
    
    // 执行器协程作用域
    private val runnerScope = CoroutineScope(
        Dispatchers.Default + 
        SupervisorJob() + 
        CoroutineName("CoroutineTaskRunner")
    )

    init {
        Log.record(TAG, "初始化协程任务执行器，共发现 ${taskList.size} 个任务")
    }

    /**
     * 启动任务执行流程（协程版本）
     * 
     * @param isFirst 是否为首次执行（用于重置统计计数器）
     * @param mode 执行模式（仅支持顺序执行）
     * @param rounds 执行轮数，默认2轮
     */
    fun run(
        isFirst: Boolean = true,
        mode: ModelTask.TaskExecutionMode = ModelTask.TaskExecutionMode.SEQUENTIAL,
        rounds: Int = 2
    ) {
        runnerScope.launch {
            if (isFirst) {
                resetCounters()
            }
            
            val startTime = System.currentTimeMillis()
            
            try {
                executeTasksWithMode(mode, rounds)
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "任务执行异常", e)
            } finally {
                val endTime = System.currentTimeMillis()
                printExecutionSummary(startTime, endTime)
                // 清空恢复尝试计数
                recoveryAttempts.clear()
            }
        }
    }

    /**
     * 执行任务（仅支持顺序执行）
     */
    private suspend fun executeTasksWithMode(
        mode: ModelTask.TaskExecutionMode,
        rounds: Int
    ) {
        // 无论传入什么模式，都使用顺序执行
        executeSequentialTasks(rounds)
    }

    /**
     * 顺序执行所有任务
     */
    private suspend fun executeSequentialTasks(rounds: Int) {
        for (round in 1..rounds) {
            val roundStartTime = System.currentTimeMillis()
            val enabledTasksInRound = taskList.filter { it.isEnable }
            
            Log.record(TAG, "🔄 开始顺序执行第${round}轮任务，共${enabledTasksInRound.size}个启用任务")
            
            for ((index, task) in enabledTasksInRound.withIndex()) {
                Log.record(TAG, "📍 第${round}轮任务进度: ${index + 1}/${enabledTasksInRound.size} - ${task.getName()}")
                executeTaskWithTimeout(task, round)
            }
            
            val roundTime = System.currentTimeMillis() - roundStartTime
            Log.record(TAG, "✅ 第${round}轮任务完成，耗时: ${roundTime}ms")
        }
    }


    /**
     * 执行单个任务（带智能超时控制和自动恢复机制）
     */
    private suspend fun executeTaskWithTimeout(task: ModelTask, round: Int) {
        val taskId = "${task.getName()}-Round$round"
        val taskStartTime = System.currentTimeMillis()
        val taskTimeout = getTaskTimeout()
        
        val timeoutText = if (taskTimeout == -1L) {
            "无限等待"
        } else {
            "${taskTimeout/1000}秒"
        }
        Log.record(TAG, "🚀 开始执行任务[$taskId]，超时设置: $timeoutText")
        try {
            // 使用智能超时机制
            executeTaskWithGracefulTimeout(task, round, taskStartTime, taskId, taskTimeout)
            val executionTime = System.currentTimeMillis() - taskStartTime
            Log.record(TAG, "✅ 任务[$taskId]执行完成，耗时: ${executionTime}ms")
        } catch (e: TimeoutCancellationException) {
            val executionTime = System.currentTimeMillis() - taskStartTime
            failureCount.incrementAndGet()
            val timeoutMsg = if (taskTimeout == -1L) "无限等待模式异常" else "${executionTime}ms > ${taskTimeout}ms"
            Log.error(TAG, "⏰ 任务[$taskId]执行超时($timeoutMsg)，准备自动恢复")
            
            // 记录任务状态信息
            logTaskStatusInfo(task, taskId)
            
            // 获取当前恢复尝试次数
            val attempts = recoveryAttempts.getOrPut(taskId) { 0 }
            
            // 检查是否超过最大尝试次数
            if (attempts >= MAX_RECOVERY_ATTEMPTS) {
                Log.error(TAG, "任务[$taskId]已达到最大恢复尝试次数($MAX_RECOVERY_ATTEMPTS)，放弃恢复")
                return
            }
            
            // 增加恢复尝试计数
            recoveryAttempts[taskId] = attempts + 1
            
            // 取消当前任务的所有协程
            task.stopTask()
            
            // 短暂延迟后重新启动任务
            delay(RECOVERY_DELAY) // 等待2秒钟
            
            try {
                Log.record(TAG, "正在自动恢复任务[$taskId]，第${attempts + 1}次尝试")
                // 强制重启任务
                val recoveryJob = task.startTask(
                    force = true,
                    mode = ModelTask.TaskExecutionMode.SEQUENTIAL,
                    rounds = 1
                )
                
                // 使用非阻塞方式等待任务完成
                try {
                    // 创建监控协程，负责监控恢复任务的状态
                    runnerScope.launch {
                        // 监控超时提示（不取消任务）
                        delay(RECOVERY_TIMEOUT)
                        if (recoveryJob?.isActive == true) {
                            Log.record(TAG, "任务[$taskId]恢复执行已超过${RECOVERY_TIMEOUT/1000}秒，继续在后台运行")
                        }
                        
                        // 监控最大运行时间
                        delay(MAX_RECOVERY_RUNTIME - RECOVERY_TIMEOUT)
                        if (recoveryJob?.isActive == true) {
                            Log.record(TAG, "任务[$taskId]恢复执行已超过最大运行时间(${MAX_RECOVERY_RUNTIME/1000/60}分钟)，标记为已完成")
                            // 取消恢复任务，避免无限运行
                            recoveryJob.cancel()
                            // 标记为成功，避免重复恢复
                            successCount.incrementAndGet()
                        }
                    }
                    
                    // 等待恢复任务完成或超时任务触发
                    recoveryJob?.invokeOnCompletion { cause ->
                        when (cause) {
                            null -> {
                                // 任务正常完成
                                successCount.incrementAndGet()
                                Log.record(TAG, "任务[$taskId]自动恢复成功")
                            }
                            is CancellationException -> {
                                // 任务被取消（可能是由于超时或手动取消）
                                Log.record(TAG, "任务[$taskId]恢复过程被取消")
                            }

                            else -> {
                                // 任务因错误而结束
                                Log.error(TAG, "任务[$taskId]恢复过程中出错: ${cause.message}")
                                Log.printStackTrace(cause)
                            }
                        }
                    }
                    
                    // 不阻塞当前协程，让恢复任务在后台继续执行
                } catch (e: Exception) {
                    Log.error(TAG, "监控恢复任务时出错: ${e.message}")
                    Log.printStackTrace(e)
                }
            } catch (e2: Exception) {
                Log.error(TAG, "任务[$taskId]自动恢复失败: ${e2.message}")
                Log.printStackTrace(e2)
            }
        }
    }

    /**
     * 智能超时执行机制
     * 当接近超时时给任务额外的时间来完成，避免强制中断
     * 支持用户配置的动态超时时间
     */
    private suspend fun executeTaskWithGracefulTimeout(
        task: ModelTask, 
        round: Int, 
        taskStartTime: Long, 
        taskId: String,
        taskTimeout: Long
    ) {
        // 如果配置为无限等待，直接执行任务
        if (taskTimeout == -1L) {
            Log.runtime(TAG, "🔄 任务[$taskId]配置为无限等待，直接执行...")
            executeTask(task, round)
            return
        }
        
        try {
            withTimeout(taskTimeout) {
                executeTask(task, round)
            }
        } catch (e: TimeoutCancellationException) {
            // 超时后检查任务是否还在运行
            val currentTime = System.currentTimeMillis()
            val runningTime = currentTime - taskStartTime
            Log.runtime(TAG, "⚠️ 任务[$taskId]达到基础超时(${runningTime}ms)，检查是否可以继续等待...")
            if (task.isRunning) {
                // 给任务额外30秒的宽限期
                val gracePeriod = 30_000L
                Log.runtime(TAG, "🕐 任务[$taskId]仍在运行，给予${gracePeriod/1000}秒宽限期...")
                
                try {
                    withTimeout(gracePeriod) {
                        // 等待任务自然完成
                        while (task.isRunning) {
                            delay(1000)
                            val currentRunningTime = System.currentTimeMillis() - taskStartTime
                            if (currentRunningTime % 10000 < 1000) { // 每10秒输出一次
                                Log.runtime(TAG, "⏳ 任务[$taskId]宽限期运行中... ${currentRunningTime/1000}秒")
                            }
                        }
                        Log.record(TAG, "✅ 任务[$taskId]在宽限期内完成")
                    }
                } catch (graceTimeoutException: TimeoutCancellationException) {
                    // 宽限期也超时了，重新抛出原始超时异常
                    Log.error(TAG, "❌ 任务[$taskId]宽限期(${gracePeriod/1000}秒)也超时，强制超时处理")
                    throw e
                }
            } else {
                // 任务已经不在运行了，重新抛出超时异常
                Log.runtime(TAG, "🔍 任务[$taskId]已停止运行，执行超时处理")
                throw e
            }
        }
    }

    /**
     * 执行单个任务
     */
    private suspend fun executeTask(task: ModelTask, round: Int) {
        val taskName = task.getName()
        val taskStartTime = System.currentTimeMillis()
        
        try {
            task.addRunCents()
            
            val taskPriority = task.priority
            if (round < taskPriority) {
                skippedCount.incrementAndGet()
                Log.record(TAG, "⏭️ 模块[${taskName}]优先级:${taskPriority} 第${round}轮跳过")
                return
            }
            
            Log.record(TAG, "🎯 启动模块[${taskName}]第${round}轮执行...")
            
            // 启动任务（使用新的协程接口）
            val job = task.startTask(
                force = false,
                mode = ModelTask.TaskExecutionMode.SEQUENTIAL,
                rounds = 1
            )
            
            if (job == null) {
                Log.runtime(TAG, "⚠️ 模块[${taskName}]第${round}轮启动失败，job为null")
                skippedCount.incrementAndGet()
                return
            }
            
            // 监控任务执行状态
            val monitorJob = runnerScope.launch {
                var lastLogTime = System.currentTimeMillis()
                while (job.isActive) {
                    delay(10000) // 每10秒检查一次
                    val currentTime = System.currentTimeMillis()
                    val runningTime = currentTime - taskStartTime
                    if (currentTime - lastLogTime >= 10000) { // 每10秒输出一次状态
                        Log.record(TAG, "🔄 模块[${taskName}]第${round}轮运行中... 已执行${runningTime/1000}秒")
                        lastLogTime = currentTime
                    }
                }
            }
            
            // 等待任务完成
            job.join()
            monitorJob.cancel()
            
            val executionTime = System.currentTimeMillis() - taskStartTime
            successCount.incrementAndGet()
            Log.record(TAG, "✅ 模块[${taskName}]第${round}轮执行成功，耗时: ${executionTime}ms")
            
        } catch (e: CancellationException) {
            // 任务取消是正常的协程控制流程，不需要作为错误处理
            val executionTime = System.currentTimeMillis() - taskStartTime
            skippedCount.incrementAndGet()
            Log.record(TAG, "⏹️ 模块[${taskName}]第${round}轮被取消，耗时: ${executionTime}ms")
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - taskStartTime
            failureCount.incrementAndGet()
            Log.error(TAG, "❌ 执行任务[${taskName}]第${round}轮时发生错误(耗时: ${executionTime}ms): ${e.message}")
            Log.printStackTrace(e)
        }
    }


    /**
     * 记录任务状态信息
     */
    private fun logTaskStatusInfo(task: ModelTask, taskId: String) {
        try {
            val isEnabled = task.isEnable
            val isRunning = task.isRunning
            val taskName = task.getName()
            val priority = task.priority
            
            Log.runtime(TAG, "📊 任务[$taskId]状态信息:")
            Log.runtime(TAG, "  - 任务名称: $taskName")
            Log.runtime(TAG, "  - 是否启用: $isEnabled")
            Log.runtime(TAG, "  - 是否运行中: $isRunning")
            Log.runtime(TAG, "  - 任务优先级: $priority")
            
            // 尝试获取更多状态信息
            try {
                val runCents = task.runCents
                val taskScope = if (task.isRunning) "运行中" else "已停止"
                Log.runtime(TAG, "  - 运行次数: $runCents")
                Log.runtime(TAG, "  - 任务状态: $taskScope")
            } catch (e: Exception) {
                Log.runtime(TAG, "  - 任务状态: 获取失败(${e.message})")
            }
            
        } catch (e: Exception) {
            Log.error(TAG, "记录任务状态信息失败: ${e.message}")
        }
    }

    /**
     * 重置计数器
     */
    private fun resetCounters() {
        successCount.set(0)
        failureCount.set(0)
        skippedCount.set(0)
        recoveryAttempts.clear()
    }

    /**
     * 打印执行摘要
     */
    @SuppressLint("DefaultLocale")
    private fun printExecutionSummary(startTime: Long, endTime: Long) {
        val totalTime = endTime - startTime
        val totalTasks = taskList.size
        val enabledTasks = taskList.count { it.isEnable }
        
        Log.record(TAG, "📈 ===== 协程任务执行统计摘要 =====")
        Log.record(TAG, "🕐 执行时间: ${totalTime}ms (${String.format("%.1f", totalTime/1000.0)}秒)")
        val nextTime = ApplicationHook.nextExecutionTime
        if (nextTime > 0) {
            Log.record(TAG, "📅 下次执行: ${TimeUtil.getCommonDate(nextTime)}")
        }
        Log.record(TAG, "📋 任务总数: $totalTasks (启用: $enabledTasks)")
        Log.record(TAG, "✅ 成功任务: ${successCount.get()}")
        Log.record(TAG, "❌ 失败任务: ${failureCount.get()}")
        Log.record(TAG, "⏭️ 跳过任务: ${skippedCount.get()}")
        Log.record(TAG, "🔄 恢复尝试: ${recoveryAttempts.size}")
        
        if (recoveryAttempts.isNotEmpty()) {
            Log.record(TAG, "🔧 恢复详情:")
            recoveryAttempts.forEach { (taskId, attempts) ->
                Log.record(TAG, "  - $taskId: $attempts 次尝试")
            }
        }
        
        // 计算成功率
        val totalExecuted = successCount.get() + failureCount.get()
        if (totalExecuted > 0) {
            val successRate = (successCount.get() * 100.0) / totalExecuted
            Log.record(TAG, "📊 成功率: ${String.format("%.1f", successRate)}%")
        }
        
        // 性能分析
        if (totalTime > 60000) { // 超过1分钟
            Log.runtime(TAG, "⚠️ 执行时间较长，建议检查任务配置或网络状况")
        }
        
        Log.record(TAG, "================================")
    }

    /**
     * 停止任务执行器
     */
    fun stop() {
        runnerScope.cancel()
        Log.record(TAG, "协程任务执行器已停止")
    }
}
