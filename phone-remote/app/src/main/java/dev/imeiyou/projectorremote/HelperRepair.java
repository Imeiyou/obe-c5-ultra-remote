package dev.imeiyou.projectorremote;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 通过 ADB 检查投影仪上的"对焦助手"是否在运行，没在运行就在后台把它复活。
 *
 * 背景：投影仪系统在切换前台应用时（例如切到 HDMI）会强行停止某个第三方应用，
 * 对焦助手被停止后，系统会顺带把它从无障碍服务清单里删掉，它自己无法恢复。
 *
 * 复活方式：用 shell 身份把它加回无障碍服务清单。系统看到清单变化会在后台重新启动服务，
 * 不打开任何界面，不影响正在播放的内容，也不会触发系统的那次清理检查。
 *
 * 纯 Java，不依赖 Android 接口（方便在电脑上测试）。
 */
final class HelperRepair {

    static final String PACKAGE = "dev.imeiyou.focushelper";
    static final String COMPONENT = PACKAGE + "/" + PACKAGE + ".FocusKeyService";
    private static final String COMPONENT_SHORT = PACKAGE + "/.FocusKeyService";

    private static final String KEY_LIST = "enabled_accessibility_services";
    private static final String KEY_MASTER = "accessibility_enabled";

    /** 写入清单后，等服务启动：每隔多久查一次、最多查几次 */
    private static final long VERIFY_INTERVAL_MS = 400;
    private static final int VERIFY_ATTEMPTS = 12;

    /** 投影仪开机不到这么久时，"在清单里但没运行"可能只是系统还没来得及启动它，不做重新绑定 */
    static final long BOOT_GRACE_SECONDS = 90;

    enum Outcome {
        /** 本来就在运行 */
        OK,
        /** 不在清单里（被强行停止），已加回并确认运行 */
        RESTORED,
        /** 在清单里但没运行，已移除再加回，确认运行 */
        REBOUND,
        /** 投影仪刚开机，服务可能还在启动，暂不处理 */
        BOOTING,
        /** 已尝试复活，但服务没有启动起来 */
        NOT_RUNNING,
        /** 投影仪上没有安装对焦助手 */
        NOT_INSTALLED
    }

    /** 投影仪上与对焦助手相关的状态 */
    static final class State {
        String list = "";
        boolean masterOn;
        boolean running;
        long uptimeSeconds = Long.MAX_VALUE;

        boolean listed() {
            return contains(list);
        }

        @Override
        public String toString() {
            return "listed=" + listed() + " master=" + masterOn + " running=" + running
                    + " uptime=" + uptimeSeconds + "s list=" + list;
        }
    }

    private HelperRepair() {}

    /** 只查询，不修改。 */
    static State query(AdbClient c) throws IOException {
        String out = c.shell(
                "echo \"L=$(settings get secure " + KEY_LIST + ")\";"
                        + "echo \"M=$(settings get secure " + KEY_MASTER + ")\";"
                        + "echo \"S=$(dumpsys activity services " + PACKAGE + " | grep -c FocusKeyService)\";"
                        + "echo \"U=$(cut -d. -f1 /proc/uptime)\"",
                8000);
        return parse(out);
    }

    static State parse(String out) {
        State s = new State();
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("L=")) {
                String v = line.substring(2).trim();
                s.list = "null".equals(v) ? "" : v;
            } else if (line.startsWith("M=")) {
                s.masterOn = "1".equals(line.substring(2).trim());
            } else if (line.startsWith("S=")) {
                s.running = parseLong(line.substring(2).trim(), 0) > 0;
            } else if (line.startsWith("U=")) {
                s.uptimeSeconds = parseLong(line.substring(2).trim(), Long.MAX_VALUE);
            }
        }
        return s;
    }

    /**
     * 检查，必要时复活。
     *
     * @param allowRebind 是否允许"在清单里但没运行"时移除再加回
     */
    static Outcome ensure(AdbClient c, boolean allowRebind) throws IOException {
        State s = query(c);
        if (s.listed() && s.masterOn && s.running) {
            return Outcome.OK;
        }

        String command;
        Outcome planned;
        if (!s.listed()) {
            command = putList(join(s.list, COMPONENT)) + ";" + putMaster();
            planned = Outcome.RESTORED;
        } else if (!s.masterOn) {
            command = putMaster();
            planned = Outcome.RESTORED;
        } else {
            if (!allowRebind || s.uptimeSeconds < BOOT_GRACE_SECONDS) {
                return Outcome.BOOTING;
            }
            String without = remove(s.list);
            command = putList(without) + ";sleep 1;" + putList(join(without, COMPONENT)) + ";" + putMaster();
            planned = Outcome.REBOUND;
        }

        c.shell(command, 10000);
        for (int i = 0; i < VERIFY_ATTEMPTS; i++) {
            sleep(VERIFY_INTERVAL_MS);
            if (isRunning(c)) {
                return planned;
            }
        }
        String path = c.shell("pm path " + PACKAGE, 8000);
        return path.contains("package:") ? Outcome.NOT_RUNNING : Outcome.NOT_INSTALLED;
    }

    /** 投影仪上是否装了对焦助手。 */
    static boolean isInstalled(AdbClient c) throws IOException {
        return c.shell("pm path " + PACKAGE, 8000).contains("package:");
    }

    private static boolean isRunning(AdbClient c) throws IOException {
        String out = c.shell("dumpsys activity services " + PACKAGE + " | grep -c FocusKeyService", 8000);
        return parseLong(out.trim(), 0) > 0;
    }

    private static String putList(String value) {
        checkSafe(value);
        return value.isEmpty()
                ? "settings delete secure " + KEY_LIST
                : "settings put secure " + KEY_LIST + " '" + value + "'";
    }

    private static String putMaster() {
        return "settings put secure " + KEY_MASTER + " 1";
    }

    /** 清单里的内容来自投影仪自己；防御一下，含有引号等字符时不拼进命令。 */
    private static void checkSafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
                    || ch == '.' || ch == '/' || ch == ':' || ch == '_' || ch == '$' || ch == '-';
            if (!ok) {
                throw new IllegalArgumentException("无障碍服务清单里有意外的字符：" + value);
            }
        }
    }

    static boolean contains(String list) {
        for (String item : split(list)) {
            if (isOurs(item)) {
                return true;
            }
        }
        return false;
    }

    /** 去掉对焦助手，保留其他服务和原有顺序。 */
    static String remove(String list) {
        List<String> keep = new ArrayList<>();
        for (String item : split(list)) {
            if (!isOurs(item)) {
                keep.add(item);
            }
        }
        return String.join(":", keep);
    }

    static String join(String list, String component) {
        return list == null || list.isEmpty() ? component : list + ":" + component;
    }

    private static boolean isOurs(String item) {
        return item.equalsIgnoreCase(COMPONENT) || item.equalsIgnoreCase(COMPONENT_SHORT);
    }

    private static List<String> split(String list) {
        List<String> items = new ArrayList<>();
        if (list == null) {
            return items;
        }
        for (String item : list.split(":")) {
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("被中断", e);
        }
    }
}
