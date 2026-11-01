package dev.imeiyou.focushelper;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/**
 * 游戏模式与 MEMC 的切换，两者互斥。
 *
 * 两个值都存在投影仪系统的内容提供者里（分析系统设置应用确认）：
 *   content://com.bkdisplay.system.provider/system/action_value
 *     game_mode : 布尔
 *     memc      : 整数档位，0 = 关（菜单里有 关 / 低 / 中 / 高）
 *     pc_mode   : 布尔
 *
 * 互斥顺序与系统菜单的官方逻辑一致：
 *   开游戏模式：memc = 0，然后 game_mode = true
 *   开 MEMC   ：pc_mode = false，game_mode = false，然后 memc = 档位
 *
 * 另外做了一点方便切换的处理：开游戏模式前记下当时的 MEMC 档位，
 * 关游戏模式时恢复它；开 MEMC 时使用上一次记下的档位。
 */
public final class PictureSwitches {

    private static final String TAG = FocusTrigger.TAG;

    private static final Uri URI =
            Uri.parse("content://com.bkdisplay.system.provider/system/action_value");
    private static final String KEY_GAME = "game_mode";
    private static final String KEY_MEMC = "memc";
    private static final String KEY_PC = "pc_mode";

    // 从没记录过档位时使用的 MEMC 档位（1 = 低）
    private static final int DEFAULT_MEMC_LEVEL = 1;

    private static final String PREFS = "picture_switches";
    private static final String PREF_LAST_MEMC = "last_memc_level";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PictureSwitches() {}

    /** 切换游戏模式。在后台线程执行，结果用提示框显示。 */
    public static void toggleGameMode(Context context) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                ContentResolver cr = app.getContentResolver();
                boolean gameOn = readInt(cr, KEY_GAME) == 1;
                int memc = readInt(cr, KEY_MEMC);
                if (gameOn) {
                    write(cr, KEY_GAME, Boolean.FALSE);
                    int restore = lastMemc(app);
                    if (restore > 0) {
                        write(cr, KEY_MEMC, restore);
                    }
                    toast(app, restore > 0 ? "游戏模式：关（MEMC 恢复为 " + restore + "）" : "游戏模式：关");
                } else {
                    if (memc > 0) {
                        saveMemc(app, memc);
                    }
                    write(cr, KEY_MEMC, 0);
                    write(cr, KEY_GAME, Boolean.TRUE);
                    toast(app, "游戏模式：开（MEMC 已关）");
                }
                Log.i(TAG, "toggleGameMode done, was gameOn=" + gameOn + " memc=" + memc);
            } catch (Exception e) {
                Log.e(TAG, "toggleGameMode failed", e);
                toast(app, "游戏模式切换失败：" + e.getClass().getSimpleName());
            }
        }, "game-mode").start();
    }

    /** 切换 MEMC。在后台线程执行，结果用提示框显示。 */
    public static void toggleMemc(Context context) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                ContentResolver cr = app.getContentResolver();
                int memc = readInt(cr, KEY_MEMC);
                if (memc > 0) {
                    saveMemc(app, memc);
                    write(cr, KEY_MEMC, 0);
                    toast(app, "MEMC：关");
                } else {
                    int level = lastMemc(app);
                    if (level <= 0) {
                        level = DEFAULT_MEMC_LEVEL;
                    }
                    write(cr, KEY_PC, Boolean.FALSE);
                    write(cr, KEY_GAME, Boolean.FALSE);
                    write(cr, KEY_MEMC, level);
                    toast(app, "MEMC：开，档位 " + level + "（游戏模式已关）");
                }
                Log.i(TAG, "toggleMemc done, was memc=" + memc);
            } catch (Exception e) {
                Log.e(TAG, "toggleMemc failed", e);
                toast(app, "MEMC 切换失败：" + e.getClass().getSimpleName());
            }
        }, "memc").start();
    }

    private static final String[] MEMC_NAMES = {"关", "低", "中", "高"};

    /**
     * 直接设置 MEMC 档位：0 = 关，1/2/3 = 低/中/高。
     * 开启任一档位时按官方顺序先关 PC 模式和游戏模式。在后台线程执行，结果用提示框显示。
     */
    public static void setMemc(Context context, int level) {
        final Context app = context.getApplicationContext();
        final int target = Math.max(0, Math.min(3, level));
        new Thread(() -> {
            try {
                ContentResolver cr = app.getContentResolver();
                int memc = readInt(cr, KEY_MEMC);
                if (target == 0) {
                    if (memc > 0) {
                        saveMemc(app, memc);
                    }
                    write(cr, KEY_MEMC, 0);
                    toast(app, "MEMC：关");
                } else {
                    boolean gameOn = readInt(cr, KEY_GAME) == 1;
                    write(cr, KEY_PC, Boolean.FALSE);
                    write(cr, KEY_GAME, Boolean.FALSE);
                    write(cr, KEY_MEMC, target);
                    saveMemc(app, target);
                    toast(app, "MEMC：" + MEMC_NAMES[target] + "（" + target + " 档）"
                            + (gameOn ? "，游戏模式已关" : ""));
                }
                Log.i(TAG, "setMemc " + target + " done, was memc=" + memc);
            } catch (Exception e) {
                Log.e(TAG, "setMemc failed", e);
                toast(app, "MEMC 设置失败：" + e.getClass().getSimpleName());
            }
        }, "memc-set").start();
    }

    private static int readInt(ContentResolver cr, String key) {
        try (Cursor c = cr.query(URI, new String[] {key}, null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                throw new IllegalStateException("query " + key + " returned nothing");
            }
            int idx = c.getColumnIndex(key);
            if (idx < 0) {
                throw new IllegalStateException("column " + key + " missing");
            }
            return c.getInt(idx);
        }
    }

    private static void write(ContentResolver cr, String key, Boolean value) {
        ContentValues cv = new ContentValues();
        cv.put(key, value);
        int n = cr.update(URI, cv, null, null);
        Log.i(TAG, "update " + key + "=" + value + " -> " + n);
    }

    private static void write(ContentResolver cr, String key, int value) {
        ContentValues cv = new ContentValues();
        cv.put(key, value);
        int n = cr.update(URI, cv, null, null);
        Log.i(TAG, "update " + key + "=" + value + " -> " + n);
    }

    private static int lastMemc(Context app) {
        return prefs(app).getInt(PREF_LAST_MEMC, 0);
    }

    private static void saveMemc(Context app, int level) {
        prefs(app).edit().putInt(PREF_LAST_MEMC, level).apply();
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void toast(Context app, String text) {
        MAIN.post(() -> Toast.makeText(app, text, Toast.LENGTH_SHORT).show());
    }
}
