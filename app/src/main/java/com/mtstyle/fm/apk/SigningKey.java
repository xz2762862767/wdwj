package com.mtstyle.fm.apk;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 应用自有签名密钥：首次使用时在本机生成 RSA 2048 密钥对与自签证书，
 * 私钥仅保存在应用私有目录，不随源码分发（开源项目不内置任何密钥）。
 */
public final class SigningKey {

    private static final String DIR_NAME = "signkey";
    private static final String KEY_NAME = "sign.pk8";
    private static final String CERT_NAME = "sign.der";
    private static final String SUBJECT_ORG = "MT Style FM";
    private static final String SUBJECT_CN = "MT Style FM Self-Signed";
    private static final long VALIDITY_DAYS = 3650L;

    private static byte[] cachedKey;
    private static byte[] cachedCert;

    private SigningKey() {
    }

    /** PKCS#8 私钥 + X.509 证书 DER。 */
    public static final class Material {
        public final byte[] pkcs8;
        public final byte[] certDer;

        Material(byte[] pkcs8, byte[] certDer) {
            this.pkcs8 = pkcs8;
            this.certDer = certDer;
        }
    }

    /** 读取或生成签名密钥（首次生成在低端机上约 1~3 秒，请在后台线程调用）。 */
    public static synchronized Material load(Context context) throws Exception {
        if (cachedKey == null || cachedCert == null) {
            File dir = new File(context.getFilesDir(), DIR_NAME);
            File keyFile = new File(dir, KEY_NAME);
            File certFile = new File(dir, CERT_NAME);
            byte[] key = readFile(keyFile);
            byte[] cert = readFile(certFile);
            if (key == null || cert == null || key.length == 0 || cert.length == 0) {
                Material generated = generate();
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new Exception("无法创建密钥目录：" + dir.getAbsolutePath());
                }
                writeFile(keyFile, generated.pkcs8);
                writeFile(certFile, generated.certDer);
                key = generated.pkcs8;
                cert = generated.certDer;
            }
            cachedKey = key;
            cachedCert = cert;
        }
        return new Material(cachedKey, cachedCert);
    }

    /** 生成一套新的密钥与自签证书（不落盘，便于测试）。 */
    public static Material generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        byte[] key = pair.getPrivate().getEncoded();
        if (key == null) {
            throw new Exception("私钥导出失败");
        }
        return new Material(key, selfSigned(pair, SUBJECT_ORG, SUBJECT_CN));
    }

    // ---------- 自签证书（手写 DER，避免引入 BouncyCastle） ----------

    private static byte[] selfSigned(KeyPair pair, String organization, String commonName)
            throws Exception {
        BigInteger serial = new BigInteger(64, new SecureRandom()).abs().add(BigInteger.ONE);
        Date notBefore = new Date(System.currentTimeMillis() - 24L * 3600 * 1000);
        Date notAfter = new Date(System.currentTimeMillis() + VALIDITY_DAYS * 24L * 3600 * 1000);
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();

        byte[] algorithm = algorithmIdentifier();
        byte[] subject = rdnSequence(organization, commonName);
        byte[] validity = sequence(utcTime(notBefore), utcTime(notAfter));
        byte[] subjectPublicKey = sequence(
                integer(publicKey.getModulus()),
                integer(publicKey.getPublicExponent()));
        byte[] subjectPublicKeyInfo = sequence(rsaAlgorithmIdentifier(), bitString(subjectPublicKey));

        byte[] tbs = sequence(
                explicit(0, integer(BigInteger.valueOf(2))),
                integer(serial),
                algorithm,
                subject,
                validity,
                subject,
                subjectPublicKeyInfo);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        return sequence(tbs, algorithm, bitString(signer.sign()));
    }

    private static byte[] rdnSequence(String organization, String commonName) {
        return sequence(
                set(sequence(oid(2, 5, 4, 10), utf8String(organization))),
                set(sequence(oid(2, 5, 4, 3), utf8String(commonName))));
    }

    private static byte[] algorithmIdentifier() {
        // sha256WithRSAEncryption
        return sequence(oid(1, 2, 840, 113549, 1, 1, 11), new byte[]{0x05, 0x00});
    }

    private static byte[] rsaAlgorithmIdentifier() {
        // rsaEncryption
        return sequence(oid(1, 2, 840, 113549, 1, 1, 1), new byte[]{0x05, 0x00});
    }

    private static byte[] oid(int... arcs) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int value = arcs[i];
            int[] stack = new int[6];
            int count = 0;
            stack[count++] = value & 0x7f;
            value >>>= 7;
            while (value > 0) {
                stack[count++] = (value & 0x7f) | 0x80;
                value >>>= 7;
            }
            for (int j = count - 1; j >= 0; j--) {
                body.write(stack[j]);
            }
        }
        return tlv(0x06, body.toByteArray());
    }

    private static byte[] utf8String(String value) {
        return tlv(0x0c, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] utcTime(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return tlv(0x17, format.format(date).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] integer(BigInteger value) {
        return tlv(0x02, value.toByteArray());
    }

    private static byte[] bitString(byte[] data) {
        byte[] content = new byte[data.length + 1];
        content[0] = 0;
        System.arraycopy(data, 0, content, 1, data.length);
        return tlv(0x03, content);
    }

    private static byte[] explicit(int index, byte[] data) {
        return tlv(0xa0 | index, data);
    }

    private static byte[] sequence(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            int bytes = 0;
            int value = length;
            while (value > 0) {
                bytes++;
                value >>>= 8;
            }
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write((length >>> (8 * i)) & 0xff);
            }
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    // ---------- 文件读写 ----------

    private static byte[] readFile(File file) {
        if (!file.isFile()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFile(File file, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(true, true);
    }
}
