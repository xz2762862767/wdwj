package com.mtstyle.fm.apk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 极简 AXML（二进制 AndroidManifest.xml）编辑器：只干一件事 ——
 * 给 application 节点设置 android:name 属性。
 *
 * <p>为什么必须手写：目标 APK 的 manifest 若没有写 android:name（用系统默认
 * Application），则四大组件没有任何可注入的入口，唯一解法是把我们自己的
 * Application 类名写进 manifest。fm 里现有的 Axml 只能解码，没有编码能力。</p>
 *
 * <p>实现要点：</p>
 * <ol>
 * <li>字符串池整体重建，新增字符串一律追加到池尾，这样原有的 resourceMap
 *     前 N 项语义完全不变；</li>
 * <li>新属性名 "name" 追加到池尾后，把 resourceMap 扩展到覆盖它，并把该项写成
 *     0x01010003（android:name 的资源 ID）。framework 是按资源 ID 识别属性的，
 *     缺了这一步属性会被当成未知属性丢掉；</li>
 * <li>application 的 START_ELEMENT chunk 只在末尾追加一个 20 字节属性并同步
 *     chunk size 与 attributeCount，不改动其它任何字节；</li>
 * <li>其余 chunk 原样搬运，因此不会破坏命名空间、CDATA、注释等结构。</li>
 * </ol>
 */
public final class AxmlEditor {

    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_XML = 0x0003;
    private static final int CHUNK_START_ELEMENT = 0x0102;
    private static final int CHUNK_RESOURCE_MAP = 0x0180;

    /** android:name 的资源 ID。 */
    private static final int ATTR_ID_NAME = 0x01010003;
    /** 属性值类型：字符串。 */
    private static final int TYPE_STRING = 0x03;
    /** 单个属性的固定长度。 */
    private static final int ATTR_SIZE = 0x14;

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";

    private AxmlEditor() {
    }

    /** 读 APK 里的 AndroidManifest.xml，把其中 application/android:name 改成 className 后写出。 */
    public static void setApplicationName(File apk, File out, String className) throws IOException {
        byte[] data = readEntry(apk, "AndroidManifest.xml");
        byte[] edited = setApplicationName(data, className);
        FileOutputStream os = new FileOutputStream(out);
        try {
            os.write(edited);
        } finally {
            os.close();
        }
    }

