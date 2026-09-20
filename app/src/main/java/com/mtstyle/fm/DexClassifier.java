package com.mtstyle.fm;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 给脱壳产物分类：真实 dex ／ 没脱出来（还是壳）／ 沙箱自带 ／ 未识别。
 *
 * <p>「真实 dex」的定义按结果来定：<b>安装包里没有、运行时才被解出来的那份</b>。
 * 加固壳的外壳 dex 里同样带着目标包名的入口类（壳的 StubApp / Application 代理就是这么做的），
 * 所以只靠「含目标包名的类」会把壳的 dex 误判成真实 dex。这里用两条硬判据修正：</p>
 *
 * <ol>
 *   <li><b>与安装包内的 classes*.dex 逐字节比对（SHA-256）</b>：一致说明只是把原文件原样
 *       拷了出来，加固根本没解开；</li>
 *   <li><b>方法体完整度</b>：统计 {@code class_data_off == 0} 的空壳类比例。抽取壳会把业务类的
 *       方法体抽走，只在 dex 里留下类名与签名，这个比例会异常高。</li>
 * </ol>
 *
 * <p>判定顺序也据此调整：先排除「没脱出来」，再看是否含目标包名的类，最后才看沙箱特征，
 * 避免壳 dex 因为带了入口类而被当成真实 dex。</p>
 */
final class DexClassifier {

    /** 真实 dex：安装包里没有的内容，且含目标应用的类。 */
    static final int REAL = 0;
    /** 沙箱自带：宿主 App 自身或 blackbox / hiddenapi 框架。 */
    static final int SANDBOX = 1;
    /** 既不像目标包、也不像沙箱自带。 */
    static final int UNKNOWN = 2;
    /** 没脱出来：这份 dex 还是壳的形态（dex 头都无效，或方法体被大范围抽空）。 */
    static final int PACKED = 3;
    /** 与安装包里那份逐字节相同、且本身是完整 dex：未加固包就是它，加固包则多是壳的入口 dex。 */
    static final int SAME = 4;
    /** 加固壳自身的 dex：不含目标包的类、也不是沙箱，但带着壳的框架类。 */
    static final int PACKER = 5;

    /** 空壳类（class_data_off == 0）占比达到这个值就认为方法体被抽走了。 */
    private static final float STRIPPED_LIMIT = 0.6f;

    /**
     * 兜底判「真实」所需的最少类数量。沙箱自己那点零碎 dex 通常只有几十个类，
     * 而正常应用的 dex 动辄上千——用代码量而不是包名来兜底，套壳应用才不会被冤枉。
     */
    private static final int MIN_REAL_CLASSES = 100;

    /** 沙箱自带 dex 的特征类路径。 */
    private static final String[] SANDBOX_MARKS = {
            "top/niunaijun/blackbox",
            "org/lsposed",
            "me/weishu/reflection",
            "com/mtstyle/fm",
            // Epic 沙箱自己的一小片 dex（只有几十个类）
            "Epic/Protect",
    };

    /**
     * 加固壳自身的特征类路径。这些 dex 既没有目标包的业务类、也没有沙箱特征，
     * 本来会落进「未识别」；单独标出来，报告才不至于把壳的框架件和真正的未知件混在一起。
     *
     * <p>这里只放「只可能是壳」的精确特征。像 {@code com/mob/}（MobSDK）、{@code com/qihoo/}
     * 这类第三方 SDK 太常见，放进来的话正常应用的 dex 会被误标成壳。</p>
     */
    private static final String[] PACKER_MARKS = {
            "com/stub/StubApp",
            "com/secneo/",
            "com/tencent/StubShell/",
            "com/qihoo/util/",
            "com/baidu/protect/",
            "com/ali/mobisecenhance/",
            "com/luoye/dpt/",
            "com/wrapper/proxyapplication/",
            "com/nagain/",
            "com/izui/",
            "com/jiagu/",
            "com/eg/android/",
            "com/apkguard/stub/",
    };

    private DexClassifier() {
    }

    /**
     * @param dex        产物里的一个 dex
     * @param pkg        本次脱壳的目标包名（点分），可为空
     * @param apkDigests 目标 APK 内所有 classes*.dex 的 SHA-256，可为空
     * @return {@link #REAL}、{@link #PACKED}、{@link #SANDBOX} 或 {@link #UNKNOWN}
     */
    static int classify(File dex, String pkg, Set<String> apkDigests) {
        boolean valid = isValidDex(dex);
        // 1) 跟安装包里那份一模一样
        if (apkDigests != null && !apkDigests.isEmpty()) {
            String digest = sha256(dex);
            if (digest != null && apkDigests.contains(digest)) {
                // 连 dex 头都不对：壳根本没解密，原样把加密文件拷了出来
                if (!valid) {
                    return PACKED;
                }
                // 完整与否决定性质：被抽空的是壳；完整的（未加固包）就是原始 dex
                return strippedRatio(dex) >= STRIPPED_LIMIT ? PACKED : SAME;
            }
        }
        // 2) 方法体被大范围抽空：解密是解了，但业务代码还在壳手里
        if (valid && strippedRatio(dex) >= STRIPPED_LIMIT) {
            return PACKED;
        }
        // 3) 到这里才轮到「含目标包名的类」——它只说明这份 dex 跟目标有关，不是真实性的证据
        if (pkg != null && !pkg.isEmpty()
                && containsAny(dex, "L" + pkg.replace('.', '/') + "/")) {
            return REAL;
        }
        if (containsAny(dex, SANDBOX_MARKS)) {
            return SANDBOX;
        }
        // 3) 壳自身的 dex：没有目标包的类，却带着加固框架的类
        if (containsAny(dex, PACKER_MARKS)) {
            return PACKER;
        }
        // 4) 兜底。不能拿「有没有目标包名的类」当真假判据：套壳应用的目标包名下几乎没有类，
        //    业务代码挂在别的包名里（实测 com.fka.zhuru 的业务全在 com/beizi、com/ubix 下），
        //    真实 dex 会被冤枉成「未识别」。这里改成只要求「像一份正常应用的代码量」。
        return classCount(dex) >= MIN_REAL_CLASSES ? REAL : UNKNOWN;
    }

