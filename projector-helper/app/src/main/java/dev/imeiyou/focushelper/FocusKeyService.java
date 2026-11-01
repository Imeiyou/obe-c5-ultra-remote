package dev.imeiyou.focushelper;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * 截获以下按键（Generic.kl 中的映射），触发投影仪功能：
 *   F12（key 88）→ 深度对焦
 *   F10（key 68）→ 智能校正（自动梯形 + 对焦）
 *   F9 （key 67）→ 手动梯形校正
 *   F8 （key 66）→ MEMC 开关（与游戏模式互斥）
 *   F7 （key 65）→ 游戏模式开关（与 MEMC 互斥）
 *   F6 （key 64）→ 画面尺寸
 *   F4 （key 62）→ 画面位移
 *   F1 （key 59）→ 亮度 +5（可按住连续调节）
 *   F2 （key 60）→ 亮度 −5（可按住连续调节）
 *   Alt+1 … Alt+5 → 亮度预设档位（见 Brightness.PRESETS）
 *   （F5 不用：投影仪的 Generic.kl 里 key 63 没有映射）
 *
 * 这几个键的按下、重复、松开事件全部吞掉，不再传给前台应用。
 * 除亮度加减外，都只在第一次按下（repeatCount == 0）时触发一次。
 * 其他按键一律放行。
 */
public class FocusKeyService extends AccessibilityService {

    // 开关类按键的防重复间隔
    private static final long TOGGLE_DEBOUNCE_MS = 1000;
    private long lastToggleAt = -TOGGLE_DEBOUNCE_MS;

    // 亮度加减按住时，每隔多久调一次（投影仪自己的按键重复太快）
    private static final long BRIGHTNESS_REPEAT_MS = 150;
    private long lastBrightnessAt;

    // 已被截获的 Alt+数字 按下事件：松开时 Alt 可能已经先松开，要按键码配对吞掉
    private final java.util.Set<Integer> consumedDigits = new java.util.HashSet<>();

    /** 服务是否真正在运行（被系统绑定），供界面显示。 */
    static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 运行期间监视无障碍服务清单：如果被别的程序改写掉了，立刻加回去。 */
    private final ContentObserver listObserver = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            if (!AccessibilityGuard.isListed(FocusKeyService.this)) {
                Log.w(FocusTrigger.TAG, "accessibility list changed, focushelper removed");
                handler.postDelayed(() ->
                        AccessibilityGuard.ensureEnabled(FocusKeyService.this, "observer"), 1000);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = true;
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false, listObserver);
        Log.i(FocusTrigger.TAG, "accessibility service connected");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        // 被系统解绑时（例如清单被改写），进程可能很快被回收：交给定时检查来恢复
        AccessibilityGuard.scheduleCheck(this, 3000, 3);
        Log.i(FocusTrigger.TAG, "accessibility service unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        getContentResolver().unregisterContentObserver(listObserver);
        super.onDestroy();
    }

    private static FocusTrigger.Action actionFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_F12: return FocusTrigger.Action.DEEP_FOCUS;
            case KeyEvent.KEYCODE_F10: return FocusTrigger.Action.SMART_CORRECTION;
            case KeyEvent.KEYCODE_F9:  return FocusTrigger.Action.MANUAL_KEYSTONE;
            case KeyEvent.KEYCODE_F6:  return FocusTrigger.Action.SCREEN_SIZE;
            case KeyEvent.KEYCODE_F4:  return FocusTrigger.Action.SCREEN_DISPLACE;
            default:                   return null;
        }
    }

    private static boolean isToggleKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_F7 || keyCode == KeyEvent.KEYCODE_F8;
    }

    /** 亮度加减与预设档位。返回 true 表示已处理。 */
    private boolean handleBrightness(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        if (keyCode == KeyEvent.KEYCODE_F1 || keyCode == KeyEvent.KEYCODE_F2) {
            if (down) {
                long now = SystemClock.elapsedRealtime();
                if (event.getRepeatCount() == 0 || now - lastBrightnessAt >= BRIGHTNESS_REPEAT_MS) {
                    lastBrightnessAt = now;
                    int delta = keyCode == KeyEvent.KEYCODE_F1 ? Brightness.STEP : -Brightness.STEP;
                    Brightness.step(this, delta);
                }
            }
            return true;
        }

        if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_5) {
            if (down && event.isAltPressed()) {
                consumedDigits.add(keyCode);
                if (event.getRepeatCount() == 0) {
                    int value = Brightness.PRESETS[keyCode - KeyEvent.KEYCODE_1];
                    Log.i(FocusTrigger.TAG, "brightness preset " + value + " deviceId=" + event.getDeviceId());
                    Brightness.set(this, value);
                }
                return true;
            }
            if (!down && consumedDigits.remove(keyCode)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (handleBrightness(event)) {
            return true;
        }
        int keyCode = event.getKeyCode();
        FocusTrigger.Action action = actionFor(keyCode);
        boolean toggle = isToggleKey(keyCode);
        if (action == null && !toggle) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return true;
        }

        if (toggle) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastToggleAt < TOGGLE_DEBOUNCE_MS) {
                return true;
            }
            lastToggleAt = now;
            Log.i(FocusTrigger.TAG, "toggle key " + keyCode + " deviceId=" + event.getDeviceId());
            if (keyCode == KeyEvent.KEYCODE_F7) {
                PictureSwitches.toggleGameMode(this);
            } else {
                PictureSwitches.toggleMemc(this);
            }
            return true;
        }

        FocusTrigger.Result r = FocusTrigger.fire(this, action,
                "key " + keyCode + " deviceId=" + event.getDeviceId());
        if (r == FocusTrigger.Result.NOT_FOUND || r == FocusTrigger.Result.DENIED) {
            Toast.makeText(this, action.label + "启动失败：" + r, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不处理界面事件
    }

    @Override
    public void onInterrupt() {
        // 无需处理
    }
}
