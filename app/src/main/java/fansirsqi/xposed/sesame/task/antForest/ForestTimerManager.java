package fansirsqi.xposed.sesame.task.antForest;

import fansirsqi.xposed.sesame.entity.CollectEnergyEntity;
import fansirsqi.xposed.sesame.task.ModelTask.ChildModelTask;
import fansirsqi.xposed.sesame.util.Average;
import fansirsqi.xposed.sesame.util.GlobalThreadPools;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.maps.UserMap;
import fansirsqi.xposed.sesame.util.TimeUtil;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ForestTimerManager {
    private static final String TAG = ForestTimerManager.class.getSimpleName();

    private final AntForest antForest;
    private final Average offsetTimeMath;
    private final int advanceTimeInt;
    private final Consumer<CollectEnergyEntity> collectEnergyConsumer;

    public ForestTimerManager(AntForest antForest, Average offsetTimeMath, Average delayTimeMath, int advanceTimeInt, Consumer<CollectEnergyEntity> collectEnergyConsumer) {
        this.antForest = antForest;
        this.offsetTimeMath = offsetTimeMath;
        this.advanceTimeInt = advanceTimeInt;
        this.collectEnergyConsumer = collectEnergyConsumer;
    }

    public void scheduleWaitingBubbles(String userId, List<AntForest.Pair<Long, Long>> waitingBubbles, String userName) {
        if (waitingBubbles.isEmpty()) {
            return;
        }
        final int bubbleCount = waitingBubbles.size();
        final AtomicInteger completedTasks = new AtomicInteger(0);
        final String finalUserName = userName;

        for (AntForest.Pair<Long, Long> pair : waitingBubbles) {
            final long bubbleId = pair.first();
            final long produceTime = pair.second();

            GlobalThreadPools.execute(() -> {
                try {
                    addEnergyTimerTask(userId, bubbleId, produceTime, finalUserName);
                } catch (Exception e) {
                    Log.printStackTrace(TAG, "添加蹲点任务异常: ", e);
                } finally {
                    int completed = completedTasks.incrementAndGet();
                    if (completed == bubbleCount) {
                        Log.record(TAG, "用户[" + finalUserName + "]的所有蹲点任务已并行添加完成");
                    }
                }
            });
        }
    }

    private void addEnergyTimerTask(String userId, long bubbleId, long produceTime, String userName) {
        final String tid = getEnergyTimerTid(userId, bubbleId);
        final long remainingTime = produceTime - System.currentTimeMillis();

        if (!antForest.hasChildTask(tid)) {
            antForest.addChildTask(new EnergyTimerTask(userId, bubbleId, produceTime, userName));
            Log.record(TAG,
                    "✅添加蹲点⏰ -> [" + userName + "]"
                            + " bubble=" + bubbleId
                            + " 成熟时间/蹲守时间=" + TimeUtil.getCommonDate(produceTime)
                            + " 剩余=" + (remainingTime / 1000) + "秒"
                            + " tid=" + tid);
        } else {
            Log.record(TAG,
                    "⚠️蹲点⏰已存在 -> [" + userName + "]"
                            + " bubble=" + bubbleId
                            + " 成熟时间/蹲守时间=" + TimeUtil.getCommonDate(produceTime)
                            + " 剩余=" + (remainingTime / 1000) + "秒"
                            + " tid=" + tid);
        }
    }

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
            String targetTaskId = "TAGET|" + targetTime;
            if (!antForest.hasChildTask(targetTaskId)) {
                antForest.addChildTask(new ChildModelTask(targetTaskId, "TAGET", func, targetTime));
                Log.record(TAG, "添加定时使用" + propName + "[" + UserMap.getCurrentMaskName() + "]在[" + TimeUtil.getCommonDate(targetTime) + "]执行");
            } else {
                antForest.addChildTask(new ChildModelTask(targetTaskId, "TAGET", func, targetTime));
            }
        }
    }

    public static String getEnergyTimerTid(String uid, long bid) {
        return "BT|" + uid + "|" + bid;
    }

    private class EnergyTimerTask extends ChildModelTask {
        private final String userId;
        private final long bubbleId;
        private final long produceTime;
        private final String userName;

        EnergyTimerTask(String uid, long bid, long pt, String uName) {
            super(getEnergyTimerTid(uid, bid), pt - advanceTimeInt);
            this.userId = uid;
            this.bubbleId = bid;
            this.produceTime = pt;
            this.userName = uName;
        }

        @Override
        public Runnable setRunnable() {
            return () -> {
                antForest.setCachedUserName(userId, this.userName);
                int averageInteger = offsetTimeMath.getAverageInteger();
                Log.record(TAG, "执行蹲-点收取⏰ 任务ID " + this.getId() + " [" + this.userName + "]" + "时差[" + averageInteger + "]ms" + "提前[" + advanceTimeInt + "]ms");
                collectEnergyConsumer.accept(new CollectEnergyEntity(userId, null, AntForestRpcCall.energyRpcEntity("", userId, bubbleId)));
            };
        }
    }
}