    /** 纯字节版本，便于单独验证。 */
    public static byte[] setApplicationName(byte[] data, String className) throws IOException {
        if (className == null || className.isEmpty()) {
            throw new IOException("类名为空");
        }
        if (data.length < 16 || u2(data, 0) != CHUNK_XML) {
            throw new IOException("不是二进制 AndroidManifest.xml");
        }

        // ---------- 1. 切分顶层 chunk ----------
        int headerSize = u2(data, 2);
        int q = (headerSize >= 8 && headerSize <= data.length) ? headerSize : 8;
        int poolOff = -1;
        int poolSize = 0;
        int resMapOff = -1;
        int resMapSize = 0;
        List<int[]> nodes = new ArrayList<int[]>();
        while (q + 8 <= data.length) {
            int type = u2(data, q);
            int size = u4(data, q + 4);
            if (size <= 0 || q + size > data.length) {
                break;
            }
            if (type == CHUNK_STRING_POOL) {
                poolOff = q;
                poolSize = size;
            } else if (type == CHUNK_RESOURCE_MAP) {
                resMapOff = q;
                resMapSize = size;
            } else {
                nodes.add(new int[]{type, q, size});
            }
            q += size;
        }
        if (poolOff < 0 || poolSize <= 0) {
            throw new IOException("manifest 缺少字符串池");
        }

        // ---------- 2. 解析字符串池 ----------
        int stringCount = u4(data, poolOff + 8);
        int flags = u4(data, poolOff + 16);
        boolean utf8 = (flags & 0x100) != 0;
        int stringsStart = u4(data, poolOff + 20);
        if (stringCount <= 0 || stringCount > 1000000 || stringsStart <= 0) {
            throw new IOException("字符串池异常");
        }
        List<String> strings = new ArrayList<String>(stringCount + 2);
        for (int i = 0; i < stringCount; i++) {
            int off = poolOff + stringsStart + u4(data, poolOff + 28 + i * 4);
            int[] cur = new int[]{off};
            strings.add(utf8 ? readUtf8(data, cur) : readUtf16(data, cur));
        }

        // ---------- 3. 追加需要的字符串（一律追加到池尾，保持前 N 项索引不变） ----------
        int nsIdx = strings.indexOf(NS_ANDROID);
        if (nsIdx < 0) {
            nsIdx = strings.size();
            strings.add(NS_ANDROID);
        }
        int nameIdx = strings.size();
        strings.add("name");
        int valueIdx = strings.size();
        strings.add(className);
        byte[] pool = buildPool(strings, utf8);

        // ---------- 4. 让 "name" 带上 android:name 的资源 ID ----------
        int rmCount = resMapOff >= 0 ? (resMapSize - 8) / 4 : 0;
        int need = rmCount > nameIdx + 1 ? rmCount : nameIdx + 1;
        byte[] resourceMap = new byte[8 + 4 * need];
        putU2(resourceMap, 0, CHUNK_RESOURCE_MAP);
        putU2(resourceMap, 2, 8);
        putU4(resourceMap, 4, resourceMap.length);
        for (int i = 0; i < rmCount; i++) {
            putU4(resourceMap, 8 + i * 4, u4(data, resMapOff + 8 + i * 4));
        }
        putU4(resourceMap, 8 + nameIdx * 4, ATTR_ID_NAME);

        // ---------- 5. 重写节点 ----------
        ByteArrayOutputStream body = new ByteArrayOutputStream(data.length + 512);
        body.write(pool);
        body.write(resourceMap);
        boolean patched = false;
        for (int i = 0; i < nodes.size(); i++) {
            int[] nd = nodes.get(i);
            int type = nd[0];
            int off = nd[1];
            int size = nd[2];
            if (!patched && type == CHUNK_START_ELEMENT && isElement(data, off, strings, "application")) {
                body.write(addNameAttr(data, off, size, nsIdx, nameIdx, valueIdx, strings));
                patched = true;
            } else {
                body.write(data, off, size);
            }
        }
        if (!patched) {
            throw new IOException("manifest 里没有找到 application 节点");
        }

        byte[] nodeBytes = body.toByteArray();
        byte[] out = new byte[8 + nodeBytes.length];
        putU2(out, 0, CHUNK_XML);
        putU2(out, 2, 8);
        putU4(out, 4, out.length);
        System.arraycopy(nodeBytes, 0, out, 8, nodeBytes.length);
        return out;
    }

    /** 判断某个 START_ELEMENT 的节点名是否等于 want。 */
    private static boolean isElement(byte[] b, int off, List<String> strings, String want) {
        int idx = u4(b, off + 20);
        return idx >= 0 && idx < strings.size() && want.equals(strings.get(idx));
    }

    /**
     * 给这个 START_ELEMENT 补上（或改写）android:name 属性。
     * 若已有名为 name 的属性，只改它的值；否则在属性表末尾追加一个属性。
     */
    private static byte[] addNameAttr(byte[] b, int off, int size, int ns, int name, int value,
                                      List<String> strings) {
        int count = u2(b, off + 28);
        int attrsAt = off + 16 + u2(b, off + 24);
        for (int i = 0; i < count; i++) {
            int a = attrsAt + i * ATTR_SIZE;
            if (a + ATTR_SIZE > off + size) {
                break;
            }
            int nIdx = u4(b, a + 4);
            if (nIdx >= 0 && nIdx < strings.size() && "name".equals(strings.get(nIdx))) {
                byte[] same = new byte[size];
                System.arraycopy(b, off, same, 0, size);
                putU4(same, a - off + 8, value);          // rawValue
                putU4(same, a - off + 16, value);         // typedValue.data
                same[a - off + 15] = (byte) TYPE_STRING;  // dataType
                return same;
            }
        }
        byte[] out = new byte[size + ATTR_SIZE];
        System.arraycopy(b, off, out, 0, size);
        putU4(out, 4, size + ATTR_SIZE);
        putU2(out, 28, count + 1);
        int a = size;
        putU4(out, a, ns);
        putU4(out, a + 4, name);
        putU4(out, a + 8, value);
        putU2(out, a + 12, 8);
        out[a + 14] = 0;
        out[a + 15] = (byte) TYPE_STRING;
        putU4(out, a + 16, value);
        return out;
    }

