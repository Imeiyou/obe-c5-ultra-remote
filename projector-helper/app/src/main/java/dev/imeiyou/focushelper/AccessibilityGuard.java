package dev.imeiyou.focushelper;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 无障碍服务的自动恢复。
 *
 * 会遇到两种失效：
 *   1. 清单里没有对焦助手了（开机时被改写，或对焦助手被强行停止——
 *      Android 9 在强行停止一个应用时，会把它的无障碍服务从清单里删掉）。
 *      处理：追加回去，保留清单里原有的其他服务。
 *   2. 清单里有，但系统没有绑定（启动）服务。
 *      处理：先把自己从清单移除，等系统处理完，再加回去，让系统重新绑定。
 *      只在允许"重新绑定"的时机做（开机 30 秒以后的检查、修复按钮），
 *      避免开机过程中服务还在绑定时误判。
 *
 * 前提：已用 adb 授予一次 WRITE_SECURE_SETTINGS 权限（重启后仍然有效，卸载才会失效）：
 *   adb shell pm grant dev.imeiyou.focushelper android.permission.WRITE_SECURE_SETTINGS
 */
public final class AccessibilityGuard {

    private static final String TAG = FocusTrigger.TAG;

    static final String ACTION_GUARD = "dev.imeiyou.focushelper.GUARD";

    /** 移除后等多久再加回：给系统留出解绑、处理清单变化的时间。 */
    private static final long REBIND_GAP_MS = 1500;

    /** 加回后等多久检查是否绑定成功。 */
    private static final long REBIND_VERIFY_MS = 2500;

    public enum Result { ALREADY_ENABLED, RESTORED, REBOUND, REBIND_FAILED, NO_PERMISSION, FAILED }

    private AccessibilityGuard() {}

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 清单里是否有对焦助手。 */
    public static boolean isListed(Context context) {
        return contains(readList(context), myComponent(context));
    }

    /**
     * 系统是否真的绑定了服务。
     * getEnabledAccessibilityServiceList 返回的是系统当前已绑定的服务，
     * 不是设置清单，所以可以从任何进程判断，不依赖静态变量。
     */
    public static boolean isBound(Context context) {
        if (FocusKeyService.running) {
            return true;
        }
        AccessibilityManager am = context.getSystemService(AccessibilityManager.class);
        if (am == null) {
            return false;
        }
        String full = myComponent(context).flattenToString();
        String shortName = myComponent(context).flattenToShortString();
        List<AccessibilityServiceInfo> list =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (list == null) {
            return false;
        }
        for (AccessibilityServiceInfo info : list) {
            String id = info.getId();
            if (full.equalsIgnoreCase(id) || shortName.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查并恢复。
     *
     * @param allowRebind 清单里有但服务没在运行时，是否"移除再加回"。
     *                    为 true 时可能阻塞约 4 秒，不能在主线程调用。
     */
    public static synchronized Result ensureEnabled(Context context, String reason, boolean allowRebind) {
        if (allowRebind && Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("ensureEnabled(allowRebind) must not run on main thread");
        }
        ComponentName me = myComponent(context);
        String list = readList(context);
        boolean listed = contains(list, me);
        boolean masterOn = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;

        if (listed && masterOn) {
            if (!allowRebind || isBound(context)) {
                return Result.ALREADY_ENABLED;
            }
            if (!hasPermission(context)) {
                GuardLog.add(context, reason + "：清单里有但没运行，未授权，无法重新绑定");
                return Result.NO_PERMISSION;
            }
            return rebind(context, reason, list, me);
        }

        if (!hasPermission(context)) {
            Log.w(TAG, "guard(" + reason + "): service missing but WRITE_SECURE_SETTINGS not granted");
            GuardLog.add(context, reason + "：服务不在清单里，未授权，无法恢复");
            return Result.NO_PERMISSION;
        }
        try {
            if (!listed) {
                String updated = TextUtils.isEmpty(list)
                        ? me.flattenToString()
                        : list + ":" + me.flattenToString();
                putList(context, updated);
            }
            Settings.Secure.putInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "guard(" + reason + "): restored, list was: " + list);
            GuardLog.add(context, reason + "：已恢复（原清单：" + describeList(list)
                    + "，总开关：" + (masterOn ? "开" : "关") + "）");
            return Result.RESTORED;
        } catch (SecurityException e) {
            Log.e(TAG, "guard(" + reason + "): failed", e);
            GuardLog.add(context, reason + "：恢复失败 " + e.getClass().getSimpleName());
            return Result.FAILED;
        }
    }

    /** 清单里有但没绑定：移除自己 → 等待 → 加回，然后确认是否绑定成功。 */
    private static Result rebind(Context context, String reason, String list, ComponentName me) {
        try {
            String without = removeFrom(list, me);
            putList(context, without);
            SystemClock.sleep(REBIND_GAP_MS);

            // 等待期间别的程序可能又改了清单：以最新内容为准，只把自己加回去
            String latest = readList(context);
            if (!contains(latest, me)) {
                putList(context, TextUtils.isEmpty(latest)
                        ? me.flattenToString()
                        : latest + ":" + me.flattenToString());
            }
            Settings.Secure.putInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            SystemClock.sleep(REBIND_VERIFY_MS);

            boolean ok = isBound(context);
            Log.i(TAG, "guard(" + reason + "): rebind " + (ok ? "ok" : "still not bound"));
            GuardLog.add(context, reason + "：清单里有但没运行，已重新绑定 → "
                    + (ok ? "成功" : "仍未运行"));
            return ok ? Result.REBOUND : Result.REBIND_FAILED;
        } catch (SecurityException e) {
            Log.e(TAG, "guard(" + reason + "): rebind failed", e);
            // 尽量保证不会把自己留在"已移除"的状态
            try {
                String latest = readList(context);
                if (!contains(latest, me)) {
                    putList(context, TextUtils.isEmpty(latest)
                            ? me.flattenToString()
                            : latest + ":" + me.flattenToString());
                }
            } catch (SecurityException ignored) {
                // 没有权限就什么也做不了
            }
            GuardLog.add(context, reason + "：重新绑定失败 " + e.getClass().getSimpleName());
            return Result.FAILED;
        }
    }

    /** 在若干毫秒后再检查一次（用于开机后其他程序稍晚才改写清单的情况）。 */
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

    private static String readList(Context context) {
        return Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    }

    private static void putList(Context context, String value) {
        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value == null ? "" : value);
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

    private static String removeFrom(String list, ComponentName me) {
        if (TextUtils.isEmpty(list)) {
            return "";
        }
        String full = me.flattenToString();
        String shortName = me.flattenToShortString();
        List<String> keep = new ArrayList<>();
        for (String item : list.split(":")) {
            if (item.isEmpty() || item.equalsIgnoreCase(full) || item.equalsIgnoreCase(shortName)) {
                continue;
            }
            keep.add(item);
        }
        return TextUtils.join(":", keep);
    }

    /** 日志里只记包名，清单原文太长。 */
    static String describeList(String list) {
        if (TextUtils.isEmpty(list)) {
            return "空";
        }
        List<String> pkgs = new ArrayList<>();
        for (String item : list.split(":")) {
            int slash = item.indexOf('/');
            pkgs.add(slash > 0 ? item.substring(0, slash) : item);
        }
        return TextUtils.join(", ", pkgs);
    }
}
