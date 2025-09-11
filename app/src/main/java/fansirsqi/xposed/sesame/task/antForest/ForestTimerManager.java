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
     *
     * @param userId         用户ID。
     * @param waitingBubbles 待成熟能量球列表，每个元素包含能量球ID和成熟时间。
     * @param userName       用户名，用于日志记录。
     */
    public void scheduleWaitingBubbles(String userId, List<AntForest.Pair<Long, Long>> waitingBubbles, String userName) {
        if (waitingBubbles.isEmpty()) {
            return;
        }
        Log.record(TAG, "开始为用户[" + userName + "]添加" + waitingBubbles.size() + "个蹲点任务");

        final int bubbleCount = waitingBubbles.size();
        final AtomicInteger completedTasks = new AtomicInteger(0);
        final String finalUserName = userName;

        for (AntForest.Pair<Long, Long> pair : waitingBubbles) {
            final long bubbleId = pair.first();
            final long produceTime = pair.second();

            // 使用全局线程池并行添加任务，避免阻塞主流程
            GlobalThreadPools.execute(() -> {
                try {
                    addEnergyTimerTask(userId, bubbleId, produceTime, finalUserName);
                } catch (Exception e) {
                    Log.printStackTrace(TAG, "添加蹲点任务异常: ", e);
                } finally {
                    // 任务添加完成后，检查是否所有任务都已添加完毕
                    if (completedTasks.incrementAndGet() == bubbleCount) {
                        Log.record(TAG, "用户[" + finalUserName + "]的所有蹲点任务已并行添加完成");
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
            String targetTaskId = "TAGET|" + targetTime;
            if (!antForest.hasChildTask(targetTaskId)) {
                antForest.addChildTask(new ChildModelTask(targetTaskId, "TAGET", func, targetTime));
                Log.record(TAG, "添加定时使用" + propName + "[" + UserMap.getCurrentMaskName() + "]在[" + TimeUtil.getCommonDate(targetTime) + "]执行");
            } else {
                antForest.addChildTask(new ChildModelTask(targetTaskId, "TAGET", func, targetTime));
            }
        }
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
                Log.record(TAG, "执行蹲-点收取⏰ 任务ID " + this.getId() + " [" + this.userName + "]" + "时差[" + averageInteger + "]ms" + "提前[" + advanceTimeInt + "]ms");
                // 调用外部传入的消费者来执行实际的能量收集RPC请求
                collectEnergyConsumer.accept(new CollectEnergyEntity(userId, null, AntForestRpcCall.energyRpcEntity("", userId, bubbleId)));
            };
        }
    }
}
