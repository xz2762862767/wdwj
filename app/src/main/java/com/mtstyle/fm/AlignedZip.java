package com.mtstyle.fm;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 自带的 zip 重打包器，负责「替换若干条目 + 按 Android 要求对齐」。
 *
 * <p>Android 上没有 zipalign 可执行文件，而 APK Signature Scheme v2 要求所有
 * <b>未压缩</b>条目在文件里 4 字节对齐，Android 12+ 还要求 {@code .so} 按 4096 字节
 * 页对齐（否则安装时报 {@code INSTALL_PARSE_FAILED_NOT_APK} / 无法 mmap）。
 * 另外 targetSdk 30+ 要求 {@code resources.arsc} 必须未压缩且 4 字节对齐。</p>
 *
 * <p>对齐做法与官方 zipalign 相同：把填充塞进该条目的 <b>local header extra field</b>
 * （id 用 {@code 0xd935}），这样压缩数据起始偏移正好落在对齐边界上。</p>
 */
final class AlignedZip {

    /** .so 页对齐（Android 12+ 要求，等价于 zipalign -p 4）。 */
    private static final int ALIGN_SO = 4096;
    /** 其他未压缩条目的对齐（v2 签名要求）。 */
    private static final int ALIGN_DEFAULT = 4;
    /** 与官方 zipalign 一致的 extra field id。 */
    private static final int EXTRA_ID_ALIGN = 0xd935;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private AlignedZip() {
    }

    /**
     * 把 {@code src} 重打包成 {@code dst}：丢掉 META-INF（重新签名会重建），
     * 用 {@code replace} 里的文件替换同名条目，其余条目原样复制。
     */
    static void write(File src, Map<String, File> replace, File dst) throws IOException {
        ZipFile zf = new ZipFile(src);
        File tmp = File.createTempFile("zipalign", ".tmp", dst.getParentFile());
        try {
            CountingOutputStream out =
                    new CountingOutputStream(new BufferedOutputStream(new FileOutputStream(dst), 1 << 16));
            List<Entry> index = new ArrayList<>();
            Set<String> done = new HashSet<>();

            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name.startsWith("META-INF/") || done.contains(name) || replace.containsKey(name)) {
                    continue;   // META-INF 重签名会重建；被替换的条目稍后单独写
                }
                done.add(name);
                writeEntry(out, index, name, e.isDirectory() ? null : zf.getInputStream(e),
                        e.isDirectory(), tmp, dst.getParentFile());
            }
            // 被替换 / 新增的条目
            for (Map.Entry<String, File> e : replace.entrySet()) {
                if (done.contains(e.getKey())) {
                    continue;
                }
                done.add(e.getKey());
                writeEntry(out, index, e.getKey(), new FileInputStream(e.getValue()), false, tmp,
                        dst.getParentFile());
            }

