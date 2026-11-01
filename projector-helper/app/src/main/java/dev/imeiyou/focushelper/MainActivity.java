package dev.imeiyou.focushelper;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 测试界面。
 * 1. 显示无障碍服务是否已开启；
 * 2. 三个按钮分别直接调用三项功能，用来单独验证普通应用有没有权限调用它们
 *    （不需要开启无障碍服务）。
 */
public class MainActivity extends Activity {

    private TextView status;
    private TextView result;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#263238"));
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("对焦助手");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(16), 0, dp(12));
        root.addView(status);

        Button repair = new Button(this);
        repair.setText("立即修复无障碍服务");
        repair.setTextSize(18);
        repair.setOnClickListener(v -> {
            AccessibilityGuard.Result r = AccessibilityGuard.ensureEnabled(this, "button");
            result.setText(describe(r));
            // 系统重新绑定服务需要一点时间
            status.postDelayed(this::refreshStatus, 1500);
        });
        root.addView(repair, new LinearLayout.LayoutParams(dp(320), LinearLayout.LayoutParams.WRAP_CONTENT));

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, dp(12)));

        Button first = addButton(root, "深度对焦（F12）", FocusTrigger.Action.DEEP_FOCUS);
        addButton(root, "自动梯形（F10）", FocusTrigger.Action.SMART_CORRECTION);
        addButton(root, "手动梯形校正（F9）", FocusTrigger.Action.MANUAL_KEYSTONE);
        addButton(root, "画面尺寸（F6）", FocusTrigger.Action.SCREEN_SIZE);
        addButton(root, "画面位移（F4）", FocusTrigger.Action.SCREEN_DISPLACE);
        addBrightnessButton(root, "亮度 +5（F1）", Brightness.STEP);
        addBrightnessButton(root, "亮度 −5（F2）", -Brightness.STEP);
        addToggleButton(root, "MEMC 开关（F8）", false);
        addToggleButton(root, "游戏模式开关（F7）", true);

        result = new TextView(this);
        result.setTextColor(Color.parseColor("#B0BEC5"));
        result.setTextSize(16);
        result.setGravity(Gravity.CENTER);
        result.setPadding(0, dp(16), 0, 0);
        root.addView(result);

        // 按钮较多，放进可滚动容器，避免在小分辨率下显示不全
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#263238"));
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
        first.requestFocus();
    }

    private Button addButton(LinearLayout root, String text, FocusTrigger.Action action) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(20);
        b.setOnClickListener(v -> {
            FocusTrigger.Result r = FocusTrigger.fire(this, action, "test button");
            result.setText(action.label + "：" + describe(r));
        });
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(dp(320), LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(b, lp);
        return b;
    }

    private void addBrightnessButton(LinearLayout root, String text, int delta) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(20);
        b.setOnClickListener(v -> {
            Brightness.step(this, delta);
            result.setText("已发送，结果见屏幕提示");
        });
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(dp(320), LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(b, lp);
    }

    private void addToggleButton(LinearLayout root, String text, boolean gameMode) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(20);
        b.setOnClickListener(v -> {
            if (gameMode) {
                PictureSwitches.toggleGameMode(this);
            } else {
                PictureSwitches.toggleMemc(this);
            }
            result.setText("已发送，结果见屏幕提示");
        });
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(dp(320), LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(b, lp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    /** 显示真实状态：服务是否在运行、设置清单里有没有、自动恢复是否可用。 */
    private void refreshStatus() {
        String service;
        if (FocusKeyService.running) {
            service = "无障碍服务：运行中";
        } else if (AccessibilityGuard.isListed(this)) {
            service = "无障碍服务：设置里已开启，但服务没有运行";
        } else {
            service = "无障碍服务：未开启";
        }
        String guard = AccessibilityGuard.hasPermission(this)
                ? "自动恢复：已授权"
                : "自动恢复：未授权（需用 adb 授权一次）";
        status.setText(service + "\n" + guard);
    }

    private static String describe(AccessibilityGuard.Result r) {
        switch (r) {
            case ALREADY_ENABLED: return "无障碍服务已在清单中，无需修复";
            case RESTORED:        return "已重新开启无障碍服务";
            case NO_PERMISSION:   return "没有修改系统设置的权限，请先用 adb 授权";
            case FAILED:          return "修复失败（详见 logcat）";
            default:              return r.name();
        }
    }

    private static String describe(FocusTrigger.Result r) {
        switch (r) {
            case STARTED:   return "已发送请求";
            case DEBOUNCED: return "刚刚触发过，暂时忽略";
            case NOT_FOUND: return "找不到对应的服务";
            case DENIED:    return "没有权限调用（详见 logcat）";
            default:        return r.name();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
