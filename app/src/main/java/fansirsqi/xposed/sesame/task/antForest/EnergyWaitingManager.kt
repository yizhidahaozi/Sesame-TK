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
    
    // 最小间隔时间（毫秒） - 防止频繁请求
    private const val MIN_INTERVAL_MS = 10000L // 10秒
    
    // 最大等待时间（毫秒） - 6小时
    private const val MAX_WAIT_TIME_MS = 6 * 60 * 60 * 1000L
    
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
                    val protectionInfo = if (task.hasProtection(currentTime)) {
                        "保护结束后"
                    } else {
                        "能量成熟后"
                    }
                    
                    Log.debug(TAG, "精确蹲点任务[${task.taskId}]等待${waitTime/1000}秒${protectionInfo}立即收取")
                    
                    // 直接等待到精确时间，无需间隔检查
                    delay(waitTime)
                    
                    // 执行前再次确认任务是否有效
                    if (!waitingTasks.containsKey(task.taskId)) {
                        Log.debug(TAG, "精确蹲点任务[${task.taskId}]已被移除，取消执行")
                        return@launch
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
                
                // 无延迟，立即收取
                val timingInfo = if (protectionEndTime > actualTime) {
                    val protectionRemain = (protectionEndTime - actualTime) / 1000
                    "能量剩余[${energyTimeRemain}秒] 保护剩余[${protectionRemain}秒] - 保护结束后立即收取"
                } else if (energyTimeRemain > 0) {
                    "能量剩余[${energyTimeRemain}秒] - 能量成熟后立即收取"
                } else {
                    "能量已成熟 - 立即收取"
                }
                
                Log.record(TAG, "精确蹲点执行：用户[${task.userName}] 能量球[${task.bubbleId}] $timingInfo")
                
                // 🚨 严格时机检查：能量未成熟时直接跳过
                if (energyTimeRemain > 60) { // 如果还有超过1分钟才成熟，直接跳过
                    Log.debug(TAG, "⚠️ 能量距离成熟还有${energyTimeRemain}秒，时机过早，跳过本次收取")
                    return@withLock
                }
                
                // 最终时机检查：如果还有保护或能量未成熟，等待一下
                val isEnergyMature = task.produceTime <= actualTime
                val isProtectionEnd = protectionEndTime <= actualTime
                
                Log.debug(TAG, "时机检查详情：")
                Log.debug(TAG, "  系统当前时间: ${System.currentTimeMillis()} (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())})")
                Log.debug(TAG, "  实际执行时间: $actualTime (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(actualTime))})")
                Log.debug(TAG, "  能量成熟时间: ${task.produceTime} (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(task.produceTime))})")
                Log.debug(TAG, "  保护结束时间: $protectionEndTime (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(protectionEndTime))})")
                Log.debug(TAG, "  时间差异: 系统时间与执行时间差${System.currentTimeMillis() - actualTime}ms")
                Log.debug(TAG, "  能量是否成熟: $isEnergyMature")
                Log.debug(TAG, "  保护是否结束: $isProtectionEnd")
                
                if (!isEnergyMature || !isProtectionEnd) {
                    val additionalWait = max(
                        protectionEndTime - actualTime,
                        task.produceTime - actualTime
                    ) // 等待到正确时机，无额外延迟
                    
                    if (additionalWait > 0 && additionalWait < 1800000L) { // 最多额外等待30分钟
                        Log.debug(TAG, "最终时机检查：等待${additionalWait/1000}秒到正确时机")
                        Log.debug(TAG, "  等待原因: ${if (!isEnergyMature) "能量未成熟" else ""}${if (!isProtectionEnd) "保护未结束" else ""}")
                        delay(additionalWait)
                    } else if (additionalWait > 1800000L) {
                        Log.debug(TAG, "⚠️ 等待时间过长(${additionalWait/60000}分钟)，可能存在时间计算错误，跳过收取")
                        return@withLock
                    }
                } else {
                    // 能量已成熟且无保护，立即收取
                    Log.debug(TAG, "时机正确：能量已成熟且无保护，立即执行收取")
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
                        Log.forest("精确蹲点收取成功：用户[${task.userName}] 收取能量[${result.energyCount}g] 耗时[${executeTime}ms]")
                        waitingTasks.remove(task.taskId) // 成功后移除任务
                    } else {
                        Log.debug(TAG, "⚠️ 精确蹲点收取异常：用户[${task.userName}] 返回success=true但energyCount=0，可能时机不对或接口异常")
                        Log.debug(TAG, "收取结果详情: ${result.message}")
                        // 不移除任务，等待下次重试
                    }
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
