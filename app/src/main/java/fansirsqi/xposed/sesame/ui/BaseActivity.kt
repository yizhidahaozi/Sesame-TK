package fansirsqi.xposed.sesame.ui

import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.appbar.MaterialToolbar
import fansirsqi.xposed.sesame.R
import fansirsqi.xposed.sesame.data.ViewAppInfo

open class BaseActivity : AppCompatActivity() {

    // 使用 lazy 委托，当第一次访问 toolbar 时会自动查找 ID
    // 只有在 setContentView 之后访问它才是安全的
    protected val toolbar: MaterialToolbar by lazy { findViewById(R.id.x_toolbar) }

    // 暂存标题和副标题
    private var pendingTitle: CharSequence? = ViewAppInfo.appTitle
    private var pendingSubtitle: CharSequence? = null

    // 基础标题
    open var baseTitle: String?
        get() = pendingTitle?.toString()
        set(value) {
            pendingTitle = value
            // 只有当 Window 已经附加了布局，且 toolbar 确实存在时才设置
            // 但简单的做法是：只要 setContentView 调用过，lazy 就能工作。
            // 我们可以用一个简单的 try-catch 或者 flag 来保护，
            // 或者更优雅地：只在 onContentChanged 之后更新 View。
            updateToolbarText()
        }

    // 基础副标题
    open var baseSubtitle: String?
        get() = pendingSubtitle?.toString()
        set(value) {
            pendingSubtitle = value
            updateToolbarText()
        }

    // 标记布局是否已加载
    private var isContentLayoutSet = false

    override fun onContentChanged() {
        super.onContentChanged()
        // 系统回调：当 setContentView 完成后调用
        isContentLayoutSet = true

        setSupportActionBar(toolbar)
        // 初始设置
        toolbar.setContentInsetsAbsolute(0, 0)
        updateToolbarText()
    }

    private fun updateToolbarText() {
        if (isContentLayoutSet) {
            // 这里可以直接访问 toolbar，因为布局已经加载了
            toolbar.title = pendingTitle
            toolbar.subtitle = pendingSubtitle
        }
    }

    fun setBaseTitleTextColor(color: Int) {
        toolbar.setTitleTextColor(color)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 纯 UI 设置
        setupWindow()
    }

    private fun setupWindow() {
        // Edge-to-Edge 支持
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 控制状态栏文字颜色
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !isNightMode()
    }


    private fun updateTitles() {
        toolbar.title = baseTitle
        toolbar.subtitle = baseSubtitle
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if ((newConfig.diff(resources.configuration) and Configuration.UI_MODE_NIGHT_MASK) != 0) {
            recreate()
        } else {
            updateTitles()
        }
    }

    private fun isNightMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}