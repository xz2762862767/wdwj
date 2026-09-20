package com.mtstyle.fm;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 文件操作集合：复制 / 移动 / 删除 / 统计 / ZIP 压缩解压 / 搜索。 */
public final class FileOps {

    public static final int MAX_SEARCH_RESULTS = 1000;

    private static final int BUF_SIZE = 64 * 1024;

    private FileOps() {
    }

    /** 用户取消时抛出。 */
    public static class Cancelled extends IOException {
        public Cancelled() {
            super("已取消");
        }
    }

    public interface Progress {
        /** percent 为 -1 表示不确定进度。 */
        void publish(String message, int percent);

        boolean isCancelled();
    }

    /** 简单进度实现，用于无界面场景。 */
    public static final Progress SILENT = new Progress() {
        @Override
        public void publish(String message, int percent) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    // ==================== 基础工具 ====================

    public static boolean isSymlink(File file) {
        try {
            return !file.getCanonicalPath().equals(file.getAbsolutePath());
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean deleteRecursively(File file) {
        if (file.isDirectory() && !isSymlink(file)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    /** 递归统计文件（非目录）数量。 */
    public static int countFiles(File file) {
        if (!file.isDirectory() || isSymlink(file)) {
            return 1;
        }
        int count = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                count += countFiles(child);
            }
        }
        return count;
    }

    /** 递归统计目录占用字节数。 */
    public static long folderSize(File folder, Progress progress) {
        if (!folder.isDirectory() || isSymlink(folder)) {
            return folder.length();
        }
        long sum = 0L;
        File[] children = folder.listFiles();
        if (children != null) {
            for (File child : children) {
                if (progress != null && progress.isCancelled()) {
                    return sum;
                }
                sum += folderSize(child, progress);
            }
        }
        return sum;
    }

    /** 目标不存在时直接返回，否则生成 name(1).ext 形式的新名字。 */
    public static File uniqueTarget(File dir, String name) {
        File target = new File(dir, name);
        if (!target.exists()) {
            return target;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 10000; i++) {
            target = new File(dir, base + "(" + i + ")" + ext);
            if (!target.exists()) {
                return target;
            }
        }
        return target;
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ==================== 复制 / 移动 ====================

    public static void copy(File src, File dst, Progress p, int[] counter, int total)
            throws IOException {
        if (p.isCancelled()) {
            throw new Cancelled();
        }
        if (src.isDirectory() && !isSymlink(src)) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException("无法创建目录: " + dst);
            }
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    copy(child, new File(dst, child.getName()), p, counter, total);
                }
            }
            dst.setLastModified(src.lastModified());
        } else {
            copyFile(src, dst, p);
            counter[0]++;
            if (total > 0) {
                p.publish("正在复制 " + src.getName(),
                        Math.min(99, counter[0] * 100 / total));
            }
        }
    }

    private static void copyFile(File src, File dst, Progress p) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[BUF_SIZE];
            int len;
            while ((len = in.read(buf)) > 0) {
                if (p.isCancelled()) {
                    throw new Cancelled();
                }
                out.write(buf, 0, len);
            }
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        dst.setLastModified(src.lastModified());
    }

    public static void move(File src, File dst, Progress p, int[] counter, int total)
            throws IOException {
        if (p.isCancelled()) {
            throw new Cancelled();
        }
        if (src.renameTo(dst)) {
            counter[0] += Math.max(1, countFiles(dst));
            if (total > 0) {
                p.publish("正在移动 " + src.getName(),
                        Math.min(99, counter[0] * 100 / total));
            }
            return;
        }
        copy(src, dst, p, counter, total);
        deleteRecursively(src);
    }

    // ==================== ZIP ====================

    public static void zip(List<File> sources, File zipFile, Progress p) throws IOException {
        int total = 0;
        for (File f : sources) {
            total += countFiles(f);
        }
        if (total <= 0) {
            total = 1;
        }
        int[] done = {0};
        ZipOutputStream zos = null;
        try {
            zos = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(zipFile), BUF_SIZE));
            zos.setLevel(6);
            for (File src : sources) {
                addToZip(zos, src, src.getName(), p, done, total);
            }
        } finally {
            closeQuietly(zos);
        }
    }

    private static void addToZip(ZipOutputStream zos, File file, String entryName,
                                 Progress p, int[] done, int total) throws IOException {
        if (p.isCancelled()) {
            throw new Cancelled();
        }
        if (file.isDirectory() && !isSymlink(file)) {
            zos.putNextEntry(new ZipEntry(entryName + "/"));
            zos.closeEntry();
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addToZip(zos, child, entryName + "/" + child.getName(), p, done, total);
                }
            }
            return;
        }
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(file.lastModified());
        zos.putNextEntry(entry);
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[BUF_SIZE];
            int len;
            while ((len = in.read(buf)) > 0) {
                if (p.isCancelled()) {
                    throw new Cancelled();
                }
                zos.write(buf, 0, len);
            }
        } finally {
            closeQuietly(in);
        }
        zos.closeEntry();
        done[0]++;
        p.publish("正在压缩 " + file.getName(), Math.min(99, done[0] * 100 / total));
    }

    /** 解压 zip 到目标目录；GBK 作为无 UTF-8 标记条目的默认编码。 */
    public static void unzip(File zipFile, File destDir, Progress p) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("无法创建目录: " + destDir);
        }
        String destPath = destDir.getCanonicalPath();
        ZipFile zip = null;
        try {
            zip = new ZipFile(zipFile, Charset.forName("GBK"));
            int total = Math.max(1, zip.size());
            int done = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (p.isCancelled()) {
                    throw new Cancelled();
                }
                ZipEntry entry = entries.nextElement();
                File out = new File(destDir, entry.getName());
                if (!out.getCanonicalPath().startsWith(destPath)) {
                    continue;   // 阻止 zip slip
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    InputStream in = null;
                    OutputStream os = null;
                    try {
                        in = zip.getInputStream(entry);
                        os = new FileOutputStream(out);
                        byte[] buf = new byte[BUF_SIZE];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            if (p.isCancelled()) {
                                throw new Cancelled();
                            }
                            os.write(buf, 0, len);
                        }
                        os.flush();
                    } finally {
                        closeQuietly(in);
                        closeQuietly(os);
                    }
                    if (entry.getTime() > 0) {
                        out.setLastModified(entry.getTime());
                    }
                }
                done++;
                p.publish("正在解压 " + entry.getName(),
                        Math.min(99, done * 100 / total));
            }
        } finally {
            closeQuietly(zip);
        }
    }

    // ==================== 搜索 ====================

    public static List<File> search(File root, String keyword, Progress p) {
        List<File> result = new ArrayList<>();
        if (root == null || keyword == null || keyword.trim().isEmpty()) {
            return result;
        }
        searchRecursive(root, keyword.trim().toLowerCase(Locale.US), result, p, new int[]{0});
        return result;
    }

    private static void searchRecursive(File dir, String keyword, List<File> result,
                                        Progress p, int[] visited) {
        if (p.isCancelled() || result.size() >= MAX_SEARCH_RESULTS) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (p.isCancelled() || result.size() >= MAX_SEARCH_RESULTS) {
                return;
            }
            visited[0]++;
            if (visited[0] % 50 == 0) {
                p.publish("已扫描 " + visited[0] + " 项，命中 " + result.size() + " 项", -1);
            }
            if (child.getName().toLowerCase(Locale.US).contains(keyword)) {
                result.add(child);
            }
            if (child.isDirectory() && !isSymlink(child)) {
                searchRecursive(child, keyword, result, p, visited);
            }
        }
    }
}
