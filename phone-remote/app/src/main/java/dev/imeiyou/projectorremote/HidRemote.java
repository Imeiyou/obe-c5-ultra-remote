package dev.imeiyou.projectorremote;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 以蓝牙 HID 设备（键盘 + 多媒体键 + 鼠标）的身份连接投影仪，并发送按键。
 *
 * 流程：取得 HID_DEVICE 代理 → 注册 HID 应用 → 连接目标投影仪 → 发送报告。
 * 回调在主线程执行；发送放在单独的工作线程里，避免阻塞界面，并保证顺序。
 *
 * 所有蓝牙调用都需要 BLUETOOTH_CONNECT 权限，由 MainActivity 在调用前申请。
 */
@SuppressLint("MissingPermission")
public class HidRemote implements RemoteSender {

    private static final String TAG = "ProjectorRemote";

    private static final String SDP_NAME = "C5 Ultra 遥控";
    private static final String SDP_DESCRIPTION = "Keyboard and mouse remote for OBE C5 Ultra";
    private static final String SDP_PROVIDER = "dev.imeiyou";

    private static final String PREFS = "hid";
    private static final String PREF_TARGET = "target_address";

    // 单击类按键：按下与松开之间的间隔
    private static final long TAP_GAP_MS = 40;

    /**
     * 唤醒链路后等多久再发真正的按键。
     * 蓝牙空闲一段时间后会进入省电状态，此时紧接着发出的"按下"和"松开"可能被合并成一次
     * 几乎没有时长的按键，投影仪可能忽略它。先补发一次当前键盘状态（不改变任何按键）把链路叫醒。
     */
    private static final long WAKE_LEAD_MS = 100;

    /**
     * 鼠标移动报告的间隔：触摸移动先累计，再按这个间隔均匀发送。
     * 20ms = 每秒 50 个报告。光标仍不连贯可以试 25～30；觉得跟手慢可以试 16。
     */
    private static final long MOUSE_INTERVAL_MS = 20;

    /** 距上次发送鼠标报告超过这个时间，wakeLink 才真正发送唤醒报告 */
    private static final long WAKE_IDLE_MS = 150;

    /**
     * 平滑系数：每次只发送累计量的这个比例，剩下的留到下一次，把突发的大位移摊开。
     * 1.0 = 不平滑（有多少发多少）；越小越平滑，但停手后光标会多滑一小段。建议 0.5～1.0。
     */
    private static final float MOUSE_SMOOTHING = 0.6f;

    public enum State { OFF, STARTING, READY, CONNECTING, CONNECTED, ERROR }

    public interface StatusListener {
        void onStatus(State state, String detail);
    }

    private static HidRemote instance;

    private final Context context;
    // 界面和后台服务都会监听状态
    private final List<StatusListener> statusListeners = new CopyOnWriteArrayList<>();
    private volatile Listener sentListener;
    private final SharedPreferences prefs;
    private final BluetoothAdapter adapter;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread workerThread = new HandlerThread("hid-sender");
    private final Handler worker;

    private volatile BluetoothHidDevice hid;
    private volatile boolean registered;
    private volatile BluetoothDevice host;      // 当前已连接的投影仪
    private boolean started;

    // 以下状态只在工作线程里读写
    // 当前按下的键：用法码 → 它需要的修饰键
    private final Map<Integer, Integer> pressedKeys = new LinkedHashMap<>();
    // 松开带修饰键的按键时，先只松开按键、保留修饰键发一次，再松开修饰键
    private int lingerModifiers;
    private int consumerUsage;
    private int mouseButtons;

    private volatile State state = State.OFF;
    private volatile String lastDetail = "未连接";

    /**
     * 整个应用共用一个实例：界面重建、后台服务都使用同一个连接，不会重复注册 HID 应用。
     */
    public static synchronized HidRemote get(Context context) {
        if (instance == null) {
            instance = new HidRemote(context);
        }
        return instance;
    }

    /** 添加状态监听，并立即回调一次当前状态。 */
    public void addStatusListener(StatusListener l) {
        if (l == null || statusListeners.contains(l)) {
            return;
        }
        statusListeners.add(l);
        State s = state;
        String d = lastDetail;
        main.post(() -> l.onStatus(s, d));
    }

