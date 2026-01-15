package fansirsqi.xposed.sesame.hook;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Service;
import android.content.*;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import androidx.core.content.ContextCompat;

import de.robv.android.xposed.XSharedPreferences;
import fansirsqi.xposed.sesame.BuildConfig;
import fansirsqi.xposed.sesame.SesameApplication;
import fansirsqi.xposed.sesame.data.Config;
import fansirsqi.xposed.sesame.data.General;
import fansirsqi.xposed.sesame.data.Status;
import fansirsqi.xposed.sesame.entity.AlipayVersion;
import fansirsqi.xposed.sesame.hook.internal.LocationHelper;
import fansirsqi.xposed.sesame.hook.internal.SecurityBodyHelper;
import fansirsqi.xposed.sesame.hook.keepalive.SmartSchedulerManager;
import fansirsqi.xposed.sesame.hook.rpc.bridge.NewRpcBridge;
import fansirsqi.xposed.sesame.hook.rpc.bridge.OldRpcBridge;
import fansirsqi.xposed.sesame.hook.rpc.bridge.RpcBridge;
import fansirsqi.xposed.sesame.hook.rpc.bridge.RpcVersion;
import fansirsqi.xposed.sesame.hook.rpc.debug.DebugRpc;
import fansirsqi.xposed.sesame.hook.rpc.intervallimit.RpcIntervalLimit;
import fansirsqi.xposed.sesame.hook.server.ModuleHttpServer;
import fansirsqi.xposed.sesame.hook.server.ModuleHttpServerManager;
import fansirsqi.xposed.sesame.hook.simple.SimplePageManager;
import fansirsqi.xposed.sesame.model.BaseModel;
import fansirsqi.xposed.sesame.model.Model;
import fansirsqi.xposed.sesame.task.MainTask;
import fansirsqi.xposed.sesame.task.ModelTask;
import fansirsqi.xposed.sesame.task.TaskRunnerAdapter;
import fansirsqi.xposed.sesame.util.*;
import fansirsqi.xposed.sesame.util.maps.UserMap;
import kotlin.Unit;
import lombok.Getter;
import lombok.Setter;

