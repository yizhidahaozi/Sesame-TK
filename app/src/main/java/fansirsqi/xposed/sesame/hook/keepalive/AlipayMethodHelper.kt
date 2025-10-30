package fansirsqi.xposed.sesame.hook.keepalive

import android.app.Activity
import android.content.Context
import de.robv.android.xposed.XposedHelpers
import fansirsqi.xposed.sesame.hook.ApplicationHook
import fansirsqi.xposed.sesame.util.Log

/**
 * 支付宝方法调用助手
 *
 * 统一封装所有主动调用支付宝内部方法的功能：
 * 1. 进程唤醒 (PushBerserker.wakeUpOnRebirth)
 * 2. 防止息屏 (BundleUtils.keepScreenOn)
 * 3. ClassLoader 获取
 */
object AlipayMethodHelper {
    private const val TAG = "AlipayMethodHelper"

    /**
     * 获取支付宝的 ClassLoader
     */
    @JvmStatic
    fun getAlipayClassLoader(): ClassLoader? {
        return try {
            val appHookClass = ApplicationHook::class.java
            val classLoaderField = appHookClass.getDeclaredField("classLoader")
            classLoaderField.isAccessible = true
            classLoaderField.get(null) as? ClassLoader
        } catch (e: Exception) {
            Log.debug(TAG, "获取支付宝 ClassLoader 失败: ${e.message}")
            null
        }
    }

    /**
     * 调用支付宝的进程唤醒方法
     * 
     * 调用 PushBerserker.wakeUpOnRebirth 保持进程活跃
     */
    @JvmStatic
    fun callWakeup() {
        try {
            val alipayContext = ApplicationHook.getAppContext()
            if (alipayContext == null) {
                Log.debug(TAG, "支付宝 Context 为 null，无法调用唤醒")
                return
            }

            val alipayClassLoader = getAlipayClassLoader()
            if (alipayClassLoader == null) {
                Log.debug(TAG, "支付宝 ClassLoader 为 null，无法调用唤醒")
                return
            }

            // 调用 PushBerserker.wakeUpOnRebirth
            try {
                val pushBerserkerClass = XposedHelpers.findClass(
                    "com.alipay.mobile.rome.voicebroadcast.berserker.PushBerserker",
                    alipayClassLoader
                )
                XposedHelpers.callStaticMethod(
                    pushBerserkerClass,
                    "wakeUpOnRebirth",
                    alipayContext
                )
                Log.debug(TAG, "✅ 已调用 PushBerserker.wakeUpOnRebirth")
            } catch (e: Exception) {
                Log.debug(TAG, "调用 PushBerserker.wakeUpOnRebirth 失败: ${e.message}")
            }

        } catch (e: Exception) {
            Log.debug(TAG, "调用支付宝唤醒失败: ${e.message}")
        }
    }

    /**
     * 调用支付宝的 keepScreenOn 方法
     *
     * 使用 BundleUtils.keepScreenOn 防止屏幕息屏
     *
     * @param keep true: 保持屏幕常亮, false: 取消保持
     */
    @JvmStatic
    fun callKeepScreenOn(keep: Boolean) {
        try {
            val alipayContext = ApplicationHook.getAppContext()
            if (alipayContext == null) {
                Log.debug(TAG, "支付宝 Context 为 null，无法调用 keepScreenOn")
                return
            }

            

            val alipayClassLoader = getAlipayClassLoader()
            if (alipayClassLoader == null) {
                Log.debug(TAG, "支付宝 ClassLoader 为 null，无法调用 keepScreenOn")
                return
            }

            // 调用 BundleUtils.keepScreenOn
            val bundleUtilsClass = XposedHelpers.findClass(
                "com.alipay.android.phone.wallet.mylive.BundleUtils",
                alipayClassLoader
            )

            XposedHelpers.callStaticMethod(
                bundleUtilsClass,
                "keepScreenOn",
                alipayContext,
                keep
            )

            val status = if (keep) "开启" else "关闭"
            Log.record(TAG, "✅ 已调用支付宝 keepScreenOn ($status)")

        } catch (e: Exception) {
            Log.debug(TAG, "调用支付宝 keepScreenOn 失败: ${e.message}")
        }
    }

    /**
     * 调用支付宝的 PushBerserker.setup 方法
     * 
     * 初始化推送服务
     */
    @JvmStatic
    fun callPushBerserkerSetup() {
        try {
            val alipayContext = ApplicationHook.getAppContext()
            if (alipayContext == null) {
                Log.debug(TAG, "支付宝 Context 为 null，无法调用 setup")
                return
            }

            val alipayClassLoader = getAlipayClassLoader()
            if (alipayClassLoader == null) {
                Log.debug(TAG, "支付宝 ClassLoader 为 null，无法调用 setup")
                return
            }

            try {
                val pushBerserkerClass = XposedHelpers.findClass(
                    "com.alipay.mobile.rome.voicebroadcast.berserker.PushBerserker",
                    alipayClassLoader
                )
                XposedHelpers.callStaticMethod(
                    pushBerserkerClass,
                    "setup",
                    alipayContext
                )
                Log.debug(TAG, "✅ 已调用 PushBerserker.setup")
            } catch (e: Exception) {
                Log.debug(TAG, "调用 PushBerserker.setup 失败: ${e.message}")
            }

        } catch (e: Exception) {
            Log.debug(TAG, "调用 PushBerserker.setup 失败: ${e.message}")
        }
    }

    /**
     * 启动支付宝推送服务
     * 
     * 启动 NotificationService 和 NetworkStartMainProcService
     */
    @JvmStatic
    fun startPushServices() {
        try {
            val alipayContext = ApplicationHook.getAppContext()
            if (alipayContext == null) {
                Log.debug(TAG, "支付宝 Context 为 null，无法启动服务")
                return
            }

            val alipayClassLoader = getAlipayClassLoader()
            if (alipayClassLoader == null) {
                Log.debug(TAG, "支付宝 ClassLoader 为 null，无法启动服务")
                return
            }

            // 启动服务列表（不包括语音播报）
            val serviceNames = listOf(
                "com.alipay.android.phone.wallet.push.notification.NotificationService",
                "com.alipay.android.phone.wallet.push.route.NetworkStartMainProcService"
            )

            serviceNames.forEach { serviceName ->
                try {
                    val serviceClass = XposedHelpers.findClass(serviceName, alipayClassLoader)
                    val intent = android.content.Intent(alipayContext, serviceClass)
                    alipayContext.startService(intent)
                    Log.debug(TAG, "✅ 已启动服务: ${serviceName.substringAfterLast('.')}")
                } catch (e: Exception) {
                    Log.debug(TAG, "启动服务 ${serviceName.substringAfterLast('.')} 失败: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.debug(TAG, "启动推送服务失败: ${e.message}")
        }
    }
}

