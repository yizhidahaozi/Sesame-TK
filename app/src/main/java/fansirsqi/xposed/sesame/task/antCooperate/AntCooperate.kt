package fansirsqi.xposed.sesame.task.antCooperate

import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.data.StatusFlags
import fansirsqi.xposed.sesame.entity.CooperateEntity.Companion.getList
import fansirsqi.xposed.sesame.model.BaseModel
import fansirsqi.xposed.sesame.model.ModelFields
import fansirsqi.xposed.sesame.model.ModelGroup
import fansirsqi.xposed.sesame.model.modelFieldExt.BooleanModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.IntegerModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.SelectAndCountModelField
import fansirsqi.xposed.sesame.task.ModelTask
import fansirsqi.xposed.sesame.task.TaskCommon
import fansirsqi.xposed.sesame.util.GlobalThreadPools.sleepCompat
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ResChecker
import fansirsqi.xposed.sesame.util.TimeUtil
import fansirsqi.xposed.sesame.util.maps.CooperateMap
import fansirsqi.xposed.sesame.util.maps.UserMap
import org.json.JSONObject

class AntCooperate : ModelTask() {
    /**
     * 获取任务名称
     *
     * @return 合种任务名称
     */
    override fun getName(): String? {
        return "蚂蚁森林合种"
    }

    /**
     * 获取任务分组
     *
     * @return 森林分组
     */
    override fun getGroup(): ModelGroup {
        return ModelGroup.FOREST
    }

    /**
     * 获取任务图标
     *
     * @return 合种任务图标文件名
     */
    override fun getIcon(): String {
        return "AntCooperate.png"
    }

    private val cooperateWater = BooleanModelField("cooperateWater", "合种浇水|开启", false)
    private val cooperateWaterList = SelectAndCountModelField(
        "cooperateWaterList",
        "合种浇水列表",
        LinkedHashMap<String?, Int?>(),
        getList(),
        "打开上面的开关后执行一次后再重新回来应该能加载出来"
    )
    private val cooperateWaterTotalLimitList = SelectAndCountModelField(
        "cooperateWaterTotalLimitList",
        "浇水总量限制列表",
        LinkedHashMap<String?, Int?>(),
        getList(),
        "当浇满后理论不会再浇了"
    )
    private val cooperateSendCooperateBeckon = BooleanModelField("cooperateSendCooperateBeckon", "合种 | 召唤队友浇水| 仅队长 ", false)
    private val loveCooperateWater = BooleanModelField("loveCooperateWater", "真爱合种 | 浇水", false)
    private val loveCooperateWaterNum = IntegerModelField("loveCooperateWaterNum", "真爱合种 | 浇水克数(最低20)", 20)

