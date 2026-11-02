package dev.imeiyou.projectorremote;

import android.annotation.SuppressLint;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 把界面上的一个按钮和一个 RemoteKey 绑定起来，按 RemoteKey.mode 决定触摸行为。
 */
public final class KeyBinder {

    // ---- 电源键分三段（按住时长）----
    /** 第一段：在这之前松手 = 短按，弹出关机菜单 */
    static final long POWER_CANCEL_ZONE_MS = 1000;
    /** 第二段：从上一个时间点到这里松手 = 取消，什么都不发；按满这个时间进入第三段，直接关机 */
    static final long POWER_SHUTDOWN_MS = 3000;

    /** 电源短按时，向投影仪发送的按住时长 */
    static final long POWER_TAP_HOLD_MS = 150;

    /** 关机时向投影仪发送的最短按住时长，要大于投影仪自己判定长按的时间 */
    static final long POWER_LONG_HOLD_MS = 3000;

    /** 电源测试模式：第三段只显示提示，不真正发送关机（调试面板里开关，不保存，重启应用后恢复关闭） */
    static volatile boolean powerDryRun;

    private KeyBinder() {}

    @SuppressLint("ClickableViewAccessibility")
    public static void bind(View view, RemoteKey key, RemoteSender sender) {
        view.setContentDescription(key.label);
        switch (key.mode) {
            case HOLD:
                view.setOnTouchListener((v, e) -> {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            // 在滚动容器里：按住期间不让父容器抢走触摸，否则按住会被中途取消
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                            v.setPressed(true);
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            sender.keyDown(key);
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            v.setPressed(false);
                            sender.keyUp(key);
                            return true;
                        default:
                            return true;
                    }
                });
                break;

            case TAP:
                view.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    sender.tap(key);
                });
                break;

            case POWER:
                bindPower(view, key, sender);
                break;

            case LONG_CONFIRM:
                view.setOnClickListener(v ->
                        Toast.makeText(v.getContext(), "长按发送「" + key.label + "」", Toast.LENGTH_SHORT).show());
                view.setOnLongClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    sender.tap(key);
                    return true;
                });
                break;
        }
    }

    /**
     * 电源键，按住时长分三段，由手机判断后再向投影仪发送固定时长的按键：
     *   第一段（0 ～ 1 秒）松手：短按 → 关机菜单
     *   第二段（1 ～ 3 秒）：震动 + 按钮显示"松手取消"，此时松手什么都不发
     *   第三段（满 3 秒）：再震动 + 显示"正在关机"，立即发送长按 → 直接关机（之后松不松手都一样）
     */
    @SuppressLint("ClickableViewAccessibility")
    private static void bindPower(View view, RemoteKey key, RemoteSender sender) {
        final int[] stage = {0};
        final long[] shutdownAt = {0};
        final CharSequence normalText = view instanceof TextView ? ((TextView) view).getText() : null;

        final Runnable restoreText = () -> setText(view, normalText);
        final Runnable enterCancelZone = () -> {
            stage[0] = 1;
            Haptics.feedback(view);
            setText(view, "松手取消");
        };
        final Runnable enterShutdown = () -> {
            stage[0] = 2;
            shutdownAt[0] = SystemClock.uptimeMillis();
            Haptics.feedback(view);
            if (powerDryRun) {
                setText(view, "测试：关机");
            } else {
                setText(view, "正在关机");
                sender.pressWithWake(key);
            }
        };

        view.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    v.setPressed(true);
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    stage[0] = 0;
                    v.removeCallbacks(restoreText);
                    restoreText.run();
                    v.postDelayed(enterCancelZone, POWER_CANCEL_ZONE_MS);
                    v.postDelayed(enterShutdown, POWER_SHUTDOWN_MS);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    v.removeCallbacks(enterCancelZone);
                    v.removeCallbacks(enterShutdown);
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    if (stage[0] == 0) {
                        if (up) {
                            sender.tapHeld(key, POWER_TAP_HOLD_MS);
                        }
                    } else if (stage[0] == 1) {
                        setText(v, "已取消");
                    } else if (!powerDryRun) {
                        long held = SystemClock.uptimeMillis() - shutdownAt[0];
                        sender.keyUpAfter(key, POWER_LONG_HOLD_MS - held);
                    }
                    v.postDelayed(restoreText, 1000);
                    return true;

                default:
                    return true;
            }
        });
    }

    private static void setText(View v, CharSequence text) {
        if (v instanceof TextView && text != null) {
            ((TextView) v).setText(text);
        }
    }
}
