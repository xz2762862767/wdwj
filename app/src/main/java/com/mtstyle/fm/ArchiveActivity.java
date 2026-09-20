package com.mtstyle.fm;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 内置压缩包浏览器：列出 zip/jar 条目，可提取单条或整体解压。 */
public class ArchiveActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "archive_path";
    public static final String EXTRA_DIR = "archive_base_dir";

    private File zipFile;
    private File baseDir;
    private SimpleRowAdapter adapter;
    private final List<ZipEntry> entries = new ArrayList<>();
    private ListView listView;
    private TextView titleView;
    private TextView statusView;
    private TextView emptyView;
    private long totalSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_archive);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        zipFile = path == null ? null : new File(path);
        String dir = getIntent().getStringExtra(EXTRA_DIR);
        baseDir = dir != null ? new File(dir)
                : (zipFile != null ? zipFile.getParentFile() : null);
        if (zipFile == null || !zipFile.exists()) {
            toast(getString(R.string.archive_open_failed, getString(R.string.archive_title)));
            finish();
            return;
        }

        titleView = findViewById(R.id.arc_title);
        statusView = findViewById(R.id.arc_status);
        emptyView = findViewById(R.id.arc_empty);
        listView = findViewById(R.id.arc_list);
        titleView.setText(zipFile.getName());

        adapter = new SimpleRowAdapter(this);
        listView.setAdapter(adapter);
        ImageView back = findViewById(R.id.arc_back);
        back.setOnClickListener(v -> finish());
        findViewById(R.id.arc_action).setOnClickListener(v -> extractAll());
        findViewById(R.id.arc_more).setOnClickListener(v -> showMoreMenu());

        listView.setOnItemClickListener((parent, view, position, id) -> {
            ZipEntry entry = entries.get(position);
            if (entry.isDirectory()) {
                toast(getString(R.string.archive_entry_dir));
                return;
            }
            openEntry(entry);
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            ZipEntry entry = entries.get(position);
            List<MtMenu.Item> items = new ArrayList<>();
            items.add(MtMenu.item(getString(R.string.archive_open_entry), R.drawable.ic_open,
                    () -> openEntry(entry)));
            items.add(MtMenu.item(getString(R.string.archive_extract_item),
                    R.drawable.ic_download, () -> extractEntryToDir(entry)));
            if (!entry.isDirectory() && isEditableXml(entry.getName())) {
                items.add(MtMenu.item(getString(R.string.analysis_xml), R.drawable.ic_code,
                        () -> openXmlEditor(entry.getName())));
            }
            if (!entry.isDirectory() && isDexEntry(entry.getName())) {
                items.add(MtMenu.item(getString(R.string.analysis_dex), R.drawable.ic_code,
                        () -> openDexViewer(entry.getName())));
            }
            MtMenu.show(this, entry.getName(), Util.formatSize(Math.max(0L, entry.getSize())),
                    items);
            return true;
        });

        load();
    }

    private void load() {
        TaskRunner.run(this, getString(R.string.archive_reading), true,
                progress -> {
                    entries.clear();
                    totalSize = 0L;
                    try (ZipFile zip = new ZipFile(zipFile)) {
                        Enumeration<? extends ZipEntry> it = zip.entries();
                        while (it.hasMoreElements()) {
                            ZipEntry entry = it.nextElement();
                            entries.add(entry);
                            if (entry.getSize() > 0) {
                                totalSize += entry.getSize();
                            }
                        }
                    }
                    return;
                },
                (ok, message) -> {
                    if (!ok) {
                        toast(getString(R.string.archive_open_failed, message));
                        finish();
                        return;
                    }
                    bindEntries();
                });
    }

    /** 缩略图最多解码这么多张，避免几百张图把线程和内存拖住。 */
    private static final int THUMB_LIMIT = 200;

    private final java.util.Map<String, android.graphics.drawable.Drawable> thumbs =
            new java.util.HashMap<>();
    private final android.os.Handler uiHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private java.util.concurrent.ExecutorService thumbPool;
    private int thumbDone;

    /** 是不是能解码成图的条目。 */
    private static boolean isImageEntry(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    /** 直接从压缩包里解码缩略图，不落盘。 */
    private android.graphics.drawable.Drawable decodeThumb(String entryName) {
        try (ZipFile zip = new ZipFile(zipFile)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = zip.getInputStream(entry)) {
                android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 96 || bounds.outHeight / (sample * 2) >= 96) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options options =
                    new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            android.graphics.Bitmap bitmap;
            try (java.io.InputStream in = zip.getInputStream(entry)) {
                bitmap = android.graphics.BitmapFactory.decodeStream(in, null, options);
            }
            return bitmap == null ? null
                    : new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** 后台逐张解码，每 4 张刷一次列表；缩略图就绪后由 IconProvider 现取。 */
    private void startThumbLoading() {
        if (thumbPool != null) {
            thumbPool.shutdownNow();
        }
        thumbs.clear();
        thumbDone = 0;
        final java.util.List<String> names = new java.util.ArrayList<>();
        for (ZipEntry entry : entries) {
            if (!entry.isDirectory() && isImageEntry(entry.getName())) {
                names.add(entry.getName());
                if (names.size() >= THUMB_LIMIT) {
                    break;
                }
            }
        }
        if (names.isEmpty()) {
            return;
        }
        thumbPool = java.util.concurrent.Executors.newSingleThreadExecutor();
        thumbPool.execute(() -> {
            for (String name : names) {
                final android.graphics.drawable.Drawable thumb = decodeThumb(name);
                synchronized (thumbs) {
                    if (thumb != null) {
                        thumbs.put(name, thumb);
                    }
                    thumbDone++;
                }
                if (thumbDone % 4 == 0) {
                    uiHandler.post(adapter::notifyDataSetChanged);
                }
            }
            uiHandler.post(adapter::notifyDataSetChanged);
        });
    }

    @Override
    protected void onDestroy() {
        if (thumbPool != null) {
            thumbPool.shutdownNow();
            thumbPool = null;
        }
        super.onDestroy();
    }

    private void bindEntries() {
        List<SimpleRowAdapter.Row> rows = new ArrayList<>();
        for (ZipEntry entry : entries) {
            SimpleRowAdapter.Row row = new SimpleRowAdapter.Row();
            row.iconRes = entry.isDirectory() ? R.drawable.ic_folder : R.drawable.ic_file;
            if (!entry.isDirectory() && isImageEntry(entry.getName())) {
                row.iconKey = entry.getName();
            }
            row.title = entry.getName();
            row.subtitle = (!entry.isDirectory() && entry.getTime() > 0)
                    ? Util.formatDate(entry.getTime()) : "";
            row.trailing = entry.isDirectory() ? "" : Util.formatSize(Math.max(0L, entry.getSize()));
            rows.add(row);
        }
        adapter.setIconProvider(name -> {
            synchronized (thumbs) {
                return thumbs.get(name);
            }
        });
        adapter.setRows(rows);
        startThumbLoading();
        emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        String info = getString(R.string.archive_entries, entries.size()) + "   "
                + Util.formatSize(totalSize) + "   " + zipFile.getAbsolutePath();
        statusView.setText(info);
    }

    private void showMoreMenu() {
        List<MtMenu.Item> items = new ArrayList<>();
        items.add(MtMenu.item(getString(R.string.archive_extract_here), R.drawable.ic_download,
                this::extractAll));
        items.add(MtMenu.item(getString(R.string.refresh), R.drawable.ic_refresh, this::load));
        items.add(MtMenu.item(getString(R.string.apk_open_with), R.drawable.ic_open,
                () -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    Uri uri = FileProvider.getUriForFile(this,
                            getPackageName() + ".fileprovider", zipFile);
                    intent.setDataAndType(uri, "application/zip");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent,
                            getString(R.string.apk_open_with)));
                }));
        MtMenu.show(this, zipFile.getName(),
                getString(R.string.archive_entries, entries.size()), items);
    }

    private void extractAll() {
        File dest = baseDir != null ? baseDir : zipFile.getParentFile();
        if (dest == null) {
            return;
        }
        TaskRunner.run(this, getString(R.string.unzipping), true,
                progress -> FileOps.unzip(zipFile, dest, progress),
                (ok, message) -> {
                    if (ok) {
                        toast(getString(R.string.archive_extracted, entries.size(),
                                dest.getAbsolutePath()));
                    } else {
                        toast(getString(R.string.archive_extract_failed, message));
                    }
                });
    }

    private void extractEntryToDir(ZipEntry entry) {
        File dest = baseDir != null ? baseDir : zipFile.getParentFile();
        if (dest == null) {
            return;
        }
        TaskRunner.run(this, getString(R.string.unzipping), true,
                progress -> {
                    progress.publish(entry.getName(), 0);
                    try (ZipFile zip = new ZipFile(zipFile)) {
                        InputStream in = zip.getInputStream(zip.getEntry(entry.getName()));
                        File out = safeTarget(dest, entry.getName());
                        if (out == null) {
                            throw new Exception("非法条目名");
                        }
                        File parent = out.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw new Exception("无法创建目录");
                        }
                        try (FileOutputStream os = new FileOutputStream(out)) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = in.read(buffer)) > 0) {
                                os.write(buffer, 0, read);
                            }
                        }
                        in.close();
                    }
                    return;
                },
                (ok, message) -> {
                    if (ok) {
                        toast(getString(R.string.archive_item_extracted,
                                Util.joinPath(dest.getAbsolutePath(), entry.getName())));
                    } else {
                        toast(getString(R.string.archive_extract_failed, message));
                    }
                });
    }

    /** 提取单条到缓存并交给内置编辑器 / 外部应用打开。 */
    /** 根目录下的 classes*.dex 等 dex 条目。 */
    private boolean isDexEntry(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".dex") && name.indexOf('/') < 0;
    }

    private void openDexViewer(String entryName) {
        Intent intent = new Intent(this, DexActivity.class);
        intent.putExtra(DexActivity.EXTRA_PATH, zipFile.getAbsolutePath());
        intent.putExtra(DexActivity.EXTRA_ENTRY, entryName);
        startActivity(intent);
    }

    /** 可编辑的 XML 条目：二进制 AndroidManifest.xml 或 res/xml、res/layout 下的 xml。 */
    private boolean isEditableXml(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        if (lower.equals("androidmanifest.xml")) {
            return true;
        }
        return lower.endsWith(".xml") && (lower.startsWith("res/xml/")
                || lower.startsWith("res/layout/") || lower.startsWith("res/anim/")
                || lower.startsWith("res/drawable/") || lower.startsWith("res/menu/"));
    }

    /** 用内置 XML 编辑器打开包内条目（二进制 XML 自动解码为文本）。 */
    private void openXmlEditor(String entryName) {
        try {
            Intent intent = new Intent(this, XmlEditorActivity.class);
            intent.putExtra(XmlEditorActivity.EXTRA_PATH, zipFile.getAbsolutePath());
            intent.putExtra(XmlEditorActivity.EXTRA_ENTRY, entryName);
            startActivity(intent);
        } catch (Exception e) {
            toast("无法编辑：" + e.getMessage());
        }
    }

    private void openEntry(ZipEntry entry) {
        // dex / xml / arsc 让查看器直接从包里读，不必先解压
        if (!entry.isDirectory() && BuiltinOpen.openInArchive(this, zipFile, entry.getName())) {
            return;
        }
        File cacheDir = new File(getCacheDir(), "archive");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            toast(getString(R.string.archive_extract_failed, "无法创建缓存目录"));
            return;
        }
        File target = safeTarget(cacheDir, entry.getName());
        if (target == null) {
            toast(getString(R.string.archive_extract_failed, "非法条目名"));
            return;
        }
        TaskRunner.run(this, getString(R.string.archive_extracting), true,
                progress -> {
                    progress.publish(entry.getName(), 0);
                    try (ZipFile zip = new ZipFile(zipFile)) {
                        InputStream in = zip.getInputStream(zip.getEntry(entry.getName()));
                        File parent = target.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw new Exception("无法创建目录");
                        }
                        try (FileOutputStream os = new FileOutputStream(target)) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = in.read(buffer)) > 0) {
                                os.write(buffer, 0, read);
                            }
                        }
                        in.close();
                    }
                    return;
                },
                (ok, message) -> {
                    if (!ok) {
                        toast(getString(R.string.archive_extract_failed, message));
                        return;
                    }
                    // 提取出来先用内置查看器打开：dex / arsc / 二进制 xml / so 在系统里
                    // 压根没有应用能接，以前这里一律走外部，点了只会弹「找不到应用」。
                    if (!BuiltinOpen.open(this, target)) {
                        openWith(target);
                    }
                });
    }

    private void openWith(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            String extension = MimeTypeMap.getFileExtensionFromUrl(file.getName());
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime != null ? mime : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.apk_open_with)));
        } catch (Exception e) {
            toast(getString(R.string.archive_extract_failed, e.getMessage()));
        }
    }

    /** 防目录穿越：条目只允许落在 base 目录内。 */
    private File safeTarget(File base, String name) {
        try {
            File target = new File(base, name);
            String basePath = base.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            if (!targetPath.startsWith(basePath.endsWith(File.separator)
                    ? basePath : basePath + File.separator)) {
                return null;
            }
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
