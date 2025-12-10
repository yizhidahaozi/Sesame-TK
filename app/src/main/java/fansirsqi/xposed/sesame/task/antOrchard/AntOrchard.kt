package fansirsqi.xposed.sesame.task.antOrchard

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
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.Notify
import fansirsqi.xposed.sesame.util.RandomUtil
import fansirsqi.xposed.sesame.util.maps.UserMap
import org.json.JSONObject

class AntOrchard : ModelTask() {
    companion object {
        private val TAG = AntOrchard::class.java.simpleName

        // 任务黑名单：某些广告/外跳类任务后端不支持 finishTask 或需要前端行为配合
        private val ORCHARD_TASK_BLACKLIST = setOf(
            "70000",                        // 逛好物最高得1500肥料（XLIGHT）
            "ORCHARD_NORMAL_KUAISHOU_MAX",  // 逛一逛快手
            "ORCHARD_NORMAL_DIAOYU1",       // 钓鱼1次
            "ZHUFANG3IN1",                  // 添加农场小组件并访问
            "12172",                        // 逛助农好货得肥料
            "TOUTIAO"                       // 逛一逛今日头条
        )
    }

    private var userId: String? = UserMap.currentUid
    private var treeLevel: String? = null
    private var wuaList: Array<String>? = null
    private var executeIntervalInt: Int = 0

    private lateinit var executeInterval: IntegerModelField
    private lateinit var receiveOrchardTaskAward: BooleanModelField
    private lateinit var orchardSpreadManureCount: IntegerModelField
    private lateinit var assistFriendList: SelectModelField

    override fun getName(): String = "农场"

    override fun getGroup(): ModelGroup = ModelGroup.ORCHARD

    override fun getIcon(): String = "AntOrchard.png"

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(
            IntegerModelField("executeInterval", "执行间隔(毫秒)", 500).also { executeInterval = it }
        )
        modelFields.addField(
            BooleanModelField("receiveOrchardTaskAward", "收取农场任务奖励", false).also { receiveOrchardTaskAward = it }
        )
        modelFields.addField(
            IntegerModelField("orchardSpreadManureCount", "农场每日施肥次数", 0).also { orchardSpreadManureCount = it }
        )
        /*
        * modelFields.addField(
            SelectModelField("assistFriendList", "助力好友列表", LinkedHashSet(), AlipayUser::getList).also { assistFriendList = it }
        )
        * */
        return modelFields
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
            Log.record(TAG, "执行开始-$name")
            executeIntervalInt = maxOf(executeInterval.value, 500)

            val indexResponse = AntOrchardRpcCall.orchardIndex()
            val indexJson = JSONObject(indexResponse)

            if (indexJson.optString("resultCode") != "100") {
                Log.runtime(TAG, indexJson.optString("resultDesc", "orchardIndex 调用失败"))
                return
            }

            if (!indexJson.optBoolean("userOpenOrchard", false)) {
                enableField.value = false
                Log.other("请先开启芭芭农场！")
                return
            }

            val taobaoData = JSONObject(indexJson.getString("taobaoData"))
            treeLevel = taobaoData.getJSONObject("gameInfo")
                .getJSONObject("plantInfo")
                .getJSONObject("seedStage")
                .getInt("stageLevel")
                .toString()



            if (userId == null) {
                userId= UserMap.currentUid
            }

            // 七日礼包
            if (indexJson.has("lotteryPlusInfo")) {
                drawLotteryPlus(indexJson.getJSONObject("lotteryPlusInfo"))
            }

            // 每日肥料
            extraInfoGet()

            // 农场任务
            if (receiveOrchardTaskAward.value) {
                doOrchardDailyTask(userId!!)
                triggerTbTask()
            }

            // 施肥
            val orchardSpreadManureCountValue = orchardSpreadManureCount.value
            if (orchardSpreadManureCountValue > 0 && Status.canSpreadManureToday(userId!!)) {
                CoroutineUtils.sleepCompat(200)
                orchardSpreadManure()
            }

            // 许愿
            if (orchardSpreadManureCountValue in 3..<10) {
                querySubplotsActivity(3)
            } else if (orchardSpreadManureCountValue >= 10) {
                querySubplotsActivity(10)
            }

