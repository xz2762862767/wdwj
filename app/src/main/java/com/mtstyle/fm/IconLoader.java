package com.mtstyle.fm;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文件图标加载器：
 * 1) 根据扩展名返回文件类型图标；
 * 2) 对 .apk 自动解析包信息并提取其真实应用图标（异步 + LRU 缓存）；
 * 3) 对图片文件异步解码真实缩略图（MT 风格：列表直接显示图片本身）。
 */
public final class IconLoader {

    private static final LruCache<String, Drawable> CACHE = new LruCache<>(300);
    /** 图片缩略图缓存：按字节数计（约占堆 1/8），避免大列表 OOM。 */
    private static LruCache<String, Drawable> IMAGE_CACHE;
    /** 缩略图解码边长（px），列表图标最大约 34dp*3=102px，留点余量。 */
    private static final int THUMB_PX = 192;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private IconLoader() {
    }

    private static synchronized LruCache<String, Drawable> imageCache() {
        if (IMAGE_CACHE == null) {
            int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
            if (maxKb < 4 * 1024) {
                maxKb = 4 * 1024;
            }
            IMAGE_CACHE = new LruCache<String, Drawable>(maxKb) {
                @Override
                protected int sizeOf(String key, Drawable value) {
                    if (value instanceof BitmapDrawable) {
                        Bitmap bitmap = ((BitmapDrawable) value).getBitmap();
                        if (bitmap != null && !bitmap.isRecycled()) {
                            return Math.max(1, bitmap.getByteCount() / 1024);
                        }
                    }
                    return 1;
                }
            };
        }
        return IMAGE_CACHE;
    }

    public interface Callback {
        void onIcon(Drawable drawable);
    }

    public static boolean isApk(File file) {
        return file != null && file.isFile()
                && file.getName().toLowerCase(Locale.US).endsWith(".apk");
    }

    /** 是否为可解码预览的图片文件（svg 等矢量格式不包括，BitmapFactory 解不了）。 */
    public static boolean isImage(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        switch (extension(file)) {
            case "jpg": case "jpeg": case "png": case "gif": case "bmp":
            case "webp": case "heic": case "heif": case "avif":
                return true;
            default:
                return false;
        }
    }

    /** 可用内置压缩包浏览器直接打开的格式（zip 系）。 */
    public static boolean isArchiveOpenable(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String ext = extension(file);
        return "zip".equals(ext) || "jar".equals(ext) || "zipx".equals(ext)
                || "xpi".equals(ext) || "epub".equals(ext) || "docx".equals(ext)
                || "xlsx".equals(ext) || "pptx".equals(ext) || "apks".equals(ext);
    }

    /** 其它压缩格式（rar/7z/tar/gz…），内置浏览器不支持，交给外部应用。 */
    public static boolean isArchiveOther(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String ext = extension(file);
        switch (ext) {
            case "rar": case "7z": case "tar": case "gz": case "bz2":
            case "xz": case "tgz": case "lz4": case "cab": case "iso":
                return true;
            default:
                return false;
        }
    }

    private static String extension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    /** 是否为可用内置编辑器打开的文本类文件。 */
    public static boolean isTextLike(File file) {
        if (file == null || file.isDirectory()) {
            return false;
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.US);
        switch (ext) {
            case "txt": case "md": case "log": case "json": case "xml":
            case "csv": case "ini": case "conf": case "properties": case "yml": case "yaml":
            case "java": case "kt": case "c": case "cpp": case "h": case "py":
            case "js": case "ts": case "html": case "css": case "sh": case "go":
            case "rs": case "php": case "rb": case "sql": case "smali": case "gradle":
                return true;
            default:
                return false;
        }
    }

