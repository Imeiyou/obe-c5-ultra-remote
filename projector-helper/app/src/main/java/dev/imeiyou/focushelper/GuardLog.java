package dev.imeiyou.focushelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 守护日志：把每次检查、恢复、服务连接/断开记到应用自己的文件里（最多保留 MAX_LINES 行）。
 *
 * logcat 重启后就没了，而"开机后为什么失效"恰恰要看重启前后的记录，
 * 所以单独存一份，测试界面上可以直接看到。
 *
 * 每行带开机序号（Settings.Global.BOOT_COUNT，每次真正重启加 1）和开机后的秒数，
 * 用来判断：某次开机时开机广播到底有没有送到对焦助手。
 */
public final class GuardLog {

    private static final String TAG = FocusTrigger.TAG;
    private static final String FILE = "guard.log";
    private static final int MAX_LINES = 80;

    private static final String PREFS = "guard_state";
    private static final String PREF_LAST_BOOT_SEEN = "last_boot_seen";

    private GuardLog() {}

    /** 系统记录的开机次数；读不到时返回 -1。 */
    public static int bootCount(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    /** 记下"这一次开机已经收到过开机广播"。 */
    public static void markBootSeen(Context context) {
        prefs(context).edit().putInt(PREF_LAST_BOOT_SEEN, bootCount(context)).apply();
    }

    /** 最近一次收到开机广播时的开机序号；从没收到过时返回 -1。 */
    public static int lastBootSeen(Context context) {
        return prefs(context).getInt(PREF_LAST_BOOT_SEEN, -1);
    }

    public static synchronized void add(Context context, String text) {
        Context app = context.getApplicationContext();
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                + "  开机#" + bootCount(app)
                + "  +" + (SystemClock.elapsedRealtime() / 1000) + "s  "
                + text;
        Log.i(TAG, "guardlog: " + line);
        try {
            List<String> lines = readAll(app);
            lines.add(line);
            int from = Math.max(0, lines.size() - MAX_LINES);
            File f = new File(app.getFilesDir(), FILE);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8)) {
                for (int i = from; i < lines.size(); i++) {
                    w.write(lines.get(i));
                    w.write('\n');
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "guardlog write failed", e);
        }
    }

    /** 最近 n 行，新的在前。 */
    public static synchronized List<String> recent(Context context, int n) {
        List<String> all;
        try {
            all = readAll(context.getApplicationContext());
        } catch (Exception e) {
            all = new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < n; i--) {
            out.add(all.get(i));
        }
        return out;
    }

    public static synchronized void clear(Context context) {
        File f = new File(context.getApplicationContext().getFilesDir(), FILE);
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "guardlog clear failed");
        }
    }

    private static List<String> readAll(Context app) throws Exception {
        List<String> lines = new ArrayList<>();
        File f = new File(app.getFilesDir(), FILE);
        if (!f.exists()) {
            return lines;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String s;
            while ((s = r.readLine()) != null) {
                if (!s.isEmpty()) {
                    lines.add(s);
                }
            }
        }
        return lines;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