            writeCentralDirectory(out, index);
            writeEndOfCentralDirectory(out, index);
            out.close();
        } finally {
            zf.close();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** 写一条本地条目：数据先落到临时文件，好把 crc/大小填进 local header（不用 data descriptor）。 */
    private static void writeEntry(CountingOutputStream out, List<Entry> index, String name,
                                   InputStream data, boolean dir, File tmp, File tmpDir)
            throws IOException {
        boolean store = dir || shouldStore(name);
        int align = store ? alignOf(name) : 0;

        long crc;
        long rawSize;
        long compSize;
        FileOutputStream tos = new FileOutputStream(tmp);
        try {
            CRC32 crc32 = new CRC32();
            byte[] buf = new byte[1 << 16];
            if (dir) {
                crc = 0;
                rawSize = 0;
                compSize = 0;
            } else if (store) {
                long n = 0;
                int r;
                while ((r = data.read(buf)) > 0) {
                    tos.write(buf, 0, r);
                    crc32.update(buf, 0, r);
                    n += r;
                }
                crc = crc32.getValue();
                rawSize = n;
                compSize = n;
            } else {
                Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
                byte[] obuf = new byte[1 << 16];
                long n = 0;
                long c = 0;
                int r;
                while ((r = data.read(buf)) > 0) {
                    crc32.update(buf, 0, r);
                    n += r;
                    def.setInput(buf, 0, r);
                    while (!def.needsInput()) {
                        int k = def.deflate(obuf);
                        tos.write(obuf, 0, k);
                        c += k;
                    }
                }
                def.finish();
                while (!def.finished()) {
                    int k = def.deflate(obuf);
                    if (k <= 0) {
                        break;
                    }
                    tos.write(obuf, 0, k);
                    c += k;
                }
                def.end();
                crc = crc32.getValue();
                rawSize = n;
                compSize = c;
            }
        } finally {
            tos.close();
            if (data != null) {
                data.close();
            }
        }

        byte[] nameBytes = name.getBytes(UTF8);
        byte[] extra = null;
        int extraLen = 0;
        long localOffset = out.count();
        if (align > 0) {
            // dataOffset = localOffset + 30(固定头) + nameLen + extraLen，反推需要多少填充
            int base = (int) ((localOffset + 30 + nameBytes.length + 4) % align);
            int pad = (align - base) % align;
            extra = new byte[4 + pad];
            extra[0] = (byte) (EXTRA_ID_ALIGN & 0xff);
            extra[1] = (byte) ((EXTRA_ID_ALIGN >> 8) & 0xff);
            extra[2] = (byte) (pad & 0xff);
            extra[3] = (byte) ((pad >> 8) & 0xff);
            extraLen = extra.length;
        }
        int method = store ? ZipEntry.STORED : ZipEntry.DEFLATED;

        // ---- local file header ----
        writeInt(out, 0x04034b50);
        writeShort(out, 20);                 // version needed
        writeShort(out, 0x800);              // flags：bit11=文件名按 UTF-8 解释（APK 里可能有非 ASCII 名）
        writeShort(out, method);
        writeShort(out, 0);                  // time
        writeShort(out, 0x21);               // date（1980-01-01，保持确定性）
        writeInt(out, (int) crc);
        writeInt(out, (int) compSize);
        writeInt(out, (int) rawSize);
        writeShort(out, nameBytes.length);
        writeShort(out, extraLen);
        out.write(nameBytes);
        if (extra != null) {
            out.write(extra);
        }
        long dataOffset = out.count();

        // ---- data ----
        if (!dir) {
            copy(new FileInputStream(tmp), out);
        }
        // 对齐自检：未压缩条目必须落在边界上，否则 v2 签名会失败
        if (align > 0 && (dataOffset % align) != 0) {
            throw new IOException("对齐失败：" + name + " 偏移 " + dataOffset + " 不是 " + align + " 的倍数");
        }

        index.add(new Entry(name, method, crc, compSize, rawSize, localOffset,
                nameBytes.length, extraLen));
    }

    private static void writeCentralDirectory(CountingOutputStream out, List<Entry> index)
            throws IOException {
        long start = out.count();
        for (Entry e : index) {
            byte[] nameBytes = e.name.getBytes(UTF8);
            writeInt(out, 0x02014b50);
            writeShort(out, 20);             // version made by
            writeShort(out, 20);             // version needed
            writeShort(out, 0);              // flags
            writeShort(out, e.method);
            writeShort(out, 0);              // time
            writeShort(out, 0x21);           // date
            writeInt(out, (int) e.crc);
            writeInt(out, (int) e.compSize);
            writeInt(out, (int) e.rawSize);
            writeShort(out, nameBytes.length);
            writeShort(out, 0);              // extra len
            writeShort(out, 0);              // comment len
            writeShort(out, 0);              // disk number
            writeShort(out, 0);              // internal attrs
            writeInt(out, 0);                // external attrs
            writeInt(out, (int) e.localOffset);
            out.write(nameBytes);
        }
        cdOffset = start;
        cdSize = out.count() - start;
    }

    private static void writeEndOfCentralDirectory(CountingOutputStream out, List<Entry> index)
            throws IOException {
        writeInt(out, 0x06054b50);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, index.size());
        writeShort(out, index.size());
        writeInt(out, (int) cdSize);
        writeInt(out, (int) cdOffset);
        writeShort(out, 0);
    }

    private static long cdOffset;
    private static long cdSize;

    private static boolean shouldStore(String name) {
        return name.endsWith(".so") || "resources.arsc".equals(name);
    }

    private static int alignOf(String name) {
        return name.endsWith(".so") ? ALIGN_SO : ALIGN_DEFAULT;
    }

    private static void copy(InputStream is, OutputStream os) throws IOException {
        try {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = is.read(buf)) > 0) {
                os.write(buf, 0, r);
            }
        } finally {
            is.close();
        }
    }

    private static void writeShort(OutputStream os, int v) throws IOException {
        os.write(v & 0xff);
        os.write((v >>> 8) & 0xff);
    }

    private static void writeInt(OutputStream os, int v) throws IOException {
        os.write(v & 0xff);
        os.write((v >>> 8) & 0xff);
        os.write((v >>> 16) & 0xff);
        os.write((v >>> 24) & 0xff);
    }

    private static final class Entry {
        final String name;
        final int method;
        final long crc;
        final long compSize;
        final long rawSize;
        final long localOffset;
        final int nameLen;
        final int extraLen;

        Entry(String name, int method, long crc, long compSize, long rawSize, long localOffset,
              int nameLen, int extraLen) {
            this.name = name;
            this.method = method;
            this.crc = crc;
            this.compSize = compSize;
            this.rawSize = rawSize;
            this.localOffset = localOffset;
            this.nameLen = nameLen;
            this.extraLen = extraLen;
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream out;
        private long count;

        CountingOutputStream(OutputStream out) {
            this.out = out;
        }

        long count() {
            return count;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }
}
