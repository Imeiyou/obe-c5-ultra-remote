package dev.imeiyou.projectorremote;

/**
 * 界面与发送层之间的接口。
 * 由 HidRemote 实现。
 */
public interface RemoteSender {

    /** 按键按下（HOLD 类按键使用） */
    void keyDown(RemoteKey key);

    /** 按键松开（HOLD 类按键使用） */
    void keyUp(RemoteKey key);

    /** 发送一次完整的"按下 + 松开"（TAP、LONG_CONFIRM 类按键使用） */
    void tap(RemoteKey key);

    /** 先唤醒蓝牙链路，再发送一次按住 holdMs 毫秒的按键（电源短按使用） */
    void tapHeld(RemoteKey key, long holdMs);

    /** 先唤醒蓝牙链路，再发送"按下"，之后需要调用 keyUpAfter 松开（电源长按使用） */
    void pressWithWake(RemoteKey key);

    /** delayMs 毫秒后发送"松开" */
    void keyUpAfter(RemoteKey key, long delayMs);

    /**
     * 唤醒蓝牙链路：发一个不移动、不改变按键的鼠标报告。
     * 投影仪收到后不会产生任何动作，但链路会从省电状态恢复，之后的移动不再延迟。
     */
    void wakeLink();

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
