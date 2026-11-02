package dev.imeiyou.projectorremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * 震动提示。不同手机对震动的限制不一样（系统触感设置、厂商定制、勿扰模式），
 * 所以提供几种方式，在"调试"面板里逐个试，选能震的那一种，选择会被记住。
 */
final class Haptics {

    static final String[] NAMES = {
            "系统触感反馈",
            "震动马达（默认）",
            "震动马达（触摸类）",
            "震动马达（通知类）",
            "震动马达（闹钟类）",
    };

    // 默认用通知类：系统"触摸反馈"关掉时，触摸类和默认方式在部分手机上会被一起屏蔽
    static final int DEFAULT_METHOD = 3;

    private static final String PREFS = "haptics";
    private static final String KEY_METHOD = "method";

    private Haptics() {}

    static int method(Context c) {
        int m = prefs(c).getInt(KEY_METHOD, DEFAULT_METHOD);
        return (m >= 0 && m < NAMES.length) ? m : DEFAULT_METHOD;
    }

    static void setMethod(Context c, int m) {
        prefs(c).edit().putInt(KEY_METHOD, m).apply();
    }

    /** 用已选定的方式震一下（电源键分段提示使用）。 */
    static void feedback(View v) {
        play(v, method(v.getContext()), 150);
    }

    /** 用指定方式震动 ms 毫秒。返回 false 表示这台手机没有震动马达或调用出错。 */
    static boolean play(View v, int method, long ms) {
        try {
            if (method == 0) {
                return v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            Vibrator vib = vibrator(v.getContext());
            if (vib == null || !vib.hasVibrator()) {
                return false;
            }
            VibrationEffect effect = VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE);
            if (method == 1) {
                vib.vibrate(effect);
                return true;
            }
            if (Build.VERSION.SDK_INT >= 33) {
                int usage = method == 2 ? VibrationAttributes.USAGE_TOUCH
                        : method == 3 ? VibrationAttributes.USAGE_NOTIFICATION
                        : VibrationAttributes.USAGE_ALARM;
                vib.vibrate(effect, VibrationAttributes.createForUsage(usage));
            } else {
                int usage = method == 2 ? AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                        : method == 3 ? AudioAttributes.USAGE_NOTIFICATION
                        : AudioAttributes.USAGE_ALARM;
                vib.vibrate(effect, new AudioAttributes.Builder().setUsage(usage).build());
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 调试面板里显示的手机震动相关状态。 */
    static String diagnose(Context c) {
        StringBuilder sb = new StringBuilder();
        Vibrator vib = vibrator(c);
        sb.append("震动马达：").append(vib != null && vib.hasVibrator() ? "有" : "没有");
        if (vib != null && vib.hasVibrator()) {
            sb.append("（").append(vib.hasAmplitudeControl() ? "支持" : "不支持").append("调节强度）");
        }
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am != null) {
            int mode = am.getRingerMode();
            sb.append("\n铃声模式：").append(mode == AudioManager.RINGER_MODE_SILENT ? "静音"
                    : mode == AudioManager.RINGER_MODE_VIBRATE ? "震动" : "响铃");
        }
        int haptic = Settings.System.getInt(c.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, -1);
        sb.append("\n系统触感反馈：").append(haptic == 1 ? "开" : haptic == 0 ? "关" : "未知");
        sb.append("\nAndroid ").append(Build.VERSION.RELEASE).append("，").append(Build.MANUFACTURER)
                .append(" ").append(Build.MODEL);
        return sb.toString();
    }

    private static Vibrator vibrator(Context c) {
        VibratorManager vm = c.getSystemService(VibratorManager.class);
        return vm != null ? vm.getDefaultVibrator() : null;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
