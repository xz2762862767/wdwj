package com.mtstyle.fm.apk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

/**
 * APK Signature Scheme v2 签名器（自研，纯 Java 离线实现）。
 *
 * <p>流程：解析 ZIP 结构（剥离可能已存在的 APK Signing Block）→ 按 v2 规范计算
 * 「分块内容摘要」（CHUNKED_SHA256 / CHUNKED_SHA512）→ 组装 signed data（摘要 + 证书 + 附加属性）
 * → RSA PKCS#1 v1.5 签名 → 写入 APK Signing Block（ID 0x7109871a）→ 重写 EOCD 的 CD 偏移。
 *
 * <p>校验方式：Android SDK 的 {@code apksigner verify -v} 必须通过。
 */
public final class ApkSignerV2 {

    /** v2 签名块 ID。 */
    private static final int ID_V2 = 0x7109871a;
    /** 签名算法 ID：RSA PKCS#1 v1.5 + SHA-256（取 SignatureAlgorithm.getId()）。 */
    private static final int ALG_RSA_PKCS1_SHA256 = 0x0103;
    /** 分块大小 1MB。 */
    private static final int CHUNK_SIZE = 1024 * 1024;
    /** 条目内容零填充对齐（v2 规范以 4096 为对齐粒度）。 */
    private static final int ALIGNMENT = 4096;
    /** 签名块结束魔数。 */
    private static final byte[] MAGIC = {
            'A', 'P', 'K', ' ', 'S', 'i', 'g', ' ', 'B', 'l', 'o', 'c', 'k', ' ', '4', '2'};

    private ApkSignerV2() {
    }

