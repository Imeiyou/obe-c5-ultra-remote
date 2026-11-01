package dev.imeiyou.focushelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 在以下时机检查并恢复无障碍服务：
 *   开机完成：立即检查一次，并在 30 秒、2 分钟后各再检查一次
 *            （语音助手可能在开机后稍晚才改写清单）
 *   应用更新（例如用 Android Studio 重新安装）：立即检查
 *   定时检查（ACTION_GUARD）：检查一次
 */
public class GuardReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        AccessibilityGuard.Result r = AccessibilityGuard.ensureEnabled(context, String.valueOf(action));
        Log.i(FocusTrigger.TAG, "GuardReceiver " + action + " -> " + r);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            AccessibilityGuard.scheduleCheck(context, 30_000, 1);
            AccessibilityGuard.scheduleCheck(context, 120_000, 2);
        }
    }
}
