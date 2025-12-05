package fansirsqi.xposed.sesame.task.antOrchard;

import android.util.Base64
import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.model.ModelFields
import fansirsqi.xposed.sesame.model.ModelGroup
import fansirsqi.xposed.sesame.model.modelFieldExt.BooleanModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.IntegerModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.SelectModelField
import fansirsqi.xposed.sesame.task.ModelTask
import fansirsqi.xposed.sesame.task.TaskCommon
import fansirsqi.xposed.sesame.util.CoroutineUtils
import fansirsqi.xposed.sesame.util.Detector
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.Notify
import fansirsqi.xposed.sesame.util.RandomUtil
import fansirsqi.xposed.sesame.util.ResChecker
import fansirsqi.xposed.sesame.util.maps.UserMap
import org.json.JSONObject

class AntOrchard : ModelTask() {
    companion object {
        private val TAG = AntOrchard::class.java.simpleName
    }

    private var userId: String? = null
    private var treeLevel: String? = null
    private var executeIntervalInt: Int = 0
    private lateinit var executeInterval: IntegerModelField
    private lateinit var receiveOrchardTaskAward: BooleanModelField
    private lateinit var orchardSpreadManure: BooleanModelField
    private lateinit var orchardSpreadManureCount: IntegerModelField

    // 助力好友列表
    private lateinit var assistFriendList: SelectModelField

    override fun getName(): String {
        return "农场"
    }

    override fun getGroup(): ModelGroup {
        return ModelGroup.ORCHARD
    }

