package fansirsqi.xposed.sesame.task

import android.annotation.SuppressLint
import fansirsqi.xposed.sesame.hook.ApplicationHook
import fansirsqi.xposed.sesame.model.BaseModel
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
import fansirsqi.xposed.sesame.util.WakeLockManager
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
 * 5. **超时控制**: 任务超时后自动停止并继续下一个任务
 * 6. **统计监控**: 提供详细的执行统计和性能指标
 * 7. **错误处理**: 完善的异常处理机制
 */
class CoroutineTaskRunner(allModels: List<Model>) {
    companion object {
        private const val TAG = "CoroutineTaskRunner"
        
        /**
         * 任务超时时间配置（毫秒）
         * 优化后的固定超时时间，足够各类任务完成
         * - 森林：主任务完成后，蹲点在后台独立运行，不占用主流程
         * - 庄园：主任务完成后，定时任务在后台独立运行
         * - 其他：一般任务都能在此时间内完成
         */
        private const val DEFAULT_TASK_TIMEOUT = 10 * 60 * 1000L // 10分钟统一超时
    }

    private val taskList: List<ModelTask> = allModels.filterIsInstance<ModelTask>()
    private val successCount = AtomicInteger(0)
    private val failureCount = AtomicInteger(0)
    private val skippedCount = AtomicInteger(0)
    
