package fansirsqi.xposed.sesame.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import fansirsqi.xposed.sesame.BuildConfig
import fansirsqi.xposed.sesame.R
import fansirsqi.xposed.sesame.util.Log
import androidx.core.net.toUri

@SuppressLint("StaticFieldLeak")
object ViewAppInfo {
    val TAG: String = ViewAppInfo::class.java.simpleName
    var context: Context? = null
    var appTitle: String = ""
    var appVersion: String = ""
    var appBuildTarget: String = ""
    var appBuildNumber: String = ""
    val emojiList =
        listOf(
            "🍅", "🍓", "🥓", "🍂", "🍚", "🌰", "🟢", "🌴",
            "🥗", "🧀", "🥩", "🍍", "🌶️", "🍲", "🍆", "🥕",
            "✨", "🍑", "🍘", "🍀", "🥞", "🍈", "🥝", "🧅",
            "🌵", "🌾", "🥜", "🍇", "🌭", "🥑", "🥐", "🥖",
            "🍊", "🌽", "🍉", "🍖", "🍄", "🥚", "🥙", "🥦",
            "🍌", "🍱", "🍏", "🍎", "🌲", "🌿", "🍁", "🍒",
            "🥔", "🌯", "🌱", "🍐", "🍞", "🍳", "🍙", "🍋",
            "🍗", "🌮", "🍃", "🥘", "🥒", "🧄", "🍠", "🥥", "📦"
        )

    //    var runType: RunType? = RunType.DISABLE
    @Volatile
    internal var runType: RunType? = RunType.DISABLE
        @Synchronized set

    @JvmStatic
    fun setRunType(type: RunType) {
        runType = type
    }

    @JvmStatic
    fun getRunType() = runType

    /**
     * 初始化 ViewAppInfo，设置应用的相关信息，如版本号、构建日期等
     *
     * @param context 上下文对象，用于获取应用的资源信息
     */
    fun init(context: Context) {
        if (ViewAppInfo.context == null) {
            ViewAppInfo.context = context
            appBuildNumber = BuildConfig.VERSION_CODE.toString()
            appTitle = context.getString(R.string.app_name) //+ BuildConfig.VERSION_NAME
            appBuildTarget = BuildConfig.BUILD_DATE + " " + BuildConfig.BUILD_TIME + " ⏰"
            try {
                appVersion = "${BuildConfig.VERSION_NAME} " + emojiList.random()
            } catch (e: Exception) {
                Log.printStackTrace(e)
            }
        }
    }


    /**
     * 判断当前应用是否处于调试模式
     *
     * @return 如果应用处于调试模式返回 true，否则返回 false
     */
    val isApkInDebug: Boolean
        get() {
            try {
                val info = context!!.applicationInfo
                return (info.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            } catch (_: Exception) {
                return false
            }
        }

    fun isApkInDebug2(context: Context): Boolean {
        try {
            val info = context.applicationInfo
            return (info.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Exception) {
            return false
        }
    }
}
