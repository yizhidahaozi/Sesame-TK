package fansirsqi.xposed.sesame.task.antForest;

import fansirsqi.xposed.sesame.entity.CollectEnergyEntity;
import fansirsqi.xposed.sesame.task.ModelTask.ChildModelTask;
import fansirsqi.xposed.sesame.util.Average;
import fansirsqi.xposed.sesame.util.GlobalThreadPools;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.maps.UserMap;
import fansirsqi.xposed.sesame.util.TimeUtil;
import lombok.Getter;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 蚂蚁森林定时任务管理器。
 * 负责处理与蚂蚁森林相关的所有定时和延迟任务，特别是能量球的“蹲点”收取。
 * 该类从 AntForest 主类中分离出来，以遵循单一职责原则。
 */
public class ForestTimerManager {
    private static final String TAG = ForestTimerManager.class.getSimpleName();

    private final AntForest antForest;
    private final Average offsetTimeMath;
    @Getter
    private final Average delayTimeMath;
    private final int advanceTimeInt;
    private final Consumer<CollectEnergyEntity> collectEnergyConsumer;

    /**
     * 构造函数。
     *
     * @param antForest             AntForest 主类的实例，用于回调和访问共享状态。
     * @param offsetTimeMath        服务器与本地时间的平均偏移量计算器。
     * @param advanceTimeInt        能量球收取的提前时间（毫秒）。
     * @param collectEnergyConsumer 实际执行能量收集的回调函数。
     */
    public ForestTimerManager(AntForest antForest, Average offsetTimeMath, Average delayTimeMath, int advanceTimeInt, Consumer<CollectEnergyEntity> collectEnergyConsumer) {
        this.antForest = antForest;
        this.offsetTimeMath = offsetTimeMath;
        this.delayTimeMath = delayTimeMath;
        this.advanceTimeInt = advanceTimeInt;
        this.collectEnergyConsumer = collectEnergyConsumer;
    }

    /**
     * 为指定用户调度所有待成熟能量球的蹲点任务。
     * 此方法会遍历所有待成熟的能量球，并为每一个能量球创建一个并行的蹲点任务。
     * 优化：限制单个用户的蹲点任务数量，避免资源过度占用
     *
     * @param userId         用户ID。
     * @param waitingBubbles 待成熟能量球列表，每个元素包含能量球ID和成熟时间。
     * @param userName       用户名，用于日志记录。
     */
    public void scheduleWaitingBubbles(String userId, List<AntForest.Pair<Long, Long>> waitingBubbles, String userName) {
        if (waitingBubbles.isEmpty()) {
            return;
        }
        
        // 限制单个用户最多10个蹲点任务，避免资源过度占用
        final int maxBubbles = Math.min(waitingBubbles.size(), 10);
        final List<AntForest.Pair<Long, Long>> limitedBubbles = waitingBubbles.subList(0, maxBubbles);
        
        if (maxBubbles < waitingBubbles.size()) {
            Log.record(TAG, "用户[" + userName + "]有" + waitingBubbles.size() + "个能量球，限制为" + maxBubbles + "个蹲点任务");
        } else {
            Log.record(TAG, "开始为用户[" + userName + "]添加" + maxBubbles + "个蹲点任务");
        }

        final AtomicInteger completedTasks = new AtomicInteger(0);
        final String finalUserName = userName;

        // 批量处理，减少线程创建开销
        for (int i = 0; i < limitedBubbles.size(); i += 3) { // 每批处理3个任务
            final int batchStart = i;
            final int batchEnd = Math.min(i + 3, limitedBubbles.size());
            
            GlobalThreadPools.execute(() -> {
                try {
                    for (int j = batchStart; j < batchEnd; j++) {
                        AntForest.Pair<Long, Long> pair = limitedBubbles.get(j);
                        addEnergyTimerTask(userId, pair.first(), pair.second(), finalUserName);
                    }
                } catch (Exception e) {
                    Log.printStackTrace(TAG, "批量添加蹲点任务异常: ", e);
                } finally {
                    // 任务添加完成后，检查是否所有批次都已完成
                    if (completedTasks.addAndGet(batchEnd - batchStart) >= maxBubbles) {
                        Log.record(TAG, "用户[" + finalUserName + "]的所有蹲点任务已批量添加完成");
                    }
                }
            });
        }
    }

    /**
     * 添加单个能量球的定时收取任务。
     * 如果任务已存在，则不会重复添加。
     *
     * @param userId      用户ID。
     * @param bubbleId    能量球ID。
     * @param produceTime 能量球成熟时间。
     * @param userName    用户名。
     */
    private void addEnergyTimerTask(String userId, long bubbleId, long produceTime, String userName) {
        final String tid = getEnergyTimerTid(userId, bubbleId);
        final long remainingTime = produceTime - System.currentTimeMillis();
        boolean taskExists = antForest.hasChildTask(tid);
        String status = taskExists ? "⚠️蹲点⏰已存在" : "✅添加蹲点⏰";
        if (!taskExists) {
            antForest.addChildTask(new EnergyTimerTask(userId, bubbleId, produceTime, userName));
        }
        Log.record(TAG, status + " -> [" + userName + "]"
                + " bubble=" + bubbleId
                + " 成熟时间/蹲守时间=" + TimeUtil.getCommonDate(produceTime)
                + " 剩余=" + (remainingTime / 1000) + "秒"
                + " tid=" + tid);
    }