    /** dex 里定义了多少个类（header 0x60 的 class_defs_size）。读不出来返回 0。 */
    static int classCount(File dex) {
        InputStream in = null;
        try {
            in = new FileInputStream(dex);
            byte[] header = new byte[0x70];
            if (readFully(in, header) < header.length) {
                return 0;
            }
            if (!(header[0] == 'd' && header[1] == 'e' && header[2] == 'x' && header[3] == '\n')) {
                return 0;
            }
            return readInt(header, 0x60);
        } catch (Throwable t) {
            return 0;
        } finally {
            close(in);
        }
    }

    /** 是不是一个正常打开的 dex（magic 为 {@code dex\n}）。加密态的 classes.dex 头是乱的。 */
    static boolean isValidDex(File dex) {
        InputStream in = null;
        try {
            in = new FileInputStream(dex);
            byte[] magic = new byte[4];
            return readFully(in, magic) == magic.length
                    && magic[0] == 'd' && magic[1] == 'e' && magic[2] == 'x' && magic[3] == '\n';
        } catch (Throwable t) {
            return false;
        } finally {
            close(in);
        }
    }

    /** 目标 APK 里所有 {@code *.dex}（classes.dex / classes2.dex …）的 SHA-256 集合。 */
    static Set<String> apkDexDigests(File apk) {
        Set<String> out = new HashSet<>();
        if (apk == null || !apk.isFile()) {
            return out;
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".dex")) {
                    continue;
                }
                InputStream in = null;
                try {
                    in = zip.getInputStream(entry);
                    String digest = sha256(in);
                    if (digest != null) {
                        out.add(digest);
                    }
                } catch (Throwable ignored) {
                    // 单个条目读不了就跳过
                } finally {
                    close(in);
                }
            }
        } catch (Throwable ignored) {
            // 打不开就当没有安装包可比对
        } finally {
            close(zip);
        }
        return out;
    }

    /**
     * dex 里「没有任何类数据（{@code class_data_off == 0}）」的类占多大比例。
     * 正常 dex 只有接口/注解之类少数类没有类数据，抽取壳则会剩下一大片空壳类。
     */
    static float strippedRatio(File dex) {
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(dex), 64 * 1024);
            byte[] header = new byte[0x70];
            if (readFully(in, header) < header.length) {
                return 0f;
            }
            if (!(header[0] == 'd' && header[1] == 'e' && header[2] == 'x' && header[3] == '\n')) {
                return 0f;
            }
            int classDefsSize = readInt(header, 0x60);
            int classDefsOff = readInt(header, 0x64);
            if (classDefsSize <= 0 || classDefsOff <= 0) {
                return 0f;
            }
            long skip = classDefsOff - (long) header.length;
            while (skip > 0) {
                long skipped = in.skip(skip);
                if (skipped <= 0) {
                    return 0f;
                }
                skip -= skipped;
            }
            int stripped = 0;
            byte[] item = new byte[32];
            for (int i = 0; i < classDefsSize; i++) {
                if (readFully(in, item) < item.length) {
                    break;
                }
                // class_def_item: class_idx, access_flags, superclass_idx, interfaces_off,
                // source_file_idx, annotations_off, class_data_off, static_values_off
                if (readInt(item, 24) == 0) {
                    stripped++;
                }
            }
            return stripped / (float) classDefsSize;
        } catch (Throwable t) {
            return 0f;
        } finally {
            close(in);
        }
    }

    /** 文件字节流里是否出现任意一个 needle。dex 的类名是明文，直接找即可。 */
    static boolean containsAny(File file, String... needles) {
        byte[][] pats = new byte[needles.length][];
        for (int i = 0; i < needles.length; i++) {
            pats[i] = needles[i].getBytes(StandardCharsets.UTF_8);
        }
        int[] matched = new int[pats.length];
        byte[] buf = new byte[128 * 1024];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 128 * 1024)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    for (int p = 0; p < pats.length; p++) {
                        byte[] pat = pats[p];
                        if (b == pat[matched[p]]) {
                            matched[p]++;
                            if (matched[p] == pat.length) {
                                return true;
                            }
                        } else {
                            matched[p] = (b == pat[0]) ? 1 : 0;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读不了就当未识别，不影响结果页
        }
        return false;
    }

    private static String sha256(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            return sha256(in);
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
        }
    }

    private static String sha256(InputStream in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int readInt(byte[] data, int off) {
        return (data[off] & 0xFF)
                | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16)
                | ((data[off + 3] & 0xFF) << 24);
    }

    /** 尽量读满 data.length 字节，返回实际读到的字节数。 */
    private static int readFully(InputStream in, byte[] data) {
        int off = 0;
        try {
            while (off < data.length) {
                int read = in.read(data, off, data.length - off);
                if (read <= 0) {
                    break;
                }
                off += read;
            }
        } catch (Throwable ignored) {
            // 返回已读到的部分
        }
        return off;
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
