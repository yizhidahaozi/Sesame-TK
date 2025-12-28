package fansirsqi.xposed.sesame.hook

import android.annotation.SuppressLint
import android.app.Activity
import fansirsqi.xposed.sesame.hook.simple.SimplePageManager
import fansirsqi.xposed.sesame.hook.simple.SimpleViewImage
import fansirsqi.xposed.sesame.model.BaseModel
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.SwipeUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/**
 * 滑动验证码路径信息的数据类。
 * @property startX 滑动的起始 X 坐标。
 * @property startY 滑动的起始 Y 坐标。
 * @property endX 滑动的结束 X 坐标。
 * @property endY 滑动的结束 Y 坐标。
 */
data class SlidePath(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int
) {
    fun toIntArray(): IntArray = intArrayOf(startX, startY, endX, endY)
}

/**
 * 验证码处理程序的基类，提供处理滑动验证码的通用逻辑。
 * 该类专门用于处理支付宝验证页面上的滑动验证码。
 */
abstract class BaseCaptchaHandler {

    companion object {
        private const val TAG = "CaptchaHandler"

        // 滑动位置偏移量和持续时间
        private const val SLIDE_START_OFFSET = 50
        private const val SLIDE_END_MARGIN = 100
        private const val SLIDE_DURATION = 300L

        // 滑动后延迟检查是否成功
        private const val POST_SLIDE_CHECK_DELAY_MS = 2000L

        // 查找滑动验证文本的 XPath。文本是中文的。
        private const val SLIDE_VERIFY_TEXT_XPATH = "//TextView[contains(@text,'向右滑动验证')]"

        // 并发控制，防止多个处理程序同时运行。
        private val captchaProcessingMutex = Mutex()
    }

    /**
     * 获取在 DataStore 中存储滑动路径的键。
     * @return 用于存储滑动路径的键。
     */
    protected abstract fun getSlidePathKey(): String


    /**
     * 处理当前 Activity 中的验证码
     * @param activity 当前 Activity 实例
     * @param root 根视图图像
     * @return 如果验证码处理成功返回 true，否则返回 false
     */
    open suspend fun handleActivity(activity: Activity, root: SimpleViewImage): Boolean {
        return try {
            handleSlideCaptcha(activity, root)
        } catch (e: Exception) {
            Log.error(TAG, "处理验证码页面时发生异常: ${e.stackTraceToString()}")
            false
        }
    }


    @SuppressLint("SuspiciousIndentation")
    private suspend fun handleSlideCaptcha(activity: Activity, root: SimpleViewImage): Boolean {
        if (!captchaProcessingMutex.tryLock()) {
            Log.captcha(TAG, "验证码正在处理中，跳过本次调用")
            return true // 返回 true 告知上层已处理，避免重试
        }

        try {
            Log.captcha(TAG, "========== 开始处理滑动验证码 ==========")

            Log.captcha(TAG, "正在查找滑动验证文本...")
            val slideTextInDialog = findSlideTextInDialog()
            if (slideTextInDialog == null) {
                Log.captcha(TAG, "未找到滑动验证文本，跳过处理")
                return false // 未找到关键视图，返回 false 让其他处理器尝试
            }

            Log.captcha(TAG, "发现滑动验证文本: ${slideTextInDialog.getText()}")
            val slideRect = getSlideRect(slideTextInDialog) ?: run {
                Log.captcha(TAG, "未找到滑动文本的父节点")
                return false // 结构不符，返回 false
            }

            val slidePath = calculateSlidePath(activity, slideRect)
            logSlideInfo(activity, slideRect, slidePath)

            if (!BaseModel.enableSlide.value) {
                Log.captcha(TAG, "Sesame-TK 滑动验证功能已禁用，使用 ShortX 广播方式")
                ApplicationHook.sendBroadcastShell(
                    getSlidePathKey(),
                    "input swipe " + slidePath.toIntArray().joinToString(" ")
                )
            } else {
                performSlideAndVerify(activity, slidePath)
            }
            return true // 已决定处理，返回 true 阻止 SimplePageManager 重试
        } catch (e: Exception) {
            Log.captcha(TAG, "处理滑动验证码时发生错误: ${e.stackTraceToString()}")
            return false
        } finally {
            Log.captcha(TAG, "========== 结束处理滑动验证码 ==========")
            captchaProcessingMutex.unlock()
        }
    }

