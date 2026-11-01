package dev.imeiyou.projectorremote;

import android.annotation.SuppressLint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

/**
 * 把界面上的一个按钮和一个 RemoteKey 绑定起来，按 RemoteKey.mode 决定触摸行为。
 */
public final class KeyBinder {

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
}
