package fansirsqi.xposed.sesame.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import fansirsqi.xposed.sesame.BuildConfig
import fansirsqi.xposed.sesame.R
import fansirsqi.xposed.sesame.SesameApplication.Companion.hasPermissions
import fansirsqi.xposed.sesame.SesameApplication.Companion.preferencesKey
import fansirsqi.xposed.sesame.data.RunType
import fansirsqi.xposed.sesame.data.ViewAppInfo
import fansirsqi.xposed.sesame.data.ViewAppInfo.verifyId
import fansirsqi.xposed.sesame.entity.UserEntity
import fansirsqi.xposed.sesame.newui.DeviceInfoCard
import fansirsqi.xposed.sesame.newui.DeviceInfoUtil
import fansirsqi.xposed.sesame.newui.WatermarkView
import fansirsqi.xposed.sesame.newutil.IconManager
import fansirsqi.xposed.sesame.ui.log.LogViewerComposeActivity
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.PermissionUtil
import fansirsqi.xposed.sesame.util.ToastUtil
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.File

//   欢迎自己打包 欢迎大佬pr
//   项目开源且公益  维护都是自愿
//   但是如果打包改个名拿去卖钱忽悠小白
//   那我只能说你妈死了 就当开源项目给你妈烧纸钱了
class MainActivity : BaseActivity() {

    // 使用 ViewModel 委托
    private val viewModel: MainViewModel by viewModels()

    private lateinit var oneWordTextView: TextView
    private lateinit var watermarkView: WatermarkView

    // Shizuku 权限监听
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1234) {
            val msg = if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku 授权成功！" else "Shizuku 授权被拒绝"
            ToastUtil.showToast(this, msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 初始化 View
        initViews()

        // 2. 检查权限并初始化 ViewModel 逻辑
        if (PermissionUtil.checkOrRequestFilePermissions(this)) {
            viewModel.initAppLogic()
        }

        // 3. 观察数据变化
        observeViewModel()

        // 4. 设置 Shizuku
        setupShizuku()

        // 5. 同步图标状态
        val prefs = getSharedPreferences(preferencesKey, MODE_PRIVATE)
        IconManager.syncIconState(this, prefs.getBoolean("is_icon_hidden", false))
    }

    private fun initViews() {
        oneWordTextView = findViewById(R.id.one_word)
        watermarkView = WatermarkView.install(this)

        // 设置 Compose 内容
        findViewById<ComposeView>(R.id.device_info).setContent {
            val colors = lightColorScheme(
                primary = Color(0xFF3F51B5),
                onPrimary = Color.White,
                background = Color(0xFFF5F5F5)
            )

            // 🔥 修复点 1：使用 produceState 处理挂起函数
            // produceState 会自动启动协程，并在结果返回时触发 UI 重组
            val infoMap by androidx.compose.runtime.produceState<Map<String, String>?>(initialValue = null) {
                // 这里是在协程中运行的
                value = DeviceInfoUtil.showInfo(verifyId, this@MainActivity)
            }

            MaterialTheme(colorScheme = colors) {
                // 只有当数据加载完成后才显示卡片
                infoMap?.let { DeviceInfoCard(it) }
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 一言
                launch { viewModel.oneWord.collect { oneWordTextView.text = it } }

                // 运行状态 & 激活用户 (合并监听更新标题)
                launch {
                    viewModel.runType.collect { type ->
                        updateSubTitle(type, viewModel.activeUser.value)
                    }
                }
                launch {
                    viewModel.activeUser.collect { user ->
                        updateSubTitle(viewModel.runType.value, user)
                    }
                }
            }
        }
    }

    private fun setupShizuku() {
        Shizuku.addRequestPermissionResultListener(shizukuListener)
        if (Shizuku.pingBinder() && checkSelfPermission(ShizukuProvider.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1234)
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次返回页面时，刷新用户列表和配置 (现在是异步的，不会卡顿)
        if (hasPermissions) {
            viewModel.reloadUserConfigs()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        watermarkView.refresh()
    }

    /**
     * 更新标题栏 (逻辑保留在 Activity，数据来自 ViewModel)
     */
    private fun updateSubTitle(runType: RunType, currentUserEntity: UserEntity?) {
        baseTitle = "${ViewAppInfo.appTitle} [${runType.nickName}]"
        baseSubtitle = "当前载入: ${currentUserEntity?.showName ?: "未载入^o^ 重启支付宝看看👀"}"
        val colorRes = when (runType) {
            RunType.DISABLE -> R.color.not_active_text
            RunType.ACTIVE -> R.color.active_text
            RunType.LOADED -> R.color.textColorPrimary
        }
        setBaseTitleTextColor(ContextCompat.getColor(this, colorRes))
    }

    /**
     * 统一点击事件处理
     */
    fun onClick(v: View) {
        when (v.id) {
            R.id.btn_forest_log -> openLogFile(Files.getForestLogFile())
            R.id.btn_farm_log -> openLogFile(Files.getFarmLogFile())
            R.id.btn_view_error_log_file -> executeWithVerification { openLogFile(Files.getErrorLogFile()) }
            R.id.btn_view_all_log_file -> openLogFile(Files.getRecordLogFile())

            R.id.btn_github -> openUrl("https://github.com/Fansirsqi/Sesame-TK")

            R.id.btn_settings -> {
                // 使用扩展函数显示弹窗
                showUserSelectionDialog(viewModel.userList.value) { selectedUser ->
                    navigateToSettings(selectedUser)
                }
            }

            R.id.one_word -> viewModel.fetchOneWord()
        }
    }

    private fun openLogFile(logFile: File) {
        if (!logFile.exists()) {
            ToastUtil.showToast(this, "日志文件不存在: ${logFile.name}")
            return
        }
        val intent = Intent(this, LogViewerComposeActivity::class.java).apply {
            data = logFile.toUri()
        }
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // 菜单逻辑建议保留在 Activity，属于纯 View 层控制
        try {
            val pm = packageManager
            val defaultComp = ComponentName(this, IconManager.COMPONENT_DEFAULT)
            val christmasComp = ComponentName(this, IconManager.COMPONENT_CHRISTMAS)

            val isDefault = pm.getComponentEnabledSetting(defaultComp) in listOf(
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            )
            val isChristmas = pm.getComponentEnabledSetting(christmasComp) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

            val isIconVisible = isDefault || isChristmas

            menu.add(0, 1, 1, R.string.hide_the_application_icon).setCheckable(true).isChecked = !isIconVisible
            menu.add(0, 2, 2, R.string.view_capture)
            menu.add(0, 3, 3, R.string.extend)
            if (BuildConfig.DEBUG) {
                menu.add(0, 4, 4, "清除配置")
            }
        } catch (e: Exception) {
            Log.printStackTrace(e)
            return false
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                val shouldHide = !item.isChecked
                item.isChecked = shouldHide
                getSharedPreferences(preferencesKey, MODE_PRIVATE).edit {
                    putBoolean("is_icon_hidden", shouldHide)
                }
                viewModel.syncIconState(shouldHide)
                Toast.makeText(this, "设置已保存，可能需要重启桌面才能生效", Toast.LENGTH_SHORT).show()
                return true
            }
            2 -> openLogFile(Files.getCaptureLogFile())
            3 -> {
                startActivity(Intent(this, ExtendActivity::class.java))
                return true
            }
            4 -> {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ 警告")
                    .setMessage("🤔 确认清除所有模块配置？")
                    .setPositiveButton(R.string.ok) { _, _ ->
                        if (Files.delFile(Files.CONFIG_DIR)) Toast.makeText(this, "🙂 清空配置成功", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(this, "😭 清空配置失败", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}