    public void removeStatusListener(StatusListener l) {
        statusListeners.remove(l);
    }

    public void setSentListener(Listener l) {
        this.sentListener = l;
    }

    private HidRemote(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        BluetoothManager bm = this.context.getSystemService(BluetoothManager.class);
        this.adapter = bm == null ? null : bm.getAdapter();
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    // ------------------------------------------------------------------
    // 连接管理
    // ------------------------------------------------------------------

    public boolean isBluetoothAvailable() {
        return adapter != null;
    }

    public boolean isBluetoothEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    public State getState() {
        return state;
    }

    public String getDetail() {
        return lastDetail;
    }

    public boolean isStarted() {
        return started;
    }

    public boolean hasTarget() {
        return prefs.getString(PREF_TARGET, null) != null;
    }

    public String targetName() {
        BluetoothDevice d = savedTarget();
        return d == null ? null : nameOf(d);
    }

    public Set<BluetoothDevice> bondedDevices() {
        return adapter == null ? java.util.Collections.emptySet() : adapter.getBondedDevices();
    }

    /** 取得 HID 代理并注册；注册成功后自动连接已保存的投影仪。可重复调用。 */
    public void start() {
        if (adapter == null) {
            setState(State.ERROR, "这台手机不支持蓝牙");
            return;
        }
        if (!adapter.isEnabled()) {
            setState(State.ERROR, "蓝牙未打开");
            return;
        }
        if (started) {
            if (registered) {
                connectSavedTarget();
            }
            return;
        }
        started = true;
        setState(State.STARTING, "正在启动蓝牙遥控…");
        boolean ok = adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE);
        if (!ok) {
            started = false;
            setState(State.ERROR, "无法使用蓝牙 HID 功能");
        }
    }

    /**
     * 选择要连接的投影仪。已注册时立即连接；
     * 未注册时只保存，等服务启动、注册完成后自动连接。
     */
    public void setTarget(BluetoothDevice device) {
        prefs.edit().putString(PREF_TARGET, device.getAddress()).apply();
        BluetoothHidDevice h = hid;
        if (h != null && registered) {
            BluetoothDevice current = host;
            if (current != null && !current.equals(device)) {
                h.disconnect(current);
            }
            connect(device);
        }
    }

    /** 断开当前连接（保留注册，方便之后重新连接）。 */
    public void disconnect() {
        BluetoothHidDevice h = hid;
        BluetoothDevice d = host;
        if (h != null && d != null) {
            // 先在工作线程里把所有按键松开，再断开，保证顺序
            releaseAll();
            worker.post(() -> h.disconnect(d));
        }
    }

