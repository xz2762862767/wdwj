package com.mtstyle.fm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Activity 记录：由无障碍服务写入，保存在内部存储的文本文件中。 */
public final class ActivityLog {

    private static final String FILE_NAME = ".myfiles_activity.log";
    private static final long MAX_BYTES = 512 * 1024;
    private static final int KEEP_LINES = 800;

    public static class Record {
        public long time;
        public String packageName;
        public String className;
    }

    private ActivityLog() {
    }

    public static File file() {
        return new File(Environment.getExternalStorageDirectory(), FILE_NAME);
    }

    public static synchronized void append(String packageName, String className) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        File file = file();
        try {
            if (file.exists() && file.length() > MAX_BYTES) {
                List<Record> records = read();
                int from = Math.max(0, records.size() - KEEP_LINES);
                StringBuilder builder = new StringBuilder();
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                for (int i = from; i < records.size(); i++) {
                    Record record = records.get(i);
                    builder.append(format.format(new Date(record.time))).append('|')
                            .append(record.packageName).append('|')
                            .append(record.className).append('\n');
                }
                write(builder.toString());
            }
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "|" + packageName + "|" + (className == null ? "" : className) + "\n";
            FileOutputStream out = new FileOutputStream(file, true);
            out.write(line.getBytes("UTF-8"));
            out.close();
        } catch (IOException ignored) {
        }
    }

    public static synchronized List<Record> read() {
        List<Record> records = new ArrayList<>();
        File file = file();
        if (!file.exists()) {
            return records;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), Charset.forName("UTF-8")))) {
            String line;
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length < 2) {
                    continue;
                }
                Record record = new Record();
                try {
                    Date date = format.parse(parts[0]);
                    record.time = date == null ? 0L : date.getTime();
                } catch (Exception e) {
                    record.time = 0L;
                }
                record.packageName = parts[1];
                record.className = parts.length > 2 ? parts[2] : "";
                records.add(record);
            }
        } catch (IOException ignored) {
        }
        Collections.reverse(records);
        return records;
    }

    public static synchronized void clear() {
        write("");
    }

    private static void write(String content) {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file(), false), Charset.forName("UTF-8"))) {
            writer.write(content);
        } catch (IOException ignored) {
        }
    }

    public static ComponentName component(Context context) {
        return new ComponentName(context, ActivityTrackService.class);
    }

    public static boolean isServiceEnabled(Context context) {
        String enabled = android.provider.Settings.Secure.getString(context.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        String target = component(context).flattenToString();
        String targetShort = component(context).flattenToShortString();
        return enabled.contains(target) || enabled.contains(targetShort);
    }

    public static void openSettings(Context context) {
        try {
            context.startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }
}
