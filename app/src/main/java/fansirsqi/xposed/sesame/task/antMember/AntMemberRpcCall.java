package fansirsqi.xposed.sesame.task.antMember;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import fansirsqi.xposed.sesame.entity.RpcEntity;
import fansirsqi.xposed.sesame.hook.ApplicationHook;
import fansirsqi.xposed.sesame.hook.RequestManager;
import fansirsqi.xposed.sesame.util.RandomUtil;
import fansirsqi.xposed.sesame.util.TimeUtil;

public class AntMemberRpcCall {
    private static String getUniqueId() {
        return String.valueOf(System.currentTimeMillis()) + RandomUtil.nextLong();
    }

    /* ant member point */
    public static String queryPointCert(int page, int pageSize) {
        String args1 = "[{\"page\":" + page + ",\"pageSize\":" + pageSize + "}]";
        return RequestManager.requestString("alipay.antmember.biz.rpc.member.h5.queryPointCert", args1);
    }

    public static String receivePointByUser(String certId) {
        String args1 = "[{\"certId\":" + certId + "}]";
        return RequestManager.requestString("alipay.antmember.biz.rpc.member.h5.receivePointByUser", args1);
    }

    public static String receiveAllPointByUser() throws JSONException {
//        [{"bizSource":"myTab","sourcePassMap":{"innerSource":"","passInfo":"{\"tc\":\"EXPIRING_POINT\"}","source":"myTab","unid":""}}]
        JSONObject args = new JSONObject();
        args.put("bizSource", "myTab");
        JSONObject passMap = new JSONObject();
        passMap.put("innerSource", "");
        JSONObject passInfo = new JSONObject();
        passInfo.put("tc", "EXPIRING_POINT");
        passMap.put("passInfo", passInfo);
        passMap.put("source", "myTab");
        passMap.put("unid", "");
        args.put("sourcePassMap", passMap);
        String params = "[" + args + "]";
        return RequestManager.requestString("com.alipay.alipaymember.biz.rpc.pointcert.h5.receiveAllPointByUser", params);
    }

    public static String queryMemberSigninCalendar() {
        return RequestManager.requestString("com.alipay.amic.biz.rpc.signin.h5.queryMemberSigninCalendar",
                "[{\"autoSignIn\":true,\"invitorUserId\":\"\",\"sceneCode\":\"QUERY\"}]");
    }

    /* 商家开门打卡任务 */
    public static String signIn(String activityNo) {
        return RequestManager.requestString("alipay.merchant.kmdk.signIn",
                "[{\"activityNo\":\"" + activityNo + "\"}]");
    }

    public static String signUp(String activityNo) {
        return RequestManager.requestString("alipay.merchant.kmdk.signUp",
                "[{\"activityNo\":\"" + activityNo + "\"}]");
    }

    /* 商家服务 */
    public static String transcodeCheck() {
        return RequestManager.requestString("alipay.mrchservbase.mrchbusiness.sign.transcode.check",
                "[{}]");
    }

    public static String merchantSign() {
        return RequestManager.requestString("alipay.mrchservbase.mrchpoint.sqyj.homepage.signin.v1",
                "[{}]");
    }

    public static String zcjSignInQuery() {
        return RequestManager.requestString("alipay.mrchservbase.zcj.view.invoke",
                "[{\"compId\":\"ZCJ_SIGN_IN_QUERY\"}]");
    }

    public static String zcjSignInExecute() {
        return RequestManager.requestString("alipay.mrchservbase.zcj.view.invoke",
                "[{\"compId\":\"ZCJ_SIGN_IN_EXECUTE\"}]");
    }

    public static String taskListQuery() {
        return RequestManager.requestString("alipay.mrchservbase.task.more.query",
                "[{\"paramMap\":{\"platform\":\"Android\"},\"taskItemCode\":\"\"}]");
    }

    public static String queryActivity() {
        return RequestManager.requestString("alipay.merchant.kmdk.query.activity",
                "[{\"scene\":\"activityCenter\"}]");
    }

    /* 商家服务任务 */
    public static String taskFinish(String bizId) {
        return RequestManager.requestString("com.alipay.adtask.biz.mobilegw.service.task.finish",
                "[{\"bizId\":\"" + bizId + "\"}]");
    }

