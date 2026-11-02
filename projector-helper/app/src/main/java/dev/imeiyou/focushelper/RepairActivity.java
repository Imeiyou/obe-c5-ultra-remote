package dev.imeiyou.focushelper;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * "修复对焦助手"：没有界面，打开即修复，提示结果后自动关闭。
 *
 * 用途：
 *   1. 功能键失效时，在桌面上点一下这个图标即可，不用进测试界面；
 *   2. 打开任何一个界面都会让系统解除对焦助手的"已停止"状态，
 *      之后开机广播才能重新送达（被强行停止过的应用收不到开机广播）；
 *   3. 如果桌面或系统有"开机自动启动应用"的设置，可以把它设成这个。
 */
public class RepairActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            AccessibilityGuard.Result r;
            try {
                r = AccessibilityGuard.ensureEnabled(app, "修复图标", true);
            } catch (Exception e) {
                GuardLog.add(app, "修复图标：出错 " + e.getClass().getSimpleName());
                r = AccessibilityGuard.Result.FAILED;
            }
            final String text = "对焦助手：" + MainActivity.describe(r);
            runOnUiThread(() -> {
                Toast.makeText(app, text, Toast.LENGTH_LONG).show();
                finish();
            });
        }, "repair").start();
    }
}
