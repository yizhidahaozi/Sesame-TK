package fansirsqi.xposed.sesame.task.antForest

import com.fasterxml.jackson.core.type.TypeReference
import fansirsqi.xposed.sesame.newutil.DataStore
import fansirsqi.xposed.sesame.newutil.DataStore.put
import fansirsqi.xposed.sesame.util.JsonUtil
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.TimeUtil
import java.util.concurrent.ConcurrentHashMap

/**
 * 临时黑名单管理器
 * 用于管理因所有条件不满足而临时跳过的好友，第二天自动恢复
 */
object TemporaryBlockManager {
    private const val TAG = "TemporaryBlockManager"
    private const val STORAGE_KEY = "antForest_temporaryBlockList"
    
    // 存储格式：Map<日期, Set<用户ID>>
    private val blockListByDate: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()
    
    /**
     * 清理过期的临时黑名单（不是今天的记录）
     */
    fun cleanExpiredTemporaryBlockList() {
        try {
            // 从持久化存储加载临时黑名单
            loadFromStorage()
            
            val today = TimeUtil.getDateStr2()
            val iterator = blockListByDate.iterator()
            var removedCount = 0
            
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key != today) {
                    removedCount += entry.value.size
                    iterator.remove()
                }
            }
            
            if (removedCount > 0) {
                Log.record(TAG, "🗑️ 清理了 $removedCount 个过期的临时黑名单用户")
                saveToStorage()
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "清理临时黑名单失败", e)
        }
    }
    
    /**
     * 检查用户是否在临时黑名单中
     */
    fun isInTemporaryBlockList(userId: String?): Boolean {
        if (userId == null) return false
        val today = TimeUtil.getDateStr2()
        val todayList = blockListByDate[today] ?: return false
        return todayList.contains(userId)
    }
    
    /**
     * 将用户添加到临时黑名单
     * @return true 表示首次添加，false 表示已存在
     */
    fun addToTemporaryBlockList(userId: String?): Boolean {
        if (userId == null) return false
        try {
            val today = TimeUtil.getDateStr2()
            val todayList = blockListByDate.getOrPut(today) { ConcurrentHashMap.newKeySet() }
            
            if (todayList.add(userId)) {
                saveToStorage()
                return true
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "添加用户到临时黑名单失败", e)
        }
        return false
    }
    
    /**
     * 获取今天临时黑名单的用户数量
     */
    fun getTodayBlockCount(): Int {
        val today = TimeUtil.getDateStr2()
        return blockListByDate[today]?.size ?: 0
    }
    
    /**
     * 从持久化存储加载
     */
    private fun loadFromStorage() {
        try {
            val savedData = DataStore.get(STORAGE_KEY, String::class.java)
            if (!savedData.isNullOrEmpty()) {
                val savedMap: Map<String, Set<String>> = JsonUtil.parseObject(
                    savedData, 
                    object : TypeReference<Map<String, Set<String>>>() {}
                )
                blockListByDate.clear()
                savedMap.forEach { (date, userSet) ->
                    blockListByDate[date] = ConcurrentHashMap.newKeySet<String>().apply { addAll(userSet) }
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "加载临时黑名单失败", e)
        }
    }
    
    /**
     * 保存到持久化存储
     */
    private fun saveToStorage() {
        try {
            val jsonStr = JsonUtil.formatJson(blockListByDate, false)
            put(STORAGE_KEY, jsonStr)
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "保存临时黑名单失败", e)
        }
    }
}