    /** 完全停止：断开、注销 HID 应用、释放代理。之后可以再次 start()。 */
    public void stop() {
        BluetoothHidDevice h = hid;
        if (h != null) {
            BluetoothDevice d = host;
            if (d != null) {
                h.disconnect(d);
            }
            if (registered) {
                h.unregisterApp();
            }
            if (adapter != null) {
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, h);
            }
        }
        hid = null;
        host = null;
        registered = false;
        started = false;
        worker.post(() -> {
            resetMotion();
            pressedKeys.clear();
            consumerUsage = 0;
            mouseButtons = 0;
        });
        setState(State.OFF, "未连接");
    }

    private void register() {
        BluetoothHidDevice h = hid;
        if (h == null) {
            return;
        }
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                SDP_NAME, SDP_DESCRIPTION, SDP_PROVIDER,
                BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptor.DESCRIPTOR);
        boolean ok = h.registerApp(sdp, null, null, context.getMainExecutor(), hidCallback);
        if (!ok) {
            setState(State.ERROR, "注册失败：可能有其他蓝牙键盘/鼠标应用正在占用");
        }
    }

    private void connectSavedTarget() {
        BluetoothDevice target = savedTarget();
        if (target == null) {
            setState(State.READY, "请选择投影仪");
            return;
        }
        if (host != null && host.equals(target)) {
            setState(State.CONNECTED, nameOf(target));
            return;
        }
        connect(target);
    }

    private void connect(BluetoothDevice device) {
        BluetoothHidDevice h = hid;
        if (h == null) {
            return;
        }
        setState(State.CONNECTING, "正在连接 " + nameOf(device) + "…");
        if (!h.connect(device)) {
            setState(State.READY, "连接请求失败，请确认投影仪已开机并已与手机配对");
        }
    }

    private BluetoothDevice savedTarget() {
        String addr = prefs.getString(PREF_TARGET, null);
        if (addr == null || adapter == null || !BluetoothAdapter.checkBluetoothAddress(addr)) {
            return null;
        }
        return adapter.getRemoteDevice(addr);
    }

    private static String nameOf(BluetoothDevice d) {
        String n = d.getName();
        return n == null || n.isEmpty() ? d.getAddress() : n;
    }

    private final BluetoothProfile.ServiceListener serviceListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HID_DEVICE) {
                return;
            }
            hid = (BluetoothHidDevice) proxy;
            register();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.HID_DEVICE) {
                return;
            }
            hid = null;
            host = null;
            registered = false;
            started = false;
            setState(State.OFF, "蓝牙服务已断开");
        }
    };

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
            registered = isRegistered;
            Log.i(TAG, "onAppStatusChanged registered=" + isRegistered + " plugged=" + pluggedDevice);
            if (!isRegistered) {
                host = null;
                if (started) {
                    setState(State.ERROR, "蓝牙遥控未注册：可能有其他蓝牙键盘/鼠标应用正在占用");
                }
                return;
            }
            connectSavedTarget();
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int newState) {
            Log.i(TAG, "onConnectionStateChanged " + device + " state=" + newState);
            switch (newState) {
                case BluetoothProfile.STATE_CONNECTED:
                    host = device;
                    // 投影仪主动连上来时（例如刚配对完），也记住它
                    prefs.edit().putString(PREF_TARGET, device.getAddress()).apply();
                    setState(State.CONNECTED, nameOf(device));
                    // 投影仪刚开机或刚连上：在后台确认一次对焦助手在运行
                    ProjectorGuard.get(context).checkAsync();
                    break;
                case BluetoothProfile.STATE_CONNECTING:
                    setState(State.CONNECTING, "正在连接 " + nameOf(device) + "…");
                    break;
                case BluetoothProfile.STATE_DISCONNECTED:
                    if (host == null || host.equals(device)) {
                        host = null;
                        worker.post(() -> {
                            resetMotion();
                            pressedKeys.clear();
                            consumerUsage = 0;
                            mouseButtons = 0;
                        });
                        setState(State.READY, "等待投影仪连接…");
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            BluetoothHidDevice h = hid;
            if (h == null) {
                return;
            }
            byte[] data = emptyReport(id);
            if (data == null) {
                h.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
            } else {
                h.replyReport(device, type, id, data);
            }
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            BluetoothHidDevice h = hid;
            if (h != null) {
                h.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
            }
        }

        @Override
        public void onVirtualCableUnplug(BluetoothDevice device) {
            Log.i(TAG, "onVirtualCableUnplug " + device);
            host = null;
            setState(State.READY, "投影仪已解除连接");
        }
    };

    private static byte[] emptyReport(byte id) {
        switch (id) {
            case HidDescriptor.ID_KEYBOARD: return new byte[HidDescriptor.KEYBOARD_REPORT_SIZE];
            case HidDescriptor.ID_CONSUMER: return new byte[HidDescriptor.CONSUMER_REPORT_SIZE];
            case HidDescriptor.ID_MOUSE:    return new byte[HidDescriptor.MOUSE_REPORT_SIZE];
            default:                        return null;
        }
    }

    private void setState(State s, String detail) {
        state = s;
        lastDetail = detail;
        Log.i(TAG, "state " + s + " " + detail);
        for (StatusListener l : statusListeners) {
            main.post(() -> l.onStatus(s, detail));
        }
    }

    // ------------------------------------------------------------------
    // 发送（RemoteSender）
    // ------------------------------------------------------------------

    @Override
    public boolean isConnected() {
        return hid != null && host != null;
    }

    @Override
    public void keyDown(RemoteKey key) {
        worker.post(() -> {
            guardIfNeeded(key);
            press(key);
        });
        notifySent("按下 " + key.label);
    }

    @Override
    public void keyUp(RemoteKey key) {
        worker.post(() -> release(key));
    }

    @Override
    public void tap(RemoteKey key) {
        worker.post(() -> {
            guardIfNeeded(key);
            press(key);
            // 松开要排在按下之后：检查可能耗时，所以在这里才安排松开
            worker.postDelayed(() -> release(key), TAP_GAP_MS);
        });
        notifySent(key.label);
    }

    /**
     * 需要对焦助手的按键，发送前先确认它在运行（被系统停止时会在后台复活，耗时 1～2 秒）。
     * 在发送线程里执行：检查期间后面的按键排队等待，顺序不会乱。
     */
    private void guardIfNeeded(RemoteKey key) {
        if (key.needsHelper() && isConnected()) {
            ProjectorGuard.get(context).ensureBeforeKey();
        }
    }

    @Override
    public void tapHeld(RemoteKey key, long holdMs) {
        worker.post(this::sendKeyboard);
        worker.postDelayed(() -> press(key), WAKE_LEAD_MS);
        worker.postDelayed(() -> release(key), WAKE_LEAD_MS + holdMs);
        notifySent(key.label + "（短按）");
    }

    @Override
    public void pressWithWake(RemoteKey key) {
        worker.post(this::sendKeyboard);
        worker.postDelayed(() -> press(key), WAKE_LEAD_MS);
        notifySent(key.label + "（长按）");
    }

    @Override
    public void keyUpAfter(RemoteKey key, long delayMs) {
        worker.postDelayed(() -> release(key), Math.max(0, delayMs));
    }

    // ---- 鼠标移动合并（只在 worker 线程里访问） ----
    private int pendingDx, pendingDy;
    private boolean motionScheduled;
    private long lastMotionAt;

    private final Runnable flushMotion = new Runnable() {
        @Override
        public void run() {
            motionScheduled = false;
            if (pendingDx == 0 && pendingDy == 0) {
                return;
            }
            int sx = smoothPart(pendingDx);
            int sy = smoothPart(pendingDy);
            pendingDx -= sx;
            pendingDy -= sy;
            sendMouse(sx, sy, 0);
            lastMotionAt = SystemClock.uptimeMillis();
            if (pendingDx != 0 || pendingDy != 0) {
                motionScheduled = true;
                worker.postDelayed(this, MOUSE_INTERVAL_MS);
            }
        }
    };

    /** 立刻发出所有未发送的移动量（点击、滚动之前调用，保证点在正确的位置）。 */
    private void flushMotionNow() {
        worker.removeCallbacks(flushMotion);
        motionScheduled = false;
        while (pendingDx != 0 || pendingDy != 0) {
            int sx = clamp127(pendingDx);
            int sy = clamp127(pendingDy);
            pendingDx -= sx;
            pendingDy -= sy;
            sendMouse(sx, sy, 0);
        }
        lastMotionAt = SystemClock.uptimeMillis();
    }

    /** 丢弃未发送的移动量（断开、停止时调用，只在 worker 线程里调用）。 */
    private void resetMotion() {
        worker.removeCallbacks(flushMotion);
        motionScheduled = false;
        pendingDx = pendingDy = 0;
    }

    /** 本次要发送的量：累计量乘以平滑系数，至少 1 个计数，最多 ±127。 */
    private static int smoothPart(int pending) {
        if (pending == 0) {
            return 0;
        }
        int part = Math.round(pending * MOUSE_SMOOTHING);
        if (part == 0) {
            part = pending > 0 ? 1 : -1;
        }
        return clamp127(part);
    }

    private static int clamp127(int v) {
        return Math.max(-127, Math.min(127, v));
    }

    @Override
    public void wakeLink() {
        worker.post(() -> {
            // 有移动正在发送时链路本来就是醒着的，不用再发
            if (!motionScheduled && SystemClock.uptimeMillis() - lastMotionAt > WAKE_IDLE_MS) {
                sendMouse(0, 0, 0);
            }
        });
    }

    @Override
    public void mouseMove(int dx, int dy) {
        worker.post(() -> {
            pendingDx += dx;
            pendingDy += dy;
            if (!motionScheduled) {
                motionScheduled = true;
                long wait = lastMotionAt + MOUSE_INTERVAL_MS - SystemClock.uptimeMillis();
                worker.postDelayed(flushMotion, Math.max(0, wait));
            }
        });
    }

    @Override
    public void mouseClick() {
        worker.post(() -> {
            flushMotionNow();
            mouseButtons = 1;
            sendMouse(0, 0, 0);
        });
        worker.postDelayed(() -> {
            mouseButtons = 0;
            sendMouse(0, 0, 0);
        }, TAP_GAP_MS);
        notifySent("鼠标点击");
    }

    @Override
    public void mouseScroll(int notches) {
        worker.post(() -> {
            flushMotionNow();
            sendMouse(0, 0, notches);
        });
    }

    private void press(RemoteKey key) {
        if (key.page == RemoteKey.Page.KEYBOARD) {
            if (pressedKeys.size() < 6) {
                pressedKeys.put(key.usage, key.modifiers);
            }
            sendKeyboard();
        } else {
            consumerUsage = key.usage;
            sendConsumer();
        }
    }

    private void release(RemoteKey key) {
        if (key.page == RemoteKey.Page.KEYBOARD) {
            pressedKeys.remove(key.usage);
            if (key.modifiers != 0) {
                // 先松开按键（修饰键仍按住），保证对方收到的是"Alt 按住时松开数字键"
                lingerModifiers = key.modifiers;
                sendKeyboard();
                lingerModifiers = 0;
            }
            sendKeyboard();
        } else if (consumerUsage == key.usage) {
            consumerUsage = 0;
            sendConsumer();
        }
    }

    /** 断开前把所有按键松开，避免投影仪那边残留"按住"状态。 */
    private void releaseAll() {
        worker.post(() -> {
            resetMotion();
            pressedKeys.clear();
            consumerUsage = 0;
            mouseButtons = 0;
            sendKeyboard();
            sendConsumer();
            sendMouse(0, 0, 0);
        });
    }

    private void sendKeyboard() {
        byte[] r = new byte[HidDescriptor.KEYBOARD_REPORT_SIZE];
        int mods = lingerModifiers;
        int i = 2;
        for (Map.Entry<Integer, Integer> e : pressedKeys.entrySet()) {
            r[i++] = (byte) (int) e.getKey();
            mods |= e.getValue();
        }
        r[0] = (byte) mods;
        send(HidDescriptor.ID_KEYBOARD, r);
    }

    private void sendConsumer() {
        byte[] r = new byte[HidDescriptor.CONSUMER_REPORT_SIZE];
        r[0] = (byte) (consumerUsage & 0xFF);
        r[1] = (byte) ((consumerUsage >> 8) & 0xFF);
        send(HidDescriptor.ID_CONSUMER, r);
    }

    private void sendMouse(int dx, int dy, int wheel) {
        byte[] r = new byte[HidDescriptor.MOUSE_REPORT_SIZE];
        r[0] = (byte) mouseButtons;
        r[1] = (byte) dx;
        r[2] = (byte) dy;
        r[3] = (byte) wheel;
        send(HidDescriptor.ID_MOUSE, r);
    }

    private void send(byte id, byte[] data) {
        BluetoothHidDevice h = hid;
        BluetoothDevice d = host;
        if (h == null || d == null) {
            return;
        }
        try {
            if (!h.sendReport(d, id, data)) {
                Log.w(TAG, "sendReport failed id=" + id);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "sendReport denied", e);
        }
    }

    private void notifySent(String text) {
        boolean connected = isConnected();
        Listener l = sentListener;
        if (l != null) {
            main.post(() -> l.onSent(connected ? "已发送：" + text : "未连接，未发送：" + text));
        }
    }
}
