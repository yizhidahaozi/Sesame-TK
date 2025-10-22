package fansirsqi.xposed.sesame.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import fansirsqi.xposed.sesame.util.Log

/**
 * 支付宝滑块Hook工具类
 * 
 * 功能说明：
 * Hook GlobalCache.e() - 在配置初始化时修改默认值，从源头关闭滑块
 * 
 * 优点：
 * - 从源头控制，最稳定可靠
 * - 兼容性好，不受支付宝版本影响
 * - 后续所有读取都是修改后的值
 * 
 * 使用方式：
 * CaptchaHook.hookCaptcha(classLoader)
 * 
 * @author ghostxx
 * @since 2025-10-22
 */
object CaptchaHook {
    private const val TAG = "CaptchaHook"

    /**
     * 滑块配置键名
     */
    private const val CAPTCHA_SWITCH_KEY = "rds_captcha_switch"
    
    /**
     * 配置分组名
     */
    private const val CAPTCHA_GROUP_KEY = "switch"

    /**
     * 关闭状态的配置值（enable=0表示完全禁用）
     */
    private const val CAPTCHA_DISABLED_CONFIG = """{"enable":0,"intercept":0,"blackList":[],"intervalTime":0}"""

    /**
     * GlobalCache类名
     */
    private const val CLASS_GLOBAL_CACHE = "com.alipay.apmobilesecuritysdk.tool.store.cache.GlobalCache"

    /**
     * 主入口：启动滑块Hook
     * com.alipay.apmobilesecuritysdk.tool.config.GlobalConfig
     * @param classLoader 目标应用的ClassLoader
     */
    fun hookCaptcha(classLoader: ClassLoader) {
        Log.runtime(TAG, "开始Hook支付宝滑块...")
        // Hook配置写入（在初始化时就修改默认值）
        hookGlobalCacheWrite(classLoader)
        Log.runtime(TAG, "滑块Hook设置完成 ✅")
    }

    /**
     * Hook方法1: 拦截GlobalCache配置写入
     * 
     * 原理：在应用启动初始化配置时，修改写入的默认值
     * 优点：从源头控制，后续所有读取都是修改后的值
     * 
     * 目标方法: GlobalCache.e(String, String, String) -> boolean
     * 参数说明:
     *   - 参数1: 配置分组 (如 "switch")
     *   - 参数2: 配置键名 (如 "rds_captcha_switch")
     *   - 参数3: 配置值 (JSON字符串)
     *   - 返回值: true=写入成功, false=写入失败
     */
    private fun hookGlobalCacheWrite(classLoader: ClassLoader) {
        try {
            val globalCacheClass = XposedHelpers.findClass(CLASS_GLOBAL_CACHE, classLoader)
            
            XposedHelpers.findAndHookMethod(
                globalCacheClass,
                "e",
                String::class.java,
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val group = param.args[0] as? String
                        val key = param.args[1] as? String
                        val value = param.args[2] as? String
                        
                        // 只拦截滑块配置的写入
                        if (group == CAPTCHA_GROUP_KEY && key == CAPTCHA_SWITCH_KEY) {
                            Log.runtime(TAG, "拦截滑块配置写入")
                            Log.runtime(TAG, "  配置分组: $group")
                            Log.runtime(TAG, "  配置键名: $key")
                            Log.runtime(TAG, "  原始配置: $value")
                            Log.runtime(TAG, "  修改配置: $CAPTCHA_DISABLED_CONFIG")
                            
                            // 修改参数3，将配置值改为关闭状态
                            param.args[2] = CAPTCHA_DISABLED_CONFIG
                        }
                    }
                }
            )
            
            Log.runtime(TAG, "✅ Hook GlobalCache.e() 成功 - 已拦截配置写入")
        } catch (e: Throwable) {
            Log.error(TAG, "❌ Hook GlobalCache.e() 失败")
            Log.printStackTrace(TAG, e)
        }
    }
}

