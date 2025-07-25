package fansirsqi.xposed.sesame.task.antFarm

import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.entity.AlipayUser
import fansirsqi.xposed.sesame.extensions.JSONExtensions.toJSONArray
import fansirsqi.xposed.sesame.model.modelFieldExt.SelectModelField
import fansirsqi.xposed.sesame.task.antFarm.AntFarm.AnimalFeedStatus
import fansirsqi.xposed.sesame.task.antFarm.AntFarm.AnimalInteractStatus
import fansirsqi.xposed.sesame.util.GlobalThreadPools
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.RandomUtil
import fansirsqi.xposed.sesame.util.ResChecker
import fansirsqi.xposed.sesame.util.maps.UserMap
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Objects
import kotlin.math.abs


data object AntFarmFamily {
    private const val TAG = "小鸡家庭"

    /**
     * 家庭ID
     */
    private var groupId: String = ""

    /**
     * 家庭名称
     */
    private var groupName: String = ""

    /**
     * 家庭成员对象
     */
    private var familyAnimals: JSONArray = JSONArray()

    /**
     * 家庭成员列表
     */
    private var familyUserIds: MutableList<String> = mutableListOf()

    /**
     * 互动功能列表
     */
    private var familyInteractActions: JSONArray = JSONArray()

    /**
     * 美食配置对象
     */
    private var eatTogetherConfig: JSONObject = JSONObject()


