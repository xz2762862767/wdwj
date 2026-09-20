package com.mtstyle.fm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 「加固 APK」自动化的会话状态与运行日志。
 *
 * <p>会话：由 {@link HardenTool} 在调起加固工具之前下发（记录目标 APK 绝对路径），
 * {@link HardenAutoService} 在会话有效期内自动操作加固工具的界面，完成后结束会话。
 * 没有会话时服务不做任何事，也不会去点别的应用。</p>
 */
public final class HardenAuto {

    /** 目标 APK 统一放这里：工具自带的文件浏览器最容易定位到下载目录。 */
    public static final File DOWNLOAD_DIR =
            new File(Environment.getExternalStorageDirectory(), "Download");

    private static final String PREFS = "harden_auto";
    private static final String KEY_PATH = "pending_path";
    private static final String KEY_AT = "pending_at";
    private static final String KEY_PROMPTED = "prompted";
    private static final String KEY_SOURCE = "pending_source";

    /** 会话有效期：超时则认为已失效，避免误操作其它界面。 */
    public static final long SESSION_TTL = 10L * 60 * 1000;

    private static final long MAX_LOG_BYTES = 256 * 1024;

    private HardenAuto() {
    }

    // ==================== 日志 ====================

    public static File logFile() {
        return new File(DOWNLOAD_DIR, "加固自动化日志.txt");
    }

    public static synchronized void log(String message) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + "  " + message + "\n";
        File file = logFile();
        try {
            if (file.exists() && file.length() > MAX_LOG_BYTES) {
                Writer truncate = new OutputStreamWriter(new FileOutputStream(file, false),
                        Charset.forName("UTF-8"));
                truncate.write("[日志已滚动]\n");
                truncate.close();
            }
            Writer writer = new OutputStreamWriter(new FileOutputStream(file, true),
                    Charset.forName("UTF-8"));
            writer.write(line);
            writer.flush();
            writer.close();
        } catch (Throwable ignored) {
            // 外置存储不可写时忽略：日志只用于排查，不影响功能
        }
    }

    public static synchronized void clearLog() {
        try {
            new OutputStreamWriter(new FileOutputStream(logFile(), false),
                    Charset.forName("UTF-8")).close();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 会话 ====================

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 下发一次加固会话（目标 APK 路径）。 */
    public static void startSession(Context context, String path, String sourcePath) {
        prefs(context).edit()
                .putString(KEY_PATH, path)
                .putString(KEY_SOURCE, sourcePath == null ? path : sourcePath)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply();
        log("下发会话：" + path);
    }

    public static String pendingPath(Context context) {
        return prefs(context).getString(KEY_PATH, "");
    }

    /** 会话来源（详情页里那个 APK，可能已被复制到下载目录）。 */
    public static String sourcePath(Context context) {
        return prefs(context).getString(KEY_SOURCE, "");
    }

    public static boolean isSessionActive(Context context) {
        SharedPreferences p = prefs(context);
        String path = p.getString(KEY_PATH, "");
        if (path == null || path.isEmpty()) {
            return false;
        }
        long at = p.getLong(KEY_AT, 0L);
        return System.currentTimeMillis() - at < SESSION_TTL;
    }

    public static void endSession(Context context) {
        prefs(context).edit().putString(KEY_PATH, "").apply();
    }

    /** 是否已经引导过用户去开启无障碍（只引导一次）。 */
    public static boolean prompted(Context context) {
        return prefs(context).getBoolean(KEY_PROMPTED, false);
    }

    public static void setPrompted(Context context) {
        prefs(context).edit().putBoolean(KEY_PROMPTED, true).apply();
    }
}