    override fun getIcon(): String {
        return "AntOrchard.png"
    }

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(
            IntegerModelField(
                "executeInterval",
                "执行间隔(毫秒)",
                500
            ).also { executeInterval = it }
        )
        modelFields.addField(
            BooleanModelField("receiveOrchardTaskAward", "收取农场任务奖励", false).also { receiveOrchardTaskAward = it }
        )
        modelFields.addField(
            BooleanModelField("orchardSpreadManure", "果树施肥", false).also { orchardSpreadManure = it }
        )
        modelFields.addField(
            IntegerModelField("orchardSpreadManureCount", "农场每日施肥次数", 0).also { orchardSpreadManureCount = it }
        )
//        待修
//        modelFields.addField(
//            SelectModelField("assistFriendList", "助力好友列表", LinkedHashSet(), AlipayUser::getList).also { assistFriendList = it }
//        )
        return modelFields
    }

    override fun boot(classLoader: ClassLoader?) {
        super.boot(classLoader)
        Log.record("AntOrchard.boot")
    }

    override fun check(): Boolean {
        return when {
            TaskCommon.IS_ENERGY_TIME -> {
                Log.record(TAG, "⏸ 当前为只收能量时间停止执行${name}任务！")
                false
            }

            TaskCommon.IS_MODULE_SLEEP_TIME -> {
                Log.record(TAG, "💤 模块休眠时间停止执行${name}任务！")
                false
            }

            else -> true
        }
    }

    override suspend fun runSuspend() {
        try {
            Log.record(TAG, "执行开始-${name}")
            userId = UserMap.currentUid
            executeIntervalInt = maxOf(executeInterval.value, 500)
            Log.runtime("user $userId $executeIntervalInt")
            val jo = JSONObject(AntOrchardRpcCall.orchardIndex())
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.error(TAG, "粑粑农场 Index获取失败！")
            } else {
                if (jo.optBoolean("userOpenOrchard", false)) {
                    val taobaoData = JSONObject(jo.getString("taobaoData"))
                    treeLevel = taobaoData.getJSONObject("gameInfo") // 树等级
                        .getJSONObject("plantInfo").getJSONObject("seedStage").getInt("stageLevel").toString()

                    if (jo.has("lotteryPlusInfo")) {
                        drawLotteryPlus(jo.getJSONObject("lotteryPlusInfo"))
                    }
                    // extraInfoGet()
//                    if (receiveOrchardTaskAward.value) {
//                        doOrchardDailyTask(userId!!)
//                        triggerTbTask()
//                    }
                    val orchardSpreadManureCountValue = orchardSpreadManureCount.value //农场每日施肥次数

                    if (orchardSpreadManureCountValue > 0 && Status.canSpreadManureToday(userId!!) && orchardSpreadManure.value) {
                        CoroutineUtils.sleepCompat(200)
                        orchardSpreadManure()
                    }

//                    if (orchardSpreadManureCountValue >= 3 && orchardSpreadManureCountValue < 10) {
//                        querySubplotsActivity(3)
//                    } else if (orchardSpreadManureCountValue >= 10) {
//                        querySubplotsActivity(10)
//                    }
//                    // 助力
//                    orchardassistFriend()
                } else {
                    enableField.value = false
                    Log.farm("请先开通芭芭农场！")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "农场主流程执行异常！", t)
        } finally {
            Log.record(TAG, "执行结束-${name}嚯嚯嚯")
        }
    }

    /**
     * 判断是否继续施肥
     */
    private fun canSpreadManureContinue(stageBefore: Int, stageAfter: Int): Boolean {
        return if (stageAfter - stageBefore > 1) {
            true
        } else {
            Log.record(TAG, "施肥只加0.01%进度今日停止施肥！")
            false
        }
    }

    /**
     * 领取 reward 丰收礼包
     */
    private fun gotHarvest(orchardIndexData: JSONObject) {
        if (orchardIndexData.has("spreadManureActivity")) {
            val spreadManureStage = orchardIndexData.getJSONObject("spreadManureActivity").getJSONObject("spreadManureStage")
            if (spreadManureStage.getString("status") == "FINISHED") {
                val sceneCode = spreadManureStage.getString("sceneCode")
                val taskType = spreadManureStage.getString("taskType")
                val awardCount = spreadManureStage.getInt("awardCount")
                val joo = JSONObject(AntOrchardRpcCall.receiveTaskAward(sceneCode, taskType))
                if (joo.optBoolean("success")) {
                    Log.farm("农场丰收礼包🎁[返肥料奖励*$awardCount]g")
                }
            }
        }
    }

    private fun checkCanExchange(orchardIndextaobaoData: JSONObject): JSONObject {
        val plantInfo = orchardIndextaobaoData.getJSONObject("gameInfo").getJSONObject("plantInfo")
        val canExchange = plantInfo.getBoolean("canExchange")
        if (canExchange) {
            Log.farm("🎉 农场果树似乎可以兑换了！")
            Notify.sendNewNotification("发生什么事了？", "芝麻粒TK提醒您：\n 🎉 农场果树似乎可以兑换了！")
        }
        return plantInfo
    }

    private fun orchardSpreadManure() {
        try {
            var count = 0
            do {
                try {
                    val orchardIndexData = JSONObject(AntOrchardRpcCall.orchardIndex())
                    if (!ResChecker.checkRes(TAG, orchardIndexData)) {
                        Log.error(TAG, "施肥前orchardIndex请求失败！$orchardIndexData ")
                        return
                    }
                    gotHarvest(orchardIndexData) //丰收礼包
                    val orchardIndextaobaoData = JSONObject(orchardIndexData.getString("taobaoData"))
                    val plantInfo = checkCanExchange(orchardIndextaobaoData)
                    val seedStage = plantInfo.getJSONObject("seedStage")
                    treeLevel = seedStage.getInt("stageLevel").toString()
                    val accountInfo = orchardIndextaobaoData.getJSONObject("gameInfo").getJSONObject("accountInfo")
                    val happyPoint = accountInfo.getString("happyPoint").toInt() //当前剩余肥料
                    val wateringCost = accountInfo.getInt("wateringCost") //施肥消耗
                    val wateringLeftTimes = accountInfo.getInt("wateringLeftTimes") //剩余施肥次数
                    CoroutineUtils.sleepCompat(20)
                    if (count > 20) {
                        Log.runtime("一次浇水不超过 $count 次避免任务时间过长")
                        return
                    }
                    if (happyPoint < wateringCost) {//需要有足够的肥料
                        Log.runtime("农场肥料不足以施肥 $wateringCost")
                        return
                    }
                    if (wateringLeftTimes == 0) {//需要有剩余施肥次数
                        Log.runtime("剩余施肥次数为 0")
                        return
                    }
                    if ((200 - wateringLeftTimes < orchardSpreadManureCount.value)) //剩余施肥次数不能超过施肥次数限制
                    {
                        val wua = Detector.genWua()
                        Log.runtime("set Wua $wua")
                        val spreadManureData = JSONObject(AntOrchardRpcCall.orchardSpreadManure(wua)) //施肥
                        if (!ResChecker.checkRes(TAG, spreadManureData)) {
                            Log.error(TAG, "农场施肥失败:$spreadManureData")
                            return
                        }
                        val spreadManureTaobaoData = JSONObject(spreadManureData.getString("taobaoData"))
                        val stageText = spreadManureTaobaoData.getJSONObject("currentStage").getString("stageText")
                        val dailyAppWateringCount = spreadManureTaobaoData.getJSONObject("statistics").getInt("dailyAppWateringCount")
                        Log.farm("今日农场施已肥💩 $dailyAppWateringCount 次 [$stageText]") //再施16.50%果实将成熟
                        count += 1
                        if (
                            !canSpreadManureContinue(seedStage.getInt("totalValue"), spreadManureTaobaoData.getJSONObject("currentStage").getInt("totalValue"))
                        ) {
                            Status.spreadManureToday(userId!!)
                            return
                        }
                        continue
                    }
                } finally {
                    CoroutineUtils.sleepCompat(executeIntervalInt.toLong())
                }
                break
            } while (true)
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "农场施肥异常！", t)
        }
    }

    /**
     * 获取额外信息
     */
    private fun extraInfoGet() {
        try {
            val jo = JSONObject(AntOrchardRpcCall.extraInfoGet())
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.error(TAG, "粑粑农场 extraInfoGet 获取失败！")
            } else {
                val fertilizerPacket = jo.getJSONObject("data") //肥料包
                    .getJSONObject("extraData").getJSONObject("fertilizerPacket")
                if (fertilizerPacket.getString("status") == "todayFertilizerFinish") return
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "extraInfoGet err:", t)
        }
    }

    /**
     * 7日礼包
     */
    private fun drawLotteryPlus(lotteryPlusInfo: JSONObject) {
        try {
            if (!lotteryPlusInfo.has("userSevenDaysGiftsItem")) return
            val itemId = lotteryPlusInfo.getString("itemId")
            val jo = lotteryPlusInfo.getJSONObject("userSevenDaysGiftsItem")
            val ja = jo.getJSONArray("userEverydayGiftItems")
            for (i in 0 until ja.length()) {
                val jo2 = ja.getJSONObject(i)
                if (jo2.getString("itemId") == itemId) {
                    if (!jo2.getBoolean("received")) {
                        val jo3 = JSONObject(AntOrchardRpcCall.drawLottery())
                        if (jo3.getString("resultCode") == "100") {
                            val userEverydayGiftItems = jo3.getJSONObject("lotteryPlusInfo").getJSONObject("userSevenDaysGiftsItem").getJSONArray("userEverydayGiftItems")
                            for (j in 0 until userEverydayGiftItems.length()) {
                                val jo4 = userEverydayGiftItems.getJSONObject(j)
                                if (jo4.getString("itemId") == itemId) {
                                    val awardCount = jo4.optInt("awardCount", 1)
                                    Log.farm("七日礼包🎁[获得肥料]#$awardCount g")
                                    break
                                }
                            }
                        } else {
                            Log.runtime(jo3.getString("resultDesc"), jo3.toString())
                        }
                    } else {
                        Log.record(TAG, "七日礼包已领取")
                    }
                    break
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "drawLotteryPlus err:")
            Log.printStackTrace(TAG, t)
        }
    }

    /**
     * 农场任务
     */
    private fun doOrchardDailyTask(userId: String) {
        try {
            val s = AntOrchardRpcCall.orchardListTask()
            val jo = JSONObject(s)
            if (jo.getString("resultCode") == "100") {
                if (jo.has("signTaskInfo")) {
                    val signTaskInfo = jo.getJSONObject("signTaskInfo")
                    orchardSign(signTaskInfo)
                }
                val jaTaskList = jo.getJSONArray("taskList")
                for (i in 0 until jaTaskList.length()) {
                    val jo2 = jaTaskList.getJSONObject(i)
                    if (jo2.getString("taskStatus") != "TODO") continue
                    val title = jo2.getJSONObject("taskDisplayConfig").getString("title")
                    if (jo2.getString("actionType") == "TRIGGER" || jo2.getString("actionType") == "ADD_HOME" || jo2.getString("actionType") == "PUSH_SUBSCRIBE") {
                        val taskId = jo2.getString("taskId")
                        val sceneCode = jo2.getString("sceneCode")
                        val jo3 = JSONObject(AntOrchardRpcCall.finishTask(userId, sceneCode, taskId))
                        if (jo3.optBoolean("success")) {
                            Log.farm("农场任务🧾[$title]")
                        } else {
                            Log.record(jo3.getString("desc"))
                            Log.runtime(jo3.toString())
                        }
                    }
                }
            } else {
                Log.record(jo.getString("resultCode"))
                Log.runtime(s)
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "doOrchardDailyTask err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun orchardSign(signTaskInfo: JSONObject) {
        try {
            val currentSignItem = signTaskInfo.getJSONObject("currentSignItem")
            if (!currentSignItem.getBoolean("signed")) {
                val joSign = JSONObject(AntOrchardRpcCall.orchardSign())
                if (joSign.getString("resultCode") == "100") {
                    val awardCount = joSign.getJSONObject("signTaskInfo").getJSONObject("currentSignItem").getInt("awardCount")
                    Log.farm("农场签到📅[获得肥料]#$awardCount g")
                } else {
                    Log.runtime(joSign.getString("resultDesc"), joSign.toString())
                }
            } else {
                Log.record(TAG, "农场今日已签到")
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "orchardSign err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun triggerTbTask() {
        try {
            val s = AntOrchardRpcCall.orchardListTask()
            val jo = JSONObject(s)
            if (jo.getString("resultCode") == "100") {
                val jaTaskList = jo.getJSONArray("taskList")
                for (i in 0 until jaTaskList.length()) {
                    val jo2 = jaTaskList.getJSONObject(i)
                    if (jo2.getString("taskStatus") != "FINISHED") continue
                    val title = jo2.getJSONObject("taskDisplayConfig").getString("title")
                    val awardCount = jo2.optInt("awardCount", 0)
                    val taskId = jo2.getString("taskId")
                    val taskPlantType = jo2.getString("taskPlantType")
                    val jo3 = JSONObject(AntOrchardRpcCall.triggerTbTask(taskId, taskPlantType))
                    if (jo3.getString("resultCode") == "100") {
                        Log.farm("领取奖励🎖️[$title]#$awardCount g肥料")
                    } else {
                        Log.record(jo3.getString("resultDesc"))
                        Log.runtime(jo3.toString())
                    }
                }
            } else {
                Log.record(jo.getString("resultDesc"))
                Log.runtime(s)
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "triggerTbTask err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun querySubplotsActivity(taskRequire: Int) {
        try {
            val s = AntOrchardRpcCall.querySubplotsActivity(treeLevel!!)
            val jo = JSONObject(s)
            if (jo.getString("resultCode") == "100") {
                val subplotsActivityList = jo.getJSONArray("subplotsActivityList")
                for (i in 0 until subplotsActivityList.length()) {
                    val jo2 = subplotsActivityList.getJSONObject(i)
                    if (jo2.getString("activityType") != "WISH") continue
                    val activityId = jo2.getString("activityId")
                    if (jo2.getString("status") == "NOT_STARTED") {
                        val extend = jo2.getString("extend")
                        val jo3 = JSONObject(extend)
                        val wishActivityOptionList = jo3.getJSONArray("wishActivityOptionList")
                        var optionKey: String? = null
                        for (j in 0 until wishActivityOptionList.length()) {
                            val jo4 = wishActivityOptionList.getJSONObject(j)
                            if (taskRequire == jo4.getInt("taskRequire")) {
                                optionKey = jo4.getString("optionKey")
                                break
                            }
                        }
                        if (optionKey != null) {
                            val jo5 = JSONObject(AntOrchardRpcCall.triggerSubplotsActivity(activityId, "WISH", optionKey))
                            if (jo5.getString("resultCode") == "100") {
                                Log.farm("农场许愿✨[每日施肥$taskRequire 次]")
                            } else {
                                Log.record(jo5.getString("resultDesc"))
                                Log.runtime(jo5.toString())
                            }
                        }
                    } else if (jo2.getString("status") == "FINISHED") {
                        val jo3 = JSONObject(AntOrchardRpcCall.receiveOrchardRights(activityId, "WISH"))
                        if (jo3.getString("resultCode") == "100") {
                            Log.farm("许愿奖励✨[肥料${jo3.getInt("amount")}g]")
                            querySubplotsActivity(taskRequire)
                            return
                        } else {
                            Log.record(jo3.getString("resultDesc"))
                            Log.runtime(jo3.toString())
                        }
                    }
                }
            } else {
                Log.record(jo.getString("resultDesc"))
                Log.runtime(s)
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "triggerTbTask err:")
            Log.printStackTrace(TAG, t)
        }
    }

    // 助力
    private fun orchardassistFriend() {
        try {
            if (!Status.canAntOrchardAssistFriendToday()) {
                return
            }
            val friendSet = assistFriendList.value
            for (uid in friendSet) {
                val shareId = Base64.encodeToString(("$uid-${RandomUtil.getRandomInt(5)}ANTFARM_ORCHARD_SHARE_P2P").toByteArray(), Base64.NO_WRAP)
                val str = AntOrchardRpcCall.achieveBeShareP2P(shareId)
                val jsonObject = JSONObject(str)
                CoroutineUtils.sleepCompat(800)
                val name = UserMap.getMaskName(uid)
                if (!jsonObject.optBoolean("success")) {
                    val code = jsonObject.getString("code")
                    if (code == "600000027") {
                        Log.record(TAG, "农场助力💪今日助力他人次数上限")
                        Status.antOrchardAssistFriendToday()
                        return
                    }
                    Log.record(TAG, "农场助力😔失败[$name]${jsonObject.optString("desc")}")
                    continue
                }
                Log.farm("农场助力💪[助力:$name]")
            }
            Status.antOrchardAssistFriendToday()
        } catch (t: Throwable) {
            Log.runtime(TAG, "orchardassistFriend err:")
            Log.printStackTrace(TAG, t)
        }
    }
}
