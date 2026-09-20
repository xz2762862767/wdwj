package com.mtstyle.fm;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * v5.7：「免Root脱壳」引擎接入层（BlackDex）。
 *
 * <p>脱壳引擎是本机「Epic」({@value #PKG}) 里的 BlackBox 沙箱 + BlackDex 实现：宿主需要
 * 100 个代理进程、200 多个组件与配套 native 库才能跑起来，没法搬进本 App，所以这里做的是
 * 「入口 + 交接 + 进度」——直接调用它导出的自动化组件
 * {@code epic.studio.blackdex.AdbDumpActivity}，把目标包名或 APK 路径交给它，
 * 由它在自己的进程里完成脱壳，本 App 只负责界面、进度与结果展示。</p>
 *
 * <p>已确认的接口约定（读 Epic 9.9.9 的实现得到）：</p>
 * <ul>
 *   <li>extras：{@code pkg}（包名）或 {@code path}（APK 路径），可选 {@code dumpDir}；</li>
 *   <li>结果目录：{@code dumpDir/<包名>/}（双架构时多一层 arm64）；</li>
 *   <li>运行日志：{@value #RESULT_LOG}，逐行追加
 *       {@code [start]/[loading]/[progress] i/n/[success] dir=/[error]/[timeout]}，引擎自身 5 分钟超时。</li>
 * </ul>
 */
public final class EpicDump {

    /** 引擎包名（Epic）。 */
    public static final String PKG = "epic.studio.pro";

    /** 引擎里导出给外部调用的自动化脱壳组件。 */
    public static final String COMPONENT = "epic.studio.blackdex.AdbDumpActivity";

    /** 引擎自己追加写的运行日志。 */
    public static final String RESULT_LOG =
            Environment.getExternalStorageDirectory().getAbsolutePath()
                    + "/Download/epic_dump_result.txt";

    private static final String[] APK_DIRS = {
            "/sdcard/Download",
            "/sdcard/AgentAttachments",
            "/sdcard/Documents",
            "/sdcard"
    };

    /** 找引擎安装包时的文件名特征（小写比较）。 */
    private static final String[] APK_KEYS = {"epic"};

    private EpicDump() {
    }

    /**
     * 引擎是否可用。
     *
     * <p>v5.9 起引擎已内嵌进本应用（见 {@link FmBlackDex}），正常情况恒为 true；
     * 只有在极少数「内嵌引擎初始化失败」的机型上，才回退到检测本机是否装了「Epic」。</p>
     */
    public static boolean isInstalled(Context context) {
        if (FmBlackDex.isAvailable()) {
            return true;
        }
        try {
            context.getPackageManager().getApplicationInfo(PKG, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 引擎说明：内嵌引擎不需要「打开」，只有回退到 Epic 时才打开它的界面。 */
    public static void open(final Activity activity) {
        if (FmBlackDex.isAvailable()) {
            Toast.makeText(activity, activity.getString(R.string.dump_engine_builtin_ready),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (launch(activity)) {
            return;
        }
        promptInstall(activity);
    }

    private static boolean launch(Activity activity) {
        Intent intent = activity.getPackageManager().getLaunchIntentForPackage(PKG);
        if (intent == null) {
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 默认输出目录：内嵌引擎用它的目录；回退到 Epic 时用 Download/脱壳结果。 */
    public static File dumpDir() {
        if (FmBlackDex.isAvailable()) {
            return FmBlackDex.dumpDir();
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "脱壳结果");
        if (!dir.isDirectory()) {
            // 建不出来也不致命：引擎自己也会 mkdirs
            dir.mkdirs();
        }
        return dir;
    }

    public static File resultLog() {
        return new File(RESULT_LOG);
    }

    /**
     * 本次目标专属的日志：内嵌引擎把它写在「产物目录/包名/脱壳日志.txt」，每任务覆盖写，
     * 所以它才是「这一次这个包」的过程（全局日志是跨任务累积的）。
     * 外置模式没有它，调用方应自行回退到 {@link #resultLog()}。
     */
    public static File taskLog(String pkg) {
        if (FmBlackDex.isAvailable()) {
            return FmBlackDex.taskLog(pkg);
        }
        return resultLog();
    }

    public static long logLength() {
        if (FmBlackDex.isAvailable()) {
            return FmBlackDex.logLength();
        }
        File file = resultLog();
        return file.isFile() ? file.length() : 0L;
    }

    /**
     * 把目标交给引擎开始脱壳。
     *
     * @param source 包名（packageSource=true）或 APK 绝对路径
     * @param outDir 结果目录（可为 null，用引擎默认目录）
     * @return 是否成功把任务发出去
     */
    public static boolean startDump(Activity activity, String source, boolean packageSource, File outDir) {
        if (source == null || source.trim().isEmpty()) {
            return false;
        }
        // v5.9：内嵌引擎在本应用内直接脱壳，不再需要外部引擎配合
        if (FmBlackDex.isAvailable()) {
            return FmBlackDex.startDump(source, packageSource);
        }
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(PKG, COMPONENT));
        if (packageSource) {
            intent.putExtra("pkg", source.trim());
        } else {
            intent.putExtra("path", source.trim());
        }
        if (outDir != null) {
            intent.putExtra("dumpDir", outDir.getAbsolutePath());
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY);
        try {
            activity.startActivity(intent, minimalWindow());
            return true;
        } catch (Throwable t) {
            HardenAuto.log("启动免Root脱壳失败：" + t);
            return false;
        }
    }

    /**
     * v5.8：引擎的自动化组件是个空白 Activity，默认会整屏切过去，看起来像「被打开了引擎」。
     * 这里让它的窗口只有 1×1 像素，视觉上用户始终停在「我的文件」的进度页里
     * （不支持多窗口的设备会忽略该参数，不影响脱壳本身）。
     */
    private static android.os.Bundle minimalWindow() {
        try {
            android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
            options.setLaunchBounds(new android.graphics.Rect(0, 0, 1, 1));
            return options.toBundle();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读取日志文件里 {@code offset} 之后新增的完整行。
     *
     * <p>只返回完整的行（以换行结尾），末尾没写完的半行留到下次再读，避免进度行解析到一半。</p>
     *
     * @param newOffset 出参：本次消费到的位置
     */
    public static List<String> readNewLines(long offset, long[] newOffset) {
        if (FmBlackDex.isAvailable()) {
            return FmBlackDex.readNewLines(offset, newOffset);
        }
        List<String> lines = new ArrayList<>();
        newOffset[0] = offset;
        File file = resultLog();
        if (!file.isFile() || file.length() <= offset) {
            return lines;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            raf.seek(offset);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8 * 1024];
            int read;
            while ((read = raf.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            byte[] data = buffer.toByteArray();
            int lastBreak = -1;
            for (int i = data.length - 1; i >= 0; i--) {
                if (data[i] == '\n') {
                    lastBreak = i;
                    break;
                }
            }
            newOffset[0] = offset + (lastBreak < 0 ? 0 : lastBreak + 1);
            if (lastBreak < 0) {
                return lines;
            }
            String text = new String(data, 0, lastBreak, StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
        } catch (Throwable ignored) {
            newOffset[0] = offset;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
        return lines;
    }

    /** 在常见目录里找引擎安装包。 */
    public static File findApk() {
        for (String dir : APK_DIRS) {
            File[] files = new File(dir).listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (file == null || !file.isFile()) {
                    continue;
                }
                String lower = file.getName().toLowerCase();
                if (!lower.endsWith(".apk")) {
                    continue;
                }
                for (String key : APK_KEYS) {
                    if (lower.contains(key)) {
                        return file;
                    }
                }
            }
        }
        return null;
    }

    /** 未安装引擎时引导安装。 */
    public static void promptInstall(final Activity activity) {
        final File apk = findApk();
        if (apk == null) {
            Toast.makeText(activity, activity.getString(R.string.dump_engine_no_pkg),
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.dump_engine_missing)
                .setMessage(activity.getString(R.string.blackshield_need_msg, apk.getName()))
                .setPositiveButton(R.string.dump_engine_install,
                        (dialog, which) -> Installer.install(activity, apk))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
