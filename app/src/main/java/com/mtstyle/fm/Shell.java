package com.mtstyle.fm;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Root / Shell 命令执行工具。
 * 支持「自定义 su 命令」（设置页可填，留空自动识别为 su）。
 */
public final class Shell {

    private Shell() {
    }

    /** 生效的 su 命令。 */
    public static String suCommand(Context context) {
        String custom = Settings.suCommand(context);
        if (custom.isEmpty()) {
            return "su";
        }
        return custom;
    }

    /** 自动识别的 su 路径（自定义命令为空时尝试常见位置）。 */
    public static String detectSu() {
        String[] paths = new String[]{"/system/bin/su", "/system/xbin/su",
                "/sbin/su", "/su/bin/su", "/system/sbin/su", "/vendor/bin/su"};
        for (String path : paths) {
            try {
                if (new java.io.File(path).exists()) {
                    return path;
                }
            } catch (Exception ignored) {
            }
        }
        return "su";
    }

    /** 以 Root 执行命令并返回输出；失败返回 null。 */
    public static String execAsRoot(Context context, String command) {
        return exec(suCommand(context), command);
    }

    /** 用指定命令（su/sh）执行 shell 命令并返回输出；失败返回 null。 */
    public static String exec(String binary, String command) {
        Process process = null;
        try {
            process = new ProcessBuilder(binary, "-c", command)
                    .redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            process.waitFor();
            return output.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** 是否已获得 Root 权限。 */
    public static boolean isRootAvailable(Context context) {
        String output = execAsRoot(context, "id");
        return output != null && output.contains("uid=0");
    }

    /** 设备上是否存在 su（不代表已授权）。 */
    public static boolean hasSuBinary(Context context) {
        String custom = Settings.suCommand(context);
        if (!custom.isEmpty()) {
            return true;
        }
        return !"su".equals(detectSu()) || hasDefaultSu();
    }

    private static boolean hasDefaultSu() {
        try {
            return new java.io.File("/system/bin/su").exists()
                    || new java.io.File("/system/xbin/su").exists();
        } catch (Exception e) {
            return false;
        }
    }
}
