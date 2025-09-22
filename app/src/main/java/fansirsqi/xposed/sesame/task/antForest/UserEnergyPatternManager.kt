package fansirsqi.xposed.sesame.task.antForest

import fansirsqi.xposed.sesame.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * 用户能量收取模式数据类
 * 用于分析用户的能量收取习惯，但不影响蹲点时机
 */
data class UserEnergyPattern(
    val userId: String,
    val collectSuccessRate: Double = 0.8, // 收取成功率
    val avgResponseTime: Long = 1000L,    // 平均响应时间
    val lastCollectTime: Long = 0L,       // 上次收取时间
    val isActiveUser: Boolean = true      // 是否活跃用户
) {
    /**
     * 获取建议的检查间隔（不影响收取时机，只影响检查频率）
     */
    fun getSuggestedCheckInterval(timeToTarget: Long): Long {
        return when {
            timeToTarget <= 1000L -> 200L        // 1秒内：0.2秒检查
            timeToTarget <= 3000L -> 500L        // 3秒内：0.5秒检查
            timeToTarget <= 10000L -> 1000L      // 10秒内：1秒检查
            timeToTarget <= 30000L -> 2000L      // 30秒内：2秒检查
            timeToTarget <= 60000L -> 5000L      // 1分钟内：5秒检查
            timeToTarget <= 300000L -> 15000L    // 5分钟内：15秒检查
            else -> 60000L                       // 其他：1分钟检查
        }
    }
    
    /**
     * 判断是否需要更频繁的检查（基于用户活跃度）
     */
    fun needsFrequentCheck(): Boolean {
        return isActiveUser && collectSuccessRate > 0.8
    }
}

/**
 * 用户能量模式管理器
 * 单一职责：管理用户的能量收取模式和统计数据
 */
object UserEnergyPatternManager {
    private const val TAG = "UserEnergyPatternManager"
    
    // 用户模式存储
    private val userPatterns = ConcurrentHashMap<String, UserEnergyPattern>()
    
    /**
     * 获取用户模式
     */
    fun getUserPattern(userId: String): UserEnergyPattern {
        return userPatterns[userId] ?: UserEnergyPattern(userId)
    }
    
    /**
     * 更新用户模式（基于收取结果）
     */
    fun updateUserPattern(userId: String, result: CollectResult, responseTime: Long) {
        val currentPattern = getUserPattern(userId)
        val currentTime = System.currentTimeMillis()
        
        // 使用指数移动平均更新成功率
        val alpha = 0.1
        val newSuccessRate = if (result.success) {
            currentPattern.collectSuccessRate * (1 - alpha) + alpha
        } else {
            currentPattern.collectSuccessRate * (1 - alpha)
        }
        
        // 更新平均响应时间
        val newAvgResponseTime = if (responseTime > 0) {
            (currentPattern.avgResponseTime * 0.8 + responseTime * 0.2).toLong()
        } else {
            currentPattern.avgResponseTime
        }
        
        // 判断用户活跃度（24小时内有活动）
        val timeSinceLastCollect = currentTime - currentPattern.lastCollectTime
        val isActive = timeSinceLastCollect < 24 * 60 * 60 * 1000L
        
        val updatedPattern = currentPattern.copy(
            collectSuccessRate = newSuccessRate,
            avgResponseTime = newAvgResponseTime,
            lastCollectTime = if (result.success) currentTime else currentPattern.lastCollectTime,
            isActiveUser = isActive
        )
        
        userPatterns[userId] = updatedPattern
        Log.debug(TAG, "更新用户[${userId}]模式：成功率[${String.format("%.2f", newSuccessRate)}] 响应时间[${newAvgResponseTime}ms] 活跃[${isActive}]")
    }
    
    /**
     * 获取建议的动态间隔（用于调整检查频率）
     */
    fun getSuggestedInterval(userId: String, timeToTarget: Long): Long {
        val pattern = getUserPattern(userId)
        val baseInterval = pattern.getSuggestedCheckInterval(timeToTarget)
        
        // 活跃用户可以更频繁检查
        return if (pattern.needsFrequentCheck()) {
            max(1000L, (baseInterval * 0.8).toLong())
        } else {
            baseInterval
        }
    }
    
    /**
     * 清理过期的用户模式数据
     */
    fun cleanupExpiredPatterns() {
        val currentTime = System.currentTimeMillis()
        val expireTime = 30 * 24 * 60 * 60 * 1000L // 30天
        
        val expiredUsers = userPatterns.filter { (_, pattern) ->
            currentTime - pattern.lastCollectTime > expireTime
        }.keys
        
        expiredUsers.forEach { userId ->
            userPatterns.remove(userId)
        }
        
        if (expiredUsers.isNotEmpty()) {
            Log.debug(TAG, "清理过期用户模式数据：${expiredUsers.size}个用户")
        }
    }
    
    /**
     * 获取所有用户统计信息
     */
    fun getStatistics(): String {
        val totalUsers = userPatterns.size
        val activeUsers = userPatterns.values.count { it.isActiveUser }
        val avgSuccessRate = userPatterns.values.map { it.collectSuccessRate }.average()
        
        return "用户统计：总数[$totalUsers] 活跃[$activeUsers] 平均成功率[${String.format("%.2f", avgSuccessRate)}]"
    }
}
