package fansirsqi.xposed.sesame.hook.rpc.bridge;

import fansirsqi.xposed.sesame.util.CoroutineUtils;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XposedHelpers;
import fansirsqi.xposed.sesame.data.General;
import fansirsqi.xposed.sesame.entity.RpcEntity;
import fansirsqi.xposed.sesame.hook.ApplicationHook;
import fansirsqi.xposed.sesame.hook.rpc.intervallimit.RpcIntervalLimit;
import fansirsqi.xposed.sesame.model.BaseModel;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.Notify;
import fansirsqi.xposed.sesame.util.RandomUtil;
import fansirsqi.xposed.sesame.util.TimeUtil;

/**
 * 新版rpc接口，支持最低支付宝版本v10.3.96.8100 记录rpc抓包，支持最低支付宝版本v10.3.96.8100
 */
public class NewRpcBridge implements RpcBridge {
    private static final String TAG = NewRpcBridge.class.getSimpleName();
    private ClassLoader loader;
    private Object newRpcInstance;
    private Method parseObjectMethod;
    private Class<?>[] bridgeCallbackClazzArray;
    private Method newRpcCallMethod;
    private final AtomicInteger maxErrorCount = new AtomicInteger(0);
    private final Integer setMaxErrorCount = BaseModel.getSetMaxErrorCount().getValue();

    ArrayList<String> errorMark = new ArrayList<>(Arrays.asList(
            "1004", "1009", "2000", "46", "48"
    ));
    ArrayList<String> errorStringMark = new ArrayList<>(Arrays.asList(
            "繁忙", "拒绝", "网络不可用", "重试"
    ));

    // 需要屏蔽错误日志的RPC方法列表
    ArrayList<String> silentErrorMethods = new ArrayList<>(Arrays.asList(
            "com.alipay.adexchange.ad.facade.xlightPlugin"
    ));

    /**
     * 检查指定的RPC方法是否应该显示错误日志
     * 
     * @param methodName RPC方法名称
     * @return 如果应该显示错误日志返回true，否则返回false
     */
    private boolean shouldShowErrorLog(String methodName) {
        return !silentErrorMethods.contains(methodName);
    }

    /**
     * 记录RPC请求返回null的原因
     * 
     * @param rpcEntity RPC请求实体
     * @param reason 返回null的原因
     * @param count 当前重试次数
     */
    private void logNullResponse(RpcEntity rpcEntity, String reason, int count) {
        String methodName = rpcEntity != null ? rpcEntity.getRequestMethod() : "unknown";
        if (shouldShowErrorLog(methodName)) {
            Log.error(TAG, "RPC返回null | 方法: " + methodName + " | 原因: " + reason + " | 重试: " + count);
        }
    }

    /**
     * 尝试重新初始化RPC桥接（仅重新初始化技术组件，不影响配置）
     * 
     * @return 重新初始化是否成功
     */
    private boolean tryReInitialize() {
        try {
            Log.debug(TAG, "检测到RPC方法为null，尝试重新初始化RPC技术组件...");
            // 注意：此方法只重新初始化技术组件，不会影响配置列表
            // errorMark, errorStringMark, silentErrorMethods 等配置保持不变
            load();
            Log.debug(TAG, "RPC技术组件重新初始化成功，配置保持不变");
            return true;
        } catch (Exception e) {
            Log.debug(TAG, "RPC技术组件重新初始化失败:");
            Log.printStackTrace(e);
            return false;
        }
    }

    @Override
    public RpcVersion getVersion() {
        return RpcVersion.NEW;
    }

