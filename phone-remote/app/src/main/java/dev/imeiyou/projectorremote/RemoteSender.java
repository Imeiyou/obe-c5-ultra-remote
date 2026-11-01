package dev.imeiyou.projectorremote;

/**
 * 界面与发送层之间的接口。
 * 现在由 DemoSender 实现（只记录，不发送）；下一步接入蓝牙 HID 实现。
 */
public interface RemoteSender {

    /** 按键按下（HOLD 类按键使用） */
    void keyDown(RemoteKey key);

    /** 按键松开（HOLD 类按键使用） */
    void keyUp(RemoteKey key);

    /** 发送一次完整的"按下 + 松开"（TAP、LONG_CONFIRM 类按键使用） */
    void tap(RemoteKey key);

    /** 鼠标相对移动，单位为 HID 报告里的计数 */
    void mouseMove(int dx, int dy);

    /** 鼠标左键单击 */
    void mouseClick();

    /** 滚轮，正数向上，负数向下 */
    void mouseScroll(int notches);

    boolean isConnected();

    /** 界面用来显示发送状态的回调 */
    interface Listener {
        void onSent(String description);
    }
}
