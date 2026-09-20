package com.mtstyle.fm;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.content.SharedPreferences;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v5.7：侧滑栏「黑盾加固 → 免Root脱壳」界面。
 *
 * <p>三个阶段共用一套布局：选目标（本机应用 / APK 文件）→ 脱壳中（阶段 + 进度 + 日志）
 * → 结果（文件清单 + 打开目录 / 查看日志 / 复制路径）。脱壳本身由本机「Epic」的
 * BlackDex 引擎在它自己的进程里完成，接口见 {@link EpicDump}。</p>
 */
public class DumpActivity extends AppCompatActivity {

    /** 轮询引擎日志与输出目录的间隔。 */
    private static final long POLL_MS = 500L;

    /** 引擎进程刚被拉起时第一条日志常失败（沙箱服务未就绪），自动重试次数。 */
    private static final int RETRY_LIMIT = 2;

    /** 两次尝试之间的冷却时间。 */
    private static final long RETRY_DELAY_MS = 2600L;

    /** 等够这么久还没结果就收工（引擎自身 5 分钟超时，这里留足余量）。 */
    private static final long HARD_LIMIT_MS = 10 * 60 * 1000L;
    /** v5.9.5 防呆：一条 dex 都没产出 = 引擎这条链路没跑起来，早失败早提示，不干等十分钟。
     *  连 [loading] 都没有说明沙箱应用根本没起来，1.5 分钟就够了；已经起来了就多给点时间。 */
    private static final long NO_OUTPUT_LIMIT_MS = 90 * 1000L;
    /** v5.9.43 从 180s 收到 110s：实测沙箱应用起来后仍无产物的，后面基本也等不出来。 */
    private static final long NO_OUTPUT_LIMIT_LOADING_MS = 110 * 1000L;

    /** 任务日志里出现致命特征后，至少等到这时候再采信（早期行可能只是引擎启动的抖动）。 */
    private static final long FATAL_CHECK_AFTER_MS = 20000L;

    /** 产物静默阈值：结果目录里已有 dex 且这么久没有新产物，就认为脱完了。
     *  v5.9.3 从 25 秒收到 8 秒，v5.9.4 再收到 5 秒——引擎的结束回调并不可靠，
     *  之前那 25 秒纯粹是白等。判定还要求是「本次新产出的 dex」，所以收紧是安全的。 */
    private static final long QUIET_MS = 5 * 1000L;

    /** 引擎报「进度 X/X」（它认为已经抽完）时，再等 5 秒收尾就收工。 */
    private static final long QUIET_TAIL_MS = 3 * 1000L;

    private static final int REQ_PICK_APK = 0x7101;

    /** 加固识别结果缓存：包名|versionCode → 平台名（跨启动复用，第二次打开列表秒出标签）。 */
    private static final String PREFS_HARDEN = "harden_cache";

    private static final Pattern PROGRESS =
            Pattern.compile("\\[progress\\]\\s*(\\d+)\\s*/\\s*(\\d+)");

    private static final int STAGE_PICK = 0;
    private static final int STAGE_RUN = 1;
    private static final int STAGE_DONE = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<AppItem> all = new ArrayList<>();
    private final List<AppItem> shown = new ArrayList<>();
    private final StringBuilder logBuffer = new StringBuilder();

    private TargetAdapter adapter;

    /** 系统应用所在的只读分区（v5.9.37）。 */
    private static final String[] SYSTEM_DIR_PREFIXES = {
            "/system/", "/system_ext/", "/vendor/", "/product/", "/apex/",
            "/odm/", "/oem/", "/prive-app/", "/data/preload/",
    };

    /** v5.9.37 应用来源筛选：0=全部、1=本机应用、2=系统应用。 */
    /** 来源筛选：0=全部，1=本机应用，2=系统应用。默认只看本机应用，系统应用不混进来。 */
    private int appFilterMode = 1;
    private View filterBar;
    private TextView filterUser;
    private TextView filterSystem;
    private int countUser;
    private int countSystem;

    private boolean apkMode;
    private String keyword = "";
    private String selectedKey = "";
    private String selectedLabel = "";
    /** 当前选中目标的加固平台（结果页要用）。 */
    private String selectedHarden = "";
    private String runHarden = "";

