package com.mtstyle.fm.apk;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * APK / ZIP 条目读写（纯 Java）：读取某条目内容、或替换某条目后另存新包。
 * 保持原条目的存储方式（STORED 保持不变，其余按 DEFLATE 重新压缩），
 * 并剥离 META-INF 下旧的 v1 签名文件（修改后原签名必然失效）。
 */
public final class ZipEdit {

    private ZipEdit() {
    }

    /**
     * 统一读入口：archive 本身是 ZIP 就取里面的 name 条目；
     * archive 不是 ZIP（用户从压缩包解压出来的单个 dex / xml / arsc）就整体读回。
     *
     * <p>内置查看器原本只会 {@link #readEntry}，把独立文件传进来必然抛 ZipException，
     * 表现为「打不开 / 找不到条目」。改成这个入口后，压缩包内条目和已解压文件都能开。</p>
     *
     * @return ZIP 内条目不存在时返回 null；独立文件返回其全部内容。
     */
    public static byte[] readEntryOrFile(File archive, String name) throws IOException {
        if (isZip(archive)) {
            return readEntry(archive, name);
        }
        return readAll(archive);
    }

    /** 是否为 ZIP（按文件头 PK\x03\x04 / PK\x05\x06 / PK\x07\x08 判断）。 */
    public static boolean isZip(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[4];
            if (in.read(head) != 4) {
                return false;
            }
            return head[0] == 'P' && head[1] == 'K'
                    && (head[2] == 3 || head[2] == 5 || head[2] == 7);
        } catch (IOException e) {
            return false;
        }
    }

    /** 整个文件读回，带一个宽松上限避免误开超大文件把内存吃光。 */
    public static byte[] readAll(File file) throws IOException {
        long size = file.length();
        if (size > 128L * 1024 * 1024) {
            throw new IOException("文件过大：" + size + " 字节");
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    (int) Math.min(Math.max(size, 1024L), 1L << 20));
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /** 读取指定条目内容；条目不存在返回 null。 */
    public static byte[] readEntry(File zipFile, String name) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        }
    }

    /**
     * 替换（或新增）source 中的若干条目，结果写入 target。
     *
     * @param replacements 条目名 → 新内容
     */
    public static void replaceEntries(File source, File target, Map<String, byte[]> replacements)
            throws IOException {
        byte[] buffer = new byte[16384];
        try (ZipFile zip = new ZipFile(source);
             ZipOutputStream out = new ZipOutputStream(
                     new BufferedOutputStream(new FileOutputStream(target)))) {
            Enumeration<? extends ZipEntry> iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                String name = entry.getName();
                if (isOldSignature(name)) {
                    continue;
                }
                byte[] replacement = replacements.get(name);
                int method = entry.getMethod();
                if (method != ZipEntry.STORED) {
                    method = ZipEntry.DEFLATED;
                }
                ZipEntry target1 = new ZipEntry(name);
                if (entry.getTime() > 0) {
                    target1.setTime(entry.getTime());
                }
                target1.setMethod(method);
                if (replacement != null) {
                    writeEntry(out, target1, replacement, method, buffer);
                } else if (entry.isDirectory()) {
                    out.putNextEntry(target1);
                    out.closeEntry();
                } else {
                    target1.setMethod(method);
                    // 非替换条目直接流式复制，避免整体读入内存
                    long size = entry.getSize();
                    long crc = entry.getCrc();
                    if (method == ZipEntry.STORED && size >= 0 && crc >= 0) {
                        target1.setSize(size);
                        target1.setCompressedSize(size);
                        target1.setCrc(crc);
                    }
                    out.putNextEntry(target1);
                    try (InputStream in = zip.getInputStream(entry)) {
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                        }
                    }
                    out.closeEntry();
                }
            }
            // 允许新增原包中不存在的条目
            for (Map.Entry<String, byte[]> item : replacements.entrySet()) {
                boolean exists = zip.getEntry(item.getKey()) != null;
                if (!exists) {
                    ZipEntry added = new ZipEntry(item.getKey());
                    added.setMethod(ZipEntry.DEFLATED);
                    writeEntry(out, added, item.getValue(), ZipEntry.DEFLATED, buffer);
                }
            }
        }
    }

    private static void writeEntry(ZipOutputStream out, ZipEntry entry, byte[] content, int method,
                                   byte[] buffer) throws IOException {
        if (method == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(content);
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            entry.setCrc(crc.getValue());
        }
        out.putNextEntry(entry);
        out.write(content);
        out.closeEntry();
    }

    /** 旧的 v1 签名文件（修改后必然失效，直接剥离以免误判）。 */
    private static boolean isOldSignature(String name) {
        String upper = name.toUpperCase(java.util.Locale.US);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        return upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")
                || upper.endsWith(".SF") || upper.equals("META-INF/MANIFEST.MF");
    }

    /** 写字符串到流（备用）。 */
    static void write(OutputStream out, byte[] data) throws IOException {
        out.write(data);
    }
}
