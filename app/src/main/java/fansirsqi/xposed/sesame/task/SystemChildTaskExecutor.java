package fansirsqi.xposed.sesame.task;

import fansirsqi.xposed.sesame.hook.ApplicationHook;
import fansirsqi.xposed.sesame.util.GlobalThreadPools;
import fansirsqi.xposed.sesame.util.Log;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SystemChildTaskExecutor 类实现了 ChildTaskExecutor 接口，用于执行和管理子任务，
 * 支持在指定时间延迟后执行子任务，并且支持任务取消和任务组的管理。
 */
public class SystemChildTaskExecutor implements ChildTaskExecutor {
    private static final String TAG = "SystemChildTaskExecutor";

    /**
     * 用于存储按组分类的子任务的Future。
     * 结构: Map<groupName, Map<taskId, Future<?>>>
     */
    private final Map<String, Map<String, Future<?>>> groupChildTaskFuturesMap = new ConcurrentHashMap<>();

    public SystemChildTaskExecutor() {
        // Handler不再需要，因为调度将由全局调度器处理
    }

    @Override
    public Boolean addChildTask(ModelTask.ChildModelTask childTask) {
        String group = childTask.getGroup();
        String taskId = childTask.getId();

        Runnable executionRunnable = () -> {
            try {
                if (childTask.getIsCancel()) {
                    return;
                }
                // 内部不再需要sleep，延迟已由调度器处理
                childTask.run();
            } catch (Throwable t) {
                Log.printStackTrace(TAG, "子任务执行异常: " + taskId, t);
            } finally {
                cleanupTask(group, taskId, childTask);
            }
        };

        long delay = childTask.getExecTime() - System.currentTimeMillis();
        Future<?> future;

        if (delay > 0) {
            future = GlobalThreadPools.schedule(executionRunnable, delay, TimeUnit.MILLISECONDS);
        } else {
            future = GlobalThreadPools.submit(executionRunnable);
        }

        if (future != null) {
            groupChildTaskFuturesMap.computeIfAbsent(group, k -> new ConcurrentHashMap<>()).put(taskId, future);
            childTask.setCancelTask(() -> future.cancel(true));
            return true;
        }

        return false;
    }

    private void cleanupTask(String group, String taskId, ModelTask.ChildModelTask childTask) {
        Map<String, Future<?>> taskFutures = groupChildTaskFuturesMap.get(group);
        if (taskFutures != null) {
            taskFutures.remove(taskId);
        }
        childTask.getModelTask().removeChildTask(taskId);
    }

    @Override
    public void removeChildTask(ModelTask.ChildModelTask childTask) {
        childTask.cancel();
    }

    @Override
    public Boolean clearGroupChildTask(String group) {
        Map<String, Future<?>> taskFutures = groupChildTaskFuturesMap.get(group);
        if (taskFutures != null) {
            for (Future<?> future : taskFutures.values()) {
                future.cancel(true);
            }
            taskFutures.clear();
        }
        return true;
    }

    @Override
    public void clearAllChildTask() {
        for (Map<String, Future<?>> taskFutures : groupChildTaskFuturesMap.values()) {
            for (Future<?> future : taskFutures.values()) {
                future.cancel(true);
            }
        }
        groupChildTaskFuturesMap.clear();
    }
}