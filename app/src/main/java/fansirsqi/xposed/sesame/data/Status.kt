package fansirsqi.xposed.sesame.data

import com.fasterxml.jackson.databind.JsonMappingException
import fansirsqi.xposed.sesame.model.modelFieldExt.IntegerModelField
import fansirsqi.xposed.sesame.task.ModelTask
import fansirsqi.xposed.sesame.task.antForest.AntForest
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.JsonUtil
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.StringUtil
import fansirsqi.xposed.sesame.util.TimeUtil
import fansirsqi.xposed.sesame.util.maps.UserMap
import lombok.Data
import lombok.Getter
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.Objects

@Data
class Status {
    // ===========================forest
    private val waterFriendLogList: MutableMap<String?, Int?> = HashMap<String?, Int?>()

    private val cooperateWaterList: MutableSet<String?> = HashSet<String?>() //合作浇水
    private val reserveLogList: MutableMap<String?, Int?> = HashMap<String?, Int?>()
    private val ancientTreeCityCodeList: MutableSet<String?> = HashSet<String?>() //古树
    private val protectBubbleList: MutableSet<String?> = HashSet<String?>()
    private val exchangeDoubleCard = 0 // 活力值兑换双倍卡
    private val exchangeTimes = 0
    private val exchangeTimesLongTime = 0
    private val doubleTimes = 0
    private val exchangeEnergyShield = false //活力值兑换能量保护罩
    private val exchangeCollectHistoryAnimal7Days = false
    private val exchangeCollectToFriendTimes7Days = false
    private val youthPrivilege = true
    private val studentTask = true
    private val VitalityStoreList: MutableMap<String?, Int?> = HashMap<String?, Int?>()

    // ===========================farm
    private var answerQuestion = false
    private val feedFriendLogList: MutableMap<String?, Int?> = HashMap<String?, Int?>()
    private val visitFriendLogList: MutableMap<String?, Int?> = HashMap<String?, Int?>()

    // 可以存各种今日计数（步数、次数等）
    private val intFlagMap: MutableMap<String?, Int?> = HashMap<String?, Int?>() //2025/12/4  GSMT  用来存储int类型数据，无需再重复定义

    private var dailyAnswerList: MutableSet<String?>? = HashSet<String?>()
    private val donationEggList: MutableSet<String?> = HashSet<String?>()
    private var useAccelerateToolCount = 0

    /**
     * 小鸡换装
     */
    private var canOrnament = true
    private val animalSleep = false

    // =============================stall
    private val stallHelpedCountLogList: MutableMap<String?, Int?> = HashMap<String?, Int?>()
    private val spreadManureList: MutableSet<String?> = HashSet<String?>()
    private val stallP2PHelpedList: MutableSet<String?> = HashSet<String?>()
    private var canStallDonate = true

    // ==========================sport
    private val syncStepList: MutableSet<String?> = HashSet<String?>()
    private val exchangeList: MutableSet<String?> = HashSet<String?>()

    /**
     * 捐运动币
     */
    private var donateCharityCoin = false

    // =======================other
    private val memberSignInList: MutableSet<String?> = HashSet<String?>()
    private val flagList: MutableSet<String?> = HashSet<String?>()

    /**
     * 口碑签到
     */
    private var kbSignIn: Long = 0

    /**
     * 保存时间
     */
    private var saveTime: Long? = 0L

    /**
     * 新村助力好友，已上限的用户
     */
    private val antStallAssistFriend: MutableSet<String?> = HashSet<String?>()

    /**
     * 新村-罚单已贴完的用户
     */
    private val canPasteTicketTime: MutableSet<String?> = HashSet<String?>()

    /**
     * 绿色经营，收取好友金币已完成用户
     */
    private val greenFinancePointFriend: MutableSet<String?> = HashSet<String?>()

    /**
     * 绿色经营，评级领奖已完成用户
     */
    private val greenFinancePrizesMap: MutableMap<String?, Int?> = HashMap<String?, Int?>()

    /**
     * 农场助力
     */
    private val antOrchardAssistFriend: MutableSet<String?> = HashSet<String?>()

    /**
     * 会员权益
     */
    private val memberPointExchangeBenefitLogList: MutableSet<String?> = HashSet<String?>()