    /** 根据文件类型返回列表用图标。 */
    public static int typeIcon(File file) {
        if (file.isDirectory()) {
            return R.drawable.ic_folder;
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
        switch (ext) {
            case "apk":
                return R.drawable.ic_apk;
            case "jpg": case "jpeg": case "png": case "gif": case "bmp":
            case "webp": case "heic": case "heif": case "svg":
                return R.drawable.ic_image;
            case "mp4": case "mkv": case "avi": case "3gp": case "mov":
            case "webm": case "flv": case "rmvb":
                return R.drawable.ic_video;
            case "mp3": case "wav": case "flac": case "aac": case "ogg":
            case "m4a": case "ape": case "amr": case "mid":
                return R.drawable.ic_audio;
            case "zip": case "rar": case "7z": case "tar": case "gz":
            case "bz2": case "xz": case "jar": case "iso":
                return R.drawable.ic_zip;
            case "txt": case "md": case "log": case "json": case "xml":
            case "csv": case "ini": case "conf": case "properties": case "yml":
                return R.drawable.ic_text;
            case "pdf":
                return R.drawable.ic_pdf;
            case "java": case "kt": case "c": case "cpp": case "h": case "py":
            case "js": case "ts": case "html": case "css": case "sh": case "go":
            case "rs": case "php": case "rb": case "sql": case "smali":
                return R.drawable.ic_code;
            default:
                return R.drawable.ic_file;
        }
    }

    /**
     * 绑定图标到 ImageView：立即显示类型图标或缓存图标，
     * APK 则在后台解析后替换为真实应用图标。
     */
    public static void bind(final ImageView view, final File file) {
        final String key = file.getAbsolutePath();
        view.setTag(key);

        Drawable cached = CACHE.get(key);
        if (cached != null) {
            view.setImageDrawable(cached);
            return;
        }
        // 图片文件：异步解码真实缩略图（MT 风格列表预览）
        if (isImage(file)) {
            bindImageThumb(view, file, key);
            return;
        }
        view.setImageResource(typeIcon(file));

        if (!isApk(file)) {
            return;
        }
        final Context appContext = view.getContext().getApplicationContext();
        POOL.execute(() -> {
            final Drawable icon = extractApkIcon(appContext, file);
            if (icon == null) {
                return;
            }
            CACHE.put(key, icon);
            MAIN.post(() -> {
                if (key.equals(view.getTag())) {
                    view.setImageDrawable(icon);
                }
            });
        });
    }

    /** 解析 APK 文件中的真实应用图标。 */
    public static Drawable extractApkIcon(Context context, File apk) {
        if (!isApk(apk)) {
            return null;
        }
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (info == null || info.applicationInfo == null) {
                return null;
            }
            ApplicationInfo appInfo = info.applicationInfo;
            appInfo.sourceDir = apk.getAbsolutePath();
            appInfo.publicSourceDir = apk.getAbsolutePath();
            Drawable drawable = appInfo.loadIcon(pm);
            if (drawable != null) {
                drawable.setBounds(0, 0, drawable.getIntrinsicWidth() > 0
                        ? drawable.getIntrinsicWidth() : 48,
                        drawable.getIntrinsicHeight() > 0
                                ? drawable.getIntrinsicHeight() : 48);
            }
            return drawable;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** 解析 APK 的包信息（供详情/安装前展示）。 */
    public static PackageInfo archiveInfo(Context context, File apk) {
        try {
            return context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_PERMISSIONS);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 图片缩略图 ====================

    /** 异步解码图片缩略图（方图裁切 + 圆角），成功后回填 ImageView。 */
    private static void bindImageThumb(final ImageView view, final File file, final String tag) {
        final Context appContext = view.getContext().getApplicationContext();
        view.setImageResource(R.drawable.ic_image);
        final String cacheKey = file.getAbsolutePath() + "@" + file.lastModified();
        Drawable cached = imageCache().get(cacheKey);
        if (cached != null) {
            view.setImageDrawable(cached);
            return;
        }
        POOL.execute(() -> {
            final Drawable thumb = decodeThumb(file, appContext);
            if (thumb == null) {
                return;
            }
            imageCache().put(cacheKey, thumb);
            MAIN.post(() -> {
                if (tag.equals(view.getTag())) {
                    view.setImageDrawable(thumb);
                }
            });
        });
    }

    /** 解码图片为方形圆角缩略图；失败返回 null（界面保持类型图标）。 */
    public static Drawable decodeThumb(File file, Context context) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, THUMB_PX);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (decoded == null) {
                return null;
            }
            Bitmap square = squareCrop(decoded, THUMB_PX);
            if (square != decoded) {
                decoded.recycle();
            }
            Bitmap rounded = roundCorners(square, THUMB_PX * 0.14f);
            if (rounded != square) {
                square.recycle();
            }
            return new BitmapDrawable(context.getResources(), rounded);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static int sampleSize(int width, int height, int target) {
        int sample = 1;
        int maxSide = Math.max(width, height);
        while (maxSide / (sample * 2) >= target) {
            sample *= 2;
        }
        return sample;
    }

    /** 居中裁成正方形并缩放到 size×size。 */
    private static Bitmap squareCrop(Bitmap src, int size) {
        int width = src.getWidth();
        int height = src.getHeight();
        int side = Math.min(width, height);
        Bitmap cropped = Bitmap.createBitmap(src, (width - side) / 2, (height - side) / 2, side, side);
        Bitmap scaled = cropped == src ? cropped
                : Bitmap.createScaledBitmap(cropped, size, size, true);
        if (cropped != src && cropped != scaled) {
            cropped.recycle();
        }
        return scaled;
    }

    private static Bitmap roundCorners(Bitmap src, float radius) {
        int size = src.getWidth();
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawARGB(0, 0, 0, 0);
        paint.setColor(Color.WHITE);
        RectF rect = new RectF(0, 0, size, size);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, 0, 0, paint);
        paint.setXfermode(null);
        return out;
    }

    public static void clearCache() {
        CACHE.evictAll();
        imageCache().evictAll();
    }
}
