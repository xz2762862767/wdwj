package com.mtstyle.fm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * APK 安装器（MT 风格）：
 * 1) 安装前验证签名与版本号（可关闭）；
 * 2) 防自动删除：先把 APK 复制到私有目录再安装，原文件不会被系统装完清掉；
 * 3) 安装前二次确认（可关闭）；
 * 4) 安装优先级：Shizuku > Dhizuku > Root > 系统安装器，可在设置中开关；
 * 5) 支持指定「自定义系统安装器」。
 */
public final class Installer {

    public static final String MIME_APK = "application/vnd.android.package-archive";
    public static final String PKG_SHIZUKU = "moe.shizuku.privileged.api";
    public static final String PKG_DHIZUKU = "com.rosan.dhizuku";

    private Installer() {
    }

    /** 安装入口。 */
    public static void install(final Activity activity, final File apk) {
        if (activity == null || apk == null || !apk.isFile()) {
            toast(activity, activity == null ? "" : activity.getString(R.string.install_apk_missing));
            return;
        }
        if (!Settings.installVerify(activity)) {
            prepare(activity, apk);
            return;
        }
        final String[] warning = new String[1];
        TaskRunner.run(activity, activity.getString(R.string.install_verifying), false,
                progress -> warning[0] = verifyWarning(activity, apk),
                (ok, message) -> {
                    if (activity.isFinishing()) {
                        return;
                    }
                    if (warning[0] == null) {
                        prepare(activity, apk);
                        return;
                    }
                    new AlertDialog.Builder(activity)
                            .setTitle(R.string.install_verify_title)
                            .setMessage(warning[0] + "\n\n"
                                    + activity.getString(R.string.install_verify_ask))
                            .setPositiveButton(R.string.install_continue,
                                    (d, w) -> prepare(activity, apk))
                            .setNegativeButton(R.string.act_cancel, null)
                            .show();
                });
    }

    /** 与已安装应用比对签名和版本号，返回警告文案；无风险返回 null。 */
    private static String verifyWarning(Context context, File apk) {
        try {
            ApkInfoHelper.Info info = ApkInfoHelper.load(context, apk);
            if (!info.installed || info.packageName == null
                    || "-".equals(info.packageName) || info.packageName.isEmpty()) {
                return null;
            }
            PackageManager pm = context.getPackageManager();
            PackageInfo installed = pm.getPackageInfo(info.packageName, 0);
            if (installed == null) {
                return null;
            }
            List<String> warns = new ArrayList<>();
            long installedCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? installed.getLongVersionCode() : installed.versionCode;
            if (info.versionCode > 0 && info.versionCode < installedCode) {
                warns.add(context.getString(R.string.install_warn_version,
                        String.valueOf(info.versionCode), String.valueOf(installedCode)));
            }
            String installedSha = installedSha256(pm, info.packageName);
            if (!info.signatureSha256.isEmpty() && !installedSha.isEmpty()
                    && !info.signatureSha256.equalsIgnoreCase(installedSha)) {
                warns.add(context.getString(R.string.install_warn_signature,
                        shortHash(info.signatureSha256), shortHash(installedSha)));
            }
            if (warns.isEmpty()) {
                return null;
            }
            StringBuilder builder = new StringBuilder();
            for (String warn : warns) {
                if (builder.length() > 0) {
                    builder.append("\n\n");
                }
                builder.append(warn);
            }
            return builder.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isEmpty()) {
            return "-";
        }
        return hash.length() <= 16 ? hash : hash.substring(0, 16) + "…";
    }

    /** 已安装应用的签名证书 SHA-256。 */
    private static String installedSha256(PackageManager pm, String packageName) {
        try {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo info = pm.getPackageInfo(packageName, flags);
            Signature signature = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
                Signature[] signers = info.signingInfo.hasMultipleSigners()
                        ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
                if (signers != null && signers.length > 0) {
                    signature = signers[0];
                }
            }
            if (signature == null && info.signatures != null && info.signatures.length > 0) {
                signature = info.signatures[0];
            }
            return signature == null ? "" : sha256(signature.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    private static String sha256(byte[] data) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(data);
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

    /** 防自动删除：复制到私有目录再装；否则直接用原文件。 */
    private static void prepare(final Activity activity, final File apk) {
        if (!Settings.installKeepSource(activity) || !apk.canRead()) {
            confirmThenInstall(activity, apk);
            return;
        }
        File cacheRoot = activity.getExternalCacheDir();
        if (cacheRoot == null) {
            cacheRoot = activity.getCacheDir();
        }
        final File dir = new File(cacheRoot, "install_cache");
        if (!dir.exists() && !dir.mkdirs()) {
            confirmThenInstall(activity, apk);
            return;
        }
        final File target = new File(dir, apk.getName());
        TaskRunner.run(activity, activity.getString(R.string.install_copying), true,
                progress -> copy(apk, target, progress),
                (ok, message) -> {
                    if (activity.isFinishing()) {
                        return;
                    }
                    if (ok) {
                        confirmThenInstall(activity, target);
                    } else {
                        toast(activity, activity.getString(R.string.install_copy_failed,
                                message == null ? "" : message));
                        confirmThenInstall(activity, apk);
                    }
                });
    }

    private static void copy(File source, File target, FileOps.Progress progress)
            throws Exception {
        File[] olds = target.getParentFile() == null ? null : target.getParentFile().listFiles();
        if (olds != null) {
            for (File old : olds) {
                if (!old.equals(target)) {
                    old.delete();
                }
            }
        }
        long total = source.length();
        long done = 0L;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (progress.isCancelled()) {
                    throw new FileOps.Cancelled();
                }
                out.write(buffer, 0, read);
                done += read;
                int percent = total > 0 ? (int) (done * 100 / total) : -1;
                progress.publish("正在复制安装包…", percent);
            }
            out.flush();
        }
    }