    public static String taskReceive(String taskCode) {
        return RequestManager.requestString("alipay.mrchservbase.sqyj.task.receive",
                "[{\"compId\":\"ZTS_TASK_RECEIVE\",\"extInfo\":{\"taskCode\":\"" + taskCode + "\"}}]");
    }

    public static String actioncode(String actionCode) {
        return RequestManager.requestString("alipay.mrchservbase.task.query.by.actioncode",
                "[{\"actionCode\":\"" + actionCode + "\"}]");
    }

    public static String produce(String actionCode) {
        return RequestManager.requestString("alipay.mrchservbase.biz.task.action.produce",
                "[{\"actionCode\":\"" + actionCode + "\"}]");
    }

    public static String ballReceive(String ballIds) {
        return RequestManager.requestString("alipay.mrchservbase.mrchpoint.ball.receive",
                "[{\"ballIds\":[\"" + ballIds
                        + "\"],\"channel\":\"MRCH_SELF\",\"outBizNo\":\"" + getUniqueId() + "\"}]");
    }

    /* 会员任务 */
    public static String signPageTaskList() {
        return RequestManager.requestString("alipay.antmember.biz.rpc.membertask.h5.signPageTaskList",
                "[{\"sourceBusiness\":\"antmember\",\"spaceCode\":\"ant_member_xlight_task\"}]");
    }

    public static String applyTask(String darwinName, Long taskConfigId) {
        return RequestManager.requestString("alipay.antmember.biz.rpc.membertask.h5.applyTask",
                "[{\"darwinExpParams\":{\"darwinName\":\"" + darwinName
                        + "\"},\"sourcePassMap\":{\"innerSource\":\"\",\"source\":\"myTab\",\"unid\":\"\"},\"taskConfigId\":"
                        + taskConfigId + "}]");
    }

    public static String executeTask(String bizParam, String bizSubType, String bizType, Long taskConfigId) {
        return RequestManager.requestString("alipay.antmember.biz.rpc.membertask.h5.executeTask",
                "[{\"bizOutNo\":\"" + TimeUtil.getFormatDate().replaceAll("-", "") +
                        "\",\"bizParam\":\"" + bizParam + "\",\"bizSubType\":\"" + bizSubType + "\",\"bizType\":\"" + bizType +
                        "\",\"sourcePassMap\":{\"innerSource\":\"\",\"source\":\"myTab\",\"unid\":\"\"}" +
                        ",\"syncProcess\":true,\"taskConfigId\":\"" + taskConfigId + "\"}]");
    }

    public static String queryAllStatusTaskList() {
        return RequestManager.requestString("alipay.antmember.biz.rpc.membertask.h5.queryAllStatusTaskList",
                "[{\"sourceBusiness\":\"signInAd\",\"sourcePassMap\":{\"innerSource\":\"\",\"source\":\"myTab\",\"unid\":\"\"}}]");
    }

    public static String rpcCall_signIn() {
        String args1 = "[{\"sceneCode\":\"KOUBEI_INTEGRAL\",\"source\":\"ALIPAY_TAB\",\"version\":\"2.0\"}]";
        return RequestManager.requestString("alipay.kbmemberprod.action.signIn", args1);
    }

    /**
     * 黄金票收取
     *
     * @param str signInfo
     * @return 结果
     */
    public static String goldBillCollect(String str) {
        return RequestManager.requestString("com.alipay.wealthgoldtwa.goldbill.v2.index.collect",
                "[{" + str + "\"trigger\":\"Y\"}]");
    }

    /**
     * 游戏中心签到查询
     */
    public static String querySignInBall() {
        return RequestManager.requestString("com.alipay.gamecenteruprod.biz.rpc.v3.querySignInBall",
                "[{\"source\":\"ch_appcenter__chsub_9patch\"}]");
    }

    /**
     * 游戏中心签到
     */
    public static String continueSignIn() {
        return RequestManager.requestString("com.alipay.gamecenteruprod.biz.rpc.continueSignIn",
                "[{\"sceneId\":\"GAME_CENTER\",\"signType\":\"NORMAL_SIGN\",\"source\":\"ch_appcenter__chsub_9patch\"}]");
    }

