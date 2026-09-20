package com.mtstyle.fm;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 读取 APK 详情页所需的全部信息（MT 风格 APK 详情）。 */
public final class ApkInfoHelper {

    public static class Info {
        public Drawable icon;
        public String label = "-";
        public String packageName = "-";
        public String versionName = "-";
        public int versionCode;
        public String signatureStatus = "-";
        public String signatureDetail = "";
        public String signatureSha256 = "";
        public String hardenStatus = "未检测到加固";
        public String hardenPlatform = "";
        public int hardenConfidence;
        public boolean hardenSuspectSelfMade;
        public String targetSystem = "-";
        public String minSystem = "-";
        public String installedText = "未安装";
        public boolean installed;
        public String dataDir1 = "-";
        public String dataDir2 = "-";
        public String apkPath = "-";
        public String firstInstall = "-";
        public String lastUpdate = "-";
        public String uid = "-";
        public String size = "-";
        public List<String> permissions = new ArrayList<>();
    }

    private ApkInfoHelper() {
    }

    public static Info load(Context context, File apk) {
        return load(context, apk, true);
    }

    /**
     * @param withHarden 是否顺带做加固识别。加固扫描较重，传 false 可先拿基础信息，
     *                   再由界面单独异步识别加固并回填。
     */
    public static Info load(Context context, File apk, boolean withHarden) {
        Info info = new Info();
        info.apkPath = apk.getAbsolutePath();
        info.size = Util.formatSize(apk.length());

        PackageManager pm = context.getPackageManager();
        // 只做一次 PackageManager 解析：一次性拿到图标、权限、签名，避免重复解析大 APK（提速关键）。
        int flags = PackageManager.GET_PERMISSIONS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        } else {
            flags |= PackageManager.GET_SIGNATURES;
        }
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive == null || archive.applicationInfo == null) {
            return info;
        }
        ApplicationInfo appInfo = archive.applicationInfo;
        appInfo.sourceDir = apk.getAbsolutePath();
        appInfo.publicSourceDir = apk.getAbsolutePath();

        CharSequence label = appInfo.loadLabel(pm);
        info.label = label == null ? "-" : label.toString();
        try {
            info.icon = appInfo.loadIcon(pm);
        } catch (Exception | OutOfMemoryError ignored) {
        }
        info.packageName = archive.packageName;
        info.versionName = archive.versionName == null ? "-" : archive.versionName;
        info.versionCode = archive.versionCode;
        info.targetSystem = "Android " + apiToName(appInfo.targetSdkVersion)
                + " (API " + appInfo.targetSdkVersion + ")";
        info.minSystem = "Android " + apiToName(appInfo.minSdkVersion)
                + " (API " + appInfo.minSdkVersion + ")";
        if (archive.requestedPermissions != null) {
            for (String permission : archive.requestedPermissions) {
                info.permissions.add(permission);
            }
        }

        // 签名状态（复用同一次解析结果）
        analyzeSignature(apk, archive, info);

        // 加固检测（多特征精准识别；较重，可交由界面异步执行）
        if (withHarden) {
            HardenDetector.Result harden = HardenDetector.detect(apk);
            info.hardenStatus = harden.displayText;
            info.hardenPlatform = harden.platform;
            info.hardenConfidence = harden.confidence;
            info.hardenSuspectSelfMade = harden.suspectSelfMade;
        } else {
            info.hardenStatus = "";
        }

        // 是否已安装 + 安装信息
        try {
            PackageInfo installed = pm.getPackageInfo(archive.packageName, 0);
            if (installed != null) {
                info.installed = true;
                info.installedText = "已安装";
                if (installed.applicationInfo != null) {
                    info.uid = String.valueOf(installed.applicationInfo.uid);
                }
                info.firstInstall = Util.formatDate(installed.firstInstallTime);
                info.lastUpdate = Util.formatDate(installed.lastUpdateTime);
            }
        } catch (Exception ignored) {
        }
        info.dataDir1 = "/data/user/0/" + info.packageName;
        info.dataDir2 = "/storage/emulated/0/Android/data/" + info.packageName;
        return info;
    }

    private static String apiToName(int api) {
        switch (api) {
            case 24: return "7.0";
            case 25: return "7.1";
            case 26: return "8.0";
            case 27: return "8.1";
            case 28: return "9";
            case 29: return "10";
            case 30: return "11";
            case 31: return "12";
            case 32: return "12L";
            case 33: return "13";
            case 34: return "14";
            case 35: return "15";
            case 36: return "16";
            default:
                return "API " + api;
        }
    }

    private static void analyzeSignature(File apk, PackageInfo archive, Info info) {
        boolean v1 = hasV1Signature(apk);
        boolean v2plus = false;
        try {
            Signature[] signatures = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && archive != null) {
                SigningInfo signingInfo = archive.signingInfo;
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signatures = signingInfo.getApkContentsSigners();
                    } else {
                        signatures = signingInfo.getSigningCertificateHistory();
                        if (signatures == null || signatures.length == 0) {
                            signatures = signingInfo.getApkContentsSigners();
                        }
                    }
                    v2plus = signatures != null && signatures.length > 0;
                }
            }
            if (signatures == null && archive != null) {
                signatures = archive.signatures;
                v2plus = signatures != null && signatures.length > 0;
            }
            if (signatures != null && signatures.length > 0) {
                info.signatureSha256 = sha256(signatures[0].toByteArray());
                try {
                    CertificateFactory factory = CertificateFactory.getInstance("X.509");
                    InputStream in = new java.io.ByteArrayInputStream(signatures[0].toByteArray());
                    X509Certificate cert = (X509Certificate) factory.generateCertificate(in);
                    in.close();
                    info.signatureDetail = "主体: " + cert.getSubjectDN().getName()
                            + "\n有效期至: " + Util.formatDate(cert.getNotAfter().getTime());
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        if (v1 && v2plus) {
            info.signatureStatus = "已签名（V1 + V2/V3）";
        } else if (v1) {
            info.signatureStatus = "已签名（仅 V1）";
        } else if (v2plus) {
            info.signatureStatus = "已签名（V2/V3）";
        } else {
            info.signatureStatus = "未签名";
        }
    }

    private static boolean hasV1Signature(File apk) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().toUpperCase(Locale.US);
                if (name.startsWith("META-INF/") && (name.endsWith(".RSA")
                        || name.endsWith(".DSA") || name.endsWith(".EC"))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    private static String base(String path) {
        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(data);
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
