package fansirsqi.xposed.sesame.hook.keepalive;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import fansirsqi.xposed.sesame.util.Log;

/**
 * 支付宝组件保活助手
 * 
 * 最低支持：Android 8.0+ (API 26+)
 * 
 * 功能：
 * 1. 息屏唤醒支付宝进程
 * 2. WakeLock管理
 * 3. 定期保活（使用AlarmManager每60秒唤醒一次）
 * 
 * 注意：
 * - 使用 AlarmManager 定时唤醒支付宝内部组件（ClientMonitorWakeupReceiver）
 * - 建议在任务执行前主动调用唤醒方法
 * 
 * 唤醒方式：
 * - wakeupAlipay(): 完整唤醒（启动监控服务）
 */
public class AlipayComponentHelper {

    private static final String TAG = "AlipayComponentHelper";
    private final Context context;
    private static final String PACKAGE_NAME = "com.eg.android.AlipayGphone";
    private static final int ALARM_REQUEST_CODE = 1001;
    private PowerManager.WakeLock wakeLock;
    private PendingIntent keepAlivePendingIntent;

    public AlipayComponentHelper(Context context) {
        this.context = context;
    }

    // ========== 唤醒方法 ==========

    /**
     * 完整唤醒支付宝进程（支持息屏）
     * 通过广播方式启动所有监控服务（流量监控、日志同步、电量降级、计步统计）
     * 
     * @throws Exception 如果发送广播失败
     */
    public void wakeupAlipay() throws Exception {
        acquireWakeLock();
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    PACKAGE_NAME,
                    "com.alipay.mobile.logmonitor.ClientMonitorWakeupReceiver"
            ));
            intent.putExtra("autoWakeup", true);
            context.sendBroadcast(intent);
            Log.runtime(TAG, "✅ 已发送完整唤醒广播");
        } finally {
            releaseWakeLockDelayed();
        }
    }


    /**
     * 获取唤醒锁
     */
    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sesame:AlipayWakeup");
                wakeLock.setReferenceCounted(false);
            }
            
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(10000); // 最长持有10秒
                Log.runtime(TAG, "🔓 已获取唤醒锁");
            }
        } catch (Exception e) {
            Log.runtime(TAG, "获取唤醒锁失败: " + e.getMessage());
        }
    }
    /**
     * 设置定期保活（每 60 秒唤醒一次）
     *
     * 功能说明：
     * 1. 立即执行一次完整唤醒（无需等待闹钟触发）
     * 2. 设置 60 秒重复闹钟，定期唤醒支付宝内部的 ClientMonitorWakeupReceiver
     *
     * ⚠️ 注意：此方法使用系统 AlarmManager，仅用于唤醒支付宝内部组件
     * - 固定使用 request code 1001，避免闹钟泄漏
     * - 使用 setRepeating() 创建重复闹钟
     * - Android 系统限制 setRepeating() 最小间隔为 60 秒，即使设置更短也会被系统调整
     *
     * 技术说明：
     * - Android 5.1+ 开始强制限制 AlarmManager.setRepeating() 最小间隔为 60 秒
     * - 系统会自动调整任何小于 60 秒的间隔为 60 秒
     * - 这是为了减少设备唤醒次数，延长电池寿命
     *
     * 唤醒策略：
     * - 立即唤醒：使用 wakeupAlipay() 完整唤醒
     * - 定期唤醒：通过闹钟触发 ClientMonitorWakeupReceiver（支付宝内部组件）
     */
    public void setupKeepAlive() {
        try {
            // 1. 立即执行一次完整唤醒
            try {
                wakeupAlipay();
                Log.runtime(TAG, "✅ 保活启动：已立即执行完整唤醒");
            } catch (Exception e) {
                Log.runtime(TAG, "⚠️ 保活启动：立即唤醒失败，将依赖定期闹钟: " + e.getMessage());
            }
            
            // 2. 设置定期闹钟
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) {
                Log.runtime(TAG, "❌ 获取AlarmManager失败");
                return;
            }

            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    PACKAGE_NAME,
                    "com.alipay.mobile.logmonitor.ClientMonitorWakeupReceiver"
            ));
            
            // Android 12+ 必须指定 FLAG_IMMUTABLE 或 FLAG_MUTABLE
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            
            keepAlivePendingIntent = PendingIntent.getBroadcast(
                    context, ALARM_REQUEST_CODE, intent, flags
            );

            // 每 60 秒唤醒一次（系统最小间隔限制）
            alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis(),
                    60 * 1000,  // 60 秒（Android 系统强制的最小间隔）
                    keepAlivePendingIntent
            );
            Log.runtime(TAG, "✅ 保活启动：已设置定期闹钟 - 间隔60秒 (request code: " + ALARM_REQUEST_CODE + ")");
        } catch (Exception e) {
            Log.runtime(TAG, "❌ 设置定期保活失败: " + e.getMessage());
            Log.printStackTrace(TAG, e);
        }
    }
    /**
     * 延迟释放唤醒锁
     */
    private void releaseWakeLockDelayed() {
        new android.os.Handler(context.getMainLooper()).postDelayed(() -> {
            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                    Log.runtime(TAG, "🔒 已释放唤醒锁");
                } catch (Exception e) {
                    Log.runtime(TAG, "释放唤醒锁失败: " + e.getMessage());
                }
            }
        }, 5000);
    }

    /**
     * 停止定期保活并清理资源
     */
    public void stopKeepAlive() {
        try {
            // 取消定期闹钟
            if (keepAlivePendingIntent != null) {
                AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                if (alarmManager != null) {
                    alarmManager.cancel(keepAlivePendingIntent);
                    keepAlivePendingIntent.cancel();
                    keepAlivePendingIntent = null;
                    Log.runtime(TAG, "✅ 已取消定期保活闹钟");
                }
            }

            // 释放唤醒锁
            if (wakeLock != null) {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
                wakeLock = null;
                Log.runtime(TAG, "✅ 已释放唤醒锁资源");
            }
        } catch (Exception e) {
            Log.runtime(TAG, "停止保活失败: " + e.getMessage());
            Log.printStackTrace(TAG, e);
        }
    }
}