    // 性能监控指标
    private val taskExecutionTimes = ConcurrentHashMap<String, Long>()
    private val coroutineCreationCount = AtomicInteger(0)
    private val logRecordCount = AtomicInteger(0)
    
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
     * @param rounds 执行轮数，默认从BaseModel配置读取
     */
    fun run(
        isFirst: Boolean = true,
        rounds: Int = BaseModel.taskExecutionRounds.value
    ) {
        runnerScope.launch {
            if (isFirst) {
                resetCounters()
            }
            
            val startTime = System.currentTimeMillis()
            
            try {
                executeTasksWithMode(rounds)
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "任务执行异常", e)
            } finally {
                val endTime = System.currentTimeMillis()
                printExecutionSummary(startTime, endTime)

                // 调度下次执行
                try {
                    ApplicationHook.scheduleNextExecution()
                    Log.record(TAG, "✅ 已调度下次执行")
                } catch (e: Exception) {
                    Log.error(TAG, "调度下次执行失败: ${e.message}")
                    Log.printStackTrace(TAG, e)
                }
            }
        }
    }

    /**
     * 执行任务（仅支持顺序执行）
     */
    private suspend fun executeTasksWithMode(
        rounds: Int
    ) {
        // 无论传入什么模式，都使用顺序执行
        executeSequentialTasks(rounds)
    }

    /**
     * 顺序执行所有任务
     */
    private suspend fun executeSequentialTasks(rounds: Int) {
        val configuredRounds = BaseModel.taskExecutionRounds.value
        Log.record(TAG, "⚙️ 任务执行配置：传入${rounds}轮，BaseModel配置${configuredRounds}轮（用户可在基础设置中调整）")
        
        for (round in 1..rounds) {
            val roundStartTime = System.currentTimeMillis()
            val enabledTasksInRound = taskList.filter { it.isEnable }
            
            Log.record(TAG, "🔄 开始顺序执行第${round}/${rounds}轮任务，共${enabledTasksInRound.size}个启用任务")
            
            for ((index, task) in enabledTasksInRound.withIndex()) {
                Log.record(TAG, "📍 第${round}轮任务进度: ${index + 1}/${enabledTasksInRound.size} - ${task.getName()}")
                executeTaskWithTimeout(task, round)
            }
            
            val roundTime = System.currentTimeMillis() - roundStartTime
            Log.record(TAG, "✅ 第${round}/${rounds}轮任务完成，耗时: ${roundTime}ms")
        }
    }


    /**
     * 执行单个任务（带智能超时控制和自动恢复机制）
     */
    private suspend fun executeTaskWithTimeout(task: ModelTask, round: Int) {
        val taskId = "${task.getName()}-Round$round"
        val taskStartTime = System.currentTimeMillis()
        
        // 所有任务统一使用10分钟超时
        // 森林和庄园的蹲点/定时任务会在后台独立协程中运行，不影响主流程
        val effectiveTimeout = DEFAULT_TASK_TIMEOUT
        
        Log.record(TAG, "🚀 开始执行任务[$taskId]，超时设置: ${effectiveTimeout/1000}秒")
        try {
            // 使用智能超时机制
            executeTaskWithGracefulTimeout(task, round, taskStartTime, taskId, effectiveTimeout)
            val executionTime = System.currentTimeMillis() - taskStartTime
            Log.record(TAG, "✅ 任务[$taskId]执行完成，耗时: ${executionTime}ms")
        } catch (_: TimeoutCancellationException) {
            val executionTime = System.currentTimeMillis() - taskStartTime
            failureCount.incrementAndGet()
            val timeoutMsg = "${executionTime}ms > ${effectiveTimeout}ms"
            Log.record(TAG, "⏰ 任务[$taskId]执行超时($timeoutMsg)，停止任务并继续下一个")
            
            // 停止超时任务，释放资源
            try {
                task.stopTask()
            } catch (e: Exception) {
                Log.record(TAG, "停止超时任务[$taskId]时出错: ${e.message}")
            }
            
            // 记录任务状态信息（用于调试）
            logTaskStatusInfo(task, taskId)
            // 直接返回，继续执行下一个任务，不进行自动恢复
            return
        }
    }

    /**
     * 带超时控制的任务执行机制
     * 在规定时间内执行任务，超时后直接停止并继续下一个任务
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
            // 超时后直接停止任务并继续执行下一个
            val executionTime = System.currentTimeMillis() - taskStartTime
            Log.record(TAG, "⏰ 任务[$taskId]执行超时(${executionTime}ms)，停止任务并继续下一个")
            // 停止超时任务，释放资源
            try {
                task.stopTask()
            } catch (ex: Exception) {
                Log.record(TAG, "停止超时任务[$taskId]时出错: ${ex.message}")
            }
            // 抛出异常让外层 catch 处理失败计数
            throw e
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
            Log.record(TAG, "🎯 启动模块[${taskName}]第${round}轮执行...")
            logRecordCount.incrementAndGet() // 性能监控：记录日志调用次数
            // 启动任务（使用新的协程接口）
            coroutineCreationCount.incrementAndGet() // 性能监控：协程创建计数
            val job = task.startTask(
                force = false,
                rounds = 1
            )

            // 监控任务执行状态
            val monitorJob = runnerScope.launch {
                var lastLogTime = System.currentTimeMillis()
                try {
                    while (job.isActive) {
                        delay(10000) // 每10秒检查一次
                        val currentTime = System.currentTimeMillis()
                        val runningTime = currentTime - taskStartTime
                        if (currentTime - lastLogTime >= 10000) { // 每10秒输出一次状态
                            Log.record(TAG, "🔄 模块[${taskName}]第${round}轮运行中... 已执行${runningTime/1000}秒")
                            lastLogTime = currentTime
                        }
                    }
                } catch (_: CancellationException) {
                    // 监控协程被取消是正常的
                }
            }
            
            // 等待任务完成
            try {
                job.join()
            } finally {
                // 确保监控协程被取消
                monitorJob.cancel()
            }
            
            val executionTime = System.currentTimeMillis() - taskStartTime
            successCount.incrementAndGet()
            
            // 性能监控：记录任务执行时间
            val taskId = "${taskName}-Round${round}"
            taskExecutionTimes[taskId] = executionTime
            
            Log.record(TAG, "✅ 模块[${taskName}]第${round}轮执行成功，耗时: ${executionTime}ms")
            logRecordCount.incrementAndGet()
            
        } catch (_: CancellationException) {
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

            Log.runtime(TAG, "📊 任务[$taskId]状态信息:")
            Log.runtime(TAG, "  - 任务名称: $taskName")
            Log.runtime(TAG, "  - 是否启用: $isEnabled")
            Log.runtime(TAG, "  - 是否运行中: $isRunning")

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
        
        // 重置性能监控指标
        taskExecutionTimes.clear()
        coroutineCreationCount.set(0)
        logRecordCount.set(0)
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
        
        // 计算成功率
        val totalExecuted = successCount.get() + failureCount.get()
        if (totalExecuted > 0) {
            val successRate = (successCount.get() * 100.0) / totalExecuted
            Log.record(TAG, "📊 成功率: ${String.format("%.1f", successRate)}%")
        }
        
        // 性能监控指标
        Log.runtime(TAG, "⚡ 性能指标:")
        Log.runtime(TAG, "  - 协程创建次数: ${coroutineCreationCount.get()}")
        Log.runtime(TAG, "  - 日志记录次数: ${logRecordCount.get()}")
        
        // 任务执行时间分析
        if (taskExecutionTimes.isNotEmpty()) {
            val avgTime = taskExecutionTimes.values.average()
            val maxTime = taskExecutionTimes.values.maxOrNull() ?: 0L
            val minTime = taskExecutionTimes.values.minOrNull() ?: 0L
            Log.runtime(TAG, "  - 任务平均耗时: ${String.format("%.1f", avgTime)}ms")
            Log.runtime(TAG, "  - 最长任务耗时: ${maxTime}ms")
            Log.runtime(TAG, "  - 最短任务耗时: ${minTime}ms")
            
            // 找出最慢的3个任务
            val slowestTasks = taskExecutionTimes.entries
                .sortedByDescending { it.value }
                .take(3)
            if (slowestTasks.isNotEmpty()) {
                Log.runtime(TAG, "  - 最慢的任务:")
                slowestTasks.forEach { (taskId, time) ->
                    Log.runtime(TAG, "    * $taskId: ${time}ms")
                }
            }
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
