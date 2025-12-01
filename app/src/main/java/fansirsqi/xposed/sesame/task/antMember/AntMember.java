package fansirsqi.xposed.sesame.task.antMember;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fansirsqi.xposed.sesame.entity.MemberBenefit;
import fansirsqi.xposed.sesame.model.BaseModel;
import fansirsqi.xposed.sesame.model.ModelFields;
import fansirsqi.xposed.sesame.model.ModelGroup;
import fansirsqi.xposed.sesame.model.modelFieldExt.BooleanModelField;
import fansirsqi.xposed.sesame.model.modelFieldExt.SelectModelField;
import fansirsqi.xposed.sesame.task.ModelTask;
import fansirsqi.xposed.sesame.task.TaskCommon;
import fansirsqi.xposed.sesame.util.GlobalThreadPools;
import fansirsqi.xposed.sesame.util.JsonUtil;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.maps.IdMapManager;
import fansirsqi.xposed.sesame.util.maps.MemberBenefitsMap;
import fansirsqi.xposed.sesame.util.maps.UserMap;
import fansirsqi.xposed.sesame.data.Status;
import fansirsqi.xposed.sesame.util.ResChecker;
import fansirsqi.xposed.sesame.util.TimeUtil;

public class AntMember extends ModelTask {
  private static final String TAG = AntMember.class.getSimpleName();
  @Override
  public String getName() {
    return "会员";
  }
  @Override
  public ModelGroup getGroup() {
    return ModelGroup.MEMBER;
  }
  @Override
  public String getIcon() {
    return "AntMember.png";
  }
  private BooleanModelField memberSign;
  private BooleanModelField memberTask;
  private BooleanModelField memberPointExchangeBenefit;
  private SelectModelField memberPointExchangeBenefitList;
  private BooleanModelField collectSesame;
  private BooleanModelField collectSesameWithOneClick;
  private BooleanModelField sesameTask;
  private BooleanModelField collectInsuredGold;
  private BooleanModelField enableGoldTicket;
  private BooleanModelField enableGameCenter;
  private BooleanModelField merchantSign;
  private BooleanModelField merchantKmdk;
  private BooleanModelField merchantMoreTask;
  private BooleanModelField beanSignIn;
  private BooleanModelField beanExchangeBubbleBoost;
  // 芝麻炼金
  private BooleanModelField sesameAlchemy;
  // 芝麻树
  private BooleanModelField enableZhimaTree;

  @Override
  public ModelFields getFields() {
    ModelFields modelFields = new ModelFields();
    modelFields.addField(memberSign = new BooleanModelField("memberSign", "会员签到", false));
    modelFields.addField(memberTask = new BooleanModelField("memberTask", "会员任务", false));
    modelFields.addField(memberPointExchangeBenefit = new BooleanModelField("memberPointExchangeBenefit", "会员积分 | 兑换权益", false));
    modelFields.addField(memberPointExchangeBenefitList = new SelectModelField("memberPointExchangeBenefitList", "会员积分 | 权益列表", new LinkedHashSet<>(), MemberBenefit.Companion.getList()));
    modelFields.addField(sesameTask = new BooleanModelField("sesameTask", "芝麻信用|芝麻粒信用任务", false));
    modelFields.addField(collectSesame = new BooleanModelField("collectSesame", "芝麻信用|芝麻粒领取", false));
    modelFields.addField(collectSesameWithOneClick = new BooleanModelField("collectSesameWithOneClick", "芝麻信用|芝麻粒领取使用一键收取", false));
    // 芝麻炼金
    modelFields.addField(sesameAlchemy = new BooleanModelField("sesameAlchemy", "芝麻炼金", false));
    // 芝麻树
    modelFields.addField(enableZhimaTree = new BooleanModelField("enableZhimaTree", "芝麻信用|芝麻树", false));
    modelFields.addField(collectInsuredGold = new BooleanModelField("collectInsuredGold", "蚂蚁保|保障金领取", false));
    modelFields.addField(enableGoldTicket = new BooleanModelField("enableGoldTicket", "黄金票签到", false));
    modelFields.addField(enableGameCenter = new BooleanModelField("enableGameCenter", "游戏中心签到", false));
    modelFields.addField(merchantSign = new BooleanModelField("merchantSign", "商家服务|签到", false));
    modelFields.addField(merchantKmdk = new BooleanModelField("merchantKmdk", "商家服务|开门打卡", false));
    modelFields.addField(merchantMoreTask = new BooleanModelField("merchantMoreTask", "商家服务|积分任务", false));
    modelFields.addField(beanSignIn = new BooleanModelField("beanSignIn", "安心豆签到", false));
    modelFields.addField(beanExchangeBubbleBoost = new BooleanModelField("beanExchangeBubbleBoost", "安心豆兑换时光加速器", false));
    return modelFields;
  }

  @Override
  public Boolean check() {
    if (TaskCommon.IS_ENERGY_TIME){
      Log.record(TAG,"⏸ 当前为只收能量时间【"+ BaseModel.getEnergyTime().getValue() +"】，停止执行" + getName() + "任务！");
      return false;
    }else if (TaskCommon.IS_MODULE_SLEEP_TIME) {
      Log.record(TAG,"💤 模块休眠时间【"+ BaseModel.getModelSleepTime().getValue() +"】停止执行" + getName() + "任务！");
      return false;
    } else {
      return true;
    }
  }

  @Override
  protected void runJava() {
    try {
      Log.record(TAG,"执行开始-" + getName());
      if (memberSign.getValue()) {
        doMemberSign();
      }
      if (memberTask.getValue()) {
        doAllMemberAvailableTask();
        handleGrowthGuideTasks();
      }
      if (memberPointExchangeBenefit.getValue()) {
        memberPointExchangeBenefit();
      }
      // 芝麻信用相关检测
      boolean isSesameOpened = checkSesameCanRun();

      if ((sesameTask.getValue() || collectSesame.getValue()) && isSesameOpened) {
        if (sesameTask.getValue()) {
          doAllAvailableSesameTask();
        }
        if (collectSesame.getValue()) {
          collectSesame(collectSesameWithOneClick.getValue());
        }
      }
      if (collectInsuredGold.getValue()) {
        collectInsuredGold();
      }
      if (enableGoldTicket.getValue()) {
        goldTicket();
      }
      if (enableGameCenter.getValue()) {
        enableGameCenter();
      }
      if (beanSignIn.getValue()) {
        beanSignIn();
      }
      if (beanExchangeBubbleBoost.getValue()) {
        beanExchangeBubbleBoost();
      }
      // 芝麻炼金
      if (sesameAlchemy.getValue() && isSesameOpened) {
        doSesameAlchemy();
      }
      // 芝麻树
      if (enableZhimaTree.getValue() && isSesameOpened) {
        doZhimaTree();
      }
      if (merchantSign.getValue() || merchantKmdk.getValue() || merchantMoreTask.getValue()) {
        JSONObject jo = new JSONObject(AntMemberRpcCall.transcodeCheck());
        if (!jo.optBoolean("success")) {
          return;
        }
        JSONObject data = jo.getJSONObject("data");
        if (!data.optBoolean("isOpened")) {
          Log.record(TAG,"商家服务👪未开通");
          return;
        }
        if (merchantKmdk.getValue()) {
          if (TimeUtil.isNowAfterTimeStr("0600") && TimeUtil.isNowBeforeTimeStr("1200")) {
            kmdkSignIn();
          }
          kmdkSignUp();
        }
        if (merchantSign.getValue()) {
          doMerchantSign();
        }
        if (merchantMoreTask.getValue()) {
          doMerchantMoreTask();
        }
      }
    } catch (Throwable t) {
      Log.printStackTrace(TAG, t);
    }finally {
      Log.record(TAG,"执行结束-" + getName());
    }
  }

