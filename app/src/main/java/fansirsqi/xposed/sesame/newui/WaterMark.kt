package fansirsqi.xposed.sesame.newui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.core.graphics.withSave
import fansirsqi.xposed.sesame.BuildConfig
import fansirsqi.xposed.sesame.ui.MainViewModel.Companion.verifuids
import fansirsqi.xposed.sesame.util.TimeUtil
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 水印层组件
 * @param autoRefresh 是否自动刷新时间 (默认 true)
 * @param refreshIntervalMs 自动刷新间隔 (默认 1000ms)
 * @param refreshTrigger 外部传入的刷新触发器 (当此值变化时强制刷新)
 */
@Composable
fun WatermarkLayer(
    modifier: Modifier = Modifier,
    autoRefresh: Boolean = true,
    refreshIntervalMs: Long = 1000L,
    refreshTrigger: Any? = null, // 可选：外部手动刷新信号
    content: @Composable () -> Unit
) {
    // 1. 获取主题颜色
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f).toArgb() // 稍微调低透明度更美观

    // 2. 时间戳状态 (用于驱动重组)
    var currentTime by remember { mutableStateOf(TimeUtil.getFormatDateTime()) }

    // 3. 自动刷新逻辑
    if (autoRefresh) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(refreshIntervalMs)
                currentTime = TimeUtil.getFormatDateTime()
            }
        }
    }

    // 4. 计算文本行
    // 依赖项：verifuids (账号变化), currentTime (时间变化), refreshTrigger (外部强制刷新)
    val textLines = remember(verifuids, currentTime, refreshTrigger) {
        val prefixLines = listOf("免费模块仅供学习,勿在国内平台传播!!")
        val suffix = "Now: $currentTime"

        val uidLines = if (verifuids.isEmpty()) {
            listOf("未载入账号", "请启用模块后重启一次支付宝", "确保模块生成对应账号配置")
        } else {
            verifuids.mapIndexed { index, uid -> "UID${index + 1}: $uid" }
        }

        val versionLines = listOf(
            "Ver: ${BuildConfig.VERSION_NAME}.${BuildConfig.VERSION_CODE}",
            "Build: ${BuildConfig.BUILD_DATE}",
        )

        prefixLines + uidLines + listOf(suffix) + versionLines
    }

    // 5. 字体大小与随机偏移
    val density = LocalDensity.current
    val textSizePx = with(density) { 13.sp.toPx() } // 微调字号

    val offsetX = remember { Random.nextInt(-200, 200).toFloat() }
    val offsetY = remember { Random.nextInt(-200, 200).toFloat() }

    Box(modifier = modifier) {
        // A. 内容层
        content()

        // B. 水印层
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            val paint = Paint().apply {
                color = textColor
                textSize = textSizePx
                isAntiAlias = true
                textAlign = Paint.Align.LEFT
                // typeface = Typeface.DEFAULT_BOLD // 可选：加粗
            }

            val fontHeight = paint.fontSpacing
            val totalTextHeight = fontHeight * textLines.size
            val maxLineWidth = textLines.maxOfOrNull { paint.measureText(it) } ?: 0f

            val densityFactor = 0.9f
            val horizontalSpacing = (maxLineWidth * 1.5f / densityFactor)
            val verticalSpacing = (totalTextHeight * 2.5f / densityFactor) // 稍微拉大垂直间距

            val rotationDegrees = -30f

            drawContext.canvas.nativeCanvas.apply {
                withSave {
                    rotate(rotationDegrees, width / 2, height / 2)

                    var y = -height + offsetY
                    var yIndex = 0

                    while (y < height * 2) {
                        var x = -width + offsetX
                        if (yIndex % 2 == 1) x += horizontalSpacing / 2

                        while (x < width * 2) {
                            textLines.forEachIndexed { index, line ->
                                drawText(
                                    line,
                                    x,
                                    y + index * fontHeight,
                                    paint
                                )
                            }
                            x += horizontalSpacing
                        }
                        y += verticalSpacing
                        yIndex++
                    }
                }
            }
        }
    }
}