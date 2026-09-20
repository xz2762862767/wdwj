package com.mtstyle.fm;

import android.content.Context;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 通用工具方法。 */
public final class Util {

    private Util() {
    }

    public static final String DEFAULT_PATTERN = "yy-MM-dd HH:mm";

    public static String formatDate(long time) {
        return formatDate(time, DEFAULT_PATTERN);
    }

    /** 按指定格式格式化时间（格式非法时回退默认格式）。 */
    public static String formatDate(long time, String pattern) {
        try {
            return new SimpleDateFormat(
                    pattern == null || pattern.trim().isEmpty() ? DEFAULT_PATTERN : pattern,
                    Locale.getDefault()).format(new Date(time));
        } catch (Exception e) {
            return new SimpleDateFormat(DEFAULT_PATTERN, Locale.getDefault())
                    .format(new Date(time));
        }
    }

    /** 类似 MT 风格的大小显示：1.23G / 456M / 12K / 890B */
    public static String formatSize(long bytes) {
        if (bytes < 0) {
            return "-";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.0fK", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1fM", mb);
        }
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2fG", gb);
    }

    /** 简写形式，用于状态栏：132.55G */
    public static String formatSizeGs(long bytes) {
        if (bytes <= 0) {
            return "0B";
        }
        double gb = bytes / 1024.0 / 1024.0 / 1024.0;
        return String.format(Locale.US, "%.2fG", gb);
    }

    /** 自适应存储容量显示：0B / 922.76M / 132.56G */
    public static String formatStorage(long bytes) {
        if (bytes <= 0) {
            return "0B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.2fK", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.2fM", mb);
        }
        double gb = mb / 1024.0;
        if (gb < 1024) {
            return String.format(Locale.US, "%.2fG", gb);
        }
        return String.format(Locale.US, "%.2fT", gb / 1024.0);
    }

    public static String joinPath(String parent, String name) {
        if (parent.endsWith("/")) {
            return parent + name;
        }
        return parent + "/" + name;
    }

    public static File firstExisting(File... candidates) {
        for (File f : candidates) {
            if (f != null && f.exists()) {
                return f;
            }
        }
        return candidates[0];
    }

    /** 计算文件摘要（MD5 / SHA-1 / SHA-256），返回十六进制字符串。 */
    public static String digest(File file, String algorithm) {
        java.io.InputStream in = null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance(algorithm);
            in = new java.io.FileInputStream(file);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
            byte[] bytes = md.digest();
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            return "计算失败: " + e.getMessage();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ==================== 主题色 ====================

    /** 解析主题属性中的颜色（accent / accentDim / accentSoft）。 */
    public static int themeColor(Context context, int attrRes, int fallbackColorRes) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(attrRes, value, true)) {
            if (value.resourceId != 0) {
                return androidx.core.content.ContextCompat.getColor(context, value.resourceId);
            }
            if (value.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
        }
        return androidx.core.content.ContextCompat.getColor(context, fallbackColorRes);
    }

    /** 当前主题的强调色。 */
    public static int accentColor(Context context) {
        return themeColor(context, R.attr.accent, R.color.accent);
    }

    /** 当前主题的强调色（低透明度，用于选中背景）。 */
    public static int accentDimColor(Context context) {
        return themeColor(context, R.attr.accentDim, R.color.accent_dim);
    }
}