    /** 按原池的编码方式重建字符串池（强制清掉 SORTED 标志，因为我们是追加而非排序）。 */
    private static byte[] buildPool(List<String> strings, boolean utf8) throws IOException {
        int count = strings.size();
        ByteArrayOutputStream data = new ByteArrayOutputStream(4096);
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = data.size();
            String s = strings.get(i);
            if (utf8) {
                byte[] raw = mutf8(s);
                writeUleb(data, s.length());
                writeUleb(data, raw.length);
                data.write(raw);
                data.write(0);
            } else {
                writeU2(data, s.length());
                for (int k = 0; k < s.length(); k++) {
                    writeU2(data, s.charAt(k));
                }
                writeU2(data, 0);
            }
        }
        byte[] body = data.toByteArray();
        int stringsStart = 28 + 4 * count;
        // AXML 要求每个 chunk 的 size 都是 4 的倍数，否则系统解析会报
        // "XML size ... is not on an integer boundary"，安装时表现为“安装包已损坏”。
        // 字符串数据的长度不一定是 4 的倍数，这里补零对齐。
        int pad = (4 - ((stringsStart + body.length) % 4)) % 4;
        byte[] out = new byte[stringsStart + body.length + pad];
        putU2(out, 0, CHUNK_STRING_POOL);
        putU2(out, 2, 28);
        putU4(out, 4, out.length);
        putU4(out, 8, count);
        putU4(out, 12, 0);                         // styleCount = 0（manifest 不需要 style）
        putU4(out, 16, utf8 ? 0x100 : 0);          // 只保留 UTF8 标志
        putU4(out, 20, stringsStart);
        putU4(out, 24, 0);                         // stylesStart
        for (int i = 0; i < count; i++) {
            putU4(out, 28 + i * 4, offsets[i]);
        }
        System.arraycopy(body, 0, out, stringsStart, body.length);
        return out;
    }

    /** Java String → MUTF-8 字节。 */
    private static byte[] mutf8(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if (c != 0 && c < 0x80) {
                o.write(c);
            } else if (c < 0x800) {
                o.write(0xc0 | (c >> 6));
                o.write(0x80 | (c & 0x3f));
            } else {
                o.write(0xe0 | (c >> 12));
                o.write(0x80 | ((c >> 6) & 0x3f));
                o.write(0x80 | (c & 0x3f));
            }
        }
        return o.toByteArray();
    }

    private static String readUtf8(byte[] b, int[] off) {
        int utf16Len = uleb(b, off);
        int byteLen = uleb(b, off);
        int start = off[0];
        int end = Math.min(start + byteLen, b.length);
        char[] out = new char[utf16Len];
        int n = 0;
        int i = start;
        while (i < end && n < utf16Len) {
            int x = b[i++] & 0xff;
            if (x < 0x80) {
                out[n++] = (char) x;
            } else if ((x & 0xe0) == 0xc0) {
                int x2 = i < end ? (b[i++] & 0xff) : 0;
                out[n++] = (char) (((x & 0x1f) << 6) | (x2 & 0x3f));
            } else {
                int x2 = i < end ? (b[i++] & 0xff) : 0;
                int x3 = i < end ? (b[i++] & 0xff) : 0;
                out[n++] = (char) (((x & 0x0f) << 12) | ((x2 & 0x3f) << 6) | (x3 & 0x3f));
            }
        }
        off[0] = end + 1;
        return new String(out, 0, n);
    }

    private static String readUtf16(byte[] b, int[] off) {
        int len = u2(b, off[0]);
        int start = off[0] + 2;
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) u2(b, start + i * 2));
        }
        off[0] = start + len * 2 + 2;
        return sb.toString();
    }

    private static int uleb(byte[] b, int[] off) {
        int result = 0;
        int shift = 0;
        while (off[0] < b.length) {
            int x = b[off[0]++] & 0xff;
            result |= (x & 0x7f) << shift;
            if ((x & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return result;
    }

    private static byte[] readEntry(File apk, String name) throws IOException {
        ZipFile zf = new ZipFile(apk);
        try {
            ZipEntry e = zf.getEntry(name);
            if (e == null) {
                throw new IOException("APK 里没有 " + name);
            }
            InputStream in = zf.getInputStream(e);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, e.getSize()));
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        } finally {
            zf.close();
        }
    }

    private static int u2(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int u4(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static void putU2(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }

    private static void putU4(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void writeU2(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
    }

    private static void writeUleb(ByteArrayOutputStream o, int v) {
        while (true) {
            if ((v & 0xffffff80) == 0) {
                o.write(v);
                return;
            }
            o.write((v & 0x7f) | 0x80);
            v >>>= 7;
        }
    }
}
