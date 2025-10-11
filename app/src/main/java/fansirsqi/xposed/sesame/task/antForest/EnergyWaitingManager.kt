package fansirsqi.xposed.sesame.task.antForest

import android.annotation.SuppressLint
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.random.Random

/**
 * 能量收取回调接口
 */
interface EnergyCollectCallback {
    /**
     * 收取指定用户的能量（蹲点专用）
     * @param task 蹲点任务信息
     * @return 收取结果信息
     */
    suspend fun collectUserEnergyForWaiting(task: EnergyWaitingManager.WaitingTask): CollectResult
    
    /**
     * 添加能量到总计数
     * @param energyCount 要添加的能量数量
     */
    fun addToTotalCollected(energyCount: Int)
    
    /**
     * 获取蹲点收取延迟时间配置
     * @return 延迟时间（毫秒）
     */
    fun getWaitingCollectDelay(): Long
}

/**
 * 收取结果数据类
 */
    data class CollectResult(
        val success: Boolean,
        val userName: String?,
        val message: String = "",
        val hasShield: Boolean = false,
        val hasBomb: Boolean = false,
        val energyCount: Int = 0,
        val totalCollected: Int = 0  // 累加后的总能量
    )

/**
 * 智能重试策略
 */
class SmartRetryStrategy {
    companion object {
        private val retryDelays = listOf(10000L, 30000L, 60000L, 180000L) // 10秒，30秒，1分钟，3分钟
    }
    
    /**
     * 获取重试延迟时间
     */
    fun getRetryDelay(retryCount: Int, lastError: String?): Long {
        val baseDelay = retryDelays.getOrElse(retryCount) { 180000L }
        
        // 根据错误类型调整延迟
        val multiplier = when {
            lastError?.contains("网络") == true -> 2.0 // 网络错误：延长等待
            lastError?.contains("频繁") == true -> 3.0 // 频繁请求：大幅延长
            lastError?.contains("保护") == true -> 1.0 // 保护状态：正常等待
            else -> 1.0
        }
        
        // 添加随机抖动，避免同时重试
        val jitter = Random.nextLong(-2000L, 2000L)
        return (baseDelay * multiplier).toLong() + jitter
    }
    
    /**
     * 判断是否应该重试
     */
    fun shouldRetry(retryCount: Int, error: String?, timeToTarget: Long): Boolean {
        if (retryCount >= 3) return false // 最多重试3次
        if (timeToTarget < 10000L) return false // 剩余时间不足10秒不重试
        
        // 根据错误类型决定是否重试
        return when {
            error?.contains("网络") == true -> true // 网络错误可重试
            error?.contains("临时") == true -> true // 临时错误可重试
            error?.contains("保护") == true -> false // 保护状态不重试，等保护结束
            else -> retryCount < 2 // 其他错误最多重试2次
        }
    }
}

/**
 * 能量球蹲点管理器（精确时机版）
 * 
 * 单一职责：精确管理能量球的蹲点时机
 * 核心原则：
 * 1. 无保护时：严格按能量球成熟时间收取
 * 2. 有保护时：等到保护结束后立即收取
 * 3. 不提前收取：避免无效请求
 * 4. 精确时机：确保在正确的时间点执行收取
 * 
 * @author Sesame-TK Team
 */
object EnergyWaitingManager {
    private const val TAG = "EnergyWaitingManager"
    
    /**
     * 等待任务数据类
     */
    data class WaitingTask(
        val userId: String,
        val userName: String,
        val bubbleId: Long,
        val produceTime: Long,
        val fromTag: String,
        val retryCount: Int = 0,
        val maxRetries: Int = 3,
        val shieldEndTime: Long = 0, // 保护罩结束时间
        val bombEndTime: Long = 0     // 炸弹卡结束时间
    ) {
        val taskId: String = "${userId}_${bubbleId}"
        
        fun withRetry(): WaitingTask = this.copy(retryCount = retryCount + 1)
        
        /**
         * 检查是否有保护（保护罩或炸弹卡）
         */
        fun hasProtection(currentTime: Long = System.currentTimeMillis()): Boolean {
            return shieldEndTime > currentTime || bombEndTime > currentTime
        }
        
        /**
         * 获取保护结束时间（取最晚的时间）
         */
        fun getProtectionEndTime(): Long {
            return maxOf(shieldEndTime, bombEndTime)
        }
    }
    
