package dev.imeiyou.projectorremote;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * 最小的 ADB 客户端：通过网络连接投影仪的 adbd（端口 5555），以 shell 身份执行命令。
 *
 * 只实现用得到的部分：连接与认证（CNXN / AUTH）、打开 shell 流（OPEN）、
 * 接收输出（WRTE / OKAY）、流结束（CLSE）。协议见 AOSP system/core/adb/protocol.txt。
 *
 * 纯 Java，不依赖 Android 接口（方便在电脑上测试）。不是线程安全的，一个连接只在一个线程里用。
 */
final class AdbClient implements Closeable {

    private static final int CMD_CNXN = 0x4e584e43;
    private static final int CMD_AUTH = 0x48545541;
    private static final int CMD_OPEN = 0x4e45504f;
    private static final int CMD_OKAY = 0x59414b4f;
    private static final int CMD_CLSE = 0x45534c43;
    private static final int CMD_WRTE = 0x45545257;

    /** 协议版本：0x01000000 要求校验和，所有版本的 adbd 都接受 */
    private static final int VERSION = 0x01000000;
    /** 告诉 adbd 单个消息最多多大；adbd 会取双方的较小值 */
    private static final int MAX_DATA = 4096;
    /** 防御：单个消息超过这个大小视为协议错误 */
    private static final int MAX_ACCEPTED_PAYLOAD = 1024 * 1024;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    /** 投影仪不认识这把密钥（还没在投影仪上点"允许"） */
    static final class NotAuthorizedException extends IOException {
        NotAuthorizedException(String message) {
            super(message);
        }
    }

    private static final class Message {
        final int command;
        final int arg0;
        final int arg1;
        final byte[] data;

        Message(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private AdbClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /**
     * 连接并完成认证。
     *
     * @param sendPublicKey 签名不被接受时，是否把公钥发过去请求授权（投影仪会弹出"允许调试吗？"）。
     *                      只在用户主动点"授权"时为 true；平时为 false，不认识就直接放弃，不打扰投影仪。
     * @param confirmTimeoutMs 发出公钥后，等待用户在投影仪上确认的时间
     */
    static AdbClient connect(String host, int port, AdbKey key, String keyName,
                             int connectTimeoutMs, int ioTimeoutMs,
                             boolean sendPublicKey, int confirmTimeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setSoTimeout(ioTimeoutMs);
            AdbClient c = new AdbClient(s);
            c.handshake(key, keyName, sendPublicKey, ioTimeoutMs, confirmTimeoutMs);
            return c;
        } catch (IOException | RuntimeException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 已经在处理更重要的错误
            }
            throw e;
        }
    }

    private void handshake(AdbKey key, String keyName, boolean sendPublicKey,
                           int ioTimeoutMs, int confirmTimeoutMs) throws IOException {
        write(CMD_CNXN, VERSION, MAX_DATA, "host::\u0000".getBytes(StandardCharsets.US_ASCII));
        boolean sentSignature = false;
        boolean sentPublicKey = false;
        while (true) {
            Message m;
            try {
                m = read();
            } catch (SocketTimeoutException e) {
                if (sentPublicKey) {
                    throw new NotAuthorizedException("等待投影仪上确认授权超时");
                }
                throw e;
            } catch (java.io.EOFException e) {
                if (sentPublicKey) {
                    throw new NotAuthorizedException("投影仪上没有允许调试");
                }
                throw e;
            }
            if (m.command == CMD_CNXN) {
                socket.setSoTimeout(ioTimeoutMs);
                return;
            }
            if (m.command != CMD_AUTH || m.arg0 != AUTH_TOKEN) {
                continue;
            }
            if (!sentSignature) {
                byte[] signature;
                try {
                    signature = key.sign(m.data);
                } catch (GeneralSecurityException e) {
                    throw new IOException("签名失败：" + e.getMessage(), e);
                }
                write(CMD_AUTH, AUTH_SIGNATURE, 0, signature);
                sentSignature = true;
            } else if (sendPublicKey && !sentPublicKey) {
                write(CMD_AUTH, AUTH_RSAPUBLICKEY, 0, key.adbPublicKey(keyName));
                sentPublicKey = true;
                // 接下来要等用户在投影仪上点"允许"
                socket.setSoTimeout(confirmTimeoutMs);
            } else {
                throw new NotAuthorizedException("投影仪还没有授权这台手机");
            }
        }
    }

    /**
     * 以 shell 身份执行一条命令，返回它的全部输出（标准输出和错误输出混在一起）。
     *
     * @param timeoutMs 整条命令的最长时间
     */
    String shell(String command, int timeoutMs) throws IOException {
        int localId = nextLocalId++;
        long deadline = System.currentTimeMillis() + timeoutMs;
        write(CMD_OPEN, localId, 0, ("shell:" + command + "\u0000").getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean opened = false;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw new SocketTimeoutException("命令超时：" + command);
            }
            Message m = read();
            // 发给我们这个流的消息，arg1 是我们的本地编号；其他的（例如上一条命令迟到的消息）忽略
            if (m.arg1 != localId) {
                continue;
            }
            switch (m.command) {
                case CMD_OKAY:
                    opened = true;
                    break;
                case CMD_WRTE:
                    output.write(m.data, 0, m.data.length);
                    // 确认收到，adbd 才会发下一段
                    write(CMD_OKAY, localId, m.arg0, new byte[0]);
                    break;
                case CMD_CLSE:
                    if (!opened && output.size() == 0) {
                        throw new IOException("投影仪拒绝了 shell 请求");
                    }
                    return output.toString("UTF-8");
                default:
                    break;
            }
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }

    private void write(int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command);
        b.putInt(arg0);
        b.putInt(arg1);
        b.putInt(data.length);
        b.putInt(checksum(data));
        b.putInt(~command);
        b.put(data);
        out.write(b.array());
        out.flush();
    }

    private Message read() throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = b.getInt();
        int arg0 = b.getInt();
        int arg1 = b.getInt();
        int length = b.getInt();
        b.getInt(); // 校验和：TCP 已经保证数据完整，不再检查
        int magic = b.getInt();
        if (magic != ~command) {
            throw new IOException("ADB 协议错误：消息头校验失败");
        }
        if (length < 0 || length > MAX_ACCEPTED_PAYLOAD) {
            throw new IOException("ADB 协议错误：消息长度 " + length);
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return new Message(command, arg0, arg1, data);
    }

    private static int checksum(byte[] data) {
        int sum = 0;
        for (byte x : data) {
            sum += x & 0xFF;
        }
        return sum;
    }
}
