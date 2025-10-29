package fansirsqi.xposed.sesame.hook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.*;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import androidx.annotation.NonNull;
import lombok.Setter;
import org.luckypray.dexkit.DexKitBridge;
import java.io.File;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Method;
import java.lang.reflect.Member;
import java.lang.reflect.InvocationTargetException;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import fansirsqi.xposed.sesame.BuildConfig;
import fansirsqi.xposed.sesame.data.Config;
import fansirsqi.xposed.sesame.data.General;
import fansirsqi.xposed.sesame.data.RunType;
import fansirsqi.xposed.sesame.data.Status;
import fansirsqi.xposed.sesame.data.ViewAppInfo;
import fansirsqi.xposed.sesame.entity.AlipayVersion;
import fansirsqi.xposed.sesame.hook.rpc.bridge.NewRpcBridge;
import fansirsqi.xposed.sesame.hook.rpc.bridge.OldRpcBridge;
import fansirsqi.xposed.sesame.hook.rpc.bridge.RpcBridge;
import fansirsqi.xposed.sesame.hook.rpc.bridge.RpcVersion;
import fansirsqi.xposed.sesame.hook.rpc.intervallimit.RpcIntervalLimit;
import fansirsqi.xposed.sesame.hook.server.ModuleHttpServer;
import fansirsqi.xposed.sesame.model.BaseModel;
import fansirsqi.xposed.sesame.model.Model;
import fansirsqi.xposed.sesame.newutil.DataStore;
import fansirsqi.xposed.sesame.task.MainTask;
import fansirsqi.xposed.sesame.task.ModelTask;
import fansirsqi.xposed.sesame.task.TaskRunnerAdapter;
import fansirsqi.xposed.sesame.util.AssetUtil;
import fansirsqi.xposed.sesame.util.Detector;
import fansirsqi.xposed.sesame.util.Files;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.NetworkUtils;
import fansirsqi.xposed.sesame.util.Notify;
import fansirsqi.xposed.sesame.util.PermissionUtil;
import fansirsqi.xposed.sesame.util.TimeUtil;
import fansirsqi.xposed.sesame.util.maps.UserMap;
import fansirsqi.xposed.sesame.util.GlobalThreadPools;
import fansirsqi.xposed.sesame.hook.rpc.debug.DebugRpc;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;
import kotlin.jvm.JvmStatic;
import lombok.Getter;

public class ApplicationHook {
    static final String TAG = ApplicationHook.class.getSimpleName();
    public XposedInterface xposedInterface = null;
    @Getter
    @Setter
    private ModuleHttpServer httpServer;
    private static final String modelVersion = BuildConfig.VERSION_NAME;
    

    /**
     * 智能调度器管理器
     * 自动切换调度器 + 自动补偿延迟
     */
    private static boolean smartSchedulerInitialized = false;

    @Getter
    private static ClassLoader classLoader = null;
    private static Object microApplicationContextObject = null;

    @SuppressLint("StaticFieldLeak")
    static volatile Context appContext = null;


    @JvmStatic
    public static Context getAppContext() {
        return appContext;
    }

    /**
     * 确保智能调度器已初始化
     */
    private static synchronized void ensureScheduler() {
        if (appContext == null) {
            Log.debug(TAG, "⚠️ 无法初始化调度器: appContext 为 null (可能应用刚启动)");
            return;
        }
        
        if (!smartSchedulerInitialized) {
            try {
                SmartSchedulerManager.INSTANCE.initialize(appContext);
                smartSchedulerInitialized = true;
            } catch (Exception e) {
                Log.error(TAG, "❌ 智能调度器初始化失败: " + e.getMessage());
                Log.printStackTrace(TAG, e);
            }
        }
    }
    
    /**
     * 调度器适配器 - 使用智能管理器
     */
    private static class SchedulerAdapter {
        static void scheduleExactExecution(long delayMillis, long nextExecutionTime) {
            SmartSchedulerManager.INSTANCE.scheduleExactExecution(delayMillis, nextExecutionTime);
        }
        
        static void scheduleDelayedExecution(long delayMillis) {
            SmartSchedulerManager.INSTANCE.scheduleDelayedExecution(delayMillis);
        }
        
        static boolean scheduleWakeupAlarm(long triggerAtMillis, int requestCode, boolean isMainAlarm) {
            return SmartSchedulerManager.INSTANCE.scheduleWakeupAlarm(triggerAtMillis, requestCode, isMainAlarm);
        }
        
        static void cancelAllWakeupAlarms() {
            SmartSchedulerManager.INSTANCE.cancelAllWakeupAlarms();
        }
    }

    @Getter
    static AlipayVersion alipayVersion = new AlipayVersion("");

    private static volatile boolean hooked = false;

    @JvmStatic
    public static boolean isHooked() {
        return hooked;
    }

    private static volatile boolean init = false;
    static volatile Calendar dayCalendar;
    @Getter
    static volatile boolean offline = false;
    private static volatile boolean alarmTriggeredFlag = false;
    @Getter
    static final AtomicInteger reLoginCount = new AtomicInteger(0);



