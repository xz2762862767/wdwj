package com.mtstyle.fm.apk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * APK 签名分析（纯 Java，无 Android 依赖）：
 * 检测 v1（META-INF/*.RSA|DSA|EC + *.SF）与 v2/v3（APK Signing Block）签名方案，
 * 并给出签名证书的 MD5 / SHA-1 / SHA-256 以及 subject、issuer、有效期、序列号。
 */
public final class ApkSign {

    /** 签名方案与证书信息。 */
    public static class Info {
        public boolean v1;
        public boolean v2;
        public boolean v3;
        public final List<Cert> certs = new ArrayList<>();

        public String schemes() {
            StringBuilder builder = new StringBuilder();
            if (v1) {
                builder.append("v1");
            }
            if (v2) {
                builder.append(builder.length() > 0 ? ", v2" : "v2");
            }
            if (v3) {
                builder.append(builder.length() > 0 ? ", v3" : "v3");
            }
            return builder.length() == 0 ? "未检测到签名方案" : builder.toString();
        }
    }

    /** 单张签名证书。 */
    public static class Cert {
        public String subject;
        public String issuer;
        public String serial;
        public String notBefore;
        public String notAfter;
        public String algorithm;
        public String keyAlgorithm;
        public String md5;
        public String sha1;
        public String sha256;
        public boolean expired;
    }

    private static final long MAGIC_LO = 0x20676953204b5041L; // "APK Sig "
    private static final long MAGIC_HI = 0x3234206b636f6c42L; // "Block 42"
    private static final int BLOCK_ID_V2 = 0x7109871a;
    private static final int BLOCK_ID_V3 = 0xf05368c0;

    private ApkSign() {
    }

    public static Info read(File apk) throws Exception {
        Info info = new Info();
        try (ZipFile zip = new ZipFile(apk)) {
            readV1(zip, info);
        }
        readV2V3(apk, info);
        return info;
    }