  private void handleGrowthGuideTasks() {
    try {
      // 目前 guideBehaviorId 取 "yuebao_7d" 即可获取完整 growthGuideList + toDoList 列表
      String resp = AntMemberRpcCall.queryGrowthGuideToDoList("yuebao_7d", "1.0.2025.10.27");
      JSONObject root = new JSONObject(resp);
      if (!root.optBoolean("success")) {
        Log.runtime(TAG + ".handleGrowthGuideTasks", "信誉任务列表获取失败: " + root.optString("resultView", resp));
        return;
      }

      // 1. 成长引导列表（growthGuideList）主要用于前端展示，这里只做统计
      JSONArray growthGuideList = root.optJSONArray("growthGuideList");
      int guideCount = growthGuideList != null ? growthGuideList.length() : 0;

      // 2. toDoList 为真正需要处理的任务（包含每日问答、公益任务等）
      JSONArray toDoList = root.optJSONArray("toDoList");
      int toDoCount = toDoList != null ? toDoList.length() : 0;
      //Log.record(TAG, "信誉任务[成长任务列表] 已获取: growthGuideList=" + guideCount + ", toDoList=" + toDoCount);

      if (toDoList == null || toDoCount == 0) {
        return;
      }

      for (int i = 0; i < toDoList.length(); i++) {
        JSONObject task = toDoList.getJSONObject(i);
        String behaviorId = task.optString("behaviorId");
        String title = task.optString("title");
        String status = task.optString("status"); // wait_receive / wait_doing / ...
        String buttonText = task.optString("buttonText");

        // 2.1 公益类「领任务」示例：蚂蚁庄园 / 蚂蚁森林 / 芭芭农场 等
        //     status = wait_receive 时，通过 openBehaviorCollect 上报「接收任务」，相当于手动点了“领任务”按钮。
        if ("wait_receive".equals(status)) {
          // Log.record(TAG, "信誉任务[自动领取] behaviorId=" + behaviorId + " title=" + title+ " buttonText=" + buttonText + " —— 准备调用 openBehaviorCollect 上报接收");
          String openResp = AntMemberRpcCall.openBehaviorCollect(behaviorId);
          try {
            JSONObject openJo = new JSONObject(openResp);
            if (openJo.optBoolean("success")) {
              Log.other(TAG, "信誉任务[领取成功] "+title);// behaviorId=" + behaviorId + " title=" + title
            } else {
              Log.runtime(TAG + ".handleGrowthGuideTasks", "信誉任务[领取失败] behaviorId=" + behaviorId
                      + " title=" + title + " resp=" + openResp);
            }
          } catch (Throwable parseErr) {
            Log.printStackTrace(TAG + ".handleGrowthGuideTasks.openBehaviorCollect", parseErr);
          }
          // 领取后直接处理下一个任务；具体完成逻辑仍由各自业务（森林/庄园等）模块负责
          continue;
        }

        // 2.2 每日问答：behaviorId = meiriwenda，status = wait_doing
        if ("meiriwenda".equals(behaviorId)) {
          // Log.record(TAG, "信誉任务[每日问答] 尝试自动答题，title=" + title + " status=" + status);
          try {
            // 1）查询当日题目与正确答案
            String quizResp = AntMemberRpcCall.queryDailyQuiz(behaviorId);
            JSONObject quizJo = new JSONObject(quizResp);
            if (!quizJo.optBoolean("success")) {
              Log.runtime(TAG + ".handleGrowthGuideTasks", "每日问答[查询题目失败] resp=" + quizResp);
              continue;
            }
            JSONObject data = quizJo.optJSONObject("data");
            if (data == null) {
              Log.runtime(TAG + ".handleGrowthGuideTasks", "每日问答[缺少data节点] resp=" + quizResp);
              continue;
            }
            long bizDate = data.optLong("bizDate", 0L);
            JSONObject qVo = data.optJSONObject("questionVo");
            if (qVo == null) {
              Log.runtime(TAG + ".handleGrowthGuideTasks", "每日问答[缺少questionVo] resp=" + quizResp);
              continue;
            }
            String questionId = qVo.optString("questionId");
            String questionContent = qVo.optString("questionContent");
            JSONObject rightAnswer = qVo.optJSONObject("rightAnswer");
            if (rightAnswer == null) {
              Log.runtime(TAG + ".handleGrowthGuideTasks", "每日问答[缺少rightAnswer] resp=" + quizResp);
              continue;
            }
            String answerId = rightAnswer.optString("answerId");
            String answerContent = rightAnswer.optString("answerContent");
            if (bizDate <= 0L || questionId.isEmpty() || answerId.isEmpty()) {
              Log.runtime(TAG + ".handleGrowthGuideTasks", "每日问答[关键字段为空] bizDate=" + bizDate
                      + " questionId=" + questionId + " answerId=" + answerId);
              continue;
            }

            // 2）提交答题结果（直接用正确答案，answerStatus = RIGHT）
            String pushResp = AntMemberRpcCall.pushDailyTask(behaviorId, bizDate, answerId, questionId, "RIGHT");
            try {
              JSONObject pushJo = new JSONObject(pushResp);
              if (pushJo.optBoolean("success")) {
                Log.other(TAG, "信誉任务[每日答题成功] " + questionContent
                        + " 答案=" + answerContent + "(" + answerId + ")");
              } else {
                Log.runtime(TAG + ".handleGrowthGuideTasks", "每日问答[提交失败] resp=" + pushResp);
              }
            } catch (Throwable parsePushErr) {
              Log.printStackTrace(TAG + ".handleGrowthGuideTasks.pushDailyTask", parsePushErr);
            }
          } catch (Throwable e) {
            Log.printStackTrace(TAG + ".handleGrowthGuideTasks.meiriwenda", e);
          }
        }
      }
    } catch (Throwable t) {
      Log.printStackTrace(TAG + ".handleGrowthGuideTasks", t);
    }
  }

  /**
   * 会员积分0元兑，权益道具兑换
   */
  private void memberPointExchangeBenefit() {
    try {
      String userId = UserMap.getCurrentUid();
      JSONObject memberInfo = new JSONObject(AntMemberRpcCall.queryMemberInfo());
      if (!ResChecker.checkRes(TAG, memberInfo)) {
        return;
      }
      String pointBalance = memberInfo.getString("pointBalance");
      JSONObject jo = new JSONObject(AntMemberRpcCall.queryShandieEntityList(userId, pointBalance));
      if (!ResChecker.checkRes(TAG, jo)) {
        return;
      }
      if (!jo.has("benefits")) {
        Log.record(TAG,"会员积分[未找到可兑换权益]");
        return;
      }
      JSONArray benefits = jo.getJSONArray("benefits");
      for (int i = 0; i < benefits.length(); i++) {
        JSONObject benefitInfo = benefits.getJSONObject(i);
        JSONObject pricePresentation = benefitInfo.getJSONObject("pricePresentation");
        String name = benefitInfo.getString("name");
        String benefitId = benefitInfo.getString("benefitId");
        IdMapManager.getInstance(MemberBenefitsMap.class).add(benefitId, name);
        if (!Status.canMemberPointExchangeBenefitToday(benefitId)
                || !memberPointExchangeBenefitList.getValue().contains(benefitId)) {
          continue;
        }
        String itemId = benefitInfo.getString("itemId");
        if (exchangeBenefit(benefitId, itemId)) {
          String point = pricePresentation.getString("point");
          Log.other("会员积分🎐兑换[" + name + "]#花费[" + point + "积分]");
        } else {
          Log.other("会员积分🎐兑换[" + name + "]失败！");
        }
      }
      IdMapManager.getInstance(MemberBenefitsMap.class).save(userId);
    } catch (JSONException e) {
      Log.record(TAG,"JSON解析错误: " + e.getMessage());
      Log.printStackTrace(TAG, e);
    } catch (Throwable t) {
      Log.runtime(TAG, "memberPointExchangeBenefit err:");
      Log.printStackTrace(TAG, t);
    }
  }

  private Boolean exchangeBenefit(String benefitId, String itemId) {
    try {
      JSONObject jo = new JSONObject(AntMemberRpcCall.exchangeBenefit(benefitId, itemId));
      if (ResChecker.checkRes(TAG + "会员权益兑换失败:", jo)) {
        Status.memberPointExchangeBenefitToday(benefitId);
        return true;
      }
    } catch (Throwable t) {
      Log.runtime(TAG, "exchangeBenefit err:");
      Log.printStackTrace(TAG, t);
    }
    return false;
  }

  /**
   * 会员签到
   */
  private void doMemberSign() {
    try {
      if (Status.canMemberSignInToday(UserMap.getCurrentUid())) {
        String s = AntMemberRpcCall.queryMemberSigninCalendar();
        GlobalThreadPools.sleepCompat(500);
        JSONObject jo = new JSONObject(s);
        if (ResChecker.checkRes(TAG + "会员签到失败:", jo)) {
          Log.other("会员签到📅[" + jo.getString("signinPoint") + "积分]#已签到" + jo.getString("signinSumDay") + "天");
          Status.memberSignInToday(UserMap.getCurrentUid());
        } else {
          Log.record(jo.getString("resultDesc"));
          Log.runtime(s);
        }
      }
      queryPointCert(1, 8);
    } catch (Throwable t) {
      Log.printStackTrace(TAG, t);
    }
  }