    @SuppressLint("StaticFieldLeak")
    static Service service;
    @Getter
    static Handler mainHandler;
    /**
     * -- GETTER --
     * 获取主任务实例 - 供任务调度使用
     */
    @Getter
    static MainTask mainTask;

    static volatile RpcBridge rpcBridge;
    private static final Object rpcBridgeLock = new Object();
    @Getter
    private static RpcVersion rpcVersion;

    private static PowerManager.WakeLock wakeLock;

    public static void setOffline(boolean offline) {
        ApplicationHook.offline = offline;
    }

    private static volatile long lastExecTime = 0; // 添加为类成员变量
    public static volatile long nextExecutionTime = 0;
    private static final long MAX_INACTIVE_TIME = 3600000; // 最大不活动时间：1小时

    static {
        dayCalendar = Calendar.getInstance();
        dayCalendar.set(Calendar.HOUR_OF_DAY, 0);
        dayCalendar.set(Calendar.MINUTE, 0);
        dayCalendar.set(Calendar.SECOND, 0);
    }

    private final static Method deoptimizeMethod;

    static {
        Method m = null;
        try {
            m = XposedBridge.class.getDeclaredMethod("deoptimizeMethod", Member.class);
        } catch (Throwable t) {
            XposedBridge.log("E/" + TAG + " " + android.util.Log.getStackTraceString(t));
        }
        deoptimizeMethod = m;
    }

    static void deoptimizeMethod(Class<?> c) throws InvocationTargetException, IllegalAccessException {
        for (Method m : c.getDeclaredMethods()) {
            if (deoptimizeMethod != null && m.getName().equals("makeApplicationInner")) {
                deoptimizeMethod.invoke(null, m);
                if (BuildConfig.DEBUG)
                    XposedBridge.log("D/" + TAG + " Deoptimized " + m.getName());
            }
        }
    }

    /**
     * 调度下次执行（公共静态方法）
     */
    @JvmStatic
    public static void scheduleNextExecution() {
        scheduleNextExecutionInternal(lastExecTime);
    }

