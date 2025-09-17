package fansirsqi.xposed.sesame.util;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * @author: ghostxx
 * @date: 2025/9/11
 * @description: 全局线程池管理器，用于统一管理应用内的线程，避免重复创建销毁线程带来的开销。
 */
public class GlobalThreadPools {
    private static final String TAG = "GlobalThreadPools";

    /**
     * CPU核心数
     */
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    /**
     * 核心线程数
     * 根据CPU核心数动态计算，保证最佳性能。
     * 通常设置为 CPU核心数 + 1，以应对计算密集型和IO密集型任务。
     */
    private static final int CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 4));

    /**
     * 最大线程数
     * 允许线程池创建的最大线程数，防止资源过度消耗。
     */
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;

    /**
     * 线程空闲后的存活时间
     * 当线程数超过核心线程数时，多余的空闲线程在指定时间后会被销毁。
     */
    private static final long KEEP_ALIVE_TIME = 30L;

    /**
     * 任务调度器
     * 用于执行延迟或周期性任务，不会在等待时阻塞线程。
     */
    private static final ScheduledExecutorService SCHEDULER = new ScheduledThreadPoolExecutor(
            CORE_POOL_SIZE,
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "SesameScheduler-" + threadNumber.getAndIncrement());
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /**
     * 线程池实例
     * 使用 ThreadPoolExecutor 自定义线程池，以实现更精细的控制。
     * - corePoolSize: 核心线程数，即使空闲也保留在池中。
     * - maximumPoolSize: 线程池能够容纳同时执行的最大线程数。
     * - keepAliveTime: 多于corePoolSize的线程的空闲存活时间。
     * - workQueue: 用于保存等待执行的任务的阻塞队列。
     * - threadFactory: 用于创建新线程的工厂。
     */
    private static final ExecutorService THREAD_POOL = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAXIMUM_POOL_SIZE,
            KEEP_ALIVE_TIME,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(128), // 增加工作队列容量
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "SesamePool-" + threadNumber.getAndIncrement());
                    if (t.isDaemon()) {
                        t.setDaemon(false);
                    }
                    if (t.getPriority() != Thread.NORM_PRIORITY) {
                        t.setPriority(Thread.NORM_PRIORITY);
                    }
                    return t;
                }
            },
            // 添加拒绝策略，当线程池和队列都满了，会让调用者线程自己执行任务
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /**
     * 在全局线程池中执行一个任务。
     *
     * @param command 要执行的Runnable任务。
     */
    public static void execute(Runnable command) {
        try {
            THREAD_POOL.execute(command);
        } catch (Exception e) {
            Log.error(TAG, "执行任务异常: " + e.getMessage());
            Log.printStackTrace(e);
        }
    }

    /**
     * 在全局线程池中提交一个任务，并返回一个Future对象。
     *
     * @param task 要提交的Runnable任务。
     * @return 代表任务待定结果的 Future 对象，如果提交失败则返回 null。
     */
    public static Future<?> submit(Runnable task) {
        try {
            return THREAD_POOL.submit(task);
        } catch (Exception e) {
            Log.error(TAG, "提交任务异常: " + e.getMessage());
            Log.printStackTrace(e);
            return null;
        }
    }

    /**
     * 在全局调度器中安排一个延迟执行的任务。
     *
     * @param command 要执行的任务。
     * @param delay   延迟时间。
     * @param unit    时间单位。
     * @return一个 ScheduledFuture 对象，可用于取消任务或获取结果。
     */
    public static ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        try {
            return SCHEDULER.schedule(command, delay, unit);
        } catch (Exception e) {
            Log.error(TAG, "安排任务异常: " + e.getMessage());
            Log.printStackTrace(e);
            return null;
        }
    }

    /**
     * 使当前线程暂停指定的毫秒数。
     * 这是一个对 Thread.sleep 的封装，统一处理了 InterruptedException。
     *
     * @param millis 要暂停的毫秒数。
     */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Log.error(TAG, "线程休眠被中断: " + e.getMessage());
            // 重新设置中断状态，以便调用栈上层的代码可以感知到中断
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Log.error(TAG, "线程休眠时发生未知错误: " + t.getMessage());
            Log.printStackTrace(t);
        }
    }

    /**
     * 平滑地关闭指定的ExecutorService。
     * 会先尝试正常关闭，如果超时仍未关闭，则强制关闭。
     *
     * @param pool      要关闭的线程池。
     * @param timeout   等待的超时时间。
     * @param timeUnit  超时时间的单位。
     * @param poolName  线程池的名称，用于日志记录。
     */
    public static void shutdownAndAwaitTermination(ExecutorService pool, long timeout, TimeUnit timeUnit, String poolName) {
        if (pool == null || pool.isShutdown()) {
            return;
        }
        pool.shutdown(); // 禁用新任务提交
        try {
            // 等待现有任务在超时时间内完成
            if (!pool.awaitTermination(timeout, timeUnit)) {
                pool.shutdownNow(); // 取消当前执行的任务
                // 再次等待，以便响应取消操作
                if (!pool.awaitTermination(timeout, timeUnit)) {
                    Log.runtime(TAG, "线程池 " + poolName + " 未能终止");
                }
            }
        } catch (InterruptedException ie) {
            // （重新）取消任务，如果当前线程也被中断
            pool.shutdownNow();
            // 保留中断状态
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 关闭全局线程池。
     * 在应用退出时调用，以释放资源。
     */
    public static void shutdown() {
        shutdownAndAwaitTermination(THREAD_POOL, 5, TimeUnit.SECONDS, "GlobalThreadPool");
        shutdownAndAwaitTermination(SCHEDULER, 5, TimeUnit.SECONDS, "GlobalScheduler");
    }
}