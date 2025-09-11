package fansirsqi.xposed.sesame.task;

import android.os.Build;

import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.GlobalThreadPools;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * ProgramChildTaskExecutor 类实现了 ChildTaskExecutor 接口，用于管理和执行子任务。
 * 它现在使用全局线程池来执行任务。
 */
public class ProgramChildTaskExecutor implements ChildTaskExecutor {
    private static final String TAG = "ProgramChildTaskExecutor";
    /**
     * 用于存储按组分类的子任务的Future。
     * 结构: Map<groupName, Map<taskId, Future<?>>>
     */
    private final Map<String, Map<String, Future<?>>> groupChildTaskFuturesMap = new ConcurrentHashMap<>();

    @Override
    public Boolean addChildTask(ModelTask.ChildModelTask childTask) {
        String group = childTask.getGroup();
        String taskId = childTask.getId();

        Runnable taskRunnable = () -> {
            try {
                if (childTask.getIsCancel()) {
                    return;
                }
                // Thread.sleep() 已被移除，延迟操作由调度器处理
                childTask.run();
            } catch (Exception e) {
                Log.printStackTrace(e);
            } finally {
                // 清理工作
                Map<String, Future<?>> taskFutures = groupChildTaskFuturesMap.get(group);
                if (taskFutures != null) {
                    taskFutures.remove(taskId);
                }
                childTask.getModelTask().removeChildTask(taskId);
            }
        };

        long delay = childTask.getExecTime() - System.currentTimeMillis();
        Future<?> future;

        if (delay > 0) {
            future = GlobalThreadPools.schedule(taskRunnable, delay, TimeUnit.MILLISECONDS);
        } else {
            future = GlobalThreadPools.submit(taskRunnable);
        }

        if (future != null) {
            // 存储Future以便后续可以取消
            groupChildTaskFuturesMap.computeIfAbsent(group, k -> new ConcurrentHashMap<>()).put(taskId, future);
            childTask.setCancelTask(() -> future.cancel(true));
            return true;
        }
        return false;
    }

    @Override
    public void removeChildTask(ModelTask.ChildModelTask childTask) {
        childTask.cancel();
        // cancel() 会触发Future.cancel(), 进而中断线程，最终在finally块中进行清理
    }

    @Override
    public Boolean clearGroupChildTask(String group) {
        Map<String, Future<?>> taskFutures = groupChildTaskFuturesMap.get(group);
        if (taskFutures != null) {
            // 遍历并取消该组所有任务
            for (Future<?> future : taskFutures.values()) {
                future.cancel(true);
            }
            // 清空该组的Future映射
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