    /**
     * 调度定时执行（内部静态方法）
     *
     * @param lastExecTime 上次执行时间
     */
    private static void scheduleNextExecutionInternal(long lastExecTime) {
        try {
            // 检查长时间未执行的情况
            checkInactiveTime();
            int checkInterval = BaseModel.getCheckInterval().getValue();
            List<String> execAtTimeList = BaseModel.getExecAtTimeList().getValue();
            if (execAtTimeList != null && execAtTimeList.contains("-1")) {
                Log.record(TAG, "定时执行未开启");
                return;
            }

            long delayMillis = checkInterval; // 默认使用配置的检查间隔
            long targetTime = 0;

            try {
                if (execAtTimeList != null) {
                    Calendar lastExecTimeCalendar = TimeUtil.getCalendarByTimeMillis(lastExecTime);
                    Calendar nextExecTimeCalendar = TimeUtil.getCalendarByTimeMillis(lastExecTime + checkInterval);
                    for (String execAtTime : execAtTimeList) {
                        Calendar execAtTimeCalendar = TimeUtil.getTodayCalendarByTimeStr(execAtTime);
                        if (execAtTimeCalendar != null && lastExecTimeCalendar.compareTo(execAtTimeCalendar) < 0 && nextExecTimeCalendar.compareTo(execAtTimeCalendar) > 0) {
                            Log.record(TAG, "设置定时执行:" + execAtTime);
                            targetTime = execAtTimeCalendar.getTimeInMillis();
                            delayMillis = targetTime - lastExecTime;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.runtime(TAG, "execAtTime err:：" + e.getMessage());
                Log.printStackTrace(TAG, e);
            }

            // 使用调度器（协程或 WorkManager）
            nextExecutionTime = targetTime > 0 ? targetTime : (lastExecTime + delayMillis);
            
            if (appContext == null) {
                Log.error(TAG, "❌ 无法调度任务：appContext 为 null");
                return;
            }
            
            ensureScheduler();
            SchedulerAdapter.scheduleExactExecution(delayMillis, nextExecutionTime);
        } catch (Exception e) {
            Log.runtime(TAG, "scheduleNextExecution：" + e.getMessage());
            Log.printStackTrace(TAG, e);
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private void loadNativeLibs(Context context, File soFile) {
        try {
            File finalSoFile = AssetUtil.INSTANCE.copyStorageSoFileToPrivateDir(context, soFile);
            if (finalSoFile != null) {
                System.load(finalSoFile.getAbsolutePath());
                Log.runtime(TAG, "Loading " + soFile.getName() + " from :" + finalSoFile.getAbsolutePath());
            } else {
                Detector.INSTANCE.loadLibrary(soFile.getName().replace(".so", "").replace("lib", ""));
            }
        } catch (Exception e) {
            Log.error(TAG, "载入so库失败！！");
            Log.printStackTrace(e);
        }
    }

    /**
     * ✅ 原有新版入口：LibXposed / LSPosed ≥ 1.9 使用
     */
    public void loadPackage(XposedModuleInterface.PackageLoadedParam lpparam) {
        Log.runtime(TAG, "xposed start loadPackage: " + lpparam.getPackageName());
        if (!General.PACKAGE_NAME.equals(lpparam.getPackageName())) return;
        classLoader = lpparam.getClassLoader();
        handleHookLogic(classLoader, lpparam.getPackageName(), lpparam.getApplicationInfo().sourceDir, null);
    }

    /**
     * ✅ 新增旧版兼容入口：传统 Xposed / EdXposed / LSPosed < 1.9 使用
     */
    public void loadPackageCompat(XC_LoadPackage.LoadPackageParam lpparam) {
        Log.runtime(TAG, "xp82 start loadPackageCompat: " + lpparam.packageName);
        XposedBridge.log(TAG + "|Hook in  " + lpparam.packageName + " in process ${lpparam.processName}");
        if (!General.PACKAGE_NAME.equals(lpparam.packageName)) return;
        classLoader = lpparam.classLoader;
        // 注意：旧版没有 ApplicationInfo.sourceDir，需要自己从 appInfo 获取
        String apkPath = lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null;
        handleHookLogic(classLoader, lpparam.packageName, apkPath, lpparam);
    }


    @SuppressLint("PrivateApi")
    private void handleHookLogic(ClassLoader classLoader, String packageName, String apkPath, Object rawParam) {
        XposedBridge.log(TAG + "|handleHookLogic " + packageName + " scuess!");
        if (hooked) return;
        hooked = true;
        
        // Hook验证码关闭功能（需要在应用初始化之前就Hook配置写入）
        try {
            CaptchaHook.INSTANCE.setupHook(classLoader);
            Log.runtime(TAG, "验证码Hook系统已初始化");
        } catch (Throwable t) {
            Log.runtime(TAG, "验证码Hook初始化失败");
            Log.printStackTrace(TAG, t);
        }
        
        try {
            // 在Hook Application.attach 之前，先 deoptimize LoadedApk.makeApplicationInner
            try {
                Class<?> loadedApkClass = classLoader.loadClass("android.app.LoadedApk");
                deoptimizeMethod(loadedApkClass);
            } catch (Throwable t) {
                Log.runtime(TAG, "deoptimize makeApplicationInner err:");
                Log.printStackTrace(TAG, t);
            }
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    mainHandler = new Handler(Looper.getMainLooper());
                    appContext = (Context) param.args[0];

                    registerBroadcastReceiver(appContext);
                    // 初始化调度器（协程或 WorkManager）
                    ensureScheduler();

                    PackageInfo pInfo = appContext.getPackageManager().getPackageInfo(packageName, 0);
                    Log.runtime(TAG, "handleLoadPackage alipayVersion: " + alipayVersion.getVersionString());
                    loadNativeLibs(appContext, AssetUtil.INSTANCE.getCheckerDestFile());
                    loadNativeLibs(appContext, AssetUtil.INSTANCE.getDexkitDestFile());
                    boolean b = pInfo.versionName != null;
                    if (b) {
                        if (pInfo.versionName.equals("10.7.26.8100")) {
                            HookUtil.INSTANCE.fuckAccounLimit(classLoader);
                        }
                        Log.runtime(TAG, alipayVersion.getVersionString() + "Not support fuck");
                    }

                    if (BuildConfig.DEBUG) {
                        try {
                            Log.runtime(TAG, "start service for debug rpc");
                            // 使用管理器，仅主进程启动并防重复
                            fansirsqi.xposed.sesame.hook.server.ModuleHttpServerManager.INSTANCE.startIfNeeded(
                                    8080,
                                    "ET3vB^#td87sQqKaY*eMUJXP",
                                    XposedEnv.processName,
                                    General.PACKAGE_NAME
                            );
                        } catch (Throwable e) {
                            Log.printStackTrace(e);
                        }
                    } else {
                        Log.runtime(TAG, "need not start service for debug rpc");
                    }
                    super.afterHookedMethod(param);
                }
            });
        } catch (Exception e) {
            Log.printStackTrace(e);
        }

        try {
            XposedHelpers.findAndHookMethod("com.alipay.mobile.quinox.LauncherActivity", classLoader, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Log.runtime(TAG, "hook onResume after start");
                            String targetUid = getUserId();
                            Log.runtime(TAG, "onResume targetUid: " + targetUid);
                            if (targetUid == null) {
                                Log.record(TAG, "onResume:用户未登录");
                                Toast.show("用户未登录");
                                return;
                            }
                            if (!init) {
                                if (initHandler(true)) {
                                    init = true;
                                }
                                Log.runtime(TAG, "initHandler success");
                                return;
                            }
                            String currentUid = UserMap.getCurrentUid();
                            Log.runtime(TAG, "onResume currentUid: " + currentUid);
                            if (!targetUid.equals(currentUid)) {
                                if (currentUid != null) {
                                    initHandler(true);  // 重新初始化
                                    lastExecTime = 0;   // 重置执行时间，防止被间隔逻辑拦截
                                    TaskRunnerAdapter adapter = new TaskRunnerAdapter();
                                    adapter.run(); // 立即执行任务
                                    Log.record(TAG, "用户已切换");
                                    Toast.show("用户已切换");
                                    return;
                                }
                                HookUtil.INSTANCE.hookUser(classLoader);
                            }
                            if (offline) {
                                offline = false;
                                execHandler();
                                ((Activity) param.thisObject).finish();
                                Log.runtime(TAG, "Activity reLogin");
                            }
                            // 如果所有特殊情况都未命中，执行一次常规任务检查
                            execHandler();
                            Log.runtime(TAG, "hook onResume after end");
                        }
                    });
            Log.runtime(TAG, "hook login successfully");
        } catch (Throwable t) {
            Log.runtime(TAG, "hook login err");
            Log.printStackTrace(TAG, t);
        }
        try {
            XposedHelpers.findAndHookMethod("android.app.Service", classLoader, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Service appService = (Service) param.thisObject;
                            if (!General.CURRENT_USING_SERVICE.equals(appService.getClass().getCanonicalName())) {
                                return;
                            }

                            Log.runtime(TAG, "Service onCreate");
                            appContext = appService.getApplicationContext();
                            boolean isok = Detector.INSTANCE.isLegitimateEnvironment(appContext);
                            if (isok) {
                                Detector.INSTANCE.dangerous(appContext);
                                return;
                            }
                            try (DexKitBridge ignored = DexKitBridge.create(apkPath)) {
                                // Other use cases
                                Log.runtime(TAG, "hook dexkit successfully");
                            }
                            service = appService;
                            mainTask = MainTask.newInstance("MAIN_TASK", () -> {
                                try {
                                    boolean isAlarmTriggered = alarmTriggeredFlag;
                                    if (isAlarmTriggered) {
                                        alarmTriggeredFlag = false; // Consume the flag
                                    }

                                    if (!init) {
                                        Log.record(TAG, "️🐣跳过执行-未初始化");
                                        return;
                                    }
                                    if (!Config.isLoaded()) {
                                        Log.record(TAG, "️⚙跳过执行-用户模块配置未加载");
                                        return;
                                    }

                                    if (isAlarmTriggered) {
                                        Log.record(TAG, "⏰ 开始新一轮任务 (定时任务触发)");
                                    } else {
                                        if (lastExecTime == 0) {
                                            Log.record(TAG, "▶️ 首次手动触发，开始运行");
                                        } else {
                                            if (BaseModel.getManualTriggerAutoSchedule().getValue()) {
                                                Log.record(TAG, "手动APP触发，已开启");
                                                TaskRunnerAdapter adapter = new TaskRunnerAdapter();
                                                adapter.run();
                                            }
                                            Log.record(TAG, "手动APP触发，已关闭");

                                            return;
                                        }
                                    }

                                    long currentTime = System.currentTimeMillis();
                                    // 获取最小执行间隔（2秒）
                                    final long MIN_EXEC_INTERVAL = 2000;
                                    // 计算距离上次执行的时间间隔
                                    long timeSinceLastExec = currentTime - lastExecTime;

                                    if (isAlarmTriggered && timeSinceLastExec < MIN_EXEC_INTERVAL) {
                                        Log.record(TAG, "⚠️ 定时任务触发间隔较短(" + timeSinceLastExec + "ms)，跳过执行，安排下次执行");
                                        ensureScheduler();
                                        SchedulerAdapter.scheduleDelayedExecution(BaseModel.getCheckInterval().getValue());
                                        return;
                                    }
                                    String currentUid = UserMap.getCurrentUid();
                                    String targetUid = HookUtil.INSTANCE.getUserId(classLoader);
                                    if (targetUid == null || !targetUid.equals(currentUid)) {
                                        Log.record(TAG, "用户切换或为空，重新登录");
                                        reLogin();
                                        return;
                                    }
                                    lastExecTime = currentTime; // 更新最后执行时间
                                    // 方式1：直接使用数组转换
                                    TaskRunnerAdapter adapter = new TaskRunnerAdapter();
                                    adapter.run();
                                    scheduleNextExecutionInternal(lastExecTime);
                                } catch (Exception e) {
                                    Log.record(TAG, "❌执行异常");
                                    Log.printStackTrace(TAG, e);
                                }
                            });
                            dayCalendar = Calendar.getInstance();
                            if (initHandler(true)) {
                                init = true;
                            }
                        }
                    }

            );
            Log.runtime(TAG, "hook service onCreate successfully");
        } catch (Throwable t) {
            Log.runtime(TAG, "hook service onCreate err");
            Log.printStackTrace(TAG, t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.app.Service", classLoader, "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Service service = (Service) param.thisObject;
                            if (!General.CURRENT_USING_SERVICE.equals(service.getClass().getCanonicalName()))
                                return;
                            Log.record(TAG, "支付宝前台服务被销毁");
                            Notify.updateStatusText("支付宝前台服务被销毁");
                            destroyHandler(true);
                            try {
                                fansirsqi.xposed.sesame.hook.server.ModuleHttpServerManager.INSTANCE.stopIfRunning();
                            } catch (Throwable ignore) {
                            }
                            restartByBroadcast();
                        }
                    });
        } catch (Throwable t) {
            Log.runtime(TAG, "hook service onDestroy err");
            Log.printStackTrace(TAG, t);
        }

        HookUtil.INSTANCE.hookOtherService(classLoader);

        hooked = true;
    }

    /**
     * 设置定时唤醒
     */
    private static void setWakenAtTimeAlarm() {
        setWakenAtTimeAlarmWithRetry(0);
    }

    /**
     * 设置定时唤醒（带重试机制）
     */
    private static void setWakenAtTimeAlarmWithRetry(int retryCount) {
        try {
            if (appContext == null) {
                if (retryCount < 3) {
                    final int currentRetry = retryCount + 1;
                    Log.runtime(TAG, "appContext 未初始化，延迟" + (currentRetry * 2) + "秒后重试 (第" + currentRetry + "次)");
                    if (mainHandler != null) {
                        mainHandler.postDelayed(() -> setWakenAtTimeAlarmWithRetry(currentRetry), currentRetry * 2000L);
                    }
                } else {
                    Log.error(TAG, "appContext 初始化超时，放弃设置定时任务");
                }
                return;
            }
            
            // 确保调度器已初始化
            ensureScheduler();

            List<String> wakenAtTimeList = BaseModel.getWakenAtTimeList().getValue();
            if (wakenAtTimeList != null && wakenAtTimeList.contains("-1")) {
                Log.record(TAG, "定时唤醒未开启");
                return;
            }

            // 清理旧唤醒任务
            unsetWakenAtTimeAlarm();

            // 设置0点唤醒
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.DAY_OF_MONTH, 1);
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);

            boolean success = SchedulerAdapter.scheduleWakeupAlarm(calendar.getTimeInMillis(), 0, true);
            if (success) {
                Log.record(TAG, "⏰ 设置0点定时任务成功");
            } else {
                Log.runtime(TAG, "⏰ 设置0点定时任务失败");
            }

            // 设置自定义时间点唤醒
            if (wakenAtTimeList != null && !wakenAtTimeList.isEmpty()) {
                Calendar nowCalendar = Calendar.getInstance();
                int successCount = 0;
                for (int i = 1, len = wakenAtTimeList.size(); i < len; i++) {
                    try {
                        String wakenAtTime = wakenAtTimeList.get(i);
                        Calendar wakenAtTimeCalendar = TimeUtil.getTodayCalendarByTimeStr(wakenAtTime);
                        if (wakenAtTimeCalendar != null && wakenAtTimeCalendar.compareTo(nowCalendar) > 0) {
                            boolean customSuccess = SchedulerAdapter.scheduleWakeupAlarm(wakenAtTimeCalendar.getTimeInMillis(), i, false);
                            if (customSuccess) {
                                successCount++;
                                Log.record(TAG, "⏰ 设置定时任务成功: " + wakenAtTime);
                            }
                        }
                    } catch (Exception e) {
                        Log.runtime(TAG, "设置自定义唤醒时间失败: " + e.getMessage());
                    }
                }
                if (successCount > 0) {
                    Log.record(TAG, "⏰ 共设置了 " + successCount + " 个自定义定时任务");
                }
            }
        } catch (Exception e) {
            Log.runtime(TAG, "setWakenAtTimeAlarm err:");
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 取消所有定时任务
     */
    private static void unsetWakenAtTimeAlarm() {
        ensureScheduler();
        SchedulerAdapter.cancelAllWakeupAlarms();
        Log.record(TAG, "已取消所有定时任务");
    }

    private static synchronized Boolean initHandler(Boolean force) {
        try {
            if (init && !force) { // 已经初始化 & 非强制，直接跳过
                return true;
            }

            if (init) {
                destroyHandler(true); // 重新初始化时销毁旧的handler
            }

            // 调度器确保可用
            ensureScheduler();

            Model.initAllModel(); // 在所有服务启动前装模块配置
            if (service == null) {
                return false;
            }

            if (force) {
                String userId = HookUtil.INSTANCE.getUserId(classLoader);
                if (userId == null) {
                    Log.record(TAG, "initHandler: 用户未登录");
                    Toast.show("用户未登录");
                    return false;
                }

                HookUtil.INSTANCE.hookUser(classLoader);
                String startMsg = "芝麻粒-TK 开始初始化...";
                Log.record(TAG, startMsg);
                Log.record(TAG, "⚙️模块版本：" + modelVersion);
                Log.record(TAG, "📦应用版本：" + alipayVersion.getVersionString());
                Log.record(TAG, "📶网络类型：" + NetworkUtils.INSTANCE.getNetworkType());

                Config.load(userId); // 加载配置
                if (!Config.isLoaded()) {
                    Log.record(TAG, "用户模块配置加载失败");
                    Toast.show("用户模块配置加载失败");
                    return false;
                }

                // 后台运行权限检查 (WorkManager 不需要精确闹钟权限)
                if (!init && !PermissionUtil.checkBatteryPermissions()) {
                    Log.record(TAG, "支付宝无始终在后台运行权限");
                    mainHandler.postDelayed(
                            () -> {
                                if (!PermissionUtil.checkOrRequestBatteryPermissions(appContext)) {
                                    Toast.show("请授予支付宝始终在后台运行权限");
                                }
                            },
                            2000);
                }

                Notify.start(service);

//                BaseModel baseModel = Model.getModel(BaseModel.class);
//                if (baseModel == null) {
//                    Log.error(TAG, "BaseModel 未找到 初始化失败");
//                    Notify.setStatusTextDisabled();
//                    return false;
//                }
//
//                if (!baseModel.getEnableField().getValue()) {
//                    Log.record(TAG, "❌ 芝麻粒已禁用");
//                    Toast.show("❌ 芝麻粒已禁用");
//                    Notify.setStatusTextDisabled();
//                    return false;
//                }
                try {
                    PowerManager pm = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, service.getClass().getName());
                    wakeLock.acquire(10 * 60 * 1000L); // 10分钟
                } catch (Throwable t) {
                    Log.record(TAG, "唤醒锁申请失败:");
                    Log.printStackTrace(t);
                }

                setWakenAtTimeAlarm();

                synchronized (rpcBridgeLock) {
                    if (BaseModel.getNewRpc().getValue()) {
                        rpcBridge = new NewRpcBridge();
                    } else {
                        rpcBridge = new OldRpcBridge();
                    }
                    rpcBridge.load();
                    rpcVersion = rpcBridge.getVersion();
                }

                if (BaseModel.getNewRpc().getValue() && BaseModel.getDebugMode().getValue()) {
                    HookUtil.INSTANCE.hookRpcBridgeExtension(
                            classLoader,
                            BaseModel.getSendHookData().getValue(),
                            BaseModel.getSendHookDataUrl().getValue()
                    );
                    HookUtil.INSTANCE.hookDefaultBridgeCallback(classLoader);
                }

                   Model.bootAllModel(classLoader);
                   Status.load(userId);
                   DataStore.INSTANCE.init(Files.CONFIG_DIR);
                   updateDay(userId);
                   String successMsg = "芝麻粒-TK 加载成功✨";
                   Log.record(successMsg);
                   Toast.show(successMsg);
               }

               offline = false;
               init = true;
               
               // 首次初始化后，立即执行一次任务并调度下次执行
               execHandler();
               
               return true;
        } catch (Throwable th) {
            Log.printStackTrace(TAG, "startHandler", th);
            Toast.show("芝麻粒加载失败 🎃");
            return false;
        }
    }



    /**
     * 销毁处理程序
     *
     * @param force 是否强制销毁
     */
    static synchronized void destroyHandler(Boolean force) {
        try {
            if (force) {
                if (service != null) {
                    stopHandler();
                    BaseModel.destroyData();
                    Status.unload();
                    Notify.stop();
                    RpcIntervalLimit.INSTANCE.clearIntervalLimit();
                    Config.unload();
                    UserMap.unload();
                }
                

                if (wakeLock != null) {
                    wakeLock.release();
                    wakeLock = null;
                }
                synchronized (rpcBridgeLock) {
                    if (rpcBridge != null) {
                        rpcVersion = null;
                        rpcBridge.unload();
                        rpcBridge = null;
                    }
                }
            } else {
                ModelTask.stopAllTask();
            }
        } catch (Throwable th) {
            Log.runtime(TAG, "stopHandler err:");
            Log.printStackTrace(TAG, th);
        }
    }

    static void execHandler() {
        mainTask.startTask(false);// 非强制执行，避免重复排队
    }

    /**
     * 检查长时间未执行的情况，如果超过阈值则自动重启
     * 特别针对0点后可能出现的执行中断情况
     */
    private static void checkInactiveTime() {
        try {
            if (lastExecTime == 0) {
                return; // 首次执行，跳过检查
            }
            long currentTime = System.currentTimeMillis();
            long inactiveTime = currentTime - lastExecTime;
            // 检查是否经过了0点
            Calendar lastExecCalendar = Calendar.getInstance();
            lastExecCalendar.setTimeInMillis(lastExecTime);
            Calendar currentCalendar = Calendar.getInstance();
            currentCalendar.setTimeInMillis(currentTime);
            boolean crossedMidnight = lastExecCalendar.get(Calendar.DAY_OF_YEAR) != currentCalendar.get(Calendar.DAY_OF_YEAR) ||
                    lastExecCalendar.get(Calendar.YEAR) != currentCalendar.get(Calendar.YEAR);
            // 如果超过最大不活动时间或者跨越了0点但已经过了一段时间
            if (inactiveTime > MAX_INACTIVE_TIME ||
                    (crossedMidnight && currentCalendar.get(Calendar.HOUR_OF_DAY) >= 1)) {
                Log.record(TAG, "⚠️ 检测到长时间未执行(" + (inactiveTime / 60000) + "分钟)，可能跨越0点，尝试重新登录");
                reLogin();
            }
        } catch (Exception e) {
            Log.runtime(TAG, "checkInactiveTime err:" + e.getMessage());
            Log.printStackTrace(TAG, e);
        }
    }

    private static void stopHandler() {
        mainTask.stopTask();
        ModelTask.stopAllTask();
    }

    public static void updateDay(String userId) {
        Calendar nowCalendar = Calendar.getInstance();
        try {
            if (dayCalendar == null) {
                dayCalendar = (Calendar) nowCalendar.clone();
                dayCalendar.set(Calendar.HOUR_OF_DAY, 0);
                dayCalendar.set(Calendar.MINUTE, 0);
                dayCalendar.set(Calendar.SECOND, 0);
                Log.record(TAG, "初始化日期为：" + dayCalendar.get(Calendar.YEAR) + "-" + (dayCalendar.get(Calendar.MONTH) + 1) + "-" + dayCalendar.get(Calendar.DAY_OF_MONTH));
                setWakenAtTimeAlarm();
                return;
            }

            int nowYear = nowCalendar.get(Calendar.YEAR);
            int nowMonth = nowCalendar.get(Calendar.MONTH);
            int nowDay = nowCalendar.get(Calendar.DAY_OF_MONTH);
            if (dayCalendar.get(Calendar.YEAR) != nowYear || dayCalendar.get(Calendar.MONTH) != nowMonth || dayCalendar.get(Calendar.DAY_OF_MONTH) != nowDay) {
                dayCalendar = (Calendar) nowCalendar.clone();
                dayCalendar.set(Calendar.HOUR_OF_DAY, 0);
                dayCalendar.set(Calendar.MINUTE, 0);
                dayCalendar.set(Calendar.SECOND, 0);
                Log.record(TAG, "日期更新为：" + nowYear + "-" + (nowMonth + 1) + "-" + nowDay);
                setWakenAtTimeAlarm();
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }

        try {
            Status.save(nowCalendar);
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }


    public static void reLoginByBroadcast() {
        try {
            appContext.sendBroadcast(new Intent("com.eg.android.AlipayGphone.sesame.reLogin"));
        } catch (Throwable th) {
            Log.runtime(TAG, "sesame sendBroadcast reLogin err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /**
     * 通过广播发送重启模块服务的指令。
     */
    public static void restartByBroadcast() {
        try {
            appContext.sendBroadcast(new Intent("com.eg.android.AlipayGphone.sesame.restart"));
        } catch (Throwable th) {
            Log.runtime(TAG, "发送重启广播时出错:");
            Log.printStackTrace(TAG, th);
        }
    }

    /**
     * 通过广播发送立即执行一次任务的指令。
     */
    public static void executeByBroadcast() {
        try {
            appContext.sendBroadcast(new Intent("com.eg.android.AlipayGphone.sesame.execute"));
        } catch (Throwable th) {
            Log.runtime(TAG, "发送执行广播时出错:");
            Log.printStackTrace(TAG, th);
        }
    }


    public static Object getMicroApplicationContext() {
        if (microApplicationContextObject == null) {
            try {
                Class<?> alipayApplicationClass = XposedHelpers.findClass(
                        "com.alipay.mobile.framework.AlipayApplication", classLoader
                );
                Object alipayApplicationInstance = XposedHelpers.callStaticMethod(
                        alipayApplicationClass, "getInstance"
                );
                if (alipayApplicationInstance == null) {
                    return null;
                }
                microApplicationContextObject = XposedHelpers.callMethod(
                        alipayApplicationInstance, "getMicroApplicationContext"
                );
            } catch (Throwable t) {
                Log.printStackTrace(t);
            }
        }
        return microApplicationContextObject;
    }

    public static Object getServiceObject(String service) {
        try {
            return XposedHelpers.callMethod(getMicroApplicationContext(), "findServiceByInterface", service);
        } catch (Throwable th) {
            Log.runtime(TAG, "getUserObject err");
            Log.printStackTrace(TAG, th);
        }
        return null;
    }

    public static Object getUserObject() {
        try {
            return XposedHelpers.callMethod(
                    getServiceObject(
                            XposedHelpers.findClass("com.alipay.mobile.personalbase.service.SocialSdkContactService", classLoader).getName()
                    ),
                    "getMyAccountInfoModelByLocal");
        } catch (Throwable th) {
            Log.runtime(TAG, "getUserObject err");
            Log.printStackTrace(TAG, th);
        }
        return null;
    }

    public static String getUserId() {
        try {
            Object userObject = getUserObject();
            if (userObject != null) {
                return (String) XposedHelpers.getObjectField(userObject, "userId");
            }
        } catch (Throwable th) {
            Log.runtime(TAG, "getUserId err");
            Log.printStackTrace(TAG, th);
        }
        return null;
    }

    public static void reLogin() {
        mainHandler.post(
                () -> {
                    long delayMillis;
                    if (reLoginCount.get() < 5) {
                        delayMillis = reLoginCount.getAndIncrement() * 5000L;
                    } else {
                        delayMillis = Math.max(BaseModel.getCheckInterval().getValue(), 180_000);
                    }

                    // 使用调度器（协程或 WorkManager）
                    ensureScheduler();
                    SchedulerAdapter.scheduleDelayedExecution(delayMillis);

                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setClassName(General.PACKAGE_NAME, General.CURRENT_USING_ACTIVITY);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    offline = true;
                    appContext.startActivity(intent);
                });
    }


    static class AlipayBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                Log.runtime(TAG, "Alipay got Broadcast " + action + " intent:" + intent);
                if (action != null) {
                    switch (action) {
                        case "com.eg.android.AlipayGphone.sesame.restart":
                            Log.printStack(TAG);
                            GlobalThreadPools.INSTANCE.execute(() -> initHandler(true));
                            break;
                       case "com.eg.android.AlipayGphone.sesame.execute":
                           Log.printStack(TAG);
                           if (intent.getBooleanExtra("alarm_triggered", false)) {
                               alarmTriggeredFlag = true;
                               Log.record(TAG, "⏰ 收到定时任务触发广播 (协程调度器)");
                           }
                           // 如果已初始化，直接执行任务；否则先初始化
                           if (init) {
                               Log.record(TAG, "✅ 模块已初始化，开始执行任务");
                               execHandler();
                           } else {
                               Log.record(TAG, "⚠️ 模块未初始化，开始初始化流程");
                               GlobalThreadPools.INSTANCE.execute(() -> {
                                   if (initHandler(false)) {
                                       Log.record(TAG, "✅ 初始化成功，开始执行任务");
                                       execHandler();
                                   } else {
                                       Log.error(TAG, "❌ 初始化失败，任务无法执行");
                                   }
                               });
                           }
                           break;
                        case "com.eg.android.AlipayGphone.sesame.reLogin":
                            Log.printStack(TAG);
                            GlobalThreadPools.INSTANCE.execute(ApplicationHook::reLogin);
                            break;
                        case "com.eg.android.AlipayGphone.sesame.status":
                            // 状态查询处理
                            Log.printStack(TAG);
                            if (ViewAppInfo.getRunType() == RunType.DISABLE) {
                                Intent replyIntent = new Intent("fansirsqi.xposed.sesame.status");
                                replyIntent.putExtra("EXTRA_RUN_TYPE", RunType.ACTIVE.getNickName());
                                replyIntent.setPackage(General.MODULE_PACKAGE_NAME);
                                context.sendBroadcast(replyIntent);
                                Log.system(TAG, "Replied with status: " + RunType.ACTIVE.getNickName());
                            }
                            break;
                        case "com.eg.android.AlipayGphone.sesame.rpctest":
                            GlobalThreadPools.INSTANCE.execute(() -> {
                                try {
                                    String method = intent.getStringExtra("method");
                                    String data = intent.getStringExtra("data");
                                    String type = intent.getStringExtra("type");
                                    Log.runtime(TAG, "收到RPC测试请求 - Method: " + method + ", Type: " + type);
                                    DebugRpc rpcInstance = new DebugRpc();
                                    rpcInstance.start(method, data, type);
                                } catch (Throwable th) {
                                    Log.runtime(TAG, "sesame 测试RPC请求失败:");
                                    Log.printStackTrace(TAG, th);
                                }
                            });
                            break;
                        default:
                            // 协程调度器会自动处理任务触发，无需额外处理
                            Log.debug(TAG, "收到未知广播: " + action);
                            break;
                    }
                }
            } catch (Throwable t) {
                Log.printStackTrace(TAG, "AlipayBroadcastReceiver.onReceive err:", t);
            }
        }
    }

    /**
     * 注册广播接收器以监听支付宝相关动作。
     *
     * @param context 应用程序上下文
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    // 忽略Lint关于注册广播接收器时未指定导出属性的警告
    void registerBroadcastReceiver(Context context) {
        //创建一个IntentFilter实例，用于过滤出我们需要捕获的广播
        try {
            IntentFilter intentFilter = getIntentFilter();
            // 根据Android SDK版本注册广播接收器
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 在Android 13及以上版本，注册广播接收器并指定其可以被其他应用发送的广播触发
                context.registerReceiver(new AlipayBroadcastReceiver(), intentFilter, Context.RECEIVER_EXPORTED);
            } else {
                // 在Android 13以下版本，注册广播接收器
                context.registerReceiver(new AlipayBroadcastReceiver(), intentFilter);
            }
            // 记录成功注册广播接收器的日志
            Log.runtime(TAG, "hook registerBroadcastReceiver successfully");
        } catch (Throwable th) {
            // 记录注册广播接收器失败的日志
            Log.runtime(TAG, "hook registerBroadcastReceiver err:");
            // 打印异常堆栈信息
            Log.printStackTrace(TAG, th);
        }
    }

    @NonNull
    private static IntentFilter getIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("com.eg.android.AlipayGphone.sesame.restart"); // 重启支付宝服务的动作
        intentFilter.addAction("com.eg.android.AlipayGphone.sesame.execute"); // 执行特定命令的动作
        intentFilter.addAction("com.eg.android.AlipayGphone.sesame.reLogin"); // 重新登录支付宝的动作
        intentFilter.addAction("com.eg.android.AlipayGphone.sesame.status"); // 查询支付宝状态的动作
        intentFilter.addAction("com.eg.android.AlipayGphone.sesame.rpctest"); // 调试RPC的动作
        return intentFilter;
    }

}