    @Override
    public void load() throws Exception {
        loader = ApplicationHook.getClassLoader();
        try {
            Object service = XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.alipay.mobile.nebulacore.Nebula", loader), "getService");
            Object extensionManager = XposedHelpers.callMethod(service, "getExtensionManager");
            Method getExtensionByName = extensionManager.getClass().getDeclaredMethod("createExtensionInstance", Class.class);
            getExtensionByName.setAccessible(true);
            newRpcInstance = getExtensionByName.invoke(null, loader.loadClass("com.alibaba.ariver.commonability.network.rpc.RpcBridgeExtension"));
            if (newRpcInstance == null) {
                Object nodeExtensionMap = XposedHelpers.callMethod(extensionManager, "getNodeExtensionMap");
                if (nodeExtensionMap != null) {
                    @SuppressWarnings("unchecked")
                    Map<Object, Map<String, Object>> map = (Map<Object, Map<String, Object>>) nodeExtensionMap;
                    for (Map.Entry<Object, Map<String, Object>> entry : map.entrySet()) {
                        Map<String, Object> map1 = entry.getValue();
                        for (Map.Entry<String, Object> entry1 : map1.entrySet()) {
                            if ("com.alibaba.ariver.commonability.network.rpc.RpcBridgeExtension".equals(entry1.getKey())) {
                                newRpcInstance = entry1.getValue();
                                break;
                            }
                        }
                    }
                }
                if (newRpcInstance == null) {
                    Log.runtime(TAG, "get newRpcInstance null");
                    throw new RuntimeException("get newRpcInstance is null");
                }
            }
            parseObjectMethod = loader.loadClass("com.alibaba.fastjson.JSON").getMethod("parseObject", String.class);
            Class<?> bridgeCallbackClazz = loader.loadClass("com.alibaba.ariver.engine.api.bridge.extension.BridgeCallback");
            bridgeCallbackClazzArray = new Class[]{bridgeCallbackClazz};
            newRpcCallMethod = newRpcInstance.getClass().getMethod("rpc"
                    , String.class
                    , boolean.class
                    , boolean.class
                    , String.class
                    , loader.loadClass(General.JSON_OBJECT_NAME)
                    , String.class
                    , loader.loadClass(General.JSON_OBJECT_NAME)
                    , boolean.class
                    , boolean.class
                    , int.class
                    , boolean.class
                    , String.class
                    , loader.loadClass("com.alibaba.ariver.app.api.App")
                    , loader.loadClass("com.alibaba.ariver.app.api.Page")
                    , loader.loadClass("com.alibaba.ariver.engine.api.bridge.model.ApiContext")
                    , bridgeCallbackClazz
            );
            Log.runtime(TAG, "get newRpcCallMethod successfully");
        } catch (Exception e) {
            Log.runtime(TAG, "get newRpcCallMethod err:");
            throw e;
        }
    }

    @Override
    public void unload() {
        newRpcCallMethod = null;
        bridgeCallbackClazzArray = null;
        parseObjectMethod = null;
        newRpcInstance = null;
        loader = null;
    }

    /**
     * 发送RPC请求并获取响应字符串
     * <p>
     * 该方法是requestObject的包装，将RPC响应对象转换为字符串返回：
     * 1. 调用requestObject执行实际的RPC请求
     * 2. 从返回的RPC实体中提取响应字符串
     * </p>
     *
     * @param rpcEntity RPC请求实体，包含请求方法、参数等信息
     * @param tryCount 最大尝试次数，设置为1表示只尝试一次不重试，设置为0表示不尝试，大于1表示有重试
     * @param retryInterval 重试间隔（毫秒），负值表示使用默认延迟，0表示立即重试
     * @return 响应字符串，如果请求失败则返回null
     */
    public String requestString(RpcEntity rpcEntity, int tryCount, int retryInterval) {
        RpcEntity resRpcEntity = requestObject(rpcEntity, tryCount, retryInterval);
        if (resRpcEntity != null) {
            return resRpcEntity.getResponseString();
        }
        return null;
    }