    /**
     * 添加定时使用道具的任务。
     *
     * @param TargetTimeValue 目标执行时间的字符串列表（HHmm格式）。
     * @param propName        道具名称，用于日志。
     * @param func            要执行的任务逻辑。
     */
    public void addPropTimerTasks(List<String> TargetTimeValue, String propName, Runnable func) {
        for (String targetTimeStr : TargetTimeValue) {
            if ("-1".equals(targetTimeStr)) {
                return;
            }
            Calendar targetTimeCalendar = TimeUtil.getTodayCalendarByTimeStr(targetTimeStr);
            if (targetTimeCalendar == null) {
                return;
            }
            long targetTime = targetTimeCalendar.getTimeInMillis();
            long now = System.currentTimeMillis();
            if (now > targetTime) {
                continue;
            }
            String targetTaskId = "TARGET|" + targetTime;
            boolean taskExists = antForest.hasChildTask(targetTaskId);
            String action = taskExists ? "替换" : "添加";
            antForest.addChildTask(new ChildModelTask(targetTaskId, "TARGET", func, targetTime));
            Log.record(TAG, action + "定时使用" + propName + "[" + UserMap.getCurrentMaskName() + "]在[" + TimeUtil.getCommonDate(targetTime) + "]执行");
        }
    }

    /**
     * 调度保护罩/炸弹卡过期时的蹲点任务
     * 当保护罩或炸弹卡过期时，自动重新查询该用户的主页并收取能量
     *
     * @param userId           用户ID
     * @param protectionEndTime 保护过期时间
     * @param userName         用户名
     */
    public void scheduleProtectionExpire(String userId, long protectionEndTime, String userName) {
        // 在保护过期后延迟1秒再收取，确保保护已完全过期
        long executeTime = protectionEndTime + 1000;
        long now = System.currentTimeMillis();
        
        if (executeTime <= now) {
            Log.record(TAG, "保护已过期，跳过蹲点任务: [" + userName + "]");
            return;
        }
        
        String taskId = "PROTECTION_EXPIRE|" + userId + "|" + protectionEndTime;
        boolean taskExists = antForest.hasChildTask(taskId);
        String action = taskExists ? "⚠️保护过期蹲点⏰已存在" : "✅添加保护过期蹲点⏰";
        
        if (!taskExists) {
            Runnable protectionExpireTask = () -> {
                try {
                    // 设置用户名到缓存
                    antForest.setCachedUserName(userId, userName);
                    Log.forest("保护过期蹲点⏰[" + userName + "]保护已过期，重新查询收取");
                    
                    // 重新查询用户主页并收取能量
                    String response = AntForestRpcCall.queryFriendHomePage(userId, "PROTECTION_EXPIRE");
                    if (response != null && !response.isEmpty()) {
                        try {
                            org.json.JSONObject friendHomeObj = new org.json.JSONObject(response);
                            // 创建CollectEnergyEntity并通过消费者回调处理
                            CollectEnergyEntity entity = new CollectEnergyEntity(userId, friendHomeObj, null, "protection_expire");
                            collectEnergyConsumer.accept(entity);
                        } catch (Exception e) {
                            Log.printStackTrace(TAG, "保护过期蹲点处理异常", e);
                        }
                    }
                } catch (Exception e) {
                    Log.printStackTrace(TAG, "保护过期蹲点任务执行异常", e);
                }
            };
            
            antForest.addChildTask(new ChildModelTask(taskId, "PROTECTION_EXPIRE", protectionExpireTask, executeTime));
        }
        
        long remainingTime = executeTime - now;
        Log.record(TAG, action + " -> [" + userName + "]"
                + " 保护过期时间=" + TimeUtil.getCommonDate(protectionEndTime)
                + " 执行时间=" + TimeUtil.getCommonDate(executeTime)
                + " 剩余=" + (remainingTime / 1000) + "秒"
                + " tid=" + taskId);
    }

    /**
     * 生成能量收取定时任务的唯一ID。
     *
     * @param uid 用户ID。
     * @param bid 能量球ID。
     * @return 格式为 "BT|uid|bid" 的唯一任务ID。
     */
    public static String getEnergyTimerTid(String uid, long bid) {
        return "BT|" + uid + "|" + bid;
    }

    /**
     * 内部类，代表一个具体的能量球定时收取任务。
     * 该任务在指定的时间点（成熟时间 - 提前时间）被调度执行。
     */
    private class EnergyTimerTask extends ChildModelTask {
        private final String userId;
        private final long bubbleId;
        private final String userName;

        EnergyTimerTask(String uid, long bid, long pt, String uName) {
            super(getEnergyTimerTid(uid, bid), pt - advanceTimeInt);
            this.userId = uid;
            this.bubbleId = bid;
            this.userName = uName;
        }

        @Override
        public Runnable setRunnable() {
            return () -> {
                // 在任务执行时，将用户名设置回上下文，解决异步任务中用户名可能为null的问题
                antForest.setCachedUserName(userId, this.userName);
                int averageInteger = offsetTimeMath.getAverageInteger();
                Log.forest("蹲点收取⏰[" + this.userName + "]时差[" + averageInteger + "]ms提前[" + advanceTimeInt + "]ms");
                collectEnergyConsumer.accept(new CollectEnergyEntity(userId, null, AntForestRpcCall.energyRpcEntity("", userId, bubbleId)));
            };
        }
    }
}