    private static void readV1(ZipFile zip, Info info) {
        List<String> blocks = new ArrayList<>();
        boolean hasSf = false;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName().toUpperCase(Locale.US);
            if (!name.startsWith("META-INF/")) {
                continue;
            }
            if (name.endsWith(".SF")) {
                hasSf = true;
            } else if (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC")) {
                blocks.add(entry.getName());
            }
        }
        info.v1 = hasSf && !blocks.isEmpty();
        for (String name : blocks) {
            try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
                parsePkcs7(in, info);
            } catch (Exception ignored) {
            }
        }
    }

    /** 解析 PKCS#7 签名块中的 X.509 证书。 */
    private static void parsePkcs7(InputStream in, Info info) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certificates =
                    factory.generateCertificates(new ByteArrayInputStream(readAll(in)));
            for (Certificate certificate : certificates) {
                if (certificate instanceof X509Certificate) {
                    info.certs.add(toCert((X509Certificate) certificate));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static Cert toCert(X509Certificate certificate) throws Exception {
        Cert cert = new Cert();
        cert.subject = certificate.getSubjectX500Principal().getName();
        cert.issuer = certificate.getIssuerX500Principal().getName();
        cert.serial = certificate.getSerialNumber().toString(16).toUpperCase(Locale.US);
        cert.notBefore = String.valueOf(certificate.getNotBefore());
        cert.notAfter = String.valueOf(certificate.getNotAfter());
        cert.algorithm = certificate.getSigAlgName();
        cert.keyAlgorithm = certificate.getPublicKey().getAlgorithm();
        byte[] encoded = certificate.getEncoded();
        cert.md5 = digest("MD5", encoded);
        cert.sha1 = digest("SHA-1", encoded);
        cert.sha256 = digest("SHA-256", encoded);
        try {
            certificate.checkValidity();
            cert.expired = false;
        } catch (Exception e) {
            cert.expired = true;
        }
        return cert;
    }

    /** 检测 APK Signing Block 中的 v2 / v3 签名块。 */
    private static void readV2V3(File apk, Info info) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(apk, "r");
            long length = raf.length();
            int tailSize = (int) Math.min(length, 64 * 1024);
            byte[] tail = new byte[tailSize];
            raf.seek(length - tailSize);
            raf.readFully(tail);
            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if ((tail[i] & 0xFF) == 0x50 && (tail[i + 1] & 0xFF) == 0x4B
                        && (tail[i + 2] & 0xFF) == 0x05 && (tail[i + 3] & 0xFF) == 0x06) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) {
                return;
            }
            ByteBuffer eocdBuffer = ByteBuffer.wrap(tail, eocd, 22).order(ByteOrder.LITTLE_ENDIAN);
            eocdBuffer.position(eocd + 16);
            long cdOffset = eocdBuffer.getInt() & 0xFFFFFFFFL;
            if (cdOffset < 24) {
                return;
            }
            byte[] header = new byte[24];
            raf.seek(cdOffset - 24);
            raf.readFully(header);
            ByteBuffer headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            long sizeInFooter = headerBuffer.getLong();
            if (headerBuffer.getLong() != MAGIC_LO || headerBuffer.getLong() != MAGIC_HI) {
                return;
            }
            long blockStart = cdOffset - sizeInFooter - 8;
            long blockSize = sizeInFooter + 8;
            if (blockStart < 0 || blockSize > 32L * 1024 * 1024) {
                return;
            }
            byte[] block = new byte[(int) blockSize];
            raf.seek(blockStart);
            raf.readFully(block);
            ByteBuffer buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
            buffer.position(8);
            while (buffer.remaining() > 8) {
                long pairSize = buffer.getLong();
                if (pairSize < 4 || pairSize - 4 > buffer.remaining()) {
                    break;
                }
                int id = buffer.getInt();
                int valueSize = (int) (pairSize - 4);
                if (id == BLOCK_ID_V2) {
                    info.v2 = true;
                } else if (id == BLOCK_ID_V3) {
                    info.v3 = true;
                }
                if ((id == BLOCK_ID_V2 || id == BLOCK_ID_V3) && valueSize > 0
                        && valueSize <= buffer.remaining() && info.certs.isEmpty()) {
                    byte[] value = new byte[valueSize];
                    buffer.get(value);
                    extractCerts(value, info);
                } else {
                    buffer.position(buffer.position() + valueSize);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 从 v2/v3 签名块中提取签名证书（DER），失败则静默忽略。
     * 结构：signers(长度前缀序列) → signer → signedData(长度前缀) → digests、certificates、…
     */
    private static void extractCerts(byte[] value, Info info) {
        try {
            int position = 0;
            if (value.length < 4) {
                return;
            }
            int signersLength = readU32(value, position);
            position += 4;
            int signersEnd = Math.min(value.length, position + signersLength);
            while (position + 4 <= signersEnd) {
                int signerLength = readU32(value, position);
                position += 4;
                int signerEnd = Math.min(signersEnd, position + signerLength);
                if (position + 4 > signerEnd) {
                    break;
                }
                int signedDataLength = readU32(value, position);
                position += 4;
                int signedDataEnd = Math.min(signerEnd, position + signedDataLength);
                // 跳过 digests
                if (position + 4 <= signedDataEnd) {
                    int digestsLength = readU32(value, position);
                    position += 4 + digestsLength;
                }
                // certificates：长度前缀序列，每项为 DER 证书
                if (position + 4 <= signedDataEnd) {
                    int certsLength = readU32(value, position);
                    position += 4;
                    int certsEnd = Math.min(signedDataEnd, position + certsLength);
                    CertificateFactory factory = CertificateFactory.getInstance("X.509");
                    while (position + 4 <= certsEnd) {
                        int certLength = readU32(value, position);
                        position += 4;
                        if (certLength <= 0 || position + certLength > certsEnd) {
                            break;
                        }
                        byte[] der = new byte[certLength];
                        System.arraycopy(value, position, der, 0, certLength);
                        position += certLength;
                        try {
                            Certificate certificate = factory.generateCertificate(
                                    new ByteArrayInputStream(der));
                            if (certificate instanceof X509Certificate) {
                                info.certs.add(toCert((X509Certificate) certificate));
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                position = signerEnd;
            }
        } catch (Exception ignored) {
        }
    }

    private static int readU32(byte[] data, int position) {
        if (position + 4 > data.length) {
            return 0;
        }
        return (data[position] & 0xFF) | ((data[position + 1] & 0xFF) << 8)
                | ((data[position + 2] & 0xFF) << 16) | ((data[position + 3] & 0xFF) << 24);
    }

    private static String digest(String algorithm, byte[] data) {
        try {
            byte[] bytes = MessageDigest.getInstance(algorithm).digest(data);
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString().toUpperCase(Locale.US);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** 生成用于展示的文本。 */
    public static String describe(Info info) {
        StringBuilder builder = new StringBuilder();
        builder.append("签名方案：").append(info.schemes()).append('\n');
        if (info.certs.isEmpty()) {
            builder.append("\n未解析到签名证书（可能只有 v2/v3 签名）。");
            return builder.toString();
        }
        int index = 1;
        for (Cert cert : info.certs) {
            builder.append("\n证书 ").append(index++).append('\n');
            builder.append("主题：").append(cert.subject).append('\n');
            builder.append("颁发者：").append(cert.issuer).append('\n');
            builder.append("序列号：").append(cert.serial).append('\n');
            builder.append("有效期：").append(cert.notBefore).append(" ~ ").append(cert.notAfter)
                    .append(cert.expired ? "（已过期）" : "").append('\n');
            builder.append("签名算法：").append(cert.algorithm)
                    .append("（公钥 ").append(cert.keyAlgorithm).append("）\n");
            builder.append("MD5：    ").append(cert.md5).append('\n');
            builder.append("SHA-1：  ").append(cert.sha1).append('\n');
            builder.append("SHA-256：").append(cert.sha256).append('\n');
        }
        return builder.toString();
    }
}
