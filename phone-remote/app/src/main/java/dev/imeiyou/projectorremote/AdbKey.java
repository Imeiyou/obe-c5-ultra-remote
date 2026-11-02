package dev.imeiyou.projectorremote;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;

/**
 * 手机自己的 ADB 密钥（RSA 2048），用于向投影仪的 adbd 证明身份。
 *
 * 与电脑上的 adb 相同的机制：
 *   第一次连接时把公钥发给投影仪，投影仪弹出"允许调试吗？"，选"始终允许"后记住这把公钥；
 *   以后每次连接，投影仪发来 20 字节的随机令牌，手机用私钥签名，投影仪用记住的公钥验证。
 *
 * 纯 Java，不依赖 Android 接口（方便在电脑上测试）。
 */
final class AdbKey {

    private static final int KEY_BITS = 2048;
    private static final int KEY_BYTES = KEY_BITS / 8;
    private static final int KEY_WORDS = KEY_BITS / 32;

    /** adbd 的令牌长度（Android 9 的 TOKEN_SIZE） */
    static final int TOKEN_SIZE = 20;

    /**
     * adbd 用 RSA_verify(NID_sha1, token, ...) 验证签名：把 20 字节令牌当作 SHA-1 摘要，
     * 期望的是 PKCS#1 v1.5 签名。这里手动拼好"填充 + SHA-1 DigestInfo 前缀"，再做一次原始 RSA 私钥运算。
     */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final KeyPair keyPair;

    private AdbKey(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    static AdbKey generate() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(new RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4));
        return new AdbKey(g.generateKeyPair());
    }

    /** 从 privateEncoded() / publicEncoded() 保存的字符串恢复。 */
    static AdbKey fromEncoded(String privateB64, String publicB64) throws GeneralSecurityException {
        KeyFactory f = KeyFactory.getInstance("RSA");
        PrivateKey priv = f.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateB64)));
        PublicKey pub = f.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicB64)));
        return new AdbKey(new KeyPair(pub, priv));
    }

    String privateEncoded() {
        return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    String publicEncoded() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    /** 对 adbd 发来的令牌签名。 */
    byte[] sign(byte[] token) throws GeneralSecurityException {
        if (token == null || token.length != TOKEN_SIZE) {
            throw new GeneralSecurityException("unexpected token length "
                    + (token == null ? -1 : token.length));
        }
        byte[] block = new byte[KEY_BYTES];
        int psLen = KEY_BYTES - 3 - SHA1_DIGEST_INFO.length - TOKEN_SIZE;
        block[0] = 0x00;
        block[1] = 0x01;
        Arrays.fill(block, 2, 2 + psLen, (byte) 0xFF);
        block[2 + psLen] = 0x00;
        System.arraycopy(SHA1_DIGEST_INFO, 0, block, 3 + psLen, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, block, KEY_BYTES - TOKEN_SIZE, TOKEN_SIZE);

        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, keyPair.getPrivate());
        return c.doFinal(block);
    }

    /**
     * 第一次授权时发给 adbd 的公钥：Android 自己的 RSAPublicKey 结构（小端），
     * Base64 编码后加一个空格和名字，以 NUL 结尾。
     *   uint32 len（以 32 位字计，2048 位为 64）
     *   uint32 n0inv = -1 / n[0] mod 2^32
     *   uint32 n[64]（低位字在前）
     *   uint32 rr[64] = (2^(2048))^2 mod n
     *   uint32 exponent
     */
    byte[] adbPublicKey(String name) {
        String text = Base64.getEncoder().encodeToString(publicKeyStruct()) + " " + name + "\u0000";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 投影仪"允许调试吗？"对话框里显示的指纹（公钥结构的 MD5），用来核对。 */
    String fingerprint() {
        try {
            byte[] md5 = MessageDigest.getInstance("MD5").digest(publicKeyStruct());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < md5.length; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format("%02X", md5[i] & 0xFF));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            return "?";
        }
    }

    private byte[] publicKeyStruct() {
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n);

        ByteBuffer b = ByteBuffer.allocate(4 + 4 + KEY_BYTES + KEY_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(KEY_WORDS);
        b.putInt(n0inv.intValue());
        putWords(b, n);
        putWords(b, rr);
        b.putInt(pub.getPublicExponent().intValue());
        return b.array();
    }

    /** 把大整数按 32 位字、低位在前写入。 */
    private static void putWords(ByteBuffer b, BigInteger v) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < KEY_WORDS; i++) {
            b.putInt(v.shiftRight(32 * i).and(mask).intValue());
        }
    }
}
