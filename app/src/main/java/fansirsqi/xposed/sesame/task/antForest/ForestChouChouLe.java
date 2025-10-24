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

            Log.forest("开始处理森林抽抽乐");

            // 直接处理两个已知的抽奖场景，避免复杂的活动发现逻辑
            processKnownScenes(source, presetBad);

        } catch (Exception e) {
            Log.printStackTrace(TAG, "chouChouLe 执行异常", e);
        }
    }

    /**
     * 直接处理已知的抽奖场景
     */
    private void processKnownScenes(String source, Set<String> presetBad) {
        try {
            // 已知的抽奖场景配置
            String[][] knownScenes = {
                // {activityId, sceneCode, sceneName}
                {"2025101301", "ANTFOREST_NORMAL_DRAW", "森林抽抽乐普通版"},
                {"20251024", "ANTFOREST_ACTIVITY_DRAW", "森林抽抽乐活动版"}
            };

            for (String[] scene : knownScenes) {
                String activityId = scene[0];
                String sceneCode = scene[1];
                String sceneName = scene[2];
                
                Log.forest("开始处理：" + sceneName + " (ActivityId: " + activityId + ", SceneCode: " + sceneCode + ")");
                
                processChouChouLeScene(activityId, sceneCode, sceneName, source, presetBad);
                
                // 场景间延时
                GlobalThreadPools.sleepCompat(3000L);
            }

        } catch (Exception e) {
            Log.printStackTrace(TAG, "processKnownScenes 执行异常", e);
        }
    }

    /**
     * 处理单个抽奖场景
     */
    private void processChouChouLeScene(String activityId, String sceneCode, String sceneName, String source, Set<String> presetBad) {
        try {
            boolean doublecheck;
            String listSceneCode = sceneCode + "_TASK";

            // 首先尝试进入活动
            JSONObject jo = new JSONObject(AntForestRpcCall.enterDrawActivityopengreen(activityId, sceneCode, source));
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.error(TAG, sceneName + " - enterDrawActivity 调用失败");
                return;
            }

            JSONObject drawActivity = jo.getJSONObject("drawActivity");
            long startTime = drawActivity.getLong("startTime");
            long endTime = drawActivity.getLong("endTime");
            
            // 检查活动是否在有效期内
            long currentTime = System.currentTimeMillis();
            if (currentTime < startTime || currentTime > endTime) {
                Log.forest(sceneName + " 活动不在有效期内，跳过");
                return;
            }

            int loopCount = 0;           // 循环次数计数
            final int MAX_LOOP = 3;      // 最大循环次数，避免死循环

            do {
                doublecheck = false;
                Log.record(sceneName + " 第 " + (loopCount + 1) + " 轮任务处理开始");
                
                // 获取任务列表
                JSONObject listTaskopengreen = new JSONObject(AntForestRpcCall.listTaskopengreen(listSceneCode, source));
                if (ResChecker.checkRes(TAG, listTaskopengreen)) {
                    JSONArray taskList = listTaskopengreen.getJSONArray("taskInfoList");
                    Log.forest(sceneName + " 发现 " + taskList.length() + " 个任务");

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

                        Log.record(sceneName + " 任务: " + taskName + " [" + taskType + "] 状态: " + taskStatus + 
                                  " 进度: " + rightsTimes + "/" + rightsTimesLimit);

                        // ==================== 屏蔽逻辑 ====================
                        if (presetBad.contains(taskType)) {
                            Log.record(sceneName + " 已屏蔽任务，跳过：" + taskName);
                            continue;
                        }
                        // ==============================================

                        // ==================== 活力值兑换任务 ====================
                        if (taskType.equals("NORMAL_DRAW_EXCHANGE_VITALITY") && taskStatus.equals(TaskStatus.TODO.name())) {
                            Log.record(sceneName + " 处理活力值兑换任务：" + taskName);
                            String sginRes = AntForestRpcCall.exchangeTimesFromTaskopengreen(
                                    activityId, sceneCode, source, taskSceneCode, taskType
                            );
                            if (ResChecker.checkRes(TAG, sginRes)) {
                                Log.forest(sceneName + "🧾：" + taskName + " 兑换成功");
                                doublecheck = true;
                            } else {
                                Log.error(TAG, sceneName + " 活力值兑换失败: " + taskName);
                            }
                            continue;
                        }
                        // =====================================================

                        // 统一处理任务（适配普通版和活动版）
                        if ((taskType.startsWith("FOREST_NORMAL_DRAW") || taskType.startsWith("FOREST_ACTIVITY_DRAW")) 
                            && taskStatus.equals(TaskStatus.TODO.name())) {
                            Log.record(sceneName + " 执行任务延时30S模拟：" + taskName);
                            GlobalThreadPools.sleepCompat(30 * 1000L);

                            // 调用对应完成接口
                            String result;
                            if (taskType.contains("XLIGHT")) {
                                result = AntForestRpcCall.finishTask4Chouchoule(taskType, taskSceneCode);
                            } else {
                                result = AntForestRpcCall.finishTaskopengreen(taskType, taskSceneCode);
                            }

                            if (ResChecker.checkRes(TAG, result)) {
                                Log.forest(sceneName + "🧾：" + taskName + " 完成成功");
                                doublecheck = true;
                            } else {
                                Log.error(TAG, sceneName + " 任务完成失败: " + taskName);
                                // 失败计数（不会自动屏蔽）
                                int tryCount = taskTryCount.computeIfAbsent(taskType, k -> new AtomicInteger(0)).incrementAndGet();
                                if (tryCount > 3) {
                                    Log.forest(sceneName + " 任务 " + taskName + " 多次失败，建议检查");
                                }
                            }
                        }

                        // 已完成任务领取奖励
                        if (taskStatus.equals(TaskStatus.FINISHED.name())) {
                            Log.record(sceneName + " 领取奖励延时3S:" + taskName);
                            GlobalThreadPools.sleepCompat(3000L);
                            String sginRes = AntForestRpcCall.receiveTaskAwardopengreen(source, taskSceneCode, taskType);
                            if (ResChecker.checkRes(TAG, sginRes)) {
                                Log.forest(sceneName + "🧾：" + taskName + " 奖励领取成功");
                                if (rightsTimesLimit - rightsTimes > 0) {
                                    doublecheck = true;
                                }
                            } else {
                                Log.error(TAG, sceneName + " 奖励领取失败: " + taskName);
                            }
                        }
                    }
                } else {
                    Log.error(TAG, sceneName + " - listTaskopengreen 调用失败");
                    break; // 获取任务列表失败则退出循环
                }
                
                // 循环间隔
                if (doublecheck && loopCount < MAX_LOOP - 1) {
                    Log.record(sceneName + " 等待3秒后继续下一轮检查");
                    GlobalThreadPools.sleepCompat(3000L);
                }
                
            } while (doublecheck && ++loopCount < MAX_LOOP);

            // ==================== 执行当前场景的抽奖 ====================
            Log.forest(sceneName + " 开始处理抽奖");
            // 重新进入活动获取最新状态
            jo = new JSONObject(AntForestRpcCall.enterDrawActivityopengreen(activityId, sceneCode, source));
            if (ResChecker.checkRes(TAG, jo)) {
                JSONObject drawAsset = jo.getJSONObject("drawAsset");
                int blance = drawAsset.optInt("blance", 0);
                int totalTimes = drawAsset.optInt("totalTimes", 0);

                Log.forest(sceneName + " 剩余抽奖次数：" + blance + "/" + totalTimes);

                int drawCount = 0;
                while (blance > 0 && drawCount < 50) { // 防止无限循环
                    drawCount++;
                    Log.record(sceneName + " 第 " + drawCount + " 次抽奖");
                    
                    // 执行抽奖
                    jo = new JSONObject(AntForestRpcCall.drawopengreen(activityId, sceneCode, source, UserMap.getCurrentUid()));
                    if (ResChecker.checkRes(TAG, jo)) {
                        drawAsset = jo.getJSONObject("drawAsset");
                        int newBlance = drawAsset.getInt("blance");
                        JSONObject prizeVO = jo.getJSONObject("prizeVO");
                        String prizeName = prizeVO.getString("prizeName");
                        int prizeNum = prizeVO.getInt("prizeNum");
                        Log.forest(sceneName + "🎁[领取: " + prizeName + "*" + prizeNum + "] 剩余次数: " + newBlance);
                        
                        blance = newBlance;
                        
                        // 抽奖间隔
                        if (blance > 0) {
                            GlobalThreadPools.sleepCompat(2000L);
                        }
                    } else {
                        Log.error(TAG, sceneName + " - 第 " + drawCount + " 次抽奖失败");
                        break; // 抽奖失败则退出循环
                    }
                }
                
                if (drawCount > 0) {
                    Log.forest(sceneName + " 抽奖完成，共抽奖 " + drawCount + " 次");
                }
            } else {
                Log.error(TAG, sceneName + " - 抽奖前enterDrawActivity调用失败");
            }
            // ==============================================

        } catch (Exception e) {
            Log.printStackTrace(TAG, sceneName + " 处理异常", e);
        }
    }
}