package fansirsqi.xposed.sesame.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fansirsqi.xposed.sesame.BuildConfig;
import fansirsqi.xposed.sesame.model.BaseModel;

/**
 * 日志工具类，负责初始化和管理各种类型的日志记录器，并提供日志输出方法。
 */
public class Log {
    private static final String TAG = "";
    private static final Logger RUNTIME_LOGGER;
    private static final Logger SYSTEM_LOGGER;
    private static final Logger RECORD_LOGGER;
    private static final Logger DEBUG_LOGGER;
    private static final Logger FOREST_LOGGER;
    private static final Logger FARM_LOGGER;
    private static final Logger OTHER_LOGGER;
    private static final Logger ERROR_LOGGER;
    private static final Logger CAPTURE_LOGGER;

    static {
        Logback.configureLogbackDirectly();
        RUNTIME_LOGGER = LoggerFactory.getLogger("runtime");
        SYSTEM_LOGGER = LoggerFactory.getLogger("system");
        RECORD_LOGGER = LoggerFactory.getLogger("record");
        DEBUG_LOGGER = LoggerFactory.getLogger("debug");
        FOREST_LOGGER = LoggerFactory.getLogger("forest");
        FARM_LOGGER = LoggerFactory.getLogger("farm");
        OTHER_LOGGER = LoggerFactory.getLogger("other");
        ERROR_LOGGER = LoggerFactory.getLogger("error");
        CAPTURE_LOGGER = LoggerFactory.getLogger("capture");
    }

    private static String truncateLogmsg(String msg) {
        if (msg.length() > 16) {
            return msg.substring(0, 16) + "...";
        }
        return msg;
    }

    public static void system(String msg) {
        SYSTEM_LOGGER.info(TAG + "{}", msg);
    }

    public static void system(String TAG, String msg) {
        system("[" + TAG + "]: " + msg);
    }

    public static void runtime(String msg) {
        system(msg);
        RUNTIME_LOGGER.info(TAG + "{}", msg);
    }

    public static void runtime(String TAG, String msg) {
        runtime("[" + TAG + "]: " + msg);
    }

    public static void record(String msg) {
        runtime(msg);
        if (BaseModel.getRecordLog().getValue()) {
            RECORD_LOGGER.info(TAG + "{}", msg);
        }
    }

    public static void record(String TAG, String msg) {
        record("[" + TAG + "]: " + msg);
    }

    public static void forest(String msg) {
        record(msg);
        FOREST_LOGGER.info("{}", msg);
    }

    public static void forest(String TAG, String msg) {
        forest("[" + TAG + "]: " + msg);
    }

    public static void farm(String msg) {
        record(msg);
        FARM_LOGGER.info("{}", msg);
    }

    public static void farm(String TAG, String msg) {
        farm("[" + TAG + "]: " + msg);
    }

    public static void other(String msg) {
        record(msg);
        OTHER_LOGGER.info("{}", msg);
    }

    public static void other(String TAG, String msg) {
        other("[" + TAG + "]: " + msg);
    }

    public static void debug(String msg) {
        runtime(msg);
        DEBUG_LOGGER.info("{}", msg);
    }

    public static void debug(String TAG, String msg) {
        debug("[" + TAG + "]: " + msg);
    }

    public static void error(String msg) {
        runtime(msg);
        ERROR_LOGGER.error(TAG + "{}", msg);
    }

    public static void error(String TAG, String msg) {
        error("[" + TAG + "]: " + msg);
    }

    public static void capture(String msg) {
        CAPTURE_LOGGER.info(TAG + "{}", msg);
    }

    public static void capture(String TAG, String msg) {
        capture("[" + TAG + "]: " + msg);
    }

    public static void printStackTrace(Throwable th) {
        String stackTrace = "error: " + android.util.Log.getStackTraceString(th);
        error(stackTrace);
    }

    public static void printStackTrace(String msg, Throwable th) {
        String stackTrace = "Throwable error: " + android.util.Log.getStackTraceString(th);
        error(msg, stackTrace);
    }

    public static void printStackTrace(String TAG, String msg, Throwable th) {
        String stackTrace = "[" + TAG + "] Throwable error: " + android.util.Log.getStackTraceString(th);
        error(msg, stackTrace);
    }

    public static void printStackTrace(Exception e) {
        String stackTrace = "Exception error: " + android.util.Log.getStackTraceString(e);
        error(stackTrace);
    }

    public static void printStackTrace(String msg, Exception e) {
        String stackTrace = "Throwable error: " + android.util.Log.getStackTraceString(e);
        error(msg, stackTrace);
    }

    public static void printStackTrace(String TAG, String msg, Exception e) {
        String stackTrace = "[" + TAG + "] Throwable error: " + android.util.Log.getStackTraceString(e);
        error(msg, stackTrace);
    }


}
