package dev.imeiyou.focushelper;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

/**
 * 调用投影仪自带的功能（都在 com.obe.settings 里）。
 *
 * 深度对焦：启动 FocusService，等价于已验证的命令
 *   am startservice -a obe.action.AUTO_FOCUS -p com.obe.settings --es from remote_long_press
 *
 * 手动梯形校正：启动 ProjectorCallerService，等价于已验证的命令
 *   am startservice -a obe.action.KEYSTONE_CORRECTION -p com.obe.settings
 *
 * 智能校正（自动梯形 + 对焦）：绑定 CommonApiService，调用 IObeSettingsApi.autoKeystone()。
 *   经分析确认：它和设置菜单里"智能校正"调用的是同一个方法 KeystoneManagerImpl.autoKeystone()。
 *   接口名 com.obe.settings.commonapi.IObeSettingsApi，autoKeystone 的事务号是 10，无参数。
 */
public final class FocusTrigger {

    public static final String TAG = "FocusHelper";

    private static final String TARGET_PACKAGE = "com.obe.settings";

    private static final String API_ACTION = "obe.action.COMMON_API_SERVICE";
    private static final String API_DESCRIPTOR = "com.obe.settings.commonapi.IObeSettingsApi";
    private static final int TRANSACTION_AUTO_KEYSTONE = 10;

    public enum Action {
        // 一次深度对焦约 7 秒
        DEEP_FOCUS("深度对焦", "obe.action.AUTO_FOCUS", "remote_long_press", 3000),
        // 打开手动梯形校正界面
        MANUAL_KEYSTONE("手动梯形校正", "obe.action.KEYSTONE_CORRECTION", null, 1000),
        // 自动梯形校正 + 对焦，约 9 秒
        SMART_CORRECTION("智能校正", null, null, 5000),
        // 打开画面尺寸界面（已用 adb 验证）
        SCREEN_SIZE("画面尺寸", "obe.action.SCREEN_SIZE", null, 1000),
        // 打开画面位移界面（已用 adb 验证）
        SCREEN_DISPLACE("画面位移", "obe.action.SCREEN_DISPLACE", null, 1000);

        final String label;
        final String intentAction;   // null 表示走 CommonApiService 接口
        final String from;
        final long debounceMs;
        long lastFireAt;

        Action(String label, String intentAction, String from, long debounceMs) {
            this.label = label;
            this.intentAction = intentAction;
            this.from = from;
            this.debounceMs = debounceMs;
            this.lastFireAt = -debounceMs;
        }
    }

    public enum Result { STARTED, DEBOUNCED, NOT_FOUND, DENIED }

    private FocusTrigger() {}

    public static synchronized Result fire(Context context, Action action, String source) {
        long now = SystemClock.elapsedRealtime();
        if (now - action.lastFireAt < action.debounceMs) {
            Log.i(TAG, action + " ignored (debounce), source=" + source);
            return Result.DEBOUNCED;
        }
        action.lastFireAt = now;

        if (action.intentAction != null) {
            return startService(context, action, source);
        } else {
            return callAutoKeystone(context.getApplicationContext(), source);
        }
    }

    private static Result startService(Context context, Action action, String source) {
        Intent intent = new Intent(action.intentAction).setPackage(TARGET_PACKAGE);
        if (action.from != null) {
            intent.putExtra("from", action.from);
        }
        try {
            ComponentName started = context.startService(intent);
            if (started == null) {
                Log.w(TAG, action + " service not found, source=" + source);
                return Result.NOT_FOUND;
            }
            Log.i(TAG, action + " started: " + started.flattenToShortString() + ", source=" + source);
            return Result.STARTED;
        } catch (SecurityException | IllegalStateException e) {
            Log.e(TAG, action + " startService failed, source=" + source, e);
            return Result.DENIED;
        }
    }

    /**
     * 绑定 CommonApiService，调用一次 autoKeystone()，然后解绑。
     * 调用本身是异步完成的，这里返回 STARTED 只表示绑定请求已经发出。
     */
    private static Result callAutoKeystone(final Context app, final String source) {
        Intent intent = new Intent(API_ACTION).setPackage(TARGET_PACKAGE);
        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, final IBinder binder) {
                final ServiceConnection self = this;
                new Thread(() -> {
                    Parcel data = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(API_DESCRIPTOR);
                        binder.transact(TRANSACTION_AUTO_KEYSTONE, data, reply, 0);
                        reply.readException();
                        Log.i(TAG, "SMART_CORRECTION called autoKeystone(), source=" + source);
                    } catch (Exception e) {
                        Log.e(TAG, "SMART_CORRECTION transact failed, source=" + source, e);
                    } finally {
                        data.recycle();
                        reply.recycle();
                        try {
                            app.unbindService(self);
                        } catch (IllegalArgumentException ignored) {
                            // 已经解绑
                        }
                    }
                }, "smart-correction").start();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.w(TAG, "CommonApiService disconnected");
            }
        };
        try {
            boolean ok = app.bindService(intent, conn, Context.BIND_AUTO_CREATE);
            if (!ok) {
                Log.w(TAG, "SMART_CORRECTION bind failed (service not found), source=" + source);
                return Result.NOT_FOUND;
            }
            Log.i(TAG, "SMART_CORRECTION binding, source=" + source);
            return Result.STARTED;
        } catch (SecurityException e) {
            Log.e(TAG, "SMART_CORRECTION bind denied, source=" + source, e);
            return Result.DENIED;
        }
    }
}
