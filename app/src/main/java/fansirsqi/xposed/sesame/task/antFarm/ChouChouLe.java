package fansirsqi.xposed.sesame.task.antFarm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import fansirsqi.xposed.sesame.util.GlobalThreadPools;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.maps.UserMap;
import fansirsqi.xposed.sesame.util.ResChecker;

public class ChouChouLe {
    private static final String TAG = ChouChouLe.class.getSimpleName();

    public enum TaskStatus {
        TODO, FINISHED, RECEIVED, DONATION
    }

    // 定义任务结构体
    private static class TaskInfo {
        String taskStatus;
        String title;
        String taskId;
        String innerAction;
        int rightsTimes;
        int rightsTimesLimit;
        String awardType;
        int awardCount;

        int getRemainingTimes() {
            return Math.max(0, rightsTimesLimit - rightsTimes);
        }
    }

    void chouchoule() {
        try {
            // 使用 queryDrawMachineActivity 作为抽奖入口判断（替代 queryLoveCabin）
            JSONObject qjo = new JSONObject(AntFarmRpcCall.queryDrawMachineActivity());
            if (!ResChecker.checkRes(TAG, qjo)) {
                return;
            }

            // daily 抽抽乐信息（可能为空）
            JSONObject drawMachineActivity = qjo.optJSONObject("drawMachineActivity");
            JSONArray otherDraws = qjo.optJSONArray("otherDrawMachineActivityIds");

            if (drawMachineActivity != null) {
                // 如果有 daily 活动，执行 daily 流程（先做任务、领奖，再抽奖）
                doChouchoule("dailyDraw");
            }

            if (otherDraws != null && otherDraws.length() > 0) {
                // 如果有 ip 活动 id 列表，执行 ip 流程（先做任务、领奖，再抽 ip 抽奖）
                doChouchoule("ipDraw");
            }

        } catch (Throwable t) {
            Log.printStackTrace("chouchoule err:", t);
        }
    }

