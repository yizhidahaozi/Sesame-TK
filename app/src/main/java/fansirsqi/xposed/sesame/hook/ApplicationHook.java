package fansirsqi.xposed.sesame.hook;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.NonNull;

import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Method;
import java.lang.reflect.Member;
import java.lang.reflect.InvocationTargetException;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import fansirsqi.xposed.sesame.BuildConfig;
import fansirsqi.xposed.sesame.data.Config;
import fansirsqi.xposed.sesame.data.DataCache;
import fansirsqi.xposed.sesame.data.General;
import fansirsqi.xposed.sesame.data.RunType;
import fansirsqi.xposed.sesame.data.Status;
import fansirsqi.xposed.sesame.data.ViewAppInfo;
import fansirsqi.xposed.sesame.entity.AlipayVersion;
import fansirsqi.xposed.sesame.hook.rpc.bridge.NewRpcBridge;
import fansirsqi.xposed.sesame.hook.rpc.bridge.OldRpcBridge;
import fansirsqi.xposed.sesame.hook.rpc.bridge.RpcBridge;
import fansirsqi.xposed.sesame.hook.rpc.bridge.RpcVersion;
import fansirsqi.xposed.sesame.hook.rpc.debug.DebugRpc;
import fansirsqi.xposed.sesame.hook.rpc.intervallimit.RpcIntervalLimit;
import fansirsqi.xposed.sesame.hook.server.ModuleHttpServer;
import fansirsqi.xposed.sesame.model.BaseModel;
import fansirsqi.xposed.sesame.model.Model;
import fansirsqi.xposed.sesame.newutil.DataStore;
import fansirsqi.xposed.sesame.task.BaseTask;
import fansirsqi.xposed.sesame.task.ModelTask;
import fansirsqi.xposed.sesame.util.AssetUtil;
import fansirsqi.xposed.sesame.util.Detector;
import fansirsqi.xposed.sesame.util.Files;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.Notify;
import fansirsqi.xposed.sesame.util.PermissionUtil;
import fansirsqi.xposed.sesame.util.StringUtil;
import fansirsqi.xposed.sesame.util.TimeUtil;
import fansirsqi.xposed.sesame.util.maps.UserMap;
import fi.iki.elonen.NanoHTTPD;
import kotlin.jvm.JvmStatic;
import lombok.Getter;

public class ApplicationHook implements IXposedHookLoadPackage {
    static final String TAG = ApplicationHook.class.getSimpleName();
    private ModuleHttpServer httpServer;
    private static final String modelVersion = BuildConfig.VERSION_NAME;
    private static final Map < String, PendingIntent > wakenAtTimeAlarmMap = new ConcurrentHashMap < > ();
    @Getter
    private static ClassLoader classLoader = null;
    @Getter
    private static Object microApplicationContextObject = null;

    @SuppressLint("StaticFieldLeak")
    static Context appContext = null;

    @JvmStatic
    public static Context getAppContext() {
        return appContext;
    }

    @SuppressLint("StaticFieldLeak")
    static Context moduleContext = null;

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
    @SuppressLint("StaticFieldLeak")
    static Service service;
    @Getter
    static Handler mainHandler;
    static BaseTask mainTask;
    static RpcBridge rpcBridge;
    @Getter
    private static RpcVersion rpcVersion;
    private static PowerManager.WakeLock wakeLock;
    private static PendingIntent alarm0Pi;

    public static void setOffline(boolean offline) {
        ApplicationHook.offline = offline;
    }

    private static volatile long lastExecTime = 0; // 添加为类成员变量
    private static final long MAX_INACTIVE_TIME = 3600000; // 最大不活动时间：1小时

    private XC_LoadPackage.LoadPackageParam modelLoadPackageParam;

    private static XC_LoadPackage.LoadPackageParam appLloadPackageParam;

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

    static void deoptimizeMethod(Class < ? > c) throws InvocationTargetException, IllegalAccessException {
        for (Method m: c.getDeclaredMethods()) {
            if (deoptimizeMethod != null && m.getName().equals("makeApplicationInner")) {
                deoptimizeMethod.invoke(null, m);
                if (BuildConfig.DEBUG)
                    XposedBridge.log("D/" + TAG + " Deoptimized " + m.getName());
            }
        }
    }

