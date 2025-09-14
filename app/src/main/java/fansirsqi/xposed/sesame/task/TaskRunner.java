package fansirsqi.xposed.sesame.task;

import android.annotation.SuppressLint;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

import fansirsqi.xposed.sesame.model.BaseModel;
import fansirsqi.xposed.sesame.model.Model;
import fansirsqi.xposed.sesame.util.Log;

/**
 * 任务执行器类。
 * <p>
 * 该类的核心职责是提供一个无状态的、可重复使用的任务执行环境。
 * 它从 ModelTask 中分离了任务的“执行”逻辑，使得 ModelTask 只负责任务的“定义”。
 * <p>
 * 主要特性:
 * 1.  <b>线程安全</b>: 每个 TaskRunner 实例都有自己独立的状态（任务列表、计数器），
 *      因此可以安全地并发执行多个 TaskRunner 实例而不会产生数据竞争。
 * 2.  <b>职责分离</b>: TaskRunner 专注于如何执行任务（顺序、并行、多轮），
 *      而 ModelTask 及其子类只专注于任务自身的具体逻辑。
 * 3.  <b>可扩展性</b>: 未来可以方便地扩展新的执行模式或任务过滤逻辑。
 */
public class TaskRunner {
    private static final String TAG = TaskRunner.class.getSimpleName();

    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger skippedCount = new AtomicInteger(0);
    private final List<ModelTask> taskList;

    /**
     * 构造一个新的 TaskRunner。
     *
     * @param allModels 系统中所有已注册的模型列表。构造函数会自动筛选出其中的任务（ModelTask）。
     */
    public TaskRunner(List<Model> allModels) {
        this.taskList = allModels.stream()
                .filter(m -> m instanceof ModelTask)
                .map(m -> (ModelTask) m)
                .collect(Collectors.toList());
    }

    /**
     * 启动任务执行流程。
     *
     * @param isFirst 是否为首次执行（用于重置统计计数器）。
     * @param mode    执行模式（并行或顺序）。
     */
    public void run(boolean isFirst, ModelTask.TaskExecutionMode mode) {
        if (isFirst) {
            successCount.set(0);
            failureCount.set(0);
            skippedCount.set(0);
        }
        long startTime = System.currentTimeMillis();
        int roundCount = 2;
        for (int i = 1; i <= roundCount; i++) {
            Log.record(TAG, "开始执行第" + i + "轮任务");
            executeTasksByMode(i, mode);
        }
        long endTime = System.currentTimeMillis();
        @SuppressLint("DefaultLocale") String stats = String.format("任务执行统计 - 总耗时: %dms, 成功: %d, 失败: %d, 跳过: %d",
                (endTime - startTime), successCount.get(), failureCount.get(), skippedCount.get());
        Log.record(TAG, stats);
    }

    /**
     * 根据指定的模式执行一轮任务。
     */
    private void executeTasksByMode(int runCount, ModelTask.TaskExecutionMode mode) {
        if (mode == ModelTask.TaskExecutionMode.PARALLEL) {
            startAllTaskParallel(runCount);
        } else {
            startAllTaskSequential(runCount);
        }
    }

    /**
     * 顺序执行一轮任务。
     */
    private void startAllTaskSequential(int runCount) {
        CountDownLatch latch = new CountDownLatch(taskList.size());
        for (ModelTask task : taskList) {
            try {
                if (task.isEnable()) {
                    executeTask(task, runCount, successCount, failureCount, skippedCount);
                }
            } catch (Exception e) {
                Log.printStackTrace(TAG, "执行顺序任务异常", e);
            } finally {
                latch.countDown();
            }
        }
        waitForTaskCompletion(runCount, latch);
    }