    /**
     * 发送RPC请求并获取响应对象
     * <p>
     * 该方法负责执行实际的RPC调用，支持重试机制和错误处理：
     * 1. 根据tryCount参数控制重试次数
     * 2. 根据retryInterval参数控制重试间隔
     *    - retryInterval < 0: 使用600ms+随机延迟
     *    - retryInterval = 0: 不等待立即重试
     *    - retryInterval > 0: 使用指定的毫秒数等待
     * 3. 检测网络错误并根据配置进入离线模式或尝试重新登录
     * </p>
     *
     * @param rpcEntity RPC请求实体，包含请求方法、参数等信息
     * @param tryCount 最大尝试次数，设置为1表示只尝试一次不重试，设置为0表示不尝试，大于1表示有重试
     * @param retryInterval 重试间隔（毫秒），负值表示使用默认延迟，0表示立即重试
     * @return 包含响应数据的RPC实体，如果请求失败则返回null
     */
    @Override
    public RpcEntity requestObject(RpcEntity rpcEntity, int tryCount, int retryInterval) {

        if (newRpcCallMethod == null) {
            ApplicationHook.restartByBroadcast();
            return null;
        }
        try {
            int count = 0;
            do {
                count++;
                try {
                    RpcIntervalLimit.INSTANCE.enterIntervalLimit(Objects.requireNonNull(rpcEntity.getRequestMethod()));
                    // 再次检查RPC方法是否为null（防止并发情况下被unload）
                    if (newRpcCallMethod == null) {
                        if (count == 1 && tryReInitialize()) {
                            // 重新初始化成功，继续下一次循环重试
                            Log.runtime(TAG, "RPC方法在执行前变为null，尝试重新初始化成功");
                            continue;
                        }
                        return null;
                    }
                    
                    newRpcCallMethod.invoke(
                            newRpcInstance, rpcEntity.getRequestMethod(), false, false, "json", parseObjectMethod.invoke(null,
                                    rpcEntity.getRpcFullRequestData()), "", null, true, false, 0, false, "", null, null, null, Proxy.newProxyInstance(loader,
                                    bridgeCallbackClazzArray, (proxy, innerMethod, args) -> {
                                        if ("equals".equals(innerMethod.getName())) {
                                            return proxy == args[0];
                                        }
                                        if ("hashCode".equals(innerMethod.getName())) {
                                            return System.identityHashCode(proxy);
                                        }
                                        if ("toString".equals(innerMethod.getName())) {
                                            return "Proxy for " + bridgeCallbackClazzArray[0].getName();
                                        }
                                        if (args != null && args.length == 1 && "sendJSONResponse".equals(innerMethod.getName())) {
                                            try {
                                                Object obj = args[0];
                                                rpcEntity.setResponseObject(obj, (String) XposedHelpers.callMethod(obj, "toJSONString"));
                                                if (!(Boolean) XposedHelpers.callMethod(obj, "containsKey", "success")
                                                        && !(Boolean) XposedHelpers.callMethod(obj, "containsKey", "isSuccess")) {
                                                    rpcEntity.setError();

                                                    // 如果方法不在屏蔽列表中，则显示错误日志
                                                    if (shouldShowErrorLog(rpcEntity.getRequestMethod())) {
                                                    Log.error(TAG, "new rpc response1 | id: " + rpcEntity.hashCode() + " | method: " + rpcEntity.getRequestMethod() + "\n " +
                                                            "args: " + rpcEntity.getRequestData() + " |\n data: " + rpcEntity.getResponseString());
                                                    }
                                                }
                                            } catch (Exception e) {
                                                rpcEntity.setError();
                                                Log.error(TAG, "new rpc response2 | id: " + rpcEntity.hashCode() + " | method: " + rpcEntity.getRequestMethod() +
                                                        " err:");
                                                Log.printStackTrace(e);
                                            }
                                        }
                                        return null;
                                    })
                    );
                    if (!rpcEntity.getHasResult()) {
                        logNullResponse(rpcEntity, "无响应结果", count);
                        return null;
                    }
                    if (!rpcEntity.getHasError()) {
                        return rpcEntity;
                    }
                    try {
                        String errorCode = (String) XposedHelpers.callMethod(rpcEntity.getResponseObject(), "getString", "error");
                        String errorMessage = (String) XposedHelpers.callMethod(rpcEntity.getResponseObject(), "getString", "errorMessage");
                        String response = rpcEntity.getResponseString();
                        String methodName = rpcEntity.getRequestMethod();

                        if (errorMark.contains(errorCode) || errorStringMark.contains(errorMessage)) {
                            int currentErrorCount = maxErrorCount.incrementAndGet();
                            if (!ApplicationHook.isOffline()) {
                                if (currentErrorCount > setMaxErrorCount) {
                                    ApplicationHook.setOffline(true);
                                    Notify.updateStatusText("网络连接异常，已进入离线模式");
                                    if (BaseModel.getErrNotify().getValue()) {
                                        Notify.sendErrorNotification(TimeUtil.getTimeStr() + " | 网络异常次数超过阈值[" + setMaxErrorCount + "]", response);
                                    }
                                }
                                if (BaseModel.getErrNotify().getValue()) {
                                    Notify.sendErrorNotification(TimeUtil.getTimeStr() + " | 网络异常: " + methodName, response);
                                }
                                if (BaseModel.getTimeoutRestart().getValue()) {
                                    Log.record(TAG, "尝试重新登录");
                                    ApplicationHook.reLoginByBroadcast();
                                }
                            }
                            logNullResponse(rpcEntity, "网络错误: " + errorCode + "/" + errorMessage, count);
                            return null;
                        }
                        return rpcEntity;
                    } catch (Exception e) {
                        Log.error(TAG, "new rpc response | id: " + rpcEntity.hashCode() + " | method: " + rpcEntity.getRequestMethod() + " get err:");
                        Log.printStackTrace(e);
                    }
                    if (retryInterval < 0) {
                        CoroutineUtils.sleepCompat(600 + RandomUtil.delay());
                    } else if (retryInterval > 0) {
                        CoroutineUtils.sleepCompat(retryInterval);
                    }
                } catch (Throwable t) {
                    Log.error(TAG, "new rpc request | id: " + rpcEntity.hashCode() + " | method: " + rpcEntity.getRequestMethod() + " err:");
                    Log.printStackTrace(t);
                    if (retryInterval < 0) {
                        CoroutineUtils.sleepCompat(600 + RandomUtil.delay());
                    } else if (retryInterval > 0) {
                        CoroutineUtils.sleepCompat(retryInterval);
                    }
                }
            } while (count < tryCount);
            logNullResponse(rpcEntity, "重试次数耗尽", tryCount);
            return null;
        } finally {
            Log.system(TAG, "New RPC\n方法: " + rpcEntity.getRequestMethod() + "\n参数: " + rpcEntity.getRequestData() + "\n数据: " + rpcEntity.getResponseString() + "\n"+"\n"+"堆栈:"+new Exception().getStackTrace()[1].toString());
            Log.printStack(TAG);

        }
    }
}