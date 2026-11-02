package dev.imeiyou.focushelper;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * 在以下时机检查并恢复无障碍服务：
 *   开机完成（含部分系统使用的 QUICKBOOT_POWERON）：立即检查一次，
 *            并在 30 秒、2 分钟、5 分钟后各再检查一次
 *   蓝牙设备连上投影仪（ACL_CONNECTED，例如手机遥控器连上）：检查一次
 *            —— 这是开机广播之外的第二条触发途径，投影仪从待机唤醒、
 *               没有真正重启时也能触发
 *   应用更新（例如重新安装）：立即检查
 *   定时检查（ACTION_GUARD）：检查一次
 *
 * 开机 60 秒以后的检查，发现"清单里有但服务没运行"时会移除再加回（重新绑定）。
 */
public class GuardReceiver extends BroadcastReceiver {

    /** 开机多久以后才允许"重新绑定"：太早的话服务可能正在被系统绑定。 */
    private static final long REBIND_AFTER_UPTIME_MS = 60_000;

    /** 蓝牙连接事件可能很频繁（原装遥控器每次唤醒都会重连），间隔太短的忽略。 */
    private static final long ACL_MIN_INTERVAL_MS = 30_000;
    private static long lastAclCheckAt = -ACL_MIN_INTERVAL_MS;

    private static final String QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";
    private static final String QUICKBOOT_HTC = "com.htc.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = String.valueOf(intent.getAction());
        final Context app = context.getApplicationContext();
        final boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || QUICKBOOT.equals(action) || QUICKBOOT_HTC.equals(action);
        final boolean acl = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action);

        if (acl) {
            long now = SystemClock.elapsedRealtime();
            synchronized (GuardReceiver.class) {
                if (now - lastAclCheckAt < ACL_MIN_INTERVAL_MS) {
                    return;
                }
                lastAclCheckAt = now;
            }
        }

        if (boot) {
            GuardLog.markBootSeen(app);
            GuardLog.add(app, "收到开机广播 " + shortAction(action)
                    + "，服务" + (AccessibilityGuard.isBound(app) ? "已在运行" : "未运行"));
            AccessibilityGuard.scheduleCheck(app, 30_000, 1);
            AccessibilityGuard.scheduleCheck(app, 120_000, 2);
            AccessibilityGuard.scheduleCheck(app, 300_000, 4);
        }

        // 重新绑定要等几秒，放到后台线程，并告诉系统这个广播还没处理完
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                boolean allowRebind = !boot
                        && SystemClock.elapsedRealtime() > REBIND_AFTER_UPTIME_MS;
                AccessibilityGuard.Result r =
                        AccessibilityGuard.ensureEnabled(app, shortAction(action), allowRebind);
                // 一切正常（ALREADY_ENABLED）时只写 logcat，不写守护日志，免得刷屏；
                // 有动作时 ensureEnabled 自己会写守护日志
                Log.i(FocusTrigger.TAG, "GuardReceiver " + action + " -> " + r);
            } catch (Exception e) {
                Log.e(FocusTrigger.TAG, "GuardReceiver " + action + " failed", e);
                GuardLog.add(app, shortAction(action) + "：检查出错 " + e.getClass().getSimpleName());
            } finally {
                pending.finish();
            }
        }, "guard").start();
    }

    static String shortAction(String action) {
        if (action == null) {
            return "null";
        }
        int dot = action.lastIndexOf('.');
        return dot >= 0 ? action.substring(dot + 1) : action;
    }
}
