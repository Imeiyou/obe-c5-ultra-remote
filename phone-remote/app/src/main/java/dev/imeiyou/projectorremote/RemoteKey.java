package dev.imeiyou.projectorremote;

/**
 * 遥控器上的所有按键，以及它们通过蓝牙 HID 发出的编码。
 *
 * 编码依据：投影仪对手机这类设备使用 /system/usr/keylayout/Generic.kl。
 *   KEYBOARD：HID 键盘页（0x07）用法码
 *   CONSUMER：HID 消费者页（0x0C）用法码
 *
 * 发送方式：
 *   HOLD          按下发"按下"，松开发"松开"；按住时由投影仪自己产生重复或判断长按（方向键、音量、电源）
 *   TAP           无论按多久，都只发一次"按下 + 松开"（避免设置菜单反复开关那类问题）
 *   LONG_CONFIRM  必须长按才发送一次，防止误触（目前没有按键使用）
 *   POWER         由手机判断短按 / 长按，再向投影仪发送固定时长的按键（电源键使用）
 */
public enum RemoteKey {

    // 导航
    UP("上", Page.KEYBOARD, 0x52, Mode.HOLD),
    DOWN("下", Page.KEYBOARD, 0x51, Mode.HOLD),
    LEFT("左", Page.KEYBOARD, 0x50, Mode.HOLD),
    RIGHT("右", Page.KEYBOARD, 0x4F, Mode.HOLD),
    OK("确认", Page.KEYBOARD, 0x28, Mode.HOLD),
    BACK("返回", Page.CONSUMER, 0x0224, Mode.TAP),
    HOME("主页", Page.CONSUMER, 0x0223, Mode.TAP),
    MENU("菜单", Page.KEYBOARD, 0x65, Mode.TAP),

    // 系统
    SETTINGS("设置", Page.KEYBOARD, 0x44, Mode.TAP),          // F11 → Generic.kl key 87 SETTINGS
    // 短按 / 长按由手机判断（见 KeyBinder 的 POWER 模式）：短按弹出关机菜单，长按直接关机
    POWER("电源", Page.KEYBOARD, 0x66, Mode.POWER),

    // 声音
    VOLUME_UP("音量 +", Page.KEYBOARD, 0x80, Mode.HOLD),
    VOLUME_DOWN("音量 −", Page.KEYBOARD, 0x81, Mode.HOLD),
    MUTE("静音", Page.CONSUMER, 0x00E2, Mode.TAP),

    // 投影功能：由投影仪上的"对焦助手"截获后调用系统功能
    DEEP_FOCUS("深度对焦", Page.KEYBOARD, 0x45, Mode.TAP),        // F12
    SMART_CORRECTION("自动梯形", Page.KEYBOARD, 0x43, Mode.TAP),  // F10（自动梯形 + 对焦）
    MANUAL_KEYSTONE("手动梯形", Page.KEYBOARD, 0x42, Mode.TAP),   // F9
    MEMC("MEMC", Page.KEYBOARD, 0x41, Mode.TAP),                 // F8 开关（界面已改用下面的档位面板，保留兼容）
    GAME_MODE("游戏模式", Page.KEYBOARD, 0x40, Mode.TAP),         // F7
    SCREEN_SIZE("画面尺寸", Page.KEYBOARD, 0x3F, Mode.TAP),       // F6
    SCREEN_DISPLACE("画面位移", Page.KEYBOARD, 0x3D, Mode.TAP),   // F4（F5 在投影仪上没有映射）

    // 亮度：加减键按住可连续调节（对焦助手每 150ms 调一次 ±5）
    BRIGHTNESS_UP("亮度 +", Page.KEYBOARD, 0x3A, Mode.HOLD),       // F1
    BRIGHTNESS_DOWN("亮度 −", Page.KEYBOARD, 0x3B, Mode.HOLD),     // F2
    // 亮度预设档位：Alt + 数字 1~5，数值必须和对焦助手 Brightness.PRESETS 一致
    BRIGHTNESS_30("30", Page.KEYBOARD, 0x1E, Mode.TAP, Mod.LEFT_ALT),
    BRIGHTNESS_50("50", Page.KEYBOARD, 0x1F, Mode.TAP, Mod.LEFT_ALT),
    BRIGHTNESS_70("70", Page.KEYBOARD, 0x20, Mode.TAP, Mod.LEFT_ALT),
    BRIGHTNESS_85("85", Page.KEYBOARD, 0x21, Mode.TAP, Mod.LEFT_ALT),
    BRIGHTNESS_100("100", Page.KEYBOARD, 0x22, Mode.TAP, Mod.LEFT_ALT),

    // MEMC 档位：Alt + 数字 6~9 → 关 / 低 / 中 / 高（对焦助手 PictureSwitches.setMemc）
    MEMC_OFF("MEMC 关", Page.KEYBOARD, 0x23, Mode.TAP, Mod.LEFT_ALT),
    MEMC_1("MEMC 低", Page.KEYBOARD, 0x24, Mode.TAP, Mod.LEFT_ALT),
    MEMC_2("MEMC 中", Page.KEYBOARD, 0x25, Mode.TAP, Mod.LEFT_ALT),
    MEMC_3("MEMC 高", Page.KEYBOARD, 0x26, Mode.TAP, Mod.LEFT_ALT);

    public enum Page { KEYBOARD, CONSUMER }

    public enum Mode { HOLD, TAP, LONG_CONFIRM, POWER }

    /**
     * 键盘报告第一个字节里的修饰键位。
     * 放在嵌套类里：枚举常量不能直接引用写在它们后面的静态字段。
     */
    static final class Mod {
        static final int LEFT_ALT = 0x04;

        private Mod() {}
    }

    public final String label;
    public final Page page;
    public final int usage;
    public final Mode mode;
    /** 需要同时按住的修饰键（只用于键盘页），0 表示没有 */
    public final int modifiers;

    RemoteKey(String label, Page page, int usage, Mode mode) {
        this(label, page, usage, mode, 0);
    }

    RemoteKey(String label, Page page, int usage, Mode mode, int modifiers) {
        this.label = label;
        this.page = page;
        this.usage = usage;
        this.mode = mode;
        this.modifiers = modifiers;
    }

    /**
     * 这个键是否要靠投影仪上的"对焦助手"截获才有效果。
     * 是的话，发送前会先确认对焦助手在运行（见 ProjectorGuard）。
     */
    public boolean needsHelper() {
        switch (this) {
            case DEEP_FOCUS:
            case SMART_CORRECTION:
            case MANUAL_KEYSTONE:
            case MEMC:
            case GAME_MODE:
            case SCREEN_SIZE:
            case SCREEN_DISPLACE:
            case BRIGHTNESS_UP:
            case BRIGHTNESS_DOWN:
            case BRIGHTNESS_30:
            case BRIGHTNESS_50:
            case BRIGHTNESS_70:
            case BRIGHTNESS_85:
            case BRIGHTNESS_100:
            case MEMC_OFF:
            case MEMC_1:
            case MEMC_2:
            case MEMC_3:
                return true;
            default:
                return false;
        }
    }
}
