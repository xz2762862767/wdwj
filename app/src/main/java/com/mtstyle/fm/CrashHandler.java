package com.mtstyle.fm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 全局崩溃捕获：把堆栈写入可访问的日志文件，便于排查闪退。 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    public static final String LOG_NAME = "我的文件_crash.log";
    private static final String PREFS = "fm_crash";
    private static final String KEY_PENDING = "pending";
    private static final long MAX_BYTES = 256 * 1024;

    private final Context appContext;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashHandler(Context context, Thread.UncaughtExceptionHandler previous) {
        this.appContext = context;
        this.previous = previous;
    }

    public static synchronized void install(Context context) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof CrashHandler) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(
                new CrashHandler(context.getApplicationContext(), previous));
    }

    /** 日志文件位置：优先 /sdcard/Download，其次 /sdcard，最后应用私有目录。 */
    public static File logFile(Context context) {
        File download = new File(Environment.getExternalStorageDirectory(), "Download");
        if (download.isDirectory() && download.canWrite()) {
            return new File(download, LOG_NAME);
        }
        File root = new File(Environment.getExternalStorageDirectory(), LOG_NAME);
        if (root.getParentFile() != null && root.getParentFile().canWrite()) {
            return root;
        }
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            return new File(external, LOG_NAME);
        }
        return new File(context.getFilesDir(), LOG_NAME);
    }

    public static boolean hasPending(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_PENDING, false);
    }

    public static void clearPending(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PENDING, false).apply();
    }

    public static String readLog(Context context) {
        File file = logFile(context);
        if (!file.exists()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), Charset.forName("UTF-8")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } catch (IOException ignored) {
        }
        return builder.toString();
    }

    public static void clearLog(Context context) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(logFile(context), false);
        } catch (IOException ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
        clearPending(context);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        try {
            write(thread, error);
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_PENDING, true).apply();
        } catch (Throwable ignored) {
        }
        if (previous != null) {
            previous.uncaughtException(thread, error);
        }
    }

    private void write(Thread thread, Throwable error) {
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        writer.println("================ 崩溃报告 ================");
        writer.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()));
        writer.println("应用: " + appContext.getPackageName());
        writer.println("设备: " + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        writer.println("线程: " + thread.getName());
        writer.println("异常: " + error);
        writer.println("--- 堆栈 ---");
        error.printStackTrace(writer);
        writer.println();
        writer.flush();

        File file = logFile(appContext);
        if (file.exists() && file.length() > MAX_BYTES) {
            file.delete();
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file, true);
            out.write(buffer.toString().getBytes("UTF-8"));
            out.flush();
        } catch (IOException ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