    /**
     * 游戏中心查询待领取乐豆列表
     */
    public static String queryPointBallList() {
        return RequestManager.requestString("com.alipay.gamecenteruprod.biz.rpc.v3.queryPointBallList",
                "[{\"source\":\"ch_appcenter__chsub_9patch\"}]");
    }

    /**
     * 游戏中心全部领取
     */
    public static String batchReceivePointBall() {
        return RequestManager.requestString("com.alipay.gamecenteruprod.biz.rpc.v3.batchReceivePointBall",
                "[{}]");
    }

    /**
     * 芝麻信用首页
     */
    public static String queryHome() {
        return RequestManager.requestString("com.antgroup.zmxy.zmcustprod.biz.rpc.home.api.HomeV7RpcManager.queryHome",
                "[{\"invokeSource\":\"zmHome\",\"miniZmGrayInside\":\"\",\"version\":\"week\"}]");
    }

    /**
     * 获取芝麻信用任务列表
     */
    public static String queryAvailableSesameTask() {
        return RequestManager.requestString("com.antgroup.zmxy.zmmemberop.biz.rpc.creditaccumulate.CreditAccumulateStrategyRpcManager.queryListV3", "[{}]");
    }

    /**
     * 芝麻信用领取任务
     */
    public static String joinSesameTask(String taskTemplateId) {
        return RequestManager.requestString("com.antgroup.zmxy.zmmemberop.biz.rpc.promise.PromiseRpcManager.joinActivity",
                "[{\"chInfo\":\"seasameList\",\"joinFromOuter\":false,\"templateId\":\"" + taskTemplateId + "\"}]");
    }

    /**
     * 芝麻信用获取任务回调
     */
    public static String feedBackSesameTask(String taskTemplateId) {
        return RequestManager.requestString("com.antgroup.zmxy.zmmemberop.biz.rpc.creditaccumulate.CreditAccumulateStrategyRpcManager.taskFeedback",
                "[{\"actionType\":\"TO_COMPLETE\",\"templateId\":\"" + taskTemplateId + "\"}]",
                "zmmemberop", "taskFeedback", "CreditAccumulateStrategyRpcManager");
    }

    /**
     * 芝麻信用完成任务
     */
    public static String finishSesameTask(String recordId) {
        return RequestManager.requestString("com.antgroup.zmxy.zmmemberop.biz.rpc.promise.PromiseRpcManager.pushActivity",
                "[{\"recordId\":\"" + recordId + "\"}]");
    }