    /** 读 APK 的 zip 条目名比较费 IO，放后台并限制并发，列表本身照旧秒开。 */
    private final ExecutorService hardenPool = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "harden-scan");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private boolean hardenRefreshPosted;

    private View stagePick;
    private View stageRun;
    private View stageDone;
    private TextView engineStatus;
    private TextView engineSub;
    private TextView outDirView;
    private TextView engineAction;
    private TextView tabAppsText;
    private TextView tabApkText;
    private View tabAppsLine;
    private View tabApkLine;
    private View browseButton;
    private ListView listView;
    private TextView emptyView;
    private TextView pickHint;
    private TextView runTarget;
    private TextView runPhase;
    private TextView runDex;
    private TextView runLog;
    private ProgressBar runBar;
    private ScrollView logScroll;
    private TextView doneTitle;
    private TextView doneMsg;
    private TextView doneDir;
    private TextView doneFiles;

    /** ===== 单次脱壳的运行状态 ===== */
    private boolean running;
    private int retries;
    private String lastSource = "";
    private boolean lastPackageSource;
    private long logOffset;
    private long runStart;
    private File resultDir;
    private String runPkg;
    private int dexCount;
    private long dexBytes;
    /** v5.9：产物静默检测——目录里出现 dex 且一段时间不再变化，就算脱完了。 */
    private int lastDexCount;
    private long lastDexBytes;
    private long dexChangeAt;
    /** 本次产物里最新的修改时间：用来排除上一次脱壳留下的旧 dex。 */
    private long newestDexTime;
    /** 结果页：dex 归属（绝对路径 → DexClassifier 常量）。后台线程写、UI 线程读。 */
    private volatile java.util.Map<String, Integer> dexKinds = new java.util.HashMap<>();
    private boolean classifyStarted;
    private boolean kindSummaryShown;
    /** 产物里 0 字节的 dex 残片数：引擎 dump 中途 native 崩溃会留下这种空文件。 */
    private int emptyDexCount;
    /** 当前生效的静默阈值（引擎报进度完成时会收窄）。 */
    private long quietMs = QUIET_MS;
    private int progressCurrent;
    private int progressTotal;
    private boolean sawLoading;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            pollOnce();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Settings.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dump);

        stagePick = findViewById(R.id.stage_pick);
        stageRun = findViewById(R.id.stage_run);
        stageDone = findViewById(R.id.stage_done);
        engineStatus = findViewById(R.id.engine_status);
        engineSub = findViewById(R.id.engine_sub);
        outDirView = findViewById(R.id.out_dir);
        engineAction = findViewById(R.id.engine_action);
        tabAppsText = findViewById(R.id.tab_apps_text);
        tabApkText = findViewById(R.id.tab_apk_text);
        tabAppsLine = findViewById(R.id.tab_apps_line);
        tabApkLine = findViewById(R.id.tab_apk_line);
        browseButton = findViewById(R.id.btn_browse);
        listView = findViewById(R.id.target_list);
        emptyView = findViewById(R.id.target_empty);
        pickHint = findViewById(R.id.pick_hint);
        runTarget = findViewById(R.id.run_target);
        runPhase = findViewById(R.id.run_phase);
        runDex = findViewById(R.id.run_dex);
        runLog = findViewById(R.id.run_log);
        runBar = findViewById(R.id.run_bar);
        logScroll = findViewById(R.id.log_scroll);
        doneTitle = findViewById(R.id.done_title);
        doneMsg = findViewById(R.id.done_msg);
        doneDir = findViewById(R.id.done_dir);
        doneFiles = findViewById(R.id.done_files);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_search).setOnClickListener(v -> showSearchDialog());
        findViewById(R.id.tab_apps).setOnClickListener(v -> switchMode(false));
        findViewById(R.id.tab_apk).setOnClickListener(v -> switchMode(true));
        browseButton.setOnClickListener(v -> browseApk());
        bindAppFilterBar();
        engineAction.setOnClickListener(v -> onEngineAction());
        findViewById(R.id.engine_refresh).setOnClickListener(v -> {
            refreshEngine();
            toast(getString(R.string.dump_engine_refresh));
        });
        findViewById(R.id.btn_start).setOnClickListener(v -> startRun());
        findViewById(R.id.btn_quick).setOnClickListener(v -> quickUnpack());
        findViewById(R.id.btn_quick).setVisibility(View.VISIBLE);
        findViewById(R.id.btn_tip).setOnClickListener(v -> showSlowTip());
        findViewById(R.id.btn_background).setOnClickListener(v -> {
            toast(getString(R.string.dump_run_background));
            finish();
        });
        findViewById(R.id.btn_open_dir).setOnClickListener(v -> openResultDir());
        findViewById(R.id.btn_again).setOnClickListener(v -> {
            showStage(STAGE_PICK);
            refreshEngine();
        });
        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_copy_path).setOnClickListener(v -> copyPath());
        findViewById(R.id.btn_view_log).setOnClickListener(v -> openLog());

        adapter = new TargetAdapter(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            AppItem item = shown.get(position);
            selectedKey = key(item);
            selectedLabel = item.label;
            selectedHarden = item.harden == null ? "" : item.harden;
            adapter.notifyDataSetChanged();
            updatePickHint();
        });

        outDirView.setText(getString(R.string.dump_out_dir_hint,
                EpicDump.dumpDir().getAbsolutePath()));
        updateTabs();
        updatePickHint();
        refreshEngine();
        loadApps();
        // v5.9.3 预热：进页面就把沙箱服务拉起来，用户挑应用的这几秒不算白等
        FmBlackDex.warmUp();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poller);
        hardenPool.shutdownNow();
        // 页面关了就别继续占着「正在脱壳」状态
        FmBlackDex.abort();
        super.onDestroy();
    }

    /**
     * v5.9.4 加固识别：先把「application 类名」命中与缓存结果铺上，剩下的丢后台逐个体检 APK
     * （只读 zip 条目名，不解压），每 300ms 批量刷新一次列表，所以列表始终是秒开。
     */
    private void startHardenScan() {
        final SharedPreferences cache = getSharedPreferences(PREFS_HARDEN, Context.MODE_PRIVATE);
        final List<AppItem> todo = new ArrayList<>();
        SharedPreferences.Editor editor = cache.edit();
        int changed = 0;
        for (AppItem item : all) {
            if (item.packageName == null || item.packageName.isEmpty()) {
                continue;
            }
            String cacheKey = hardenKey(item);
            if (item.harden != null && !item.harden.isEmpty()) {
                editor.putString(cacheKey, item.harden);
                changed++;
                continue;
            }
            String cached = cache.getString(cacheKey, null);
            if (cached != null) {
                item.harden = cached;
                changed++;
            } else {
                todo.add(item);
            }
        }
        if (changed > 0) {
            editor.apply();
            adapter.notifyDataSetChanged();
        }
        // 用户自己装的应用先出标签，系统应用排后面（数量多且几乎都是未加固）
        Collections.sort(todo, (a, b) -> Boolean.compare(a.system, b.system));
        for (final AppItem item : todo) {
            hardenPool.execute(() -> {
                File apk = item.sourceDir == null ? null : new File(item.sourceDir);
                String platform = HardenDetect.scanApk(apk);
                if (platform != null && platform.isEmpty()) {
                    // 读不了（只有系统分区/损坏包会这样）：保持无标签，也不写缓存，留着以后重试
                    return;
                }
                final String value = platform == null ? HardenDetect.NOT_HARDENED : platform;
                cache.edit().putString(hardenKey(item), value).apply();
                handler.post(() -> {
                    item.harden = value;
                    scheduleHardenRefresh();
                });
            });
        }
    }

    private String hardenKey(AppItem item) {
        return item.packageName + "|" + item.versionCode;
    }

    /** 标签刷新节流：几百个应用逐个刷会把 UI 刷爆，这里合并成 300ms 一次。 */
    private void scheduleHardenRefresh() {
        if (hardenRefreshPosted) {
            return;
        }
        hardenRefreshPosted = true;
        handler.postDelayed(() -> {
            hardenRefreshPosted = false;
            if (!isFinishing() && !isDestroyed()) {
                adapter.notifyDataSetChanged();
            }
        }, 300L);
    }

    // ==================== 引擎状态 ====================

    private void refreshEngine() {
        if (EpicDump.isInstalled(this)) {
            engineStatus.setText(R.string.dump_engine_ready);
            engineSub.setText(R.string.dump_engine_ready_sub);
            engineAction.setText(R.string.dump_engine_open);
        } else {
            engineStatus.setText(R.string.dump_engine_missing);
            engineSub.setText(R.string.dump_engine_missing_sub);
            engineAction.setText(R.string.dump_engine_install);
        }
    }

    private void onEngineAction() {
        if (EpicDump.isInstalled(this)) {
            EpicDump.open(this);
        } else {
            EpicDump.promptInstall(this);
        }
    }

    // ==================== 目标列表 ====================

    private void switchMode(boolean apk) {
        if (apkMode == apk) {
            return;
        }
        apkMode = apk;
        selectedKey = "";
        selectedLabel = "";
        selectedHarden = "";
        updateTabs();
        updatePickHint();
        adapter.setItems(new ArrayList<>());
        if (apk) {
            loadApks();
        } else {
            loadApps();
        }
    }

    private void updateTabs() {
        int accent = Util.accentColor(this);
        int secondary = ContextCompat.getColor(this, R.color.text_secondary);
        tabAppsText.setTextColor(apkMode ? secondary : accent);
        tabApkText.setTextColor(apkMode ? accent : secondary);
        tabAppsLine.setVisibility(apkMode ? View.INVISIBLE : View.VISIBLE);
        tabApkLine.setVisibility(apkMode ? View.VISIBLE : View.INVISIBLE);
        browseButton.setVisibility(apkMode ? View.VISIBLE : View.GONE);
        // 来源筛选只对「本机应用」有意义，APK 文件模式没有系统应用这个概念
        if (filterBar != null) {
            filterBar.setVisibility(apkMode ? View.GONE : View.VISIBLE);
        }
    }

    /** 筛选栏：全部 / 本机应用 / 系统应用（v5.9.37）。 */
    private void bindAppFilterBar() {
        filterBar = findViewById(R.id.filter_bar);
        filterUser = findViewById(R.id.filter_user);
        filterSystem = findViewById(R.id.filter_system);
        // 顶部那一行「全部应用」就是全部，所以这里只放两个子项；
        // 再点一次已选中的项即取消筛选、回到全部，不必再多一个「全部」按钮。
        filterUser.setOnClickListener(v -> setAppFilter(1));
        filterSystem.setOnClickListener(v -> setAppFilter(2));
    }

    private void setAppFilter(int mode) {
        appFilterMode = appFilterMode == mode ? 0 : mode;
        applyFilter();
        listView.setSelection(0);
    }

    private void updateAppFilterBar() {
        if (filterUser == null) {
            return;
        }
        styleFilterChip(filterUser, appFilterMode == 1,
                getString(R.string.dump_filter_user, countUser));
        styleFilterChip(filterSystem, appFilterMode == 2,
                getString(R.string.dump_filter_system, countSystem));
    }

    private void styleFilterChip(TextView view, boolean on, String text) {
        int accent = Util.accentColor(this);
        view.setText(text);
        view.setTextColor(on ? 0xFFFFFFFF
                : ContextCompat.getColor(this, R.color.text_secondary));
        int radius = Math.round(13 * getResources().getDisplayMetrics().density);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        if (on) {
            bg.setColor(accent);
        } else {
            bg.setColor(0x0A000000);
            bg.setStroke(Math.max(1, Math.round(getResources().getDisplayMetrics().density)),
                    ContextCompat.getColor(this, R.color.divider));
        }
        view.setBackground(bg);
        view.getPaint().setFakeBoldText(on);
    }

    private void updatePickHint() {
        if (selectedKey.isEmpty()) {
            pickHint.setText(R.string.dump_pick_hint);
            return;
        }
        String text = selectedLabel;
        if (selectedHarden != null && !selectedHarden.isEmpty()
                && !HardenDetect.NOT_HARDENED.equals(selectedHarden)) {
            text = selectedLabel + " · " + selectedHarden;
        }
        pickHint.setText(getString(R.string.dump_picked, text));
    }

    /** 条目唯一标识：本机应用用包名，APK 文件用路径。 */
    private String key(AppItem item) {
        if (item == null) {
            return "";
        }
        if (apkMode && item.sourceDir != null) {
            return item.sourceDir;
        }
        return item.packageName == null ? "" : item.packageName;
    }

    /**
     * v5.9.2：本机应用列表改用「三路合并」，尽量把本机应用都列全。
     *
     * <p>Android 11 起有包可见性限制，部分 ROM 会忽略 {@code QUERY_ALL_PACKAGES}，
     * 只靠 {@code getInstalledPackages()} 常常只能看到一部分应用。这里依次用
     * ① 全部已安装包、② 有启动图标的应用、③ 全部 ApplicationInfo 三路取并集去重，
     * 并把实际识别到的数量显示在标题上，便于判断是不是被系统限制了。</p>
     */
    private void loadApps() {
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            PackageManager pm = getPackageManager();
            final List<AppItem> items = new ArrayList<>();
            // v5.9.37 提速：三路只做「取并集」，不再逐包回查。
            // 旧写法是每条路都调一次 pm.getPackageInfo(包名)，500 个应用 = 上千次 binder IPC，
            // 光这一步就要好几秒。现在三路拿到的 ApplicationInfo 直接复用，版本号从
            // getInstalledPackages() 那一次的结果里查表，全程只有 3 次 IPC。
            final Map<String, PackageInfo> rich = new HashMap<>();
            final LinkedHashMap<String, ApplicationInfo> apps = new LinkedHashMap<>();
            try {
                for (PackageInfo info : pm.getInstalledPackages(0)) {
                    if (info == null || info.packageName == null) {
                        continue;
                    }
                    // 只保留第一份，后面的路不再覆盖（避免重复解析 label）
                    if (!rich.containsKey(info.packageName)) {
                        rich.put(info.packageName, info);
                    }
                    if (info.applicationInfo != null && !apps.containsKey(info.packageName)) {
                        apps.put(info.packageName, info.applicationInfo);
                    }
                }
            } catch (Throwable ignored) {
                // 单路失败不影响其余两路
            }
            try {
                Intent launcher = new Intent(Intent.ACTION_MAIN);
                launcher.addCategory(Intent.CATEGORY_LAUNCHER);
                for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
                    if (ri == null || ri.activityInfo == null
                            || ri.activityInfo.applicationInfo == null) {
                        continue;
                    }
                    ApplicationInfo ai = ri.activityInfo.applicationInfo;
                    if (ai.packageName != null && !apps.containsKey(ai.packageName)) {
                        apps.put(ai.packageName, ai);
                    }
                }
            } catch (Throwable ignored) {
                // 同上
            }
            try {
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    if (ai == null || ai.packageName == null) {
                        continue;
                    }
                    if (!apps.containsKey(ai.packageName)) {
                        apps.put(ai.packageName, ai);
                    }
                }
            } catch (Throwable ignored) {
                // 同上
            }
            for (Map.Entry<String, ApplicationInfo> e : apps.entrySet()) {
                items.add(buildAppItem(pm, e.getKey(), e.getValue(), rich.get(e.getKey())));
            }
            // 本机应用排前面、系统应用沉底，组内按名称——一眼就能看出哪些是系统自带
            Collections.sort(items, (a, b) -> a.system != b.system
                    ? (a.system ? 1 : -1)
                    : a.label.compareToIgnoreCase(b.label));
            final long cost = System.currentTimeMillis() - t0;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || apkMode) {
                    return;
                }
                all.clear();
                all.addAll(items);
                applyFilter();
                startHardenScan();
            });
            android.util.Log.i("DumpActivity",
                    "loadApps: " + items.size() + " 个应用，用时 " + cost + "ms");
        }, "dump-apps").start();
    }

    /**
     * 系统应用判定（v5.9.37）。
     *
     * <p>只看 {@code FLAG_SYSTEM} 会在两种情况下判断错：系统应用被升级后装到 /data/app，
     * 这时标志变成 {@code FLAG_UPDATED_SYSTEM_APP}；反过来某些 ROM 又给普通应用乱打
     * {@code FLAG_SYSTEM}。所以以安装路径为主、标志位为辅。</p>
     */
    static boolean isSystemApp(ApplicationInfo ai) {
        if (ai == null) {
            return false;
        }
        String dir = ai.sourceDir != null ? ai.sourceDir : ai.publicSourceDir;
        if (dir != null && dir.startsWith("/data/")) {
            // 装在用户数据分区：只有「系统应用的更新版」才会带着这个标志
            return (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        }
        if (dir != null) {
            for (String prefix : SYSTEM_DIR_PREFIXES) {
                if (dir.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    private void loadApks() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            final List<AppItem> items = new ArrayList<>();
            final List<File> found = new ArrayList<>();
            String[] dirs = {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            .getAbsolutePath(),
                    "/sdcard/AgentAttachments",
                    "/sdcard/Documents",
                    "/sdcard"
            };
            for (String dir : dirs) {
                File[] children = new File(dir).listFiles();
                if (children == null) {
                    continue;
                }
                for (File file : children) {
                    if (file != null && file.isFile()
                            && file.getName().toLowerCase(Locale.ROOT).endsWith(".apk")
                            && !found.contains(file)) {
                        found.add(file);
                    }
                }
            }
            Collections.sort(found, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File file : found) {
                items.add(buildApkItem(pm, file));
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !apkMode) {
                    return;
                }
                all.clear();
                all.addAll(items);
                applyFilter();
            });
        }, "dump-apks").start();
    }

    private AppItem buildAppItem(PackageManager pm, String pkg,
                                 ApplicationInfo app, PackageInfo info) {
        AppItem item = new AppItem();
        CharSequence label = null;
        try {
            label = app.loadLabel(pm);
        } catch (Throwable ignored) {
            // 忽略
        }
        item.label = label == null || label.length() == 0 ? pkg : label.toString();
        item.packageName = pkg;
        item.versionName = info == null || info.versionName == null ? "-" : info.versionName;
        item.versionCode = info == null ? 0 : info.versionCode;
        item.system = isSystemApp(app);
        item.appClass = app.className;
        // 一级检测：application 类名（零成本，等价于加固壳入口类）
        String byName = HardenDetect.byApplicationName(app.className);
        if (byName != null) {
            item.harden = byName;
        }
        String dir = app.sourceDir != null ? app.sourceDir : app.publicSourceDir;
        item.sourceDir = dir;
        if (dir != null) {
            File file = new File(dir);
            if (file.exists()) {
                item.size = file.length();
            }
        }
        return item;
    }

    private AppItem buildApkItem(PackageManager pm, File file) {
        AppItem item = new AppItem();
        item.label = file.getName();
        item.sourceDir = file.getAbsolutePath();
        item.size = file.length();
        item.packageName = file.getAbsolutePath();
        item.versionName = "-";
        try {
            PackageInfo info = pm.getPackageArchiveInfo(file.getAbsolutePath(), 0);
            if (info != null) {
                item.packageName = info.packageName;
                item.versionName = info.versionName == null ? "-" : info.versionName;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return item;
    }

    private void applyFilter() {
        String kw = keyword.toLowerCase(Locale.ROOT);
        shown.clear();
        countUser = 0;
        countSystem = 0;
        for (AppItem item : all) {
            if (item.system) {
                countSystem++;
            } else {
                countUser++;
            }
        }
        for (AppItem item : all) {
            // 来源筛选：只看本机应用 / 只看系统应用
            if (!apkMode && appFilterMode == 1 && item.system) {
                continue;
            }
            if (!apkMode && appFilterMode == 2 && !item.system) {
                continue;
            }
            if (kw.isEmpty()) {
                shown.add(item);
                continue;
            }
            String label = item.label == null ? "" : item.label.toLowerCase(Locale.ROOT);
            String pkg = item.packageName == null ? "" : item.packageName.toLowerCase(Locale.ROOT);
            String harden = item.harden == null ? "" : item.harden.toLowerCase(Locale.ROOT);
            if (label.contains(kw) || pkg.contains(kw) || harden.contains(kw)) {
                shown.add(item);
            }
        }
        adapter.setItems(shown);
        updateAppFilterBar();
        // 标题上带出识别到的数量：本机应用明显偏少时，多半是系统限制了包可见性
        if (apkMode) {
            tabApkText.setText(getString(R.string.dump_tab_apk_count, all.size()));
        } else {
            tabAppsText.setText(getString(R.string.dump_tab_apps_count, all.size()));
        }
        boolean empty = shown.isEmpty();
        if (empty) {
            if (apkMode) {
                emptyView.setText(R.string.dump_apk_empty);
            } else if (appFilterMode == 1) {
                emptyView.setText(R.string.dump_app_empty_user);
            } else if (appFilterMode == 2) {
                emptyView.setText(R.string.dump_app_empty_system);
            } else {
                emptyView.setText(R.string.dump_app_empty);
            }
        }
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void showSearchDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.dump_search_hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(keyword);
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(pad, Math.round(8 * getResources().getDisplayMetrics().density), pad, 0);
        holder.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.search)
                .setView(holder)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.apps_search_clear, (dialog, which) -> {
                    keyword = "";
                    applyFilter();
                })
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    keyword = input.getText().toString().trim();
                    applyFilter();
                })
                .show();
    }

    /** APK 模式：文件选择器（外部存储能直接拿到真实路径，其它来源先复制到结果目录）。 */
    private void browseApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        try {
            startActivityForResult(intent, REQ_PICK_APK);
        } catch (Throwable t) {
            toast(getString(R.string.dump_apk_unreadable));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_APK || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        new Thread(() -> {
            final File file = resolveToFile(uri);
            runOnUiThread(() -> {
                if (file == null || !file.isFile()) {
                    toast(getString(R.string.dump_apk_unreadable));
                    return;
                }
                AppItem item = buildApkItem(getPackageManager(), file);
                for (int i = all.size() - 1; i >= 0; i--) {
                    if (key(all.get(i)).equals(item.sourceDir)) {
                        all.remove(i);
                    }
                }
                all.add(0, item);
                applyFilter();
                selectedKey = item.sourceDir;
                selectedLabel = item.label;
                selectedHarden = item.harden == null ? "" : item.harden;
                adapter.notifyDataSetChanged();
                updatePickHint();
            });
        }, "dump-pick").start();
    }

    private File resolveToFile(Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) {
                File file = new File(uri.getPath());
                return file.isFile() ? file : null;
            }
            if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                String docId = DocumentsContract.getDocumentId(uri);
                int colon = docId.indexOf(':');
                String volume = colon > 0 ? docId.substring(0, colon) : "primary";
                String relative = colon > 0 ? docId.substring(colon + 1) : docId;
                String base = "primary".equalsIgnoreCase(volume)
                        ? Environment.getExternalStorageDirectory().getAbsolutePath()
                        : "/storage/" + volume;
                File file = new File(base, relative);
                if (file.isFile()) {
                    return file;
                }
            }
            return copyToDumpDir(uri);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 其它 provider（网盘之类）拿不到真实路径：复制一份到结果目录再交给引擎。 */
    private File copyToDumpDir(Uri uri) throws Exception {
        File dir = EpicDump.dumpDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        File out = FileOps.uniqueTarget(dir, displayName(uri));
        InputStream in = null;
        OutputStream os = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            os = new FileOutputStream(out);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                os.write(buffer, 0, read);
            }
            os.flush();
        } finally {
            if (in != null) {
                in.close();
            }
            if (os != null) {
                os.close();
            }
        }
        return out;
    }

    private String displayName(Uri uri) {
        try {
            android.database.Cursor cursor = getContentResolver()
                    .query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                            null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        String name = cursor.getString(0);
                        if (name != null && !name.isEmpty()) {
                            return name;
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return "picked.apk";
    }

    // ==================== 开始脱壳 / 轮询进度 ====================

    private void startRun() {
        if (!EpicDump.isInstalled(this)) {
            EpicDump.promptInstall(this);
            return;
        }
        if (selectedKey.isEmpty()) {
            toast(getString(R.string.dump_need_target));
            return;
        }
        if (running) {
            return;
        }
        retries = 0;
        launchRun(selectedKey, !apkMode);
    }

    /**
     * v5.8：把一次尝试单独拆出来，便于「引擎冷启动第一条失败」时自动重试。
     *
     * <p>免 Root 脱壳由引擎进程里的 BlackBox 沙箱完成，引擎进程刚被拉起时沙箱服务
     * 往往还没就绪，第一条 [error] 属于常态。这里自动再来一次，用户点一下就能跑通，
     * 不必先手动打开引擎预热。</p>
     */
    private void launchRun(final String source, final boolean packageSource) {
        final File dumpDir = EpicDump.dumpDir();
        String pkg = packageSource ? source : packageOf(new File(source));
        runPkg = pkg;
        classifyStarted = false;
        kindSummaryShown = false;
        dexKinds = new java.util.HashMap<>();
        resultDir = pkg == null ? dumpDir : new File(dumpDir, pkg);

        lastSource = source;
        lastPackageSource = packageSource;

        logOffset = EpicDump.logLength();
        logBuffer.setLength(0);
        setLogText("");
        dexCount = 0;
        dexBytes = 0;
        lastDexCount = 0;
        lastDexBytes = 0;
        dexChangeAt = 0;
        newestDexTime = 0;
        quietMs = QUIET_MS;
        progressCurrent = 0;
        progressTotal = 0;
        sawLoading = false;
        runStart = System.currentTimeMillis();
        running = true;

        runHarden = selectedHarden == null ? "" : selectedHarden;
        runTarget.setText(getString(R.string.dump_run_target, selectedLabel));
        runPhase.setText(retries == 0
                ? getString(R.string.dump_phase_prepare)
                : getString(R.string.dump_retry_running, retries));
        runPhase.setTextColor(Util.accentColor(this));
        runDex.setText("");
        runBar.setIndeterminate(true);
        showStage(STAGE_RUN);

        handler.removeCallbacks(poller);
        handler.postDelayed(poller, POLL_MS);
        // 用户明确点了「开始脱壳」：上一次的任务无论什么状态都作废，
        // 否则会被残留的 busy 顶回来，界面直接跳到失败页。
        FmBlackDex.abort();
        if (!EpicDump.startDump(this, source, packageSource, dumpDir)) {
            finishRun("error", getString(FmBlackDex.isBusy()
                    ? R.string.dump_busy_retry
                    : R.string.dump_engine_missing));
        }
    }

    /** 引擎冷启动导致的失败：短暂冷却后自动再来一次。 */
    private void scheduleRetry() {
        running = false;
        handler.removeCallbacks(poller);
        runPhase.setText(getString(R.string.dump_retry_hint, retries));
        runDex.setText(getString(R.string.dump_elapsed, elapsedText()));
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                launchRun(lastSource, lastPackageSource);
            }
        }, RETRY_DELAY_MS);
    }

    /** 判断是不是「引擎刚起来、沙箱服务还没就绪」这类值得重试的错误。 */
    private static boolean isColdStartError(String line) {
        if (line == null) {
            return false;
        }
        // 注意：不要把「安装失败」算进来——那是目标包本身的问题，重试没有意义
        return line.contains("沙箱") || line.contains("就绪") || line.contains("InstallResult")
                || line.contains("bind") || line.contains("RemoteException");
    }

    private String elapsedText() {
        long seconds = Math.max(0, (System.currentTimeMillis() - runStart) / 1000);
        if (seconds < 60) {
            return seconds + "s";
        }
        return (seconds / 60) + "m" + (seconds % 60) + "s";
    }

    private String packageOf(File apk) {
        try {
            PackageInfo info = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            return info == null ? null : info.packageName;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 每秒多看一次引擎日志和输出目录：这边只是「观众」，脱壳在引擎进程里跑。 */
    private void pollOnce() {
        if (!running) {
            return;
        }
        long[] next = new long[1];
        for (String line : EpicDump.readNewLines(logOffset, next)) {
            appendLog(line);
            handleLogLine(line);
        }
        logOffset = next[0];
        if (!running) {
            return;
        }
        scanDex();
        long now = System.currentTimeMillis();
        // v5.9.43：任务日志里已经写明「这次没戏」（拿不到目标包名 / 引擎服务没起来），
        // 又一条 dex 都没出，就别让用户对着转圈干等到 90~110 秒。已经出了产物就不采信——
        // 那说明链路是通的，日志里的报错只是局部失败。
        if (dexCount == 0 && now - runStart > FATAL_CHECK_AFTER_MS) {
            String fatal = FmBlackDex.fatalTaskHint();
            if (fatal != null) {
                finishRun("timeout", fatal);
                return;
            }
        }
        // v5.9：引擎的结束回调不可靠（沙箱里成功/失败都可能不回调），
        // 所以这里用「产物静默」兜底：目录里有本次新产出的 dex，且一段时间不再变化就算脱完了。
        // newestDexTime > runStart 用来排除上一次脱壳留下的旧文件（同名文件被覆盖时 mtime 会刷新）。
        boolean fresh = dexCount > 0 && newestDexTime > runStart;
        if (fresh && (sawLoading || now - runStart > 4000L)) {
            if (dexCount != lastDexCount || dexBytes != lastDexBytes) {
                lastDexCount = dexCount;
                lastDexBytes = dexBytes;
                dexChangeAt = now;
            } else if (now - dexChangeAt >= quietMs) {
                finishRun("success", null);
                return;
            }
        }
        // v5.9.5：迟迟没有任何「本次新产出的 dex」就直接判失败，别让人对着转圈等十分钟
        if (!fresh && now - runStart
                > (sawLoading ? NO_OUTPUT_LIMIT_LOADING_MS : NO_OUTPUT_LIMIT_MS)) {
            finishRun("timeout", getString(sawLoading
                    ? R.string.dump_phase_no_output_loading
                    : R.string.dump_phase_no_output));
            return;
        }
        if (now - runStart > HARD_LIMIT_MS) {
            finishRun(dexCount > 0 ? "success" : "timeout",
                    getString(R.string.dump_phase_timeout));
            return;
        }
        updateRunUi();
        handler.postDelayed(poller, POLL_MS);
    }

    private void handleLogLine(String line) {
        if (line.contains("[loading]")) {
            sawLoading = true;
            return;
        }
        Matcher matcher = PROGRESS.matcher(line);
        if (matcher.find()) {
            progressCurrent = parseInt(matcher.group(1));
            progressTotal = parseInt(matcher.group(2));
            // 引擎报「X/X」= 它认为已经抽完了，后面只剩修 dex 头这类收尾，别再多等
            if (progressTotal > 0 && progressCurrent >= progressTotal) {
                quietMs = QUIET_TAIL_MS;
            }
            return;
        }
        if (line.contains("[success]")) {
            // 引擎会在成功行里带上真实输出目录，以它为准（我们只是把 dumpDir 顺手带过去）
            String dir = extractDir(line);
            if (dir != null) {
                File actual = new File(dir);
                if (actual.isDirectory()) {
                    resultDir = actual;
                }
            }
            finishRun("success", line);
        } else if (line.contains("[timeout]")) {
            finishRun("timeout", line);
        } else if (line.contains("[error]")) {
            if (dexCount > 0) {
                // 引擎报错但结果目录已经有产物：多半是收尾阶段的误报，交给「产物静默」判定
                return;
            }
            if (retries < RETRY_LIMIT && isColdStartError(line)) {
                retries++;
                scheduleRetry();
            } else {
                finishRun("error", line);
            }
        }
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 从 {@code [success] dir=/xx/yy files=3} 里取出目录。 */
    private static String extractDir(String line) {
        int start = line.indexOf("dir=");
        if (start < 0) {
            return null;
        }
        String rest = line.substring(start + 4);
        int files = rest.indexOf(" files=");
        if (files >= 0) {
            rest = rest.substring(0, files);
        }
        rest = rest.trim();
        return rest.isEmpty() ? null : rest;
    }

    private void updateRunUi() {
        if (progressTotal > 0) {
            runBar.setIndeterminate(false);
            runBar.setMax(progressTotal);
            runBar.setProgress(Math.min(progressCurrent, progressTotal));
            runPhase.setText(getString(R.string.dump_phase_progress, progressCurrent, progressTotal));
        } else if (sawLoading) {
            runBar.setIndeterminate(true);
            runPhase.setText(R.string.dump_phase_launch);
        } else if (System.currentTimeMillis() - runStart > 25000L && dexCount == 0) {
            // 二十多秒还没产物：多半在过加固壳的初始化，给个安抚文案免得像卡死
            runBar.setIndeterminate(true);
            runPhase.setText(R.string.dump_phase_slow);
        } else {
            runBar.setIndeterminate(true);
            runPhase.setText(R.string.dump_phase_prepare);
        }
        // 阶段细化：脱壳在沙箱进程里跑，宿主拿不到它的状态，但两边往同一个产物目录写日志，
        // 把最后一条阶段文本显示出来，用户才能看出「还在推进」而不是卡死。
        String stage = FmBlackDex.tailTaskStage();
        if (stage != null && !stage.isEmpty()) {
            runPhase.setText(stage);
        }
        runDex.setText(dexCount > 0
                ? getString(R.string.dump_elapsed_dex, elapsedText(), dexCount,
                        Util.formatSize(dexBytes))
                : getString(R.string.dump_elapsed, elapsedText()));
    }

    private void appendLog(String line) {
        logBuffer.append(line).append('\n');
        if (logBuffer.length() > 12000) {
            logBuffer.delete(0, 4000);
        }
        setLogText(logBuffer.toString());
    }

    private void setLogText(String text) {
        runLog.setText(text);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** 统计结果目录里的 dex 数量与体积（脱壳进度最直观的体现）。 */
    private void scanDex() {
        dexCount = 0;
        dexBytes = 0;
        newestDexTime = 0;
        if (resultDir == null || !resultDir.isDirectory()) {
            return;
        }
        collectDex(resultDir, new int[1], new long[1]);
    }

    private void collectDex(File dir, int[] count, long[] bytes) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child == null) {
                continue;
            }
            if (child.isDirectory()) {
                collectDex(child, count, bytes);
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(".dex")) {
                // 0 字节是引擎 dump 到一半崩溃留下的残片，不能算「脱出来了」
                if (child.length() > 0L) {
                    count[0]++;
                }
                bytes[0] += child.length();
                newestDexTime = Math.max(newestDexTime, child.lastModified());
            }
        }
        dexCount = count[0];
        dexBytes = bytes[0];
    }

    private void finishRun(String result, String detail) {
        if (!running) {
            return;
        }
        running = false;
        handler.removeCallbacks(poller);
        scanDex();
        // v5.9：只要结果目录里已经有 dex，就不该报失败（引擎的失败回调经常是误报）
        if ("error".equals(result) && dexCount > 0) {
            result = "success";
            detail = null;
        }
        showStage(STAGE_DONE);

        // 标题带一个状态符号，一眼就能分清这次到底成没成
        if ("success".equals(result)) {
            doneTitle.setText("✓  " + getString(R.string.dump_result_success));
            doneTitle.setTextColor(Util.accentColor(this));
        } else if ("timeout".equals(result)) {
            doneTitle.setText("⏱  " + getString(R.string.dump_result_timeout));
            doneTitle.setTextColor(ContextCompat.getColor(this, R.color.accent_orange));
        } else {
            doneTitle.setText("✗  " + getString(R.string.dump_result_fail));
            doneTitle.setTextColor(ContextCompat.getColor(this, R.color.accent_red));
        }

        List<File> files = new ArrayList<>();
        long total = collectFiles(resultDir, files, 0);
        StringBuilder message = new StringBuilder();
        if ("success".equals(result)) {
            message.append(getString(R.string.dump_result_summary, files.size(),
                    Util.formatSize(total)));
        } else if (detail != null && !detail.isEmpty()) {
            message.append(detail);
            message.append(getString(R.string.dump_error_hint));
        }
        if (dexCount > 0) {
            if (message.length() > 0) {
                message.append('\n');
            }
            message.append(getString(R.string.dump_dex_count, dexCount, Util.formatSize(dexBytes)));
        }
        if (runHarden != null && !runHarden.isEmpty()
                && !HardenDetect.NOT_HARDENED.equals(runHarden)) {
            if (message.length() > 0) {
                message.append('\n');
            }
            message.append(getString(R.string.dump_run_harden, runHarden));
        }
        // v5.9.3：把本次耗时摆出来，方便判断速度是否正常
        if (message.length() > 0) {
            message.append('\n');
        }
        message.append(getString(R.string.dump_total_elapsed,
                String.format(Locale.ROOT, "%.1f",
                        (System.currentTimeMillis() - runStart) / 1000f)));
        doneMsg.setText(message);
        doneDir.setText(resultDir == null ? "" : resultDir.getAbsolutePath());

        fillDoneFiles();
    }

    /** 结果页的文件清单（脱壳与快速解包共用）。 */
    private void fillDoneFiles() {
        List<File> files = new ArrayList<>();
        collectFiles(resultDir, files, 0);
        if (files.isEmpty()) {
            doneFiles.setText(R.string.dump_result_empty);
            return;
        }
        renderDoneFiles(files, dexKinds);
        if (!classifyStarted) {
            classifyStarted = true;
            classifyDexes(files);
        }
    }

    /**
     * 分辨哪些 dex 是目标应用真正脱出来的、哪些是沙箱自带的。目标应用跑在宿主 apk 里，
     * 目标进程的 classpath 同时挂着宿主 dex 与 blackbox 框架 dex，产物必然混在一起。
     * 扫描可能几十 MB，所以放后台线程，识别完再给清单上色。
     */
    private void classifyDexes(final List<File> files) {
        new Thread(() -> {
            // 先算出安装包里那几个 dex 的指纹：产物里跟它一模一样的就是「没脱出来」。
            final java.util.Set<String> apkDigests = DexClassifier.apkDexDigests(targetApk());
            final java.util.Map<String, Integer> kinds = new java.util.HashMap<>();
            int empty = 0;
            for (File f : files) {
                if (!isDexFile(f)) {
                    continue;
                }
                if (f.length() == 0L) {
                    empty++;
                    continue;
                }
                try {
                    kinds.put(f.getAbsolutePath(), DexClassifier.classify(f, runPkg, apkDigests));
                } catch (Throwable ignored) {
                }
            }
            emptyDexCount = empty;
            handler.post(() -> {
                dexKinds = kinds;
                if (resultDir == null) {
                    return;
                }
                renderDoneFiles(files, kinds);
                appendKindSummary(kinds);
            });
        }, "fm-dex-classify").start();
    }

    private static boolean isDexFile(File f) {
        return f.getName().toLowerCase(Locale.ROOT).endsWith(".dex");
    }

    /** 本次目标的安装包，用来判断「产物里的 dex 是不是安装包里那份原封不动的壳」。 */
    private File targetApk() {
        if (runPkg != null && !runPkg.isEmpty()) {
            try {
                android.content.pm.ApplicationInfo info =
                        getPackageManager().getApplicationInfo(runPkg, 0);
                if (info != null && info.sourceDir != null) {
                    File apk = new File(info.sourceDir);
                    if (apk.isFile()) {
                        return apk;
                    }
                }
            } catch (Throwable ignored) {
                // 取不到就往下试文件模式
            }
        }
        if (selectedKey != null) {
            File apk = new File(selectedKey);
            if (apk.isFile() && apk.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                return apk;
            }
        }
        return null;
    }

    /** 结果清单：真实 dex 绿色、沙箱自带灰色、未识别橙色，开头给出图例。 */
    private void renderDoneFiles(List<File> files, java.util.Map<String, Integer> kinds) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int legendStart = sb.length();
        sb.append(getString(R.string.dump_dex_legend)).append('\n').append('\n');
        sb.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_secondary)),
                legendStart, sb.length() - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        int limit = Math.min(files.size(), 300);
        for (int i = 0; i < limit; i++) {
            File file = files.get(i);
            int start = sb.length();
            sb.append(relativeName(resultDir, file))
                    .append("    ")
                    .append(Util.formatSize(file.length()));
            Integer kind = kinds == null ? null : kinds.get(file.getAbsolutePath());
            if (kind != null) {
                // 文件名 / 大小保持正文色，只有「色块 + 标签」上类型色，扫读时更清爽
                sb.setSpan(new ForegroundColorSpan(
                                ContextCompat.getColor(this, R.color.text_primary)),
                        start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                int mark = sb.length();
                sb.append("   ").append('●').append(' ');
                sb.setSpan(new ForegroundColorSpan(colorOf(kind)), mark, mark + 4,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                int tag = sb.length();
                sb.append(getString(tagOf(kind)));
                sb.setSpan(new ForegroundColorSpan(colorOf(kind)), tag, sb.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sb.append('\n');
        }
        if (files.size() > limit) {
            sb.append("…");
        }
        doneFiles.setText(sb);
    }

    private int tagOf(int kind) {
        switch (kind) {
            case DexClassifier.REAL:
                return R.string.dump_dex_tag_real;
            case DexClassifier.PACKED:
                return R.string.dump_dex_tag_packed;
            case DexClassifier.SAME:
                return R.string.dump_dex_tag_same;
            case DexClassifier.SANDBOX:
                return R.string.dump_dex_tag_sandbox;
            case DexClassifier.PACKER:
                return R.string.dump_dex_tag_packer;
            default:
                return R.string.dump_dex_tag_unknown;
        }
    }

    private int colorOf(int kind) {
        switch (kind) {
            case DexClassifier.REAL:
                return ContextCompat.getColor(this, R.color.accent_green);
            case DexClassifier.PACKED:
                return ContextCompat.getColor(this, android.R.color.holo_red_light);
            case DexClassifier.SAME:
                return ContextCompat.getColor(this, R.color.accent_orange);
            case DexClassifier.SANDBOX:
                return ContextCompat.getColor(this, R.color.text_secondary);
            case DexClassifier.PACKER:
                return ContextCompat.getColor(this, android.R.color.holo_purple);
            default:
                return ContextCompat.getColor(this, android.R.color.darker_gray);
        }
    }

    /** 把「真实 / 没脱出来 / 沙箱自带 / 未识别」的数量补进结果说明，并点明是否真脱出来了。 */
    private void appendKindSummary(java.util.Map<String, Integer> kinds) {
        if (kindSummaryShown) {
            return;
        }
        if ((kinds == null || kinds.isEmpty()) && emptyDexCount == 0) {
            return;
        }
        java.util.Map<String, Integer> map = kinds == null
                ? java.util.Collections.<String, Integer>emptyMap() : kinds;
        int real = 0;
        int packed = 0;
        int same = 0;
        int sandbox = 0;
        int packer = 0;
        int unknown = 0;
        for (Integer k : map.values()) {
            if (k == null) {
                continue;
            }
            if (k == DexClassifier.REAL) {
                real++;
            } else if (k == DexClassifier.PACKED) {
                packed++;
            } else if (k == DexClassifier.SAME) {
                same++;
            } else if (k == DexClassifier.SANDBOX) {
                sandbox++;
            } else if (k == DexClassifier.PACKER) {
                packer++;
            } else {
                unknown++;
            }
        }
        kindSummaryShown = true;
        // 引擎崩溃留下的空残片最容易让人误以为「脱成功了」，单独点出来
        if (emptyDexCount > 0) {
            doneMsg.setText(doneMsg.getText().toString() + "\n"
                    + getString(R.string.dump_dex_empty_fragment, emptyDexCount));
        }
        if (kinds == null || kinds.isEmpty()) {
            return;
        }
        StringBuilder msg = new StringBuilder(getString(R.string.dump_dex_kind_summary,
                real, packed, same, sandbox, packer, unknown));
        if (real == 0) {
            if (packed > 0) {
                msg.append('\n').append(getString(R.string.dump_dex_all_packed));
            } else if (same > 0) {
                msg.append('\n').append(getString(R.string.dump_dex_all_same));
            } else {
                msg.append('\n').append(getString(R.string.dump_dex_no_real));
            }
        }
        doneMsg.setText(doneMsg.getText().toString() + "\n" + msg);
    }

    private String relativeName(File base, File file) {
        String basePath = base == null ? "" : base.getAbsolutePath();
        String path = file.getAbsolutePath();
        return path.startsWith(basePath) ? path.substring(basePath.length() + 1) : path;
    }

    private long collectFiles(File dir, List<File> out, int depth) {
        if (dir == null || !dir.isDirectory()) {
            return 0L;
        }
        long total = 0L;
        File[] children = dir.listFiles();
        if (children == null) {
            return total;
        }
        for (File child : children) {
            if (child == null) {
                continue;
            }
            if (child.isDirectory()) {
                if (depth < 4) {
                    total += collectFiles(child, out, depth + 1);
                }
            } else {
                out.add(child);
                total += child.length();
            }
        }
        Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return total;
    }

    private void showStage(int stage) {
        stagePick.setVisibility(stage == STAGE_PICK ? View.VISIBLE : View.GONE);
        stageRun.setVisibility(stage == STAGE_RUN ? View.VISIBLE : View.GONE);
        stageDone.setVisibility(stage == STAGE_DONE ? View.VISIBLE : View.GONE);
    }

    // ==================== 快速解包（不走引擎） ====================

    /**
     * v5.8：未加固的安装包根本不需要脱壳，直接把 APK 里的 classes*.dex 解出来就行，
     * 秒级完成且完全不经过引擎 —— 这才是真正意义的「不打开引擎」。
     */
    private void quickUnpack() {
        if (running) {
            return;
        }
        if (selectedKey.isEmpty()) {
            toast(getString(R.string.dump_need_target));
            return;
        }
        final File apk;
        if (apkMode) {
            apk = new File(selectedKey);
        } else {
            String dir = sourceDirOf(selectedKey);
            apk = dir == null ? null : new File(dir);
        }
        if (apk == null || !apk.isFile()) {
            toast(getString(R.string.dump_apk_unreadable));
            return;
        }
        final String pkg = packageOf(apk);
        toast(getString(R.string.dump_quick_working));
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                int count = 0;
                long[] bytes = new long[1];
                File outDir = new File(EpicDump.dumpDir(),
                        (pkg == null ? apk.getName() : pkg) + "_unpack");
                try {
                    count = unpackDex(apk, outDir, bytes);
                } catch (Throwable t) {
                    error = String.valueOf(t);
                }
                final int finalCount = count;
                final long finalBytes = bytes[0];
                final String finalError = error;
                final File finalDir = outDir;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        finishQuickUnpack(finalDir, finalCount, finalBytes, finalError);
                    }
                });
            }
        }, "dump-unpack").start();
    }

    /** 把顶层 classes*.dex 解到 outDir，返回 dex 个数（总字节数写进 bytes[0]）。 */
    private int unpackDex(File apk, File outDir, long[] bytes) throws Exception {
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new java.io.IOException("无法创建目录：" + outDir);
        }
        int count = 0;
        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk);
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.indexOf('/') >= 0) {
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("classes") || !lower.endsWith(".dex")) {
                    continue;
                }
                InputStream in = zip.getInputStream(entry);
                OutputStream out = new FileOutputStream(new File(outDir, name));
                try {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                        bytes[0] += read;
                    }
                    out.flush();
                } finally {
                    in.close();
                    out.close();
                }
                count++;
            }
        } finally {
            zip.close();
        }
        return count;
    }

    private void finishQuickUnpack(File outDir, int count, long bytes, String error) {
        resultDir = outDir;
        dexCount = count;
        dexBytes = bytes;
        showStage(STAGE_DONE);

        if (error != null) {
            doneTitle.setText(R.string.dump_result_fail);
            doneTitle.setTextColor(ContextCompat.getColor(this, R.color.accent_red));
            doneMsg.setText(getString(R.string.dump_quick_fail, error));
        } else if (count <= 0) {
            doneTitle.setText(R.string.dump_result_fail);
            doneTitle.setTextColor(ContextCompat.getColor(this, R.color.accent_orange));
            doneMsg.setText(R.string.dump_quick_none);
        } else {
            doneTitle.setText(R.string.dump_result_success);
            doneTitle.setTextColor(Util.accentColor(this));
            doneMsg.setText(getString(R.string.dump_quick_ok, count, Util.formatSize(bytes)));
        }
        doneDir.setText(outDir == null ? "" : outDir.getAbsolutePath());
        fillDoneFiles();
    }

    /** 本机已安装应用的安装包路径（应用模式也能快速解包）。 */
    private String sourceDirOf(String pkg) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            return info == null ? null : info.sourceDir;
        } catch (Throwable t) {
            return null;
        }
    }

    private void showSlowTip() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dump_tip_slow)
                .setMessage(R.string.dump_tip_slow_msg)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    // ==================== 结果操作 ====================

    private void openResultDir() {
        File dir = resultDir != null && resultDir.isDirectory() ? resultDir : EpicDump.dumpDir();
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_OPEN_PATH, dir.getAbsolutePath());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(intent);
        } catch (Throwable t) {
            toast(dir.getAbsolutePath());
        }
    }

    private void copyPath() {
        try {
            ClipboardManager manager =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) {
                return;
            }
            String path = resultDir == null ? EpicDump.dumpDir().getAbsolutePath()
                    : resultDir.getAbsolutePath();
            manager.setPrimaryClip(ClipData.newPlainText("dump-dir", path));
            toast(getString(R.string.dump_copied));
        } catch (Throwable t) {
            toast(getString(R.string.dump_copy_failed, String.valueOf(t)));
        }
    }

    private void openLog() {
        File log = logTarget();
        Intent intent = new Intent(this, TextEditorActivity.class);
        intent.putExtra(TextEditorActivity.EXTRA_PATH, log.getAbsolutePath());
        try {
            startActivity(intent);
        } catch (Throwable t) {
            toast(log.getAbsolutePath());
        }
    }

    /**
     * 「查看日志」打开哪个文件：优先本次这个包自己的日志——它就写在该包的产物目录里，
     * 每次任务覆盖写，内容就是这一次的过程。全局日志是跨任务累积的，直接开它会看到一堆
     * 历史任务，看起来就像「日志没同步到当前 APK」，所以只作为回退。
     */
    private File logTarget() {
        String pkg = runPkg;
        if ((pkg == null || pkg.isEmpty()) && resultDir != null) {
            String name = resultDir.getName();
            if (name != null && name.contains(".")) {
                pkg = name;
            }
        }
        if (pkg != null && !pkg.isEmpty()) {
            File f = EpicDump.taskLog(pkg);
            if (f.isFile() && f.length() > 0L) {
                return f;
            }
        }
        return EpicDump.resultLog();
    }

    private void toast(String text) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show();
    }

    /** 列表适配器：在通用应用行上补一层「当前选中」高亮。 */
    private class TargetAdapter extends AppRowAdapter {

        private final int selection;

        TargetAdapter(Context context) {
            super(context);
            selection = (Util.accentColor(context) & 0x00FFFFFF) | 0x33000000;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            AppItem item = getItemAt(position);
            boolean selected = item != null && !selectedKey.isEmpty()
                    && key(item).equals(selectedKey);
            view.setBackgroundColor(selected ? selection : 0x00000000);
            return view;
        }
    }
}