    /**
     * 并行执行一轮任务。
     */
    private void startAllTaskParallel(int runCount) {
        int activeModelCount = (int) taskList.stream().filter(ModelTask::isEnable).count();
        if (activeModelCount == 0) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(activeModelCount);
        for (ModelTask task : taskList) {
            if (task.isEnable()) {
                ModelTask.ParallelTaskExecutor.PARALLEL_EXECUTOR.execute(() -> {
                    try {
                        executeTask(task, runCount, successCount, failureCount, skippedCount);
                    } catch (Exception e) {
                        Log.printStackTrace(TAG, "执行并行任务异常", e);
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }
        waitForTaskCompletion(runCount, latch);
    }

    /**
     * 执行单个具体任务，并更新统计信息。
     */
    private void executeTask(ModelTask task, int runCount, AtomicInteger success, AtomicInteger fail, AtomicInteger skip) {
        String taskName = task.getName();
        try {
            task.addRunCents();
            int taskPriority = task.getPriority();
            if (runCount < taskPriority) {
                skip.incrementAndGet();
                Log.record(TAG, "模块[" + taskName + "]优先级:" + taskPriority + " 第" + runCount + "轮跳过");
                return;
            }
            if (task.startTask(true)) {
                success.incrementAndGet();
            } else {
                fail.incrementAndGet();
            }
        } catch (Exception e) {
            fail.incrementAndGet();
            Log.error(TAG, "执行任务[" + taskName + "]时发生错误: " + e.getMessage());
            Log.printStackTrace(e);
        }
    }

    /**
     * 等待一轮任务执行完成。
     * 使用 CountDownLatch 进行阻塞等待，最多等待10分钟。
     */
    @SuppressLint("DefaultLocale")
    private void waitForTaskCompletion(int runCount, CountDownLatch latch) {
        Thread currentThread = Thread.currentThread();
        @SuppressWarnings("deprecation")
        long threadId = currentThread.getId();
        Log.record(TAG, String.format("开始等待第%d轮任务完成，线程信息: [ID=%d, Name=%s]", runCount, threadId, currentThread.getName()));
        // 获取等待时间设置
        int waitTimeValue = BaseModel.taskWaitTime.getValue();
        boolean infiniteWait = waitTimeValue == -1;
        // 设置等待时间（-1表示无限等待）
        long remainingTime = infiniteWait ? Long.MAX_VALUE : TimeUnit.MINUTES.toMillis(waitTimeValue);
        if (infiniteWait) {
            Log.record(TAG, "任务等待时间设置为-1，将无限等待直到任务完成");
        }
        long startTime = System.currentTimeMillis();
        while (remainingTime > 0) {
            try {
                if (latch.await(30, TimeUnit.SECONDS)) {
                    Log.record(TAG, String.format("第%d轮所有任务已完成，线程信息: [ID=%d, Name=%s]", runCount, threadId, currentThread.getName()));
                    return;
                }
                // 更新剩余时间
                remainingTime = TimeUnit.MINUTES.toMillis(waitTimeValue) - (System.currentTimeMillis() - startTime);
                // 输出每个任务的执行状态
                StringBuilder status = new StringBuilder();
                // 构建状态信息
                StringBuilder timeInfo = new StringBuilder();
                if (infiniteWait) {
                    timeInfo.append("无限等待模式");
                } else {
                    long minutes = Math.max(0, TimeUnit.MILLISECONDS.toMinutes(remainingTime));
                    long seconds = Math.max(0, TimeUnit.MILLISECONDS.toSeconds(remainingTime) % 60);
                    timeInfo.append(String.format("剩余%d分%d秒", minutes, seconds));
                }
                
                status.append(String.format("\n⏳ 第%d轮任务执行状态 (%s):\n", 
                    runCount, timeInfo.toString()));
                // 获取主任务状态
                BaseTask mainTask = null;
                try {
                    Class<?> appHookClass = Class.forName("fansirsqi.xposed.sesame.hook.ApplicationHook");
                    java.lang.reflect.Method getTaskMethod = appHookClass.getDeclaredMethod("getMainTask");
                    getTaskMethod.setAccessible(true);
                    mainTask = (BaseTask) getTaskMethod.invoke(null);
                } catch (Exception e) {
                    Log.error(TAG, "获取主任务状态失败: " + e.getMessage());
                }
                for (ModelTask task : taskList) {
                    if (task.isEnable()) {
                         String taskStatus;
                         if (mainTask != null) {
                             Thread taskThread = mainTask.getThread();
                             if (taskThread != null && taskThread.isAlive()) {
                                // 计算任务已执行时间
                                long currentTime = System.currentTimeMillis();
                                long taskStartTime = mainTask.getTaskStartTime();
                                if (taskStartTime > 0 && taskStartTime <= currentTime) {
                                    long duration = currentTime - taskStartTime;
                                    taskStatus = String.format("⚡正在执行 (%d秒)", TimeUnit.MILLISECONDS.toSeconds(duration));
                                } else {
                                    taskStatus = "⚡正在执行";
                                }
                             } else if (taskThread == null) {
                                 // 计算下次执行时间
                                 long nextExecTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(BaseModel.taskWaitTime.getValue());
                                 String nextTimeStr = String.format("%02d:%02d:%02d", 
                                     TimeUnit.MILLISECONDS.toHours(nextExecTime) % 24,
                                     TimeUnit.MILLISECONDS.toMinutes(nextExecTime) % 60,
                                     TimeUnit.MILLISECONDS.toSeconds(nextExecTime) % 60);
                                 taskStatus = String.format("⏰等待执行 (下次: %s)", nextTimeStr);
                             } else {
                                // 显示任务完成用时
                                long taskStartTime = mainTask.getTaskStartTime();
                                long taskEndTime = mainTask.getTaskEndTime();
                                if (taskStartTime > 0 && taskEndTime > taskStartTime) {
                                    long duration = taskEndTime - taskStartTime;
                                    taskStatus = String.format("✅已完成 (用时%d秒)", TimeUnit.MILLISECONDS.toSeconds(duration));
                                } else {
                                    taskStatus = "✅已完成";
                                }
                             }
                         } else {
                             taskStatus = "❓状态未知";
                         }
                         status.append(String.format("- %s: %s\n", task.getName(), taskStatus));
                    }
                }
                Log.record(TAG, status.toString());
                
            } catch (InterruptedException e) {
                // 记录中断，但继续等待
                Log.record(TAG, String.format("第%d轮任务等待被中断，但将继续等待。线程信息: [ID=%d, Name=%s]", runCount, threadId, currentThread.getName()));
                remainingTime = TimeUnit.MINUTES.toMillis(10) - (System.currentTimeMillis() - startTime);
            }
        }
        
        Log.record(TAG, String.format("第%d轮任务等待超时（10分钟），线程信息: [ID=%d, Name=%s]", runCount, threadId, currentThread.getName()));
    }
}