    /**
     * 调度定时执行
     *
     * @param lastExecTime 上次执行时间
     */
    private void scheduleNextExecution(long lastExecTime) {
        try {
            // 检查长时间未执行的情况
            checkInactiveTime();

            int checkInterval = BaseModel.getCheckInterval().getValue();
            List < String > execAtTimeList = BaseModel.getExecAtTimeList().getValue();
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
                    for (String execAtTime: execAtTimeList) {
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

            // 使用新的可靠执行方法
            scheduleNextExecutionWithAlarm(delayMillis, targetTime > 0 ? targetTime : (lastExecTime + delayMillis));
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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (General.MODULE_PACKAGE_NAME.equals(loadPackageParam.packageName)) {
            try {
                Class < ? > applicationClass = loadPackageParam.classLoader.loadClass("android.app.Application");
                XposedHelpers.findAndHookMethod(applicationClass, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        moduleContext = (Context) param.thisObject;
                        // 可以在这里调用其他需要 Context 的 Hook 方法
                        HookUtil.INSTANCE.hookActive(loadPackageParam);
                    }
                });
            } catch (Exception e) {
                Log.printStackTrace(e);
            }
        } else if (General.PACKAGE_NAME.equals(loadPackageParam.packageName) && General.PACKAGE_NAME.equals(loadPackageParam.processName)) {
            try {
                if (hooked) return;
                appLloadPackageParam = loadPackageParam;
                classLoader = appLloadPackageParam.classLoader;
                // 在Hook Application.attach 之前，先 deoptimize LoadedApk.makeApplicationInner
                try {
                    @SuppressLint("PrivateApi") Class < ? > loadedApkClass = classLoader.loadClass("android.app.LoadedApk");
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
                        PackageInfo pInfo = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
                        assert pInfo.versionName != null;
                        alipayVersion = new AlipayVersion(pInfo.versionName);
                        Log.runtime(TAG, "handleLoadPackage alipayVersion: " + alipayVersion.getVersionString());
                        loadNativeLibs(appContext, AssetUtil.INSTANCE.getCheckerDestFile());
                        loadNativeLibs(appContext, AssetUtil.INSTANCE.getDexkitDestFile());
                        HookUtil.INSTANCE.fuckAccounLimit(loadPackageParam);
                        if (BuildConfig.DEBUG) {
                            try {
                                Log.runtime(TAG, "start service for debug rpc");
                                httpServer = new ModuleHttpServer(8080, "ET3vB^#td87sQqKaY*eMUJXP");
                                httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                            } catch (IOException e) {
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
                                        initHandler(true);
                                        Log.record(TAG, "用户已切换");
                                        Toast.show("用户已切换");
                                        return;
                                    }
                                    //                                    UserMap.initUser(targetUid);
                                    HookUtil.INSTANCE.hookUser(appLloadPackageParam);
                                }
                                if (offline) {
                                    offline = false;
                                    execHandler();
                                    ((Activity) param.thisObject).finish();
                                    Log.runtime(TAG, "Activity reLogin");
                                }
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
                                String apkPath = loadPackageParam.appInfo.sourceDir;
                                try (DexKitBridge ignored = DexKitBridge.create(apkPath)) {
                                    // Other use cases
                                    Log.runtime(TAG, "hook dexkit successfully");
                                }
                                service = appService;
                                mainTask = BaseTask.newInstance("MAIN_TASK", () -> {
                                    try {
                                        if (!init) {
                                            Log.record(TAG, "️🐣跳过执行-未初始化");
                                            return;
                                        }
                                        if (!Config.isLoaded()) {
                                            Log.record(TAG, "️⚙跳过执行-用户模块配置未加载");
                                            return;
                                        }
                                        Log.record(TAG, "开始执行");
                                        long currentTime = System.currentTimeMillis();

                                        // 检查是否是闹钟触发的执行
                                        // 通过线程名称或当前调用栈来判断
                                        boolean isAlarmTriggered = Thread.currentThread().getName().contains("AlarmTriggered") ||
                                                Thread.currentThread().getStackTrace().length > 0 &&
                                                        Arrays.toString(Thread.currentThread().getStackTrace()).contains("AlipayBroadcastReceiver");

                                        // 获取最小执行间隔（2秒）
                                        final long MIN_EXEC_INTERVAL = 2000;

                                        // 计算距离上次执行的时间间隔
                                        long timeSinceLastExec = currentTime - lastExecTime;

                                        // 检查执行条件
                                        boolean isIntervalTooShort = timeSinceLastExec < MIN_EXEC_INTERVAL;
                                        boolean shouldSkipExecution = isIntervalTooShort && !isAlarmTriggered;

                                        // 记录执行间隔信息（无论是否跳过）
                                        Log.record(TAG, "执行间隔: " + timeSinceLastExec + "ms，最小间隔: " + MIN_EXEC_INTERVAL +
                                                "ms，闹钟触发: " + (isAlarmTriggered ? "是" : "否"));

                                        // 只有在非闹钟触发且间隔太短的情况下才跳过执行
                                        if (shouldSkipExecution) {
                                            Log.record(TAG, "⚠️ 执行间隔较短，跳过执行，安排下次执行");
                                            execDelayedWithAlarm(BaseModel.getCheckInterval().getValue());
                                            return;
                                        }

                                        // 闹钟触发的执行总是允许的
                                        if (isAlarmTriggered) {
                                            Log.record(TAG, "闹钟触发执行，忽略间隔时间检查");
                                        }
                                        String currentUid = UserMap.getCurrentUid();
                                        String targetUid = HookUtil.INSTANCE.getUserId(appLloadPackageParam.classLoader);
                                        if (targetUid == null || !targetUid.equals(currentUid)) {
                                            Log.record(TAG, "用户切换或为空，重新登录");
                                            reLogin();
                                            return;
                                        }
                                        lastExecTime = currentTime; // 更新最后执行时间
                                        ModelTask.startAllTask(false, ModelTask.TaskExecutionMode.PARALLEL);
                                        scheduleNextExecution(lastExecTime);
                                    } catch (Exception e) {
                                        Log.record(TAG, "❌执行异常");
                                        Log.printStackTrace(TAG, e);
                                    }
                                });
                                registerBroadcastReceiver(appService);
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
                                httpServer.stop();
                                restartByBroadcast();
                            }
                        });
            } catch (Throwable t) {
                Log.runtime(TAG, "hook service onDestroy err");
                Log.printStackTrace(TAG, t);
            }

