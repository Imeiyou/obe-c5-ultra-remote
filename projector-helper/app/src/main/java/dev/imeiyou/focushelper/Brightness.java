package dev.imeiyou.focushelper;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/**
 * 投影仪光源亮度（系统菜单里 0–100 的"亮度"条）。
 *
 * 经分析确认：菜单项 MENU_DISPLAY_LCD_BRIGHTNESS 拖动时最终写入
 *   content://com.bkdisplay.system.provider/system/action_value 的 back_light_progress（整数）
 * 中间层只打日志，没有额外处理，所以直接写这个字段等同于拖动菜单里的亮度条。
 *
 * 为防止误操作导致黑屏，最低限制为 10。
 */
public final class Brightness {

    private static final String TAG = FocusTrigger.TAG;

    private static final Uri URI =
            Uri.parse("content://com.bkdisplay.system.provider/system/action_value");
    private static final String KEY = "back_light_progress";

    public static final int MIN = 10;
    public static final int MAX = 100;
    public static final int STEP = 5;

    /** 预设档位：Alt+1 … Alt+5 依次对应。手机端的按钮文字必须和这里一致。 */
    public static final int[] PRESETS = {30, 50, 70, 85, 100};

    private static final Handler WORKER;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Toast toast;

    static {
        HandlerThread t = new HandlerThread("brightness");
        t.start();
        WORKER = new Handler(t.getLooper());
    }

    private Brightness() {}

    /** 在当前亮度基础上加减。 */
    public static void step(Context context, int delta) {
        final Context app = context.getApplicationContext();
        WORKER.post(() -> {
            try {
                ContentResolver cr = app.getContentResolver();
                int current = read(cr);
                int target = clamp(current + delta);
                if (target != current) {
                    write(cr, target);
                }
                show(app, "亮度：" + target);
            } catch (Exception e) {
                Log.e(TAG, "brightness step failed", e);
                show(app, "亮度调节失败：" + e.getClass().getSimpleName());
            }
        });
    }

    /** 设为指定亮度。 */
    public static void set(Context context, int value) {
        final Context app = context.getApplicationContext();
        WORKER.post(() -> {
            try {
                int target = clamp(value);
                write(app.getContentResolver(), target);
                show(app, "亮度：" + target);
            } catch (Exception e) {
                Log.e(TAG, "brightness set failed", e);
                show(app, "亮度调节失败：" + e.getClass().getSimpleName());
            }
        });
    }

    private static int clamp(int v) {
        return Math.max(MIN, Math.min(MAX, v));
    }

    private static int read(ContentResolver cr) {
        try (Cursor c = cr.query(URI, new String[] {KEY}, null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                throw new IllegalStateException("query returned nothing");
            }
            int idx = c.getColumnIndex(KEY);
            if (idx < 0) {
                throw new IllegalStateException("column missing");
            }
            return c.getInt(idx);
        }
    }

    private static void write(ContentResolver cr, int value) {
        ContentValues cv = new ContentValues();
        cv.put(KEY, value);
        int n = cr.update(URI, cv, null, null);
        Log.i(TAG, "brightness -> " + value + " (" + n + ")");
    }

    /** 连续调节时只保留一个提示框，不让提示排队。 */
    private static void show(Context app, String text) {
        MAIN.post(() -> {
            if (toast != null) {
                toast.cancel();
            }
            toast = Toast.makeText(app, text, Toast.LENGTH_SHORT);
            toast.show();
        });
    }
}