import org.luckypray.dexkit.DexKitBridge;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;
import kotlin.jvm.JvmStatic;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ApplicationHook {
    static final String TAG = "ApplicationHook"; // 简化TAG
    public XposedInterface xposedInterface = null;
    @Getter
    @Setter
    private ModuleHttpServer httpServer;

    static String finalProcessName = "";

    // 广播接收器实例，用于注销
    private static AlipayBroadcastReceiver mBroadcastReceiver;

    private static class BroadcastActions {
        static final String RESTART = "com.eg.android.AlipayGphone.sesame.restart";
        static final String RE_LOGIN = "com.eg.android.AlipayGphone.sesame.reLogin";
        static final String STATUS = "com.eg.android.AlipayGphone.sesame.status";
        static final String RPC_TEST = "com.eg.android.AlipayGphone.sesame.rpctest";
    }

    private static class AlipayClasses {
        static final String APPLICATION = "com.alipay.mobile.framework.AlipayApplication";
        static final String SOCIAL_SDK = "com.alipay.mobile.personalbase.service.SocialSdkContactService";
        static final String LAUNCHER_ACTIVITY = "com.alipay.mobile.quinox.LauncherActivity";
        static final String SERVICE = "android.app.Service";
        static final String LOADED_APK = "android.app.LoadedApk";
    }

    @Getter
    private static ClassLoader classLoader = null;
    @Getter
    private static final Object microApplicationContextObject = null;

    @SuppressLint("StaticFieldLeak")
    static volatile Context appContext = null;

    @JvmStatic
    public static Context getAppContext() {
        return appContext;
    }

    // 任务锁
    private static final Object taskLock = new Object();
    private static volatile boolean isTaskRunning = false;

    private static class TaskLock implements AutoCloseable {
        private final boolean acquired;

        TaskLock() {
            synchronized (taskLock) {
                if (isTaskRunning) {
                    acquired = false;
                    throw new IllegalStateException("任务已在运行中");
                }
                isTaskRunning = true;
                acquired = true;
            }
        }

        @Override
        public void close() {
            if (acquired) {
                synchronized (taskLock) {
                    isTaskRunning = false;
                }
            }
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
    @Getter
    static final AtomicInteger reLoginCount = new AtomicInteger(0);
    private static volatile boolean batteryPermissionChecked = false;

    @SuppressLint("StaticFieldLeak")
    static Service service;
    @Getter
    static Handler mainHandler;
    @Getter
    static MainTask mainTask;

    static volatile RpcBridge rpcBridge;
    private static final Object rpcBridgeLock = new Object();
    @Getter
    private static RpcVersion rpcVersion;

    public static void setOffline(boolean offline) {
        ApplicationHook.offline = offline;
    }

    private static volatile long lastExecTime = 0;
    public static volatile long nextExecutionTime = 0;
    private static final long MAX_INACTIVE_TIME = 3600_000; // 1小时

    // Deoptimize 方法缓存
    private final static Method deoptimizeMethod;

    static {
        dayCalendar = Calendar.getInstance();
        resetToMidnight(dayCalendar);
        Method m = null;
        try {
            //noinspection JavaReflectionMemberAccess
            m = XposedBridge.class.getDeclaredMethod("deoptimizeMethod", Member.class);
        } catch (Throwable t) {
            // ignore
        }
        deoptimizeMethod = m;
    }

    // --- 入口方法 ---

    public void loadPackage(XposedModuleInterface.PackageLoadedParam lpparam) {
        if (!General.PACKAGE_NAME.equals(lpparam.getPackageName())) return;
        handleHookLogic(lpparam.getClassLoader(), lpparam.getPackageName(), lpparam.getApplicationInfo().sourceDir, lpparam);
    }

    public void loadPackageCompat(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!General.PACKAGE_NAME.equals(lpparam.packageName)) return;
        String apkPath = lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null;
        handleHookLogic(lpparam.classLoader, lpparam.packageName, apkPath, lpparam);
    }

    @SuppressLint("PrivateApi")
    private void handleHookLogic(ClassLoader loader, String packageName, String apkPath, Object rawParam) {
        classLoader = loader;

        // 1. 初始化配置读取
        XSharedPreferences prefs = new XSharedPreferences(General.MODULE_PACKAGE_NAME, SesameApplication.PREFERENCES_KEY);
        prefs.makeWorldReadable();

        // 2. 进程检查
        resolveProcessName(rawParam);
        if (!shouldHookProcess()) return;

        DataStore.INSTANCE.init(Files.CONFIG_DIR);
        if (hooked) return;
        hooked = true;

        // 3. 基础环境 Hook
        ModuleStatus.INSTANCE.detectFramework(classLoader);
        StatusManager.INSTANCE.updateStatus(ModuleStatus.INSTANCE.detectFramework(classLoader), packageName);
        VersionHook.installHook(classLoader);
        initReflection(classLoader);

        // 4. 功能模块 Hook
        try {
            CaptchaHook.INSTANCE.setupHook(classLoader);
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "验证码Hook初始化失败", t);
        }

        // 5. 核心生命周期 Hook
        hookApplicationAttach(packageName);
        hookLauncherResume();
        hookServiceLifecycle(apkPath);

        HookUtil.INSTANCE.hookOtherService(classLoader);
    }

    private void resolveProcessName(Object rawParam) {
        if (rawParam instanceof XC_LoadPackage.LoadPackageParam) {
            finalProcessName = ((XC_LoadPackage.LoadPackageParam) rawParam).processName;
        } else if (rawParam instanceof XposedModuleInterface.PackageLoadedParam) {
            finalProcessName = XposedEnv.INSTANCE.getProcessName();
        }
    }

    private boolean shouldHookProcess() {
        boolean isMainProcess = General.PACKAGE_NAME.equals(finalProcessName);
        if (!isMainProcess) {
            Log.record(TAG, "跳过辅助进程: " + finalProcessName);
            return false;
        }
        return true;
    }

    private void initReflection(ClassLoader loader) {
        try {
            XposedHelpers.findClass(AlipayClasses.APPLICATION, loader);
            XposedHelpers.findClass(AlipayClasses.SOCIAL_SDK, loader);
        } catch (Throwable t) {
            // ignore
        }

        try {
            @SuppressLint("PrivateApi") Class<?> loadedApkClass = loader.loadClass(AlipayClasses.LOADED_APK);
            deoptimizeClass(loadedApkClass);
        } catch (Throwable t) {
            // ignore
        }
    }

    private void hookApplicationAttach(String packageName) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    appContext = (Context) param.args[0];
                    mainHandler = new Handler(Looper.getMainLooper());
                    Log.init(appContext);
                    ensureScheduler();

                    SecurityBodyHelper.INSTANCE.init(classLoader);
                    LocationHelper.INSTANCE.init(classLoader);

                    initVersionInfo(packageName);
                    loadLibs();
                    // 特殊版本处理
                    if (VersionHook.hasVersion() && alipayVersion.compareTo(new AlipayVersion("10.7.26.8100")) == 0) {
                        HookUtil.INSTANCE.fuckAccounLimit(classLoader);
                    }

                    initSimplePageManager();
                }
            });
        } catch (Exception e) {
            Log.printStackTrace(TAG, "Hook attach failed", e);
        }
    }

    private void hookLauncherResume() {
        try {
            XposedHelpers.findAndHookMethod(AlipayClasses.LAUNCHER_ACTIVITY, classLoader, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String targetUid = HookUtil.INSTANCE.getUserId(classLoader);
                    if (targetUid == null) {
                        Toast.INSTANCE.show("用户未登录");
                        return;
                    }
                    if (!init) {
                        if (initHandler()) init = true;
                        return;
                    }
                    String currentUid = UserMap.INSTANCE.getCurrentUid();
                    if (!targetUid.equals(currentUid)) {
                        if (currentUid != null) {
                            initHandler();
                            lastExecTime = 0;
                            Toast.INSTANCE.show("用户已切换");
                            return;
                        }
                        HookUtil.INSTANCE.hookUser(classLoader);
                    }
                }
            });
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "Hook Launcher failed", t);
        }
    }

    private void hookServiceLifecycle(String apkPath) {
        try {
            XposedHelpers.findAndHookMethod(AlipayClasses.SERVICE, classLoader, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Service appService = (Service) param.thisObject;
                    if (!General.CURRENT_USING_SERVICE.equals(appService.getClass().getCanonicalName())) {
                        return;
                    }

                    service = appService;
                    appContext = appService.getApplicationContext();
                    ensureScheduler();

                    if (Detector.INSTANCE.isLegitimateEnvironment(appContext)) {
                        Detector.INSTANCE.dangerous(appContext);
                        return;
                    }

                    try (DexKitBridge ignored = DexKitBridge.create(apkPath)) {
                        Log.record(TAG, "Hook DexKit successfully");
                    }

                    // 初始化主任务
                    mainTask = MainTask.newInstance("MAIN_TASK", ApplicationHook::runMainTaskLogic);

                    dayCalendar = Calendar.getInstance();
                    if (initHandler()) {
                        init = true;
                    }
                }
            });

            XposedHelpers.findAndHookMethod(AlipayClasses.SERVICE, classLoader, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Service s = (Service) param.thisObject;
                    if (General.CURRENT_USING_SERVICE.equals(s.getClass().getCanonicalName())) {
                        Notify.updateStatusText("支付宝前台服务被销毁");
                        destroyHandler();
                        restartByBroadcast();
                    }
                }
            });
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "Hook Service failed", t);
        }
    }

    private static void runMainTaskLogic() {
        try (TaskLock ignored = new TaskLock()) {
            if (!init || !Config.isLoaded()) return;

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastExecTime < 2000) {
                Log.record(TAG, "⚠️ 间隔过短，跳过");
                SmartSchedulerManager.INSTANCE.schedule(BaseModel.Companion.getCheckInterval().getValue(), "间隔重试", () -> {
                    execHandler();
                    return Unit.INSTANCE;
                });
                return;
            }

            String currentUid = UserMap.INSTANCE.getCurrentUid();
            String targetUid = HookUtil.INSTANCE.getUserId(classLoader);
            if (targetUid == null || !targetUid.equals(currentUid)) {
                reOpenApp();
                return;
            }

            lastExecTime = currentTime;
            new TaskRunnerAdapter().run();
            scheduleNextExecutionInternal(lastExecTime);
        } catch (IllegalStateException e) {
            Log.record(TAG, "⚠️ " + e.getMessage());
        } catch (Exception e) {
            Log.printStackTrace(TAG, e);
        }
    }

    private void initVersionInfo(String packageName) {
        if (VersionHook.hasVersion()) {
            alipayVersion = VersionHook.getCapturedVersion();
            Log.record(TAG, "📦 支付宝版本(Hook): " + alipayVersion.getVersionString());
        } else {
            try {
                PackageInfo pInfo = appContext.getPackageManager().getPackageInfo(packageName, 0);
                alipayVersion = new AlipayVersion(Objects.requireNonNullElse(pInfo.versionName, ""));
            } catch (Exception e) {
                alipayVersion = new AlipayVersion("");
            }
        }
    }

    private void loadLibs() {
        loadNativeLibs(appContext, AssetUtil.INSTANCE.getCheckerDestFile());
        loadNativeLibs(appContext, AssetUtil.INSTANCE.getDexkitDestFile());
    }

    private void initSimplePageManager() {
        if (VersionHook.hasVersion() && alipayVersion.getVersionString() != null) {
            // 例如：10.6.58.8000 <= 10.6.58.88888，但 10.6.59 > 10.6.58.88888
            if (alipayVersion.compareTo(new AlipayVersion("10.6.58.88888")) <= 0) {
                SimplePageManager.INSTANCE.enableWindowMonitoring(classLoader);
                SimplePageManager.INSTANCE.addHandler("com.alipay.mobile.nebulax.xriver.activity.XRiverActivity", new Captcha1Handler());
                SimplePageManager.INSTANCE.addHandler("com.eg.android.AlipayGphone.AlipayLogin", new Captcha2Handler());
            } else {
                Log.debug(TAG, "支付宝版本 " + alipayVersion.getVersionString() + " 高于 10.6.58，跳过 SimplePageManager 初始化");
            }
        } else {
            Log.debug(TAG, "无法获取支付宝版本信息，跳过 SimplePageManager 初始化");
        }
    }

    // --- 辅助方法 ---

    private static void ensureScheduler() {
        if (appContext != null) {
            SmartSchedulerManager.INSTANCE.initialize(appContext);
        }
    }

    static void deoptimizeClass(Class<?> c) throws InvocationTargetException, IllegalAccessException {
        if (deoptimizeMethod == null) return;
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals("makeApplicationInner")) {
                deoptimizeMethod.invoke(null, m);
            }
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private void loadNativeLibs(Context context, File soFile) {
        try {
            File finalSoFile = AssetUtil.INSTANCE.copyStorageSoFileToPrivateDir(context, soFile);
            if (finalSoFile != null) {
                System.load(finalSoFile.getAbsolutePath());
            } else {
                Detector.INSTANCE.loadLibrary(soFile.getName().replace(".so", "").replace("lib", ""));
            }
        } catch (Exception e) {
            Log.printStackTrace(TAG, "载入so库失败: " + soFile.getName(), e);
        }
    }

    // --- 调度与执行 ---

    @JvmStatic
    public static void scheduleNextExecution() {
        scheduleNextExecutionInternal(lastExecTime);
    }

    private static void scheduleNextExecutionInternal(long lastTime) {
        try {
            checkInactiveTime();
            int checkInterval = BaseModel.Companion.getCheckInterval().getValue();
            List<String> execAtTimeList = BaseModel.Companion.getExecAtTimeList().getValue();

            if (execAtTimeList != null && execAtTimeList.contains("-1")) {
                Log.record(TAG, "定时执行未开启");
                return;
            }

            long delayMillis = checkInterval;
            long targetTime = 0;
            // ... (计算 delayMillis 逻辑保持不变) ...

            // 计算逻辑简化省略，保持原逻辑
            if (execAtTimeList != null) {
                Calendar lastCal = TimeUtil.getCalendarByTimeMillis(lastTime);
                Calendar nextCal = TimeUtil.getCalendarByTimeMillis(lastTime + checkInterval);
                for (String timeStr : execAtTimeList) {
                    Calendar execCal = TimeUtil.getTodayCalendarByTimeStr(timeStr);
                    if (execCal != null && lastCal.compareTo(execCal) < 0 && nextCal.compareTo(execCal) > 0) {
                        Log.record(TAG, "设置定时执行:" + timeStr);
                        targetTime = execCal.getTimeInMillis();
                        delayMillis = targetTime - lastTime;
                        break;
                    }
                }
            }

            nextExecutionTime = targetTime > 0 ? targetTime : (lastTime + delayMillis);
            ensureScheduler();

            SmartSchedulerManager.INSTANCE.schedule(delayMillis, "轮询任务", () -> {
                execHandler();
                return Unit.INSTANCE;
            });
        } catch (Exception e) {
            Log.printStackTrace(TAG, "scheduleNextExecution failed", e);
        }
    }

    // --- 初始化核心逻辑 ---

    private static synchronized Boolean initHandler() {
        try {
            if (init) destroyHandler();

            // 调试模式初始化
            if (BuildConfig.DEBUG) {
                try {
                    ModuleHttpServerManager.INSTANCE.startIfNeeded(8080, "ET3vB^#td87sQqKaY*eMUJXP", XposedEnv.processName, General.PACKAGE_NAME);
                    registerBroadcastReceiver(appContext);
                } catch (Throwable e) { /* ignore */ }
            }

            ensureScheduler();
            Model.initAllModel();

            if (service == null) return false;
            String userId = HookUtil.INSTANCE.getUserId(classLoader);
            if (userId == null) {
                Toast.INSTANCE.show("用户未登录");
                return false;
            }

            HookUtil.INSTANCE.hookUser(classLoader);
            Log.record(TAG, "芝麻粒-TK 开始初始化...");

            Config.load(userId);
            if (!Config.isLoaded()) return false;

            Notify.start(service);
            setWakenAtTimeAlarm();

            synchronized (rpcBridgeLock) {
                rpcBridge = BaseModel.Companion.getNewRpc().getValue() ? new NewRpcBridge() : new OldRpcBridge();
                rpcBridge.load();
                rpcVersion = rpcBridge.getVersion();
            }

            if (BaseModel.Companion.getNewRpc().getValue() && BaseModel.Companion.getDebugMode().getValue()) {
                HookUtil.INSTANCE.hookRpcBridgeExtension(classLoader, BaseModel.Companion.getSendHookData().getValue(), BaseModel.Companion.getSendHookDataUrl().getValue());
                HookUtil.INSTANCE.hookDefaultBridgeCallback(classLoader);
            }

            TokenHooker.INSTANCE.start(userId);
            checkBatteryPermission();

            Model.bootAllModel(classLoader);
            Status.load(userId);
            updateDay();

            String successMsg = "Loaded SesameTk " + BuildConfig.VERSION_NAME + "✨";
            Log.record(successMsg);
            Toast.INSTANCE.show(successMsg);

            offline = false;
            init = true;
            execHandler();
            return true;
        } catch (Throwable th) {
            Log.printStackTrace(TAG, "startHandler", th);
            return false;
        }
    }

    private static void checkBatteryPermission() {
        if (!BaseModel.Companion.getBatteryPerm().getValue() || batteryPermissionChecked) return;

        boolean hasPermission = PermissionUtil.checkBatteryPermissions(appContext);
        batteryPermissionChecked = true;
        if (!hasPermission) {
            Log.record(TAG, "无后台运行权限，2秒后申请");
            mainHandler.postDelayed(() -> {
                if (!PermissionUtil.checkOrRequestBatteryPermissions(appContext)) {
                    Toast.INSTANCE.show("请授予支付宝始终在后台运行权限");
                }
            }, 2000);
        }
    }

    static synchronized void destroyHandler() {
        try {
            GlobalThreadPools.INSTANCE.shutdownAndRestart();

            if (service != null) {
                stopHandler();
                BaseModel.Companion.destroyData();
                Status.unload();
                Notify.stop();
                RpcIntervalLimit.INSTANCE.clearIntervalLimit();
                Config.unload();
                UserMap.unload();
            }

            SmartSchedulerManager.INSTANCE.cleanup();

            // 注销广播接收器
            unregisterBroadcastReceiver(appContext);

            synchronized (rpcBridgeLock) {
                if (rpcBridge != null) {
                    rpcVersion = null;
                    rpcBridge.unload();
                    rpcBridge = null;
                }
                ModelTask.stopAllTask();
            }
        } catch (Throwable th) {
            Log.printStackTrace(TAG, "stopHandler err:", th);
        }
    }

    static void execHandler() {
        if (mainTask != null) mainTask.startTask(false);
    }

    private static void stopHandler() {
        if (mainTask != null) mainTask.stopTask();
        ModelTask.stopAllTask();
    }

    // --- 杂项方法 ---

    private static void checkInactiveTime() {
        if (lastExecTime == 0) return;
        long inactiveTime = System.currentTimeMillis() - lastExecTime;
        if (inactiveTime > MAX_INACTIVE_TIME) {
            Log.record(TAG, "⚠️ 检测到长时间未执行(" + inactiveTime / 60000 + "m)，重新登录");
            reOpenApp();
        }
    }

    public static void updateDay() {
        Calendar now = Calendar.getInstance();
        if (dayCalendar == null || dayCalendar.get(Calendar.DAY_OF_MONTH) != now.get(Calendar.DAY_OF_MONTH)) {
            dayCalendar = (Calendar) now.clone();
            resetToMidnight(dayCalendar);
            Log.record(TAG, "日期更新");
            setWakenAtTimeAlarm();
        }
        try {
            Status.save(now);
        } catch (Exception ignored) {
        }
    }

    private static void resetToMidnight(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }

    public static void sendBroadcast(String action) {
        if (appContext != null) appContext.sendBroadcast(new Intent(action));
    }

    public static void sendBroadcastShell(String API, String message) {
        if (appContext == null) return;
        Intent intent = new Intent("fansirsqi.xposed.sesame.SHELL");
        intent.putExtra(API, message);
        appContext.sendBroadcast(intent, null);
    }

    public static void reLoginByBroadcast() {
        sendBroadcast(BroadcastActions.RE_LOGIN);
    }

    public static void restartByBroadcast() {
        sendBroadcast(BroadcastActions.RESTART);
    }

    public static void reOpenApp() {
        ensureScheduler();
        SmartSchedulerManager.INSTANCE.schedule(20_000L, "重新登录", () -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setClassName(General.PACKAGE_NAME, General.CURRENT_USING_ACTIVITY);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                offline = true;
                if (appContext != null) appContext.startActivity(intent);
            } catch (Exception e) {
                Log.error(TAG, "重启Activity失败: " + e.getMessage());
            }
            return Unit.INSTANCE;
        });
    }

    // --- 定时唤醒 ---

    private static void setWakenAtTimeAlarm() {
        if (appContext == null) return;
        ensureScheduler();

        List<String> wakenAtTimeList = BaseModel.Companion.getWakenAtTimeList().getValue();
        if (wakenAtTimeList != null && wakenAtTimeList.contains("-1")) return;

        // 1. 每日0点
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        resetToMidnight(calendar);
        long delayToMidnight = calendar.getTimeInMillis() - System.currentTimeMillis();

        if (delayToMidnight > 0) {
            SmartSchedulerManager.INSTANCE.schedule(delayToMidnight, "每日0点任务", () -> {
                Log.record(TAG, "⏰ 0点任务触发");
                updateDay();
                execHandler();
                setWakenAtTimeAlarm(); // 递归设置明天
                return Unit.INSTANCE;
            });
        }

        // 2. 自定义时间
        if (wakenAtTimeList != null) {
            Calendar now = Calendar.getInstance();
            for (String timeStr : wakenAtTimeList) {
                try {
                    Calendar target = TimeUtil.getTodayCalendarByTimeStr(timeStr);
                    if (target != null && target.compareTo(now) > 0) {
                        long delay = target.getTimeInMillis() - System.currentTimeMillis();
                        SmartSchedulerManager.INSTANCE.schedule(delay, "自定义: " + timeStr, () -> {
                            Log.record(TAG, "⏰ 自定义触发: " + timeStr);
                            execHandler();
                            return Unit.INSTANCE;
                        });
                    }
                } catch (Exception e) { /* ignore */ }
            }
        }
    }

    // --- 广播接收器 ---

    static class AlipayBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            if (finalProcessName != null && finalProcessName.endsWith(":widgetProvider")) {
                return; // 忽略小组件进程
            }

            switch (action) {
                case BroadcastActions.RESTART:
                    GlobalThreadPools.INSTANCE.execute(ApplicationHook::initHandler);
                    break;
                case BroadcastActions.RE_LOGIN:
                    reOpenApp();
                    break;
                case BroadcastActions.RPC_TEST:
                    handleRpcTest(intent);
                    break;
            }
        }

        private void handleRpcTest(Intent intent) {
            GlobalThreadPools.INSTANCE.execute(() -> {
                Log.record(TAG, "RPC测试: " + intent);
                try {
                    DebugRpc rpc = new DebugRpc();
                    rpc.start(intent.getStringExtra("method"), intent.getStringExtra("data"), intent.getStringExtra("type"));
                } catch (Throwable t) { /* ignore */ }
            });
        }
    }

    static void registerBroadcastReceiver(Context context) {
        if (mBroadcastReceiver != null) return; // 防止重复注册
        try {
            mBroadcastReceiver = new AlipayBroadcastReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(BroadcastActions.RESTART);
            filter.addAction(BroadcastActions.RE_LOGIN);
            filter.addAction(BroadcastActions.STATUS);
            filter.addAction(BroadcastActions.RPC_TEST);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(mBroadcastReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ContextCompat.registerReceiver(context, mBroadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            }
            Log.record(TAG, "BroadcastReceiver registered");
        } catch (Throwable th) {
            mBroadcastReceiver = null;
            Log.printStackTrace(TAG, "Register Receiver failed", th);
        }
    }

    static void unregisterBroadcastReceiver(Context context) {
        if (mBroadcastReceiver == null || context == null) return;
        try {
            context.unregisterReceiver(mBroadcastReceiver);
            Log.record(TAG, "BroadcastReceiver unregistered");
        } catch (Throwable th) {
            // ignore: receiver not registered
        } finally {
            mBroadcastReceiver = null;
        }
    }
}