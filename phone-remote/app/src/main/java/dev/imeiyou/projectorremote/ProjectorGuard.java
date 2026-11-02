package dev.imeiyou.projectorremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 守护投影仪上的"对焦助手"：通过网络 ADB 检查它是否在运行，被系统停止后自动在后台复活。
 *
 * 检查时机：
 *   1. 手机通过蓝牙连上投影仪时（后台进行，不影响按键）；
 *   2. 发送需要对焦助手的按键前（对焦、梯形、MEMC、游戏模式、亮度等），
 *      最近 OK_TTL_MS 内确认过就跳过；否则先检查、必要时复活，再发送按键。
 *      复活要 1～2 秒，这次按键会相应延迟，但不会丢。
 *
 * 失败（连不上、未授权、未安装）后 FAIL_BACKOFF_MS 内不再在按键前检查，
 * 避免每次按键都卡住；这期间按键照常发送。
 *
 * 所有网络操作都在单独的线程里串行执行。
 */
final class ProjectorGuard {

    private static final String TAG = "ProjectorGuard";

    static final int ADB_PORT = 5555;

    private static final String PREFS = "projector_guard";
    private static final String PREF_HOST = "host";
    private static final String PREF_AUTO = "auto";
    private static final String PREF_KEY_PRIVATE = "adb_key_private";
    private static final String PREF_KEY_PUBLIC = "adb_key_public";

    /** 这么久内确认过对焦助手在运行，按键前就不再检查 */
    private static final long OK_TTL_MS = 5_000;
    /** 按键前的检查失败后，这么久内不再在按键前检查 */
    private static final long FAIL_BACKOFF_MS = 15_000;
    /** 按键前最多等检查多久；超过就不等了，按键照常发送 */
    private static final long KEY_WAIT_MS = 9_000;
    /** 复活成功后，再等一会儿让服务开始截获按键 */
    private static final long AFTER_REVIVE_MS = 500;

    private static final int CONNECT_TIMEOUT_MS = 1_500;
    private static final int IO_TIMEOUT_MS = 4_000;
    /** 授权时等用户在投影仪上点"允许"的时间 */
    private static final int CONFIRM_TIMEOUT_MS = 60_000;

    interface Listener {
        /** 对焦助手状态有变化（复活了、连不上等）时回调，在主线程 */
        void onGuardMessage(String message);
    }

    interface Callback {
        /** 手动操作的结果，在主线程 */
        void onResult(String message);
    }

