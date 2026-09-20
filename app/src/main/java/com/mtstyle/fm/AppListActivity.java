package com.mtstyle.fm;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 已安装应用（MT 风格）：顶部计数、底部用户/系统切换、行内显示名称/版本/大小/包名。 */
public class AppListActivity extends AppCompatActivity {

    /** v5.6：带上该 extra 打开时，点应用即「提取安装包 → 交给『免费APK加密』」。 */
    public static final String EXTRA_CRYPTO = "crypto_deliver";

    private final List<AppItem> all = new ArrayList<>();
    private final List<AppItem> shown = new ArrayList<>();
    private AppRowAdapter adapter;
    private TextView titleView;
    private TextView emptyView;
    private TextView tabUserText;
    private TextView tabSystemText;
    private View tabUserLine;
    private View tabSystemLine;
    private ListView listView;

    private boolean showSystem;
    private boolean systemLoaded;
    private String keyword = "";
    /** v5.6：是否处于「交给免费APK加密」模式。 */
    private boolean cryptoMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Settings.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);

        titleView = findViewById(R.id.apps_title);
        emptyView = findViewById(R.id.apps_empty);
        tabUserText = findViewById(R.id.tab_user_text);
        tabSystemText = findViewById(R.id.tab_system_text);
        tabUserLine = findViewById(R.id.tab_user_line);
        tabSystemLine = findViewById(R.id.tab_system_line);
        listView = findViewById(R.id.apps_list);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_search).setOnClickListener(v -> showSearchDialog());
        findViewById(R.id.tab_user).setOnClickListener(v -> switchTab(false));
        findViewById(R.id.tab_system).setOnClickListener(v -> switchTab(true));

        adapter = new AppRowAdapter(this);
        listView.setAdapter(adapter);
        cryptoMode = getIntent().getBooleanExtra(EXTRA_CRYPTO, false);
        if (cryptoMode) {
            titleView.setText(R.string.apkcrypto_apps_title);
            toast(getString(R.string.apkcrypto_apps_hint));
        }
        listView.setOnItemClickListener((parent, view, position, id) -> {
            AppItem item = shown.get(position);
            if (cryptoMode) {
                deliverAppApk(item, true);
            } else {
                showAppInfo(item);
            }
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            showAppMenu(shown.get(position));
            return true;
        });

        updateTabs();
        loadApps();
    }

    // ==================== 顶部 / 底部状态 ====================

    private void switchTab(boolean system) {
        if (showSystem == system) {
            return;
        }
        showSystem = system;
        updateTabs();
        applyFilter();
    }

    private void updateTabs() {
        int accent = Util.accentColor(this);
        int secondary = ContextCompat.getColor(this, R.color.text_secondary);
        int primary = ContextCompat.getColor(this, R.color.text_primary);
        tabUserText.setTextColor(showSystem ? secondary : primary);
        tabSystemText.setTextColor(showSystem ? primary : secondary);
        tabUserLine.setVisibility(showSystem ? View.INVISIBLE : View.VISIBLE);
        tabSystemLine.setVisibility(showSystem ? View.VISIBLE : View.INVISIBLE);
        if (!showSystem) {
            tabUserText.setTextColor(accent);
        } else {
            tabSystemText.setTextColor(accent);
        }
    }

    private void showSearchDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.apps_search_hint);
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

    // ==================== 数据 ====================

    /**
     * 不做“准备中”模态提示：进页面立即渲染，后台先加载用户应用（快）并马上显示，
     * 紧接着补齐系统应用；图标由 AppRowAdapter 按行懒加载。
     */
    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<PackageInfo> packages;
            boolean failed = false;
            try {
                packages = pm.getInstalledPackages(0);
            } catch (Throwable t) {
                packages = new ArrayList<>();
                failed = true;
            }
            final boolean loadFailed = failed;
            final List<AppItem> userItems = new ArrayList<>();
            final List<PackageInfo> systemPackages = new ArrayList<>();
            for (PackageInfo info : packages) {
                if (info == null || info.applicationInfo == null) {
                    continue;
                }
                if ((info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    systemPackages.add(info);
                } else {
                    userItems.add(buildItem(pm, info));
                }
            }
            sortByLabel(userItems);

            // 第一批：用户应用 —— 立即显示，不等系统应用
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                all.clear();
                all.addAll(userItems);
                applyFilter();
                if (loadFailed) {
                    toast("读取应用列表失败");
                }
            });

            // 第二批：系统应用 —— 后台补齐后自动刷新
            final List<AppItem> systemItems = new ArrayList<>();
            for (PackageInfo info : systemPackages) {
                systemItems.add(buildItem(pm, info));
            }
            sortByLabel(systemItems);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                all.addAll(systemItems);
                systemLoaded = true;
                applyFilter();
            });
        }, "apps-load").start();
    }

    private AppItem buildItem(PackageManager pm, PackageInfo info) {
        AppItem item = new AppItem();
        ApplicationInfo app = info.applicationInfo;
        CharSequence label = null;
        try {
            label = app.loadLabel(pm);
        } catch (Throwable ignored) {
        }
        item.label = label == null ? info.packageName : label.toString();
        item.packageName = info.packageName;
        item.versionName = info.versionName == null ? "-" : info.versionName;
        item.versionCode = info.versionCode;
        item.system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        item.sourceDir = app.sourceDir;
        // 图标不在这里加载：交给 AppRowAdapter 按行懒加载，避免几百个图标拖慢进入页面
        if (app.sourceDir != null) {
            File dir = new File(app.sourceDir);
            if (dir.exists()) {
                item.size = dir.length();
            }
        }
        return item;
    }

    private static void sortByLabel(List<AppItem> list) {
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
    }

    private void applyFilter() {
        String key = keyword == null ? "" : keyword.toLowerCase();
        shown.clear();
        int userCount = 0;
        for (AppItem item : all) {
            if (item.system) {
                continue;
            }
            userCount++;
        }
        for (AppItem item : all) {
            if (showSystem != item.system) {
                continue;
            }
            if (!key.isEmpty()) {
                boolean hit = item.label.toLowerCase().contains(key)
                        || item.packageName.toLowerCase().contains(key);
                if (!hit) {
                    continue;
                }
            }
            shown.add(item);
        }
        adapter.setItems(shown);
        int base = showSystem ? all.size() - userCount : userCount;
        boolean pendingSystem = showSystem && !systemLoaded;
        if (!pendingSystem) {
            if (key.isEmpty()) {
                titleView.setText(getString(R.string.apps_count, base));
            } else {
                titleView.setText(getString(R.string.apps_count_filtered, shown.size(), base));
            }
        }
        emptyView.setVisibility(shown.isEmpty() && !pendingSystem ? View.VISIBLE : View.GONE);
        updateTabs();
    }

    // ==================== 单项操作 ====================

    /** MT 风格弹出菜单（长按应用）。 */
    private void showAppMenu(final AppItem item) {
        java.util.List<MtMenu.Item> items = new java.util.ArrayList<>();
        items.add(MtMenu.item(getString(R.string.apps_open), R.drawable.ic_folder,
                () -> openApp(item)));
        items.add(MtMenu.item(getString(R.string.apps_detail), R.drawable.ic_apk,
                () -> showAppInfo(item)));
        items.add(MtMenu.item(getString(R.string.apps_extract), R.drawable.ic_apk,
                () -> extractApk(item)));
        // v5.3：加固工具自带的「已安装应用」列表在本机读不到，这里直接把安装包提取到下载目录再交给它
        items.add(MtMenu.item(getString(R.string.apk_harden), R.drawable.ic_apk,
                () -> hardenApp(item)));
        // v5.6：提取安装包后直接交给「免费APK加密」
        items.add(MtMenu.item(getString(R.string.apkcrypto_entry), R.drawable.ic_shield,
                () -> deliverAppApk(item, true)));
        items.add(MtMenu.item(getString(R.string.apps_copy_pkg), R.drawable.ic_text,
                () -> copyText(item.packageName)));
        items.add(MtMenu.item(getString(R.string.act_share), R.drawable.ic_file,
                () -> shareApk(item)));
        items.add(MtMenu.item(getString(R.string.apps_uninstall), R.drawable.ic_trash,
                () -> uninstall(item)).danger());
        MtMenu.show(this, item.label, item.packageName + " · " + item.versionName, items);
    }

    private void openApp(AppItem item) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(item.packageName);
        if (intent == null) {
            toast("该应用没有可启动界面");
            return;
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast("打开失败：" + e.getMessage());
        }
    }

    private void showAppInfo(AppItem item) {
        try {
            Intent intent = new Intent(this, AppDetailActivity.class);
            intent.putExtra(AppDetailActivity.EXTRA_PACKAGE, item.packageName);
            startActivity(intent);
        } catch (Exception e) {
            toast("无法打开应用信息：" + e.getMessage());
        }
    }

    private void uninstall(final AppItem item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.apps_uninstall)
                .setMessage(item.label + "\n" + item.packageName)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_DELETE,
                            Uri.parse("package:" + item.packageName));
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        toast("无法调用卸载：" + e.getMessage());
                    }
                })
                .show();
    }

    private void shareApk(AppItem item) {
        File apk = new File(item.sourceDir == null ? "" : item.sourceDir);
        if (!apk.exists()) {
            toast("找不到安装包文件");
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

    /** 提取安装包到 /sdcard/Download/应用提取/。 */
    private void extractApk(final AppItem item) {
        final File apk = new File(item.sourceDir == null ? "" : item.sourceDir);
        if (!apk.exists()) {
            toast("找不到安装包文件");
            return;
        }
        final File destDir = new File(new File(Environment.getExternalStorageDirectory(),
                "Download"), "应用提取");
        final String fileName = sanitize(item.label) + "_" + item.versionName + ".apk";
        final File[] created = new File[1];
        TaskRunner.run(this, getString(R.string.apps_extracting), true,
                progress -> {
                    if (!destDir.exists() && !destDir.mkdirs()) {
                        throw new java.io.IOException("无法创建目录 " + destDir.getAbsolutePath());
                    }
                    File target = FileOps.uniqueTarget(destDir, fileName);
                    created[0] = target;
                    progress.publish("正在提取 " + target.getName(), 30);
                    FileOps.copy(apk, target, progress, new int[1], 1);
                    progress.publish("完成", 100);
                },
                (ok, message) -> {
                    if (ok) {
                        toast(getString(R.string.apps_extracted, created[0] == null
                                ? destDir.getAbsolutePath() : created[0].getAbsolutePath()));
                    } else {
                        toast(getString(R.string.apps_extract_failed, message));
                    }
                });
    }

    /**
     * v5.3：加固已安装应用。
     *
     * <p>外部加固工具自带的「已安装应用」列表在本机读不到（其页面逻辑在加密壳内，无法修改），
     * 因此改由本应用把该应用的安装包提取到下载目录，再按既有「加固 APK」流程交给工具——
     * 这样在工具的文件浏览里直接能看到并选中它，不依赖它自己的应用列表。</p>
     */
    private void hardenApp(final AppItem item) {
        deliverAppApk(item, false);
    }

    /**
     * v5.6：把已安装应用的安装包提取到下载目录，然后交给「加固工具」（crypto=false）
     * 或「免费APK加密」（crypto=true）。
     */
    private void deliverAppApk(final AppItem item, final boolean crypto) {
        final File src = new File(item.sourceDir == null ? "" : item.sourceDir);
        if (!src.exists()) {
            toast("找不到安装包文件");
            return;
        }
        final File dir = HardenAuto.DOWNLOAD_DIR;
        final String fileName = sanitize(item.label) + "_" + item.versionName + ".apk";
        final File[] created = new File[1];
        TaskRunner.run(this, getString(R.string.apps_extracting), true,
                progress -> {
                    File existing = new File(dir, fileName);
                    if (existing.isFile() && existing.length() == src.length()) {
                        created[0] = existing;
                        progress.publish("复用已提取的安装包", 100);
                        return;
                    }
                    if (!dir.isDirectory() && !dir.mkdirs()) {
                        throw new java.io.IOException("无法访问目录 " + dir.getAbsolutePath());
                    }
                    File target = FileOps.uniqueTarget(dir, fileName);
                    created[0] = target;
                    progress.publish("正在提取 " + target.getName(), 30);
                    FileOps.copy(src, target, progress, new int[1], 1);
                    progress.publish("完成", 100);
                },
                (ok, message) -> {
                    if (ok && created[0] != null) {
                        if (crypto) {
                            ApkCrypto.openWithApk(this, created[0]);
                        } else {
                            HardenTool.open(this, created[0]);
                        }
                    } else {
                        toast(getString(R.string.apps_extract_failed, String.valueOf(message)));
                    }
                });
    }

    private String sanitize(String name) {
        if (name == null) {
            return "app";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private void copyText(String text) {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("text", text));
            toast(getString(R.string.path_copied));
        }
    }

    private void toast(String text) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show();
    }
}
