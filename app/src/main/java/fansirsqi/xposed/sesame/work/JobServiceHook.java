package fansirsqi.xposed.sesame.work;

import android.annotation.SuppressLint;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import fansirsqi.xposed.sesame.hook.ApplicationHook;
import fansirsqi.xposed.sesame.util.Log;

/**
 * JobService Hook 集成器
 * 
 * 🎯 核心思想：
 * 利用支付宝现有的JobService基础设施来实现更可靠的后台任务调度
 * 
 * 📋 工作原理：
 * 1. 【Hook安装】：拦截支付宝ChargingJobService和通用JobService的onStartJob方法
 * 2. 【主动调度】：使用Android JobScheduler API主动调度支付宝的JobService
 * 3. 【任务拦截】：当JobService被系统调用时，检查JobID是否在Sesame范围内
 * 4. 【任务执行】：如果是Sesame任务，拦截并执行我们的任务逻辑
 * 5. 【双重保障】：ChargingJobService失败时自动尝试通用JobService
 * 
 * 🔧 技术细节：
 * - JobID范围：999000-999999（避免与支付宝冲突）
 * - 优先级：ChargingJobService > 通用JobService
 * - 调度参数：无网络要求、不持久化、放宽系统状态限制
 * - 执行方式：独立线程执行，避免阻塞JobService主线程
 * 
 * 💡 优势：
 * - 系统级调度，即使应用被杀死也能执行
 * - 利用支付宝现有基础设施，兼容性好
 * - 智能回退机制，提高调度成功率
 * - 详细日志记录，便于调试和监控
 */
public class JobServiceHook {
    private static final String TAG = "JobServiceHook";
    
    // 支付宝JobService相关常量
    private static final String ALIPAY_PACKAGE = "com.eg.android.AlipayGphone";
    private static final String CHARGING_JOB_SERVICE = "com.alipay.mobile.framework.service.ChargingJobService";
    private static final String COMMON_JOB_SERVICE = "com.alipay.mobile.common.job.JobService";
    
    // 我们的Job ID范围 (使用特殊范围避免冲突)
    private static final int SESAME_JOB_ID_BASE = 998000;
    private static final AtomicInteger jobIdCounter = new AtomicInteger(SESAME_JOB_ID_BASE);
    
    private static final AtomicBoolean hookInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean jobServiceAvailable = new AtomicBoolean(false);
    
    /**
     * 安装JobService Hook
     */
    public static void installHook(ClassLoader classLoader) {
        if (hookInstalled.getAndSet(true)) {
            return;
        }
        
        try {
            // Hook ChargingJobService
            hookChargingJobService(classLoader);
            // Hook 通用JobService
            hookCommonJobService(classLoader);
            
            Log.record(TAG, "JobService Hook安装成功");
        } catch (Exception e) {
            Log.error(TAG, "JobService Hook安装失败: " + e.getMessage());
            Log.printStackTrace(TAG, e);
        }
    }
    
    /**
     * Hook ChargingJobService
     */
    private static void hookChargingJobService(ClassLoader classLoader) {
        try {
            Class<?> chargingJobServiceClass = XposedHelpers.findClass(CHARGING_JOB_SERVICE, classLoader);
            // Hook onStartJob 方法
            XposedHelpers.findAndHookMethod(chargingJobServiceClass, "onStartJob",
                android.app.job.JobParameters.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        JobParameters jobParams = (JobParameters) param.args[0];
                        int jobId = jobParams.getJobId();
                        // 检查是否是我们的Job
                        if (isSesameJob(jobId)) {
                            Log.record(TAG, "拦截到Sesame Job执行请求, JobID: " + jobId);
                            // 执行我们的任务
                            executeSesameTask(jobId);
                            // 阻止原始逻辑执行
                            param.setResult(false);
                            return;
                        }
                        
                        Log.record(TAG, "检测到支付宝ChargingJobService执行, JobID: " + jobId);
                        jobServiceAvailable.set(true);
                    }
                });
                
