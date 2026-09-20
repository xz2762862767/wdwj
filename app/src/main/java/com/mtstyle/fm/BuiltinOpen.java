package com.mtstyle.fm;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;

/**
 * 能识别的文件一律用本应用内置的查看器打开。
 *
 * <p>抽出来是因为「文件列表」和「压缩包内条目」两条路都需要同一套分发：
 * 以前它们各自把不认识的文件丢给外部 {@code ACTION_VIEW}，而 dex / arsc / 二进制 xml
 * 在系统里根本没有应用能接，用户看到的是「找不到应用 → 要不要用文本编辑器打开？」
 * 这种绕圈提示——其实内置查看器一直都在，只是没接上。</p>
 */
public final class BuiltinOpen {

    private BuiltinOpen() {
    }

    /**
     * 打开压缩包（apk / zip）里的条目。
     *
     * <p>dex / xml / arsc 的查看器本来就能直接读压缩包内的条目，让它们自己去读，
     * 比「先解压到缓存再打开」又快，也不会踩到系统里没有应用能接 dex / arsc 的坑。</p>
     *
     * @return true 表示已处理；false 表示这个类型得先解压出来才能看。
     */
    public static boolean openInArchive(Activity activity, File archive, String entry) {
        String name = entry.toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".dex") || name.endsWith(".odex") || name.endsWith(".vdex")) {
                Intent intent = new Intent(activity, DexActivity.class);
                intent.putExtra(DexActivity.EXTRA_PATH, archive.getAbsolutePath());
                intent.putExtra(DexActivity.EXTRA_ENTRY, entry);
                activity.startActivity(intent);
                return true;
            }
            if (name.endsWith(".arsc")) {
                // ArscActivity 固定读包内 resources.arsc，传包路径就够了
                return into(activity, ArscActivity.class, ArscActivity.EXTRA_PATH, archive);
            }
            if (name.endsWith(".xml")) {
                Intent intent = new Intent(activity, XmlEditorActivity.class);
                intent.putExtra(XmlEditorActivity.EXTRA_PATH, archive.getAbsolutePath());
                intent.putExtra(XmlEditorActivity.EXTRA_ENTRY, entry);
                activity.startActivity(intent);
                return true;
            }
        } catch (Exception e) {
            Toast.makeText(activity, "打开失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    /**
     * 尝试内置打开。
     *
     * @return true 表示已经处理（无论成功与否）；false 表示这个类型接不住，调用方自行兜底。
     */
    public static boolean open(Activity activity, File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        try {
            // 压缩包必须先放行：IconLoader.isTextLike 对 zip 也返回 true
            // （zip 头部含大量可打印字节），不排掉的话会被下面的文本兜底抢走，
            // 用户点 zip 打开看到的是一屏乱码，而不是内置的压缩包浏览器。
            if (IconLoader.isArchiveOpenable(file) || IconLoader.isArchiveOther(file)) {
                return false;
            }
            if (IconLoader.isApk(file) || name.endsWith(".apks") || name.endsWith(".xapk")) {
                return into(activity, ApkAnalysisActivity.class,
                        ApkAnalysisActivity.EXTRA_PATH, file);
            }
            if (name.endsWith(".dex") || name.endsWith(".odex") || name.endsWith(".vdex")) {
                return into(activity, DexActivity.class, DexActivity.EXTRA_PATH, file);
            }
            if (name.endsWith(".arsc")) {
                return into(activity, ArscActivity.class, ArscActivity.EXTRA_PATH, file);
            }
            if (name.endsWith(".class")) {
                return into(activity, ClassActivity.class, ClassActivity.EXTRA_PATH, file);
            }
            if (name.endsWith(".smali")) {
                return into(activity, SmaliActivity.class, SmaliActivity.EXTRA_PATH, file);
            }
            if (name.endsWith(".xml") && isBinaryXml(file)) {
                // 编译过的二进制 xml，用普通文本编辑器打开只有乱码
                return into(activity, XmlEditorActivity.class,
                        XmlEditorActivity.EXTRA_PATH, file);
            }
            if (IconLoader.isImage(file)) {
                Intent intent = new Intent(activity, ImageViewerActivity.class);
                intent.putExtra(ImageViewerActivity.EXTRA_PATH, file.getAbsolutePath());
                File dir = file.getParentFile();
                if (dir != null) {
                    intent.putExtra(ImageViewerActivity.EXTRA_DIR, dir.getAbsolutePath());
                }
                activity.startActivity(intent);
                return true;
            }
            if (name.endsWith(".so") || name.endsWith(".bin") || name.endsWith(".dat")
                    || name.endsWith(".rsa") || name.endsWith(".sf") || name.endsWith(".mf")
                    || name.endsWith(".vdex") || name.endsWith(".oat") || name.endsWith(".art")) {
                // 二进制：十六进制查看，比文本视图的一屏乱码有用得多
                Toast.makeText(activity, R.string.open_hex_hint, Toast.LENGTH_SHORT).show();
                return into(activity, HexViewerActivity.class, HexViewerActivity.EXTRA_PATH, file);
            }
            if (IconLoader.isTextLike(file)) {
                return into(activity, TextEditorActivity.class,
                        TextEditorActivity.EXTRA_PATH, file);
            }
        } catch (Exception e) {
            Toast.makeText(activity, "打开失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            return true;
        }
        // 兜底：认不出来的按二进制看。之前丢文本视图，二进制只剩零星字符加满屏
        // 替换符，用户反馈「显示几个数字，内容完全不清楚」，所以换成十六进制视图。
        Toast.makeText(activity, R.string.open_hex_hint, Toast.LENGTH_SHORT).show();
        return into(activity, HexViewerActivity.class, HexViewerActivity.EXTRA_PATH, file);
    }

    private static boolean into(Activity activity, Class<?> cls, String extra, File file) {
        Intent intent = new Intent(activity, cls);
        intent.putExtra(extra, file.getAbsolutePath());
        activity.startActivity(intent);
        return true;
    }

    /** Android 编译后的二进制 xml：文件头固定是 03 00 08 00。 */
    static boolean isBinaryXml(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] head = new byte[4];
            if (in.read(head) != 4) {
                return false;
            }
            return head[0] == 0x03 && head[1] == 0x00 && head[2] == 0x08 && head[3] == 0x00;
        } catch (Exception e) {
            return false;
        }
    }
}
