package com.mtstyle.fm;

import android.app.Activity;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 「加固 APK」入口：把目标 APK 交给外部应用「Android加固工具」（com.adfxcbnm.hardeningtool）完成加固。
 *
 * <p>该工具的界面与加固逻辑都在加密壳内，且没有声明“接收文件 / 分享 / 打开方式”入口，
 * 实测它不读取外部传入的路径，必须在其自带的文件列表里手动选择 APK。
 * 因此这里提供两条路：</p>
 * <ul>
 *     <li><b>自动加固</b>（推荐）：由 {@link HardenAutoService}（无障碍服务）自动操作工具界面，
 *     自己完成“选择该 APK → 开始加固”。为便于在工具列表里一眼定位，目标 APK 会被放到下载目录。</li>
 *     <li><b>手动</b>：直接把工具打开，同时把路径塞进剪贴板，自己选文件即可。</li>
 * </ul>
 */
public final class HardenTool {

    public static final String PKG = "com.adfxcbnm.hardeningtool";
    private static final String ACT = "com.adfxcbnm.hardeningtool.MainActivity";

    /** 该工具可能读取的参数名：全部一起传，命中哪个都能用（实测未命中，留作兼容）。 */
    private static final String[] PATH_KEYS = {
            "apk_path", "apkPath", "apk", "path", "file_path", "filePath", "file",
            "input", "input_path", "inputPath", "input_apk",
            "target", "target_path", "targetPath", "src", "source", "extra_path"
    };

    /** 找加固工具安装包时扫的目录（用户手动下载的常见位置）。 */
    private static final String[] TOOL_APK_DIRS = {
            "/sdcard/Download", "/sdcard/Documents", "/sdcard/AgentAttachments", "/sdcard"
    };

    private HardenTool() {
    }