  /**
   * 会员任务-逛一逛
   * 单次执行 1
   */
  private void doAllMemberAvailableTask() {
    try {
      String str = AntMemberRpcCall.queryAllStatusTaskList();
      GlobalThreadPools.sleepCompat(500);
      JSONObject jsonObject = new JSONObject(str);
      if (!ResChecker.checkRes(TAG, jsonObject)) {
        Log.error(TAG + ".doAllMemberAvailableTask", "会员任务响应失败: " + jsonObject.getString("resultDesc"));
        return;
      }
      if (!jsonObject.has("availableTaskList")) {
        return;
      }
      JSONArray taskList = jsonObject.getJSONArray("availableTaskList");
      for (int j = 0; j < taskList.length(); j++) {
        JSONObject task = taskList.getJSONObject(j);
        processTask(task);
      }
    } catch (Throwable t) {
      Log.runtime(TAG, "doAllMemberAvailableTask err:");
      Log.printStackTrace(TAG, t);
    }
  }

  /**
   * 会员积分收取
   * @param page 第几页
   * @param pageSize 每页数据条数
   */
  private static void queryPointCert(int page, int pageSize) {
    try {
      String s = AntMemberRpcCall.queryPointCert(page, pageSize);
      GlobalThreadPools.sleepCompat(500);
      JSONObject jo = new JSONObject(s);
      if (ResChecker.checkRes(TAG + "查询会员积分证书失败:", jo)) {
        boolean hasNextPage = jo.getBoolean("hasNextPage");
        JSONArray jaCertList = jo.getJSONArray("certList");
        for (int i = 0; i < jaCertList.length(); i++) {
          jo = jaCertList.getJSONObject(i);
          String bizTitle = jo.getString("bizTitle");
          String id = jo.getString("id");
          int pointAmount = jo.getInt("pointAmount");
          s = AntMemberRpcCall.receivePointByUser(id);
          jo = new JSONObject(s);
          if (ResChecker.checkRes(TAG + "会员积分领取失败:", jo)) {
            Log.other("会员积分🎖️[领取" + bizTitle + "]#" + pointAmount + "积分");
          } else {
            Log.record(jo.getString("resultDesc"));
            Log.runtime(s);
          }
        }
        if (hasNextPage) {
          queryPointCert(page + 1, pageSize);
        }
      } else {
        Log.record(jo.getString("resultDesc"));
        Log.runtime(s);
      }
    } catch (Throwable t) {
      Log.runtime(TAG, "queryPointCert err:");
      Log.printStackTrace(TAG, t);
    }
  }

  /**
   * 检查是否满足运行芝麻信用任务的条件
   * @return bool
   */
  private static Boolean checkSesameCanRun() {
    try {
      String s = AntMemberRpcCall.queryHome();
      JSONObject jo = new JSONObject(s);
      if (!jo.optBoolean("success")) {
        Log.other(TAG, "芝麻信用💳[首页响应失败]#" + jo.optString("errorMsg"));
        Log.error(TAG + ".checkSesameCanRun.queryHome", "芝麻信用💳[首页响应失败]#" + s);
        return false;
      }
      JSONObject entrance = jo.getJSONObject("entrance");
      if (!entrance.optBoolean("openApp")) {
        Log.other("芝麻信用💳[未开通芝麻信用]");
        return false;
      }
      return true;
    } catch (Throwable t) {
      Log.printStackTrace(TAG + ".checkSesameCanRun", t);
      return false;
    }
  }

  /**
   * 芝麻信用任务 - 重构版本
   */
  private void doAllAvailableSesameTask() {
    try {
      String s = AntMemberRpcCall.queryAvailableSesameTask();
      GlobalThreadPools.sleepCompat(500);
      JSONObject jo = new JSONObject(s);
      if (jo.has("resData")) {
        jo = jo.getJSONObject("resData");
      }
      if (!jo.optBoolean("success")) {
        Log.other(TAG, "芝麻信用💳[查询任务响应失败]#" + jo.getString("resultCode"));
        Log.error(TAG + ".doAllAvailableSesameTask.queryAvailableSesameTask", "芝麻信用💳[查询任务响应失败]#" + s);
        return;
      }

     // Log.record(TAG, "芝麻信用💳[查询任务响应]#" + s);

      JSONObject taskObj = jo.getJSONObject("data");
      int totalTasks = 0;
      int completedTasks = 0;
      int skippedTasks = 0;

      // 处理日常任务
      if (taskObj.has("dailyTaskListVO")) {
        JSONObject dailyTaskListVO = taskObj.getJSONObject("dailyTaskListVO");

        if (dailyTaskListVO.has("waitCompleteTaskVOS")) {
          JSONArray waitCompleteTaskVOS = dailyTaskListVO.getJSONArray("waitCompleteTaskVOS");
          totalTasks += waitCompleteTaskVOS.length();
          Log.record(TAG, "芝麻信用💳[待完成任务]#开始处理(" + waitCompleteTaskVOS.length() + "个)");
          int[] results = joinAndFinishSesameTaskWithResult(waitCompleteTaskVOS);
          completedTasks += results[0];
          skippedTasks += results[1];
        }

        if (dailyTaskListVO.has("waitJoinTaskVOS")) {
          JSONArray waitJoinTaskVOS = dailyTaskListVO.getJSONArray("waitJoinTaskVOS");
          totalTasks += waitJoinTaskVOS.length();
          Log.record(TAG, "芝麻信用💳[待加入任务]#开始处理(" + waitJoinTaskVOS.length() + "个)");
          int[] results = joinAndFinishSesameTaskWithResult(waitJoinTaskVOS);
          completedTasks += results[0];
          skippedTasks += results[1];
        }
      }

      // 处理toCompleteVOS任务
      if (taskObj.has("toCompleteVOS")) {
        JSONArray toCompleteVOS = taskObj.getJSONArray("toCompleteVOS");
        totalTasks += toCompleteVOS.length();
        Log.record(TAG, "芝麻信用💳[toCompleteVOS任务]#开始处理(" + toCompleteVOS.length() + "个)");
        int[] results = joinAndFinishSesameTaskWithResult(toCompleteVOS);
        completedTasks += results[0];
        skippedTasks += results[1];
      }

      // 统计结果并决定是否关闭开关
      Log.record(TAG, "芝麻信用💳[任务处理完成]#总任务:" + totalTasks + "个, 完成:" + completedTasks + "个, 跳过:" + skippedTasks + "个");
      
      // 如果所有任务都已完成或跳过（没有剩余可完成任务），关闭开关
      if (totalTasks > 0 && (completedTasks + skippedTasks) >= totalTasks) {
        sesameTask.setValue(false);
        Log.record(TAG, "芝麻信用💳[已全部完成任务，临时关闭]");
      }
    } catch (Throwable t) {
      Log.printStackTrace(TAG + ".doAllAvailableSesameTask", t);
    }
  }

  /**
   * 不能完成的任务黑名单（根据title关键词匹配）
   */
  private static final String[] TASK_BLACKLIST = {
          "每日施肥领水果",           // 需要淘宝操作
          "坚持种水果",              // 需要淘宝操作
          "坚持去玩休闲小游戏",       // 需要游戏操作
          "去AQapp提问",            // 需要下载APP
          "去AQ提问",               // 需要下载APP
          "坚持看直播领福利",        // 需要淘宝直播
          "去淘金币逛一逛",          // 需要淘宝操作
          "坚持攒保障金",            // 参数错误：promiseActivityExtCheck
          "芝麻租赁下单得芝麻粒",     // 需要租赁操作
          "去玩小游戏",              // 参数错误：promiseActivityExtCheck
          "浏览租赁商家小程序",       // 需要小程序操作
          "订阅小组件",              // 参数错误：promiseActivityExtCheck
          "租1笔图书",               // 参数错误：promiseActivityExtCheck
          "去订阅芝麻小组件",         // 参数错误：promiseActivityExtCheck
          "坚持攒保障"               // 参数错误：promiseActivityExtCheck（与"坚持攒保障金"类似，防止匹配遗漏）
  };