    private val teamCooperateWaterNum = IntegerModelField("teamCooperateWaterNum", "组队合种 | 浇水克数(0为关闭，10-5000)", 0)


    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(cooperateWater)
        modelFields.addField(cooperateWaterList)
        modelFields.addField(cooperateWaterTotalLimitList)
        modelFields.addField(cooperateSendCooperateBeckon)
        // 添加真爱合种配置
        modelFields.addField(loveCooperateWater)
        modelFields.addField(loveCooperateWaterNum)
        modelFields.addField(teamCooperateWaterNum)
        return modelFields
    }

    /**
     * 检查任务是否可以执行
     *
     * @return 是否可以执行合种任务
     */
    override fun check(): Boolean? {
        if (TaskCommon.IS_ENERGY_TIME) {
            Log.record(TAG, "⏸ 当前为只收能量时间【" + BaseModel.energyTime.value + "】，停止执行" + name + "任务！")
            return false
        } else if (TaskCommon.IS_MODULE_SLEEP_TIME) {
            Log.record(TAG, "💤 模块休眠时间【" + BaseModel.modelSleepTime.value + "】停止执行" + name + "任务！")
            return false
        } else {
            return true
        }
    }

    /**
     * 执行合种任务的主要逻辑
     */
    override suspend fun runSuspend() {
        try {
            Log.record(TAG, "执行开始-$name")

            // 1. 真爱合种
            if (loveCooperateWater.value) {
                loveCooperateWater()
            }

            // 2. 组队合种
            if (teamCooperateWaterNum.value > 0) {
                teamCooperateWater()

            }
            // 3. 普通合种
            if (cooperateWater.value) {
                val queryUserCooperatePlantList = JSONObject(AntCooperateRpcCall.queryUserCooperatePlantList())
                if (ResChecker.checkRes(TAG, queryUserCooperatePlantList)) {
                    val userCurrentEnergy = queryUserCooperatePlantList.getInt("userCurrentEnergy")
                    val cooperatePlants = queryUserCooperatePlantList.getJSONArray("cooperatePlants")
                    Log.runtime(TAG, "获取合种列表成功:" + cooperatePlants.length() + "颗合种")
                    for (i in 0..<cooperatePlants.length()) {
                        var plant = cooperatePlants.getJSONObject(i)
                        val cooperationId = plant.getString("cooperationId")
                        if (!plant.has("name")) {
                            plant = JSONObject(AntCooperateRpcCall.queryCooperatePlant(cooperationId)).getJSONObject("cooperatePlant")
                        }
                        val admin = plant.getString("admin")
                        val name = plant.getString("name")
                        if (cooperateSendCooperateBeckon.value && UserMap.currentUid == admin) {
                            cooperateSendCooperateBeckon(cooperationId, name)
                        }
                        val waterDayLimit = plant.getInt("waterDayLimit")
                        val waterLimit = plant.getJSONObject("cooperateTemplate").getInt("waterLimit")
                        val watered = waterLimit - waterDayLimit
                        Log.runtime(TAG, "合种$name: 浇水信息:$waterDayLimit/$waterLimit")
                        CooperateMap.getInstance(CooperateMap::class.java).add(cooperationId, name)
                        if (!Status.canCooperateWaterToday(UserMap.currentUid, cooperationId)) {
                            Log.runtime(TAG, name + "今日已浇水[" + watered + "]g💦")
                            continue
                        }
                        var needWater = cooperateWaterList.value[cooperationId]
                        if (needWater != null) {
                            val limitNum = cooperateWaterTotalLimitList.value[cooperationId]
                            if (limitNum != null) {
                                val cumulativeWaterAmount: Int = calculatedWaterNum(cooperationId)
                                if (cumulativeWaterAmount < 0) {
                                    Log.runtime(TAG, "当前用户[" + UserMap.currentUid + "]的累计浇水能量获取失败,跳过本次浇水！")
                                    continue
                                }
                                needWater = limitNum - cumulativeWaterAmount
                                Log.runtime(TAG, "[$name] 调整后的浇水数量: $needWater")
                            }
                            if (needWater > waterDayLimit) {
                                needWater = waterDayLimit
                            }
                            if (needWater > userCurrentEnergy) {
                                needWater = userCurrentEnergy
                            }
                            if (needWater > 0) {
                                cooperateWater(cooperationId, needWater, name)
                            } else {
                                Log.runtime(TAG, "浇水数量为0，跳过[$name]")
                            }
                        } else {
                            Log.runtime(TAG, "浇水列表中没有为[$name]配置")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, t)
        } finally {
            CooperateMap.getInstance(CooperateMap::class.java).save(UserMap.currentUid)
            Log.record(TAG, "执行结束-$name")
        }
    }

    // 真爱合种逻辑
    private fun loveCooperateWater() {
        try {
            var myWatered: Int? = 0
            if (!Status.hasFlagToday("love::teamWater")) {
                Log.forest(TAG, "真爱合种今日已浇水(" + myWatered + "g)")
                return
            }
            val queryLoveHome = JSONObject(AntCooperateRpcCall.queryLoveHome())
            if (!ResChecker.checkRes(TAG, queryLoveHome)) {
                Log.error(TAG, "查询真爱合种首页失败")
                return
            } else {
                val teamInfo = queryLoveHome.optJSONObject("teamInfo")
                if (teamInfo == null) {
                    Log.error(TAG, "未解析到真爱合种队伍信息，可能是结构变更")
                    return
                }
                val teamId = teamInfo.optString("teamId")
                val teamStatus = teamInfo.optString("teamStatus")
                // 通过 waterInfo -> todayWaterMap 查看当前用户今日是否已浇水
                val waterInfo = teamInfo.optJSONObject("waterInfo")
                val todayWaterMap = waterInfo?.optJSONObject("todayWaterMap")
                val currentUid = UserMap.currentUid
                myWatered = todayWaterMap?.optInt(currentUid, 0)
                if (myWatered != null) {
                    if (myWatered > 0) {
                        Log.forest(TAG, "真爱合种今日已浇水(" + myWatered + "g)")
                    }
                } else {
                    Log.error(TAG, "真爱合不知道什么勾八错误")
                }
                if ("ACTIVATED" == teamStatus && !teamId.isEmpty()) {
                    val waterNum = loveCooperateWaterNum.value
                    val waterJo = JSONObject(AntCooperateRpcCall.loveTeamWater(teamId, waterNum))
                    if (!ResChecker.checkRes(TAG, waterJo)) {
                        Log.error(TAG, "真爱合种浇水失败: " + waterJo.optString("resultDesc"))
                    } else {
                        Log.forest("真爱合种💖[浇水成功]#" + waterNum + "g")
                        Status.setFlagToday("love::teamWater")
                    }
                } else {
                    Log.error(TAG, "真爱合种队伍状态不可用或ID为空: $teamStatus")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "loveCooperateWater err:", t)
        }
    }
    // 组队合种浇水逻辑
    /**
     * 组队合种：自动浇水逻辑
     */
    private fun teamCooperateWater() {
        try {
            // 1. 用户自定义浇水数量限制（强制区间）
            var configNum = teamCooperateWaterNum.value ?: 10  // null 时默认 10
            configNum = configNum.coerceIn(10, 5000)

            // 2. 今日执行总量是否达到用户设置上限
            val todayUsed = Status.getIntFlagToday(StatusFlags.FLAG_TEAM_WATER_DAILY_COUNT) ?: 0
            if (todayUsed >= configNum) {
                Log.record(TAG, "组队合种今日已达用户设置上限，跳过")
                return
            }



            // 4. 查询首页，用来获取 teamId + 当前能量
            val homePageStr = AntCooperateRpcCall.queryHomePage()
            val homeJo = JSONObject(homePageStr)
            if (!ResChecker.checkRes(TAG, homeJo)) {
                Log.record(TAG, "queryHomePage 返回异常")
                return
            }

            val teamId = homeJo
                .optJSONObject("teamHomeResult")
                ?.optJSONObject("teamBaseInfo")
                ?.optString("teamId")
                ?.takeIf { it.isNotBlank() }

            if (teamId == null) {
                Log.record(TAG, "未获取到组队合种 TeamID")
                return
            }
            // 3. 查询可浇水次数
            val miscInfoStr = AntCooperateRpcCall.queryMiscInfo("teamCanWaterCount",teamId)
            val miscJo = JSONObject(miscInfoStr)
            if (!ResChecker.checkRes(TAG, miscJo)) {
                Log.record(TAG, "queryMiscInfo 查询失败")
                return
            }
            val teamCanWaterCount = miscJo
                .optJSONObject("combineHandlerVOMap")
                ?.optJSONObject("teamCanWaterCount")

            val dailyWaterLimit = teamCanWaterCount?.optInt("dailyWaterLimit", 0) ?: 0
            val waterCount = teamCanWaterCount?.optInt("waterCount", 0) ?: 0

            Log.record(TAG, "浇水限制：每日上限=$dailyWaterLimit，可浇=$waterCount g")

            if (waterCount <= 0) {
                Log.record(TAG, "当前无可浇水次数，跳过")
                return
            }

            // 当前能量（保证非空）
            val currentEnergy = homeJo
                .optJSONObject("userBaseInfo")
                ?.optInt("currentEnergy")
                ?: 0

            // 5. 能量不足10 → 不浇水
            if (currentEnergy < 10) {
                Log.record(TAG, "能量不足（$currentEnergy g），低于 10g，本次不执行浇水")
                return
            }

            // 6. 如果能量不足以满足配置上限 → 使用当前能量
            if (currentEnergy < configNum) {
                Log.record(TAG, "能量不足：需要 $configNum g，本次只浇 $currentEnergy g")
                configNum = currentEnergy
            }

            //Log.record(TAG, "准备浇水：TeamID=$teamId  本次=$configNum g")

            // 7. 调用浇水 RPC
            val waterResStr = AntCooperateRpcCall.teamWater(teamId, configNum)
            if (waterResStr == null) {
                Log.record(TAG, "teamWater 调用失败(null)")
                return
            }

            val waterJo = JSONObject(waterResStr)
            if (ResChecker.checkRes(TAG, waterJo)) {
                Log.forest("组队合种🌲[浇水成功] #${configNum} g")

                // 更新今日累计值
                val newTotal = todayUsed + configNum
                Status.setIntFlagToday(StatusFlags.FLAG_TEAM_WATER_DAILY_COUNT, newTotal)

                Log.record(TAG, "今日累计浇水: $newTotal g")
            }

        } catch (t: Throwable) {
            Log.runtime(TAG, "teamCooperateWater err:")
            Log.printStackTrace(TAG, t)
        }
    }

    companion object {
        private val TAG: String = AntCooperate::class.java.getSimpleName()

        private fun cooperateWater(coopId: String, count: Int, name: String) {
            try {
                val s = AntCooperateRpcCall.cooperateWater(UserMap.currentUid, coopId, count)
                val jo = JSONObject(s)
                if (ResChecker.checkRes(TAG, jo)) {
                    Log.forest("合种浇水🚿[" + name + "]" + jo.getString("barrageText"))
                    Status.cooperateWaterToday(UserMap.currentUid, coopId)
                } else {
                    Log.runtime(TAG, "浇水失败[" + name + "]: " + jo.getString("resultDesc"))
                }
            } catch (t: Throwable) {
                Log.runtime(TAG, "cooperateWater err:")
                Log.printStackTrace(TAG, t)
            } finally {
                sleepCompat(1500)
            }
        }

        private fun calculatedWaterNum(coopId: String?): Int {
            try {
                val s = AntCooperateRpcCall.queryCooperateRank("A", coopId)
                val jo = JSONObject(s)
                if (jo.optBoolean("success", false)) {
                    val jaList = jo.getJSONArray("cooperateRankInfos")
                    for (i in 0..<jaList.length()) {
                        val joItem = jaList.getJSONObject(i)
                        val userId = joItem.getString("userId")
                        if (userId == UserMap.currentUid) {
                            // 未获取到累计浇水量 返回 -1 不执行浇水
                            val energySummation = joItem.optInt("energySummation", -1)
                            if (energySummation >= 0) {
                                Log.runtime(TAG, "当前用户[$userId]的累计浇水能量: $energySummation")
                            }
                            return energySummation
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, t)
            }
            return -1 // 未获取到累计浇水量，停止浇水
        }

        /**
         * 召唤队友浇水（仅队长）
         */
        private fun cooperateSendCooperateBeckon(cooperationId: String, name: String) {
            try {
                if (TimeUtil.isNowBeforeTimeStr("1800")) {
                    return
                }
                var jo = JSONObject(AntCooperateRpcCall.queryCooperateRank("D", cooperationId))
                if (ResChecker.checkRes(TAG, jo)) {
                    val cooperateRankInfos = jo.getJSONArray("cooperateRankInfos")
                    for (i in 0..<cooperateRankInfos.length()) {
                        val rankInfo = cooperateRankInfos.getJSONObject(i)
                        if (rankInfo.getBoolean("canBeckon")) {
                            jo = JSONObject(AntCooperateRpcCall.sendCooperateBeckon(rankInfo.getString("userId"), cooperationId))
                            if (ResChecker.checkRes(TAG, jo)) {
                                Log.forest("合种🚿[" + name + "]#召唤队友[" + rankInfo.getString("displayName") + "]成功")
                            }
                            TimeUtil.sleepCompat(300)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "召唤队友和种错误：", t)
            }
        }
    }
}