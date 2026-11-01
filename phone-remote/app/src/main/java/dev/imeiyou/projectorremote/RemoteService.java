package dev.imeiyou.projectorremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * 后台常驻服务：让蓝牙遥控在应用切到后台、锁屏或界面被关闭后仍然保持连接。
 *
 * 投影仪关机或断开后，服务继续运行并保持 HID 注册，
 * 投影仪再次开机时可以主动连回手机，不需要重新打开应用。
 * 通知栏里的"断开"会完全停止服务。
 */
public class RemoteService extends Service implements HidRemote.StatusListener {

    private static final String CHANNEL_ID = "remote";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_STOP = "dev.imeiyou.projectorremote.STOP";

    private HidRemote remote;
    private NotificationManager nm;

    /** 启动服务并开始连接。必须在界面处于前台时调用。 */
    public static void start(Context context) {
        context.startForegroundService(new Intent(context, RemoteService.class));
    }

    /** 断开并停止服务。 */
    public static void stop(Context context) {
        context.startService(new Intent(context, RemoteService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "蓝牙遥控连接", NotificationManager.IMPORTANCE_LOW));
        remote = HidRemote.get(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            remote.removeStatusListener(this);
            remote.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(remote.getState(), remote.getDetail()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        remote.addStatusListener(this);
        remote.start();
        // 被系统杀掉后不自动重启：重启时界面不在前台，权限和蓝牙状态都无法保证
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        remote.removeStatusListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onStatus(HidRemote.State state, String detail) {
        if (state == HidRemote.State.OFF) {
            return;
        }
        nm.notify(NOTIFICATION_ID, buildNotification(state, detail));
    }

    private Notification buildNotification(HidRemote.State state, String detail) {
        String text;
        switch (state) {
            case CONNECTED: text = "已连接：" + detail; break;
            default:        text = detail; break;
        }

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RemoteService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.launcher_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "断开", stop).build())
                .build();
    }
}
