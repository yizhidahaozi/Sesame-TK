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
 * 能量球蹲点管理器
 * 
 * 负责管理和调度蚂蚁森林中等待成熟的能量球的蹲点任务。
 * 
 * 主要功能：
 * 1. 管理等待成熟的能量球队列
 * 2. 基于协程的定时任务调度
 * 3. 自动重试和错误处理
 * 4. 智能间隔控制
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
        
        fun canRetry(): Boolean = retryCount < maxRetries
        
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
        
        /**
         * 检查是否应该跳过蹲点
         * 如果保护时间比能量球成熟时间还要长，就跳过
         */
        fun shouldSkipDueToProtection(currentTime: Long = System.currentTimeMillis()): Boolean {
            val protectionEndTime = getProtectionEndTime()
            return protectionEndTime > produceTime
        }
        
        /**
         * 获取实际应该收取的时间
         * 如果有保护，则在保护结束后收取；否则在能量球成熟时收取
         */
        fun getActualCollectTime(currentTime: Long = System.currentTimeMillis()): Long {
            val protectionEndTime = getProtectionEndTime()
            return if (protectionEndTime > currentTime) {
                maxOf(protectionEndTime, produceTime)
            } else {
                produceTime
            }
        }
    }
    
    // 蹲点任务存储
    private val waitingTasks = ConcurrentHashMap<String, WaitingTask>()
    
    // 协程作用域
    private val managerScope = CoroutineScope(
        Dispatchers.Default + 
        SupervisorJob() + 
        CoroutineName("EnergyWaitingManager")
    )
    
    // 互斥锁，防止并发操作
    private val taskMutex = Mutex()
    
    // 最后执行时间，用于间隔控制
    private val lastExecuteTime = AtomicLong(0)
    
    // 最小间隔时间（毫秒）
    private const val MIN_INTERVAL_MS = 30000L // 30秒
    
    // 最大等待时间（毫秒） - 6小时
    private const val MAX_WAIT_TIME_MS = 6 * 60 * 60 * 1000L
    
    // 任务检查间隔（毫秒）
    private const val CHECK_INTERVAL_MS = 30000L // 30秒检查一次
    
    // 等待收取时间（毫秒） - 等待2秒收取
    private const val ADVANCE_TIME_MS = 2000L
    
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
        fromTag: String = "",
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
                
                // 启动蹲点协程
                startWaitingCoroutine(task)
            }
        }
    }
    
    /**
     * 启动蹲点协程
     */
    private fun startWaitingCoroutine(task: WaitingTask) {
        managerScope.launch {
            try {
                val currentTime = System.currentTimeMillis()
                val waitTime = task.produceTime - currentTime + ADVANCE_TIME_MS
                
                if (waitTime > 0) {
                    Log.debug(TAG, "蹲点任务[${task.taskId}]等待${waitTime/1000}秒后执行")
                    delay(waitTime)
                }
                
                // 执行收取任务
                executeWaitingTask(task)
                
            } catch (_: CancellationException) {
                Log.debug(TAG, "蹲点任务[${task.taskId}]被取消")
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "蹲点任务[${task.taskId}]执行异常", e)
                
                // 重试逻辑
                if (task.canRetry()) {
                    val retryTask = task.withRetry()
                    waitingTasks[task.taskId] = retryTask
                    
                    // 延迟重试
                    delay(60000) // 1分钟后重试
                    startWaitingCoroutine(retryTask)
                } else {
                    Log.error(TAG, "蹲点任务[${task.taskId}]重试次数已达上限，放弃")
                    waitingTasks.remove(task.taskId)
                }
            }
        }
    }
    
    /**
     * 执行蹲点收取任务
     */
    private suspend fun executeWaitingTask(task: WaitingTask) {
        taskMutex.withLock {
            try {
                // 检查任务是否仍然有效
                if (!waitingTasks.containsKey(task.taskId)) {
                    Log.debug(TAG, "蹲点任务[${task.taskId}]已被移除，跳过执行")
                    return@withLock
                }
                
                // 间隔控制
                val currentTime = System.currentTimeMillis()
                val timeSinceLastExecute = currentTime - lastExecuteTime.get()
                if (timeSinceLastExecute < MIN_INTERVAL_MS) {
                    val delayTime = MIN_INTERVAL_MS - timeSinceLastExecute
                    Log.debug(TAG, "间隔控制：延迟${delayTime / 1000}秒执行蹲点任务[${task.taskId}]")
                    delay(delayTime)
                }

                // 更新最后执行时间
                lastExecuteTime.set(System.currentTimeMillis())

                // 智能日志显示
                val currentLogTime = System.currentTimeMillis()
                val energyTimeRemain = (task.produceTime - currentLogTime) / 1000
                val protectionEndTime = task.getProtectionEndTime()

                if (protectionEndTime > currentLogTime) {
                    val protectionTimeRemain = (protectionEndTime - currentLogTime) / 1000
                    val protectionHours = protectionTimeRemain / 3600
                    val energyHours = energyTimeRemain / 3600

                    Log.record(
                        TAG,
                        "执行蹲点任务：[${task.fromTag}|${task.userName}]能量球[${task.bubbleId}] - 保护${protectionHours}h，能量${energyHours}h"
                    )
                } else {
                    Log.record(
                        TAG,
                        "执行蹲点任务：[${task.fromTag}|${task.userName}]能量球[${task.bubbleId}]"
                    )
                }

                // 调用AntForest的能量收取逻辑
                executeEnergyCollection(task)

                // 任务执行完成，从队列中移除
                // 无论是成功收取、跳过（保护罩/炸弹）还是失败，都移除任务避免重复执行
                waitingTasks.remove(task.taskId)
                
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "执行蹲点收取任务异常", e)
                throw e
            }
        }
    }
    
    /**
     * 执行能量收取（增强版）
     */
private suspend fun executeEnergyCollection(task: WaitingTask) {
    withContext(Dispatchers.Default) {
        try {
            // 通过回调获取收取结果
            val result = collectEnergyFromWaiting(task)
            // 根据结果进行不同的处理
            // 注意：保护罩和炸弹的检查已经在原有的collectEnergy方法中处理，会产生相应的日志
            when {
                result.success -> {
                    val displayName = result.userName ?: task.userName
                    if (result.energyCount > 0) {
                        val energyInfo = " (+${result.energyCount}g)"
                        // 在这里累加到总能量
                        energyCollectCallback?.addToTotalCollected(result.energyCount)
                        Log.forest("${task.fromTag}收取成功🎯${energyInfo}[|${displayName}]")
                    } else {
                        // 数量为0g，不显示"收取成功"
                        Log.forest("${task.fromTag}收取完成[|${displayName}]，但未获得能量")
                    }
                }
                else -> {
                    val displayName = result.userName ?: task.userName
                    val reason = if (result.message.isNotEmpty()) " - ${result.message}" else ""
                    Log.debug(TAG, "蹲点任务完成：[${task.fromTag}|${displayName}]${reason}")
                }
            }
            
            // 注意：任务移除在executeWaitingTask方法中统一处理
            
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "收取能量异常", e)
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
                    delay(CHECK_INTERVAL_MS)
                    cleanExpiredTasks()
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
        Log.record(TAG, "能量球蹲点管理器已初始化")
    }
}
