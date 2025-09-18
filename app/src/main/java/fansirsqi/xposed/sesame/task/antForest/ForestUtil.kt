@file:JvmName("ForestUtil")

package fansirsqi.xposed.sesame.task.antForest

import org.json.JSONObject

object ForestUtil {

    @JvmStatic
    fun hasShield(userHomeObj: JSONObject, serverTime: Long): Boolean {
        return hasPropGroup(userHomeObj, "shield", serverTime)
    }

    @JvmStatic
    fun hasBombCard(userHomeObj: JSONObject, serverTime: Long): Boolean {
        return hasPropGroup(userHomeObj, "energyBombCard", serverTime)
    }

    /**
     * 获取保护罩结束时间
     * @param userHomeObj 用户主页数据
     * @return 保护罩结束时间戳，如果没有保护罩则返回0
     */
    @JvmStatic
    fun getShieldEndTime(userHomeObj: JSONObject): Long {
        return getPropGroupEndTime(userHomeObj, "shield")
    }

    /**
     * 获取炸弹卡结束时间
     * @param userHomeObj 用户主页数据
     * @return 炸弹卡结束时间戳，如果没有炸弹卡则返回0
     */
    @JvmStatic
    fun getBombCardEndTime(userHomeObj: JSONObject): Long {
        return getPropGroupEndTime(userHomeObj, "energyBombCard")
    }

    /**
     * 获取用户的保护结束时间（取保护罩和炸弹卡中最晚的时间）
     * @param userHomeObj 用户主页数据
     * @return 保护结束时间戳，如果都没有则返回0
     */
    @JvmStatic
    fun getProtectionEndTime(userHomeObj: JSONObject): Long {
        val shieldEndTime = getShieldEndTime(userHomeObj)
        val bombEndTime = getBombCardEndTime(userHomeObj)
        return maxOf(shieldEndTime, bombEndTime)
    }

    /**
     * 检查是否应该跳过蹲点（保护时间比能量球成熟时间长）
     * @param userHomeObj 用户主页数据
     * @param energyMaturityTime 能量球成熟时间
     * @return true表示应该跳过蹲点，false表示可以蹲点
     */
    @JvmStatic
    fun shouldSkipWaitingDueToProtection(userHomeObj: JSONObject, energyMaturityTime: Long): Boolean {
        val protectionEndTime = getProtectionEndTime(userHomeObj)
        return protectionEndTime > energyMaturityTime
    }

    private fun hasPropGroup(userHomeObj: JSONObject, group: String, serverTime: Long): Boolean {
        val props = userHomeObj.optJSONArray("usingUserProps")
            ?: userHomeObj.optJSONArray("usingUserPropsNew")
            ?: return false
        return (0 until props.length()).any { i ->
            val prop = props.optJSONObject(i)
            prop?.optString("propGroup") == group && prop.optLong("endTime", 0L) > serverTime
        }
    }

    /**
     * 获取指定道具组的结束时间
     * @param userHomeObj 用户主页数据
     * @param group 道具组名称（如"shield"、"energyBombCard"）
     * @return 结束时间戳，如果没有该道具则返回0
     */
    private fun getPropGroupEndTime(userHomeObj: JSONObject, group: String): Long {
        val props = userHomeObj.optJSONArray("usingUserProps")
            ?: userHomeObj.optJSONArray("usingUserPropsNew")
            ?: return 0L
        
        var latestEndTime = 0L
        for (i in 0 until props.length()) {
            val prop = props.optJSONObject(i) ?: continue
            if (prop.optString("propGroup") == group) {
                val endTime = prop.optLong("endTime", 0L)
                latestEndTime = maxOf(latestEndTime, endTime)
            }
        }
        return latestEndTime
    }
}