    /**
     * 执行抽抽乐
     *
     * @param drawType "dailyDraw" or "ipDraw" 普通装扮或者IP装扮
     */
    private void doChouchoule(String drawType) {
        boolean doubleCheck;
        do {
            doubleCheck = false;
            try {
                JSONObject jo = new JSONObject(AntFarmRpcCall.chouchouleListFarmTask(drawType));
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.error(TAG, drawType.equals("ipDraw") ? "IP抽抽乐任务列表获取失败" : "抽抽乐任务列表获取失败");
                    continue;
                }
                JSONArray farmTaskList = jo.getJSONArray("farmTaskList");//获取任务列表
                List<TaskInfo> tasks = parseTasks(farmTaskList);
                for (TaskInfo task : tasks) {
                    // 已完成的任务，去领奖
                    if (TaskStatus.FINISHED.name().equals(task.taskStatus)) {
                        // 针对饲料上限判断 — 使用 awardType == "FOOD" 来判断饲料奖励是否会溢出
                        if ("FOOD".equals(task.awardType) && task.awardCount + AntFarm.foodStock > AntFarm.foodStockLimit) {
                            Log.record(TAG, "抽抽乐任务[" + task.title + "]的奖励领取后会使饲料超出上限，暂不领取");
                            continue;
                        }
                        if (receiveTaskAward(drawType, task.taskId)) {//领取奖励
                            GlobalThreadPools.sleepCompat(5 * 1000L);
                            doubleCheck = true;
                        }
                    } else if (TaskStatus.TODO.name().equals(task.taskStatus)) {
                        // TODO 任务且还有剩余次数，并且不是捐赠类/分享类任务
                        if (task.getRemainingTimes() > 0 && !"DONATION".equals(task.innerAction) && !"SHARE".equals(task.innerAction)) {
                            if (doChouTask(drawType, task)) {
                                doubleCheck = true;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.printStackTrace("doChouchoule err:", t);
            }
        } while (doubleCheck);

        if ("ipDraw".equals(drawType)) {
            handleIpDraw();
        } else {
            handleDailyDraw();
        }
    }

    private List<TaskInfo> parseTasks(JSONArray array) throws Exception {
        List<TaskInfo> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            TaskInfo info = new TaskInfo();
            info.taskStatus = item.optString("taskStatus");
            info.title = item.optString("title");
            info.taskId = item.optString("bizKey");
            info.innerAction = item.optString("innerAction");
            info.rightsTimes = item.optInt("rightsTimes", 0);
            info.rightsTimesLimit = item.optInt("rightsTimesLimit", 0);
            info.awardType = item.optString("awardType");
            info.awardCount = item.optInt("awardCount", 0);
            list.add(info);
        }
        return list;
    }

    /**
     * 执行单个抽抽乐任务（加强版）
     */
    private Boolean doChouTask(String drawType, TaskInfo task) {
        try {
            // 调用 doFarmTask（注意：AntFarmRpcCall.chouchouleDoFarmTask 内应保证 source 为 "icon"）
            String s = AntFarmRpcCall.chouchouleDoFarmTask(drawType, task.taskId);
            JSONObject jo = new JSONObject(s);

            // 先用通用检查（resultCode / common error）
            if (!ResChecker.checkRes(TAG, jo)) {
                // 简单重试一次（网络波动或短暂状态不一致）
                try {
                    GlobalThreadPools.sleepCompat(1000L);
                    s = AntFarmRpcCall.chouchouleDoFarmTask(drawType, task.taskId);
                    jo = new JSONObject(s);
                    if (!ResChecker.checkRes(TAG, jo)) {
                        return false;
                    }
                } catch (Throwable ignored) {
                    return false;
                }
            }

            // 更严格的字段检查：success + taskStatus == FINISHED
            if (!jo.optBoolean("success", false)) {
                Log.farm("抽抽乐任务返回 success=false → " + task.taskId);
                return false;
            }

            String status = jo.optString("taskStatus", "");
            if (!"FINISHED".equals(status)) {
                // 若不是 FINISHED，判定为未真正完成（避免误判）
                Log.farm("抽抽乐任务状态未完成: " + status + " → " + task.taskId);
                return false;
            }

            // 成功才记录日志与 sleep
            Log.farm((drawType.equals("ipDraw") ? "IP抽抽乐" : "抽抽乐") + "🧾️[任务: " + task.title + "]");
            // 优先用 taskId 判断短 sleep（避免文案本地化导致判断失效）
            if ("DAILY_DRAW_EXCHANGE_TASK".equals(task.taskId) || "SOME_ANOTHER_SHORT_SLEEP_TASK_ID".equals(task.taskId)) {
                GlobalThreadPools.sleepCompat(1000L);
            } else {
                GlobalThreadPools.sleepCompat(5 * 1000L);
            }
            return true;
        } catch (Throwable t) {
            Log.printStackTrace("执行抽抽乐任务 err:", t);
            return false;
        }
    }

    /**
     * 领取任务奖励
     *
     * @param drawType "dailyDraw" or "ipDraw" 普通装扮或者IP装扮
     * @param taskId   任务ID
     * @return 是否领取成功
     */
    private boolean receiveTaskAward(String drawType, String taskId) {
        try {
            String s = AntFarmRpcCall.chouchouleReceiveFarmTaskAward(drawType, taskId);
            JSONObject jo = new JSONObject(s);
            return ResChecker.checkRes(TAG, jo);
        } catch (Throwable t) {
            Log.printStackTrace("receiveFarmTaskAward err:", t);
        }
        return false;
    }

    /**
     * 执行IP抽抽乐
     */
    private void handleIpDraw() {
        try {
            JSONObject jo = new JSONObject(AntFarmRpcCall.queryDrawMachineActivity());
            if (!ResChecker.checkRes(TAG, jo)) {
                return;
            }

            // 获取 ip 活动 id 列表（抓包举例为 ["ipDrawMachine_251119"]）
            JSONArray otherIds = jo.optJSONArray("otherDrawMachineActivityIds");
            if (otherIds == null || otherIds.length() == 0) {
                Log.record(TAG, "未发现 IP 抽抽乐活动 id");
                return;
            }

            // 选第一个 ip 活动 id（如果需要更复杂策略可调整）
            String ipActivityId = otherIds.optString(0);

            JSONObject activity = jo.optJSONObject("drawMachineActivity");
            long endTime = activity != null ? activity.optLong("endTime", 0L) : 0L;
            if (endTime > 0 && System.currentTimeMillis() > endTime) {
                Log.record(TAG, "该[" + (activity != null ? activity.optString("activityId") : ipActivityId) + "]抽奖活动已结束");
                return;
            }

            int drawTimes = jo.optInt("drawTimes", 0);
            // 如果 query 返回 drawTimes 为 0，则不进行抽奖（抓包里也出现过 drawTimes=0）
            for (int i = 0; i < drawTimes; i++) {
                // 抽 IP 抽奖时，把 ipActivityId 作为参数传入 drawMachine（若 AntFarmRpcCall.drawMachine 支持 activityId 参数）
                String call = AntFarmRpcCall.drawMachine(ipActivityId); // 请确保 AntFarmRpcCall.drawMachine(String) 存在
                drawPrize("IP抽抽乐", call);
                GlobalThreadPools.sleepCompat(5 * 1000L);
            }

        } catch (Throwable t) {
            Log.printStackTrace("handleIpDraw err:", t);
        }
    }

    /**
     * 执行正常抽抽乐
     */
    private void handleDailyDraw() {
        try {
            // 进入抽奖页面（你原有实现用 enterDrawMachine）
            JSONObject jo = new JSONObject(AntFarmRpcCall.enterDrawMachine());
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.record(TAG, "抽奖活动进入失败");
                return;
            }

            JSONObject userInfo = jo.optJSONObject("userInfo");
            JSONObject drawActivityInfo = jo.optJSONObject("drawActivityInfo");
            long endTime = drawActivityInfo != null ? drawActivityInfo.optLong("endTime", 0L) : 0L;
            if (endTime > 0 && System.currentTimeMillis() > endTime) {
                Log.record(TAG, "该[" + (drawActivityInfo != null ? drawActivityInfo.optString("activityId") : "unknown") + "]抽奖活动已结束");
                return;
            }

            int leftDrawTimes = userInfo != null ? userInfo.optInt("leftDrawTimes", 0) : 0;
            String activityId = drawActivityInfo != null ? drawActivityInfo.optString("activityId", "null") : "null";

            for (int i = 0; i < leftDrawTimes; i++) {
                String call = "null".equals(activityId) ? AntFarmRpcCall.DrawPrize() : AntFarmRpcCall.DrawPrize(activityId);
                drawPrize("抽抽乐", call);
                GlobalThreadPools.sleepCompat(5 * 1000L);
            }

        } catch (Throwable t) {
            Log.printStackTrace("handleDailyDraw err:", t);
        }
    }

    /**
     * 领取抽抽乐奖品
     *
     * @param prefix   抽奖类型
     * @param response 服务器返回的结果
     */
    private void drawPrize(String prefix, String response) {
        try {
            JSONObject jo = new JSONObject(response);
            if (ResChecker.checkRes(TAG, jo)) {
                // 抓包显示 daily draw 返回字段为 drawMachinePrize，ip draw 也类似
                JSONObject prize = jo.optJSONObject("drawMachinePrize");
                if (prize == null) {
                    // 兼容部分返回 structure 为直接 prize 字段或 drawMachinePrize
                    prize = jo.optJSONObject("prize");
                }
                if (prize != null) {
                    String title = prize.optString("title", prize.optString("prizeName", "未知奖品"));
                    int prizeNum = prize.optInt("awardCount", prize.optInt("prizeNum", 1));
                    Log.farm(prefix + "🎁[领取: " + title + "*" + prizeNum + "]");
                } else {
                    // 兼容旧结构：有时候字段在 top level (抓包显示 drawMachinePrize)
                    if (jo.has("title")) {
                        String title = jo.optString("title");
                        int prizeNum = jo.optInt("prizeNum", 1);
                        Log.farm(prefix + "🎁[领取: " + title + "*" + prizeNum + "]");
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