    /**
     * 查询可收取的芝麻粒
     */
    public static String queryCreditFeedback() {
        return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.home.creditaccumulate.api.CreditAccumulateRpcManager.queryCreditFeedback",
                "[{\"queryPotential\":false,\"size\":20,\"status\":\"UNCLAIMED\"}]");
    }

    /**
     * 一键收取芝麻粒
     */
    public static String collectAllCreditFeedback() {
        return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.home.creditaccumulate.api.CreditAccumulateRpcManager.collectCreditFeedback",
                "[{\"collectAll\":true,\"status\":\"UNCLAIMED\"}]");
    }

    /**
     * 收取芝麻粒
     *
     * @param creditFeedbackId creditFeedbackId
     */
    public static String collectCreditFeedback(String creditFeedbackId) {
        return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.home.creditaccumulate.api.CreditAccumulateRpcManager.collectCreditFeedback",
                "[{\"collectAll\":false,\"creditFeedbackId\":\"" + creditFeedbackId + "\",\"status\":\"UNCLAIMED\"}]");
    }

    /**
     * 获取保障金信息
     */
    public static String queryInsuredHome() {
        return RequestManager.requestString("com.alipay.insplatformbff.insgift.accountService.queryAccountForPlat",
                "[{\"includePolicy\":true,\"specialChannel\":\"wealth_entry\"}]");
    }

    /**
     * 获取所有可领取的保障金
     */
    public static String queryAvailableCollectInsuredGold() {
        return RequestManager.requestString("com.alipay.insgiftbff.insgiftMain.queryMultiSceneWaitToGainList",
                "[{\"entrance\":\"wealth_entry\",\"eventToWaitParamDTO\":{\"giftProdCode\":\"GIFT_UNIVERSAL_COVERAGE\",\"rightNoList\":[\"UNIVERSAL_ACCIDENT\",\"UNIVERSAL_HOSPITAL\",\"UNIVERSAL_OUTPATIENT\",\"UNIVERSAL_SERIOUSNESS\",\"UNIVERSAL_WEALTH\",\"UNIVERSAL_TRANS\",\"UNIVERSAL_FRAUD_LIABILITY\"]},\"helpChildParamDTO\":{\"giftProdCode\":\"GIFT_HEALTH_GOLD_CHILD\",\"rightNoList\":[\"UNIVERSAL_ACCIDENT\",\"UNIVERSAL_HOSPITAL\",\"UNIVERSAL_OUTPATIENT\",\"UNIVERSAL_SERIOUSNESS\",\"UNIVERSAL_WEALTH\",\"UNIVERSAL_TRANS\",\"UNIVERSAL_FRAUD_LIABILITY\"]},\"priorityChannelParamDTO\":{\"giftProdCode\":\"GIFT_UNIVERSAL_COVERAGE\",\"rightNoList\":[\"UNIVERSAL_ACCIDENT\",\"UNIVERSAL_HOSPITAL\",\"UNIVERSAL_OUTPATIENT\",\"UNIVERSAL_SERIOUSNESS\",\"UNIVERSAL_WEALTH\",\"UNIVERSAL_TRANS\",\"UNIVERSAL_FRAUD_LIABILITY\"]},\"signInParamDTO\":{\"giftProdCode\":\"GIFT_UNIVERSAL_COVERAGE\",\"rightNoList\":[\"UNIVERSAL_ACCIDENT\",\"UNIVERSAL_HOSPITAL\",\"UNIVERSAL_OUTPATIENT\",\"UNIVERSAL_SERIOUSNESS\",\"UNIVERSAL_WEALTH\",\"UNIVERSAL_TRANS\",\"UNIVERSAL_FRAUD_LIABILITY\"]}}]",
                "insgiftbff", "queryMultiSceneWaitToGainList", "insgiftMain");
    }

    /**
     * 领取保障金
     */
    public static String collectInsuredGold(JSONObject goldBallObj) {
        return RequestManager.requestString("com.alipay.insgiftbff.insgiftMain.gainMyAndFamilySumInsured",
                goldBallObj.toString(), "insgiftbff", "gainMyAndFamilySumInsured", "insgiftMain");
    }

    /**
     * 查询生活记录
     *
     * @return 结果
     */
    public static String promiseQueryHome() {
        return RequestManager.requestString("com.antgroup.zmxy.zmmemberop.biz.rpc.promise.PromiseRpcManager.queryHome", null);
    }

    /**
     * 查询生活记录明细
     *
     * @param recordId recordId
     * @return 结果
     */
    public static String promiseQueryDetail(String recordId) {
        return RequestManager.requestString("com.antgroup.zmxy.zmmemberop.biz.rpc.promise.PromiseRpcManager.queryDetail",
                "[{\"recordId\":\"" + recordId + "\"}]");
    }

    /**
     * 生活记录加入新纪录
     *
     * @param data data
     * @return 结果
     */
    public static String promiseJoin(String data) {
        return RequestManager.requestString("com.antgroup.zmxy.zmmemberop.biz.rpc.promise.PromiseRpcManager.join",
                "[" + data + "]");
    }

    /**
     * 查询待领取的保障金
     *
     * @return 结果
     */
    public static String queryMultiSceneWaitToGainList() {
        return RequestManager.requestString("com.alipay.insgiftbff.insgiftMain.queryMultiSceneWaitToGainList",
                "[{\"entrance\":\"jkj_zhima_dairy66\",\"eventToWaitParamDTO\":{\"giftProdCode\":\"GIFT_UNIVERSAL_COVERAGE\"," +
                        "\"rightNoList\":[\"UNIVERSAL_ACCIDENT\",\"UNIVERSAL_HOSPITAL\",\"UNIVERSAL_OUTPATIENT\"," +
                        "\"UNIVERSAL_SERIOUSNESS\",\"UNIVERSAL_WEALTH\",\"UNIVERSAL_TRANS\",\"UNIVERSAL_FRAUD_LIABILITY\"]}," +
                        "\"helpChildParamDTO\":{\"giftProdCode\":\"GIFT_HEALTH_GOLD_CHILD\",\"rightNoList\":[\"UNIVERSAL_ACCIDENT\"," +
                        "\"UNIVERSAL_HOSPITAL\",\"UNIVERSAL_OUTPATIENT\",\"UNIVERSAL_SERIOUSNESS\",\"UNIVERSAL_WEALTH\"," +
                        "\"UNIVERSAL_TRANS\",\"UNIVERSAL_FRAUD_LIABILITY\"]},\"priorityChannelParamDTO\":{\"giftProdCode\":" +
                        "\"GIFT_UNIVERSAL_COVERAGE\",\"rightNoList\":[\"UNIVERSAL_ACCIDENT\",\"UNIVERSAL_HOSPITAL\"," +
                        "\"UNIVERSAL_OUTPATIENT\",\"UNIVERSAL_SERIOUSNESS\",\"UNIVERSAL_WEALTH\",\"UNIVERSAL_TRANS\"," +
                        "\"UNIVERSAL_FRAUD_LIABILITY\"]},\"signInParamDTO\":{\"giftProdCode\":\"GIFT_UNIVERSAL_COVERAGE\"," +
                        "\"rightNoList\":[\"UNIVERSAL_ACCIDENT\",\"UNIVERSAL_HOSPITAL\",\"UNIVERSAL_OUTPATIENT\"," +
                        "\"UNIVERSAL_SERIOUSNESS\",\"UNIVERSAL_WEALTH\",\"UNIVERSAL_TRANS\",\"UNIVERSAL_FRAUD_LIABILITY\"]}}]");
    }

    /**
     * 领取保障金
     *
     * @param jsonObject jsonObject
     * @return 结果
     */
    public static String gainMyAndFamilySumInsured(JSONObject jsonObject) throws JSONException {
        jsonObject.put("disabled", false);
        jsonObject.put("entrance", "jkj_zhima_dairy66");
        return RequestManager.requestString("com.alipay.insgiftbff.insgiftMain.gainMyAndFamilySumInsured",
                "[" + jsonObject + "]");
    }

    // 安心豆
    public static String querySignInProcess(String appletId, String scene) {
        return RequestManager.requestString("com.alipay.insmarketingbff.bean.querySignInProcess",
                "[{\"appletId\":\"" + appletId + "\",\"scene\":\"" + scene + "\"}]");
    }

    public static String signInTrigger(String appletId, String scene) {
        return RequestManager.requestString("com.alipay.insmarketingbff.bean.signInTrigger",
                "[{\"appletId\":\"" + appletId + "\",\"scene\":\"" + scene + "\"}]");
    }

    public static String beanExchangeDetail(String itemId) {
        return RequestManager.requestString("com.alipay.insmarketingbff.onestop.planTrigger",
                "[{\"extParams\":{\"itemId\":\"" + itemId + "\"},"
                        + "\"planCode\":\"bluebean_onestop\",\"planOperateCode\":\"exchangeDetail\"}]");
    }

    public static String beanExchange(String itemId, int pointAmount) {
        return RequestManager.requestString("com.alipay.insmarketingbff.onestop.planTrigger",
                "[{\"extParams\":{\"itemId\":\"" + itemId + "\",\"pointAmount\":\"" + Integer.toString(pointAmount) + "\"},"
                        + "\"planCode\":\"bluebean_onestop\",\"planOperateCode\":\"exchange\"}]");
    }

    public static String queryUserAccountInfo(String pointProdCode) {
        return RequestManager.requestString("com.alipay.insmarketingbff.point.queryUserAccountInfo",
                "[{\"channel\":\"HiChat\",\"pointProdCode\":\"" + pointProdCode + "\",\"pointUnitType\":\"COUNT\"}]");
    }

    /**
     * 查询会员信息
     */
    public static String queryMemberInfo() {
        String data = "[{\"needExpirePoint\":true,\"needGrade\":true,\"needPoint\":true,\"queryScene\":\"POINT_EXCHANGE_SCENE\",\"source\":\"POINT_EXCHANGE_SCENE\",\"sourcePassMap\":{\"innerSource\":\"\",\"source\":\"\",\"unid\":\"\"}}]";
        return RequestManager.requestString("com.alipay.alipaymember.biz.rpc.member.h5.queryMemberInfo", data);
    }

    /**
     * 查询0元兑公益道具列表
     *
     * @param userId       userId
     * @param pointBalance 当前可用会员积分
     */
    public static String queryShandieEntityList(String userId, String pointBalance) {
        String uniqueId = System.currentTimeMillis() + userId + "94000SR202501061144200394000SR2025010611458003";
        String data = "[{\"blackIds\":[],\"deliveryIdList\":[\"94000SR2025010611442003\",\"94000SR2025010611458003\"],\"filterCityCode\":false,\"filterPointNoEnough\":false,\"filterStockNoEnough\":false,\"pageNum\":1,\"pageSize\":18,\"point\":" + pointBalance + ",\"previewCopyDbId\":\"\",\"queryType\":\"DELIVERY_ID_LIST\",\"source\":\"member_day\",\"sourcePassMap\":{\"innerSource\":\"\",\"source\":\"0yuandui\",\"unid\":\"\"},\"topIds\":[],\"uniqueId\":\"" + uniqueId + "\"}]";
        return RequestManager.requestString("com.alipay.alipaymember.biz.rpc.config.h5.queryShandieEntityList", data);
    }

    /**
     * 会员积分兑换道具
     *
     * @param benefitId benefitId
     * @param itemId    itemId
     * @return 结果
     */
    public static String exchangeBenefit(String benefitId, String itemId) {
        String requestId = "requestId" + System.currentTimeMillis();
        String alipayClientVersion = ApplicationHook.getAlipayVersion().getVersionString();
        String data = "[{\"benefitId\":\"" + benefitId + "\",\"cityCode\":\"\",\"exchangeType\":\"POINT_PAY\",\"itemId\":\"" + itemId + "\",\"miniAppId\":\"\",\"orderSource\":\"\",\"requestId\":\"" + requestId + "\",\"requestSourceInfo\":\"\",\"sourcePassMap\":{\"alipayClientVersion\":\"" + alipayClientVersion + "\",\"innerSource\":\"\",\"mobileOsType\":\"Android\",\"source\":\"\",\"unid\":\"\"},\"userOutAccount\":\"\"}]";
        return RequestManager.requestString("com.alipay.alipaymember.biz.rpc.exchange.h5.exchangeBenefit", data);
    }

    /**
     * 芝麻炼金/积分首页
     */
    public static String alchemyQueryHome() {
        return RequestManager.requestString("com.antgroup.zmxy.zmmemberop.biz.rpc.AlchemyRpcManager.queryHome",
                "[{}]");
    }

    /**
     * [日志对应] 芝麻炼金-执行炼金
     * Method: com.antgroup.zmxy.zmmemberop.biz.rpc.AlchemyRpcManager.alchemy
     * Params: [null]
     */
    public static String alchemyExecute() {
        // 日志中 requestData 为 [null]
        return RequestManager.requestString("com.antgroup.zmxy.zmmemberop.biz.rpc.AlchemyRpcManager.alchemy", "[null]");
    }

    /**
     * [日志对应] 芝麻炼金-签到列表查询
     * Method: com.antgroup.zmxy.zmmemberop.biz.rpc.pointtask.CheckInTaskRpcManager.queryTaskLists
     */
    public static String alchemyQueryCheckIn() {
        return RequestManager.requestString("com.antgroup.zmxy.zmmemberop.biz.rpc.pointtask.CheckInTaskRpcManager.queryTaskLists",
                "[{\"sceneCode\":\"alchemy\",\"version\":\"2025-10-22\"}]");
    }

    /**
     * [日志对应] 芝麻炼金-时段奖励查询 (午饭/晚饭)
     * Method: com.antgroup.zmxy.zmmemberop.biz.rpc.pointtask.TimeLimitedTaskRpcManager.queryTask
     */
    public static String alchemyQueryTimeLimitedTask() {
        return RequestManager.requestString("com.antgroup.zmxy.zmmemberop.biz.rpc.pointtask.TimeLimitedTaskRpcManager.queryTask",
                "[{}]");
    }

    /**
     * [日志对应] 芝麻炼金-任务列表 V3 (参数精确匹配日志)
     * Method: com.antgroup.zmxy.zmmemberop.biz.rpc.creditaccumulate.CreditAccumulateStrategyRpcManager.queryListV3
     */
    public static String alchemyQueryListV3() {
        return RequestManager.requestString("com.antgroup.zmxy.zmmemberop.biz.rpc.creditaccumulate.CreditAccumulateStrategyRpcManager.queryListV3",
                "[{\"chInfo\":\"\",\"deliverStatus\":\"\",\"deliveryTemplateId\":\"\",\"searchSubscribeTask\":true,\"version\":\"alchemy\"}]");
    }

    // ================= 芝麻树 =================
    private static final String ZHIMATREE_PLAY_INFO = "SwbtxJSo8OOUrymAU%2FHnY2jyFRc%2BkCJ3";
    private static final String ZHIMATREE_REFER = "https://render.alipay.com/p/yuyan/180020010001269849/zmTree.html?caprMode=sync&chInfo=chInfo=ch_zmzltf__chsub_xinyongsyyingxiaowei";

    /**
     * 查询芝麻树首页
     */
    public static String zhimaTreeHomePage() {
        try {
            JSONObject args = new JSONObject();
            args.put("operation", "ZHIMA_TREE_HOME_PAGE");
            args.put("playInfo", ZHIMATREE_PLAY_INFO);
            args.put("refer", ZHIMATREE_REFER);
            args.put("extInfo", new JSONObject());

            return RequestManager.requestString("alipay.promoprod.play.trigger",
                    new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 净化芝麻树 (消耗净化值)
     */
    public static String zhimaTreeCleanAndPush(String treeCode) {
        try {
            JSONObject args = new JSONObject();
            args.put("operation", "ZHIMA_TREE_CLEAN_AND_PUSH");
            args.put("playInfo", ZHIMATREE_PLAY_INFO);
            args.put("refer", ZHIMATREE_REFER);

            JSONObject extInfo = new JSONObject();
            extInfo.put("clickNum", "1");
            extInfo.put("treeCode", treeCode);

            args.put("extInfo", extInfo);

            return RequestManager.requestString("alipay.promoprod.play.trigger",
                    new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 查询做任务赚净化值列表
     */
    public static String queryRentGreenTaskList() {
        try {
            JSONObject args = new JSONObject();
            args.put("operation", "RENT_GREEN_TASK_LIST_QUERY");
            args.put("playInfo", ZHIMATREE_PLAY_INFO);
            args.put("refer", ZHIMATREE_REFER);

            JSONObject extInfo = new JSONObject();
            extInfo.put("chInfo", "ch_share__chsub_ALPContact");
            extInfo.put("batchId", "");
            args.put("extInfo", extInfo);

            return RequestManager.requestString("alipay.promoprod.play.trigger",
                    new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 完成/领取净化值任务
     * @param stageCode "send" 表示去完成/开始, "receive" 表示领取奖励
     */
    public static String rentGreenTaskFinish(String taskId, String stageCode) {
        try {
            JSONObject args = new JSONObject();
            args.put("operation", "RENT_GREEN_TASK_FINISH");
            args.put("playInfo", ZHIMATREE_PLAY_INFO);
            args.put("refer", ZHIMATREE_REFER);

            JSONObject extInfo = new JSONObject();
            extInfo.put("chInfo", "ch_share__chsub_ALPContact");
            extInfo.put("taskId", taskId);
            extInfo.put("stageCode", stageCode);
            args.put("extInfo", extInfo);

            return RequestManager.requestString("alipay.promoprod.play.trigger",
                    new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 信誉获取任务列表（成长任务）
     * <p>
     * 对应抓包：
     * <pre>
     *   Method: com.antgroup.zmxy.zmcustprod.biz.rpc.growthbehavior.apiGrowthBehaviorRpcManager.queryToDoList
     *   requestData: [{"guideBehaviorId":"yuebao_7d","invokeVersion":"1.0.2025.10.27","switchNewPage":true}]
     * </pre>
     * 说明：
     * <ul>
     *   <li>__apiCallStartTime / __apiNativeCallId 属于容器层元数据，不需要拼在 requestData 里</li>
     *   <li>guideBehaviorId 用来指定「引导任务」的入口，通常传 yuebao_7d 即可拉全量列表</li>
     *   <li>invokeVersion 建议保持和抓包一致，方便服务器做灰度控制</li>
     * </ul>
     *
     * @param guideBehaviorId 抓包中的 guideBehaviorId，例如 "yuebao_7d"
     * @param invokeVersion   抓包中的 invokeVersion，例如 "1.0.2025.10.27"
     */
    public static String queryGrowthGuideToDoList(String guideBehaviorId, String invokeVersion) {
        if (guideBehaviorId == null || guideBehaviorId.isEmpty()) {
            guideBehaviorId = "yuebao_7d";
        }
        if (invokeVersion == null || invokeVersion.isEmpty()) {
            // 默认使用抓包中观察到的版本号，避免服务端按版本做限流/灰度
            invokeVersion = "1.0.2025.10.27";
        }
        String data = "[{" +
                "\"guideBehaviorId\":\"" + guideBehaviorId + "\"," +
                "\"invokeVersion\":\"" + invokeVersion + "\"," +
                "\"switchNewPage\":true" +
                "}]";
        return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.growthbehavior.apiGrowthBehaviorRpcManager.queryToDoList",
                data
        );
    }

    /**
     * 信誉任务「领取任务 / 触发接收」接口。
     * <p>
     * 对应抓包：
     * <pre>
     *   Method: com.antgroup.zmxy.zmcustprod.biz.rpc.growthbehavior.apiGrowthBehaviorRpcManager.openBehaviorCollect
     *   requestData: [{"behaviorId":"babanongchang_7d"}]
     * </pre>
     * behaviorId 直接来自 queryToDoList 返回的 toDoList[i].behaviorId。
     */
    public static String openBehaviorCollect(String behaviorId) {
        String data = "[{\"behaviorId\":\"" + behaviorId + "\"}]";
        return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.growthbehavior.apiGrowthBehaviorRpcManager.openBehaviorCollect",
                data
        );
    }

    /**
     * 查询每日答题题目（每日问答）。
     *
     * 对应抓包：
     *   Method: com.antgroup.zmxy.zmcustprod.biz.rpc.growthtask.api.GrowthTaskRpcManager.queryDailyQuiz
     *   requestData: [{"behaviorId":"meiriwenda"}]
     *
     * @param behaviorId 行为 ID（例如 "meiriwenda"）
     */
    public static String queryDailyQuiz(String behaviorId) {
        String data = "[{\"behaviorId\":\"" + behaviorId + "\"}]";
        return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.growthtask.api.GrowthTaskRpcManager.queryDailyQuiz",
                data
        );
    }

    /**
     * 提交每日答题结果。
     *
     * 对应抓包：
     *   Method: com.antgroup.zmxy.zmcustprod.biz.rpc.growthtask.api.GrowthTaskRpcManager.pushDailyTask
     *   requestData: [{
     *       "behaviorId":"meiriwenda",
     *       "bizDate":1764564388751,
     *       "extInfo":{
     *           "answerId":"20250925_3_0",
     *           "answerStatus":"RIGHT",
     *           "questionId":"20250925_3"
     *       }
     *   }]
     *
     * @param behaviorId    行为 ID（meiriwenda）
     * @param bizDate       业务时间戳（直接使用 queryDailyQuiz 返回的 data.bizDate）
     * @param answerId      选中的答案 ID（data.questionVo.rightAnswer.answerId）
     * @param questionId    题目 ID（data.questionVo.questionId）
     * @param answerStatus  答案状态：RIGHT / WRONG
     */
    public static String pushDailyTask(String behaviorId, long bizDate,
                                       String answerId, String questionId,
                                       String answerStatus) {
        if (answerStatus == null || answerStatus.isEmpty()) {
            answerStatus = "RIGHT";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[{\"behaviorId\":\"")
                .append(behaviorId)
                .append("\",\"bizDate\":")
                .append(bizDate)
                .append(",\"extInfo\":{")
                .append("\"answerId\":\"").append(answerId).append("\",")
                .append("\"answerStatus\":\"").append(answerStatus).append("\",")
                .append("\"questionId\":\"").append(questionId).append("\"")
                .append("}}]");
        String data = sb.toString();
        return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.growthtask.api.GrowthTaskRpcManager.pushDailyTask",
                data
        );
    }
}
