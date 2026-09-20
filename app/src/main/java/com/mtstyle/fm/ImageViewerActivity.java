package com.mtstyle.fm;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内置图片查看器（MT 风格）：
 * - 打开即显示原图（按屏幕尺寸 2 倍采样，保留缩放细节，不做压缩预览）；
 * - 双指缩放 / 双击放大 / 放大后拖动；
 * - 未放大时左右滑动切换同目录图片；
 * - 单击隐藏或显示上下信息栏，底栏显示分辨率、大小、修改时间。
 */
public class ImageViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "image_path";
    public static final String EXTRA_DIR = "image_dir";

    private final List<File> images = new ArrayList<>();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int[] resolution = new int[]{0, 0};

    private ZoomImageView imageView;
    private TextView titleView;
    private TextView positionView;
    private TextView infoView;
    private TextView loadingView;
    private View topBar;
    private View bottomBar;

    private File folder;
    private int index;
    private int loadToken;
    private boolean barsVisible = true;
    private Bitmap current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String dir = getIntent().getStringExtra(EXTRA_DIR);
        File target = path == null ? null : new File(path);
        if (target == null || !target.exists()) {
            toast("找不到该图片");
            finish();
            return;
        }
        folder = dir != null ? new File(dir) : target.getParentFile();

        imageView = findViewById(R.id.viewer_image);
        titleView = findViewById(R.id.viewer_title);
        positionView = findViewById(R.id.viewer_position);
        infoView = findViewById(R.id.viewer_info);
        loadingView = findViewById(R.id.viewer_loading);
        topBar = findViewById(R.id.viewer_top);
        bottomBar = findViewById(R.id.viewer_bottom);

        findViewById(R.id.viewer_back).setOnClickListener(v -> finish());
        findViewById(R.id.viewer_more).setOnClickListener(v -> showMoreMenu());
        findViewById(R.id.viewer_share).setOnClickListener(v -> share());
        findViewById(R.id.viewer_info_action).setOnClickListener(v -> showDetails());
        findViewById(R.id.viewer_open_with).setOnClickListener(v -> openWithOtherApp());

        imageView.setListener(new ZoomImageView.Listener() {
            @Override
            public void onSingleTap() {
                toggleBars();
            }

            @Override
            public void onSwipe(int delta) {
                show(index + delta);
            }
        });

        collectImages(target);
        show(indexOf(target));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loadToken++;
        pool.shutdownNow();
        if (imageView != null) {
            imageView.setImageDrawable(null);
        }
        current = null;
    }

    /** 收集同目录图片（按文件名排序），并定位当前图片。 */
    private void collectImages(File target) {
        File[] files = folder == null ? null : folder.listFiles();
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File file : files) {
                if (IconLoader.isImage(file)) {
                    images.add(file);
                }
            }
        }
        if (images.isEmpty()) {
            images.add(target);
        }
    }

    private int indexOf(File target) {
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getAbsolutePath().equals(target.getAbsolutePath())) {
                return i;
            }
        }
        return 0;
    }

    /** 显示第 i 张图片（越界时提示并保持不变）。 */
    private void show(int i) {
        if (images.isEmpty()) {
            return;
        }
        if (i < 0 || i >= images.size()) {
            toast(getString(R.string.image_viewer_none));
            return;
        }
        index = i;
        final File file = images.get(i);
        titleView.setText(file.getName());
        positionView.setText(images.size() > 1
                ? getString(R.string.image_viewer_count, i + 1, images.size())
                : "");
        resolution[0] = 0;
        resolution[1] = 0;
        infoView.setText(Util.formatSize(file.length()) + "   "
                + Util.formatDate(file.lastModified()));
        loadingView.setVisibility(View.VISIBLE);

        final int token = ++loadToken;
        pool.execute(() -> {
            final Bitmap bitmap = decodeOriginal(file);
            final int[] size = new int[]{resolution[0], resolution[1]};
            main.post(() -> {
                if (token != loadToken || isFinishing() || isDestroyed()) {
                    return;
                }
                loadingView.setVisibility(View.GONE);
                if (bitmap == null) {
                    toast(getString(R.string.image_viewer_failed, file.getName()));
                    return;
                }
                current = bitmap;
                imageView.setImageBitmap(bitmap);
                if (size[0] > 0) {
                    infoView.setText(size[0] + "×" + size[1] + "   "
                            + Util.formatSize(file.length()) + "   "
                            + Util.formatDate(file.lastModified()));
                }
            });
        });
    }

    /** 解码原图：按屏幕尺寸 2 倍采样，兼顾清晰度与内存。 */
    private Bitmap decodeOriginal(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            resolution[0] = bounds.outWidth;
            resolution[1] = bounds.outHeight;
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int target = Math.max(metrics.widthPixels, metrics.heightPixels) * 2;
            if (target < 1600) {
                target = 1600;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxSide / (options.inSampleSize * 2) >= target) {
                options.inSampleSize *= 2;
            }
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try {
                return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            } catch (OutOfMemoryError oom) {
                options.inSampleSize *= 2;
                return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        int visibility = barsVisible ? View.VISIBLE : View.GONE;
        topBar.setVisibility(visibility);
        bottomBar.setVisibility(visibility);
    }

    private File currentFile() {
        return index >= 0 && index < images.size() ? images.get(index) : null;
    }

    private void showMoreMenu() {
        final File file = currentFile();
        if (file == null) {
            return;
        }
        List<MtMenu.Item> items = new ArrayList<>();
        items.add(MtMenu.item(getString(R.string.act_share), R.drawable.ic_download,
                this::share));
        items.add(MtMenu.item(getString(R.string.act_info), R.drawable.ic_file,
                this::showDetails));
        items.add(MtMenu.item(getString(R.string.apk_open_with), R.drawable.ic_open,
                this::openWithOtherApp));
        items.add(MtMenu.item(getString(R.string.image_reset_zoom), R.drawable.ic_refresh,
                () -> imageView.resetZoom()));
        items.add(MtMenu.item(getString(R.string.copy_path), R.drawable.ic_text,
                this::copyPath));
        MtMenu.show(this, file.getName(),
                Util.formatSize(file.length()) + " · " + Util.formatDate(file.lastModified()),
                items);
    }

    private void copyPath() {
        File file = currentFile();
        if (file == null) {
            return;
        }
        try {
            android.content.ClipboardManager manager = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(android.content.ClipData.newPlainText(
                        "path", file.getAbsolutePath()));
                toast(getString(R.string.copy_path_done));
            }
        } catch (Exception e) {
            toast(getString(R.string.failed));
        }
    }

    private void showDetails() {
        final File file = currentFile();
        if (file == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append("名称：").append(file.getName()).append('\n');
        if (resolution[0] > 0) {
            text.append("分辨率：").append(resolution[0]).append(" × ")
                    .append(resolution[1]).append('\n');
        }
        text.append("大小：").append(Util.formatSize(file.length())).append('\n');
        text.append("修改时间：").append(Util.formatDate(file.lastModified())).append('\n');
        text.append("路径：").append(file.getAbsolutePath());
        new AlertDialog.Builder(this)
                .setTitle(R.string.image_info_title)
                .setMessage(text.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void share() {
        File file = currentFile();
        if (file == null) {
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeOf(file));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.act_share)));
        } catch (Exception e) {
            toast(getString(R.string.failed) + ": " + e.getMessage());
        }
    }

    private void openWithOtherApp() {
        File file = currentFile();
        if (file == null) {
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeOf(file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.apk_open_with)));
        } catch (Exception e) {
            toast(getString(R.string.failed) + ": " + e.getMessage());
        }
    }

    private String mimeOf(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
        switch (ext) {
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "bmp":
                return "image/bmp";
            case "heic":
            case "heif":
                return "image/heic";
            default:
                return "image/jpeg";
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
