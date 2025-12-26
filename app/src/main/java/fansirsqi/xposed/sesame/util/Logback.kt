package fansirsqi.xposed.sesame.util

import android.content.Context
import android.util.Log
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.android.LogcatAppender
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.util.FileSize
import org.slf4j.LoggerFactory
import java.io.File

object Logback {
    private var isFileInitialized = false

    // 定义所有 Logger 的名称
    val LOG_NAMES = listOf(
        "runtime", "system", "record", "debug", "forest",
        "farm", "other", "error", "capture", "captcha"
    )

    /**
     * 阶段1：初始化 Logcat (保证控制台一定有日志)
     * 在 Log 类的 init 块中自动调用
     */
    fun initLogcatOnly() {
        try {
            val lc = LoggerFactory.getILoggerFactory() as LoggerContext
            lc.reset() // 清除之前的配置

            // 配置 Logcat Appender
            val encoder = PatternLayoutEncoder().apply {
                context = lc
                pattern = "[%thread] %msg%n"
                start()
            }

            val logcatAppender = LogcatAppender().apply {
                context = lc
                this.encoder = encoder
                name = "LOGCAT"
                start()
            }

            // 为根 Logger 添加 Logcat 输出
            lc.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).apply {
                level = Level.INFO
                addAppender(logcatAppender)
            }

        } catch (e: Exception) {
            Log.e("SesameLog", "Logback initLogcatOnly failed", e)
        }
    }

    /**
     * 阶段2：初始化文件日志 (有了 Context 之后调用)
     * 这是一个“追加”操作，不会打断 Logcat 日志
     */
    @Synchronized
    fun initFileLogging(context: Context) {
        if (isFileInitialized) return

        val logDir = getLogDir(context) ?: return

        try {
            val lc = LoggerFactory.getILoggerFactory() as LoggerContext

            // 为每个特定业务的 Logger 添加文件 Appender
            LOG_NAMES.forEach { logName ->
                addFileAppender(lc, logName, logDir)
            }

            isFileInitialized = true
            Log.i("SesameLog", "File logging initialized at: $logDir")
        } catch (e: Exception) {
            Log.e("SesameLog", "Logback initFileLogging failed", e)
        }
    }

    private fun getLogDir(context: Context): String? {
        return try {
            // 优先尝试外部私有目录 (不需要权限，且卸载后自动清除)
            val dir = context.getExternalFilesDir("logs")
                ?: File(context.filesDir, "logs") // 回退到内部私有目录

            if (!dir.exists()) dir.mkdirs()
            dir.absolutePath + File.separator
        } catch (e: Exception) {
            Log.e("SesameLog", "Failed to resolve log dir", e)
            null
        }
    }

    private fun addFileAppender(lc: LoggerContext, logName: String, logDir: String) {
        // 1. 先创建实例，不要直接链式 apply，以便后面引用它
        val fileAppender = RollingFileAppender<ILoggingEvent>()

        fileAppender.apply {
            context = lc
            name = "FILE-$logName"
            file = "$logDir$logName.log"
            // 2. 配置 Policy
            val policy = SizeAndTimeBasedRollingPolicy<ILoggingEvent>().apply {
                context = lc
                fileNamePattern = "${logDir}bak/$logName-%d{yyyy-MM-dd}.%i.log"
                setMaxFileSize(FileSize.valueOf("10MB"))
                setTotalSizeCap(FileSize.valueOf("100MB"))
                maxHistory = 7
                // 🔥 修复点 1: 必须调用 setParent 方法，而不是使用 parent 属性
                // 🔥 修复点 2: 传入外层的 fileAppender 变量
                setParent(fileAppender)
                start()
            }
            // 将配置好的 policy 赋值给 appender
            rollingPolicy = policy

            // 3. 配置 Encoder
            encoder = PatternLayoutEncoder().apply {
                context = lc
                pattern = "%d{dd日 HH:mm:ss.SS} %msg%n"
                start()
            }

            // 启动 Appender
            start()
        }

        // 4. 获取对应的 Logger 并添加 Appender
        lc.getLogger(logName).apply {
            level = Level.INFO
            isAdditive = true
            addAppender(fileAppender)
        }
    }
}