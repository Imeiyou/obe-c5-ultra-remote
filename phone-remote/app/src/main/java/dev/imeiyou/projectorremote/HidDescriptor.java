package dev.imeiyou.projectorremote;

/**
 * 蓝牙 HID 报告描述符：一个设备同时包含键盘、多媒体（消费者控制）、鼠标三部分。
 *
 *   报告 1：键盘    8 字节 = 修饰键 1 + 保留 1 + 同时按下的按键用法码 6
 *   报告 2：多媒体  2 字节 = 消费者页用法码（小端序），0 表示松开
 *   报告 3：鼠标    4 字节 = 按键位 1 + X 1 + Y 1 + 滚轮 1（均为 -127..127）
 */
final class HidDescriptor {

    static final byte ID_KEYBOARD = 1;
    static final byte ID_CONSUMER = 2;
    static final byte ID_MOUSE = 3;

    static final int KEYBOARD_REPORT_SIZE = 8;
    static final int CONSUMER_REPORT_SIZE = 2;
    static final int MOUSE_REPORT_SIZE = 4;

    static final byte[] DESCRIPTOR = bytes(
            // ---------- 键盘 ----------
            0x05, 0x01,             // Usage Page (Generic Desktop)
            0x09, 0x06,             // Usage (Keyboard)
            0xA1, 0x01,             // Collection (Application)
            0x85, ID_KEYBOARD,      //   Report ID (1)
            0x05, 0x07,             //   Usage Page (Keyboard/Keypad)
            0x19, 0xE0,             //   Usage Minimum (Left Control)
            0x29, 0xE7,             //   Usage Maximum (Right GUI)
            0x15, 0x00,             //   Logical Minimum (0)
            0x25, 0x01,             //   Logical Maximum (1)
            0x75, 0x01,             //   Report Size (1)
            0x95, 0x08,             //   Report Count (8)
            0x81, 0x02,             //   Input (Data, Var, Abs)  修饰键
            0x95, 0x01,             //   Report Count (1)
            0x75, 0x08,             //   Report Size (8)
            0x81, 0x01,             //   Input (Const)           保留字节
            0x95, 0x06,             //   Report Count (6)
            0x75, 0x08,             //   Report Size (8)
            0x15, 0x00,             //   Logical Minimum (0)
            0x26, 0xFF, 0x00,       //   Logical Maximum (255)
            0x05, 0x07,             //   Usage Page (Keyboard/Keypad)
            0x19, 0x00,             //   Usage Minimum (0)
            0x2A, 0xFF, 0x00,       //   Usage Maximum (255)   覆盖电源 0x66、音量 0x80/0x81 等
            0x81, 0x00,             //   Input (Data, Array)     按键数组
            0xC0,                   // End Collection

            // ---------- 多媒体（消费者控制） ----------
            0x05, 0x0C,             // Usage Page (Consumer)
            0x09, 0x01,             // Usage (Consumer Control)
            0xA1, 0x01,             // Collection (Application)
            0x85, ID_CONSUMER,      //   Report ID (2)
            0x15, 0x00,             //   Logical Minimum (0)
            0x26, 0xFF, 0x03,       //   Logical Maximum (0x3FF)
            0x19, 0x00,             //   Usage Minimum (0)
            0x2A, 0xFF, 0x03,       //   Usage Maximum (0x3FF)  覆盖返回 0x224、主页 0x223、静音 0xE2
            0x75, 0x10,             //   Report Size (16)
            0x95, 0x01,             //   Report Count (1)
            0x81, 0x00,             //   Input (Data, Array)
            0xC0,                   // End Collection

            // ---------- 鼠标 ----------
            0x05, 0x01,             // Usage Page (Generic Desktop)
            0x09, 0x02,             // Usage (Mouse)
            0xA1, 0x01,             // Collection (Application)
            0x85, ID_MOUSE,         //   Report ID (3)
            0x09, 0x01,             //   Usage (Pointer)
            0xA1, 0x00,             //   Collection (Physical)
            0x05, 0x09,             //     Usage Page (Button)
            0x19, 0x01,             //     Usage Minimum (1)
            0x29, 0x03,             //     Usage Maximum (3)
            0x15, 0x00,             //     Logical Minimum (0)
            0x25, 0x01,             //     Logical Maximum (1)
            0x95, 0x03,             //     Report Count (3)
            0x75, 0x01,             //     Report Size (1)
            0x81, 0x02,             //     Input (Data, Var, Abs)  三个按键
            0x95, 0x01,             //     Report Count (1)
            0x75, 0x05,             //     Report Size (5)
            0x81, 0x01,             //     Input (Const)           补齐 5 位
            0x05, 0x01,             //     Usage Page (Generic Desktop)
            0x09, 0x30,             //     Usage (X)
            0x09, 0x31,             //     Usage (Y)
            0x09, 0x38,             //     Usage (Wheel)
            0x15, 0x81,             //     Logical Minimum (-127)
            0x25, 0x7F,             //     Logical Maximum (127)
            0x75, 0x08,             //     Report Size (8)
            0x95, 0x03,             //     Report Count (3)
            0x81, 0x06,             //     Input (Data, Var, Rel)
            0xC0,                   //   End Collection
            0xC0                    // End Collection
    );

    private HidDescriptor() {}

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