    fun run(familyOptions: SelectModelField, notInviteList: SelectModelField) {
        try {
            enterFamily(familyOptions, notInviteList)
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e.message, e)
        }
    }

    /**
     * 进入家庭
     */
    fun enterFamily(familyOptions: SelectModelField, notInviteList: SelectModelField) {
        try {
            var enterRes = JSONObject(AntFarmRpcCall.enterFamily());
            if (ResChecker.checkRes(TAG, enterRes)) {
                groupId = enterRes.getString("groupId")
                groupName = enterRes.getString("groupName")
                var familyAwardNum: Int = enterRes.optInt("familyAwardNum", 0)//奖励数量
                var familySignTips: Boolean = enterRes.optBoolean("familySignTips", false)//签到
                var assignFamilyMemberInfo: JSONObject? = enterRes.optJSONObject("assignFamilyMemberInfo")//分配成员信息-顶梁柱
                familyAnimals = enterRes.getJSONArray("animals")//家庭动物列表
                familyUserIds = (0..<familyAnimals.length())
                    .map { familyAnimals.getJSONObject(it).getString("userId") }
                    .toMutableList()
                familyInteractActions = enterRes.getJSONArray("familyInteractActions")//互动功能列表
                eatTogetherConfig = enterRes.getJSONObject("eatTogetherConfig")//美食配置对象


                if (familyOptions.value.contains("familySign") && familySignTips) {
                    familySign()
                }

                if (assignFamilyMemberInfo != null
                    && familyOptions.value.contains("assignRights")
                    && assignFamilyMemberInfo.getJSONObject("assignRights").getString("status") != "USED"
                ) {
                    if (assignFamilyMemberInfo.getJSONObject("assignRights").getString("assignRightsOwner") == UserMap.currentUid) {
                        assignFamilyMember(assignFamilyMemberInfo, familyUserIds)
                    } else {
                        Log.record("家庭任务🏡[使用顶梁柱特权] 不是家里的顶梁柱！")
                        familyOptions.value.remove("assignRights")
                    }
                }

                if (familyOptions.value.contains("familyClaimReward") && familyAwardNum > 0) {
                    familyClaimRewardList()
                }

                if (familyOptions.value.contains("feedFamilyAnimal")) {
                    familyFeedFriendAnimal(familyAnimals)
                }

                if (familyOptions.value.contains("eatTogetherConfig")) {
                    familyEatTogether(eatTogetherConfig, familyInteractActions, familyUserIds)
                }

                if (familyOptions.value.contains("deliverMsgSend")) {
                    deliverMsgSend(familyUserIds)
                }

                if (familyOptions.value.contains("shareToFriends")) {
                    familyShareToFriends(familyUserIds, notInviteList)
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e.message, e)
        }
    }


    /**
     * 家庭签到
     */
    fun familySign() {
        try {
            if (Status.hasFlagToday("farmfamily::dailySign")) return
            val res = JSONObject(AntFarmRpcCall.familyReceiveFarmTaskAward("FAMILY_SIGN_TASK"))
            if (ResChecker.checkRes(TAG, res)) {
                Log.farm("家庭任务🏡每日签到")
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e.message, e)
        }
    }

    /**
     * 领取家庭奖励
     */
    fun familyClaimRewardList() {
        try {
            var jo = JSONObject(AntFarmRpcCall.familyAwardList())
            if (ResChecker.checkRes(TAG, jo)) {
                val ja = jo.getJSONArray("familyAwardRecordList")
                for (i in 0..<ja.length()) {
                    jo = ja.getJSONObject(i)
                    if (jo.optBoolean("expired")
                        || jo.optBoolean("received", true)
                        || jo.has("linkUrl")
                        || (jo.has("operability") && !jo.getBoolean("operability"))
                    ) {
                        continue
                    }
                    val rightId = jo.getString("rightId")
                    val awardName = jo.getString("awardName")
                    val count = jo.optInt("count", 1)
                    var receveRes = JSONObject(AntFarmRpcCall.receiveFamilyAward(rightId))
                    if (ResChecker.checkRes(TAG, receveRes)) {
                        Log.farm("家庭奖励🏆: $awardName x $count")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "家庭领取奖励", t)
        }
    }

    /**
     * 顶梁柱
     */
    fun assignFamilyMember(jsonObject: JSONObject, userIds: MutableList<String>) {
        try {
            userIds.remove(UserMap.currentUid)
            //随机选一个家庭成员
            if (userIds.isEmpty()) {
                return
            }
            val beAssignUser = userIds[RandomUtil.nextInt(0, userIds.size - 1)]
            //随机获取一个任务类型
            val assignConfigList = jsonObject.getJSONArray("assignConfigList")
            val assignConfig = assignConfigList.getJSONObject(RandomUtil.nextInt(0, assignConfigList.length() - 1))
            var jo = JSONObject(AntFarmRpcCall.assignFamilyMember(assignConfig.getString("assignAction"), beAssignUser))
            if (ResChecker.checkRes(TAG, jo)) {
                Log.farm("家庭任务🏡[使用顶梁柱特权] ${assignConfig.getString("assignDesc")}")
//                val sendRes = JSONObject(AntFarmRpcCall.sendChat(assignConfig.getString("chatCardType"), beAssignUser))
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, t)
        }
    }


    /**
     * 帮好友喂小鸡
     * @param animals 家庭动物列表
     */
    fun familyFeedFriendAnimal(animals: JSONArray) {
        try {
            for (i in 0..<animals.length()) {
                val animal = animals.getJSONObject(i)
                val animalStatusVo = animal.getJSONObject("animalStatusVO")
                if (AnimalInteractStatus.HOME.name == animalStatusVo.getString("animalInteractStatus") && AnimalFeedStatus.HUNGRY.name == animalStatusVo.getString("animalFeedStatus")) {
                    val groupId = animal.getString("groupId")
                    val farmId = animal.getString("farmId")
                    val userId = animal.getString("userId")
                    if (UserMap.getUserIdSet().contains(userId)) {
                        if (Status.hasFlagToday("farm::feedFriendLimit")) {
                            Log.runtime("今日喂鸡次数已达上限🥣 家庭喂")
                            return
                        }
                        val jo = JSONObject(AntFarmRpcCall.feedFriendAnimal(farmId, groupId))
                        if (ResChecker.checkRes(TAG, jo)) {
                            Log.farm("家庭任务🏠帮喂好友🥣[" + UserMap.getMaskName(userId) + "]的小鸡180g #剩余" + jo.getInt("foodStock") + "g")
                        }
                    } else {
                        Log.error(TAG, "$userId 不是你的好友！ 跳过家庭喂食")
                        continue
                    }
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "familyFeedFriendAnimal err:")
            Log.printStackTrace(TAG, t)
        }
    }


    /**
     * 请客吃美食
     * @param eatTogetherConfig 美食配置对象
     * @param familyInteractActions 互动功能列表
     * @param familyUserIds 家庭成员列表
     */
    private fun familyEatTogether(eatTogetherConfig: JSONObject, familyInteractActions: JSONArray, familyUserIds: MutableList<String>) {
        try {
            var isEat = false
            val periodItemList = eatTogetherConfig.optJSONArray("periodItemList")
            if (periodItemList == null || periodItemList.length() == 0) {
                Log.error(TAG, "美食不足,无法请客,请检查小鸡厨房")
                return
            }
            if (familyInteractActions.length() > 0) {
                for (i in 0..<familyInteractActions.length()) {
                    val familyInteractAction = familyInteractActions.getJSONObject(i)
                    if ("EatTogether" == familyInteractAction.optString("familyInteractType")) {
                        val endTime = familyInteractAction.optLong("interactEndTime", 0)
                        val gaptime = endTime - System.currentTimeMillis()
                        Log.record("正在吃..${formatDuration(gaptime)} 吃完")
                        return
                    }
                }
            }
            var periodName = ""
            val currentTime = Calendar.getInstance()
            for (i in 0..<periodItemList.length()) {
                val periodItem = periodItemList.getJSONObject(i)
                val startHour = periodItem.optInt("startHour")
                val startMinute = periodItem.optInt("startMinute")
                val endHour = periodItem.optInt("endHour")
                val endMinute = periodItem.optInt("endMinute")
                val startTime = Calendar.getInstance()
                startTime.set(Calendar.HOUR_OF_DAY, startHour)
                startTime.set(Calendar.MINUTE, startMinute)
                val endTime = Calendar.getInstance()
                endTime.set(Calendar.HOUR_OF_DAY, endHour)
                endTime.set(Calendar.MINUTE, endMinute)
                if (currentTime.after(startTime) && currentTime.before(endTime)) {
                    periodName = periodItem.optString("periodName")
                    isEat = true
                    break
                }
            }
            if (!isEat) {
                Log.record("家庭任务🏠请客吃美食#当前时间不在美食时间段")
                return
            }
            if (Objects.isNull(familyUserIds) || familyUserIds.isEmpty()) {
                Log.record("家庭成员列表为空,无法请客")
                return
            }
            val array: JSONArray? = queryRecentFarmFood(familyUserIds.size)
            if (array == null) {
                Log.record("查询最近的几份美食为空,无法请客")
                return
            }
            val jo = JSONObject(AntFarmRpcCall.familyEatTogether(groupId, familyUserIds.toJSONArray(), array))
            if (ResChecker.checkRes(TAG, jo)) {
                Log.farm("家庭任务🏠请客" + periodName + "#消耗美食" + familyUserIds.size + "份")
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "familyEatTogether err:")
            Log.printStackTrace(TAG, t)
        }
    }

    /**
     * 查询最近的几份美食
     * @param queryNum 查询数量
     */
    fun queryRecentFarmFood(queryNum: Int): JSONArray? {
        try {
            val jo = JSONObject(AntFarmRpcCall.queryRecentFarmFood(queryNum))
            if (!ResChecker.checkRes(TAG, jo)) {
                return null
            }
            val cuisines = jo.getJSONArray("cuisines")
            var count = 0
            for (i in 0..<cuisines.length()) {
                val cuisine = cuisines.getJSONObject(i)
                count += cuisine.optInt("count")
            }
            if (cuisines != null && queryNum <= count) {
                return cuisines
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "queryRecentFarmFood err:", t)
        }
        return null
    }


/**
     * 发送道早安消息
     * @param familyUserIds 家庭成员ID列表
     */
    fun deliverMsgSend(familyUserIds: MutableList<String>) {
        // 检查时间范围（6-10点）
        if (!isWithinMorningHours()) return

        // 检查必要参数
        if (groupId == null) return

        // 准备接收用户列表（排除当前用户）
        val recipients = familyUserIds.toMutableList().apply {
            remove(UserMap.currentUid)
        }
        if (recipients.isEmpty()) return

        // 检查今日是否已发送
        if (Status.hasFlagToday("antFarm::deliverMsgSend")) return

        try {
            // 转换用户ID列表为JSON格式
            val userIds = JSONArray().apply {
                recipients.forEach { put(it) }
            }

            // 调用推荐接口获取内容
            val recommendResp = callRecommendApi(userIds)
            val ariverRpcTraceId = recommendResp.optString("ariverRpcTraceId")
                .takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("缺少 ariverRpcTraceId")

            // 调用内容扩展接口
            val expandResp = callContentExpandApi(userIds, ariverRpcTraceId)

            // 延迟处理（避免请求过于频繁）
            GlobalThreadPools.sleep(500)

            // 提取关键信息
            val content = recommendResp.optString("content")
            val deliverId = recommendResp.optString("deliverId")

            // 检查必要字段
            if (content.isEmpty() || deliverId.isEmpty()) {
                throw IllegalArgumentException("缺少 content 或 deliverId")
            }

            // 发送消息
            val sendResp = callSendMessageApi(groupId!!, userIds, content, deliverId)
            if (ResChecker.checkRes(TAG, sendResp)) {
                Log.farm("家庭任务🏠道早安: $content 🌈")
                Status.setFlagToday("antFarm::deliverMsgSend")
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "deliverMsgSend 异常:", e)
        }
    }

    /**
     * 检查当前时间是否在早晨范围内（6-10点）
     */
    private fun isWithinMorningHours(): Boolean {
        val currentTime = Calendar.getInstance()
        val hour = currentTime.get(Calendar.HOUR_OF_DAY)
        return hour in 6 until 10
    }

    /**
     * 调用推荐接口并处理响应
     */
    private fun callRecommendApi(userIds: JSONArray): JSONObject {
        val resp = JSONObject(AntFarmRpcCall.deliverSubjectRecommend(userIds))
        if (!ResChecker.checkRes(TAG, resp)) {
            throw RuntimeException("推荐接口请求失败: $resp")
        }
        return resp
    }

    /**
     * 调用内容扩展接口并处理响应
     */
    private fun callContentExpandApi(userIds: JSONArray, traceId: String): JSONObject {
        val resp = JSONObject(AntFarmRpcCall.deliverContentExpand(userIds, traceId))
        if (!ResChecker.checkRes(TAG, resp)) {
            throw RuntimeException("内容扩展接口请求失败: $resp")
        }
        return resp
    }

    /**
     * 调用消息发送接口并处理响应
     */
    private fun callSendMessageApi(groupId: String, userIds: JSONArray, content: String, deliverId: String): JSONObject {
        return JSONObject(AntFarmRpcCall.deliverMsgSend(groupId, userIds, content, deliverId))
    }


    /**
     * 好友分享家庭
     * @param familyUserIds 好友列表
     * @param notInviteList 不邀请列表
     */
    private fun familyShareToFriends(familyUserIds: MutableList<String>, notInviteList: SelectModelField) {
        try {
            if (Status.hasFlagToday("antFarm::familyShareToFriends")) {
                return
            }

            val familyValue: MutableSet<String?> = notInviteList.value
            val allUser: List<AlipayUser> = AlipayUser.getList()

            if (allUser.isEmpty()) {
                Log.error(TAG, "allUser is empty")
                return
            }

            // 打乱顺序，实现随机选取
            val shuffledUsers = allUser.shuffled()

            val inviteList = JSONArray()
            for (u in shuffledUsers) {
                if (!familyUserIds.contains(u.id) && !familyValue.contains(u.id)) {
                    inviteList.put(u.id)
                    if (inviteList.length() >= 6) {
                        break
                    }
                }
            }

            if (inviteList.length() == 0) {
                Log.error(TAG, "没有符合分享条件的好友")
                return
            }

            Log.runtime(TAG, "inviteList: $inviteList")

            val jo = JSONObject(AntFarmRpcCall.inviteFriendVisitFamily(inviteList))
            if (ResChecker.checkRes(TAG, jo)) {
                Log.farm("家庭任务🏠分享好友")
                Status.setFlagToday("antFarm::familyShareToFriends")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "familyShareToFriends err:", t)
        }
    }


    /**
     * 通用时间差格式化（自动区分过去/未来）
     * @param diffMillis 任意时间戳（毫秒）
     * @return 易读字符串，如 "刚刚", "5分钟后", "3天前"
     */
    fun formatDuration(diffMillis: Long): String {
        val absSeconds = abs(diffMillis) / 1000

        val (value, unit) = when {
            absSeconds < 60 -> Pair(absSeconds, "秒")
            absSeconds < 3600 -> Pair(absSeconds / 60, "分钟")
            absSeconds < 86400 -> Pair(absSeconds / 3600, "小时")
            absSeconds < 2592000 -> Pair(absSeconds / 86400, "天")
            absSeconds < 31536000 -> Pair(absSeconds / 2592000, "个月")
            else -> Pair(absSeconds / 31536000, "年")
        }

        return when {
            absSeconds < 1 -> "刚刚"
            diffMillis > 0 -> "$value$unit 后"
            else -> "$value$unit 前"
        }
    }


}
