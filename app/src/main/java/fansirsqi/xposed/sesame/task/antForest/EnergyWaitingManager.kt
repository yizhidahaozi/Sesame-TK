package fansirsqi.xposed.sesame.task.antForest

import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
import fansirsqi.xposed.sesame.util.maps.UserMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
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
    
    // 最小间隔时间（毫秒） - 防止频繁请求
    private const val MIN_INTERVAL_MS = 10000L // 10秒
    
    // 最大等待时间（毫秒） - 6小时
    private const val MAX_WAIT_TIME_MS = 6 * 60 * 60 * 1000L
    
    // 基础检查间隔（毫秒）
    private const val BASE_CHECK_INTERVAL_MS = 30000L // 30秒检查一次
    
    // 精确时机计算 - 延后2秒收取，确保时机正确
    private fun calculatePreciseCollectTime(task: WaitingTask): Long {
        val currentTime = System.currentTimeMillis()
        val protectionEndTime = task.getProtectionEndTime()
        
        return when {
            // 有保护：等到保护结束后延后2秒收取
            protectionEndTime > currentTime -> protectionEndTime + 2000L // 保护结束后2秒
            // 无保护：能量成熟后延后2秒收取
            else -> task.produceTime + 2000L // 成熟后2秒
        }
    }
    
    // 获取动态检查间隔 - 根据最近任务时间调整
    private fun getDynamicCheckInterval(): Long {
        val currentTime = System.currentTimeMillis()
        val nearestTaskTime = waitingTasks.values.minByOrNull { 
            calculatePreciseCollectTime(it)
        }?.let { task ->
            calculatePreciseCollectTime(task)
        }
        
        return if (nearestTaskTime != null) {
            val timeToNext = nearestTaskTime - currentTime
            // 使用用户模式管理器的建议间隔
            val userId = waitingTasks.values.first().userId
            UserEnergyPatternManager.getSuggestedInterval(userId, timeToNext)
        } else {
            BASE_CHECK_INTERVAL_MS
        }
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
                
                // 检查时间有效性
                if (produceTime <= currentTime) {
                    Log.debug(TAG, "能量球[$bubbleId]已经成熟，跳过蹲点")
                    // 如果已过期，移除现有任务
                    waitingTasks.remove(taskId)
                    return@withLock
                }
                
                // 检查等待时间是否过长
                val waitTime = produceTime - currentTime
                if (waitTime > MAX_WAIT_TIME_MS) {
                    Log.debug(TAG, "能量球[$bubbleId]等待时间过长(${waitTime/1000/60}分钟)，跳过蹲点")
                    // 移除过长的任务
                    waitingTasks.remove(taskId)
                    return@withLock
                }
                
                // 智能获取保护时间
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
                Log.record(
                    TAG,
                    "${actionText}蹲点任务：[${fromTag}|${userName}]能量球[${bubbleId}]将在[${TimeUtil.getCommonDate(produceTime)}]成熟${protectionStatus}"
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
                    // 计算检查间隔，临近时更频繁检查
                    val checkInterval = UserEnergyPatternManager.getSuggestedInterval(task.userId, waitTime)
                    
                    val protectionInfo = if (task.hasProtection(currentTime)) {
                        "保护结束后"
                    } else {
                        "能量成熟后"
                    }
                    
                    Log.debug(TAG, "精确蹲点任务[${task.taskId}]等待${waitTime/1000}秒${protectionInfo}收取，检查间隔${checkInterval/1000}秒")
                    
                    // 分阶段等待，定期检查任务状态
                    var remainingWait = waitTime
                    while (remainingWait > 0 && isActive) {
                        val nextDelay = min(checkInterval, remainingWait)
                        delay(nextDelay)
                        remainingWait -= nextDelay
                        
                        // 检查任务是否仍然有效
                        if (!waitingTasks.containsKey(task.taskId)) {
                            Log.debug(TAG, "精确蹲点任务[${task.taskId}]已被移除，停止等待")
                            return@launch
                        }
                        
                        // 重新计算精确时机（保护状态可能变化）
                        val newPreciseTime = calculatePreciseCollectTime(task)
                        val adjustment = newPreciseTime - preciseCollectTime
                        if (kotlin.math.abs(adjustment) > 5000L) { // 调整超过5秒才重新计算
                            remainingWait = max(0L, newPreciseTime - System.currentTimeMillis())
                            Log.debug(TAG, "精确蹲点任务[${task.taskId}]调整等待时间：${adjustment/1000}秒")
                        }
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
    private suspend fun executePreciseWaitingTask(task: WaitingTask) {
        taskMutex.withLock {
            try {
                // 检查任务是否仍然有效
                if (!waitingTasks.containsKey(task.taskId)) {
                    Log.debug(TAG, "精确蹲点任务[${task.taskId}]已被移除，跳过执行")
                    return@withLock
                }
                
                // 最小间隔控制：防止频繁请求
                val currentTime = System.currentTimeMillis()
                val timeSinceLastExecute = currentTime - lastExecuteTime.get()
                
                if (timeSinceLastExecute < MIN_INTERVAL_MS) {
                    val delayTime = MIN_INTERVAL_MS - timeSinceLastExecute
                    Log.debug(TAG, "间隔控制：延迟${delayTime / 1000}秒执行蹲点任务[${task.taskId}]")
                    delay(delayTime)
                }
                
                // 更新最后执行时间
                lastExecuteTime.set(System.currentTimeMillis())
                
                // 验证执行时机是否正确
                val actualTime = System.currentTimeMillis()
                val energyTimeRemain = (task.produceTime - actualTime) / 1000
                val protectionEndTime = task.getProtectionEndTime()
                
                val timingInfo = if (protectionEndTime > actualTime) {
                    val protectionRemain = (protectionEndTime - actualTime) / 1000
                    "能量剩余[${energyTimeRemain}秒] 保护剩余[${protectionRemain}秒] - 保护结束后2秒收取"
                } else if (energyTimeRemain > 0) {
                    "能量剩余[${energyTimeRemain}秒] - 能量成熟后2秒收取"
                } else {
                    "能量已成熟 - 延后2秒收取"
                }
                
                Log.record(TAG, "精确蹲点执行：用户[${task.userName}] 能量球[${task.bubbleId}] $timingInfo")
                
                // 最终时机检查：如果还有保护或能量未成熟，等待一下
                if (protectionEndTime > actualTime || task.produceTime > actualTime) {
                    val additionalWait = max(
                        protectionEndTime - actualTime,
                        task.produceTime - actualTime
                    ) + 2000L // 额外等待2秒确保时机正确
                    
                    if (additionalWait > 0 && additionalWait < 60000L) { // 最多额外等待1分钟
                        Log.debug(TAG, "最终时机检查：额外等待${additionalWait/1000}秒确保时机正确")
                        delay(additionalWait)
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
                    Log.forest(TAG, "精确蹲点收取成功：用户[${task.userName}] 收取能量[${result.energyCount}g] 耗时[${executeTime}ms]")
                    waitingTasks.remove(task.taskId) // 成功后移除任务
                } else {
                    Log.debug(TAG, "精确蹲点收取失败：用户[${task.userName}] 原因[${result.message}]")
                    
                    // 根据失败原因决定是否重试
                    if (result.hasShield || result.hasBomb) {
                        Log.debug(TAG, "用户[${task.userName}]仍有保护，移除蹲点任务")
                        waitingTasks.remove(task.taskId)
                    }
                    // 其他失败情况由上层重试逻辑处理
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
     * 移除蹲点任务
     */
    fun removeWaitingTask(userId: String, bubbleId: Long) {
        managerScope.launch {
            taskMutex.withLock {
                val taskId = "${userId}_${bubbleId}"
                waitingTasks.remove(taskId)
                Log.debug(TAG, "移除蹲点任务：[$taskId]")
            }
        }
    }
    
    /**
     * 移除用户的所有蹲点任务
     */
    fun removeUserWaitingTasks(userId: String) {
        managerScope.launch {
            taskMutex.withLock {
                val toRemove = waitingTasks.keys.filter { it.startsWith("${userId}_") }
                toRemove.forEach { taskId ->
                    waitingTasks.remove(taskId)
                }
                if (toRemove.isNotEmpty()) {
                    Log.debug(TAG, "移除用户[${UserMap.getMaskName(userId)}]的${toRemove.size}个蹲点任务")
                }
            }
        }
    }
    
    /**
     * 清理过期的蹲点任务
     */
    fun cleanExpiredTasks() {
        managerScope.launch {
            taskMutex.withLock {
                val currentTime = System.currentTimeMillis()
                val expiredTasks = waitingTasks.filter { (_, task) ->
                    currentTime > task.produceTime + 30 * 60 * 1000L // 超过成熟时间30分钟
                }
                
                expiredTasks.forEach { (taskId, _) ->
                    waitingTasks.remove(taskId)
                }
                
                if (expiredTasks.isNotEmpty()) {
                    Log.debug(TAG, "清理了${expiredTasks.size}个过期蹲点任务")
                }
            }
        }
    }
    
    /**
     * 获取当前蹲点任务数量
     */
    fun getWaitingTaskCount(): Int = waitingTasks.size
    
    /**
     * 获取蹲点任务状态信息
     */
    fun getStatusInfo(): String {
        val currentTime = System.currentTimeMillis()
        val activeTasks = waitingTasks.values
        
        if (activeTasks.isEmpty()) {
            return "当前没有蹲点任务"
        }
        
        val nextTask = activeTasks.minByOrNull { it.produceTime }
        val nextTaskTime = nextTask?.let { 
            TimeUtil.getCommonDate(it.produceTime) 
        } ?: "未知"
        
        return "蹲点任务：${activeTasks.size}个，最近执行：$nextTaskTime"
    }
    
    /**
     * 设置能量收取回调
     */
    fun setEnergyCollectCallback(callback: EnergyCollectCallback) {
        energyCollectCallback = callback
        Log.record(TAG, "已设置能量收取回调")
    }
    
    /**
     * 停止所有蹲点任务
     */
    fun stopAll() {
        managerScope.cancel()
        waitingTasks.clear()
        Log.record(TAG, "已停止所有蹲点任务")
    }
    
    /**
     * 启动定期清理任务
     */
    fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                try {
                    // 使用动态间隔进行清理
                    val cleanupInterval = getDynamicCheckInterval()
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
    
    init {
        // 启动定期清理任务
        startPeriodicCleanup()
        Log.record(TAG, "精确能量球蹲点管理器已初始化")
    }
}
