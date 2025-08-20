package fansirsqi.xposed.sesame.task.antForest;

import com.fasterxml.jackson.core.type.TypeReference;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import fansirsqi.xposed.sesame.task.TaskStatus;
import fansirsqi.xposed.sesame.util.GlobalThreadPools;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.ResChecker;
import fansirsqi.xposed.sesame.newutil.DataStore;
import fansirsqi.xposed.sesame.util.maps.UserMap;

public class ForestChouChouLe {

    private static final String TAG = ForestChouChouLe.class.getSimpleName();

    // 任务尝试次数计数，避免重复失败
    private final java.util.Map<String, AtomicInteger> taskTryCount = new java.util.concurrent.ConcurrentHashMap<>();

    void chouChouLe() {
        try {
            boolean doublecheck;
            String source = "task_entry";

            // ==================== 屏蔽任务集合 ====================
            Set<String> presetBad = new LinkedHashSet<>();
            presetBad.add("FOREST_NORMAL_DRAW_SHARE");  // 邀请好友任务（可屏蔽）

            TypeReference<Set<String>> typeRef = new TypeReference<Set<String>>() {};
            Set<String> badTaskSet = DataStore.INSTANCE.getOrCreate("badForestTaskSet", typeRef);
            badTaskSet.addAll(presetBad);
            DataStore.INSTANCE.put("badForestTaskSet", badTaskSet);
            // =====================================================

            JSONObject jo = new JSONObject(AntForestRpcCall.enterDrawActivityopengreen(source));
            if (!ResChecker.checkRes(TAG, jo)) return;

            JSONObject drawScene = jo.getJSONObject("drawScene");
            JSONObject drawActivity = drawScene.getJSONObject("drawActivity");
            String activityId = drawActivity.getString("activityId");
            String sceneCode = drawActivity.getString("sceneCode"); // ANTFOREST_NORMAL_DRAW
            String listSceneCode = sceneCode + "_TASK";

            long startTime = drawActivity.getLong("startTime");
            long endTime = drawActivity.getLong("endTime");

            do {
                doublecheck = false;
                if (System.currentTimeMillis() > startTime && System.currentTimeMillis() < endTime) {
                    Log.record("延时1S");
                    GlobalThreadPools.sleep(1000L);

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
                            if (badTaskSet.contains(taskType)) {
                                Log.record("已屏蔽任务，跳过：" + taskName);
                                continue;
                            }
                            // ==============================================

                            // 统一处理 FOREST_NORMAL_DRAW 开头任务
                            if (taskType.startsWith("FOREST_NORMAL_DRAW") && taskStatus.equals(TaskStatus.TODO.name())) {
                                Log.record("任务延时30S模拟：" + taskName);
                                GlobalThreadPools.sleep(30 * 1000L);

                                // 调用对应完成接口
                                String result;
                                if (taskType.contains("XLIGHT")) {
                                    result = AntForestRpcCall.finishTask4Chouchoule(taskType, taskSceneCode);
                                } else {
                                    result = AntForestRpcCall.finishTaskopengreen(taskType, taskSceneCode);
                                }

                                if (ResChecker.checkRes(TAG, result)) {
                                    Log.forest("森林寻宝🧾：" + taskName);
                                    doublecheck = true;
                                } else {
                                    // 失败计数
                                    int count = taskTryCount.computeIfAbsent(taskType, k -> new AtomicInteger(0)).incrementAndGet();
                                    if (count > 1) {
                                        Log.error("任务多次失败，加入屏蔽：" + taskName);
                                        badTaskSet.add(taskType);
                                        DataStore.INSTANCE.put("badForestTaskSet", badTaskSet);
                                    }
                                }
                            }

                            // 已完成任务领取奖励
                            if (taskStatus.equals(TaskStatus.FINISHED.name())) {
                                Log.record("奖励延时3S:" + taskName);
                                GlobalThreadPools.sleep(3000L);
                                String sginRes = AntForestRpcCall.receiveTaskAwardopengreen(source, taskSceneCode, taskType);
                                if (ResChecker.checkRes(TAG, sginRes)) {
                                    Log.forest("森林寻宝🧾：" + taskName);
                                    if (rightsTimesLimit - rightsTimes <= 0) {
                                        badTaskSet.add(taskType);
                                        DataStore.INSTANCE.put("badForestTaskSet", badTaskSet);
                                    } else {
                                        doublecheck = true;
                                    }
                                }
                            }
                        }
                    }
                }
            } while (doublecheck);

            // ==================== 执行抽奖 ====================
            jo = new JSONObject(AntForestRpcCall.enterDrawActivityopengreen(source));
            if (ResChecker.checkRes(TAG, jo)) {
                drawScene = jo.getJSONObject("drawScene");
                drawActivity = drawScene.getJSONObject("drawActivity");
                activityId = drawActivity.getString("activityId");
                sceneCode = drawActivity.getString("sceneCode");

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
                        Log.forest("森林寻宝🎁[领取: " + prizeName + "*" + prizeNum + "]");
                    }
                }
            }
            // ==============================================

        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }
}