    // 蹲点任务存储
    private val waitingTasks = ConcurrentHashMap<String, WaitingTask>()
    
    // 智能重试策略
    private val smartRetryStrategy = SmartRetryStrategy()
    
    // 协程作用域
    private val managerScope = CoroutineScope(
        Dispatchers.Default + 
        SupervisorJob() + 
        CoroutineName("PreciseEnergyWaitingManager")
    )
    
    // 互斥锁，防止并发操作
    private val taskMutex = Mutex()
    
    // 最后执行时间，用于间隔控制
    private val lastExecuteTime = AtomicLong(0)
    
    // 最小间隔时间（毫秒） - 精确蹲点模式，快速收取
    private const val MIN_INTERVAL_MS = 500L  // 最小0.5秒（精确蹲点模式）
    private const val MAX_INTERVAL_MS = 1500L // 最大1.5秒（精确蹲点模式）
    
    // 最大等待时间（毫秒） - 8小时
    private const val MAX_WAIT_TIME_MS = 8 * 60 * 60 * 1000L
    
    // 基础检查间隔（毫秒）
    private const val BASE_CHECK_INTERVAL_MS = 30000L // 30秒检查一次
    
    // 精确时机计算 - 能量成熟或保护结束后立即收取
    private fun calculatePreciseCollectTime(task: WaitingTask): Long {
        val currentTime = System.currentTimeMillis()
        val protectionEndTime = task.getProtectionEndTime()
        
        return when {
            // 有保护：等到保护结束后立即收取
            protectionEndTime > currentTime -> protectionEndTime
            // 无保护：能量成熟后立即收取
            else -> task.produceTime
        }
    }
    
    // 获取清理任务间隔 - 固定间隔清理过期任务
    private fun getCleanupInterval(): Long {
        return BASE_CHECK_INTERVAL_MS // 30秒清理一次
    }
    
    // 能量收取回调
    private var energyCollectCallback: EnergyCollectCallback? = null
    
    /**
     * 添加蹲点任务（带重复检查优化和智能保护判断）
     * 
     * @param userId 用户ID
     * @param userName 用户名称
     * @param bubbleId 能量球ID
     * @param produceTime 能量球成熟时间
     * @param fromTag 来源标记
     * @param shieldEndTime 保护罩结束时间（可选，如果为0则会自动获取）
     * @param bombEndTime 炸弹卡结束时间（可选，如果为0则会自动获取）
     * @param userHomeObj 用户主页数据（可选，用于自动获取保护时间）
     */
    fun addWaitingTask(
        userId: String,
        userName: String,
        bubbleId: Long,
        produceTime: Long,
        fromTag: String = "waiting",
        shieldEndTime: Long = 0,
        bombEndTime: Long = 0,
        userHomeObj: JSONObject? = null
    ) {
        managerScope.launch {
            taskMutex.withLock {
                val currentTime = System.currentTimeMillis()
                val taskId = "${userId}_${bubbleId}"
                
                // 检查是否已存在相同的任务
                val existingTask = waitingTasks[taskId]
                if (existingTask != null) {
                    // 如果已存在且时间相同，跳过添加
                    if (existingTask.produceTime == produceTime) {
                        Log.debug(TAG, "蹲点任务[$taskId]已存在且时间相同，跳过重复添加")
                        return@withLock
                    }
                    // 如果时间不同，记录更新信息
                    Log.debug(TAG, "更新蹲点任务[$taskId]：时间从[${TimeUtil.getCommonDate(existingTask.produceTime)}]更新为[${TimeUtil.getCommonDate(produceTime)}]")
                }
                
                // 智能获取保护时间（提前到时间检查之前）
                var finalShieldEndTime = shieldEndTime
                var finalBombEndTime = bombEndTime

                if (userHomeObj != null) {
                    finalShieldEndTime = ForestUtil.getShieldEndTime(userHomeObj)
                    finalBombEndTime = ForestUtil.getBombCardEndTime(userHomeObj)
                    // 智能判断是否应该跳过蹲点
                    if (ForestUtil.shouldSkipWaitingDueToProtection(userHomeObj, produceTime)) {
                        val protectionEndTime = ForestUtil.getProtectionEndTime(userHomeObj)
                        val timeDifference = protectionEndTime - produceTime
                        val formattedTimeDifference = formatTime(timeDifference)
                        Log.record(
                            TAG,
                            "智能跳过蹲点：[$userName]的保护罩比能量球晚到期${formattedTimeDifference}，无法收取，已跳过。"
                        )
                        // 移除无效的蹲点任务
                        waitingTasks.remove(taskId)
                        return@withLock
                    }
                }

                // 注释：原本的时间有效性检查已删除
                // 因为 addWaitingTask 只在 produceTime > serverTime 时被调用
                // 所以 produceTime <= currentTime 的情况几乎不会发生
                
                // 检查等待时间是否过长
                val waitTime = produceTime - currentTime
                if (waitTime > MAX_WAIT_TIME_MS) {
                    Log.debug(TAG, "能量球[$bubbleId]等待时间过长(${waitTime/1000/60}分钟)，跳过蹲点")
                    // 移除过长的任务
                    waitingTasks.remove(taskId)
                    return@withLock
                }
                

                val task = WaitingTask(
                    userId = userId,
                    userName = userName,
                    bubbleId = bubbleId,
                    produceTime = produceTime,
                    fromTag = fromTag,
                    shieldEndTime = finalShieldEndTime,
                    bombEndTime = finalBombEndTime
                )
                
                // 移除旧任务（如果存在）
                waitingTasks.remove(taskId)
                
                // 添加新任务
                waitingTasks[taskId] = task

                val protectionEndTime = task.getProtectionEndTime()
                val protectionStatus = if (protectionEndTime > currentTime) {
                    " 保护罩到期：" + TimeUtil.getCommonDate(protectionEndTime)
                } else {
                    ""
                }
                val actionText = if (existingTask != null) "更新" else "添加"
                val waitTimeMinutes = (produceTime - currentTime) / 1000 / 60
                Log.record(
                    TAG,
                    "${actionText}蹲点：[${fromTag}|${userName}]球[${bubbleId}]在[${TimeUtil.getCommonDate(produceTime)}]成熟(等待${waitTimeMinutes}分钟)${protectionStatus}"
                )
                // 启动精确蹲点协程
                startPreciseWaitingCoroutine(task)
            }
        }
    }
    