    /**
     * 获取滑动区域的位置信息。
     * @param slideText 滑动文本的视图图像。
     * @return 表示滑动区域位置的 IntArray，如果无法确定则返回 null。
     */
    private fun getSlideRect(slideText: SimpleViewImage): IntArray? {
        val slideTextParent = slideText.parentNode(1) ?: return null
        return slideTextParent.locationOnScreen()
    }

    /**
     * 根据滑动区域计算滑动路径。
     * @param activity 当前的 Activity。
     * @param slideRect 滑动区域的位置。
     * @return 计算出的 SlidePath。
     */
    private fun calculateSlidePath(activity: Activity, slideRect: IntArray): SlidePath {
        val displayMetrics = activity.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels

        val startX = slideRect[0] + SLIDE_START_OFFSET
        val centerY = slideRect[1] + SLIDE_START_OFFSET
        val endX = screenWidth - SLIDE_END_MARGIN

        return SlidePath(startX, centerY, endX, centerY)
    }

    /**
     * 记录滑动操作的详细信息。
     * @param activity 当前的 Activity。
     * @param slideRect 滑动区域的位置。
     * @param slidePath 计算出的滑动路径。
     */
    private fun logSlideInfo(activity: Activity, slideRect: IntArray, slidePath: SlidePath) {
        val displayMetrics = activity.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        Log.captcha(TAG, "滑动区域位置: ${slideRect.contentToString()}")
        Log.captcha(TAG, "屏幕尺寸: ${screenWidth}x$screenHeight")
        Log.captcha(
            TAG,
            "滑动路径: (${slidePath.startX}, ${slidePath.startY}) -> (${slidePath.endX}, ${slidePath.endY})"
        )
    }

    /**
     * 执行滑动操作并验证结果。如果主要的滑动失败，
     * 它会回退到发送广播命令。
     * @param activity 当前的 Activity。
     * @param slidePath 要滑动的路径。
     * @return 如果验证码成功解除返回 true，否则返回 false。
     */
    private suspend fun performSlideAndVerify(activity: Activity, slidePath: SlidePath): Boolean {
        Log.captcha(TAG, "========== 执行滑动操作 ==========")

        val swipeSuccess = SwipeUtil.swipe(
            activity,
            slidePath.startX,
            slidePath.startY,
            slidePath.endX,
            slidePath.endY,
            SLIDE_DURATION
        )

        if (swipeSuccess) {
            Log.captcha(TAG, "滑动操作完成，检查验证码文本是否消失...")
            delay(POST_SLIDE_CHECK_DELAY_MS)
            return if (checkCaptchaTextGone()) {
                Log.captcha(TAG, "验证码文本已消失，滑动成功。")
                true
            } else {
                Log.captcha(TAG, "验证码文本仍然存在，滑动可能失败。")
                // 如果验证码仍然存在，返回 false 让上层处理。
                false
            }
        } else {
            Log.captcha(TAG, "滑动操作失败，回退到发送广播给 ShortX...")
            val api = getSlidePathKey()
            val command =
                "input swipe ${slidePath.startX} ${slidePath.startY} ${slidePath.endX} ${slidePath.endY} $SLIDE_DURATION"
            ApplicationHook.sendBroadcastShell(api, command)
            return false // 主要方法失败。
        }
    }

    /**
     * 检查验证码验证文本是否已从视图中消失。
     * @return 如果文本已消失返回 true，如果仍然存在返回 false。
     */
    private fun checkCaptchaTextGone(): Boolean {
        val slideTextInDialog = findSlideTextInDialog()
        return if (slideTextInDialog == null) {
            Log.captcha(TAG, "验证码文本已消失 (在对话框中未找到)。")
            true
        } else {
            Log.captcha(TAG, "验证码文本仍然存在 (在对话框中找到)。")
            false
        }
    }

    /**
     * 在对话框视图中查找滑动验证文本。
     * @return 如果找到则返回文本视图的 SimpleViewImage，否则返回 null。
     */
    private fun findSlideTextInDialog(): SimpleViewImage? {
        return try {
            Log.captcha(TAG, "尝试通过 XPath 查找滑动验证文本: $SLIDE_VERIFY_TEXT_XPATH")
            val result = SimplePageManager.tryGetTopView(SLIDE_VERIFY_TEXT_XPATH)
            if (result != null) {
                Log.captcha(TAG, "成功找到滑动验证文本。")
            } else {
                Log.captcha(TAG, "未找到滑动验证文本。")
            }
            result
        } catch (e: Exception) {
            Log.captcha(TAG, "由于异常导致查找验证码文本失败: ${e.stackTraceToString()}")
            null
        }
    }
}
