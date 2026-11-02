package dev.imeiyou.focushelper;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 测试界面。
 * 1. 显示无障碍服务是否已开启、本次开机是否收到开机广播，以及守护日志；
 * 2. 三个按钮分别直接调用三项功能，用来单独验证普通应用有没有权限调用它们
 *    （不需要开启无障碍服务）。
 */
public class MainActivity extends Activity {

    private TextView status;
    private TextView result;
    private TextView logView;

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
            // 可能要"移除再加回"，耗时几秒，放到后台线程
            repair.setEnabled(false);
            result.setText("正在检查……");
            final Context app = getApplicationContext();
            new Thread(() -> {
                AccessibilityGuard.Result r;
                try {
                    r = AccessibilityGuard.ensureEnabled(app, "修复按钮", true);
                } catch (Exception e) {
                    GuardLog.add(app, "修复按钮：出错 " + e.getClass().getSimpleName());
                    r = AccessibilityGuard.Result.FAILED;
                }
                final AccessibilityGuard.Result fr = r;
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    repair.setEnabled(true);
                    result.setText(describe(fr));
                    // 系统重新绑定服务需要一点时间
                    status.postDelayed(this::refreshStatus, 1500);
                });
            }, "repair-button").start();
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

        // 守护日志：重启前后发生了什么，不依赖 logcat
        TextView logTitle = new TextView(this);
        logTitle.setText("守护日志（新的在上）");
        logTitle.setTextColor(Color.WHITE);
        logTitle.setTextSize(18);
        logTitle.setPadding(0, dp(24), 0, dp(8));
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#CFD8DC"));
        logView.setTextSize(13);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(logView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button clearLog = new Button(this);
        clearLog.setText("清空日志");
        clearLog.setTextSize(16);
        clearLog.setOnClickListener(v -> {
            GuardLog.clear(this);
            refreshStatus();
        });
        root.addView(clearLog, new LinearLayout.LayoutParams(dp(200), LinearLayout.LayoutParams.WRAP_CONTENT));

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
        // 打开测试界面时自动检查一次（服务正常时什么也不做）。
        // 有的桌面（例如 Aerial）一个应用只显示一个图标，"修复对焦助手"图标看不到，
        // 所以打开"对焦助手"本身也要能修复。
        final Context app = getApplicationContext();
        new Thread(() -> {
            AccessibilityGuard.Result r;
            try {
                r = AccessibilityGuard.ensureEnabled(app, "打开测试界面", true);
            } catch (Exception e) {
                GuardLog.add(app, "打开测试界面：出错 " + e.getClass().getSimpleName());
                return;
            }
            if (r == AccessibilityGuard.Result.ALREADY_ENABLED) {
                return;
            }
            final AccessibilityGuard.Result fr = r;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                result.setText("自动检查：" + describe(fr));
                status.postDelayed(this::refreshStatus, 1500);
            });
        }, "auto-check").start();
    }

    /** 显示真实状态：服务是否在运行、设置清单里有没有、自动恢复是否可用、本次开机广播是否收到。 */
    private void refreshStatus() {
        String service;
        if (AccessibilityGuard.isBound(this)) {
            service = "无障碍服务：运行中";
        } else if (AccessibilityGuard.isListed(this)) {
            service = "无障碍服务：设置里已开启，但服务没有运行";
        } else {
            service = "无障碍服务：未开启";
        }
        String guard = AccessibilityGuard.hasPermission(this)
                ? "自动恢复：已授权"
                : "自动恢复：未授权（需用 adb 授权一次）";

        int boot = GuardLog.bootCount(this);
        int seen = GuardLog.lastBootSeen(this);
        String bootLine;
        if (boot < 0) {
            bootLine = "本次开机：读不到开机序号";
        } else if (seen == boot) {
            bootLine = "本次开机（#" + boot + "）：已收到开机广播";
        } else {
            bootLine = "本次开机（#" + boot + "）：没有收到开机广播"
                    + (seen >= 0 ? "（上次收到是 #" + seen + "）" : "");
        }
        status.setText(service + "\n" + guard + "\n" + bootLine);

        if (logView != null) {
            List<String> lines = GuardLog.recent(this, 30);
            logView.setText(lines.isEmpty() ? "（暂无记录）" : android.text.TextUtils.join("\n", lines));
        }
    }

    static String describe(AccessibilityGuard.Result r) {
        switch (r) {
            case ALREADY_ENABLED: return "无障碍服务正常运行，无需修复";
            case RESTORED:        return "已重新开启无障碍服务";
            case REBOUND:         return "服务没有运行，已让系统重新启动它";
            case REBIND_FAILED:   return "已尝试重新启动服务，但仍未运行（详见守护日志）";
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