    /** 对 APK 文件做 v2 签名，返回新 APK 字节。 */
    public static byte[] sign(byte[] apk, byte[] pkcs8Key, byte[] certDer) throws Exception {
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8Key));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(certDer));
        byte[] publicKey = cert.getPublicKey().getEncoded();

        int eocd = findEocd(apk);
        int cdSize = readU32(apk, eocd + 12);
        int cdOffset = readU32(apk, eocd + 16);
        int entriesEnd = cdOffset;
        // 已存在签名块：定位其起点并剥离（签名块位于 CD 之前，以魔数结尾）
        if (cdOffset >= 32 && matches(apk, cdOffset - MAGIC.length, MAGIC)) {
            long blockSize = readU64(apk, cdOffset - 24);
            long start = cdOffset - blockSize - 8;
            if (start >= 0 && start < cdOffset) {
                entriesEnd = (int) start;
            }
        }
        byte[] eocdBytes = new byte[apk.length - eocd];
        System.arraycopy(apk, eocd, eocdBytes, 0, eocdBytes.length);
        byte[] eocdForDigest = eocdBytes.clone();
        writeU32(eocdForDigest, 16, entriesEnd); // 摘要计算时 CD 偏移 = 签名块起始位置

        byte[] sha256 = contentDigest(apk, entriesEnd, cdOffset, cdSize, eocdForDigest, "SHA-256");

        // 摘要对：[u32 长度][u32 SignatureAlgorithm.getId()][u32 摘要长度][摘要]
        byte[] digestPairs = intPair(ALG_RSA_PKCS1_SHA256, sha256);
        // 证书列表：[u32 证书长度][DER]
        byte[] certificates = lengthPrefixed(certDer);
        // 附加属性：留空（仅 v2 签名）
        byte[] attributes = new byte[0];

        // signed data = 三个「长度前缀元素」直接拼接
        byte[] signedData = concat(lengthPrefixed(digestPairs), lengthPrefixed(certificates),
                lengthPrefixed(attributes));

        byte[] signature = rsaSign(key, "SHA256withRSA", signedData);
        byte[] signatures = intPair(ALG_RSA_PKCS1_SHA256, signature);
        byte[] signer = concat(lengthPrefixed(signedData), lengthPrefixed(signatures),
                lengthPrefixed(publicKey));
        // v2 值 = [u32 签名者序列长度][该序列]（序列本身由长度前缀的签名者拼接而成）
        byte[] v2Value = lengthPrefixed(lengthPrefixed(signer));

        byte[] block = signingBlock(new int[]{ID_V2}, new byte[][]{v2Value});

        ByteArrayOutputStream out = new ByteArrayOutputStream(apk.length + block.length);
        out.write(apk, 0, entriesEnd);
        out.write(block);
        out.write(apk, cdOffset, cdSize);
        byte[] newEocd = eocdBytes.clone();
        writeU32(newEocd, 16, entriesEnd + block.length);
        out.write(newEocd);
        return out.toByteArray();
    }

    /** 直接对文件签名并写出。 */
    public static void signFile(File input, File output, byte[] pkcs8Key, byte[] certDer) throws Exception {
        byte[] apk = Files.readAllBytes(input.toPath());
        byte[] signed = sign(apk, pkcs8Key, certDer);
        Files.write(output.toPath(), signed);
    }

    // ---------- 内容摘要 ----------

    /**
     * 计算 v2 内容摘要。段1=文件 [0, 签名块起点) 原始字节，段2=中央目录，段3=改写过 CD 偏移的 EOCD；
     * 每段各自按 1MB 切块（段边界处强制收尾），块摘要 0xa5+块长，总摘要 0x5a+块数。
     */
    private static byte[] contentDigest(byte[] apk, int entriesEnd, int cdOffset, int cdSize,
                                        byte[] eocd, String algorithm) throws Exception {
        ChunkedDigest digester = new ChunkedDigest(algorithm);
        digester.update(apk, 0, entriesEnd);
        digester.endSection();
        digester.update(apk, cdOffset, cdSize);
        digester.endSection();
        digester.update(eocd, 0, eocd.length);
        digester.endSection();
        return digester.finish();
    }

    /** 返回中央目录里各条目的 [本地头偏移, 本地头+数据总长]。 */
    private static List<int[]> centralDirectoryEntries(byte[] apk, int cdOffset, int cdSize) throws IOException {
        List<int[]> result = new ArrayList<>();
        int position = cdOffset;
        int end = cdOffset + cdSize;
        while (position + 46 <= end) {
            if (!matches(apk, position, new byte[]{0x50, 0x4b, 0x01, 0x02})) {
                break;
            }
            int compressedSize = readU32(apk, position + 20);
            int nameLength = readU16(apk, position + 28);
            int extraLength = readU16(apk, position + 30);
            int commentLength = readU16(apk, position + 32);
            int localOffset = readU32(apk, position + 42);
            int dataOffset = localHeaderDataOffset(apk, localOffset);
            int dataLength = compressedSize;
            // 本地头可能带 data descriptor（大小字段为 0）：用下一个条目/中央目录位置回退
            if (dataLength == 0 && dataOffset > 0) {
                dataLength = -1;
            }
            int localTotal;
            if (dataLength < 0) {
                int next = nextLocalHeader(apk, localOffset, position, entriesUsed(result));
                localTotal = next - localOffset;
            } else {
                localTotal = dataOffset - localOffset + dataLength;
            }
            result.add(new int[]{localOffset, localTotal});
            position += 46 + nameLength + extraLength + commentLength;
        }
        return result;
    }

    private static int entriesUsed(List<int[]> entries) {
        return entries.size();
    }

    /** 粗略估算下一个本地头位置（回退用）。 */
    private static int nextLocalHeader(byte[] apk, int localOffset, int cdOffset, int index) {
        int scan = localOffset + 30;
        while (scan + 4 <= cdOffset) {
            if (matches(apk, scan, new byte[]{0x50, 0x4b, 0x03, 0x04})
                    || matches(apk, scan, new byte[]{0x50, 0x4b, 0x01, 0x02})) {
                return scan;
            }
            scan++;
        }
        return cdOffset;
    }

    /** 本地文件头的数据起始位置。 */
    private static int localHeaderDataOffset(byte[] apk, int localOffset) {
        if (localOffset + 30 > apk.length || !matches(apk, localOffset, new byte[]{0x50, 0x4b, 0x03, 0x04})) {
            return -1;
        }
        int nameLength = readU16(apk, localOffset + 26);
        int extraLength = readU16(apk, localOffset + 28);
        return localOffset + 30 + nameLength + extraLength;
    }

    // ---------- 组装 ----------

    /** [u32 长度][内容]。 */
    private static byte[] lengthPrefixed(byte[] data) {
        byte[] out = new byte[4 + data.length];
        writeU32(out, 0, data.length);
        System.arraycopy(data, 0, out, 4, data.length);
        return out;
    }

    /** 直接拼接若干字节块。 */
    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        byte[] out = new byte[size];
        int position = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, position, part.length);
            position += part.length;
        }
        return out;
    }

    /** [u32 (8+值长)][u32 算法 ID][u32 值长][值] —— 摘要对 / 签名对。 */
    private static byte[] intPair(int algorithmId, byte[] value) {
        byte[] out = new byte[12 + value.length];
        writeU32(out, 0, 8 + value.length);
        writeU32(out, 4, algorithmId);
        writeU32(out, 8, value.length);
        System.arraycopy(value, 0, out, 12, value.length);
        return out;
    }

    /** APK Signing Block：u64 长度 + 若干 (u64 长度, u32 ID, 值) + u64 长度 + 魔数。 */
    private static byte[] signingBlock(int[] ids, byte[][] values) {
        int payload = 0;
        for (byte[] value : values) {
            payload += 8 + 4 + value.length;
        }
        long sizeField = payload + 8 + MAGIC.length;
        byte[] out = new byte[8 + payload + 8 + MAGIC.length];
        writeU64(out, 0, sizeField);
        int position = 8;
        for (int i = 0; i < ids.length; i++) {
            writeU64(out, position, 4 + values[i].length);
            writeU32(out, position + 8, ids[i]);
            System.arraycopy(values[i], 0, out, position + 12, values[i].length);
            position += 12 + values[i].length;
        }
        writeU64(out, position, sizeField);
        System.arraycopy(MAGIC, 0, out, position + 8, MAGIC.length);
        return out;
    }

    private static byte[] rsaSign(PrivateKey key, String algorithm, byte[] data) throws Exception {
        Signature signature = Signature.getInstance(algorithm);
        signature.initSign(key);
        signature.update(data);
        return signature.sign();
    }

    // ---------- 分块摘要 ----------

    /** v2 分块摘要：每块前缀 0xa5 + 块长，最终摘要前缀 0x5a + 块数。 */
    private static final class ChunkedDigest {
        private final String algorithm;
        private final MessageDigest chunkDigest;
        private final ByteArrayOutputStream chunkDigests = new ByteArrayOutputStream();
        private final byte[] buffer = new byte[CHUNK_SIZE];
        private int buffered;
        private int chunks;

        ChunkedDigest(String algorithm) throws Exception {
            this.algorithm = algorithm;
            this.chunkDigest = MessageDigest.getInstance(algorithm);
        }

        void update(byte[] data, int offset, int length) {
            int position = offset;
            int remaining = length;
            while (remaining > 0) {
                int take = Math.min(remaining, buffer.length - buffered);
                System.arraycopy(data, position, buffer, buffered, take);
                buffered += take;
                position += take;
                remaining -= take;
                if (buffered == buffer.length) {
                    flush();
                }
            }
        }

        void updateZeros(int count) {
            int remaining = count;
            while (remaining > 0) {
                int take = Math.min(remaining, buffer.length - buffered);
                buffered += take;
                remaining -= take;
                if (buffered == buffer.length) {
                    flush();
                }
            }
        }

        private void flush() {
            chunkDigest.reset();
            chunkDigest.update((byte) 0xa5);
            chunkDigest.update(u32Bytes(buffered));
            chunkDigest.update(buffer, 0, buffered);
            chunkDigests.write(chunkDigest.digest(), 0, chunkDigest.getDigestLength());
            chunks++;
            buffered = 0;
        }

        /** 段结束：强制收尾当前块（v2 分块按段重置）。 */
        void endSection() {
            if (buffered > 0) {
                flush();
            }
        }

        byte[] finish() throws Exception {
            if (buffered > 0) {
                flush();
            }
            MessageDigest finalDigest = MessageDigest.getInstance(algorithm);
            finalDigest.update((byte) 0x5a);
            finalDigest.update(u32Bytes(chunks));
            finalDigest.update(chunkDigests.toByteArray());
            return finalDigest.digest();
        }
    }

    // ---------- 基础读写 ----------

    private static int findEocd(byte[] data) throws IOException {
        int minimum = Math.max(0, data.length - 65557);
        for (int i = data.length - 22; i >= minimum; i--) {
            if (matches(data, i, new byte[]{0x50, 0x4b, 0x05, 0x06})) {
                int commentLength = readU16(data, i + 20);
                if (i + 22 + commentLength == data.length) {
                    return i;
                }
            }
        }
        throw new IOException("未找到 ZIP 结束记录（EOCD），可能不是有效 APK");
    }

    private static boolean matches(byte[] data, int offset, byte[] pattern) {
        if (offset < 0 || offset + pattern.length > data.length) {
            return false;
        }
        for (int i = 0; i < pattern.length; i++) {
            if (data[offset + i] != pattern[i]) {
                return false;
            }
        }
        return true;
    }

    private static int readU16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readU32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long readU64(byte[] data, int offset) {
        return (readU32(data, offset) & 0xFFFFFFFFL) | ((readU32(data, offset + 4) & 0xFFFFFFFFL) << 32);
    }

    private static void writeU32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static void writeU64(byte[] data, int offset, long value) {
        writeU32(data, offset, (int) value);
        writeU32(data, offset + 4, (int) (value >>> 32));
    }

    private static byte[] u32Bytes(int value) {
        byte[] out = new byte[4];
        writeU32(out, 0, value);
        return out;
    }
}