    public static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getApplicationInfo(PKG, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 「自动加固」无障碍服务是否已开启。 */
    public static boolean isAutoEnabled(Context context) {
        try {
            AccessibilityManager manager =
                    (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager == null) {
                return false;
            }
            List<AccessibilityServiceInfo> list =
                    manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (list == null) {
                return false;
            }
            for (AccessibilityServiceInfo info : list) {
                ResolveInfo resolve = info == null ? null : info.getResolveInfo();
                if (resolve == null || resolve.serviceInfo == null) {
                    continue;
                }
                if (context.getPackageName().equals(resolve.serviceInfo.packageName)
                        && resolve.serviceInfo.name != null
                        && resolve.serviceInfo.name.contains("HardenAutoService")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 打开系统无障碍设置（优先一步直达本服务的开关页）。 */
    public static void openAccessibilitySettings(Context context) {
        try {
            android.content.ComponentName component =
                    new android.content.ComponentName(context, HardenAutoService.class);
            Intent detail = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
            detail.putExtra("android.provider.extra.COMPONENT_NAME", component);
            detail.putExtra("component_name", component);
            detail.putExtra("extra_component_name", component.flattenToString());
            detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(detail);
            return;
        } catch (Throwable ignored) {
            // 系统不支持直达时退回无障碍列表
        }
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            toast(context, context.getString(R.string.harden_auto_missing));
        }
    }

    /** 直接打开加固工具（不带目标文件）。 */
    public static void openTool(Activity activity) {
        if (!isInstalled(activity)) {
            promptInstall(activity);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(PKG, ACT);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable t) {
            toast(activity, activity.getString(R.string.harden_auto_missing));
        }
    }

    /** 把 APK 交给加固工具处理。未安装该工具时引导安装。 */
    public static void open(final Activity activity, final File apk) {
        if (apk == null || !apk.isFile()) {
            toast(activity, activity.getString(R.string.apk_parse_failed));
            return;
        }
        if (!isInstalled(activity)) {
            promptInstall(activity);
            return;
        }
        final boolean auto = isAutoEnabled(activity);
        if (!auto) {
            // 无障碍服务在覆盖安装后会被系统关闭：每次都明确提示，绝不静默改成手动
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.harden_auto_need_title2)
                    .setMessage(R.string.harden_auto_need_msg)
                    .setPositiveButton(R.string.harden_auto_go2, (dialog, which) -> {
                        HardenAuto.setPrompted(activity);
                        openAccessibilitySettings(activity);
                    })
                    .setNegativeButton(R.string.harden_auto_manual, (dialog, which) -> {
                        HardenAuto.setPrompted(activity);
                        launch(activity, apk, false);
                    })
                    .show();
            return;
        }
        // 自动加固：为便于工具列表定位，目标统一放到下载目录（大文件复制放后台线程）
        if (isInDownloadDir(apk)) {
            launch(activity, apk, true);
            return;
        }
        new Thread(() -> {
            final File target = copyToDownload(apk);
            if (activity.isFinishing()) {
                return;
            }
            activity.runOnUiThread(() -> launch(activity, target, target != apk));
        }, "harden-copy").start();
    }

    private static void launch(final Activity activity, final File apk, final boolean auto) {
        touchAndScan(activity, apk);
        final String path = apk.getAbsolutePath();
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName(PKG, ACT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        for (String key : PATH_KEYS) {
            intent.putExtra(key, path);
        }
        intent.putExtra(Intent.EXTRA_TEXT, path);

        boolean started = false;
        try {
            Intent withData = new Intent(intent);
            withData.setDataAndType(Uri.parse("file://" + path), Installer.MIME_APK);
            activity.startActivity(withData);
            started = true;
        } catch (Throwable ignored) {
            // Android 7+ 不允许在 Intent 中携带 file:// ，退回纯参数方式
        }
        if (!started) {
            try {
                activity.startActivity(intent);
            } catch (Throwable t) {
                toast(activity, activity.getString(R.string.harden_tool_failed,
                        String.valueOf(t.getMessage())));
                return;
            }
        }
        // 兜底：路径进剪贴板，没自动载入时可手动粘贴
        copyPath(activity, path);
        if (auto) {
            HardenAuto.startSession(activity, path, path);
            toast(activity, activity.getString(R.string.harden_auto_on, apk.getName()));
        } else {
            toast(activity, activity.getString(R.string.harden_auto_off));
        }
    }

    /**
     * 刷新文件时间戳并通知媒体库：让它在系统文件选择器的「最近」里排到第一位，
     * 这样无障碍只需点第一行就能选中，不用在目录里翻找。
     */
    private static void touchAndScan(Activity activity, File file) {
        try {
            file.setLastModified(System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
        try {
            android.media.MediaScannerConnection.scanFile(
                    activity.getApplicationContext(), new String[]{file.getAbsolutePath()},
                    new String[]{"application/vnd.android.package-archive"}, null);
            HardenAuto.log("已刷新「最近」排序：" + file.getName());
        } catch (Throwable t) {
            HardenAuto.log("刷新媒体库失败：" + t);
        }
    }

    /** 目标是否已经在下载目录（避免无谓复制）。 */    private static boolean isInDownloadDir(File apk) {
        File parent = apk.getParentFile();
        if (parent == null) {
            return false;
        }
        try {
            return parent.getCanonicalPath().equals(HardenAuto.DOWNLOAD_DIR.getCanonicalPath());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 复制到下载目录（同名同大小则复用已有副本）；失败时返回原文件。 */
    private static File copyToDownload(File apk) {
        File dir = HardenAuto.DOWNLOAD_DIR;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                HardenAuto.log("下载目录不可用：" + dir);
                return apk;
            }
            File dst = new File(dir, apk.getName());
            if (dst.isFile() && dst.length() == apk.length()) {
                HardenAuto.log("复用下载目录中的副本：" + dst);
                return dst;
            }
            InputStream in = new FileInputStream(apk);
            try {
                OutputStream out = new FileOutputStream(dst);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            HardenAuto.log("已复制到下载目录：" + dst);
            return dst;
        } catch (Throwable t) {
            HardenAuto.log("复制到下载目录失败：" + t);
            return apk;
        }
    }

    private static void promptInstall(final Activity activity) {
        File tool = findToolApk();
        if (tool == null) {
            toast(activity, activity.getString(R.string.harden_tool_missing));
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.harden_tool_need_title)
                .setMessage(activity.getString(R.string.harden_tool_need_msg, tool.getName()))
                .setPositiveButton(R.string.apk_install,
                        (dialog, which) -> Installer.install(activity, tool))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 在常见目录里找「Android加固工具」的安装包。 */
    public static File findToolApk() {
        for (String dir : TOOL_APK_DIRS) {
            File[] files = new File(dir).listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (file == null || !file.isFile()) {
                    continue;
                }
                String name = file.getName();
                String lower = name.toLowerCase();
                if (!lower.endsWith(".apk")) {
                    continue;
                }
                if (name.contains("加固") || lower.contains("harden")) {
                    return file;
                }
            }
        }
        return null;
    }

    private static void copyPath(Context context, String path) {
        try {
            ClipboardManager manager =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("path", path));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }
}