    companion object {
        private val TAG: String = Status::class.java.getSimpleName()

        @Getter
        private val INSTANCE = Status()
        val currentDayTimestamp: Long
            get() {
                val calendar = Calendar.getInstance()
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                return calendar.getTimeInMillis() // 返回当天零点时间戳
            }


        fun getVitalityCount(skuId: String?): Int {
            var exchangedCount = Status.getINSTANCE().getVitalityStoreList().get(skuId)
            if (exchangedCount == null) {
                exchangedCount = 0
            }
            return exchangedCount
        }

        fun canVitalityExchangeToday(skuId: String?, count: Int): Boolean {
            return !hasFlagToday("forest::VitalityExchangeLimit::" + skuId) && getVitalityCount(skuId) < count
        }

        fun vitalityExchangeToday(skuId: String?) {
            val count: Int = getVitalityCount(skuId) + 1
            Status.getINSTANCE().getVitalityStoreList().put(skuId, count)
            save()
        }

        fun canAnimalSleep(): Boolean {
            return !Status.getINSTANCE().getAnimalSleep()
        }

        fun animalSleep() {
            if (!Status.getINSTANCE().getAnimalSleep()) {
                Status.getINSTANCE().setAnimalSleep(true)
                save()
            }
        }

        fun canWaterFriendToday(id: String?, newCount: Int): Boolean {
            var id = id
            id = UserMap.getCurrentUid() + "-" + id
            val count = Status.getINSTANCE().getWaterFriendLogList().get(id)
            if (count == null) {
                return true
            }
            return count < newCount
        }

        fun waterFriendToday(id: String?, count: Int) {
            var id = id
            id = UserMap.getCurrentUid() + "-" + id
            Status.getINSTANCE().getWaterFriendLogList().put(id, count)
            save()
        }

        fun getReserveTimes(id: String?): Int {
            val count = Status.getINSTANCE().getReserveLogList().get(id)
            if (count == null) {
                return 0
            }
            return count
        }

        fun canReserveToday(id: String?, count: Int): Boolean {
            return getReserveTimes(id) < count
        }

        fun reserveToday(id: String?, newCount: Int) {
            var count = Status.getINSTANCE().getReserveLogList().get(id)
            if (count == null) {
                count = 0
            }
            Status.getINSTANCE().getReserveLogList().put(id, count + newCount)
            save()
        }

        fun canCooperateWaterToday(uid: String?, coopId: String?): Boolean {
            return !Status.getINSTANCE().getCooperateWaterList().contains(uid + "_" + coopId)
        }

        fun cooperateWaterToday(uid: String?, coopId: String?) {
            val v = uid + "_" + coopId
            if (!Status.getINSTANCE().getCooperateWaterList().contains(v)) {
                Status.getINSTANCE().getCooperateWaterList().add(v)
                save()
            }
        }

        fun canAncientTreeToday(cityCode: String?): Boolean {
            return !Status.getINSTANCE().getAncientTreeCityCodeList().contains(cityCode)
        }

        fun ancientTreeToday(cityCode: String?) {
            if (!Status.getINSTANCE().getAncientTreeCityCodeList().contains(cityCode)) {
                Status.getINSTANCE().getAncientTreeCityCodeList().add(cityCode)
                save()
            }
        }

        fun canAnswerQuestionToday(): Boolean {
            return !Status.getINSTANCE().answerQuestion
        }

        fun answerQuestionToday() {
            if (!Status.getINSTANCE().answerQuestion) {
                Status.getINSTANCE().answerQuestion = true
                save()
            }
        }

        fun canFeedFriendToday(id: String?, newCount: Int): Boolean {
            val count = Status.getINSTANCE().feedFriendLogList.get(id)
            if (count == null) {
                return true
            }
            return count < newCount
        }

        fun feedFriendToday(id: String?) {
            var count = Status.getINSTANCE().feedFriendLogList.get(id)
            if (count == null) {
                count = 0
            }
            Status.getINSTANCE().feedFriendLogList.put(id, count + 1)
            save()
        }

        fun canVisitFriendToday(id: String?, newCount: Int): Boolean {
            var id = id
            id = UserMap.getCurrentUid() + "-" + id
            val count = Status.getINSTANCE().visitFriendLogList.get(id)
            if (count == null) {
                return true
            }
            return count < newCount
        }

        fun visitFriendToday(id: String?, newCount: Int) {
            var id = id
            id = UserMap.getCurrentUid() + "-" + id
            Status.getINSTANCE().visitFriendLogList.put(id, newCount)
            save()
        }

        fun canMemberSignInToday(uid: String?): Boolean {
            return !Status.getINSTANCE().memberSignInList.contains(uid)
        }

        fun memberSignInToday(uid: String?) {
            if (!Status.getINSTANCE().memberSignInList.contains(uid)) {
                Status.getINSTANCE().memberSignInList.add(uid)
                save()
            }
        }

        fun canUseAccelerateTool(): Boolean {
            return Status.getINSTANCE().useAccelerateToolCount < 8
        }

        fun useAccelerateTool() {
            Status.getINSTANCE().useAccelerateToolCount += 1
            save()
        }


        fun canDonationEgg(uid: String?): Boolean {
            return !Status.getINSTANCE().donationEggList.contains(uid)
        }

        fun donationEgg(uid: String?) {
            if (!Status.getINSTANCE().donationEggList.contains(uid)) {
                Status.getINSTANCE().donationEggList.add(uid)
                save()
            }
        }

        fun canSpreadManureToday(uid: String?): Boolean {
            return !Status.getINSTANCE().spreadManureList.contains(uid)
        }

        fun spreadManureToday(uid: String?) {
            if (!Status.getINSTANCE().spreadManureList.contains(uid)) {
                Status.getINSTANCE().spreadManureList.add(uid)
                save()
            }
        }

        /**
         * 是否可以新村助力
         *
         * @return true是，false否
         */
        fun canAntStallAssistFriendToday(): Boolean {
            return !Status.getINSTANCE().antStallAssistFriend.contains(UserMap.getCurrentUid())
        }

        /**
         * 设置新村助力已到上限
         */
        fun antStallAssistFriendToday() {
            val uid = UserMap.getCurrentUid()
            if (!Status.getINSTANCE().antStallAssistFriend.contains(uid)) {
                Status.getINSTANCE().antStallAssistFriend.add(uid)
                save()
            }
        }

        // 农场助力
        fun canAntOrchardAssistFriendToday(): Boolean {
            return !Status.getINSTANCE().antOrchardAssistFriend.contains(UserMap.getCurrentUid())
        }

        fun antOrchardAssistFriendToday() {
            val uid = UserMap.getCurrentUid()
            if (!Status.getINSTANCE().antOrchardAssistFriend.contains(uid)) {
                Status.getINSTANCE().antOrchardAssistFriend.add(uid)
                save()
            }
        }

        fun canProtectBubbleToday(uid: String?): Boolean {
            return !Status.getINSTANCE().getProtectBubbleList().contains(uid)
        }

        fun protectBubbleToday(uid: String?) {
            if (!Status.getINSTANCE().getProtectBubbleList().contains(uid)) {
                Status.getINSTANCE().getProtectBubbleList().add(uid)
                save()
            }
        }

        /**
         * 是否可以贴罚单
         *
         * @return true是，false否
         */
        fun canPasteTicketTime(): Boolean {
            return !Status.getINSTANCE().canPasteTicketTime.contains(UserMap.getCurrentUid())
        }

        /**
         * 罚单贴完了
         */
        fun pasteTicketTime() {
            if (Status.getINSTANCE().canPasteTicketTime.contains(UserMap.getCurrentUid())) {
                return
            }
            Status.getINSTANCE().canPasteTicketTime.add(UserMap.getCurrentUid())
            save()
        }

        fun canDoubleToday(): Boolean {
            val task: AntForest? = ModelTask.getModel<AntForest?>(AntForest::class.java)
            if (task == null) {
                return false
            }
            return Status.getINSTANCE().getDoubleTimes() < Objects.requireNonNull<IntegerModelField?>(task.getDoubleCountLimit()).value
        }

        fun DoubleToday() {
            Status.getINSTANCE().setDoubleTimes(Status.getINSTANCE().getDoubleTimes() + 1)
            save()
        }

        fun canKbSignInToday(): Boolean {
            return Status.getINSTANCE().kbSignIn < currentDayTimestamp
        }

        fun KbSignInToday() {
            val todayZero: Long = currentDayTimestamp // 获取当天零点时间戳
            if (Status.getINSTANCE().kbSignIn != todayZero) {
                Status.getINSTANCE().kbSignIn = todayZero
                save()
            }
        }

        fun setDadaDailySet(dailyAnswerList: MutableSet<String?>?) {
            Status.getINSTANCE().dailyAnswerList = dailyAnswerList
            save()
        }

        fun canDonateCharityCoin(): Boolean {
            return !Status.getINSTANCE().donateCharityCoin
        }

        fun donateCharityCoin() {
            if (!Status.getINSTANCE().donateCharityCoin) {
                Status.getINSTANCE().donateCharityCoin = true
                save()
            }
        }

        fun canExchangeToday(uid: String?): Boolean {
            return !Status.getINSTANCE().exchangeList.contains(uid)
        }

        fun exchangeToday(uid: String?) {
            if (!Status.getINSTANCE().exchangeList.contains(uid)) {
                Status.getINSTANCE().exchangeList.add(uid)
                save()
            }
        }

        /**
         * 绿色经营-是否可以收好友金币
         *
         * @return true是，false否
         */
        fun canGreenFinancePointFriend(): Boolean {
            return Status.getINSTANCE().greenFinancePointFriend.contains(UserMap.getCurrentUid())
        }

        /**
         * 绿色经营-收好友金币完了
         */
        fun greenFinancePointFriend() {
            if (canGreenFinancePointFriend()) {
                return
            }
            Status.getINSTANCE().greenFinancePointFriend.add(UserMap.getCurrentUid())
            save()
        }

        /**
         * 绿色经营-是否可以做评级任务
         *
         * @return true是，false否
         */
        fun canGreenFinancePrizesMap(): Boolean {
            val week = TimeUtil.getWeekNumber(Date())
            val currentUid = UserMap.getCurrentUid()
            if (Status.getINSTANCE().greenFinancePrizesMap.containsKey(currentUid)) {
                val storedWeek = Status.getINSTANCE().greenFinancePrizesMap.get(currentUid)
                return storedWeek == null || storedWeek != week
            }
            return true
        }

        /**
         * 绿色经营-评级任务完了
         */
        fun greenFinancePrizesMap() {
            if (!canGreenFinancePrizesMap()) {
                return
            }
            Status.getINSTANCE().greenFinancePrizesMap.put(UserMap.getCurrentUid(), TimeUtil.getWeekNumber(Date()))
            save()
        }

        /**
         * 加载状态文件
         *
         * @return 状态对象
         */
        @Synchronized
        fun load(currentUid: String?): Status {
//        String currentUid = UserMap.getCurrentUid();
            if (StringUtil.isEmpty(currentUid)) {
                Log.runtime(TAG, "用户为空，状态加载失败")
                throw RuntimeException("用户为空，状态加载失败")
            }
            try {
                val statusFile = Files.getStatusFile(currentUid)
                if (statusFile.exists()) {
                    Log.runtime(TAG, "加载 status.json")
                    val json = Files.readFromFile(statusFile)
                    if (!json.trim { it <= ' ' }.isEmpty()) {
                        JsonUtil.copyMapper().readerForUpdating(Status.getINSTANCE()).readValue<Any?>(json)
                        val formatted = JsonUtil.formatJson(Status.getINSTANCE())
                        if (formatted != null && formatted != json) {
                            Log.runtime(TAG, "重新格式化 status.json")
                            Files.write2File(formatted, statusFile)
                        }
                    } else {
                        Log.runtime(TAG, "配置文件为空，初始化默认配置")
                        initializeDefaultConfig(statusFile)
                    }
                } else {
                    Log.runtime(TAG, "配置文件不存在，初始化默认配置")
                    initializeDefaultConfig(statusFile)
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, t)
                Log.runtime(TAG, "状态文件格式有误，已重置")
                resetAndSaveConfig()
            }
            if (Status.getINSTANCE().saveTime == null) {
                Status.getINSTANCE().saveTime = System.currentTimeMillis()
            }
            return Status.getINSTANCE()
        }

        /**
         * 初始化默认配置
         *
         * @param statusFile 状态文件
         */
        private fun initializeDefaultConfig(statusFile: File?) {
            try {
                JsonUtil.copyMapper().updateValue<Status?>(Status.getINSTANCE(), Status())
                Log.runtime(TAG, "初始化 status.json")
                Files.write2File(JsonUtil.formatJson(Status.getINSTANCE()), statusFile)
            } catch (e: JsonMappingException) {
                Log.printStackTrace(TAG, e)
                throw RuntimeException("初始化配置失败", e)
            }
        }

        /**
         * 重置配置并保存
         */
        private fun resetAndSaveConfig() {
            try {
                JsonUtil.copyMapper().updateValue<Status?>(Status.getINSTANCE(), Status())
                Files.write2File(JsonUtil.formatJson(Status.getINSTANCE()), Files.getStatusFile(UserMap.getCurrentUid()))
            } catch (e: JsonMappingException) {
                Log.printStackTrace(TAG, e)
                throw RuntimeException("重置配置失败", e)
            }
        }

        @Synchronized
        fun unload() {
            try {
                JsonUtil.copyMapper().updateValue<Status?>(Status.getINSTANCE(), Status())
            } catch (e: JsonMappingException) {
                Log.printStackTrace(TAG, e)
            }
        }

        @Synchronized
        fun save() {
            save(Calendar.getInstance())
        }

        @Synchronized
        fun save(nowCalendar: Calendar) {
            val currentUid = UserMap.getCurrentUid()
            if (StringUtil.isEmpty(currentUid)) {
                Log.record(TAG, "用户为空，状态保存失败")
                throw RuntimeException("用户为空，状态保存失败")
            }
            if (updateDay(nowCalendar)) {
                Log.runtime(TAG, "重置 statistics.json")
            } else {
                Log.runtime(TAG, "保存 status.json")
            }
            val lastSaveTime = Status.getINSTANCE().saveTime
            try {
                Status.getINSTANCE().saveTime = System.currentTimeMillis()
                Files.write2File(JsonUtil.formatJson(Status.getINSTANCE()), Files.getStatusFile(currentUid))
            } catch (e: Exception) {
                Status.getINSTANCE().saveTime = lastSaveTime
                throw e
            }
        }

        fun updateDay(nowCalendar: Calendar): Boolean {
            if (TimeUtil.isLessThanSecondOfDays(Status.getINSTANCE().saveTime, nowCalendar.getTimeInMillis())) {
                unload()
                return true
            } else {
                return false
            }
        }

        fun canOrnamentToday(): Boolean {
            return Status.getINSTANCE().canOrnament
        }

        fun setOrnamentToday() {
            if (Status.getINSTANCE().canOrnament) {
                Status.getINSTANCE().canOrnament = false
                save()
            }
        }

        // 新村捐赠
        fun canStallDonateToday(): Boolean {
            return Status.getINSTANCE().canStallDonate
        }

        fun setStallDonateToday() {
            if (Status.getINSTANCE().canStallDonate) {
                Status.getINSTANCE().canStallDonate = false
                save()
            }
        }

        fun hasFlagToday(flag: String?): Boolean {
            return Status.getINSTANCE().flagList.contains(flag)
        }

        fun setFlagToday(flag: String?) {
            if (!hasFlagToday(flag)) {
                Status.getINSTANCE().flagList.add(flag)
                save()
            }
        }

        //2025/12/4 用来获取 自定义flag的int
        fun getIntFlagToday(key: String?): Int? {
            return Status.getINSTANCE().intFlagMap.get(key)
        }

        fun setIntFlagToday(key: String?, value: Int) {
            Status.getINSTANCE().intFlagMap.put(key, value)
            save()
        }


        fun canMemberPointExchangeBenefitToday(benefitId: String?): Boolean {
            return !Status.getINSTANCE().memberPointExchangeBenefitLogList.contains(benefitId)
        }

        fun memberPointExchangeBenefitToday(benefitId: String?) {
            if (canMemberPointExchangeBenefitToday(benefitId)) {
                Status.getINSTANCE().memberPointExchangeBenefitLogList.add(benefitId)
                save()
            }
        }

        /**
         * 乐园商城-是否可以兑换该商品
         *
         * @param spuId 商品spuId
         * @return true 可以兑换 false 兑换达到上限
         */
        fun canParadiseCoinExchangeBenefitToday(spuId: String?): Boolean {
            return !hasFlagToday("farm::paradiseCoinExchangeLimit::" + spuId)
        }
    }
}