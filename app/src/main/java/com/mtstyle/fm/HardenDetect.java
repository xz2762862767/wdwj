package com.mtstyle.fm;

import java.io.File;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 加固平台识别（v5.9.4）。
 *
 * <p>两级特征，先便宜后昂贵：</p>
 * <ol>
 *   <li><b>application 类名</b>：加固后应用启动必经壳入口，系统已经把 {@code android:name}
 *       解析好了（{@link android.content.pm.ApplicationInfo#className}），零成本、最可靠；</li>
 *   <li><b>APK 内容特征</b>：读 zip 中央目录（不解压），匹配各家壳的 .so / assets 专属文件名；
 *       没有任何特征命中但形迹可疑（classes.dex 极小、assets 里塞大 dex/jar）时给出「疑似加固」。</li>
 * </ol>
 *
 * <p>只列已确证的特征串，宁可显示「未加固」也不猜——避免给出错误的加固平台。</p>
 */
public final class HardenDetect {

    /** 没有加固特征。 */
    public static final String NOT_HARDENED = "未加固";
    /** 有加固迹象但认不出是哪家。 */
    public static final String UNKNOWN = "疑似加固";
    /** APK 读不了（权限/损坏）：结论未知，既不能说未加固也不能说加固。 */
    public static final String FAILED = "";

    /** application 类名 → 平台。加固壳入口类，命中即确定。 */
    private static final String[][] APP_SIGS = {
            {"com.stub.StubApp", "360加固"},
            {"com.qihoo.util.StubApplication", "360加固"},
            {"com.tencent.StubShell.TxAppEntry", "腾讯乐固"},
            {"com.secneo.apkwrapper.ApplicationWrapper", "梆梆加固"},
            {"s.h.e.l.l.S", "爱加密"},
            {"com.baidu.protect.StubApplication", "百度加固"},
            {"com.ali.mobisecenhance.StubApplication", "阿里聚安全"},
            {"com.netease.nis.wrapper.MyApplication", "网易易盾"},
            {"com.dingxiang.mobile.DXApplication", "顶象加固"},
            {"com.apkguard.stub.StubApplication", "APKGuard加固"},
    };

    /**
     * 文件名（zip 条目名，小写）包含即命中 → 平台。
     * 顺序有讲究：越特异的放前面，避免「libexec」这类短串先命中别的壳。
     */
    private static final String[][] FILE_SIGS = {
            {"libjiagu", "360加固"},
            {"libshella", "腾讯乐固"},
            {"libshellx", "腾讯乐固"},
            {"libtosprotection", "腾讯乐固"},
            {"0oo00l111l1l", "腾讯乐固"},
            {"o0oo00oo0l", "腾讯乐固"},
            {"libdexhelper", "梆梆加固"},
            {"libsecexe", "梆梆加固"},
            {"libsecmain", "梆梆加固"},
            {"libsecprog", "梆梆加固"},
            {"libexecmain", "爱加密"},
            {"libijm", "爱加密"},
            {"ijiami", "爱加密"},
            {"libexec.so", "爱加密"},
            {"libchaosvmp", "娜迦加固"},
            {"libddog", "娜迦加固"},
            {"libfdog", "娜迦加固"},
            {"libedog", "娜迦加固"},
            {"libbaiduprotect", "百度加固"},
            {"libmobisec", "阿里聚安全"},
            {"libnesec", "网易易盾"},
            {"libnqshield", "网易易盾"},
            {"libtersafe", "腾讯御安全"},
            {"libegis", "通付盾"},
            // 以下是在真机样本上实测到的自研/小众壳，特征串本身很特异，单独标出来更清楚
            {"adfx_dex.bin", "自研壳(adfx)"},
            {"adfx_code.bin", "自研壳(adfx)"},
            {"protection_manifest.json", "自研壳(adfx)"},
            {"ondev/", "OnDev壳"},
            {"libondev", "OnDev壳"},
            {"libsrpatch", "SRPatch壳"},
            {"yigu_kit.dat", "yigu壳"},
            {"libobsc", "yigu壳"},
            // APKGuard：assets/stub/<hash>/<abi>/{stub.enc,bootstrap.bin}，包里没有任何明文 dex
            {"assets/stub/", "APKGuard加固"},
            {"stub.enc", "APKGuard加固"},
    };

    /** classes.dex 小于这个大小、而 APK 远大于它 → 说明真正的代码被藏起来了。 */
    private static final long TINY_DEX = 120 * 1024L;
    /** APK 至少这么大才谈得上「壳里藏了东西」。 */
    private static final long BIG_APK = 3 * 1024 * 1024L;
    /** assets 里塞这么大的 dex/jar 很反常。 */
    private static final long BIG_PAYLOAD = 500 * 1024L;

    private HardenDetect() {
    }

    /**
     * 一级检测：application 类名。
     *
     * @return 平台名；不匹配返回 {@code null}
     */
    public static String byApplicationName(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        for (String[] sig : APP_SIGS) {
            if (className.equals(sig[0])) {
                return sig[1];
            }
        }
        return null;
    }

    /**
     * 二级检测：读 APK 的 zip 条目名（不解压内容，很快）。
     *
     * @return 平台名 / {@link #UNKNOWN}（疑似加固）/ {@code null}（未见加固特征）/
     *         {@link #FAILED}（APK 读不了，结论未知）
     */
    public static String scanApk(File apk) {
        if (apk == null || !apk.isFile() || apk.length() <= 0L) {
            return FAILED;
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            long classesDex = -1L;
            boolean bigPayload = false;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName().toLowerCase(Locale.ROOT);
                String hit = byFileName(path);
                if (hit != null) {
                    return hit;
                }
                if ("classes.dex".equals(path)) {
                    classesDex = entry.getSize();
                } else if (isBigPayload(path) && entry.getSize() > BIG_PAYLOAD) {
                    bigPayload = true;
                }
            }
            if (bigPayload
                    || (classesDex >= 0L && classesDex < TINY_DEX && apk.length() > BIG_APK)) {
                return UNKNOWN;
            }
            return null;
        } catch (Throwable t) {
            // 读不了（权限/损坏/超大）：结论未知，别误报成「未加固」
            return FAILED;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String byFileName(String path) {
        for (String[] sig : FILE_SIGS) {
            if (path.contains(sig[0])) {
                return sig[1];
            }
        }
        return null;
    }

    /** assets 里的 dex/jar 通常意味着代码被搬进了资源目录。 */
    private static boolean isBigPayload(String path) {
        return path.startsWith("assets/")
                && (path.endsWith(".dex") || path.endsWith(".jar"));
    }
}
