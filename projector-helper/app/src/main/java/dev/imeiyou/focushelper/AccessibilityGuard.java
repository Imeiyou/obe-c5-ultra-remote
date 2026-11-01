package dev.imeiyou.focushelper;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

/**
 * 无障碍服务的自动恢复。
 *
 * 实测：投影仪开机时，系统自带的语音助手有时会把"已开启的无障碍服务"清单
 * 整个改写成只剩它自己，对焦助手因此被关掉。
 *
 * 这里在清单里缺少对焦助手时，把它追加回去（保留清单里原有的其他服务），
 * 并确保总开关 accessibility_enabled 为 1。
 *
 * 前提：已用 adb 授予一次 WRITE_SECURE_SETTINGS 权限（重启后仍然有效，卸载才会失效）：
 *   adb shell pm grant dev.imeiyou.focushelper android.permission.WRITE_SECURE_SETTINGS
 */
public final class AccessibilityGuard {

    private static final String TAG = FocusTrigger.TAG;

    static final String ACTION_GUARD = "dev.imeiyou.focushelper.GUARD";

    public enum Result { ALREADY_ENABLED, RESTORED, NO_PERMISSION, FAILED }

    private AccessibilityGuard() {}

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 清单里是否有对焦助手。 */
    public static boolean isListed(Context context) {
        String list = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return contains(list, myComponent(context));
    }

    /** 缺少时追加回去。 */
    public static synchronized Result ensureEnabled(Context context, String reason) {
        ComponentName me = myComponent(context);
        String list = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean listed = contains(list, me);
        boolean masterOn = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
        if (listed && masterOn) {
            return Result.ALREADY_ENABLED;
        }
        if (!hasPermission(context)) {
            Log.w(TAG, "guard(" + reason + "): service missing but WRITE_SECURE_SETTINGS not granted");
            return Result.NO_PERMISSION;
        }
        try {
            if (!listed) {
                String updated = TextUtils.isEmpty(list)
                        ? me.flattenToString()
                        : list + ":" + me.flattenToString();
                Settings.Secure.putString(context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
            }
            Settings.Secure.putInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "guard(" + reason + "): restored, list was: " + list);
            return Result.RESTORED;
        } catch (SecurityException e) {
            Log.e(TAG, "guard(" + reason + "): failed", e);
            return Result.FAILED;
        }
    }

    /** 在若干秒后再检查一次（用于开机后语音助手稍晚才改写清单的情况）。 */
    public static void scheduleCheck(Context context, long delayMs, int requestCode) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) {
            return;
        }
        Intent i = new Intent(context, GuardReceiver.class).setAction(ACTION_GUARD);
        PendingIntent pi = PendingIntent.getBroadcast(context, requestCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMs, pi);
    }

    static ComponentName myComponent(Context context) {
        return new ComponentName(context, FocusKeyService.class);
    }

    private static boolean contains(String list, ComponentName me) {
        if (TextUtils.isEmpty(list)) {
            return false;
        }
        String full = me.flattenToString();
        String shortName = me.flattenToShortString();
        for (String item : list.split(":")) {
            if (item.equalsIgnoreCase(full) || item.equalsIgnoreCase(shortName)) {
                return true;
            }
        }
        return false;
    }
}