            HookUtil.INSTANCE.hookOtherService(loadPackageParam);

            hooked = true;
            Log.runtime(TAG, "load success: " + loadPackageParam.packageName);
        }
    }

    /**
     * 设置定时唤醒
     */
    private static void setWakenAtTimeAlarm() {
        try {
            List < String > wakenAtTimeList = BaseModel.getWakenAtTimeList().getValue();
            if (wakenAtTimeList != null && wakenAtTimeList.contains("-1")) {
                Log.record(TAG, "定时唤醒未开启");
                return;
            }
            unsetWakenAtTimeAlarm();
            try {
                Intent intent0 = new Intent("com.eg.android.AlipayGphone.sesame.execute");
                intent0.putExtra("alarm_triggered", true);  // 标记为闹钟触发的执行
                intent0.putExtra("waken_at_time", true);    // 标记为定时唤醒
                PendingIntent pendingIntent = PendingIntent.getBroadcast(appContext, 0, intent0, getPendingIntentFlag());
                Calendar calendar = Calendar.getInstance();
                calendar.add(Calendar.DAY_OF_MONTH, 1);
                calendar.set(Calendar.HOUR_OF_DAY, 0);
                calendar.set(Calendar.MINUTE, 0);
                calendar.set(Calendar.SECOND, 0);
                calendar.set(Calendar.MILLISECOND, 0);
                if (setAlarmTask(calendar.getTimeInMillis(), pendingIntent)) {
                    alarm0Pi = pendingIntent;
                    Log.record(TAG, "⏰ 设置定时唤醒:0|000000");
                }
            } catch (Exception e) {
                Log.runtime(TAG, "setWakenAt0 err:");
                Log.printStackTrace(TAG, e);
            }
            if (wakenAtTimeList != null && !wakenAtTimeList.isEmpty()) {
                Calendar nowCalendar = Calendar.getInstance();
                for (int i = 1, len = wakenAtTimeList.size(); i < len; i++) {
                    try {
                        String wakenAtTime = wakenAtTimeList.get(i);
                        Calendar wakenAtTimeCalendar = TimeUtil.getTodayCalendarByTimeStr(wakenAtTime);
                        if (wakenAtTimeCalendar != null) {
                            if (wakenAtTimeCalendar.compareTo(nowCalendar) > 0) {
                                Intent wakenIntent = new Intent("com.eg.android.AlipayGphone" + ".sesame.execute");
                                wakenIntent.putExtra("alarm_triggered", true);  // 标记为闹钟触发的执行
                                wakenIntent.putExtra("waken_at_time", true);    // 标记为定时唤醒
                                wakenIntent.putExtra("waken_time", wakenAtTime); // 记录唤醒时间
                                PendingIntent wakenAtTimePendingIntent = PendingIntent.getBroadcast(appContext, i, wakenIntent, getPendingIntentFlag());
                                if (setAlarmTask(wakenAtTimeCalendar.getTimeInMillis(), wakenAtTimePendingIntent)) {
                                    String wakenAtTimeKey = i + "|" + wakenAtTime;
                                    wakenAtTimeAlarmMap.put(wakenAtTimeKey, wakenAtTimePendingIntent);
                                    Log.record(TAG, "⏰ 设置定时唤醒:" + wakenAtTimeKey);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.runtime(TAG, "setWakenAtTime err:");
                        Log.printStackTrace(TAG, e);
                    }
                }
            }
        } catch (Exception e) {
            Log.runtime(TAG, "setWakenAtTimeAlarm err:");
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 取消定时唤醒
     */
    private static void unsetWakenAtTimeAlarm() {
        try {
            for (Map.Entry < String, PendingIntent > entry: wakenAtTimeAlarmMap.entrySet()) {
                try {
                    String wakenAtTimeKey = entry.getKey();
                    PendingIntent wakenAtTimePendingIntent = entry.getValue();
                    if (unsetAlarmTask(wakenAtTimePendingIntent)) {
                        wakenAtTimeAlarmMap.remove(wakenAtTimeKey);
                        Log.record(TAG, "⏰ 取消定时唤醒:" + wakenAtTimeKey);
                    }
                } catch (Exception e) {
                    Log.runtime(TAG, "unsetWakenAtTime err:");
                    Log.printStackTrace(TAG, e);
                }
            }
            try {
                if (unsetAlarmTask(alarm0Pi)) {
                    alarm0Pi = null;
                    Log.record(TAG, "⏰ 取消定时唤醒:0|000000");
                }
            } catch (Exception e) {
                Log.runtime(TAG, "unsetWakenAt0 err:");
                Log.printStackTrace(TAG, e);
            }
        } catch (Exception e) {
            Log.runtime(TAG, "unsetWakenAtTimeAlarm err:");
            Log.printStackTrace(TAG, e);
        }
    }

    @SuppressLint("WakelockTimeout")
    /*
     * 保存任务执行状态
     */
    private static void saveExecutionState(long lastExecTime, long nextExecTime) {
        try {
            JSONObject state = new JSONObject();
            state.put("lastExecTime", lastExecTime);
            state.put("nextExecTime", nextExecTime);
            state.put("timestamp", System.currentTimeMillis());

            // 保存到DataStore
            String stateJson = state.toString();
            DataStore.INSTANCE.put("execution_state", stateJson);
            Log.debug(TAG, "已保存执行状态: " + stateJson);
        } catch (Exception e) {
            Log.error(TAG, "保存执行状态失败: " + e.getMessage());
        }
    }

    private static synchronized Boolean initHandler(Boolean force) {
        try {
            // 检查是否长时间未执行，特别是跨越0点的情况
            if (!force && lastExecTime > 0) {
                long currentTime = System.currentTimeMillis();
                long inactiveTime = currentTime - lastExecTime;
                boolean crossedMidnight = isCrossedMidnight(currentTime);
                if (inactiveTime > MAX_INACTIVE_TIME || crossedMidnight) {
                    Log.record(TAG, "⚠️ 初始化时检测到长时间未执行(" + (inactiveTime / 60000) + "分钟)，可能跨越0点，将强制重新初始化");
                    force = true; // 强制重新初始化
                }
            }

            destroyHandler(force); // 销毁之前的处理程序
            Model.initAllModel(); //在所有服务启动前装模块配置
            if (service == null) {
                return false;
            }
            if (force) {
                String userId = HookUtil.INSTANCE.getUserId(appLloadPackageParam.classLoader);
                if (userId == null) {
                    Log.record(TAG, "initHandler:用户未登录");
                    Toast.show("initHandler:用户未登录");
                    return false;
                }
                HookUtil.INSTANCE.hookUser(appLloadPackageParam);
                String startMsg = "芝麻粒-TK 开始初始化...";
                Log.record(TAG, startMsg);
                Log.record(TAG, "⚙️模块版本：" + modelVersion);
                Log.record(TAG, "📦应用版本：" + alipayVersion.getVersionString());
                Config.load(userId); //加载配置
                if (!Config.isLoaded()) {
                    Log.record(TAG, "用户模块配置加载失败");
                    Toast.show("用户模块配置加载失败");
                    return false;
                }
                //闹钟权限申请
                if (!PermissionUtil.checkAlarmPermissions()) {
                    Log.record(TAG, "❌ 支付宝无闹钟权限");
                    mainHandler.postDelayed(
                            () -> {
                                if (!PermissionUtil.checkOrRequestAlarmPermissions(appContext)) {
                                    Toast.show("请授予支付宝使用闹钟权限");
                                }
                            },
                            2000);
                    return false;
                }
                // 检查并请求后台运行权限
                if (BaseModel.getBatteryPerm().getValue() && !init && !PermissionUtil.checkBatteryPermissions()) {
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
                // 获取 BaseModel 实例
                BaseModel baseModel = Model.getModel(BaseModel.class);
                if (baseModel == null) {
                    Log.error(TAG, "BaseModel 未找到 初始化失败");
                    Notify.setStatusTextDisabled();
                    return false;
                }
                // 检查 enableField 的值
                if ((0 == baseModel.getEnableField().getValue())) {
                    Log.record(TAG, "❌ 芝麻粒已禁用");
                    Toast.show("❌ 芝麻粒已禁用");
                    Notify.setStatusTextDisabled();
                    return false;
                }
                // 保持唤醒锁，防止设备休眠
                if (BaseModel.getStayAwake().getValue()) {
                    try {
                        PowerManager pm = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
                        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, service.getClass().getName());
                        wakeLock.acquire(10*60*1000L /*10 minutes*/); // 确保唤醒锁在前台服务启动前
                    } catch (Throwable t) {
                        Log.record(TAG, "唤醒锁申请失败:");
                        Log.printStackTrace(t);
                    }
                }

                setWakenAtTimeAlarm();

                if (BaseModel.getNewRpc().getValue()) {
                    rpcBridge = new NewRpcBridge();
                } else {
                    rpcBridge = new OldRpcBridge();
                }
                rpcBridge.load();
                rpcVersion = rpcBridge.getVersion();
                if (BaseModel.getNewRpc().getValue() && BaseModel.getDebugMode().getValue()) {
                    HookUtil.INSTANCE.hookRpcBridgeExtension(appLloadPackageParam, BaseModel.getSendHookData().getValue(), BaseModel.getSendHookDataUrl().getValue());
                    HookUtil.INSTANCE.hookDefaultBridgeCallback(appLloadPackageParam);
                }
                Model.bootAllModel(classLoader);
                Status.load(userId);
                DataCache.INSTANCE.load();
                DataStore.INSTANCE.init(Files.CONFIG_DIR);
                updateDay(userId);
                String successMsg = "芝麻粒-TK 加载成功✨";
                Log.record(successMsg);
                Toast.show(successMsg);
            }
            offline = false;
            execHandler();
            return true;
        } catch (Throwable th) {
            Log.printStackTrace(TAG, "startHandler", th);
            Toast.show("芝麻粒加载失败 🎃");
            return false;
        }
    }

    private static boolean isCrossedMidnight(long currentTime) {
        Calendar lastExecCalendar = Calendar.getInstance();
        lastExecCalendar.setTimeInMillis(lastExecTime);
        Calendar currentCalendar = Calendar.getInstance();
        currentCalendar.setTimeInMillis(currentTime);
        return lastExecCalendar.get(Calendar.DAY_OF_YEAR) != currentCalendar.get(Calendar.DAY_OF_YEAR) ||
                lastExecCalendar.get(Calendar.YEAR) != currentCalendar.get(Calendar.YEAR);
    }

    /**
     * 销毁处理程序
     *
     * @param force 是否强制销毁
     */
    static synchronized void destroyHandler(Boolean force) {
        try {
            if (force) {
                // 取消所有已设置的闹钟
                cancelAllScheduledAlarms();
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
                if (rpcBridge != null) {
                    rpcVersion = null;
                    rpcBridge.unload();
                    rpcBridge = null;
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
        // 这里不需要强制初始化，因为调用此方法的地方已经完成了初始化
        // 例如在initHandler方法的末尾调用
        mainTask.startTask(false);
    }

    /**
     * 安排主任务在指定的延迟时间后执行，并更新通知中的下次执行时间。
     * 使用AlarmManager设置闹钟，确保即使在设备休眠时也能执行任务。
     *
     * @param delayMillis 延迟执行的毫秒数
     */
    static void execDelayedWithAlarm(long delayMillis) {
        try {
            long exactTimeMillis = System.currentTimeMillis() + delayMillis;
            // 生成唯一请求码
            int requestCode = (int)((exactTimeMillis + 1) % 10000); // +1避免与其他闹钟ID冲突
            // 创建用于执行任务的PendingIntent
            Intent intent = new Intent("com.eg.android.AlipayGphone.sesame.execute");
            // 添加唯一标识，避免PendingIntent复用
            intent.putExtra("execution_time", exactTimeMillis);
            intent.putExtra("request_code", requestCode);
            intent.putExtra("scheduled_at", System.currentTimeMillis());
            intent.putExtra("alarm_triggered", true);  // 标记为闹钟触发的执行
            intent.putExtra("delayed_execution", true); // 标记为延迟执行
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    requestCode,
                    intent,
                    getPendingIntentFlag()
            );
            // 获取AlarmManager服务
            AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
            // 设置精确闹钟，确保在Doze模式下也能触发
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                // 如果没有权限，使用普通闹钟作为退化方案
                Log.record(TAG, "⚠️ 使用非精确闹钟作为退化方案，可能会延迟触发");
                alarmManager.set(AlarmManager.RTC_WAKEUP, exactTimeMillis, pendingIntent);
            } else {
                // 有权限或者低版本Android，使用精确闹钟
                assert alarmManager != null;
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, exactTimeMillis, pendingIntent);
            }
            // 保存闹钟引用
            scheduledAlarms.put(requestCode, pendingIntent);
            // 更新通知显示下次执行时间
            String nt = "⏰ 下次执行(Alarm) " + TimeUtil.getTimeStr(exactTimeMillis);
            Notify.updateNextExecText(exactTimeMillis);
            Toast.show(nt);

            Log.record(TAG, "已设置延迟执行闹钟，ID=" + requestCode + "，时间：" + TimeUtil.getCommonDate(exactTimeMillis));
            Log.record(TAG, nt);
        } catch (Exception e) {
            Log.error(TAG, "设置延迟执行闹钟失败：" + e.getMessage());
            Log.printStackTrace(e);

            // 闹钟设置失败时，退回到Handler方式作为最后备份
            if (mainHandler != null) {
                mainHandler.postDelayed(() -> {
                    Log.record(TAG, "闹钟设置失败，使用Handler备份执行");
                    if (initHandler(true)) {  // 强制初始化
                        mainTask.startTask(true);
                    }
                }, delayMillis);
            }
        }
    }

    /**
     * 使用AlarmManager设置下次执行的闹钟，确保即使在设备休眠时也能执行任务
     *
     * @param delayMillis 延迟执行的毫秒数
     * @param exactTimeMillis 精确的执行时间点（毫秒时间戳）
     */
    static void scheduleNextExecutionWithAlarm(long delayMillis, long exactTimeMillis) {
        // 检查是否有设置精确闹钟的权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                Log.record(TAG, "没有设置精确闹钟的权限，尝试请求权限");
                // 请求权限
                try {
                    // 在Android 12及以上版本，需要引导用户到设置页面授予权限
                    Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    intent.setData(android.net.Uri.parse("package:" + General.PACKAGE_NAME));
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appContext.startActivity(intent);
                    Toast.show("请授予支付宝设置精确闹钟的权限，这对于定时任务执行非常重要");

                    // 记录权限请求事件
                    Log.record(TAG, "已发送精确闹钟权限请求，等待用户授权");

                    // 添加通知提醒
                    Notify.updateStatusText("请授予精确闹钟权限以确保定时任务正常执行");
                } catch (Exception e) {
                    Log.error(TAG, "请求精确闹钟权限失败: " + e.getMessage());
                    Log.printStackTrace(TAG, e);
                }
                // 退回到Handler方式
                execDelayedWithAlarm(delayMillis);
                return;
            }
        }

        // 生成唯一请求码，结合时间戳和随机数，避免冲突
        int requestCode = (int)((exactTimeMillis % 10000) * 10 + (int)(Math.random() * 10));

        try {
            // 先取消之前的同ID闹钟（如果有）
            PendingIntent oldPendingIntent = scheduledAlarms.get(requestCode);
            if (oldPendingIntent != null) {
                AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
                alarmManager.cancel(oldPendingIntent);
                scheduledAlarms.remove(requestCode);
                Log.debug(TAG, "已取消旧闹钟: ID=" + requestCode);
            }

            // 创建用于执行任务的PendingIntent
            Intent intent = new Intent("com.eg.android.AlipayGphone.sesame.execute");
            // 添加唯一标识，避免PendingIntent复用
            intent.putExtra("execution_time", exactTimeMillis);
            intent.putExtra("request_code", requestCode);
            intent.putExtra("scheduled_at", System.currentTimeMillis());
            intent.putExtra("alarm_triggered", true);  // 标记为闹钟触发的执行
            intent.putExtra("unique_id", System.currentTimeMillis() + "_" + requestCode); // 添加绝对唯一ID

            // 设置组件以确保Intent的明确性
            intent.setPackage(General.PACKAGE_NAME);
            // 添加类别以增加Intent的特异性
            intent.addCategory("fansirsqi.xposed.sesame.ALARM_CATEGORY");

            // 使用FLAG_CANCEL_CURRENT确保旧的PendingIntent被取消
            int flags = getPendingIntentFlag() | PendingIntent.FLAG_CANCEL_CURRENT;

            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    requestCode,
                    intent,
                    flags
            );

            // 获取AlarmManager服务
            AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);

            // 确保设备在闹钟触发时能够唤醒
            // 获取电源锁，确保在闹钟触发前不会休眠
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = null;
            try {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "Sesame:AlarmSetupWakeLock");
                wakeLock.acquire(5000); // 获取5秒钟的唤醒锁，足够设置闹钟
            } catch (Exception e) {
                Log.error(TAG, "获取唤醒锁失败: " + e.getMessage());
            }

            // 设置精确闹钟，确保在Doze模式下也能触发
            // 在Android 12+上，已经在前面检查了权限，这里可以安全调用
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // 如果没有权限，使用普通闹钟作为退化方案
                Log.record(TAG, "⚠️ 使用非精确闹钟作为退化方案，可能会延迟触发");
                // 尝试使用带有唤醒功能的闹钟
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, exactTimeMillis, pendingIntent);
                Log.record(TAG, "已设置setAndAllowWhileIdle闹钟");
            } else {
                // 有权限或者低版本Android，使用精确闹钟
                // 使用最强力的闹钟设置方法
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, exactTimeMillis, pendingIntent);
                Log.record(TAG, "已设置setExactAndAllowWhileIdle闹钟");
            }

            // 释放唤醒锁
            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                } catch (Exception e) {
                    Log.error(TAG, "释放唤醒锁失败: " + e.getMessage());
                }
            }

            // 保存闹钟引用
            scheduledAlarms.put(requestCode, pendingIntent);
            // 更新通知显示下次执行时间
            String nt = "⏰ 下次执行(Alarm) " + TimeUtil.getTimeStr(exactTimeMillis);
            Notify.updateNextExecText(exactTimeMillis);
            Toast.show(nt);
            Log.record(TAG, "已设置闹钟唤醒执行，ID=" + requestCode +
                    "，时间：" + TimeUtil.getCommonDate(exactTimeMillis) +
                    "，延迟：" + delayMillis / 1000 + "秒" +
                    "，权限：" + (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms() ? "已授予" : "未授予"));

            // 保存执行状态，以便在重启后恢复
            saveExecutionState(System.currentTimeMillis(), exactTimeMillis);

            // 同时设置多重备份机制，确保任务一定会执行

            // 1. 使用Handler作为第一级备份，延迟稍长一些，避免重复执行
            if (mainHandler != null) {
                mainHandler.postDelayed(() -> {
                    // 检查是否已经由闹钟触发执行
                    long currentTime = System.currentTimeMillis();
                    if (currentTime > exactTimeMillis + 10000) { // 如果已经超过预定时间10秒
                        Log.record(TAG, "闹钟可能未触发，使用Handler备份执行 (第一级备份)");
                        // 确保在备份执行前也进行初始化
                        if (initHandler(true)) {  // 强制初始化
                            mainTask.startTask(true);
                        }
                    }
                }, delayMillis + 10000); // 比预定时间晚10秒

                // 2. 使用第二级备份，再延迟30秒，以防第一级备份也失败
                mainHandler.postDelayed(() -> {
                    // 检查是否已经由闹钟或第一级备份触发执行
                    long currentTime = System.currentTimeMillis();
                    if (currentTime > exactTimeMillis + 40000) { // 如果已经超过预定时间40秒
                        Log.record(TAG, "闹钟和第一级备份可能都未触发，使用Handler备份执行 (第二级备份)");
                        // 确保在备份执行前也进行初始化
                        if (initHandler(true)) {  // 强制初始化
                            mainTask.startTask(true);
                        }
                    }
                }, delayMillis + 40000); // 比预定时间晚40秒
            }

            // 3. 设置额外的闹钟备份，使用不同的请求码，以防主闹钟失败
            try {
                int backupRequestCode = requestCode + 10000; // 使用不同的请求码
                Intent backupIntent = new Intent("com.eg.android.AlipayGphone.sesame.execute");
                backupIntent.putExtra("execution_time", exactTimeMillis + 20000); // 比主闹钟晚20秒
                backupIntent.putExtra("request_code", backupRequestCode);
                backupIntent.putExtra("scheduled_at", System.currentTimeMillis());
                backupIntent.putExtra("alarm_triggered", true);
                backupIntent.putExtra("is_backup_alarm", true); // 标记为备份闹钟
                backupIntent.setPackage(General.PACKAGE_NAME);

                PendingIntent backupPendingIntent = PendingIntent.getBroadcast(
                        appContext,
                        backupRequestCode,
                        backupIntent,
                        getPendingIntentFlag()
                );

                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                        exactTimeMillis + 20000, backupPendingIntent);

                scheduledAlarms.put(backupRequestCode, backupPendingIntent);
                Log.debug(TAG, "已设置备份闹钟: ID=" + backupRequestCode);
            } catch (Exception e) {
                Log.error(TAG, "设置备份闹钟失败: " + e.getMessage());
            }

        } catch (Exception e) {
            Log.error(TAG, "设置执行闹钟失败：" + e.getMessage());
            Log.printStackTrace(e);

            // 闹钟设置失败时，退回到Handler方式作为备份
            execDelayedWithAlarm(delayMillis);
        }
    }

    /**
     * 检查长时间未执行的情况，如果超过阈值则自动重启
     * 特别针对0点后可能出现的执行中断情况
     */
    private void checkInactiveTime() {
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

    @SuppressLint({
            "ObsoleteSdkInt"
    })
    private static Boolean setAlarmTask(long triggerAtMillis, PendingIntent operation) {
        try {
            AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);

            // 检查Android 12+上的精确闹钟权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager != null) {
                if (!alarmManager.canScheduleExactAlarms()) {
                    // 没有权限，记录日志但不尝试请求权限（避免重复请求）
                    Log.record(TAG, "⚠️ 缺少精确闹钟权限，闹钟可能不会准时触发");
                    // 使用非精确闹钟作为退化方案
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
                    return true;
                }
            }
            // 有权限或低版本Android，使用精确闹钟
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                assert alarmManager != null;
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
            }
            return true;
        } catch (Throwable th) {
            Log.runtime(TAG, "setAlarmTask err:");
            Log.printStackTrace(TAG, th);
        }
        return false;
    }

    private static Boolean unsetAlarmTask(PendingIntent operation) {
        try {
            if (operation != null) {
                AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
                alarmManager.cancel(operation);
            }
            return true;
        } catch (Throwable th) {
            Log.runtime(TAG, "unsetAlarmTask err:");
            Log.printStackTrace(TAG, th);
        }
        return false;
    }

    public static void reLoginByBroadcast() {
        try {
            appContext.sendBroadcast(new Intent("com.eg.android.AlipayGphone.sesame.reLogin"));
        } catch (Throwable th) {
            Log.runtime(TAG, "sesame sendBroadcast reLogin err:");
            Log.printStackTrace(TAG, th);
        }
    }

    public static void restartByBroadcast() {
        try {
            appContext.sendBroadcast(new Intent("com.eg.android.AlipayGphone.sesame.restart"));
        } catch (Throwable th) {
            Log.runtime(TAG, "sesame sendBroadcast restart err:");
            Log.printStackTrace(TAG, th);
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    private static int getPendingIntentFlag() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        } else {
            return PendingIntent.FLAG_UPDATE_CURRENT;
        }
    }

    public static Object getMicroApplicationContext() {
        if (microApplicationContextObject == null) {
            try {
                Class < ? > alipayApplicationClass = XposedHelpers.findClass(
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
                    if (reLoginCount.get() < 5) {
                        execDelayedWithAlarm(reLoginCount.getAndIncrement() * 5000L);
                    } else {
                        execDelayedWithAlarm(Math.max(BaseModel.getCheckInterval().getValue(), 180_000));
                    }
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setClassName(General.PACKAGE_NAME, General.CURRENT_USING_ACTIVITY);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    offline = true;
                    appContext.startActivity(intent);
                });
    }

    // 存储当前设置的所有闹钟，便于管理和取消
    private static final Map < Integer, PendingIntent > scheduledAlarms = new ConcurrentHashMap < > ();

    /**
     * 取消所有已设置的闹钟
     */
    private static void cancelAllScheduledAlarms() {
        AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        for (Map.Entry < Integer, PendingIntent > entry: scheduledAlarms.entrySet()) {
            try {
                alarmManager.cancel(entry.getValue());
                Log.record(TAG, "已取消闹钟: ID=" + entry.getKey());
            } catch (Exception e) {
                Log.error(TAG, "取消闹钟失败: " + e.getMessage());
            }
        }
        scheduledAlarms.clear();
    }

    static class AlipayBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.runtime(TAG, "Alipay got Broadcast " + action + " intent:" + intent);
            if (action != null) {
                switch (action) {
                    case "com.eg.android.AlipayGphone.sesame.restart":
                        String userId = intent.getStringExtra("userId");
                        if (StringUtil.isEmpty(userId) || Objects.equals(UserMap.getCurrentUid(), userId)) {
                            initHandler(true);
                        }
                        break;
                    case "com.eg.android.AlipayGphone.sesame.execute":
                        // 获取临时唤醒锁，确保任务执行不会被中断
                        PowerManager.WakeLock tempWakeLock = null;
                        try {
                            // 获取闹钟相关信息
                            int requestCode = intent.getIntExtra("request_code", -1);
                            long executionTime = intent.getLongExtra("execution_time", 0);
                            long currentTime = System.currentTimeMillis();
                            long delayMillis = currentTime - executionTime;
                            boolean isAlarmTriggered = intent.getBooleanExtra("alarm_triggered", false);
                            boolean isWakenAtTime = intent.getBooleanExtra("waken_at_time", false);
                            boolean isDelayedExecution = intent.getBooleanExtra("delayed_execution", false);
                            boolean isBackupAlarm = intent.getBooleanExtra("is_backup_alarm", false);
                            String wakenTime = intent.getStringExtra("waken_time");
                            String uniqueId = intent.getStringExtra("unique_id");

                            String logInfo = "收到执行广播，闹钟ID=" + requestCode +
                                    "，预定时间=" + TimeUtil.getCommonDate(executionTime) +
                                    "，当前时间=" + TimeUtil.getCommonDate(currentTime) +
                                    "，延迟=" + delayMillis + "ms" +
                                    "，闹钟触发=" + (isAlarmTriggered ? "是" : "否");

                            if (isWakenAtTime) {
                                logInfo += "，定时唤醒=" + (wakenTime != null ? wakenTime : "0点");
                            }

                            if (isDelayedExecution) {
                                logInfo += "，延迟执行=是";
                            }

                            if (isBackupAlarm) {
                                logInfo += "，备份闹钟=是";
                            }

                            if (uniqueId != null) {
                                logInfo += "，唯一ID=" + uniqueId;
                            }

                            // 记录闹钟触发信息到日志文件


                            Log.record(TAG, logInfo);

                            // 从管理集合中移除已触发的闹钟
                            if (requestCode >= 0) {
                                scheduledAlarms.remove(requestCode);
                            }

                            // 获取临时唤醒锁
                            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
                            tempWakeLock = pm.newWakeLock(
                                    PowerManager.PARTIAL_WAKE_LOCK,
                                    ApplicationHook.class.getName() + ":executeTask"
                            );
                            tempWakeLock.acquire(5 * 60 * 1000L); // 最多持有5分钟

                            // 强制设置上次执行时间为更早一点的时间
                            // 确保闹钟触发的执行不会被间隔检查阻止
                            lastExecTime = currentTime - 10000; // 设置为10秒前

                            // 根据是否为闹钟触发决定是否进行强制初始化
                            if (isAlarmTriggered) {
                                Log.record(TAG, "闹钟唤醒，执行强制初始化");
                            } else {
                                Log.record(TAG, "非闹钟唤醒，检查是否需要初始化");
                            }
                            if (initHandler(isAlarmTriggered)) {  // 根据闹钟触发状态决定是否强制初始化
                                // 记录执行开始时间
                                long startTime = System.currentTimeMillis();
                                // 设置线程名称以标识闹钟触发的执行
                                Thread.currentThread().setName("AlarmTriggered_" + System.currentTimeMillis());
                                // 直接执行任务
                                mainTask.startTask(true);

                                // 记录执行耗时
                                long executionTime2 = System.currentTimeMillis() - startTime;
                                Log.record(TAG, "任务执行完成，耗时: " + executionTime2 + "ms");
                            }
                        } catch (Exception e) {
                            Log.error(TAG, "处理执行广播时发生错误: " + e.getMessage());
                            Log.printStackTrace(e);
                        } finally {
                            // 释放唤醒锁
                            if (tempWakeLock != null && tempWakeLock.isHeld()) {
                                try {
                                    tempWakeLock.release();
                                } catch (Exception e) {
                                    Log.error(TAG, "释放唤醒锁失败: " + e.getMessage());
                                }
                            }
                        }
                        break;
                    case "com.eg.android.AlipayGphone.sesame.reLogin":
                        reLogin();
                        break;
                    case "com.eg.android.AlipayGphone.sesame.status":
                        try {
                            if (ViewAppInfo.getRunType() == RunType.DISABLE) {
                                Intent replyIntent = new Intent("fansirsqi.xposed.sesame.status");
                                replyIntent.putExtra("EXTRA_RUN_TYPE", RunType.ACTIVE.getNickName());
                                replyIntent.setPackage(General.MODULE_PACKAGE_NAME);
                                context.sendBroadcast(replyIntent);
                                Log.system(TAG, "Replied with status: " + RunType.ACTIVE.getNickName());
                            }
                        } catch (Throwable th) {
                            Log.runtime(TAG, "sesame sendBroadcast status err:");
                            Log.printStackTrace(TAG, th);
                        }
                        break;
                    case "com.eg.android.AlipayGphone.sesame.rpctest":
                        try {
                            String method = intent.getStringExtra("method");
                            String data = intent.getStringExtra("data");
                            String type = intent.getStringExtra("type");
                            DebugRpc rpcInstance = new DebugRpc(); // 创建实例
                            rpcInstance.start(method, data, type); // 通过实例调用非静态方法
                        } catch (Throwable th) {
                            Log.runtime(TAG, "sesame 测试RPC请求失败:");
                            Log.printStackTrace(TAG, th);
                        }
                        break;
                    default:
                        throw new IllegalStateException("Unexpected value: " + action);
                }
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