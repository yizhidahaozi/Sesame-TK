package fansirsqi.xposed.sesame.task.antCooperate

import fansirsqi.xposed.sesame.data.Status
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
    public override fun getName(): String? {
        return "合种"
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
    private val cooperateWaterList = SelectAndCountModelField("cooperateWaterList", "合种浇水列表", LinkedHashMap<String?, Int?>(), getList(), "开启合种浇水后执行一次重载")
    private val cooperateWaterTotalLimitList = SelectAndCountModelField("cooperateWaterTotalLimitList", "浇水总量限制列表", LinkedHashMap<String?, Int?>(), getList())
    private val cooperateSendCooperateBeckon = BooleanModelField("cooperateSendCooperateBeckon", "合种 | 召唤队友浇水| 仅队长 ", false)
    private val loveCooperateWater = BooleanModelField("loveCooperateWater", "真爱合种 | 浇水", false)
    private val loveCooperateWaterNum = IntegerModelField("loveCooperateWaterNum", "真爱合种 | 浇水克数(最低20)", 20)

    public override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(cooperateWater)
        modelFields.addField(cooperateWaterList)
        modelFields.addField(cooperateWaterTotalLimitList)
        modelFields.addField(cooperateSendCooperateBeckon)
        // 添加真爱合种配置
        modelFields.addField(loveCooperateWater)
        modelFields.addField(loveCooperateWaterNum)
        return modelFields
    }

    /**
     * 检查任务是否可以执行
     *
     * @return 是否可以执行合种任务
     */
    public override fun check(): Boolean? {
        if (TaskCommon.IS_ENERGY_TIME) {
            Log.record(TAG, "⏸ 当前为只收能量时间【" + BaseModel.getEnergyTime().getValue() + "】，停止执行" + getName() + "任务！")
            return false
        } else if (TaskCommon.IS_MODULE_SLEEP_TIME) {
            Log.record(TAG, "💤 模块休眠时间【" + BaseModel.getModelSleepTime().getValue() + "】停止执行" + getName() + "任务！")
            return false
        } else {
            return true
        }
    }

    /**
     * 执行合种任务的主要逻辑
     */
    override fun runJava() {
        try {
            Log.record(TAG, "执行开始-" + getName())

            // 1. 真爱合种
            if (loveCooperateWater.getValue()) {
                loveCooperateWater()
            }

            // 2. 普通合种
            if (cooperateWater.getValue()) {
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
                        if (cooperateSendCooperateBeckon.getValue() && UserMap.getCurrentUid() == admin) {
                            cooperateSendCooperateBeckon(cooperationId, name)
                        }
                        val waterDayLimit = plant.getInt("waterDayLimit")
                        val waterLimit = plant.getJSONObject("cooperateTemplate").getInt("waterLimit")
                        val watered = waterLimit - waterDayLimit
                        Log.runtime(TAG, "合种" + name + ": 浇水信息:" + waterDayLimit + "/" + waterLimit)
                        CooperateMap.getInstance<CooperateMap?>(CooperateMap::class.java).add(cooperationId, name)
                        if (!Status.canCooperateWaterToday(UserMap.getCurrentUid(), cooperationId)) {
                            Log.runtime(TAG, name + "今日已浇水[" + watered + "]g💦")
                            continue
                        }
                        var needWater = cooperateWaterList.getValue().get(cooperationId)
                        if (needWater != null) {
                            val limitNum = cooperateWaterTotalLimitList.getValue().get(cooperationId)
                            if (limitNum != null) {
                                val cumulativeWaterAmount: Int = calculatedWaterNum(cooperationId)
                                if (cumulativeWaterAmount < 0) {
                                    Log.runtime(TAG, "当前用户[" + UserMap.getCurrentUid() + "]的累计浇水能量获取失败,跳过本次浇水！")
                                    continue
                                }
                                needWater = limitNum - cumulativeWaterAmount
                                Log.runtime(TAG, "[" + name + "] 调整后的浇水数量: " + needWater)
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
                                Log.runtime(TAG, "浇水数量为0，跳过[" + name + "]")
                            }
                        } else {
                            Log.runtime(TAG, "浇水列表中没有为[" + name + "]配置")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, t)
        } finally {
            CooperateMap.getInstance<CooperateMap?>(CooperateMap::class.java).save(UserMap.getCurrentUid())
            Log.record(TAG, "执行结束-" + getName())
        }
    }

    // 真爱合种逻辑
    private fun loveCooperateWater() {
        try {
            var jo: JSONObject? = JSONObject(AntCooperateRpcCall.queryLoveHome())
            if (ResChecker.checkRes(TAG, jo)) {
                var teamInfo = jo!!.optJSONObject("teamInfo")
                if (teamInfo == null) {
                    val result = jo.optJSONObject("result")
                    if (result != null) jo = result
                    val teamHomeResult = jo.optJSONObject("teamHomeResult")
                    if (teamHomeResult != null) {
                        teamInfo = teamHomeResult.optJSONObject("teamInfo")
                    }
                }
                if (teamInfo == null) {
                    Log.record(TAG, "未解析到真爱合种队伍信息，可能是结构变更")
                    return
                }

                val teamId = teamInfo.optString("teamId")
                val teamStatus = teamInfo.optString("teamStatus")

                // 通过 waterInfo -> todayWaterMap 查看当前用户今日是否已浇水
                val waterInfo = teamInfo.optJSONObject("waterInfo")
                if (waterInfo != null) {
                    val todayWaterMap = waterInfo.optJSONObject("todayWaterMap")
                    val currentUid = UserMap.getCurrentUid()
                    if (todayWaterMap != null && todayWaterMap.has(currentUid)) {
                        val myWatered = todayWaterMap.optInt(currentUid, 0)
                        if (myWatered > 0) {
                            Log.record(TAG, "真爱合种今日已浇水(" + myWatered + "g)，任务跳过")
                            return
                        }
                    }
                }

                if ("ACTIVATED" == teamStatus && !teamId.isEmpty()) {
                    var waterNum = loveCooperateWaterNum.getValue()
                    if (waterNum < 20) {
                        waterNum = 20
                        Log.record(TAG, "真爱合种浇水数值修正为最低20g")
                    }

                    Log.record(TAG, "真爱合种开始浇水，目标ID: " + teamId + ", 数量: " + waterNum)
                    val waterRes = AntCooperateRpcCall.loveTeamWater(teamId, waterNum)
                    val waterJo = JSONObject(waterRes)
                    if (ResChecker.checkRes(TAG, waterJo)) {
                        Log.forest("真爱合种💖[浇水成功]#" + waterNum + "g")
                    } else {
                        Log.record(TAG, "真爱合种浇水失败: " + waterJo.optString("resultDesc"))
                    }
                } else {
                    Log.record(TAG, "真爱合种队伍状态不可用或ID为空: " + teamStatus)
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "loveCooperateWater err:", t)
        }
    }

    companion object {
        private val TAG: String = AntCooperate::class.java.getSimpleName()
        private const val num = 0
        private const val limitNum = 0

        private fun cooperateWater(coopId: String?, count: Int, name: String?) {
            try {
                val s = AntCooperateRpcCall.cooperateWater(UserMap.getCurrentUid(), coopId, count)
                val jo = JSONObject(s)
                if (ResChecker.checkRes(TAG, jo)) {
                    Log.forest("合种浇水🚿[" + name + "]" + jo.getString("barrageText"))
                    Status.cooperateWaterToday(UserMap.getCurrentUid(), coopId)
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
                        if (userId == UserMap.getCurrentUid()) {
                            // 未获取到累计浇水量 返回 -1 不执行浇水
                            val energySummation = joItem.optInt("energySummation", -1)
                            if (energySummation >= 0) {
                                Log.runtime(TAG, "当前用户[" + userId + "]的累计浇水能量: " + energySummation)
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
        private fun cooperateSendCooperateBeckon(cooperationId: String?, name: String?) {
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