package fansirsqi.xposed.sesame.hook

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import fansirsqi.xposed.sesame.util.Log

/**
 * 安全的 Context 包装器
 * 用于处理 WorkManager 在 Xposed 环境中的资源访问问题
 * 
 * WorkManager 会尝试读取一些 boolean 资源配置，但这些资源在宿主应用中不存在
 * 此包装器拦截资源访问，返回安全的默认值
 */
class SafeContextWrapper(base: Context) : ContextWrapper(base) {
    
    companion object {
        private const val TAG = "SafeContextWrapper"
    }
    
    private val safeResources: Resources by lazy {
        SafeResources(baseContext.resources)
    }
    
    override fun getResources(): Resources {
        return safeResources
    }
    
    override fun getApplicationContext(): Context {
        // 确保 applicationContext 也是安全包装的
        val appContext = super.getApplicationContext()
        return if (appContext is SafeContextWrapper) {
            appContext
        } else {
            SafeContextWrapper(appContext)
        }
    }
    
    /**
     * 安全的 Resources 包装器
     */
    private class SafeResources(private val base: Resources) : Resources(
        base.assets,
        base.displayMetrics,
        base.configuration
    ) {
        
        override fun getBoolean(id: Int): Boolean {
            return try {
                base.getBoolean(id)
            } catch (e: NotFoundException) {
                // 资源不存在时返回默认值
                Log.debug(TAG, "资源 ID $id 不存在，返回默认值 false")
                false
            }
        }
        
        override fun getInteger(id: Int): Int {
            return try {
                base.getInteger(id)
            } catch (e: NotFoundException) {
                Log.debug(TAG, "资源 ID $id 不存在，返回默认值 0")
                0
            }
        }
        
        override fun getString(id: Int): String {
            return try {
                base.getString(id)
            } catch (e: NotFoundException) {
                Log.debug(TAG, "资源 ID $id 不存在，返回空字符串")
                ""
            }
        }
        
        override fun getString(id: Int, vararg formatArgs: Any?): String {
            return try {
                base.getString(id, *formatArgs)
            } catch (e: NotFoundException) {
                Log.debug(TAG, "资源 ID $id 不存在，返回空字符串")
                ""
            }
        }
    }
}

