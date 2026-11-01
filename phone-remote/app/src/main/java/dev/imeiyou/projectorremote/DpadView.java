package dev.imeiyou.projectorremote;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * 圆形方向键：外圈四个方向，中间是确认键。
 * 五个区域都按 HOLD 方式发送：按下发"按下"，松开发"松开"，
 * 按住时由投影仪自己产生重复。按下后滑到别的区域不会切换，避免误触。
 */
public class DpadView extends View {

    private static final float MAX_RADIUS_DP = 140f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressedFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF outerRect = new RectF();
    private final RectF innerRect = new RectF();
    private final Path path = new Path();

    private float cx, cy, outerR, innerR;

    private RemoteSender sender;
    private RemoteKey pressed;

    public DpadView(Context context) {
        this(context, null);
    }

    public DpadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fill.setColor(context.getColor(R.color.surface));
        pressedFill.setColor(context.getColor(R.color.surface_pressed));
        divider.setColor(context.getColor(R.color.bg));
        divider.setStrokeWidth(dp(3));
        divider.setStyle(Paint.Style.STROKE);
        arrow.setColor(context.getColor(R.color.text_secondary));
        centerFill.setColor(context.getColor(R.color.surface_high));
        centerText.setColor(context.getColor(R.color.text_primary));
        centerText.setTextAlign(Paint.Align.CENTER);
        centerText.setTextSize(sp(18));
        setHapticFeedbackEnabled(true);
        setContentDescription("方向键");
    }

    public void setSender(RemoteSender sender) {
        this.sender = sender;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cx = w / 2f;
        cy = h / 2f;
        // 占满剩余空间时也不要大得离谱：最大直径 280dp
        outerR = Math.min(Math.min(w, h) / 2f - dp(2), dp(MAX_RADIUS_DP));
        innerR = outerR * 0.36f;
        outerRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
        innerRect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawCircle(cx, cy, outerR, fill);

        if (pressed != null && pressed != RemoteKey.OK) {
            float start = startAngle(pressed);
            path.reset();
            path.arcTo(outerRect, start, 90);
            path.arcTo(innerRect, start + 90, -90);
            path.close();
            canvas.drawPath(path, pressedFill);
        }

        // 四条分隔线（45°、135°、225°、315°）
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(45 + i * 90);
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            canvas.drawLine(cx + innerR * cos, cy + innerR * sin,
                    cx + outerR * cos, cy + outerR * sin, divider);
        }

        // 四个方向箭头
        float mid = (outerR + innerR) / 2f;
        drawArrow(canvas, cx, cy - mid, 0, -1);
        drawArrow(canvas, cx, cy + mid, 0, 1);
        drawArrow(canvas, cx - mid, cy, -1, 0);
        drawArrow(canvas, cx + mid, cy, 1, 0);

        // 中间确认键
        centerFill.setColor(getContext().getColor(
                pressed == RemoteKey.OK ? R.color.surface_pressed : R.color.surface_high));
        canvas.drawCircle(cx, cy, innerR - dp(4), centerFill);
        float textY = cy - (centerText.descent() + centerText.ascent()) / 2f;
        canvas.drawText("OK", cx, textY, centerText);
    }

    /** 画一个指向 (dirX, dirY) 的小三角形 */
    private void drawArrow(Canvas canvas, float x, float y, int dirX, int dirY) {
        float s = dp(9);
        path.reset();
        // 尖端
        path.moveTo(x + dirX * s, y + dirY * s);
        // 底边两点：垂直于方向
        path.lineTo(x - dirX * s * 0.5f + dirY * s, y - dirY * s * 0.5f + dirX * s);
        path.lineTo(x - dirX * s * 0.5f - dirY * s, y - dirY * s * 0.5f - dirX * s);
        path.close();
        canvas.drawPath(path, arrow);
    }

    private static float startAngle(RemoteKey key) {
        // Canvas 角度：0° 指向右，顺时针增加
        switch (key) {
            case RIGHT: return -45;
            case DOWN:  return 45;
            case LEFT:  return 135;
            default:    return 225;   // UP
        }
    }

    private RemoteKey regionAt(float x, float y) {
        float dx = x - cx;
        float dy = y - cy;
        double dist = Math.hypot(dx, dy);
        if (dist > outerR) {
            return null;
        }
        if (dist < innerR) {
            return RemoteKey.OK;
        }
        double a = Math.toDegrees(Math.atan2(dy, dx));   // -180..180，y 轴向下
        if (a >= -45 && a < 45) return RemoteKey.RIGHT;
        if (a >= 45 && a < 135) return RemoteKey.DOWN;
        if (a >= -135 && a < -45) return RemoteKey.UP;
        return RemoteKey.LEFT;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                RemoteKey key = regionAt(e.getX(), e.getY());
                if (key == null) {
                    return false;
                }
                // 在滚动容器里：按住期间不让父容器抢走触摸
                getParent().requestDisallowInterceptTouchEvent(true);
                pressed = key;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (sender != null) {
                    sender.keyDown(key);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pressed != null) {
                    if (sender != null) {
                        sender.keyUp(pressed);
                    }
                    pressed = null;
                    invalidate();
                }
                return true;
            default:
                return pressed != null;
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}
