package fansirsqi.xposed.sesame.task

import android.annotation.SuppressLint
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.model.ModelType
import fansirsqi.xposed.sesame.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

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
        
        // 任务超时设置（毫秒）
        private const val TASK_TIMEOUT = 60_000L // 增加到60秒，给复杂任务更多执行时间
        
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
            Log.record(TAG, "开始顺序执行第${round}轮任务")
            
            for (task in taskList) {
                if (task.isEnable) {
                    executeTaskWithTimeout(task, round)
                }
            }
        }
    }


    /**
     * 执行单个任务（带超时控制和自动恢复机制）
     */
    private suspend fun executeTaskWithTimeout(task: ModelTask, round: Int) {
        val taskId = "${task.getName()}-Round$round"
        
        try {
            withTimeout(TASK_TIMEOUT) { // 默认30秒超时
                executeTask(task, round)
            }
        } catch (e: TimeoutCancellationException) {
            failureCount.incrementAndGet()
            Log.error(TAG, "任务[$taskId]执行超时，准备自动恢复")
            
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
     * 执行单个任务
     */
    private suspend fun executeTask(task: ModelTask, round: Int) {
        val taskName = task.getName()
        
        try {
            task.addRunCents()
            
            val taskPriority = task.priority
            if (round < taskPriority) {
                skippedCount.incrementAndGet()
                Log.record(TAG, "模块[${taskName}]优先级:${taskPriority} 第${round}轮跳过")
                return
            }
            
            // 启动任务（使用新的协程接口）
            val job = task.startTask(
                force = false,
                mode = ModelTask.TaskExecutionMode.SEQUENTIAL,
                rounds = 1
            )
            
            // 等待任务完成
            job?.join()
            
            successCount.incrementAndGet()
            Log.record(TAG, "模块[${taskName}]第${round}轮执行成功")
            
        } catch (e: CancellationException) {
            // 任务取消是正常的协程控制流程，不需要作为错误处理
            skippedCount.incrementAndGet()
            Log.record(TAG, "模块[${taskName}]第${round}轮被取消")
        } catch (e: Exception) {
            failureCount.incrementAndGet()
            Log.error(TAG, "执行任务[${taskName}]第${round}轮时发生错误: ${e.message}")
            Log.printStackTrace(e)
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
        val stats = String.format(
            "协程任务执行统计 - 总耗时: %dms, 成功: %d, 失败: %d, 跳过: %d, 恢复尝试: %d",
            totalTime, successCount.get(), failureCount.get(), skippedCount.get(), recoveryAttempts.size
        )
        Log.record(TAG, stats)
    }

    /**
     * 停止任务执行器
     */
    fun stop() {
        runnerScope.cancel()
        Log.record(TAG, "协程任务执行器已停止")
    }
}