    private static ProjectorGuard instance;

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "projector-guard"));
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile long lastOkAt = -OK_TTL_MS;
    private volatile long lastFailAt = -FAIL_BACKOFF_MS;
    private volatile String lastMessage = "尚未检查";

    /** 只在 executor 线程里访问 */
    private AdbKey key;

    static synchronized ProjectorGuard get(Context context) {
        if (instance == null) {
            instance = new ProjectorGuard(context);
        }
        return instance;
    }

    private ProjectorGuard(Context context) {
        this.app = context.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    String host() {
        return prefs.getString(PREF_HOST, "");
    }

    void setHost(String host) {
        String h = host == null ? "" : host.trim();
        if (!h.equals(host())) {
            prefs.edit().putString(PREF_HOST, h).apply();
            lastOkAt = -OK_TTL_MS;
            lastFailAt = -FAIL_BACKOFF_MS;
        }
    }

    boolean autoEnabled() {
        return prefs.getBoolean(PREF_AUTO, true);
    }

    void setAutoEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_AUTO, enabled).apply();
    }

    /** 设置了 IP 且开启了自动守护 */
    boolean isActive() {
        return autoEnabled() && !host().isEmpty();
    }

    String lastMessage() {
        return lastMessage;
    }

    void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ------------------------------------------------------------------
    // 检查入口
    // ------------------------------------------------------------------

    /**
     * 发送需要对焦助手的按键前调用（在蓝牙发送线程里）。
     * 需要检查时会阻塞，最长 KEY_WAIT_MS；不需要检查时立即返回。
     */
    void ensureBeforeKey() {
        if (!isActive()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastOkAt < OK_TTL_MS || now - lastFailAt < FAIL_BACKOFF_MS) {
            return;
        }
        Future<?> f = executor.submit(() -> runCheck(true, false));
        try {
            f.get(KEY_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Log.w(TAG, "check before key timed out, sending anyway");
        } catch (Exception e) {
            Log.w(TAG, "check before key failed", e);
        }
    }

    /** 后台检查一次，不阻塞（蓝牙连上时调用）。 */
    void checkAsync() {
        if (!isActive()) {
            return;
        }
        executor.submit(() -> runCheck(false, false));
    }

    /** 调试面板："立即检查"，不受缓存影响，结果回调给界面。 */
    void checkNow(Callback callback) {
        executor.submit(() -> {
            String msg = runCheck(false, true);
            main.post(() -> callback.onResult(msg));
        });
    }

    /**
     * 调试面板："授权"。连接投影仪，投影仪不认识这台手机时发送公钥，
     * 等用户在投影仪上点"允许"（最多 CONFIRM_TIMEOUT_MS），成功后检查对焦助手。
     *
     * @param progress 开始等待用户确认前回调一次，带上密钥指纹，方便和投影仪上显示的核对
     */
    void authorize(Callback progress, Callback done) {
        executor.submit(() -> {
            String msg;
            String host = host();
            if (host.isEmpty()) {
                msg = "请先填写投影仪的 IP";
            } else {
                try {
                    AdbKey k = key();
                    String fp = k.fingerprint();
                    main.post(() -> progress.onResult(
                            "正在连接 " + host + "…\n如果投影仪弹出\"允许 USB 调试吗？\"，"
                                    + "用遥控器勾选\"一律允许\"后确认。\n手机密钥指纹：" + fp));
                    try (AdbClient c = AdbClient.connect(host, ADB_PORT, k, keyName(),
                            CONNECT_TIMEOUT_MS, IO_TIMEOUT_MS, true, CONFIRM_TIMEOUT_MS)) {
                        boolean installed = HelperRepair.isInstalled(c);
                        msg = "授权成功。" + (installed
                                ? "已找到对焦助手。"
                                : "但投影仪上没有安装对焦助手。");
                    }
                    lastFailAt = -FAIL_BACKOFF_MS;
                    msg += "\n" + runCheck(false, true);
                } catch (AdbClient.NotAuthorizedException e) {
                    msg = "没有授权：" + e.getMessage();
                } catch (IOException e) {
                    msg = describeIo(host, e);
                } catch (GeneralSecurityException e) {
                    msg = "生成密钥失败：" + e.getMessage();
                }
            }
            String m = msg;
            main.post(() -> done.onResult(m));
        });
    }

    // ------------------------------------------------------------------
    // 实际检查（executor 线程）
    // ------------------------------------------------------------------

    /**
     * @param fromKey 是否由按键触发（失败时进入退避期，避免之后每次按键都卡住）
     * @param force   是否忽略缓存
     * @return 给界面看的结果描述
     */
    private String runCheck(boolean fromKey, boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastOkAt < OK_TTL_MS) {
            return "对焦助手正常运行";
        }
        String host = host();
        if (host.isEmpty()) {
            return "未设置投影仪 IP";
        }
        if (!onLocalNetwork()) {
            return fail(fromKey, "手机没有连接 WiFi，无法检查对焦助手");
        }
        try (AdbClient c = AdbClient.connect(host, ADB_PORT, key(), keyName(),
                CONNECT_TIMEOUT_MS, IO_TIMEOUT_MS, false, 0)) {
            HelperRepair.Outcome o = HelperRepair.ensure(c, true);
            Log.i(TAG, "check -> " + o);
            switch (o) {
                case OK:
                    lastOkAt = SystemClock.elapsedRealtime();
                    lastMessage = "对焦助手正常运行";
                    return lastMessage;
                case RESTORED:
                    SystemClock.sleep(AFTER_REVIVE_MS);
                    lastOkAt = SystemClock.elapsedRealtime();
                    return report("对焦助手被系统停止过，已在后台恢复");
                case REBOUND:
                    SystemClock.sleep(AFTER_REVIVE_MS);
                    lastOkAt = SystemClock.elapsedRealtime();
                    return report("对焦助手没有运行，已在后台重新启动");
                case BOOTING:
                    lastMessage = "投影仪刚开机，对焦助手可能还在启动";
                    return lastMessage;
                case NOT_INSTALLED:
                    return fail(fromKey, "投影仪上没有安装对焦助手");
                case NOT_RUNNING:
                default:
                    return fail(fromKey, "已尝试恢复，但对焦助手没有启动起来");
            }
        } catch (AdbClient.NotAuthorizedException e) {
            return fail(fromKey, "投影仪还没有授权这台手机：请在调试面板里点\"授权\"");
        } catch (IOException e) {
            return fail(fromKey, describeIo(host, e));
        } catch (GeneralSecurityException e) {
            return fail(fromKey, "密钥错误：" + e.getMessage());
        } catch (RuntimeException e) {
            Log.e(TAG, "check failed", e);
            return fail(fromKey, "检查出错：" + e.getMessage());
        }
    }

    private String fail(boolean fromKey, String message) {
        if (fromKey) {
            lastFailAt = SystemClock.elapsedRealtime();
        }
        return report(message);
    }

    private String report(String message) {
        boolean changed = !message.equals(lastMessage);
        lastMessage = message;
        Log.i(TAG, message);
        if (changed || message.contains("恢复") || message.contains("重新启动")) {
            for (Listener l : listeners) {
                main.post(() -> l.onGuardMessage(message));
            }
        }
        return message;
    }

    private static String describeIo(String host, IOException e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return "连不上投影仪（" + host + ":" + ADB_PORT + "）：" + detail
                + "\n确认手机和投影仪在同一个 WiFi，IP 没有变";
    }

    private boolean onLocalNetwork() {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return true;
        }
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private static String keyName() {
        return "projectorremote@" + Build.MODEL.replace(' ', '_');
    }

    private AdbKey key() throws GeneralSecurityException {
        if (key != null) {
            return key;
        }
        String priv = prefs.getString(PREF_KEY_PRIVATE, null);
        String pub = prefs.getString(PREF_KEY_PUBLIC, null);
        if (priv != null && pub != null) {
            try {
                key = AdbKey.fromEncoded(priv, pub);
                return key;
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                Log.w(TAG, "saved key unreadable, generating a new one", e);
            }
        }
        key = AdbKey.generate();
        prefs.edit()
                .putString(PREF_KEY_PRIVATE, key.privateEncoded())
                .putString(PREF_KEY_PUBLIC, key.publicEncoded())
                .apply();
        return key;
    }
}