  /**
   * 检查任务是否在黑名单中
   * @param taskTitle 任务标题
   * @return true表示在黑名单中，应该跳过
   */
  private static boolean isTaskInBlacklist(String taskTitle) {
    if (taskTitle == null) return false;
    for (String blacklistItem : TASK_BLACKLIST) {
      if (taskTitle.contains(blacklistItem)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 芝麻信用-领取并完成任务（带结果统计）
   * @param taskList 任务列表
   * @return int数组 [完成数量, 跳过数量]
   * @throws JSONException JSON解析异常，上抛处理
   */
  private static int[] joinAndFinishSesameTaskWithResult(JSONArray taskList) throws JSONException {
    int completedCount = 0;
    int skippedCount = 0;
    
    for (int i = 0; i < taskList.length(); i++) {
      JSONObject task = taskList.getJSONObject(i);
      String taskTitle = task.has("title") ? task.getString("title") : "未知任务";
      
      // 打印任务状态信息用于调试
      boolean finishFlag = task.optBoolean("finishFlag", false);
      String actionText = task.optString("actionText", "");
    //  Log.record(TAG, "芝麻信用💳[任务状态调试]#" + taskTitle + " - finishFlag:" + finishFlag + ", actionText:" + actionText);
      
      // 检查任务是否已完成
      if (finishFlag || "已完成".equals(actionText)) {
        Log.record(TAG, "芝麻信用💳[跳过已完成任务]#" + taskTitle);
        skippedCount++;
        continue;
      }
      
      // 检查黑名单
      if (isTaskInBlacklist(taskTitle)) {
        Log.record(TAG, "芝麻信用💳[跳过黑名单任务]#" + taskTitle);
        skippedCount++;
        continue;
      }
      
      // 添加检查，确保templateId存在
      if (!task.has("templateId")) {
        Log.record(TAG, "芝麻信用💳[跳过缺少templateId任务]#" + taskTitle);
        skippedCount++;
        continue;
      }
      
      String taskTemplateId = task.getString("templateId");
      int needCompleteNum = task.has("needCompleteNum") ? task.getInt("needCompleteNum") : 1;
      int completedNum = task.optInt("completedNum", 0);
      String s;
      String recordId;
      JSONObject responseObj;


      if (task.has("actionUrl") && task.getString("actionUrl").contains("jumpAction")) {
        // 跳转APP任务 依赖跳转的APP发送请求鉴别任务完成 仅靠hook支付宝无法完成
        Log.record(TAG, "芝麻信用💳[跳过跳转APP任务]#" + taskTitle);
        skippedCount++;
        continue;
      }
      
      boolean taskCompleted = false;
      if (!task.has("todayFinish")) {
        // 领取任务
        s = AntMemberRpcCall.joinSesameTask(taskTemplateId);
        GlobalThreadPools.sleepCompat(200);
        responseObj = new JSONObject(s);
        if (!responseObj.optBoolean("success")) {
          Log.other(TAG, "芝麻信用💳[领取任务" + taskTitle + "失败]#" + s);
          skippedCount++;
          continue;
        }
        recordId = responseObj.getJSONObject("data").getString("recordId");
      } else {
        if (!task.has("recordId")) {
          Log.other(TAG, "芝麻信用💳[任务" + taskTitle + "未获取到recordId]#" + task);
          skippedCount++;
          continue;
        }
        recordId = task.getString("recordId");
      }

      // 完成任务
      for (int j = completedNum; j < needCompleteNum; j++) {
        s = AntMemberRpcCall.finishSesameTask(recordId);
        GlobalThreadPools.sleepCompat(200);
        responseObj = new JSONObject(s);
        if (responseObj.optBoolean("success")) {
          Log.record(TAG, "芝麻信用💳[完成任务" + taskTitle + "]#(" + (j + 1) + "/" + needCompleteNum + "天)");
          taskCompleted = true;
        } else {
          Log.other(TAG, "芝麻信用💳[完成任务" + taskTitle + "失败]#" + s);
          break;
        }
      }
      
      if (taskCompleted) {
        completedCount++;
      } else {
        skippedCount++;
      }
    }
    
    return new int[]{completedCount, skippedCount};
  }

  /**
   * 芝麻粒收取
   * @param withOneClick 启用一键收取
   */
  private void collectSesame(Boolean withOneClick) {
    try {
      JSONObject jo = new JSONObject(AntMemberRpcCall.queryCreditFeedback());
      GlobalThreadPools.sleepCompat(500);
      if (!jo.optBoolean("success")) {
        Log.other(TAG, "芝麻信用💳[查询未领取芝麻粒响应失败]#" + jo.getString("resultView"));
        Log.error(TAG + ".collectSesame.queryCreditFeedback", "芝麻信用💳[查询未领取芝麻粒响应失败]#" + jo);
        return;
      }
      JSONArray availableCollectList = jo.getJSONArray("creditFeedbackVOS");
      if (withOneClick) {
        GlobalThreadPools.sleepCompat(2000);
        jo = new JSONObject(AntMemberRpcCall.collectAllCreditFeedback());
        GlobalThreadPools.sleepCompat(2000);
        if (!jo.optBoolean("success")) {
          Log.other(TAG, "芝麻信用💳[一键收取芝麻粒响应失败]#" + jo);
          Log.error(TAG + ".collectSesame.collectAllCreditFeedback", "芝麻信用💳[一键收取芝麻粒响应失败]#" + jo);
          return;
        }
      }
      for (int i = 0; i < availableCollectList.length(); i++) {
        jo = availableCollectList.getJSONObject(i);
        if (!"UNCLAIMED".equals(jo.getString("status"))) {
          continue;
        }
        String title = jo.getString("title");
        String creditFeedbackId = jo.getString("creditFeedbackId");
        String potentialSize = jo.getString("potentialSize");
        if (!withOneClick) {
          jo = new JSONObject(AntMemberRpcCall.collectCreditFeedback(creditFeedbackId));
          GlobalThreadPools.sleepCompat(2000);
          if (!jo.optBoolean("success")) {
            Log.other(TAG, "芝麻信用💳[查询未领取芝麻粒响应失败]#" + jo.getString("resultView"));
            Log.error(TAG + ".collectSesame.collectCreditFeedback", "芝麻信用💳[收取芝麻粒响应失败]#" + jo);
            continue;
          }
        }
        Log.other("芝麻信用💳[" + title + "]#" + potentialSize + "粒" + (withOneClick ? "(一键收取)" : ""));
      }
    } catch (Throwable t) {
      Log.printStackTrace(TAG + ".collectSesame", t);
    }
  }

  /**
   * 商家开门打卡签到
   */
  private static void kmdkSignIn() {
    try {
      String s = AntMemberRpcCall.queryActivity();
      JSONObject jo = new JSONObject(s);
      if (jo.optBoolean("success")) {
        if ("SIGN_IN_ENABLE".equals(jo.getString("signInStatus"))) {
          String activityNo = jo.getString("activityNo");
          JSONObject joSignIn = new JSONObject(AntMemberRpcCall.signIn(activityNo));
          if (joSignIn.optBoolean("success")) {
            Log.other("商家服务🏬[开门打卡签到成功]");
          } else {
            Log.record(joSignIn.getString("errorMsg"));
            Log.runtime(joSignIn.toString());
          }
        }
      } else {
        Log.record(TAG,"queryActivity" + " " + s);
      }
    } catch (Throwable t) {
      Log.runtime(TAG, "kmdkSignIn err:");
      Log.printStackTrace(TAG, t);
    }
  }

  /**
   * 商家开门打卡报名
   */
  private static void kmdkSignUp() {
    try {
      for (int i = 0; i < 5; i++) {
        JSONObject jo = new JSONObject(AntMemberRpcCall.queryActivity());
        if (jo.optBoolean("success")) {
          String activityNo = jo.getString("activityNo");
          if (!TimeUtil.getFormatDate().replace("-", "").equals(activityNo.split("_")[2])) {
            break;
          }
          if ("SIGN_UP".equals(jo.getString("signUpStatus"))) {
            break;
          }
          if ("UN_SIGN_UP".equals(jo.getString("signUpStatus"))) {
            String activityPeriodName = jo.getString("activityPeriodName");
            JSONObject joSignUp = new JSONObject(AntMemberRpcCall.signUp(activityNo));
            if (joSignUp.optBoolean("success")) {
              Log.other("商家服务🏬[" + activityPeriodName + "开门打卡报名]");
              return;
            } else {
              Log.record(joSignUp.getString("errorMsg"));
              Log.runtime(joSignUp.toString());
            }
          }
        } else {
          Log.record(TAG,"queryActivity");
          Log.runtime(jo.toString());
        }
        GlobalThreadPools.sleepCompat(500);
      }
    } catch (Throwable t) {
      Log.runtime(TAG, "kmdkSignUp err:");
      Log.printStackTrace(TAG, t);
    }
  }

  /**
   * 商家积分签到
   */
  private static void doMerchantSign() {
    try {
      String s = AntMemberRpcCall.merchantSign();
      JSONObject jo = new JSONObject(s);
      if (!jo.optBoolean("success")) {
        Log.runtime(TAG, "doMerchantSign err:" + s);
        return;
      }
      jo = jo.getJSONObject("data");
      String signResult = jo.getString("signInResult");
      String reward = jo.getString("todayReward");
      if ("SUCCESS".equals(signResult)) {
        Log.other("商家服务🏬[每日签到]#获得积分" + reward);
      } else {
        Log.record(s);
        Log.runtime(s);
      }
    } catch (Throwable t) {
      Log.runtime(TAG, "kmdkSignIn err:");
      Log.printStackTrace(TAG, t);
    }
  }

  /**
   * 商家积分任务
   */
  private static void doMerchantMoreTask() {
    String s = AntMemberRpcCall.taskListQuery();
    try {
      boolean doubleCheck = false;
      JSONObject jo = new JSONObject(s);
      if (jo.optBoolean("success")) {
        JSONArray taskList = jo.getJSONObject("data").getJSONArray("taskList");
        for (int i = 0; i < taskList.length(); i++) {
          JSONObject task = taskList.getJSONObject(i);
          if (!task.has("status")) {
            continue;
          }
          String title = task.getString("title");
          String reward = task.getString("reward");
          String taskStatus = task.getString("status");
          if ("NEED_RECEIVE".equals(taskStatus)) {
            if (task.has("pointBallId")) {
              jo = new JSONObject(AntMemberRpcCall.ballReceive(task.getString("pointBallId")));
              if (jo.optBoolean("success")) {
                Log.other("商家服务🏬[" + title + "]#领取积分" + reward);
              }
            }
          } else if ("PROCESSING".equals(taskStatus) || "UNRECEIVED".equals(taskStatus)) {
            if (task.has("extendLog")) {
              JSONObject bizExtMap = task.getJSONObject("extendLog").getJSONObject("bizExtMap");
              jo = new JSONObject(AntMemberRpcCall.taskFinish(bizExtMap.getString("bizId")));
              if (jo.optBoolean("success")) {
                Log.other("商家服务🏬[" + title + "]#领取积分" + reward);
              }
              doubleCheck = true;
            } else {
              String taskCode = task.getString("taskCode");
              switch (taskCode) {
                case "SYH_CPC_DYNAMIC":
                  // 逛一逛商品橱窗
                  taskReceive(taskCode, "SYH_CPC_DYNAMIC_VIEWED", title);
                  break;
                case "JFLLRW_TASK":
                  // 逛一逛得缴费红包
                  taskReceive(taskCode, "JFLL_VIEWED", title);
                  break;
                case "ZFBHYLLRW_TASK":
                  // 逛一逛支付宝会员
                  taskReceive(taskCode, "ZFBHYLL_VIEWED", title);
                  break;
                case "QQKLLRW_TASK":
                  // 逛一逛支付宝亲情卡
                  taskReceive(taskCode, "QQKLL_VIEWED", title);
                  break;
                case "SSLLRW_TASK":
                  // 逛逛领优惠得红包
                  taskReceive(taskCode, "SSLL_VIEWED", title);
                  break;
                case "ELMGYLLRW2_TASK":
                  // 去饿了么果园0元领水果
                  taskReceive(taskCode, "ELMGYLL_VIEWED", title);
                  break;
                case "ZMXYLLRW_TASK":
                  // 去逛逛芝麻攒粒攻略
                  taskReceive(taskCode, "ZMXYLL_VIEWED", title);
                  break;
                case "GXYKPDDYH_TASK":
                  // 逛信用卡频道得优惠
                  taskReceive(taskCode, "xykhkzd_VIEWED", title);
                  break;
                case "HHKLLRW_TASK":
                  // 49999元花呗红包集卡抽
                  taskReceive(taskCode, "HHKLLX_VIEWED", title);
                  break;
                case "TBNCLLRW_TASK":
                  // 去淘宝芭芭农场领水果百货
                  taskReceive(taskCode, "TBNCLLRW_TASK_VIEWED", title);
                  break;
              }
            }
          }
        }
        if (doubleCheck) {
          doMerchantMoreTask();
        }
      } else {
        Log.runtime(TAG,"taskListQuery err:" + " " + s);
      }
    } catch (Throwable t) {
      Log.runtime(TAG, "taskListQuery err:");
      Log.printStackTrace(TAG, t);
    } finally {
      try {
        GlobalThreadPools.sleepCompat(1000);
      } catch (Exception e) {
        Log.printStackTrace(e);
      }
    }
  }

  /**
   * 完成商家积分任务
   * @param taskCode 任务代码
   * @param actionCode 行为代码
   * @param title 标题
   */
  private static void taskReceive(String taskCode, String actionCode, String title) {
    try {
      String s = AntMemberRpcCall.taskReceive(taskCode);
      JSONObject jo = new JSONObject(s);
      if (jo.optBoolean("success")) {
        GlobalThreadPools.sleepCompat(500);
        jo = new JSONObject(AntMemberRpcCall.actioncode(actionCode));
        if (jo.optBoolean("success")) {
          GlobalThreadPools.sleepCompat(16000);
          jo = new JSONObject(AntMemberRpcCall.produce(actionCode));
          if (jo.optBoolean("success")) {
            Log.other("商家服务🏬[完成任务" + title + "]");
          }
        }
      } else {
        Log.record(TAG,"taskReceive" + " " + s);
      }
    } catch (Throwable t) {
      Log.runtime(TAG, "taskReceive err:");
      Log.printStackTrace(TAG, t);
    }
  }

  /**
   * 保障金领取
   */
  private void collectInsuredGold() {
    try {
      String s = AntMemberRpcCall.queryAvailableCollectInsuredGold();
      GlobalThreadPools.sleepCompat(200);
      JSONObject jo = new JSONObject(s);
      if (!jo.optBoolean("success")) {
        Log.other(TAG + ".collectInsuredGold.queryInsuredHome", "保障金🏥[响应失败]#" + s);
        return;
      }
      jo = jo.getJSONObject("data");
      JSONObject signInBall = jo.getJSONObject("signInDTO");
      JSONArray otherBallList = jo.getJSONArray("eventToWaitDTOList");
      if (1 == signInBall.getInt("sendFlowStatus") && 1 == signInBall.getInt("sendType")) {
        s = AntMemberRpcCall.collectInsuredGold(signInBall);
        GlobalThreadPools.sleepCompat(2000);
        jo = new JSONObject(s);
        if (!jo.optBoolean("success")) {
          Log.other(TAG + ".collectInsuredGold.collectInsuredGold", "保障金🏥[响应失败]#" + s);
          return;
        }
        String gainGold = jo.getJSONObject("data").getString("gainSumInsuredYuan");
        Log.other("保障金🏥[领取保证金]#+" + gainGold + "元");
      }
      for (int i = 0; i <otherBallList.length(); i++) {
        JSONObject anotherBall = otherBallList.getJSONObject(i);
        s = AntMemberRpcCall.collectInsuredGold(anotherBall);
        GlobalThreadPools.sleepCompat(2000);
        jo = new JSONObject(s);
        if (!jo.optBoolean("success")) {
          Log.other(TAG + ".collectInsuredGold.collectInsuredGold", "保障金🏥[响应失败]#" + s);
          return;
        }
        String gainGold = jo.getJSONObject("data").getJSONObject("gainSumInsuredDTO").getString("gainSumInsuredYuan");
        Log.other("保障金🏥[领取保证金]+" + gainGold + "元");
      }
    } catch (Throwable t) {
      Log.printStackTrace(TAG + ".collectInsuredGold", t);
    }
  }

  /**
   * 执行会员任务 类型1
   * @param task 单个任务对象
   */
  private void processTask(JSONObject task) throws JSONException {
    JSONObject taskConfigInfo = task.getJSONObject("taskConfigInfo");
    String name = taskConfigInfo.getString("name");
    long id = taskConfigInfo.getLong("id");
    String awardParamPoint = taskConfigInfo.getJSONObject("awardParam").getString("awardParamPoint");
    String targetBusiness = taskConfigInfo.getJSONArray("targetBusiness").getString(0);
    String[] targetBusinessArray = targetBusiness.split("#");
    if (targetBusinessArray.length < 3) {
      Log.runtime(TAG, "processTask target param err:" + Arrays.toString(targetBusinessArray));
      return;
    }
    String bizType = targetBusinessArray[0];
    String bizSubType = targetBusinessArray[1];
    String bizParam = targetBusinessArray[2];
    GlobalThreadPools.sleepCompat(16000);
    String str = AntMemberRpcCall.executeTask(bizParam, bizSubType, bizType, id);
    JSONObject jo = new JSONObject(str);
    if (!ResChecker.checkRes(TAG + "执行会员任务失败:", jo)) {
      Log.runtime(TAG, "执行任务失败:" + jo.optString("resultDesc"));
      return;
    }
    if (checkMemberTaskFinished(id)) {
      Log.other("会员任务🎖️[" + name + "]#获得积分" + awardParamPoint);
    }
  }

  /**
   * 查询指定会员任务是否完成
   * @param taskId 任务id
   */
  private boolean checkMemberTaskFinished(long taskId) {
    try {
      String str = AntMemberRpcCall.queryAllStatusTaskList();
      GlobalThreadPools.sleepCompat(500);
      JSONObject jsonObject = new JSONObject(str);
      if (!ResChecker.checkRes(TAG + "查询会员任务状态失败:", jsonObject)) {
        Log.error(TAG + ".checkMemberTaskFinished", "会员任务响应失败: " + jsonObject.getString("resultDesc"));
      }
      if (!jsonObject.has("availableTaskList")) {
        return true;
      }
      JSONArray taskList = jsonObject.getJSONArray("availableTaskList");
      for (int i = 0; i < taskList.length(); i++) {
        JSONObject taskConfigInfo = taskList.getJSONObject(i).getJSONObject("taskConfigInfo");
        long id = taskConfigInfo.getLong("id");
        if (taskId == id) {
          return false;
        }
      }
      return true;
    } catch (JSONException e) {
      return false;
    }
  }

  public void kbMember() {
    try {
      if (!Status.canKbSignInToday()) {
        return;
      }
      String s = AntMemberRpcCall.rpcCall_signIn();
      JSONObject jo = new JSONObject(s);
      if (jo.optBoolean("success", false)) {
        jo = jo.getJSONObject("data");
        Log.other("口碑签到📅[第" + jo.getString("dayNo") + "天]#获得" + jo.getString("value") + "积分");
        Status.KbSignInToday();
      } else if (s.contains("\"HAS_SIGN_IN\"")) {
        Status.KbSignInToday();
      } else {
        Log.runtime(TAG, jo.getString("errorMessage"));
      }
    } catch (Throwable t) {
      Log.runtime(TAG, "signIn err:");
      Log.printStackTrace(TAG, t);
    }
  }

  private void goldTicket() {
    try {
      // 签到
      goldBillCollect("\"campId\":\"CP1417744\",\"directModeDisableCollect\":true,\"from\":\"antfarm\",");
      // 收取其他
      goldBillCollect("");
    } catch (Throwable t) {
      Log.printStackTrace(TAG, t);
    }
  }

  /** 收取黄金票 */
  private void goldBillCollect(String signInfo) {
    try {
      String str = AntMemberRpcCall.goldBillCollect(signInfo);
      JSONObject jsonObject = new JSONObject(str);
      if (!jsonObject.optBoolean("success")) {
        Log.runtime(TAG + ".goldBillCollect.goldBillCollect", jsonObject.optString("resultDesc"));
        return;
      }
      JSONObject object = jsonObject.getJSONObject("result");
      JSONArray jsonArray = object.getJSONArray("collectedList");
      int length = jsonArray.length();
      if (length == 0) {
        return;
      }
      for (int i = 0; i < length; i++) {
        Log.other("黄金票🙈[" + jsonArray.getString(i) + "]");
      }
      Log.other("黄金票🏦本次总共获得[" + JsonUtil.getValueByPath(object, "collectedCamp.amount") + "]");
    } catch (Throwable th) {
      Log.runtime(TAG, "signIn err:");
      Log.printStackTrace(TAG, th);
    }
  }

  private void enableGameCenter() {
    try {
      try {
        String str = AntMemberRpcCall.querySignInBall();
        JSONObject jsonObject = new JSONObject(str);
        if (!jsonObject.optBoolean("success")) {
          Log.runtime(TAG + ".signIn.querySignInBall", jsonObject.optString("resultDesc"));
          return;
        }
        str = JsonUtil.getValueByPath(jsonObject, "data.signInBallModule.signInStatus");
        if (String.valueOf(true).equals(str)) {
          return;
        }
        str = AntMemberRpcCall.continueSignIn();
        GlobalThreadPools.sleepCompat(300);
        jsonObject = new JSONObject(str);
        if (!jsonObject.optBoolean("success")) {
          Log.runtime(TAG + ".signIn.continueSignIn", jsonObject.optString("resultDesc"));
          return;
        }
        Log.other("游戏中心🎮签到成功");
      } catch (Throwable th) {
        Log.runtime(TAG, "signIn err:");
        Log.printStackTrace(TAG, th);
      }
      try {
        String str = AntMemberRpcCall.queryPointBallList();
        JSONObject jsonObject = new JSONObject(str);
        if (!jsonObject.optBoolean("success")) {
          Log.runtime(TAG + ".batchReceive.queryPointBallList", jsonObject.optString("resultDesc"));
          return;
        }
        JSONArray jsonArray = (JSONArray) JsonUtil.getValueByPathObject(jsonObject, "data.pointBallList");
        if (jsonArray == null || jsonArray.length() == 0) {
          return;
        }
        str = AntMemberRpcCall.batchReceivePointBall();
        GlobalThreadPools.sleepCompat(300);
        jsonObject = new JSONObject(str);
        if (jsonObject.optBoolean("success")) {
          Log.other("游戏中心🎮全部领取成功[" + JsonUtil.getValueByPath(jsonObject, "data.totalAmount") + "]乐豆");
        } else {
          Log.runtime(TAG + ".batchReceive.batchReceivePointBall", jsonObject.optString("resultDesc"));
        }
      } catch (Throwable th) {
        Log.runtime(TAG, "batchReceive err:");
        Log.printStackTrace(TAG, th);
      }
    } catch (Throwable t) {
      Log.printStackTrace(TAG, t);
    }
  }

  private void beanSignIn() {
    try {
      try {
        String signInProcessStr = AntMemberRpcCall.querySignInProcess("AP16242232", "INS_BLUE_BEAN_SIGN");

        JSONObject jo = new JSONObject(signInProcessStr);
        if (!jo.optBoolean("success")) {
          Log.runtime(jo.toString());
          return;
        }

        if (jo.getJSONObject("result").getBoolean("canPush")) {
          String signInTriggerStr = AntMemberRpcCall.signInTrigger("AP16242232", "INS_BLUE_BEAN_SIGN");

          jo = new JSONObject(signInTriggerStr);
          if (jo.optBoolean("success")) {
            String prizeName = jo.getJSONObject("result").getJSONArray("prizeSendOrderDTOList").getJSONObject(0).getString("prizeName");
            Log.record(TAG,"安心豆🫘[" + prizeName + "]");
          } else {
            Log.runtime(jo.toString());
          }
        }
      } catch (NullPointerException e) {
        Log.error(TAG, "安心豆🫘[RPC桥接失败]#可能是RpcBridge未初始化");
        Log.printStackTrace(TAG, e);
      }
    } catch (Throwable t) {
      Log.runtime(TAG, "beanSignIn err:");
      Log.printStackTrace(TAG, t);
    }
  }

  private void beanExchangeBubbleBoost() {
    try {
      // 检查RPC调用是否可用
      try {
        String accountInfo = AntMemberRpcCall.queryUserAccountInfo("INS_BLUE_BEAN");

        JSONObject jo = new JSONObject(accountInfo);
        if (!jo.optBoolean("success")) {
          Log.runtime(jo.toString());
          return;
        }

        int userCurrentPoint = jo.getJSONObject("result").getInt("userCurrentPoint");

        // 检查beanExchangeDetail调用
        String exchangeDetailStr = AntMemberRpcCall.beanExchangeDetail("IT20230214000700069722");

        jo = new JSONObject(exchangeDetailStr);
        if (!jo.optBoolean("success")) {
          Log.runtime(jo.toString());
          return;
        }

        jo = jo.getJSONObject("result").getJSONObject("rspContext").getJSONObject("params").getJSONObject("exchangeDetail");
        String itemId = jo.getString("itemId");
        String itemName = jo.getString("itemName");
        jo = jo.getJSONObject("itemExchangeConsultDTO");
        int realConsumePointAmount = jo.getInt("realConsumePointAmount");

        if (!jo.getBoolean("canExchange") || realConsumePointAmount > userCurrentPoint) {
          return;
        }

        String exchangeResult = AntMemberRpcCall.beanExchange(itemId, realConsumePointAmount);

        jo = new JSONObject(exchangeResult);
        if (jo.optBoolean("success")) {
          Log.record(TAG,"安心豆🫘[兑换:" + itemName + "]");
        } else {
          Log.runtime(jo.toString());
        }
      } catch (NullPointerException e) {
        Log.error(TAG, "安心豆🫘[RPC桥接失败]#可能是RpcBridge未初始化");
        Log.printStackTrace(TAG, e);
      }
    } catch (Throwable t) {
      Log.runtime(TAG, "beanExchangeBubbleBoost err:");
      Log.printStackTrace(TAG, t);
    }
  }

  /**
   * 芝麻炼金 - 优化版
   */
  private void doSesameAlchemy() {
    try {
      Log.record(TAG, "开始执行芝麻炼金⚗️");

      // ================= Step 1: 自动炼金 (消耗芝麻粒升级) =================
      String homeRes = AntMemberRpcCall.alchemyQueryHome();
      JSONObject homeJo = new JSONObject(homeRes);
      if (homeJo.optBoolean("success")) {
        JSONObject data = homeJo.optJSONObject("data");
        if (data != null) {
          int zmlBalance = data.optInt("zmlBalance", 0);      // 当前芝麻粒
          int cost = data.optInt("alchemyCostZml", 5);        // 单次消耗
          boolean capReached = data.optBoolean("capReached", false); // 是否达到上限
          int currentLevel = data.optInt("currentLevel", 0);

          // 循环炼金逻辑
          while (zmlBalance >= cost && !capReached) {
            GlobalThreadPools.sleepCompat(1500);
            String alchemyRes = AntMemberRpcCall.alchemyExecute();
            JSONObject alchemyJo = new JSONObject(alchemyRes);

            if (alchemyJo.optBoolean("success")) {
              JSONObject alData = alchemyJo.optJSONObject("data");
              if (alData != null) {
                int goldNum = alData.optInt("goldNum", 0);
                Log.other("芝麻炼金⚗️[炼金成功]#消耗" + cost + "粒 | 获得" + goldNum + "金 |当前等级Lv." + currentLevel);
                zmlBalance -= cost;
              } else {
                break;
              }
            } else {
              Log.record(TAG, "芝麻炼金失败: " + alchemyJo.optString("resultView"));
              break;
            }
          }
        }
      } else {
        Log.record(TAG, "芝麻炼金首页查询失败");
      }

      // ================= Step 2: 自动签到 & 时段奖励 =================
      String checkInRes = AntMemberRpcCall.alchemyQueryCheckIn();
      JSONObject checkInJo = new JSONObject(checkInRes);
      if (checkInJo.optBoolean("success")) {
        JSONObject currentDay = checkInJo.optJSONObject("data").optJSONObject("currentDateCheckInTaskVO");
        if (currentDay != null && "COMPLETED".equals(currentDay.optString("status"))) {
          // Log.other("芝麻炼金⚗️[每日签到]#已完成");
        }
      }

      AntMemberRpcCall.alchemyQueryTimeLimitedTask();
      Log.record(TAG, "芝麻炼金⚗️[检查时段奖励]");

      // ================= Step 3: 自动做任务 =================
      Log.record(TAG, "芝麻炼金⚗️[开始扫描任务列表]");
      String listRes = AntMemberRpcCall.alchemyQueryListV3();
      JSONObject listJo = new JSONObject(listRes);

      if (listJo.optBoolean("success")) {
        JSONObject data = listJo.optJSONObject("data");
        if (data != null) {
          JSONArray toComplete = data.optJSONArray("toCompleteVOS");
          if (toComplete != null) {
            processAlchemyTasks(toComplete);
          }
          JSONObject dailyTaskVO = data.optJSONObject("dailyTaskListVO");
          if (dailyTaskVO != null) {
            processAlchemyTasks(dailyTaskVO.optJSONArray("waitJoinTaskVOS"));
            processAlchemyTasks(dailyTaskVO.optJSONArray("waitCompleteTaskVOS"));
          }
        }
      }

      // ================= Step 4: [新增] 任务完成后一键收取芝麻粒 =================
      Log.record(TAG, "芝麻炼金⚗️[任务处理完毕，准备收取芝麻粒]");
      GlobalThreadPools.sleepCompat(2000); // 稍作等待，确保任务奖励到账

      // 4.1 查询是否有可收取的芝麻粒
      String queryFeedbackRes = AntMemberRpcCall.queryCreditFeedback();
      JSONObject feedbackJo = new JSONObject(queryFeedbackRes);
      if (feedbackJo.optBoolean("success")) {
        JSONArray feedbackList = feedbackJo.optJSONArray("creditFeedbackVOS");
        if (feedbackList != null && feedbackList.length() > 0) {
          Log.record(TAG, "芝麻炼金⚗️[发现" + feedbackList.length() + "个待收取项，执行一键收取]");

          // 4.2 执行一键收取
          String collectRes = AntMemberRpcCall.collectAllCreditFeedback();
          JSONObject collectJo = new JSONObject(collectRes);
          if (collectJo.optBoolean("success")) {
            Log.other("芝麻炼金⚗️[一键收取成功]#收割完毕");
          } else {
            Log.record(TAG, "芝麻炼金⚗️[一键收取失败]#" + collectJo.optString("resultView"));
          }
        } else {
          Log.record(TAG, "芝麻炼金⚗️[当前无待收取芝麻粒]");
        }
      }

    } catch (Throwable t) {
      Log.printStackTrace(TAG + ".doSesameAlchemy", t);
    }
  }

  private void processAlchemyTasks(JSONArray taskList) throws JSONException {
    if (taskList == null || taskList.length() == 0) return;

    // 修改点4：定义黑名单
    String[] blackList = {
            "每日施肥",
            "芝麻租赁",
            "休闲小游戏",
            "AQApp",
            "订阅炼金",
            "租游戏账号",
            "芝麻大表鸽",
            "坚持签到"
    };

    for (int i = 0; i < taskList.length(); i++) {
      JSONObject task = taskList.getJSONObject(i);
      String title = task.optString("title");
      String templateId = task.optString("templateId");
      boolean finishFlag = task.optBoolean("finishFlag", false);

      if (finishFlag) continue;

      // 修改点4：执行黑名单检查
      boolean isBlack = false;
      for (String blackKey : blackList) {
        if (title.contains(blackKey)) {
          isBlack = true;
          break;
        }
      }
      if (isBlack) {
        Log.record(TAG, "跳过黑名单任务: " + title);
        continue;
      }

      if (templateId.contains("invite") || templateId.contains("upload")
              || templateId.contains("auth") || templateId.contains("banli")) {
        continue;
      }
      String actionUrl = task.optString("actionUrl", "");
      if (actionUrl.startsWith("alipays://") && !actionUrl.contains("chInfo")) {
      }

      Log.record(TAG, "芝麻炼金任务: " + title + " 准备执行");

      String recordId = task.optString("recordId", "");

      if (recordId.isEmpty()) {
        String joinRes = AntMemberRpcCall.joinSesameTask(templateId);
        JSONObject joinJo = new JSONObject(joinRes);
        if (joinJo.optBoolean("success")) {
          JSONObject joinData = joinJo.optJSONObject("data");
          if (joinData != null) {
            recordId = joinData.optString("recordId");
          }
          Log.record(TAG, "任务领取成功: " + title);
          GlobalThreadPools.sleepCompat(1000);
        } else {
          Log.record(TAG, "任务领取失败: " + title + " - " + joinJo.optString("resultView"));
          continue;
        }
      }

      AntMemberRpcCall.feedBackSesameTask(templateId);

      int sleepTime = 3000;
      if (title.contains("浏览") || title.contains("逛")) {
        sleepTime = 15000;
      }
      GlobalThreadPools.sleepCompat(sleepTime);

      if (!recordId.isEmpty()) {
        String finishRes = AntMemberRpcCall.finishSesameTask(recordId);
        JSONObject finishJo = new JSONObject(finishRes);
        if (finishJo.optBoolean("success")) {
          int reward = task.optInt("rewardAmount", 0);
          Log.other("芝麻炼金⚗️[任务完成: " + title + "]#获得" + reward + "粒");
        } else {
          Log.record(TAG, "任务提交失败: " + title + " - " + finishJo.optString("resultView"));
        }
      }
      GlobalThreadPools.sleepCompat(2000);
    }
  }
  private void doZhimaTree() {
    try {
      // 1. 执行首页的所有任务 (包括浏览任务和复访任务)
      doHomeTasks();

      // 2. 执行常规列表任务 (赚净化值列表)
      doRentGreenTasks();

      // 3. 消耗净化值进行净化
      doPurification();

    } catch (Throwable t) {
      Log.printStackTrace(TAG, t);
    }
  }

  /**
   * 处理首页返回的任务 (含浏览任务和状态列表任务)
   */
  private void doHomeTasks() {
    try {
      String res = AntMemberRpcCall.zhimaTreeHomePage();
      if (res == null) return;

      JSONObject json = new JSONObject(res);
      if (ResChecker.checkRes(TAG, json)) {
        JSONObject result = json.optJSONObject("extInfo");
        if (result == null) return;
        JSONObject queryResult = result.optJSONObject("zhimaTreeHomePageQueryResult");
        if (queryResult == null) return;

        // 1. 处理 browseTaskList (如：芝麻树首页每日_浏览任务)
        JSONArray browseList = queryResult.optJSONArray("browseTaskList");
        if (browseList != null) {
          for (int i = 0; i < browseList.length(); i++) {
            processSingleTask(browseList.getJSONObject(i));
          }
        }

        // 2. 处理 taskStatusList (如：芝麻树复访任务70净化值)
        JSONArray statusList = queryResult.optJSONArray("taskStatusList");
        if (statusList != null) {
          for (int i = 0; i < statusList.length(); i++) {
            processSingleTask(statusList.getJSONObject(i));
          }
        }
      }
    } catch (Exception e) {
      Log.printStackTrace(TAG, e);
    }
  }

  /**
   * 处理赚净化值列表任务
   */
  private void doRentGreenTasks() {
    try {
      String res = AntMemberRpcCall.queryRentGreenTaskList();
      if (res == null) return;

      JSONObject json = new JSONObject(res);
      if (ResChecker.checkRes(TAG, json)) {
        JSONObject extInfo = json.optJSONObject("extInfo");
        if (extInfo == null) return;

        JSONObject taskDetailListObj = extInfo.optJSONObject("taskDetailList");
        if (taskDetailListObj == null) return;

        JSONArray tasks = taskDetailListObj.optJSONArray("taskDetailList");
        if (tasks == null) return;

        for (int i = 0; i < tasks.length(); i++) {
          processSingleTask(tasks.getJSONObject(i));
        }
      }
    } catch (Exception e) {
      Log.printStackTrace(TAG, e);
    }
  }

  /**
   * 处理单个任务对象的逻辑
   */
  private void processSingleTask(JSONObject task) {
    try {
      JSONObject taskBaseInfo = task.optJSONObject("taskBaseInfo");
      if (taskBaseInfo == null) return;

      String taskId = taskBaseInfo.optString("appletId");
      // 有些任务ID在taskId字段，有些在appletId，做个兼容
      if (taskId == null || taskId.isEmpty()) {
        taskId = task.optString("taskId");
      }

      String title = taskBaseInfo.optString("appletName");
      if (title.isEmpty()) title = taskBaseInfo.optString("title", taskId);

      String status = task.optString("taskProcessStatus");

      // 过滤掉明显无法自动完成的任务（如包含邀请、下单、开通），但保留复访任务
      if (title.contains("邀请") || title.contains("下单") || title.contains("开通")) {
        return;
      }

      // 解析奖励信息
      String prizeName = getPrizeName(task);

      if ("NOT_DONE".equals(status) || "SIGNUP_COMPLETE".equals(status)) {
        // SIGNUP_COMPLETE 通常表示已报名但未做，或者对于复访任务表示可以去完成
        Log.record("芝麻树🌳[开始任务] " + title + (prizeName.isEmpty() ? "" : " (" + prizeName + ")"));
        if (performTask(taskId, title, prizeName)) {
          // 任务完成
        }
      } else if ("TO_RECEIVE".equals(status)) {
        // 待领取状态
        if (doTaskAction(taskId, "receive")) {
          String logMsg = "芝麻树🌳[领取奖励] " + title + " #" + (prizeName.isEmpty() ? "奖励已领取" : prizeName);
          Log.forest(logMsg); // 输出到 forest
        }
      }
    } catch (Exception e) {
      Log.printStackTrace(TAG, e);
    }
  }

  /**
   * 执行任务动作：去完成 -> 等待 -> 领取
   */
  private boolean performTask(String taskId, String title, String prizeName) {
    try {
      // 发送"去完成"指令
      if (doTaskAction(taskId, "send")) {
        int waitTime = 16000; // 默认等待16秒，覆盖大多数浏览任务

        try {
          Thread.sleep(waitTime);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }

        // 发送"领取"指令
        if (doTaskAction(taskId, "receive")) {
          String logMsg = "芝麻树🌳[完成任务] " + title + " #" + (prizeName.isEmpty() ? "奖励已领取" : prizeName);
          Log.forest(logMsg); // 这里输出到 forest
          return true;
        }
      }
    } catch (Exception e) {
      Log.printStackTrace(TAG, e);
    }
    return false;
  }

  /**
   * 获取任务奖励名称
   */
  private String getPrizeName(JSONObject task) {
    String prizeName = "";
    try {
      JSONArray prizes = task.optJSONArray("validPrizeDetailDTO");
      if (prizes == null || prizes.length() == 0) {
        prizes = task.optJSONArray("prizeDetailDTOList");
      }

      if (prizes != null && prizes.length() > 0) {
        JSONObject prizeBase = prizes.getJSONObject(0).optJSONObject("prizeBaseInfoDTO");
        if (prizeBase != null) {
          String rawName = prizeBase.optString("prizeName", "");

          if (rawName.contains("能量")) {
            Pattern p = Pattern.compile("(森林)?能量(\\d+g?)");
            Matcher m = p.matcher(rawName);
            if (m.find()) {
              prizeName = m.group(0);
            } else {
              prizeName = rawName;
            }
          } else if (rawName.contains("净化值")) {
            Pattern p = Pattern.compile("(\\d+净化值|净化值\\d+)");
            Matcher m = p.matcher(rawName);
            if (m.find()) {
              prizeName = m.group(1);
            } else {
              prizeName = rawName;
            }
          } else {
            prizeName = rawName;
          }
        }
      }

      // 如果没找到 PrizeDTO，尝试从 taskExtProps 解析
      if (prizeName.isEmpty()) {
        JSONObject taskExtProps = task.optJSONObject("taskExtProps");
        if (taskExtProps != null && taskExtProps.has("TASK_MORPHO_DETAIL")) {
          JSONObject detail = new JSONObject(taskExtProps.getString("TASK_MORPHO_DETAIL"));
          String val = detail.optString("finishOneTaskGetPurificationValue", "");
          if (!val.isEmpty() && !"0".equals(val)) {
            prizeName = val + "净化值";
          }
        }
      }
    } catch (Exception ignore) {}
    return prizeName;
  }

  private boolean doTaskAction(String taskId, String stageCode) {
    try {
      String s = AntMemberRpcCall.rentGreenTaskFinish(taskId, stageCode);
      if (s == null) return false;
      JSONObject json = new JSONObject(s);
      return ResChecker.checkRes(TAG, json);
    } catch (Exception e) {
      Log.printStackTrace(TAG, e);
      return false;
    }
  }

  /**
   * 净化逻辑
   */
  private void doPurification() {
    try {
      String homeRes = AntMemberRpcCall.zhimaTreeHomePage();
      if (homeRes == null) return;

      JSONObject homeJson = new JSONObject(homeRes);
      if (!ResChecker.checkRes(TAG, homeJson)) return;

      JSONObject result = homeJson.optJSONObject("extInfo").optJSONObject("zhimaTreeHomePageQueryResult");
      if (result == null) return;

      int currentScore = result.optInt("purificationScore", result.optInt("currentCleanNum", 0));
      String treeCode = "ZHIMA_TREE";
      if (result.has("trees")) {
        JSONArray trees = result.getJSONArray("trees");
        if (trees.length() > 0) {
          treeCode = trees.getJSONObject(0).optString("treeCode", "ZHIMA_TREE");
        }
      }

      if (currentScore <= 0) {
        return;
      }

      Log.forest("芝麻树🌳[开始净化] 当前净化值: " + currentScore);

      while (currentScore > 0) {
        String cleanRes = AntMemberRpcCall.zhimaTreeCleanAndPush(treeCode);
        if (cleanRes == null) break;

        JSONObject cleanJson = new JSONObject(cleanRes);
        if (ResChecker.checkRes(TAG, cleanJson)) {
          JSONObject extInfo = cleanJson.optJSONObject("extInfo");

          currentScore -= 100;

          int newScore = -1;
          int growthValue = -1;

          if (extInfo != null) {
            // 优先解析 CleanAndPushResult
            JSONObject cleanResult = extInfo.optJSONObject("zhimaTreeCleanAndPushResult");
            if (cleanResult != null) {
              newScore = cleanResult.optInt("purificationScore", -1);
              JSONObject treeInfo = cleanResult.optJSONObject("currentTreeInfo");
              if (treeInfo != null) {
                // 使用 scoreSummary 作为成长值
                growthValue = treeInfo.optInt("scoreSummary", -1);
              }
            } else if (extInfo.has("purificationScore")) {
              // 兼容旧逻辑或异常情况
              newScore = extInfo.getInt("purificationScore");
            }
          }

          if (newScore != -1) currentScore = newScore;

          String growthLog = (growthValue != -1) ? " 当前成长值:" + growthValue : "";
          Log.forest("芝麻树🌳[净化成功] 剩余净化值:" + Math.max(0, currentScore) + growthLog + "✅");

          Thread.sleep(1500);
        } else {
          break;
        }
      }

    } catch (Exception e) {
      Log.printStackTrace(TAG, e);
    }
  }
}