            // 助力
            //orchardAssistFriend()

        } catch (t: Throwable) {
            Log.runtime(TAG, "start.run err:")
            Log.printStackTrace(TAG, t)
        } finally {
            Log.record(TAG, "执行结束-$name")
        }
    }

    private fun getWua(): String {
        if (wuaList == null) {
            try {
                val content = Files.readFromFile(Files.getWuaFile())
                wuaList = content.split("\n").toTypedArray()
            } catch (ignored: Throwable) {
                wuaList = emptyArray()
            }
        }
        return if (wuaList!!.isNotEmpty()) {
            wuaList!![RandomUtil.nextInt(0, wuaList!!.size - 1)]
        } else {
            Detector.genWua()
        }
    }

    private fun canSpreadManureContinue(stageBefore: Int, stageAfter: Int): Boolean {
        return if (stageAfter - stageBefore > 1) {
            true
        } else {
            Log.record(TAG, "施肥只加0.01%进度今日停止施肥！")
            false
        }
    }

    private suspend fun orchardSpreadManure() {
        try {
            var count = 0
            do {
                try {
                    val orchardIndexData = JSONObject(AntOrchardRpcCall.orchardIndex())
                    if (orchardIndexData.getString("resultCode") != "100") {
                        Log.runtime(TAG, orchardIndexData.getString("resultDesc"))
                        return
                    }

                    // 丰收礼包
                    if (orchardIndexData.has("spreadManureActivity")) {
                        val spreadManureStage = orchardIndexData.getJSONObject("spreadManureActivity")
                            .getJSONObject("spreadManureStage")
                        if (spreadManureStage.getString("status") == "FINISHED") {
                            val sceneCode = spreadManureStage.getString("sceneCode")
                            val taskType = spreadManureStage.getString("taskType")
                            val awardCount = spreadManureStage.getInt("awardCount")
                            val joo = JSONObject(AntOrchardRpcCall.receiveTaskAward(sceneCode, taskType))
                            if (joo.optBoolean("success")) {
                                Log.forest(TAG,"丰收礼包🎁[肥料*$awardCount]")
                            } else {
                                Log.record(TAG,"农场 丰收礼包 错误："+joo.getString("desc"))
                                Log.runtime(TAG,"农场 丰收礼包 错误："+joo.toString())
                            }
                        }
                    }

                    val orchardTaobaoData = JSONObject(orchardIndexData.getString("taobaoData"))
                    val plantInfo = orchardTaobaoData.getJSONObject("gameInfo").getJSONObject("plantInfo")
                    val canExchange = plantInfo.getBoolean("canExchange")

                    if (canExchange) {
                        Log.forest("🎉 农场果树似乎可以兑换了！")
                        Notify.sendNewNotification("发生什么事了？", "芝麻粒TK提醒您：\n 🎉 农场果树似乎可以兑换了！")
                        return
                    }

                    val seedStage = plantInfo.getJSONObject("seedStage")
                    treeLevel = seedStage.getInt("stageLevel").toString()

                    val accountInfo = orchardTaobaoData.getJSONObject("gameInfo").getJSONObject("accountInfo")
                    val happyPoint = accountInfo.getString("happyPoint").toInt()
                    val wateringCost = accountInfo.getInt("wateringCost")
                    val wateringLeftTimes = accountInfo.getInt("wateringLeftTimes")

                    if (count > 20) {
                        Log.runtime(TAG,"一次浇水不超过 $count 次避免任务时间过长")
                        return
                    }

                    if (happyPoint < wateringCost) {
                        Log.runtime(TAG,"农场肥料不足以施肥 $wateringCost")
                        return
                    }

                    if (wateringLeftTimes == 0) {
                        Log.runtime(TAG,"剩余施肥次数为 0")
                        return
                    }

                    if (200 - wateringLeftTimes < orchardSpreadManureCount.value) {
                        val wua = getWua()
                        Log.runtime(TAG,"set Wua $wua")
                        val spreadManureData = JSONObject(AntOrchardRpcCall.orchardSpreadManure(wua,"ch_appcenter__chsub_9patch"))

                        if (spreadManureData.getString("resultCode") != "100") {
                            Log.record(TAG,"农场 orchardSpreadManure 错误："+spreadManureData.getString("resultDesc"))
                            Log.runtime(TAG,"农场 orchardSpreadManure 错误："+spreadManureData.toString())
                            return
                        }

                        val spreadTaobaoData = JSONObject(spreadManureData.getString("taobaoData"))
                        val stageText = spreadTaobaoData.getJSONObject("currentStage").getString("stageText")
                        val dailyAppWateringCount = spreadTaobaoData.getJSONObject("statistics").getInt("dailyAppWateringCount")

                        Log.forest("今日农场已施肥💩 $dailyAppWateringCount 次 [$stageText]")
                        count++

                        if (!canSpreadManureContinue(
                                seedStage.getInt("totalValue"),
                                spreadTaobaoData.getJSONObject("currentStage").getInt("totalValue")
                            )) {
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
            Log.runtime(TAG, "orchardSpreadManure err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun extraInfoGet() {
        try {
            val response = AntOrchardRpcCall.extraInfoGet()
            val jo = JSONObject(response)

            if (jo.getString("resultCode") == "100") {
                val fertilizerPacket = jo.getJSONObject("data")
                    .getJSONObject("extraData")
                    .getJSONObject("fertilizerPacket")

                if (fertilizerPacket.getString("status") != "todayFertilizerWaitTake") return

                val todayFertilizerNum = fertilizerPacket.getInt("todayFertilizerNum")
                val setResponse = JSONObject(AntOrchardRpcCall.extraInfoSet())

                if (setResponse.getString("resultCode") == "100") {
                    Log.forest(TAG,"每日肥料💩[${todayFertilizerNum}g]")
                } else {
                    Log.runtime(TAG,setResponse.toString())
                }
            } else {
                Log.runtime(TAG,jo.toString())
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "extraInfoGet err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun drawLotteryPlus(lotteryPlusInfo: JSONObject) {
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
                            val userEverydayGiftItems = jo3.getJSONObject("lotteryPlusInfo")
                                .getJSONObject("userSevenDaysGiftsItem")
                                .getJSONArray("userEverydayGiftItems")

                            for (j in 0 until userEverydayGiftItems.length()) {
                                val jo4 = userEverydayGiftItems.getJSONObject(j)
                                if (jo4.getString("itemId") == itemId) {
                                    val awardCount = jo4.optInt("awardCount", 1)
                                    Log.forest(TAG,"七日礼包🎁[获得肥料]#${awardCount}g")
                                    break
                                }
                            }
                        } else {
                            Log.runtime(TAG,jo3.toString())
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

    private suspend fun doOrchardDailyTask(userId: String) {
        try {
            val response = AntOrchardRpcCall.orchardListTask()
            val responseJson = JSONObject(response)

            if (responseJson.optString("resultCode") != "100") {
                Log.record("doOrchardDailyTask响应异常", response)
                Log.runtime("doOrchardDailyTask响应异常", response)
                return
            }

            // team 模式：inTeam = true 表示已经开启合种/帮帮种
            val inTeam = responseJson.optBoolean("inTeam", false)
            Log.record(TAG, if (inTeam) "当前为农场 team 模式（合种/帮帮种已开启）" else "当前为普通单人农场模式")

            // 签到任务
            if (responseJson.has("signTaskInfo")) {
                val signTaskInfo = responseJson.getJSONObject("signTaskInfo")
                orchardSign(signTaskInfo)
            }

            val taskList = responseJson.getJSONArray("taskList")
            for (i in 0 until taskList.length()) {
                val task = taskList.getJSONObject(i)

                // 只处理 TODO 状态的任务
                if (task.optString("taskStatus") != "TODO") continue

                val actionType = task.optString("actionType")
                val sceneCode = task.optString("sceneCode")
                val taskId = task.optString("taskId")
                val groupId = task.optString("groupId")

                // 任务标题
                val title = if (task.has("taskDisplayConfig")) {
                    task.getJSONObject("taskDisplayConfig").optString("title", "未知任务")
                } else {
                    "未知任务"
                }

                // 黑名单任务：后端不支持 finishTask 或需要端内实际跳转
                if (ORCHARD_TASK_BLACKLIST.contains(groupId)) {
                    Log.record(TAG, "跳过黑名单任务[$title] groupId=$groupId")
                    continue
                }

                // 广告类任务：VISIT / XLIGHT
                if (actionType == "VISIT" || actionType == "XLIGHT") {
                    var rightsTimes = task.optInt("rightsTimes", 0)
                    var rightsTimesLimit = task.optInt("rightsTimesLimit", 0)

                    // 有些任务把次数放在 extend.rightsTimesLimit（字符串）里
                    val extend = task.optJSONObject("extend")
                    if (extend != null && rightsTimesLimit <= 0) {
                        val limitStr = extend.optString("rightsTimesLimit", "")
                        if (limitStr.isNotEmpty()) {
                            try {
                                rightsTimesLimit = limitStr.toInt()
                            } catch (ignored: Throwable) {}
                        }
                    }

                    // 控制执行次数
                    val timesToDo = if (rightsTimesLimit > 0) {
                        val remaining = rightsTimesLimit - rightsTimes
                        if (remaining <= 0) continue else remaining
                    } else {
                        1
                    }

                    for (cnt in 0 until timesToDo) {
                        val finishResponse = JSONObject(AntOrchardRpcCall.finishTask(userId, sceneCode, taskId))
                        if (finishResponse.optBoolean("success")) {
                            Log.forest(TAG, "农场广告任务📺[$title] 第${rightsTimes + cnt + 1}次")
                        } else {
                            Log.record(TAG, "失败：农场广告任务📺[$title] 第${rightsTimes + cnt + 1}次${finishResponse.optString("desc")}")
                            Log.runtime(TAG, "失败：农场广告任务📺[$title] 第${rightsTimes + cnt + 1}次${finishResponse}")
                            break
                        }
                        CoroutineUtils.sleepCompat(executeIntervalInt.toLong())
                    }
                    continue
                }

                // 非广告类的普通任务
                if (actionType == "TRIGGER" || actionType == "ADD_HOME" || actionType == "PUSH_SUBSCRIBE") {
                    val finishResponse = JSONObject(AntOrchardRpcCall.finishTask(userId, sceneCode, taskId))
                    if (finishResponse.optBoolean("success")) {
                        Log.forest(TAG,"农场任务🧾[$title]")
                    } else {
                        Log.record(TAG,"农场任务🧾[$title]${finishResponse.optString("desc")}")
                        Log.runtime(TAG,"农场任务🧾[$title]$finishResponse")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "doOrchardDailyTask 错误:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun orchardSign(signTaskInfo: JSONObject) {
        try {
            val currentSignItem = signTaskInfo.getJSONObject("currentSignItem")
            if (!currentSignItem.getBoolean("signed")) {
                val joSign = JSONObject(AntOrchardRpcCall.orchardSign())
                if (joSign.getString("resultCode") == "100") {
                    val awardCount = joSign.getJSONObject("signTaskInfo")
                        .getJSONObject("currentSignItem")
                        .getInt("awardCount")
                    Log.forest("农场签到📅[获得肥料]#${awardCount}g")
                } else {
                    Log.runtime(TAG,joSign.toString())
                }
            } else {
                Log.record(TAG, "农场今日已签到")
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "orchardSign err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun triggerTbTask() {
        try {
            val response = AntOrchardRpcCall.orchardListTask()
            val jo = JSONObject(response)

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
                        Log.forest(TAG,"领取奖励🎖️[$title]#${awardCount}g肥料")
                    } else {
                        Log.record(TAG,jo3.getString("resultDesc"))
                        Log.runtime(TAG,jo3.toString())
                    }
                }
            } else {
                Log.record(TAG,jo.getString("resultDesc"))
                Log.runtime(TAG,response)
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "triggerTbTask err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun querySubplotsActivity(taskRequire: Int) {
        try {
            val response = AntOrchardRpcCall.querySubplotsActivity(treeLevel!!)
            val jo = JSONObject(response)

            if (jo.getString("resultCode") == "100") {
                val subplotsActivityList = jo.getJSONArray("subplotsActivityList")
                for (i in 0 until subplotsActivityList.length()) {
                    val jo2 = subplotsActivityList.getJSONObject(i)
                    if (jo2.getString("activityType") != "WISH") continue

                    val activityId = jo2.getString("activityId")
                    when (jo2.getString("status")) {
                        "NOT_STARTED" -> {
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
                                    Log.farm(TAG,"农场许愿✨[每日施肥$taskRequire 次]")
                                } else {
                                    Log.record(TAG,jo5.getString("resultDesc"))
                                    Log.runtime(TAG,jo5.toString())
                                }
                            }
                        }
                        "FINISHED" -> {
                            val jo3 = JSONObject(AntOrchardRpcCall.receiveOrchardRights(activityId, "WISH"))
                            if (jo3.getString("resultCode") == "100") {
                                Log.farm("许愿奖励✨[肥料${jo3.getInt("amount")}g]")
                                querySubplotsActivity(taskRequire)
                                return
                            } else {
                                Log.record(TAG,jo3.getString("resultDesc"))
                                Log.runtime(TAG,jo3.toString())
                            }
                        }
                    }
                }
            } else {
                Log.record(TAG,jo.getString("resultDesc"))
                Log.runtime(TAG,response)
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "querySubplotsActivity err:")
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun orchardAssistFriend() {
        try {
            if (!Status.canAntOrchardAssistFriendToday()) {
                Log.record(TAG, "今日已助力，跳过农场助力")
                return
            }

            val friendSet = assistFriendList.value
            for (uid in friendSet) {
                val shareId = Base64.encodeToString(
                    ("$uid-${RandomUtil.getRandomInt(5)}ANTFARM_ORCHARD_SHARE_P2P").toByteArray(),
                    Base64.NO_WRAP
                )
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
            Log.runtime(TAG, "orchardAssistFriend err:")
            Log.printStackTrace(TAG, t)
        }
    }
}