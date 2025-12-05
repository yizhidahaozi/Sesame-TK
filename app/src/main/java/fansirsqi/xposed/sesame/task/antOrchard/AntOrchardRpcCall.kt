package fansirsqi.xposed.sesame.task.antOrchard

import fansirsqi.xposed.sesame.hook.RequestManager

object AntOrchardRpcCall {
    private const val VERSION = "20251128.01"

    fun orchardIndex(): String {
        return RequestManager.requestString(
            "com.alipay.antfarm.orchardIndex",
            "[{\"inHomepage\":\"true\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"$VERSION\"}]"
        )
    }

    fun extraInfoGet(): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.extraInfoGet",
            "[{\"from\":\"entry\",\"requestType\":\"NORMAL\",\"sceneCode\":\"FUGUO\",\"source\":\"ch_alipaysearch__chsub_normal\",\"version\":\"$VERSION\"}]"
        )
    }


    fun batchHireAnimalRecommend(orchardUserId: String): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.batchHireAnimalRecommend",
            "[{\"orchardUserId\":\"$orchardUserId\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"sceneType\":\"weed\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"$VERSION\"}]"
        )
    }

    fun batchHireAnimal(recommendGroupList: List<String>): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.batchHireAnimal",
            "[{\"recommendGroupList\":[${recommendGroupList.joinToString(",")}],\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"sceneType\":\"weed\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"$VERSION\"}]"
        )
    }


    fun extraInfoSet(): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.extraInfoSet",
            "[{\"bizCode\":\"fertilizerPacket\",\"bizParam\":{\"action\":\"queryCollectFertilizerPacket\"},\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"$VERSION\"}]"
        )
    }

    fun querySubplotsActivity(treeLevel: String): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.querySubplotsActivity",
            "[{\"activityType\":[\"WISH\",\"BATTLE\",\"HELP_FARMER\",\"DEFOLIATION\",\"CAMP_TAKEOVER\"],\"inHomepage\":false,\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"treeLevel\":\"$treeLevel\",\"version\":\"$VERSION\"}]"
        )
    }

    fun triggerSubplotsActivity(activityId: String, activityType: String, optionKey: String): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.triggerSubplotsActivity",
            "[{\"activityId\":\"$activityId\",\"activityType\":\"$activityType\",\"optionKey\":\"$optionKey\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"$VERSION\"}]"
        )
    }

    fun receiveOrchardRights(activityId: String, activityType: String): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.receiveOrchardRights",
            "[{\"activityId\":\"$activityId\",\"activityType\":\"$activityType\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"$VERSION\"}]"
        )
    }

    /* 七日礼包 */
    fun drawLottery(): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.drawLottery",
            "[{\"lotteryScene\":\"receiveLotteryPlus\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"$VERSION\"}]"
        )
    }

    fun orchardSyncIndex(): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.orchardSyncIndex",
            "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"syncIndexTypes\":\"QUERY_MAIN_ACCOUNT_INFO\",\"version\":\"$VERSION\"}]"
        )
    }

    /**
     * 施肥
     */
    fun orchardSpreadManure(wua: String): String {
        return RequestManager.requestString(
            "com.alipay.antfarm.orchardSpreadManure",
            "[{\"plantScene\":\"main\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"useBatchSpread\":false,\"version\":$VERSION,\"wua\":\"$wua\"}]"
        )
    }

    fun receiveTaskAward(sceneCode: String, taskType: String): String {
        return RequestManager.requestString(
            "com.alipay.antiep.receiveTaskAward",
            "[{\"ignoreLimit\":false,\"requestType\":\"NORMAL\",\"sceneCode\":\"$sceneCode\",\"source\":\"ch_alipaysearch__chsub_normal\",\"taskType\":\"$taskType\",\"version\":\"$VERSION\"}]"
        )
    }

    fun orchardListTask(): String {
        return RequestManager.requestString(
            "com.alipay.antfarm.orchardListTask",
            "[{\"plantHiddenMMC\":\"false\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"$VERSION\"}]"
        )
    }

    fun orchardSign(): String {
        return RequestManager.requestString(
            "com.alipay.antfarm.orchardSign",
            "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"signScene\":\"ANTFARM_ORCHARD_SIGN_V2\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"$VERSION\"}]"
        )
    }

    fun finishTask(userId: String, sceneCode: String, taskType: String): String {
        return RequestManager.requestString(
            "com.alipay.antiep.finishTask",
            "[{\"outBizNo\":\"${userId}${System.currentTimeMillis()}\",\"requestType\":\"NORMAL\",\"sceneCode\":\"$sceneCode\",\"source\":\"ch_appcenter__chsub_9patch\",\"taskType\":\"$taskType\",\"userId\":\"$userId\",\"version\":\"$VERSION\"}]"
        )
    }

    fun triggerTbTask(taskId: String, taskPlantType: String): String {
        return RequestManager.requestString(
            "com.alipay.antfarm.triggerTbTask",
            "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"taskId\":\"$taskId\",\"taskPlantType\":\"$taskPlantType\",\"version\":\"$VERSION\"}]"
        )
    }

    fun orchardSelectSeed(): String {
        return RequestManager.requestString(
            "com.alipay.antfarm.orchardSelectSeed",
            "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"seedCode\":\"rp\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"$VERSION\"}]"
        )
    }

    /* 砸金蛋 */
    fun queryGameCenter(): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.queryGameCenter",
            "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"$VERSION\"}]"
        )
    }

    fun noticeGame(appId: String): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.noticeGame",
            "[{\"appId\":\"$appId\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"$VERSION\"}]"
        )
    }

    fun submitUserAction(gameId: String): String {
        return RequestManager.requestString(
            "com.alipay.gamecenteruprod.biz.rpc.v3.submitUserAction",
            "[{\"actionCode\":\"enterGame\",\"gameId\":\"$gameId\",\"paladinxVersion\":\"2.0.13\",\"source\":\"gameFramework\"}]"
        )
    }

    fun submitUserPlayDurationAction(gameAppId: String, source: String): String {
        return RequestManager.requestString(
            "com.alipay.gamecenteruprod.biz.rpc.v3.submitUserPlayDurationAction",
            "[{\"gameAppId\":\"$gameAppId\",\"playTime\":32,\"source\":\"$source\",\"statisticTag\":\"\"}]"
        )
    }

    fun smashedGoldenEgg(): String {
        return RequestManager.requestString(
            "com.alipay.antorchard.smashedGoldenEgg",
            "[{\"requestType\":\"NORMAL\",\"seneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"$VERSION\"}]"
        )
    }

    /* 助力好友 */
    //  fun shareP2P(): String {
    //        return ApplicationHook.requestString("com.alipay.antiep.shareP2P",
    //                "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ANTFARM_ORCHARD_SHARE_P2P\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"$VERSION\"}]")
    //    }
    fun achieveBeShareP2P(shareId: String): String {
        return RequestManager.requestString(
            "com.alipay.antiep.achieveBeShareP2P",
            "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ANTFARM_ORCHARD_SHARE_P2P\",\"shareId\":\"$shareId\",\"source\":\"share\",\"version\":\"$VERSION\"}]"
        )
    }
}
