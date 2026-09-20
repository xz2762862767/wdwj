package com.mtstyle.fm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.File;

/**
 * v5.5：「黑盾」加固工具的入口。
 *
 * <p>黑盾是外部应用（{@code com.obsidian.Alpha}，桌面名称「黑盾」），负责 APK 加固。
 * 本类只做三件事：判断是否已安装、启动它、未安装时在常见目录里找到安装包并引导安装。</p>
 */
public final class BlackShield {

    public static final String PKG = "com.obsidian.Alpha";

    /** 找安装包时扫的目录（含附件目录，用户从对话里收到的包常在这里）。 */
    private static final String[] APK_DIRS = {
            "/sdcard/Download",
            "/sdcard/AgentAttachments",
            "/sdcard/Documents",
            "/sdcard"
    };

    private BlackShield() {
    }

    public static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getApplicationInfo(PKG, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 打开黑盾：未安装时先引导安装安装包。 */
    public static void open(final Activity activity) {
        PackageManager pm = activity.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(PKG);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(intent);
                return;
            } catch (Throwable t) {
                // 启动失败时继续走「未安装/异常」分支，给用户可操作的提示
                HardenAuto.log("打开黑盾失败：" + t);
            }
        }
        promptInstall(activity);
    }

    private static void promptInstall(final Activity activity) {
        final File apk = findApk();
        if (apk == null) {
            toast(activity, activity.getString(R.string.blackshield_missing));
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.blackshield_need_title)
                .setMessage(activity.getString(R.string.blackshield_need_msg, apk.getName()))
                .setPositiveButton(R.string.apk_install,
                        (dialog, which) -> Installer.install(activity, apk))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 在常见目录里找黑盾的安装包（文件名含「黑盾」或包名特征）。 */
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
                String name = file.getName();
                String lower = name.toLowerCase();
                if (!lower.endsWith(".apk")) {
                    continue;
                }
                if (name.contains("黑盾") || lower.contains("blackshield")
                        || lower.contains("obsidian")) {
                    return file;
                }
            }
        }
        return null;
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }
}
