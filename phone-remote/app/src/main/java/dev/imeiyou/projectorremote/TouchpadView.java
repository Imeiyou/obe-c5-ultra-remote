package dev.imeiyou.projectorremote;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 触控板：
 *   单指滑动 → 移动光标（带简单的加速度）
 *   单指轻点 → 鼠标左键单击
 *   双指上下滑动 → 滚轮
 */
public class TouchpadView extends View {

    // 光标速度：基础倍率与加速度
    private static final float BASE_GAIN = 1.1f;
    private static final float ACCEL = 0.04f;
    private static final float MAX_GAIN = 3.5f;

    // 轻点判定
    private static final long TAP_MAX_MS = 220;

    // 双指滚动：手指移动多少 dp 算一格
    private static final float SCROLL_STEP_DP = 28f;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint touchDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final int touchSlop;
    private final float scrollStepPx;

    private RemoteSender sender;

    private float lastX, lastY;
    private float downX, downY;
    private long downTime;
    private boolean moved;
    private boolean multiTouch;
    private float remX, remY;      // 移动量的小数部分
    private float scrollAcc;
    private float lastScrollY;
    private boolean touching;

    private String hint1 = "";
    private String hint2 = "";

    public TouchpadView(Context context) {
        this(context, null);
    }

    public TouchpadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        bg.setColor(context.getColor(R.color.surface));
        border.setColor(context.getColor(R.color.stroke));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(dp(1));
        hint.setColor(context.getColor(R.color.text_secondary));
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                getResources().getDisplayMetrics()));
        touchDot.setColor(context.getColor(R.color.accent_dim));
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        scrollStepPx = dp(SCROLL_STEP_DP);
        hint1 = context.getString(R.string.touchpad_hint_1);
        hint2 = context.getString(R.string.touchpad_hint_2);
        setHapticFeedbackEnabled(true);
        setContentDescription("触控板");
    }

    public void setSender(RemoteSender sender) {
        this.sender = sender;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = dp(20);
        rect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(rect, r, r, bg);
        rect.inset(border.getStrokeWidth() / 2f, border.getStrokeWidth() / 2f);
        canvas.drawRoundRect(rect, r, r, border);

        if (touching && !multiTouch) {
            canvas.drawCircle(lastX, lastY, dp(28), touchDot);
        }

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float lh = hint.getTextSize() * 1.6f;
        canvas.drawText(hint1, cx, cy - lh / 2f, hint);
        canvas.drawText(hint2, cx, cy + lh / 2f, hint);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                lastX = downX = e.getX();
                lastY = downY = e.getY();
                downTime = e.getEventTime();
                moved = false;
                multiTouch = false;
                remX = remY = 0;
                touching = true;
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) {
                    multiTouch = true;
                    scrollAcc = 0;
                    lastScrollY = averageY(e);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (multiTouch) {
                    if (e.getPointerCount() >= 2) {
                        handleScroll(e);
                    }
                } else {
                    handleMove(e);
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                // 双指操作中抬起一根手指后，不再恢复为移动光标，直到全部抬起
                return true;

            case MotionEvent.ACTION_UP:
                if (!multiTouch && !moved && e.getEventTime() - downTime <= TAP_MAX_MS) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (sender != null) {
                        sender.mouseClick();
                    }
                }
                touching = false;
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                touching = false;
                invalidate();
                return true;

            default:
                return true;
        }
    }

    private void handleMove(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();
        if (!moved && Math.hypot(x - downX, y - downY) > touchSlop) {
            moved = true;
        }
        float dx = x - lastX;
        float dy = y - lastY;
        lastX = x;
        lastY = y;
        invalidate();
        if (!moved) {
            return;
        }

        // 简单加速：手指每次移动的距离越大，倍率越高
        float dist = (float) Math.hypot(dx, dy);
        float gain = Math.min(MAX_GAIN, BASE_GAIN + ACCEL * dist);
        float fx = dx * gain / getResources().getDisplayMetrics().density * 2f + remX;
        float fy = dy * gain / getResources().getDisplayMetrics().density * 2f + remY;
        int ix = (int) fx;
        int iy = (int) fy;
        remX = fx - ix;
        remY = fy - iy;

        // HID 鼠标报告每次最多 ±127，超出的分多次发送
        while ((ix != 0 || iy != 0) && sender != null) {
            int sx = clamp(ix);
            int sy = clamp(iy);
            sender.mouseMove(sx, sy);
            ix -= sx;
            iy -= sy;
        }
    }

    private void handleScroll(MotionEvent e) {
        float y = averageY(e);
        // 手指向下滑 → 滚轮向上（内容向下），和手机上的自然滚动方向一致
        scrollAcc += y - lastScrollY;
        lastScrollY = y;
        while (scrollAcc >= scrollStepPx) {
            scrollAcc -= scrollStepPx;
            if (sender != null) sender.mouseScroll(1);
        }
        while (scrollAcc <= -scrollStepPx) {
            scrollAcc += scrollStepPx;
            if (sender != null) sender.mouseScroll(-1);
        }
    }

    private static float averageY(MotionEvent e) {
        float sum = 0;
        int n = e.getPointerCount();
        for (int i = 0; i < n; i++) {
            sum += e.getY(i);
        }
        return sum / n;
    }

    private static int clamp(int v) {
        return Math.max(-127, Math.min(127, v));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