            Log.record(TAG, "ChargingJobService Hook安装成功");
        } catch (Exception e) {
            Log.error(TAG, "Hook ChargingJobService失败: " + e.getMessage());
        }
    }
    
    /**
     * Hook 通用JobService
     */
    private static void hookCommonJobService(ClassLoader classLoader) {
        try {
            Class<?> commonJobServiceClass = XposedHelpers.findClass(COMMON_JOB_SERVICE, classLoader);
            // Hook onStartJob 方法
            XposedHelpers.findAndHookMethod(commonJobServiceClass, "onStartJob",
                android.app.job.JobParameters.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        JobParameters jobParams = (JobParameters) param.args[0];
                        int jobId = jobParams.getJobId();
                        if (isSesameJob(jobId)) {
                            Log.record(TAG, "通用JobService拦截到Sesame Job, JobID: " + jobId);
                            executeSesameTask(jobId);
                            param.setResult(false);
                            return;
                        }
                        Log.record(TAG, "检测到支付宝通用JobService执行, JobID: " + jobId);
                        jobServiceAvailable.set(true);
                    }
                });
                
            Log.record(TAG, "通用JobService Hook安装成功");
        } catch (Exception e) {
            Log.error(TAG, "Hook 通用JobService失败: " + e.getMessage());
        }
    }
    
    /**
     * 调度JobService任务
     * 核心思路：
     * 1. 使用Android JobScheduler API主动调度支付宝的JobService
     * 2. 优先尝试ChargingJobService（exported=true，更容易调用）
     * 3. 失败时回退到通用JobService
     * 4. 当支付宝JobService被系统执行时，我们的Hook会拦截并执行Sesame任务
     * 
     * @param context 应用上下文
     * @param delayMillis 延迟执行时间（毫秒）
     * @return 是否调度成功
     */
    @SuppressLint("DefaultLocale")
    public static boolean scheduleJobServiceTask(Context context, long delayMillis) {


        try {
            // 获取系统JobScheduler服务
            JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (jobScheduler == null) {
                Log.error(TAG, "无法获取JobScheduler服务");
                return false;
            }
            // 生成唯一的Job ID（避免与支付宝现有Job冲突）
            int jobId = jobIdCounter.incrementAndGet();
            // 策略1：优先尝试使用ChargingJobService
            // 这个服务在AndroidManifest.xml中声明为exported=true，更容易被外部调用
            JobInfo jobInfo = getJobInfo(delayMillis, jobId, CHARGING_JOB_SERVICE);
            int result = jobScheduler.schedule(jobInfo);
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.record(TAG, String.format("✓ ChargingJobService调度成功, JobID=%d, 延迟=%d秒", 
                    jobId, delayMillis / 1000));
                Log.record(TAG, String.format("🎯 任务将在 %d秒 后由系统执行，届时Hook会拦截并执行Sesame任务", 
                    delayMillis / 1000));
                return true;
            } else {
                Log.record(TAG, String.format("ChargingJobService调度失败(结果码=%d), 尝试通用JobService", result));
                
                // 策略2：回退到通用JobService
                // 如果ChargingJobService调度失败，尝试使用通用的JobService
                JobInfo fallbackJobInfo = getJobInfo(delayMillis, jobId, COMMON_JOB_SERVICE);
                int fallbackResult = jobScheduler.schedule(fallbackJobInfo);
                
                if (fallbackResult == JobScheduler.RESULT_SUCCESS) {
                    Log.record(TAG, String.format("✓ 通用JobService调度成功, JobID=%d, 延迟=%d秒", 
                        jobId, delayMillis / 1000));
                    Log.record(TAG, String.format("🎯 任务将在 %d秒 后由系统执行，届时Hook会拦截并执行Sesame任务", 
                        delayMillis / 1000));
                    return true;
                } else {
                    Log.error(TAG, String.format("所有JobService调度失败, ChargingJobService结果=%d, 通用JobService结果=%d", 
                        result, fallbackResult));
                    return false;
                }
            }
            
        } catch (Exception e) {
            Log.error(TAG, "调度JobService任务失败: " + e.getMessage());
            Log.printStackTrace(TAG, e);
            return false;
        }
    }

    /**
     * 创建JobInfo对象
     * 关键点：
     * 1. 使用运行时的真实包名（支付宝的包名），而不是硬编码
     * 2. 创建指向支付宝JobService的ComponentName
     * 3. 设置合适的调度参数
     * 
     * @param delayMillis 延迟时间
     * @param jobId 任务ID
     * @param serviceName JobService类名
     * @return 配置好的JobInfo对象
     */
    @SuppressLint("DefaultLocale")
    private static JobInfo getJobInfo(long delayMillis, int jobId, String serviceName) {
        // 获取当前运行应用的包名（即支付宝的包名）
        // 这样可以确保ComponentName指向正确的应用
        Context context = ApplicationHook.getAppContext();
        String packageName = context != null ? context.getPackageName() : ALIPAY_PACKAGE;
        // 创建指向支付宝JobService的ComponentName
        ComponentName jobComponent = new ComponentName(packageName, serviceName);
        Log.record(TAG, String.format("创建JobInfo: 包名=%s, 服务=%s, JobID=%d", 
            packageName, serviceName, jobId));

        // 构建JobInfo对象
        JobInfo.Builder jobBuilder = getBuilder(delayMillis, jobId, jobComponent);
        return jobBuilder.build();
    }

    /**
     * 构建JobInfo.Builder对象，设置调度参数
     * 调度策略说明：
     * 1. 最小延迟：按用户指定时间执行
     * 2. 最大延迟：增加1分钟容错时间
     * 3. 网络要求：无需网络连接（NETWORK_TYPE_NONE）
     * 4. 持久化：不持久化，避免系统重启后执行
     * 5. 退避策略：失败后30秒线性退避
     * 6. 系统要求：Android 8.0+放宽所有系统状态要求
     * 
     * @param delayMillis 延迟时间
     * @param jobId 任务ID 
     * @param jobComponent JobService组件名
     * @return 配置好的JobInfo.Builder
     */
    @SuppressLint("DefaultLocale")
    private static JobInfo.Builder getBuilder(long delayMillis, int jobId, ComponentName jobComponent) {
        JobInfo.Builder jobBuilder = new JobInfo.Builder(jobId, jobComponent)
            .setMinimumLatency(delayMillis)              // 最小延迟时间
            .setOverrideDeadline(delayMillis + 60000)    // 最大延迟时间（+1分钟容错）
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE) // 不需要网络连接
            .setPersisted(false)                         // 不持久化（避免重启后执行）
            .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_LINEAR); // 失败退避：30秒线性
        // Android 8.0+ 需要额外设置系统状态要求
        // 设置为false表示不需要这些条件，任何时候都可以执行
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            jobBuilder.setRequiresBatteryNotLow(false)    // 不要求电量充足
                     .setRequiresCharging(false)          // 不要求充电状态
                     .setRequiresDeviceIdle(false)        // 不要求设备空闲
                     .setRequiresStorageNotLow(false);    // 不要求存储空间充足
        } else {
            Log.record(TAG, String.format("📱 Android %d 无需额外系统状态配置", Build.VERSION.SDK_INT));
        }
        return jobBuilder;
    }

    /**
     * 调度精确时间执行的JobService任务
     */
    @SuppressLint("DefaultLocale")
    public static boolean scheduleExactJobServiceTask(Context context, long exactTimeMillis) {
        long currentTime = System.currentTimeMillis();
        long delayMillis = exactTimeMillis - currentTime;
        
        if (delayMillis <= 0) {
            Log.record(TAG, "精确执行时间已过，立即执行任务");
            // 使用DELAYED类型执行，确保会触发下次调度
            XposedScheduler.executeDelayedTaskImmediately(context);
            return true;
        }
        
        Log.record(TAG, String.format("调度精确JobService任务，延迟=%d分钟", delayMillis / (60 * 1000)));
        return scheduleJobServiceTask(context, delayMillis);
    }
    
    /**
     * 取消所有Sesame Job
     */
    public static void cancelAllSesameJobs(Context context) {

        try {
            JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (jobScheduler != null) {
                // 取消我们的Job范围内的所有任务
                jobScheduler.cancelAll();
                Log.record(TAG, "已取消所有Sesame JobService任务");
            }
        } catch (Exception e) {
            Log.error(TAG, "取消JobService任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 执行Sesame任务（由Hook拦截调用）
     * 执行流程：
     * 1. 当系统调度支付宝JobService时
     * 2. 我们的Hook会检查JobID是否在Sesame范围内
     * 3. 如果是，就拦截并调用此方法执行Sesame任务
     * 4. 在独立线程中执行，避免阻塞JobService主线程
     * 
     * @param jobId 任务ID，用于日志跟踪
     */
    private static void executeSesameTask(int jobId) {
        try {
            Log.record(TAG, "🎯 Hook拦截成功，开始执行Sesame任务, JobID: " + jobId);
            // 在新线程中执行任务，避免阻塞JobService主线程
            // JobService的onStartJob运行在主线程，如果执行时间过长会导致ANR
            new Thread(() -> {
                try {
                    // 设置线程名称，方便调试
                    Thread.currentThread().setName("SesameJobService_" + jobId);
                    // 执行Sesame核心任务逻辑
                    ApplicationHook.getMainTask().startTask(false);
                    Log.record(TAG, "✅ Sesame任务执行完成, JobID: " + jobId);
                    // 记录执行后的调度器状态
                    Log.record(TAG, "📊 任务执行后调度器状态检查：");
                    XposedScheduler.logStatus();
                } catch (Exception e) {
                    Log.error(TAG, "❌ Sesame任务执行失败, JobID: " + jobId + ", 错误: " + e.getMessage());
                    Log.printStackTrace(TAG, e);
                }
            }).start();

        } catch (Exception e) {
            Log.error(TAG, "启动Sesame任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 检查JobID是否是Sesame的任务
     * ID范围设计：
     * - Sesame使用999000-999999范围（1000个ID）
     * - 避免与支付宝现有JobID冲突
     * - 便于Hook识别和拦截
     * 
     * @param jobId 要检查的JobID
     * @return 是否是Sesame的任务
     */
    private static boolean isSesameJob(int jobId) {
        return jobId >= SESAME_JOB_ID_BASE && jobId < SESAME_JOB_ID_BASE + 1000;
    }
    
    /**
     * 检查JobService是否可用
     * 可用性判断：
     * - Hook已安装：确保能够拦截JobService调用
     * - 不需要等待支付宝主动触发：我们主动调度
     * 
     * @return JobService是否可用
     */
    public static boolean isJobServiceAvailable() {
        return hookInstalled.get();
    }
    
    /**
     * 获取调度器状态
     */
    @SuppressLint("DefaultLocale")
    public static void logStatus() {
        Log.record(TAG, String.format("JobService Hook状态: Hook已安装=%s, 可用性=%s, SDK版本=%d", 
            hookInstalled.get(), 
            isJobServiceAvailable(),
            Build.VERSION.SDK_INT));
    }
}