    /**
     * 启动精确蹲点协程
     * 核心原则：不提前收取，严格按时机执行
     */
    private fun startPreciseWaitingCoroutine(task: WaitingTask) {
        managerScope.launch {
            try {
                val currentTime = System.currentTimeMillis()
                val preciseCollectTime = calculatePreciseCollectTime(task)
                val waitTime = preciseCollectTime - currentTime
                
                if (waitTime > 0) {
                    // 需要等待的任务
                    val protectionInfo = if (task.hasProtection(currentTime)) {
                        "保护结束"
                    } else {
                        "能量成熟"
                    }
                    val waitMinutes = waitTime / 1000 / 60
                    Log.record(TAG, "🕐 蹲点[${task.userName}]等待${waitMinutes}分钟(${protectionInfo}→${TimeUtil.getCommonDate(preciseCollectTime)})")
                    
                    // 分段等待，每30秒检查一次任务有效性
                    val checkInterval = 30000L // 30秒检查一次
                    var remainingWait = waitTime
                    
                    while (remainingWait > 0 && isActive) {
                        val currentWait = minOf(remainingWait, checkInterval)
                        delay(currentWait)
                        remainingWait -= currentWait
                        
                        // 检查任务是否仍然有效
                        if (!waitingTasks.containsKey(task.taskId)) {
                            Log.record(TAG, "⚠️ 蹲点[${task.userName}]已被移除")
                            return@launch
                        }
                        
                        // 记录等待进度（调试日志）
                        if (remainingWait > 0) {
                            Log.debug(TAG, "蹲点任务[${task.userName}]等待中，剩余${remainingWait/1000}秒")
                        }
                    }
                    
                    // 等待完成，最终检查任务有效性
                    if (!waitingTasks.containsKey(task.taskId)) {
                        Log.record(TAG, "⚠️ 蹲点[${task.userName}]等待过程中被移除")
                        return@launch
                    }
                    
                    Log.record(TAG, "✅ 蹲点[${task.userName}]等待完成，开始收取")
                } else {
                    // 已经到时间的任务，立即执行
                    val overdueMinutes = (-waitTime) / 1000 / 60
                    if (overdueMinutes > 2) {
                        // 超时超过2分钟，记录警告
                        Log.record(TAG, "⚡ 蹲点[${task.userName}]已超时${overdueMinutes}分钟，立即收取")
                    } else {
                        // 刚到时间或刚超时，正常执行
                        Log.record(TAG, "✅ 蹲点[${task.userName}]时间已到，立即收取")
                    }
                }
                
                // 执行收取任务
                executePreciseWaitingTask(task)
                
            } catch (_: CancellationException) {
                Log.debug(TAG, "精确蹲点任务[${task.taskId}]被取消")
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "精确蹲点任务[${task.taskId}]执行异常", e)
                
                // 精确重试逻辑
                val currentTime = System.currentTimeMillis()
                val timeToTarget = calculatePreciseCollectTime(task) - currentTime
                
                if (smartRetryStrategy.shouldRetry(task.retryCount, e.message, timeToTarget)) {
                    val retryTask = task.withRetry()
                    waitingTasks[task.taskId] = retryTask
                    
                    // 重试延迟
                    val retryDelay = smartRetryStrategy.getRetryDelay(task.retryCount, e.message)
                    Log.debug(TAG, "精确蹲点任务[${task.taskId}]将在${retryDelay/1000}秒后重试")
                    delay(retryDelay)
                    startPreciseWaitingCoroutine(retryTask)
                } else {
                    Log.error(TAG, "精确蹲点任务[${task.taskId}]不满足重试条件，放弃")
                    waitingTasks.remove(task.taskId)
                }
            }
        }
    }
    
    /**
     * 执行精确蹲点收取任务
     * 核心原则：在正确的时机执行，不提前不延后
     */
    @SuppressLint("SimpleDateFormat")
    private suspend fun executePreciseWaitingTask(task: WaitingTask) {
        taskMutex.withLock {
            try {
                // 检查任务是否仍然有效
                if (!waitingTasks.containsKey(task.taskId)) {
                    Log.debug(TAG, "精确蹲点任务[${task.taskId}]已被移除，跳过执行")
                    return@withLock
                }
                
                // 随机间隔控制：防止频繁请求，使用随机间隔更自然
                val currentTime = System.currentTimeMillis()
                val lastExecute = lastExecuteTime.get()
                
                if (lastExecute == 0L) {
                    // 第一次执行，立即收取
                    Log.record(TAG, "⚡ 首次蹲点收取，立即执行任务[${task.taskId}]")
                } else {
                    // 非首次执行，应用随机间隔控制
                    val timeSinceLastExecute = currentTime - lastExecute
                    
                    // 生成随机间隔时间（0.5-1.5秒，精确蹲点模式）
                    val randomIntervalMs = Random.nextLong(MIN_INTERVAL_MS, MAX_INTERVAL_MS + 1)
                    
                    if (timeSinceLastExecute < randomIntervalMs) {
                        val delayTime = randomIntervalMs - timeSinceLastExecute
                        Log.record(TAG, "🎲 随机间隔控制：延迟${delayTime / 1000}秒执行蹲点任务[${task.taskId}]（随机间隔${randomIntervalMs/1000}秒）")
                        delay(delayTime)
                    } else {
                        Log.debug(TAG, "⚡ 无需延迟：距离上次执行已超过${timeSinceLastExecute/1000}秒")
                    }
                }
                
                // 更新最后执行时间
                lastExecuteTime.set(System.currentTimeMillis())
                
                // 验证执行时机是否正确
                val actualTime = System.currentTimeMillis()
                val energyTimeRemain = (task.produceTime - actualTime) / 1000
                val protectionEndTime = task.getProtectionEndTime()
                val isEnergyMature = task.produceTime <= actualTime
                val isProtectionEnd = protectionEndTime <= actualTime

                if (energyTimeRemain > 300) { // 如果还有超过5分钟才成熟，直接跳过
                    Log.debug(TAG, "⚠️ 能量距离成熟还有${energyTimeRemain}秒，时机过早，跳过本次收取")
                    return@withLock
                }
                
                // 判断是否需要详细日志（未成熟或刚成熟2分钟内）
                val needDetailLog = !isEnergyMature || !isProtectionEnd || energyTimeRemain > -120
                
                if (needDetailLog) {
                    // 详细调试日志：用于未成熟或刚成熟的任务
                    Log.record(TAG, "🔍 蹲点任务[${task.userName}]时机检查详情：")
                    Log.record(TAG, "  系统当前时间: ${System.currentTimeMillis()} (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())})")
                    Log.record(TAG, "  实际执行时间: $actualTime (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(actualTime))})")
                    Log.record(TAG, "  能量成熟时间: ${task.produceTime} (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(task.produceTime))})")
                    Log.record(TAG, "  保护结束时间: $protectionEndTime (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(protectionEndTime))})")
                    Log.record(TAG, "  时间差异: 系统时间与执行时间差${System.currentTimeMillis() - actualTime}ms")
                    Log.record(TAG, "  能量剩余时间: ${energyTimeRemain}秒")
                    Log.record(TAG, "  能量是否成熟: $isEnergyMature")
                    Log.record(TAG, "  保护是否结束: $isProtectionEnd")
                } else {
                    // 简化日志：用于已成熟超过2分钟的任务
                    val matureTime = (-energyTimeRemain) / 60 // 成熟了多少分钟
                    Log.record(TAG, "⚡ 蹲点任务[${task.userName}]已成熟${matureTime.toInt()}分钟，直接收取")
                }
                
                // 最终时机检查
                if (!isEnergyMature || !isProtectionEnd) {
                    val additionalWait = max(
                        protectionEndTime - actualTime,
                        task.produceTime - actualTime
                    )
                    
                    if (additionalWait in 1..<1800000L) { // 最多额外等待30分钟
                        val waitReason = if (!isEnergyMature) "能量未成熟" else ""
                        val protectionReason = if (!isProtectionEnd) "保护未结束" else ""
                        val combinedReason = listOf(waitReason, protectionReason).filter { it.isNotEmpty() }.joinToString("且")
                        
                        Log.record(TAG, "⏳ 最终时机检查：等待${additionalWait/1000}秒 ($combinedReason)")
                        delay(additionalWait)
                        
                        // 等待后重新检查
                        val newActualTime = System.currentTimeMillis()
                        val newIsEnergyMature = task.produceTime <= newActualTime
                        val newIsProtectionEnd = task.getProtectionEndTime() <= newActualTime
                        Log.record(TAG, "⏳ 等待完成：能量成熟[$newIsEnergyMature] 保护结束[$newIsProtectionEnd]")
                    } else if (additionalWait > 1800000L) {
                        Log.error(TAG, "⚠️ 等待时间过长(${additionalWait/60000}分钟)，跳过收取")
                        return@withLock
                    }
                }
                
                // 执行收取
                val startTime = System.currentTimeMillis()
                val result = collectEnergyFromWaiting(task)
                val executeTime = System.currentTimeMillis() - startTime
                
                // 更新用户模式数据
                UserEnergyPatternManager.updateUserPattern(task.userId, result, executeTime)
                // 处理结果
                if (result.success) {
                    if (result.energyCount > 0) {
                        Log.record(TAG,"✅ 蹲点收取[${task.userName}]成功${result.energyCount}g(耗时${executeTime}ms)")
                        waitingTasks.remove(task.taskId) // 成功后移除任务
                    } else {
                        Log.record(TAG, "⚠️ 蹲点收取[${task.userName}]异常：返回0能量(${result.message})")
                        
                        // 判断是否需要重试
                        if (task.retryCount < task.maxRetries) {
                            val retryTask = task.withRetry()
                            waitingTasks[task.taskId] = retryTask
                            val retryDelay = 5000L // 5秒后重试
                            Log.record(TAG, "  → 5秒后重试(${retryTask.retryCount}/${task.maxRetries})")
                            
                            managerScope.launch {
                                delay(retryDelay)
                                startPreciseWaitingCoroutine(retryTask)
                            }
                        } else {
                            Log.record(TAG, "  → 已达最大重试次数")
                            waitingTasks.remove(task.taskId)
                        }
                    }
                } else {
                    Log.record(TAG, "❌ 蹲点收取[${task.userName}]失败：${result.message}")
                    
                    // 根据失败原因决定是否重试
                    if (result.hasShield || result.hasBomb) {
                        Log.record(TAG, "  → 检测到保护罩/炸弹卡")
                        waitingTasks.remove(task.taskId)
                    } else {
                        // 可重试的错误，主动触发重试
                        if (task.retryCount < task.maxRetries) {
                            val retryTask = task.withRetry()
                            waitingTasks[task.taskId] = retryTask
                            
                            // 根据错误类型决定重试延迟
                            val retryDelay = when {
                                result.message.contains("网络") -> 5000L // 5秒
                                result.message.contains("频繁") -> 10000L // 10秒
                                else -> 5000L // 默认5秒
                            }
                            
                            Log.record(TAG, "  → ${retryDelay/1000}秒后重试(${retryTask.retryCount}/${task.maxRetries})")
                            
                            managerScope.launch {
                                delay(retryDelay)
                                if (waitingTasks.containsKey(task.taskId)) {
                                    startPreciseWaitingCoroutine(retryTask)
                                }
                            }
                        } else {
                            Log.record(TAG, "  → 已达最大重试次数")
                            waitingTasks.remove(task.taskId)
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "执行精确蹲点任务异常", e)
                throw e
            }
        }
    }
    
    /**
     * 收取等待的能量（通过回调调用AntForest）
     */
    private suspend fun collectEnergyFromWaiting(task: WaitingTask): CollectResult {
        return try {
            val callback = energyCollectCallback
            if (callback != null) {
                // 通过回调调用AntForest的收取方法
                callback.collectUserEnergyForWaiting(task)
            } else {
                Log.debug(TAG, "能量收取回调未设置，跳过收取：用户[${task.userId}] 能量球[${task.bubbleId}]")
                CollectResult(
                    success = false,
                    userName = task.userName,
                    message = "回调未设置"
                )
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "收取能量失败", e)
            CollectResult(
                success = false,
                userName = task.userName,
                message = "异常：${e.message}"
            )
        }
    }

    /**
     * 清理过期的蹲点任务并重新触发已成熟任务
     */
    fun cleanExpiredTasks() {
        managerScope.launch {
            taskMutex.withLock {
                val currentTime = System.currentTimeMillis()
                
                // 1. 找出已经成熟超过2分钟但未执行的任务（可能是僵尸任务）
                val matureTasks = waitingTasks.filter { (_, task) ->
                    val protectionEndTime = task.getProtectionEndTime()
                    val collectTime = if (protectionEndTime > currentTime) protectionEndTime else task.produceTime
                    currentTime > collectTime + 2 * 60 * 1000L // 成熟超过2分钟
                }
                
                // 重新触发已成熟任务
                if (matureTasks.isNotEmpty()) {
                    val taskNames = matureTasks.values.map { it.userName }.take(3).joinToString(",")
                    val moreText = if (matureTasks.size > 3) "等${matureTasks.size}个" else ""
                    Log.record(TAG, "🔄 重新触发蹲点：[${taskNames}${moreText}]已成熟但未执行")
                    matureTasks.forEach { (_, task) ->
                        startPreciseWaitingCoroutine(task)
                    }
                }
                
                // 2. 找出真正过期的任务（成熟超过1小时）
                val expiredTasks = waitingTasks.filter { (_, task) ->
                    currentTime > task.produceTime + 60 * 60 * 1000L // 超过成熟时间1小时
                }
                
                if (expiredTasks.isNotEmpty()) {
                    val taskNames = expiredTasks.values.map { it.userName }.take(3).joinToString(",")
                    val moreText = if (expiredTasks.size > 3) "等${expiredTasks.size}个" else ""
                    Log.record(TAG, "🧹 清理过期蹲点：[${taskNames}${moreText}]")
                    expiredTasks.forEach { (taskId, _) ->
                        waitingTasks.remove(taskId)
                    }
                } else {
                    Log.debug(TAG, "定期清理检查：无过期任务")
                }
                
                // 记录当前活跃任务状态（简化版）
                if (waitingTasks.isNotEmpty()) {
                    val sortedTasks = waitingTasks.values.sortedBy { it.produceTime }
                    val nearestTask = sortedTasks.firstOrNull()
                    if (nearestTask != null) {
                        val timeToNearest = (nearestTask.produceTime - currentTime) / 1000 / 60
                        Log.record(TAG, "📋 活跃蹲点${waitingTasks.size}个，最近[${nearestTask.userName}]${timeToNearest}分钟后")
                    }
                } else {
                    Log.debug(TAG, "当前无活跃蹲点任务")
                }
            }
        }
    }

    /**
     * 设置能量收取回调
     */
    fun setEnergyCollectCallback(callback: EnergyCollectCallback) {
        energyCollectCallback = callback
        Log.record(TAG, "已设置能量收取回调")
    }

    /**
     * 启动定期清理任务
     */
    fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                try {
                    // 使用动态间隔进行清理
                    val cleanupInterval = getCleanupInterval()
                    delay(cleanupInterval)
                    cleanExpiredTasks()
                    
                    // 定期清理用户模式数据
                    UserEnergyPatternManager.cleanupExpiredPatterns()
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.printStackTrace(TAG, "定期清理任务异常", e)
                }
            }
        }
    }
    
    /**
     * 获取当前正在等待的蹲点任务数量
     * @return 蹲点任务数量
     */
    fun getWaitingTaskCount(): Int {
        return waitingTasks.size
    }
    
    /**
     * 获取蹲点任务详细状态（仅显示最近的3个）
     */
    fun getWaitingTasksStatus(): String {
        val currentTime = System.currentTimeMillis()
        if (waitingTasks.isEmpty()) {
            return "无蹲点任务"
        }
        
        val statusBuilder = StringBuilder()
        val sortedTasks = waitingTasks.values.sortedBy { it.produceTime }
        val displayCount = minOf(3, sortedTasks.size)
        
        statusBuilder.append("蹲点任务状态 (${waitingTasks.size}个，显示最近${displayCount}个):\n")
        
        sortedTasks.take(displayCount).forEach { task ->
            val status = formatTimeStatus(currentTime, task.produceTime)
            val executeTime = TimeUtil.getCommonDate(task.produceTime)
            
            val protectionEndTime = task.getProtectionEndTime()
            val hasProtection = protectionEndTime > currentTime
            val protectionInfo = if (hasProtection) {
                val protectionStatus = formatTimeStatus(currentTime, protectionEndTime)
                " (保护${protectionStatus.removePrefix("剩余")})"
            } else {
                ""
            }
            
            statusBuilder.append("  - [${task.userName}] 球[${task.bubbleId}] $status$protectionInfo → $executeTime\n")
        }
        
        if (sortedTasks.size > displayCount) {
            statusBuilder.append("  ... 还有${sortedTasks.size - displayCount}个任务")
        }
        
        return statusBuilder.toString().trimEnd()
    }
    
    /**
     * 格式化时间为人性化的字符串
     * @param milliseconds 毫秒数
     * @return 格式化后的时间字符串
     */
    private fun formatTime(milliseconds: Long): String {
        val hours = milliseconds / (1000 * 60 * 60)
        val minutes = (milliseconds % (1000 * 60 * 60)) / (1000 * 60)
        return when {
            hours > 0 -> "${hours}小时${minutes}分钟"
            minutes > 0 -> "${minutes}分钟"
            else -> "${milliseconds / 1000}秒"
        }
    }
    
    /**
     * 格式化剩余时间状态
     * @param currentTime 当前时间
     * @param targetTime 目标时间
     * @return 格式化后的状态字符串（如："剩余2分19秒" 或 "已成熟1分5秒"）
     */
    private fun formatTimeStatus(currentTime: Long, targetTime: Long): String {
        val timeRemainMs = targetTime - currentTime
        val timeRemainSeconds = timeRemainMs / 1000
        val timeRemainMinutes = timeRemainSeconds / 60
        
        return if (timeRemainMs > 0) {
            if (timeRemainMinutes > 0) {
                "剩余${timeRemainMinutes}分${timeRemainSeconds % 60}秒"
            } else {
                "剩余${timeRemainSeconds}秒"
            }
        } else {
            val overTimeMinutes = (-timeRemainSeconds) / 60
            if (overTimeMinutes > 0) {
                "已成熟${overTimeMinutes}分${(-timeRemainSeconds) % 60}秒"
            } else {
                "已成熟${-timeRemainSeconds}秒"
            }
        }
    }
    
    init {
        // 启动定期清理任务
        startPeriodicCleanup()
        Log.record(TAG, "精确能量球蹲点管理器已初始化")
    }
}