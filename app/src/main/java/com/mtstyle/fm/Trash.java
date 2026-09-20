package com.mtstyle.fm;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** 回收站：删除的文件先移入回收站，可恢复或彻底删除。 */
public final class Trash {

    private static final String DIR_NAME = ".myfiles_trash";
    private static final String META_NAME = ".meta";

    private static final FileOps.Progress NO_PROGRESS = FileOps.SILENT;

    public static class Entry {
        public File dir;
        public String name;
        public String original;
        public long time;
        public long size;
        public int count;

        public File source() {
            return new File(dir, name);
        }
    }

    private Trash() {
    }

    public static File root() {
        return new File(Environment.getExternalStorageDirectory(), DIR_NAME);
    }

    public static boolean isInTrash(File file) {
        if (file == null) {
            return false;
        }
        String trash = root().getAbsolutePath();
        String path = file.getAbsolutePath();
        return path.equals(trash) || path.startsWith(trash + "/");
    }

    /** 移入回收站，返回回收站内的新位置。 */
    public static File moveToTrash(File file) throws IOException {
        File container = root();
        if (!container.exists() && !container.mkdirs()) {
            throw new IOException("无法创建回收站目录：" + container.getAbsolutePath());
        }
        String id = System.currentTimeMillis() + "_"
                + String.format(Locale.US, "%04d", new Random().nextInt(10000));
        File slot = new File(container, id);
        if (!slot.mkdirs()) {
            throw new IOException("无法创建回收站条目：" + slot.getAbsolutePath());
        }
        File target = new File(slot, file.getName());
        if (!file.renameTo(target)) {
            if (file.isDirectory()) {
                FileOps.copy(file, target, NO_PROGRESS, new int[1], 0);
            } else {
                FileOps.copy(file, target, NO_PROGRESS, new int[1], 1);
            }
            FileOps.deleteRecursively(file);
        }
        writeMeta(slot, file.getAbsolutePath(), System.currentTimeMillis());
        return target;
    }

    /** 恢复回收站条目到原位置，返回恢复后的文件。 */
    public static File restore(Entry entry) throws IOException {
        File source = entry.source();
        if (!source.exists()) {
            throw new IOException("回收站内文件已不存在");
        }
        File original = new File(entry.original);
        File parent = original.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建原目录：" + parent.getAbsolutePath());
        }
        File target = FileOps.uniqueTarget(parent == null ? new File("/") : parent, original.getName());
        if (!source.renameTo(target)) {
            FileOps.copy(source, target, NO_PROGRESS, new int[1], 0);
            FileOps.deleteRecursively(source);
        }
        FileOps.deleteRecursively(entry.dir);
        return target;
    }

    public static void deleteForever(Entry entry) {
        FileOps.deleteRecursively(entry.dir);
    }

    public static int clearAll() {
        int removed = 0;
        File container = root();
        File[] children = container.listFiles();
        if (children != null) {
            for (File child : children) {
                if (FileOps.deleteRecursively(child)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /** 回收站内的条目列表（按删除时间倒序）。 */
    public static List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        File container = root();
        File[] children = container.listFiles();
        if (children == null) {
            return entries;
        }
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            String[] meta = readMeta(child);
            String name = meta[0];
            long time = parse(meta[1], child.lastModified());
            File content = null;
            File[] inner = child.listFiles();
            if (inner != null) {
                for (File file : inner) {
                    if (!META_NAME.equals(file.getName())) {
                        content = file;
                        break;
                    }
                }
            }
            Entry entry = new Entry();
            entry.dir = child;
            entry.name = content != null ? content.getName() : name;
            entry.original = name;
            entry.time = time;
            if (content != null) {
                entry.size = FileOps.folderSize(content, FileOps.SILENT);
                entry.count = FileOps.countFiles(content);
            }
            entries.add(entry);
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.time, a.time);
            }
        });
        return entries;
    }

    /** 轻量统计回收站条目数（不扫描大小）。 */
    public static int quickCount() {
        File[] children = root().listFiles();
        return children == null ? 0 : children.length;
    }

    public static int size() {
        return list().size();
    }

    public static long totalSize() {
        long sum = 0L;
        for (Entry entry : list()) {
            sum += Math.max(0, entry.size);
        }
        return sum;
    }

    private static void writeMeta(File slot, String original, long time) {
        File meta = new File(slot, META_NAME);
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(meta),
                Charset.forName("UTF-8"))) {
            writer.write(original);
            writer.write("\n");
            writer.write(String.valueOf(time));
        } catch (IOException ignored) {
        }
    }

    private static String[] readMeta(File slot) {
        File meta = new File(slot, META_NAME);
        String original = "";
        String time = "0";
        if (meta.exists()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(meta), Charset.forName("UTF-8")))) {
                String line = reader.readLine();
                if (line != null) {
                    original = line;
                }
                String second = reader.readLine();
                if (second != null) {
                    time = second;
                }
            } catch (IOException ignored) {
            }
        }
        return new String[]{original, time};
    }

    private static long parse(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return fallback;
        }
    }
}
