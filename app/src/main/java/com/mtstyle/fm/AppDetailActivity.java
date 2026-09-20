package com.mtstyle.fm;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * MT 风格「应用信息」页：顶栏 + 应用图标/名称/包名 + 信息行 + 底部操作栏。
 * 支持两种进入方式：APK 文件（EXTRA_PATH）与已安装应用（EXTRA_PACKAGE）。
 */
public class AppDetailActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "apk_path";
    public static final String EXTRA_PACKAGE = "package_name";
    /** 以 MT 风格弹窗（悬浮卡片 + 背景变暗）方式展示，默认开启。 */
    public static final String EXTRA_DIALOG = "as_dialog";

    private File apk;
    private String packageName;
    private boolean installedMode;
    private ApkInfoHelper.Info info;
    private long dataSize = -1;
    /** 加固识别是否还没出结果（第一段渲染时为 true，第二段回填后置 false） */
    private boolean hardenPending;
    /** 数据目录大小是否还在统计 */
    private boolean dataSizePending;
    private boolean asDialog = true;

    // 详情行视图引用（页面骨架先即时渲染，数据后续分批回填）
    private TextView vPkg;
    private TextView vVersion;
    private TextView vSize;
    private TextView vSign;
    private TextView vHarden;
    private TextView vTarget;
    private TextView vMin;
    private TextView vInstalled;
    private TextView vDataDir1;
    private TextView vDataDir2;
    private TextView vUid;
    private TextView vPath;
    private TextView vFirst;
    private TextView vUpdate;
    private TextView vPermCount;
    private TextView vSystem;
    private TextView dataSizeView;
    private View content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        asDialog = getIntent() == null || getIntent().getBooleanExtra(EXTRA_DIALOG, true);
        Settings.applyTheme(this);
        if (asDialog) {
            // 在配色主题之上叠加浮窗样式，避免为每套配色重复定义弹窗主题
            getTheme().applyStyle(R.style.Theme_MyFiles_Dialog, true);
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apk_detail);

        if (asDialog) {
            View topbar = findViewById(R.id.detail_topbar);
            if (topbar != null) {
                topbar.setVisibility(View.GONE);
            }
            View root = findViewById(R.id.detail_root);
            if (root != null) {
                root.setBackgroundResource(R.drawable.bg_card);
            }
            Window window = getWindow();
            window.setDimAmount(0.45f);
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            window.setLayout((int) (metrics.widthPixels * 0.94f),
                    (int) (metrics.heightPixels * 0.78f));
        }

        findViewById(R.id.detail_back).setOnClickListener(v -> finish());
        findViewById(R.id.detail_more).setOnClickListener(v -> showMoreMenu());

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String pkg = getIntent().getStringExtra(EXTRA_PACKAGE);
        try {
            if (pkg != null && pkg.length() > 0) {
                installedMode = true;
                packageName = pkg;
                ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                apk = ai.sourceDir == null ? null : new File(ai.sourceDir);
            } else if (path != null) {
                apk = new File(path);
                packageName = null;
            } else {
                finish();
                return;
            }
        } catch (Exception e) {
            toast("找不到该应用：" + e.getMessage());
            finish();
            return;
        }

        // 数据未就绪前不显示任何内容：等所有数据（含加固识别）一次算齐后整体呈现，
        // 期间由浮窗背景变暗作为“正在打开”的反馈，避免出现半填充的中间态。
        content = findViewById(R.id.detail_root);
        if (content != null) {
            content.setVisibility(View.INVISIBLE);
        }
        setupStaticUi();
        String cacheKey = cacheKey(apk);
        ApkInfoHelper.Info cached = cacheKey == null ? null : cachedInfo(cacheKey);
        if (cached != null) {
            info = cached;
            dataSize = cachedDataSize(cached.dataDir1);
            renderAll();
            return;
        }
        loadAsync(cacheKey);
    }

    /** 结果内存缓存：同一 APK（路径+大小+修改时间）再次打开可秒开，后台不再重算。 */
    private static final java.util.HashMap<String, ApkInfoHelper.Info> INFO_CACHE =
            new java.util.HashMap<>();
    private static final java.util.HashMap<String, Long> DATA_SIZE_CACHE = new java.util.HashMap<>();

    private static String cacheKey(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        return file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
    }

    private static ApkInfoHelper.Info cachedInfo(String key) {
        if (key == null) {
            return null;
        }
        synchronized (INFO_CACHE) {
            return INFO_CACHE.get(key);
        }
    }

    private static void putCache(String key, ApkInfoHelper.Info data) {
        if (key == null || data == null) {
            return;
        }
        synchronized (INFO_CACHE) {
            if (INFO_CACHE.size() > 8) {
                INFO_CACHE.clear();
            }
            INFO_CACHE.put(key, data);
        }
    }

    private static void putDataSize(String dir, long size) {
        if (dir == null || size < 0) {
            return;
        }
        synchronized (DATA_SIZE_CACHE) {
            if (DATA_SIZE_CACHE.size() > 16) {
                DATA_SIZE_CACHE.clear();
            }
            DATA_SIZE_CACHE.put(dir, size);
        }
    }

    private static long cachedDataSize(String dir) {
        if (dir == null) {
            return -1;
        }
        synchronized (DATA_SIZE_CACHE) {
            Long value = DATA_SIZE_CACHE.get(dir);
            return value == null ? -1 : value;
        }
    }

    /** 数据全部就绪后一次性渲染：先填满所有字段，再整体显示，保证“一次完整出现”。 */
    private void renderAll() {
        buildRows();
        fillInfo();
        if (content != null) {
            content.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 后台一次性取齐全部数据：基础信息 → 加固识别 → 数据大小，全部算完后才回填界面，
     * 所以各字段是“同时”出现的（加固不会再单独慢一拍）。
     */
    /**
     * 分两段取数据：先把「能立刻拿到」的基础信息渲染上屏，重的活（加固识别、数据目录大小
     * 统计）放到后面算完再局部回填。
     *
     * <p>以前是全部算齐才显示，大应用（100MB+，数据目录通常也大）光这两项就要好几秒，
     * 用户看到的就是「点了半天不出东西」，与应用体积正相关。
     */
    private void loadAsync(final String key) {
        final Context appContext = getApplicationContext();
        final File target = apk;
        final String pkgName = packageName;
        final boolean needDataSize = installedMode;
        new Thread(() -> {
            // ---------- 第一段：基础信息（解析一次 APK，通常几百毫秒） ----------
            ApkInfoHelper.Info loaded = null;
            try {
                if (target != null && target.exists() && target.canRead()) {
                    loaded = ApkInfoHelper.load(appContext, target, false);
                }
            } catch (Throwable ignored) {
            }
            if (loaded == null && pkgName != null) {
                try {
                    loaded = fallbackInfo(pkgName);
                } catch (Throwable ignored) {
                }
            }
            if (loaded == null) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    toast(getString(R.string.apk_parse_failed));
                    finish();
                });
                return;
            }

            final ApkInfoHelper.Info first = loaded;
            putCache(key, first);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                info = first;
                dataSize = -1;
                hardenPending = true;
                dataSizePending = needDataSize;
                renderAll();
            });

            // ---------- 第二段：加固识别 + 数据目录大小（较重） ----------
            File hardenTarget = target;
            if (first.apkPath != null && !first.apkPath.isEmpty()) {
                hardenTarget = new File(first.apkPath);
            }
            if (hardenTarget != null && hardenTarget.isFile()) {
                try {
                    HardenDetector.Result harden = HardenDetector.detect(hardenTarget);
                    first.hardenStatus = harden.displayText;
                    first.hardenPlatform = harden.platform;
                    first.hardenConfidence = harden.confidence;
                    first.hardenSuspectSelfMade = harden.suspectSelfMade;
                } catch (Throwable ignored) {
                }
            }
            long size = -1;
            if (needDataSize) {
                size = folderSizeQuiet(first.dataDir1);
            }
            final long dataSizeValue = size;
            putCache(key, first);
            putDataSize(first.dataDir1, dataSizeValue);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                info = first;
                dataSize = dataSizeValue;
                hardenPending = false;
                dataSizePending = false;
                fillInfo();      // 行已经建好，这里只回填，不重建
            });
        }, "apk-detail").start();
    }

    /** 与数据无关的静态 UI：标题、底部按钮、长按菜单、包名点击 —— 立即构建，不等数据。 */
    private void setupStaticUi() {
        TextView titleView = findViewById(R.id.detail_title);
        titleView.setText(installedMode ? R.string.app_info_title : R.string.apk_detail_title);

        // 头部占位：数据全部就绪后由 fillInfo() 一次性替换
        ((TextView) findViewById(R.id.detail_name)).setText("-");
        ((TextView) findViewById(R.id.detail_pkg)).setText("-");
        ((TextView) findViewById(R.id.detail_meta)).setText("-");

        // 底部按钮：已安装应用 / APK 文件两种模式
        TextView btn1 = findViewById(R.id.btn_detail_1);
        TextView btn2 = findViewById(R.id.btn_detail_2);
        TextView btn3 = findViewById(R.id.btn_detail_3);
        TextView btn4 = findViewById(R.id.btn_detail_4);

        if (installedMode) {
            btn1.setText(R.string.app_open);
            btn1.setOnClickListener(v -> launchApp());
            btn2.setText(R.string.apps_extract);
            btn2.setOnClickListener(v -> extract());
            btn3.setText(R.string.act_share);
            btn3.setOnClickListener(v -> share());
            btn4.setText(R.string.apps_uninstall);
            btn4.setOnClickListener(v -> uninstall());
        } else {
            btn1.setText(R.string.apk_install);
            btn1.setOnClickListener(v -> install());
            btn2.setText(R.string.apk_view);
            btn2.setOnClickListener(v -> viewContent());
            btn3.setText(R.string.act_share);
            btn3.setOnClickListener(v -> share());
            btn4.setText(R.string.more);
            btn4.setOnClickListener(v -> showMoreMenu());
        }

        View header = findViewById(R.id.detail_header);
        if (header != null) {
            header.setOnLongClickListener(v -> {
                showMoreMenu();
                return true;
            });
        }

        // 点击包名可复制
        findViewById(R.id.detail_pkg).setOnClickListener(v -> {
            if (!ensureReady()) {
                return;
            }
            copy(info.packageName);
            toast(getString(R.string.copied));
        });
    }

    /** 构建信息行（先全填 “-”；随后由 fillInfo() 一次性把真实数据全部回填）。 */
    private void buildRows() {
        LinearLayout rows = findViewById(R.id.detail_rows);
        rows.removeAllViews();

        // 字段与顺序按 MT 的 APK 信息页对齐（包名 → 版本号 → 安装包大小 → 签名 → 加固 →
        // 目标系统 → 最低系统 → 已安装 → 数据目录1/2 → UID → APK 路径 → 首次安装 → 最后更新）
        vPkg = addRow(rows, getString(R.string.apk_detail_pkg), "-", true, true);
        vVersion = addRow(rows, getString(R.string.apk_detail_version), "-", false, false);
        vSize = addRow(rows, getString(R.string.apk_detail_size), "-", false, false);
        vSign = addRow(rows, getString(R.string.apk_detail_sign), "-", true, false);
        vHarden = addRow(rows, getString(R.string.apk_detail_harden), "-", true, false);
        vTarget = addRow(rows, getString(R.string.apk_detail_target), "-", false, false);
        vMin = addRow(rows, getString(R.string.apk_detail_min), "-", false, false);
        vInstalled = addRow(rows, getString(R.string.apk_detail_installed), "-", false, false);
        vDataDir1 = addRow(rows, getString(R.string.apk_detail_datadir), "-", true, false);
        vDataDir2 = addRow(rows, getString(R.string.apk_detail_appdir), "-", true, false);
        vUid = addRow(rows, getString(R.string.apk_detail_uid), "-", false, false);
        vPath = addRow(rows, getString(R.string.apk_detail_path), "-", true, true);
        vFirst = addRow(rows, getString(R.string.apk_detail_first), "-", false, false);
        vUpdate = addRow(rows, getString(R.string.apk_detail_update), "-", false, false);
        if (installedMode) {
            dataSizeView = addRow(rows, getString(R.string.app_data_size), "-", false, false);
            vPermCount = addRow(rows, getString(R.string.app_permission_count), "-", false, true);
            vSystem = addRow(rows, getString(R.string.app_system), "-", false, false);
        }
    }

    /** 把基础信息回填到已经渲染好的行上。 */
    private void fillInfo() {
        if (info == null) {
            return;
        }
        ImageView iconView = findViewById(R.id.detail_icon);
        if (info.icon != null) {
            iconView.setImageDrawable(info.icon);
        }
        ((TextView) findViewById(R.id.detail_name)).setText(info.label);
        ((TextView) findViewById(R.id.detail_pkg)).setText(info.packageName);
        ((TextView) findViewById(R.id.detail_meta)).setText(getString(R.string.apk_detail_version)
                + " " + info.versionName + " (" + info.versionCode + ") · " + info.size);

        setRow(vPkg, info.packageName);
        setRow(vVersion, info.versionName + " (" + info.versionCode + ")");
        setRow(vSize, info.size);
        setRow(vSign, info.signatureSha256.isEmpty()
                ? info.signatureStatus
                : info.signatureStatus + "\nSHA-256：" + info.signatureSha256);
        if (info.hardenStatus != null && !info.hardenStatus.isEmpty()) {
            setRow(vHarden, info.hardenStatus);
        } else if (hardenPending) {
            setRow(vHarden, getString(R.string.apk_detail_scanning));
        }
        setRow(vTarget, info.targetSystem);
        setRow(vMin, info.minSystem);
        setRow(vInstalled, info.installedText);
        setRow(vDataDir1, info.dataDir1);
        setRow(vDataDir2, info.dataDir2);
        setRow(vUid, info.uid);
        setRow(vPath, info.apkPath);
        setRow(vFirst, info.firstInstall);
        setRow(vUpdate, info.lastUpdate);
        if (installedMode) {
            if (dataSize >= 0) {
                setRow(dataSizeView, Util.formatStorage(dataSize));
            } else if (dataSizePending) {
                setRow(dataSizeView, getString(R.string.app_data_size_calc));
            } else {
                setRow(dataSizeView, "-");
            }
            setRow(vPermCount, String.valueOf(info.permissions.size()));
            setRow(vSystem, isSystemApp() ? "是" : "否");
        }
    }

    private static void setRow(TextView view, String text) {
        if (view != null) {
            view.setText(text == null || text.isEmpty() ? "-" : text);
        }
    }

    /** 信息尚未就绪时给出提示，避免空指针崩溃。 */
    private boolean ensureReady() {
        if (info == null) {
            toast("正在读取应用信息，请稍候");
            return false;
        }
        return true;
    }

    private boolean isSystemApp() {
        if (packageName != null) {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(packageName, 0);
                return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void showMoreMenu() {
        if (!ensureReady()) {
            return;
        }
        List<MtMenu.Item> items = new ArrayList<>();
        if (installedMode) {
            items.add(MtMenu.item(getString(R.string.app_open), R.drawable.ic_folder,
                    this::launchApp));
            items.add(MtMenu.item(getString(R.string.apps_extract), R.drawable.ic_apk,
                    this::extract));
            // v5.3：加固工具自带的「已安装应用」列表读不到，先把安装包提取到下载目录再交给它
            items.add(MtMenu.item(getString(R.string.apk_harden), R.drawable.ic_apk,
                    this::hardenInstalled));
            items.add(MtMenu.item(getString(R.string.apps_copy_pkg), R.drawable.ic_text,
                    () -> {
                        copy(info.packageName);
                        toast(getString(R.string.copied));
                    }));
            items.add(MtMenu.item(getString(R.string.app_copy_path), R.drawable.ic_text,
                    () -> {
                        copy(info.apkPath);
                        toast(getString(R.string.copied));
                    }));
            items.add(MtMenu.item(getString(R.string.act_share), R.drawable.ic_file,
                    this::share));
            items.add(MtMenu.item(getString(R.string.apps_uninstall), R.drawable.ic_trash,
                    this::uninstall).danger());
        } else {
            items.add(MtMenu.item(getString(R.string.apk_install), R.drawable.ic_apk,
                    this::install));
            items.add(MtMenu.item(getString(R.string.apk_view), R.drawable.ic_folder,
                    this::viewContent));
            items.add(MtMenu.item(getString(R.string.analysis_title), R.drawable.ic_code,
                    this::openAnalysis));
            items.add(MtMenu.item(getString(R.string.apk_open_with), R.drawable.ic_file,
                    () -> openWith()));
            items.add(MtMenu.item(getString(R.string.app_copy_path), R.drawable.ic_text,
                    () -> {
                        copy(info.apkPath);
                        toast(getString(R.string.copied));
                    }));
            items.add(MtMenu.item(getString(R.string.act_share), R.drawable.ic_file,
                    this::share));
        }
        MtMenu.show(this, info.label, info.packageName, items);
    }

    private void launchApp() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) {
                toast(getString(R.string.app_launch_failed));
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast(getString(R.string.app_launch_failed));
        }
    }

    private void install() {
        if (apk == null || !apk.exists()) {
            toast(getString(R.string.apk_parse_failed));
            return;
        }
        // 走统一安装器：安装验证 → 二次确认 → 优先级通道（Shizuku/Dhizuku/Root/系统）
        Installer.install(this, apk);
    }

    /** 查看内容：把 APK 当作 zip 用内置压缩包浏览器打开，列出内部条目。 */
    private void viewContent() {
        if (apk == null || !apk.exists()) {
            toast(getString(R.string.apk_parse_failed));
            return;
        }
        try {
            Intent intent = new Intent(this, ArchiveActivity.class);
            intent.putExtra(ArchiveActivity.EXTRA_PATH, apk.getAbsolutePath());
            File parent = apk.getParentFile();
            if (parent != null) {
                intent.putExtra(ArchiveActivity.EXTRA_DIR, parent.getAbsolutePath());
            }
            startActivity(intent);
        } catch (Exception e) {
            toast("无法查看内容：" + e.getMessage());
        }
    }

    /** 反编译分析：查看签名 / 编辑 XML / 查看资源 / 查看包内文件。 */
    private void openAnalysis() {
        if (apk == null || !apk.exists()) {
            toast(getString(R.string.apk_parse_failed));
            return;
        }
        try {
            Intent intent = new Intent(this, ApkAnalysisActivity.class);
            intent.putExtra(ApkAnalysisActivity.EXTRA_PATH, apk.getAbsolutePath());
            startActivity(intent);
        } catch (Exception e) {
            toast("无法打开分析：" + e.getMessage());
        }
    }

    /** 提取安装包到 Download/应用提取/。 */
    private void extract() {
        if (!ensureReady()) {
            return;
        }
        if (apk == null || !apk.exists()) {
            toast("找不到安装包文件");
            return;
        }
        final File[] result = new File[1];
        TaskRunner.run(this, getString(R.string.apps_extracting), true, progress -> {
            File dir = new File(Environment.getExternalStorageDirectory(), "Download/应用提取");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new Exception("无法创建目录：" + dir.getAbsolutePath());
            }
            String name = info.label + "_" + info.versionName + ".apk";
            File target = FileOps.uniqueTarget(dir, name);
            FileOps.copy(apk, target, progress, new int[1], 1);
            result[0] = target;
        }, (ok, message) -> {
            if (ok && result[0] != null) {
                toast(getString(R.string.apps_extracted, result[0].getAbsolutePath()));
            } else {
                toast(getString(R.string.apps_extract_failed, String.valueOf(message)));
            }
        });
    }

    /**
     * v5.3：加固已安装应用。
     *
     * <p>外部加固工具的「已安装应用」列表在本机读不到，改由本应用把安装包提取到下载目录，
     * 再走既有「加固 APK」流程交给工具（自动加固开启时会被自动选中）。</p>
     */
    private void hardenInstalled() {
        if (!ensureReady()) {
            return;
        }
        if (apk == null || !apk.exists()) {
            toast("找不到安装包文件");
            return;
        }
        final File src = apk;
        final File dir = HardenAuto.DOWNLOAD_DIR;
        final String fileName = (info.label + "_" + info.versionName).replaceAll("[\\\\/:*?\"<>|]", "_")
                + ".apk";
        final File[] created = new File[1];
        TaskRunner.run(this, getString(R.string.apps_extracting), true, progress -> {
            File existing = new File(dir, fileName);
            if (existing.isFile() && existing.length() == src.length()) {
                created[0] = existing;
                progress.publish("复用已提取的安装包", 100);
                return;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new Exception("无法访问目录：" + dir.getAbsolutePath());
            }
            File target = FileOps.uniqueTarget(dir, fileName);
            created[0] = target;
            progress.publish("正在提取 " + target.getName(), 30);
            FileOps.copy(src, target, progress, new int[1], 1);
            progress.publish("完成", 100);
        }, (ok, message) -> {
            if (ok && created[0] != null) {
                HardenTool.open(this, created[0]);
            } else {
                toast(getString(R.string.apps_extract_failed, String.valueOf(message)));
            }
        });
    }

    private void share() {
        if (apk == null || !apk.exists()) {
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/vnd.android.package-archive");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.act_share)));
        } catch (Exception e) {
            toast("分享失败：" + e.getMessage());
        }
    }

    private void uninstall() {
        if (!ensureReady() || packageName == null) {
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.apps_uninstall)
                .setMessage(getString(R.string.app_uninstall_confirm, info.label))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_DELETE,
                                Uri.parse("package:" + packageName));
                        startActivity(intent);
                    } catch (Exception e) {
                        toast("无法调用卸载：" + e.getMessage());
                    }
                })
                .show();
    }

    private void openWith() {
        if (apk == null) {
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.apk_open_with)));
        } catch (Exception e) {
            toast("打开失败：" + e.getMessage());
        }
    }

    private void copy(String text) {
        try {
            ClipboardManager manager =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("fm", text));
            }
        } catch (Exception ignored) {
        }
    }

    /** APK 无法解析时的兜底信息（直接用 PackageManager 读取已安装应用）。 */
    private ApkInfoHelper.Info fallbackInfo(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            ApkInfoHelper.Info result = new ApkInfoHelper.Info();
            result.icon = ai.loadIcon(pm);
            result.label = String.valueOf(ai.loadLabel(pm));
            result.packageName = pkg;
            result.versionName = pi.versionName == null ? "-" : pi.versionName;
            result.versionCode = pi.versionCode;
            result.apkPath = ai.sourceDir == null ? "-" : ai.sourceDir;
            result.dataDir1 = ai.dataDir == null ? "-" : ai.dataDir;
            result.dataDir2 = ai.nativeLibraryDir == null ? "-" : ai.nativeLibraryDir;
            result.uid = String.valueOf(ai.uid);
            result.installed = true;
            result.installedText = "已安装";
            result.firstInstall = Util.formatDate(pi.firstInstallTime);
            result.lastUpdate = Util.formatDate(pi.lastUpdateTime);
            result.size = Util.formatSize(new File(ai.sourceDir == null ? "" : ai.sourceDir).length());
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private long folderSizeQuiet(String dir) {
        if (TextUtils.isEmpty(dir) || "-".equals(dir)) {
            return -1;
        }
        try {
            File file = new File(dir);
            if (!file.isDirectory()) {
                return -1;
            }
            return FileOps.folderSize(file, FileOps.SILENT);
        } catch (Exception e) {
            return -1;
        }
    }

    private TextView addRow(LinearLayout parent, String label, String value, boolean wrap,
                            boolean copyable) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_detail_row, parent, false);
        ((TextView) row.findViewById(R.id.row_label)).setText(label);
        final TextView valueView = row.findViewById(R.id.row_value);
        final String text = value == null || value.length() == 0 ? "-" : value;
        valueView.setText(text);
        valueView.setSingleLine(!wrap);
        if (!wrap) {
            valueView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        }
        if (copyable) {
            row.setOnLongClickListener(v -> {
                // 读取当前显示值，避免拿到构建时的占位 “-”
                CharSequence current = valueView.getText();
                copy(current == null ? "" : current.toString());
                toast(getString(R.string.copied));
                return true;
            });
        }
        parent.addView(row);
        return valueView;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
