package fansirsqi.xposed.sesame.task.antForest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import fansirsqi.xposed.sesame.task.TaskStatus;
import fansirsqi.xposed.sesame.util.GlobalThreadPools;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.ResChecker;
import fansirsqi.xposed.sesame.util.maps.UserMap;

public class ForestChouChouLe {

    private static final String TAG = ForestChouChouLe.class.getSimpleName();

    // 任务尝试次数计数，避免重复失败
    private final java.util.Map<String, AtomicInteger> taskTryCount = new java.util.concurrent.ConcurrentHashMap<>();

    void chouChouLe() {
        try {
            String source = "task_entry";

            // ==================== 手动屏蔽任务集合 ====================
            Set<String> presetBad = new LinkedHashSet<>();
            presetBad.add("FOREST_NORMAL_DRAW_SHARE");  // 普通版邀请好友任务（屏蔽）
            presetBad.add("FOREST_ACTIVITY_DRAW_SHARE"); // 活动版邀请好友任务（屏蔽）
            // =====================================================

            // 获取所有抽奖场景
            JSONObject jo = new JSONObject(AntForestRpcCall.enterDrawActivityopengreen(source));
            if (!ResChecker.checkRes(TAG, jo)) return;

            JSONArray drawSceneGroups = jo.getJSONArray("drawSceneGroups");
            
            // 遍历所有抽奖场景（普通版 + 活动版）
            for (int sceneIndex = 0; sceneIndex < drawSceneGroups.length(); sceneIndex++) {
                JSONObject drawSceneGroup = drawSceneGroups.getJSONObject(sceneIndex);
                JSONObject drawActivity = drawSceneGroup.getJSONObject("drawActivity");
                String activityId = drawActivity.getString("activityId");
                String sceneCode = drawActivity.getString("sceneCode");
                String sceneName = drawSceneGroup.getString("name");
                
                Log.forest("开始处理：" + sceneName);
                
                processChouChouLeScene(activityId, sceneCode, sceneName, source, presetBad);
            }

        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    /**
     * 处理单个抽奖场景
     */
    private void processChouChouLeScene(String activityId, String sceneCode, String sceneName, String source, Set<String> presetBad) {
        try {
            boolean doublecheck;
            String listSceneCode = sceneCode + "_TASK";

            JSONObject jo = new JSONObject(AntForestRpcCall.enterDrawActivityopengreen(source));
            if (!ResChecker.checkRes(TAG, jo)) return;

            JSONObject drawScene = jo.getJSONObject("drawScene");
            JSONObject drawActivity = drawScene.getJSONObject("drawActivity");
            long startTime = drawActivity.getLong("startTime");
            long endTime = drawActivity.getLong("endTime");

            int loopCount = 0;           // 循环次数计数
            final int MAX_LOOP = 5;      // 最大循环次数，避免死循环

            do {
                doublecheck = false;
                if (System.currentTimeMillis() > startTime && System.currentTimeMillis() < endTime) {
                    Log.record(sceneName + " 延时1S");
                    GlobalThreadPools.sleepCompat(1000L);

                    JSONObject listTaskopengreen = new JSONObject(AntForestRpcCall.listTaskopengreen(activityId, listSceneCode, source));
                    if (ResChecker.checkRes(TAG, listTaskopengreen)) {
                        JSONArray taskList = listTaskopengreen.getJSONArray("taskInfoList");

                        for (int i = 0; i < taskList.length(); i++) {
                            JSONObject taskInfo = taskList.getJSONObject(i);
                            JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo");
                            JSONObject bizInfo = new JSONObject(taskBaseInfo.getString("bizInfo"));
                            String taskName = bizInfo.getString("title");
                            String taskSceneCode = taskBaseInfo.getString("sceneCode");
                            String taskStatus = taskBaseInfo.getString("taskStatus");
                            String taskType = taskBaseInfo.getString("taskType");

                            JSONObject taskRights = taskInfo.getJSONObject("taskRights");
                            int rightsTimes = taskRights.getInt("rightsTimes");
                            int rightsTimesLimit = taskRights.getInt("rightsTimesLimit");

                            // ==================== 屏蔽逻辑 ====================
                            if (presetBad.contains(taskType)) {
                                Log.record(sceneName + " 已屏蔽任务，跳过：" + taskName);
                                continue;
                            }
                            // ==============================================

                            // ==================== 活力值兑换任务 ====================
                            if (taskType.equals("NORMAL_DRAW_EXCHANGE_VITALITY") && taskStatus.equals(TaskStatus.TODO.name())) {
                                String sginRes = AntForestRpcCall.exchangeTimesFromTaskopengreen(
                                        activityId, sceneCode, source, taskSceneCode, taskType
                                );
                                if (ResChecker.checkRes(TAG + " " + sceneName + " 活力值兑换失败:", sginRes)) {
                                    Log.forest(sceneName + "🧾：" + taskName);
                                    doublecheck = true;
                                }
                                continue;
                            }
                            // =====================================================

                            // 统一处理任务（适配普通版和活动版）
                            if ((taskType.startsWith("FOREST_NORMAL_DRAW") || taskType.startsWith("FOREST_ACTIVITY_DRAW")) 
                                && taskStatus.equals(TaskStatus.TODO.name())) {
                                Log.record(sceneName + " 任务延时30S模拟：" + taskName);
                                GlobalThreadPools.sleepCompat(30 * 1000L);

                                // 调用对应完成接口
                                String result;
                                if (taskType.contains("XLIGHT")) {
                                    result = AntForestRpcCall.finishTask4Chouchoule(taskType, taskSceneCode);
                                } else {
                                    result = AntForestRpcCall.finishTaskopengreen(taskType, taskSceneCode);
                                }

                                if (ResChecker.checkRes(TAG, result)) {
                                    Log.forest(sceneName + "🧾：" + taskName);
                                    doublecheck = true;
                                } else {
                                    // 失败计数（不会自动屏蔽）
                                    taskTryCount.computeIfAbsent(taskType, k -> new AtomicInteger(0)).incrementAndGet();
                                }
                            }

                            // 已完成任务领取奖励
                            if (taskStatus.equals(TaskStatus.FINISHED.name())) {
                                Log.record(sceneName + " 奖励延时3S:" + taskName);
                                GlobalThreadPools.sleepCompat(3000L);
                                String sginRes = AntForestRpcCall.receiveTaskAwardopengreen(source, taskSceneCode, taskType);
                                if (ResChecker.checkRes(TAG, sginRes)) {
                                    Log.forest(sceneName + "🧾：" + taskName);
                                    if (rightsTimesLimit - rightsTimes > 0) {
                                        doublecheck = true;
                                    }
                                }
                            }
                        }
                    }
                }
            } while (doublecheck && ++loopCount < MAX_LOOP);

            // ==================== 执行当前场景的抽奖 ====================
            jo = new JSONObject(AntForestRpcCall.enterDrawActivityopengreen(source));
            if (ResChecker.checkRes(TAG, jo)) {
                // 需要切换到当前场景
                JSONArray drawSceneGroups = jo.getJSONArray("drawSceneGroups");
                JSONObject currentScene = null;
                for (int i = 0; i < drawSceneGroups.length(); i++) {
                    JSONObject scene = drawSceneGroups.getJSONObject(i);
                    if (scene.getString("sceneCode").equals(sceneCode)) {
                        currentScene = scene;
                        break;
                    }
                }
                
                if (currentScene != null) {
                    JSONObject drawAsset = jo.getJSONObject("drawAsset");
                    int blance = drawAsset.optInt("blance", 0);

                    while (blance > 0) {
                        jo = new JSONObject(AntForestRpcCall.drawopengreen(activityId, sceneCode, source, UserMap.getCurrentUid()));
                        if (ResChecker.checkRes(TAG, jo)) {
                            drawAsset = jo.getJSONObject("drawAsset");
                            blance = drawAsset.getInt("blance");
                            JSONObject prizeVO = jo.getJSONObject("prizeVO");
                            String prizeName = prizeVO.getString("prizeName");
                            int prizeNum = prizeVO.getInt("prizeNum");
                            Log.forest(sceneName + "🎁[领取: " + prizeName + "*" + prizeNum + "]");
                        }
                    }
                }
            }
            // ==============================================

        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }
}