package com.mtstyle.fm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * v5.6：APK 加固/加密引擎「免费APK加密」（{@code com.apk.xgmfy}）的接入层。
 *
 * <p>加固引擎本身是第三方商业壳（ArmPro + dpt-shell 壳），无法搬进本 App，这里做的是
 * 「入口 + 交接」：把用户选好的 APK 放到双方都能看到的下载目录，然后打开该 App 处理。</p>
 */
public final class ApkCrypto {

    public static final String PKG = "com.apk.xgmfy";

    /** 找安装包时扫的目录（含对话附件目录）。 */
    private static final String[] APK_DIRS = {
            "/sdcard/Download",
            "/sdcard/AgentAttachments",
            "/sdcard/Documents",
            "/sdcard"
    };

    /** 安装包文件名特征（小写比较）。 */
    private static final String[] APK_KEYS = {"免费apk加密", "xgmfy", "apk加密"};

    private ApkCrypto() {
    }

    public static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getApplicationInfo(PKG, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 只打开「免费APK加密」主界面。 */
    public static void open(final Activity activity) {
        if (launch(activity)) {
            return;
        }
        promptInstall(activity);
    }

    /**
     * 把选好的 APK 交给「免费APK加密」。
     *
     * <p>先尝试用 ACTION_SEND 直接把这个 APK 递过去；对方不接收时退回「复制到下载目录 +
     * 打开主界面」的交接方式，并提示文件名方便在它的列表里找到。</p>
     */
    public static void openWithApk(final Activity activity, final File apk) {
        if (apk == null || !apk.isFile()) {
            toast(activity, activity.getString(R.string.apkcrypto_no_apk));
            return;
        }
        new Thread(() -> {
            File deliverable = apk;
            String error = null;
            try {
                deliverable = makeVisible(apk);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final File target = deliverable;
            final String failure = error;
            activity.runOnUiThread(() -> {
                if (failure != null) {
                    toast(activity, activity.getString(R.string.apkcrypto_copy_failed, failure));
                    return;
                }
                if (sendToTool(activity, target)) {
                    return;
                }
                if (!launch(activity)) {
                    promptInstall(activity);
                    return;
                }
                toast(activity, activity.getString(R.string.apkcrypto_hint, target.getName()));
            });
        }, "apk-crypto-handoff").start();
    }

    /** ACTION_SEND 直接把 APK 递给对方（对方支持才用）。 */
    private static boolean sendToTool(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/vnd.android.package-archive");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setPackage(PKG);
            PackageManager pm = activity.getPackageManager();
            if (intent.resolveActivity(pm) == null) {
                return false;
            }
            activity.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean launch(Activity activity) {
        Intent intent = activity.getPackageManager().getLaunchIntentForPackage(PKG);
        if (intent == null) {
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
            return true;
        } catch (Throwable t) {
            HardenAuto.log("打开免费APK加密失败：" + t);
            return false;
        }
    }

    /**
     * 保证 APK 在双方都能看到的下载目录里：已在下载目录直接返回，否则复制一份进去。
     * （该工具有 MANAGE_EXTERNAL_STORAGE，能看到下载目录里的包。）
     */
    private static File makeVisible(File apk) throws IOException {
        File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!download.isDirectory() && !download.mkdirs()) {
            throw new IOException("无法访问目录 " + download.getAbsolutePath());
        }
        String parent = apk.getParent();
        if (parent != null && apk.getParentFile().equals(download)) {
            return apk;
        }
        File target = FileOps.uniqueTarget(download, apk.getName());
        copyFile(apk, target);
        return target;
    }

    private static void copyFile(File src, File dst) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            close(in);
            close(out);
        }
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    private static void promptInstall(final Activity activity) {
        final File apk = findApk();
        if (apk == null) {
            toast(activity, activity.getString(R.string.apkcrypto_missing));
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.apkcrypto_need_title)
                .setMessage(activity.getString(R.string.apkcrypto_need_msg, apk.getName()))
                .setPositiveButton(R.string.apk_install,
                        (dialog, which) -> Installer.install(activity, apk))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 在常见目录里找「免费APK加密」的安装包。 */
    public static File findApk() {
        for (String dir : APK_DIRS) {
            File[] files = new File(dir).listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (file == null || !file.isFile()) {
                    continue;
                }
                String lower = file.getName().toLowerCase();
                if (!lower.endsWith(".apk")) {
                    continue;
                }
                for (String key : APK_KEYS) {
                    if (lower.contains(key)) {
                        return file;
                    }
                }
            }
        }
        return null;
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
