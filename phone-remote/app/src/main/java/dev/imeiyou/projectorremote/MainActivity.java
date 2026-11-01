package dev.imeiyou.projectorremote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面：按键页 + 触控板页。
 *
 * 连接按钮：
 *   短按：未连接时连接上次的投影仪（第一次会让你选择）；连接中或已连接时断开并停止后台服务
 *   长按：重新选择投影仪，或让手机可被发现（首次配对用）
 *
 * 连接由 RemoteService 在后台保持，关闭界面不会断开；要断开请点"断开"或通知栏里的"断开"。
 */
public class MainActivity extends Activity
        implements RemoteSender.Listener, HidRemote.StatusListener {

    private static final int REQ_CONNECT = 1;
    private static final int REQ_ADVERTISE = 2;
    private static final int REQ_ENABLE_BT = 3;

    // 权限拿到之后要继续做的事
    private static final int NEXT_CONNECT = 1;
    private static final int NEXT_PICK = 2;
    private int pendingAction;

    private HidRemote remote;
    private Dialog brightnessPanel;
    private Dialog memcPanel;

    private TextView statusText;
    private TextView lastAction;
    private TextView connectButton;
    private View statusDot;
    private TextView tabKeys;
    private TextView tabTouchpad;
    private View pageKeys;
    private View pageTouchpad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        remote = HidRemote.get(this);

        statusText = findViewById(R.id.statusText);
        lastAction = findViewById(R.id.lastAction);
        connectButton = findViewById(R.id.connectButton);
        statusDot = findViewById(R.id.statusDot);
        tabKeys = findViewById(R.id.tabKeys);
        tabTouchpad = findViewById(R.id.tabTouchpad);
        pageKeys = findViewById(R.id.pageKeys);
        pageTouchpad = findViewById(R.id.pageTouchpad);

        bindKeys();

        tabKeys.setOnClickListener(v -> showPage(false));
        tabTouchpad.setOnClickListener(v -> showPage(true));
        showPage(false);

        connectButton.setOnClickListener(v -> onConnectClicked());
        connectButton.setOnLongClickListener(v -> {
            withConnectPermission(NEXT_PICK);
            return true;
        });

        remote.addStatusListener(this);
        remote.setSentListener(this);

        // 已经选过投影仪、并且有权限时，打开应用就自动连接
        if (!remote.isStarted() && remote.hasTarget()
                && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
                && remote.isBluetoothEnabled()) {
            RemoteService.start(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 只解除界面的监听；连接由后台服务继续保持
        if (brightnessPanel != null) {
            brightnessPanel.dismiss();
        }
        if (memcPanel != null) {
            memcPanel.dismiss();
        }
        remote.removeStatusListener(this);
        remote.setSentListener(null);
    }

    private void bindKeys() {
        bind(R.id.keyPower, RemoteKey.POWER);
        bind(R.id.keySettings, RemoteKey.SETTINGS);
        bind(R.id.keyMenu, RemoteKey.MENU);
        bind(R.id.keyBack, RemoteKey.BACK);
        bind(R.id.keyHome, RemoteKey.HOME);
        bind(R.id.keyVolDown, RemoteKey.VOLUME_DOWN);
        bind(R.id.keyMute, RemoteKey.MUTE);
        bind(R.id.keyVolUp, RemoteKey.VOLUME_UP);

        bind(R.id.fnDeepFocus, RemoteKey.DEEP_FOCUS);
        bind(R.id.fnSmart, RemoteKey.SMART_CORRECTION);
        bind(R.id.fnKeystone, RemoteKey.MANUAL_KEYSTONE);
        bind(R.id.fnSize, RemoteKey.SCREEN_SIZE);
        bind(R.id.fnDisplace, RemoteKey.SCREEN_DISPLACE);
        bind(R.id.fnGame, RemoteKey.GAME_MODE);
        findViewById(R.id.fnMemc).setOnClickListener(v -> showMemcPanel());
        findViewById(R.id.fnBrightness).setOnClickListener(v -> showBrightnessPanel());

        bind(R.id.tpBack, RemoteKey.BACK);
        bind(R.id.tpHome, RemoteKey.HOME);
        bind(R.id.tpOk, RemoteKey.OK);

        DpadView dpad = findViewById(R.id.dpad);
        dpad.setSender(remote);
        TouchpadView touchpad = findViewById(R.id.touchpad);
        touchpad.setSender(remote);
    }

    private void bind(int viewId, RemoteKey key) {
        KeyBinder.bind(findViewById(viewId), key, remote);
    }

    /** 亮度浮动面板：从屏幕底部弹出，点"关闭"或面板外区域收起。 */
    private void showBrightnessPanel() {
        if (brightnessPanel == null) {
            Dialog d = new Dialog(this);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            d.setContentView(R.layout.dialog_brightness);
            d.setCanceledOnTouchOutside(true);

            KeyBinder.bind(d.findViewById(R.id.brightnessUp), RemoteKey.BRIGHTNESS_UP, remote);
            KeyBinder.bind(d.findViewById(R.id.brightnessDown), RemoteKey.BRIGHTNESS_DOWN, remote);
            KeyBinder.bind(d.findViewById(R.id.brightness30), RemoteKey.BRIGHTNESS_30, remote);
            KeyBinder.bind(d.findViewById(R.id.brightness50), RemoteKey.BRIGHTNESS_50, remote);
            KeyBinder.bind(d.findViewById(R.id.brightness70), RemoteKey.BRIGHTNESS_70, remote);
            KeyBinder.bind(d.findViewById(R.id.brightness85), RemoteKey.BRIGHTNESS_85, remote);
            KeyBinder.bind(d.findViewById(R.id.brightness100), RemoteKey.BRIGHTNESS_100, remote);
            d.findViewById(R.id.brightnessClose).setOnClickListener(v -> d.dismiss());
            brightnessPanel = d;
        }
        showAtBottom(brightnessPanel);
    }

    /** MEMC 档位面板：关 / 低 / 中 / 高，选中后发送并收起。 */
    private void showMemcPanel() {
        if (memcPanel == null) {
            Dialog d = new Dialog(this);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            d.setContentView(R.layout.dialog_memc);
            d.setCanceledOnTouchOutside(true);

            bindMemcLevel(d, R.id.memcOff, RemoteKey.MEMC_OFF);
            bindMemcLevel(d, R.id.memc1, RemoteKey.MEMC_1);
            bindMemcLevel(d, R.id.memc2, RemoteKey.MEMC_2);
            bindMemcLevel(d, R.id.memc3, RemoteKey.MEMC_3);
            d.findViewById(R.id.memcClose).setOnClickListener(v -> d.dismiss());
            memcPanel = d;
        }
        showAtBottom(memcPanel);
    }

    private void bindMemcLevel(Dialog d, int viewId, RemoteKey key) {
        View v = d.findViewById(viewId);
        v.setContentDescription(key.label);
        v.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            remote.tap(key);
            d.dismiss();
        });
    }

    /** 以底部浮动面板的样式显示对话框。 */
    private static void showAtBottom(Dialog d) {
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setDimAmount(0.3f);
        }
    }

    private void showPage(boolean touchpad) {
        pageKeys.setVisibility(touchpad ? View.GONE : View.VISIBLE);
        pageTouchpad.setVisibility(touchpad ? View.VISIBLE : View.GONE);
        styleTab(tabKeys, !touchpad);
        styleTab(tabTouchpad, touchpad);
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setBackgroundResource(selected ? R.drawable.bg_tab_selected : 0);
        tab.setTextColor(getColor(selected ? R.color.on_accent : R.color.text_secondary));
    }

    // ------------------------------------------------------------------
    // 连接
    // ------------------------------------------------------------------

    private void onConnectClicked() {
        switch (remote.getState()) {
            case CONNECTED:
            case CONNECTING:
            case STARTING:
                RemoteService.stop(this);
                return;
            default:
                withConnectPermission(NEXT_CONNECT);
        }
    }

    private void withConnectPermission(int next) {
        if (!remote.isBluetoothAvailable()) {
            Toast.makeText(this, "这台手机不支持蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingAction = next;
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            // 顺便申请通知权限，用于显示后台连接的常驻通知；拒绝也不影响连接
            requestPermissions(new String[] {
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS}, REQ_CONNECT);
            return;
        }
        if (!remote.isBluetoothEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT);
            return;
        }
        runPending();
    }

    private void runPending() {
        int next = pendingAction;
        pendingAction = 0;
        if (next == NEXT_PICK || (next == NEXT_CONNECT && !remote.hasTarget())) {
            showDevicePicker();
        } else if (next == NEXT_CONNECT) {
            RemoteService.start(this);
        }
    }

    private void showDevicePicker() {
        List<BluetoothDevice> devices = new ArrayList<>(remote.bondedDevices());
        List<String> labels = new ArrayList<>();
        for (BluetoothDevice d : devices) {
            String name = d.getName();
            labels.add((name == null || name.isEmpty() ? "（无名称）" : name) + "\n" + d.getAddress());
        }
        labels.add("让手机可被发现（首次配对用）");

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("选择投影仪")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which < devices.size()) {
                        remote.setTarget(devices.get(which));
                        RemoteService.start(this);
                    } else {
                        makeDiscoverable();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 让投影仪能搜索到手机：先注册 HID（这样配对时投影仪能识别为键盘鼠标），
     * 再请求系统让手机可被发现 120 秒。
     */
    private void makeDiscoverable() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_ADVERTISE}, REQ_ADVERTISE);
            return;
        }
        RemoteService.start(this);
        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
        startActivity(i);
        Toast.makeText(this, "请在投影仪的蓝牙设置里搜索并配对这台手机", Toast.LENGTH_LONG).show();
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        // 第一项是本次真正需要的权限（通知权限只是顺带申请）
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_CONNECT) {
            if (granted) {
                withConnectPermission(pendingAction);
            } else {
                Toast.makeText(this, "没有「附近的设备」权限，无法连接投影仪", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_ADVERTISE) {
            if (granted) {
                makeDiscoverable();
            } else {
                Toast.makeText(this, "没有权限，无法让手机可被发现", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT) {
            if (remote.isBluetoothEnabled()) {
                runPending();
            } else {
                Toast.makeText(this, "蓝牙未打开", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------------
    // 状态显示
    // ------------------------------------------------------------------

    @Override
    public void onStatus(HidRemote.State state, String detail) {
        boolean connected = state == HidRemote.State.CONNECTED;
        boolean active = connected || state == HidRemote.State.CONNECTING
                || state == HidRemote.State.STARTING;
        statusText.setText(connected ? "已连接：" + detail : detail);
        connectButton.setText(active ? "断开" : getString(R.string.connect));
        GradientDrawable dot = (GradientDrawable) statusDot.getBackground().mutate();
        int color;
        switch (state) {
            case CONNECTED:  color = R.color.status_on; break;
            case CONNECTING:
            case STARTING:   color = R.color.accent; break;
            case ERROR:      color = R.color.power; break;
            default:         color = R.color.status_off; break;
        }
        dot.setColor(getColor(color));
    }

    @Override
    public void onSent(String description) {
        lastAction.setText(description);
    }

    /**
     * 连接投影仪后，手机自身的音量键改为控制投影仪音量；
     * 未连接时不拦截，手机音量键保持原有功能。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (remote.isConnected()
                && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            RemoteKey key = code == KeyEvent.KEYCODE_VOLUME_UP ? RemoteKey.VOLUME_UP : RemoteKey.VOLUME_DOWN;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                remote.keyDown(key);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                remote.keyUp(key);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