    /** 二次确认（可关闭）。 */
    private static void confirmThenInstall(final Activity activity, final File apk) {
        if (!Settings.installConfirm(activity)) {
            startInstall(activity, apk);
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.install_confirm_title)
                .setMessage(activity.getString(R.string.install_confirm_message,
                        apk.getName(), channelName(activity)))
                .setPositiveButton(R.string.install_now, (d, w) -> startInstall(activity, apk))
                .setNegativeButton(R.string.act_cancel, null)
                .show();
    }

    /** 按优先级选择安装通道。 */
    private static void startInstall(final Activity activity, final File apk) {
        if (Settings.installShizuku(activity) && isInstalled(activity, PKG_SHIZUKU)) {
            // Shizuku 通道需要 Shizuku API 依赖，未集成时明确提示并自动回退
            toast(activity, activity.getString(R.string.install_shizuku_not_ready));
        } else if (Settings.installDhizuku(activity) && isInstalled(activity, PKG_DHIZUKU)) {
            toast(activity, activity.getString(R.string.install_dhizuku_not_ready));
        } else if (Settings.installRoot(activity) && hasRootBinary(activity)) {
            rootInstall(activity, apk);
            return;
        }
        systemInstall(activity, apk);
    }

    /** 当前生效的通道名（用于二次确认提示）。 */
    public static String channelName(Context context) {
        if (Settings.installShizuku(context) && isInstalled(context, PKG_SHIZUKU)) {
            return context.getString(R.string.install_channel_shizuku);
        }
        if (Settings.installDhizuku(context) && isInstalled(context, PKG_DHIZUKU)) {
            return context.getString(R.string.install_channel_dhizuku);
        }
        if (Settings.installRoot(context) && hasRootBinary(context)) {
            return context.getString(R.string.install_channel_root);
        }
        return context.getString(R.string.install_channel_system);
    }

    private static void rootInstall(final Activity activity, final File apk) {
        final boolean[] success = new boolean[1];
        TaskRunner.run(activity, activity.getString(R.string.install_channel_root), false,
                progress -> success[0] = runRootInstall(activity, apk),
                (ok, message) -> {
                    if (activity.isFinishing()) {
                        return;
                    }
                    if (success[0]) {
                        toast(activity, activity.getString(R.string.install_success));
                    } else {
                        toast(activity, activity.getString(R.string.install_root_failed));
                        systemInstall(activity, apk);
                    }
                });
    }

    private static boolean runRootInstall(Context context, File apk) {
        String output = Shell.execAsRoot(context, "pm install -r \"" + apk.getAbsolutePath() + "\"");
        return output != null && output.contains("Success");
    }

    /** 调用（自定义或默认）系统安装器。 */
    public static void systemInstall(final Activity activity, final File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, MIME_APK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            String custom = Settings.customInstaller(activity);
            if (custom != null && !custom.isEmpty()) {
                intent.setPackage(custom);
            }
            activity.startActivity(intent);
        } catch (Exception e) {
            toast(activity, activity.getString(R.string.install_failed,
                    e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /** 系统里可用于安装 APK 的安装器列表（包名 → 名称）。 */
    public static Map<String, String> listSystemInstallers(Context context) {
        Map<String, String> result = new LinkedHashMap<>();
        PackageManager pm = context.getPackageManager();
        Intent[] probes = new Intent[]{
                new Intent(Intent.ACTION_INSTALL_PACKAGE),
                new Intent(Intent.ACTION_VIEW).setDataAndType(
                        Uri.parse("file:///android_asset/x.apk"), MIME_APK)
        };
        for (Intent probe : probes) {
            try {
                List<ResolveInfo> list = pm.queryIntentActivities(probe, 0);
                for (ResolveInfo info : list) {
                    if (info.activityInfo == null || info.activityInfo.packageName == null) {
                        continue;
                    }
                    String pkg = info.activityInfo.packageName;
                    if (result.containsKey(pkg)) {
                        continue;
                    }
                    CharSequence label = info.loadLabel(pm);
                    result.put(pkg, label == null ? pkg : label.toString());
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /** 已安装应用的名称，未安装返回 null。 */
    public static String installerLabel(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return null;
        }
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 是否存在 su（仅代表设备曾被 root 过，实际授权仍需用户允许）。 */
    public static boolean hasRootBinary(Context context) {
        if (!Settings.suCommand(context).isEmpty()) {
            return true;
        }
        return !"su".equals(Shell.detectSu());
    }

    private static void toast(Activity activity, String text) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }
}
