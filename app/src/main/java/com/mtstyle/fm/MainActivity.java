package com.mtstyle.fm;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** MT 风格主界面：左侧侧滑栏 + 双列文件浏览 + 底部状态栏。 */
public class MainActivity extends AppCompatActivity {

    /** 记录当前已应用的外观，用于从设置页返回时判断是否需要重建。 */
    private int appliedAccent = -1;
    private int appliedNight = -1;

    private static final int REQ_MANAGE = 1001;
    private static final int REQ_READ = 1002;

    private static final int A_NEW_FOLDER = 1;
    private static final int A_NEW_FILE = 2;
    private static final int A_PASTE = 3;
    private static final int A_SELECT_ALL = 4;
    private static final int A_SEARCH = 5;
    private static final int A_JUMP = 6;
    private static final int A_SWAP = 7;
    private static final int A_SORT = 8;
    private static final int A_HIDDEN = 9;
    private static final int A_REFRESH = 10;
    private static final int A_ABOUT = 11;
    private static final int A_COPY_OTHER = 12;
    private static final int A_MOVE_OTHER = 13;
    private static final int A_DIGEST = 14;
    private static final int A_SET_HOME_LEFT = 15;
    private static final int A_SET_HOME_RIGHT = 16;
    private static final int A_CLEAR_HOME = 17;

    private DrawerLayout drawer;
    private DrawerAdapter drawerAdapter;
    private TextView topTitle;
    private View normalBar;
    private View selectBar;
    private TextView selectCount;

    private FilePane leftPane;
    private FilePane rightPane;
    private FilePane activePane;

    private SharedPreferences prefs;

    /** v5.5：侧滑栏「黑盾加固」分组的折叠状态 key。 */
    private static final String DRAWER_GROUP_BLACKSHIELD = "drawer_group_blackshield";

    /** v5.7：外部页面（免Root脱壳）带目录路径回到主界面时，用它指定要打开的目录。 */
    public static final String EXTRA_OPEN_PATH = "open_path";

    private interface NameCallback {
        void onName(String name);
    }

    private final FilePane.Listener paneListener = new FilePane.Listener() {
        @Override
        public void onPaneChanged(FilePane pane) {
            if (pane == activePane) {
                updateStatus();
            }
        }

        @Override
        public void onPaneActivated(FilePane pane) {
            setActivePane(pane);
        }

        @Override
        public void onSelectionChanged(FilePane pane) {
            if (pane == activePane) {
                updateSelectionUi();
            }
        }

        @Override
        public void onFileMenu(FilePane pane, File file) {
            if (pane != activePane) {
                setActivePane(pane);
            }
            try {
                if (IconLoader.isApk(file)) {
                    showApkDetail(file);
                } else if (IconLoader.isImage(file) && !IconLoader.isArchiveOpenable(file)) {
                    // 图片：直接用内置查看器显示原图
                    openImageViewer(file);
                } else if (IconLoader.isTextLike(file)) {
                    showTextFileMenu(file);
                } else {
                    openWith(file);
                }
            } catch (Exception e) {
                toast("操作失败：" + e.getMessage());
            }
        }

        @Override
        public void onPathBarClick(FilePane pane) {
            if (pane != activePane) {
                setActivePane(pane);
            }
            jumpDialog();
        }

        @Override
        public void onToolAction(FilePane pane, int action) {
            if (pane != activePane) {
                setActivePane(pane);
            }
            switch (action) {
                case FilePane.TOOL_UP:
                    pane.goUp();
                    break;
                case FilePane.TOOL_HOME:
                    openHome(pane);
                    break;
                case FilePane.TOOL_NEW:
                    newFolderDialog();
                    break;
                case FilePane.TOOL_SORT:
                    showSortDialog();
                    break;
                default:
                    showMoreMenu();
                    break;
            }
        }
    };

    /** 面板底部「主页」：未设置时把当前目录设为主页，长按可清除（在更多菜单里）。 */
    private void openHome(FilePane pane) {
        boolean left = pane == leftPane;
        String path = homePath(left);
        if (path == null) {
            setHome(left, pane.getCurrent());
            return;
        }
        pane.setPath(new File(path));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Settings.applyTheme(this);
        appliedAccent = Settings.accent(this);
        appliedNight = Settings.nightMode(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("fm", MODE_PRIVATE);

        drawer = findViewById(R.id.drawer_layout);
        // v4.9：每次打开侧滑栏时重建，保证「当前所在位置」高亮与最新目录一致
        drawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                rebuildDrawer();
            }
        });
        topTitle = findViewById(R.id.top_title);
        normalBar = findViewById(R.id.normal_bar);
        selectBar = findViewById(R.id.select_bar);
        selectCount = findViewById(R.id.select_count);

        ListView drawerList = findViewById(R.id.drawer_list);
        drawerAdapter = new DrawerAdapter(this);
        drawerList.setAdapter(drawerAdapter);

        leftPane = new FilePane(this, findViewById(R.id.pane_left), paneListener);
        rightPane = new FilePane(this, findViewById(R.id.pane_right), paneListener);

        findViewById(R.id.btn_menu).setOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
        findViewById(R.id.btn_up).setOnClickListener(v -> activePane.goUp());
        findViewById(R.id.btn_more).setOnClickListener(v -> showMoreMenu());
        // 侧滑栏底部固定的「更多」入口
        findViewById(R.id.drawer_more).setOnClickListener(v -> {
            drawer.closeDrawer(GravityCompat.START);
            showMoreMenu();
        });
        findViewById(R.id.btn_add_bookmark).setOnClickListener(v -> addBookmarkDialog());

        bindSelectionButtons();
        requestStartupPermissions();
        findViewById(android.R.id.content).post(() -> applyOpenPath(getIntent()));

        drawerList.setOnItemClickListener((parent, view, position, id) -> {
            DrawerItem item = drawerAdapter.get(position);
            if (item.type == DrawerItem.TYPE_GROUP) {
                // v5.5：折叠分组：点一下展开/收起，状态记在本地
                prefs.edit().putBoolean(item.group, !item.expanded).apply();
                rebuildDrawer();
                return;
            }
            if (item.type != DrawerItem.TYPE_ITEM) {
                return;
            }
            if (item.path != null) {
                activePane.setPath(new File(item.path));
                drawer.closeDrawer(GravityCompat.START);
            } else if (item.action != DrawerItem.ACTION_NONE) {
                runTool(item.action);
            }
        });
        drawerList.setOnItemLongClickListener((parent, view, position, id) -> {
            DrawerItem item = drawerAdapter.get(position);
            if (item.type == DrawerItem.TYPE_ITEM && item.removable && item.path != null) {
                removeBookmark(item);
                return true;
            }
            return false;
        });

        seedBookmarksIfNeeded();

        setActivePane(leftPane);
        restorePaths();
        rebuildDrawer();
        checkPermissions();
        updateSelectionUi();
        checkCrashReport();
    }

    /** 若上次异常退出，展示崩溃日志并提供清除。 */
    private void checkCrashReport() {
        if (!CrashHandler.hasPending(this)) {
            return;
        }
        CrashHandler.clearPending(this);
        String log = CrashHandler.readLog(this);
        if (log == null) {
            log = "";
        }
        if (log.length() > 4000) {
            log = log.substring(log.length() - 4000);
        }
        final String message = log.trim().isEmpty()
                ? getString(R.string.crash_no_log) : log.trim();
        new AlertDialog.Builder(this)
                .setTitle(R.string.crash_title)
                .setMessage(message)
                .setNeutralButton(R.string.crash_clear, (dialog, which) -> {
                    CrashHandler.clearLog(this);
                    toast(getString(R.string.crash_cleared));
                })
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void bindSelectionButtons() {
        findViewById(R.id.btn_sel_all).setOnClickListener(v -> {
            activePane.selectAll();
            updateSelectionUi();
        });
        findViewById(R.id.btn_sel_invert).setOnClickListener(v -> {
            activePane.invertSelection();
            updateSelectionUi();
        });
        findViewById(R.id.btn_sel_done).setOnClickListener(v -> activePane.exitSelectionMode());

        findViewById(R.id.act_copy).setOnClickListener(v -> doClipboard(false));
        findViewById(R.id.act_cut).setOnClickListener(v -> doClipboard(true));
        findViewById(R.id.act_paste).setOnClickListener(v -> pasteToActivePane());
        findViewById(R.id.act_copy_other).setOnClickListener(v -> transferToOtherPane(false));
        findViewById(R.id.act_move_other).setOnClickListener(v -> transferToOtherPane(true));
        findViewById(R.id.act_delete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.act_zip).setOnClickListener(v -> compressDialog());
        findViewById(R.id.act_unzip).setOnClickListener(v -> extractDialog());
        findViewById(R.id.act_share).setOnClickListener(v -> shareSelected());
        findViewById(R.id.act_rename).setOnClickListener(v -> {
            File file = singleSelected();
            if (file != null) {
                renameDialog(file);
            }
        });
        findViewById(R.id.act_info).setOnClickListener(v -> showProperties(singleSelected()));
    }

    // ==================== 面板与状态 ====================

    private void setActivePane(FilePane pane) {
        if (pane == null) {
            return;
        }
        if (pane == activePane) {
            updateStatus();
            return;
        }
        FilePane other = pane == leftPane ? rightPane : leftPane;
        if (other != null && other.isSelectionMode()) {
            other.exitSelectionMode();
        }
        activePane = pane;
        leftPane.setActive(leftPane == pane);
        rightPane.setActive(rightPane == pane);
        updateSelectionUi();
        updateStatus();
    }

    private void updateSelectionUi() {
        boolean selecting = activePane != null && activePane.isSelectionMode();
        normalBar.setVisibility(selecting ? View.GONE : View.VISIBLE);
        selectBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (!selecting) {
            return;
        }
        int count = activePane.getSelectedCount();
        List<File> selected = activePane.getSelectedFiles();
        selectCount.setText(getString(R.string.selected_count, count));
        ((TextView) findViewById(R.id.btn_sel_all)).setText(
                activePane.isAllSelected() ? R.string.select_none : R.string.select_all);

        setActionEnabled(R.id.act_copy, count > 0);
        setActionEnabled(R.id.act_cut, count > 0);
        setActionEnabled(R.id.act_paste, !Clipboard.isEmpty());
        setActionEnabled(R.id.act_delete, count > 0);
        setActionEnabled(R.id.act_zip, count > 0);
        setActionEnabled(R.id.act_unzip, selected.size() == 1 && isZip(selected.get(0)));
        setActionEnabled(R.id.act_share, count > 0);
        setActionEnabled(R.id.act_rename, count == 1);
        setActionEnabled(R.id.act_info, selected.size() == 1);
        setActionEnabled(R.id.act_copy_other, count > 0);
        setActionEnabled(R.id.act_move_other, count > 0);
    }

    private void setActionEnabled(int id, boolean enabled) {
        View view = findViewById(id);
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.35f);
    }

    private void updateStatus() {
        if (activePane == null) {
            return;
        }
        File current = activePane.getCurrent();
        String path = current != null ? current.getAbsolutePath() : "/";
        // MT 风格：文件夹/文件/储存信息显示在各面板路径栏下方
        String extra = null;
        if (!Clipboard.isEmpty()) {
            extra = getString(R.string.clipboard_status, Clipboard.size(),
                    getString(Clipboard.isCut() ? R.string.clip_cut : R.string.clip_copy));
        }
        if (leftPane != null) {
            leftPane.updatePaneStatus(leftPane == activePane ? extra : null);
        }
        if (rightPane != null) {
            rightPane.updatePaneStatus(rightPane == activePane ? extra : null);
        }
        if (activePane.isSearching()) {
            topTitle.setText(R.string.search);
        } else if (current != null) {
            String display = path;
            if (!display.endsWith("/")) {
                display = display + "/";
            }
            topTitle.setText(display);
        }
    }

    private File singleSelected() {
        List<File> selected = activePane != null ? activePane.getSelectedFiles() : null;
        if (selected == null || selected.size() != 1) {
            toast("请只选中一项");
            return null;
        }
        return selected.get(0);
    }

    private static boolean isZip(File file) {
        return file != null && file.isFile()
                && file.getName().toLowerCase(Locale.US).endsWith(".zip");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String mimeOf(File file) {
        String name = file.getName();
        int index = name.lastIndexOf('.');
        if (index >= 0) {
            String mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(index + 1).toLowerCase(Locale.US));
            if (mime != null) {
                return mime;
            }
        }
        return "*/*";
    }

    // ==================== 路径记忆 ====================

    private void restorePaths() {
        File internal = Environment.getExternalStorageDirectory();
        File root = new File("/");
        // v1.3 一次性迁移：默认布局改为 左=根目录 / 右=内部存储，清除旧记忆
        if (!prefs.getBoolean("layout_v13", false)) {
            prefs.edit().putBoolean("layout_v13", true)
                    .remove("left_path").remove("right_path").apply();
        }
        String homeLeft = homePath(true);
        String homeRight = homePath(false);
        String leftSaved = homeLeft != null ? homeLeft
                : prefs.getString("left_path", root.getAbsolutePath());
        String rightSaved = homeRight != null ? homeRight
                : prefs.getString("right_path", internal.getAbsolutePath());
        File left = new File(leftSaved);
        File right = new File(rightSaved);
        if (!left.isDirectory()) {
            left = root;
        }
        if (!right.isDirectory()) {
            right = internal;
        }
        leftPane.setPath(left);
        rightPane.setPath(right);
    }

    // ==================== 主页 ====================

    /** 返回主页路径，未设置或已失效时返回 null。 */
    private String homePath(boolean left) {
        String value = prefs.getString(left ? "home_left" : "home_right", null);
        if (value != null && value.length() > 0) {
            File dir = new File(value);
            if (dir.isDirectory()) {
                return value;
            }
        }
        return null;
    }

    private void setHome(boolean left, File dir) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        prefs.edit().putString(left ? "home_left" : "home_right", dir.getAbsolutePath()).apply();
        rebuildDrawer();
        toast(getString(R.string.home_set, getString(left ? R.string.home_left : R.string.home_right)
                + "：" + dir.getAbsolutePath()));
    }

    private void clearHome() {
        prefs.edit().remove("home_left").remove("home_right").apply();
        rebuildDrawer();
        toast(getString(R.string.home_cleared));
    }

    private void savePaths() {
        if (leftPane == null || rightPane == null) {
            return;
        }
        File left = leftPane.getCurrent();
        File right = rightPane.getCurrent();
        if (left != null && right != null) {
            prefs.edit()
                    .putString("left_path", left.getAbsolutePath())
                    .putString("right_path", right.getAbsolutePath())
                    .apply();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePaths();
    }

    // ==================== 更多菜单 ====================

    private void showMoreMenu() {
        List<MoreMenu.Item> items = new ArrayList<>();
        items.add(MoreMenu.item(getString(R.string.new_folder), R.drawable.ic_add,
                this::newFolderDialog));
        items.add(MoreMenu.item(getString(R.string.new_file), R.drawable.ic_file,
                this::newFileDialog));
        if (!Clipboard.isEmpty()) {
            items.add(MoreMenu.item(getString(R.string.paste_n, Clipboard.size()),
                    R.drawable.ic_paste, this::pasteToActivePane));
        }
        items.add(MoreMenu.item(getString(R.string.select_all), R.drawable.ic_check, () -> {
            activePane.enterSelectionMode(null);
            activePane.selectAll();
        }));
        items.add(MoreMenu.item(getString(R.string.search), R.drawable.ic_search,
                this::searchDialog));
        items.add(MoreMenu.item(getString(R.string.jump_path), R.drawable.ic_open,
                this::jumpDialog));
        items.add(MoreMenu.item(getString(R.string.menu_set_home_left), R.drawable.ic_home,
                () -> setHome(true, leftPane.getCurrent())));
        items.add(MoreMenu.item(getString(R.string.menu_set_home_right), R.drawable.ic_home,
                () -> setHome(false, rightPane.getCurrent())));
        if (homePath(true) != null || homePath(false) != null) {
            items.add(MoreMenu.item(getString(R.string.menu_clear_home), R.drawable.ic_undo,
                    this::clearHome));
        }
        items.add(MoreMenu.item(getString(R.string.swap_panes), R.drawable.ic_swap,
                this::swapPanes));
        items.add(MoreMenu.item(getString(R.string.sort), R.drawable.ic_sort,
                this::showSortDialog));
        items.add(MoreMenu.item(
                getString(FilePane.showHidden ? R.string.hide_hidden : R.string.show_hidden),
                FilePane.showHidden ? R.drawable.ic_eye_off : R.drawable.ic_eye,
                this::toggleHidden));
        items.add(MoreMenu.item(getString(R.string.refresh), R.drawable.ic_refresh,
                this::refreshAll));
        items.add(MoreMenu.item(getString(R.string.about), R.drawable.ic_info,
                this::showAbout));

        MoreMenu.show(this, getString(R.string.more),
                activePane != null ? activePane.getCurrent().getAbsolutePath() : null, items);
    }

    private void inputDialog(int titleRes, String hint, String defaultValue,
                             final NameCallback callback) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        if (hint != null) {
            input.setHint(hint);
        }
        if (defaultValue != null) {
            input.setText(defaultValue);
            input.setSelection(defaultValue.length());
        }
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(input)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        callback.onName(name);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void newFolderDialog() {
        inputDialog(R.string.new_folder, "文件夹名称", null, name -> {
            File current = activePane.getCurrent();
            if (current == null) {
                return;
            }
            File target = new File(current, name);
            if (target.mkdirs()) {
                refreshBoth();
            } else {
                toast(getString(R.string.failed));
            }
        });
    }

    private void newFileDialog() {
        inputDialog(R.string.new_file, "文件名，如 note.txt", null, name -> {
            File current = activePane.getCurrent();
            if (current == null) {
                return;
            }
            File target = FileOps.uniqueTarget(current, name);
            try {
                if (target.createNewFile()) {
                    refreshBoth();
                } else {
                    toast(getString(R.string.failed));
                }
            } catch (Exception e) {
                toast(getString(R.string.failed) + ": " + e.getMessage());
            }
        });
    }

    private void jumpDialog() {
        File current = activePane.getCurrent();
        inputDialog(R.string.jump_path, "/sdcard",
                current != null ? current.getAbsolutePath() : "/", name -> {
                    File target = new File(name);
                    if (target.isDirectory()) {
                        activePane.setPath(target);
                    } else {
                        toast("目录不存在");
                    }
                });
    }

    private void searchDialog() {
        inputDialog(R.string.search, getString(R.string.search_hint), null, this::startSearch);
    }

    private void startSearch(final String keyword) {
        final File root = activePane.getCurrent();
        if (root == null) {
            return;
        }
        final List<List<File>> holder = new ArrayList<>();
        TaskRunner.run(this, getString(R.string.searching), true,
                progress -> holder.add(FileOps.search(root, keyword, progress)),
                (ok, message) -> {
                    if (ok && !holder.isEmpty()) {
                        activePane.showSearchResult(keyword, holder.get(0), 0);
                        updateStatus();
                        toast(getString(R.string.search_done, holder.get(0).size()));
                    } else if (!ok) {
                        toast(message);
                    }
                });
    }

    private void swapPanes() {
        File left = leftPane.getCurrent();
        File right = rightPane.getCurrent();
        if (left == null || right == null) {
            return;
        }
        leftPane.setPath(right);
        rightPane.setPath(left);
        toast(getString(R.string.swapped));
    }

    private void showSortDialog() {
        final String[] names = {"名称", "大小", "修改日期", "类型"};
        new AlertDialog.Builder(this)
                .setTitle(R.string.sort)
                .setSingleChoiceItems(names, FilePane.sortMode, (dialog, which) -> {
                    FilePane.sortMode = which;
                    refreshBoth();
                    dialog.dismiss();
                })
                .show();
    }

    private void toggleHidden() {
        FilePane.showHidden = !FilePane.showHidden;
        toast(getString(FilePane.showHidden ? R.string.show_hidden : R.string.hide_hidden));
        refreshBoth();
    }

    private void showAbout() {
        View view = getLayoutInflater().inflate(R.layout.dialog_about, null);
        TextView ver = view.findViewById(R.id.about_version_value);
        if (ver != null) {
            ver.setText(versionName());
        }
        TextView pkg = view.findViewById(R.id.about_package_value);
        if (pkg != null) {
            pkg.setText(getPackageName());
        }
        bindQqRow(view, R.id.about_row_author_qq, R.id.about_author_qq_value, false);
        bindQqRow(view, R.id.about_row_group, R.id.about_group_value, true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.about)
                .setView(view)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    /**
     * 关于弹窗里的 QQ / 群号行：点击先尝试拉起 QQ；
     * 没装 QQ（或 scheme 无人处理）就把号码复制到剪贴板再提示。
     */
    private void bindQqRow(View root, final int rowId, final int valueId, final boolean group) {
        View row = root.findViewById(rowId);
        TextView value = root.findViewById(valueId);
        if (row == null || value == null) {
            return;
        }
        final String num = value.getText().toString();
        row.setOnClickListener(v -> {
            String url = group
                    ? "mqqapi://card/show_pslcard?src_type=internal&version=1"
                        + "&uin=" + num + "&card_type=group&source=qrcode"
                    : "mqqwpa://im/chat?chat_type=wpa&version=1&src_type=web&uin=" + num;
            boolean started = false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                started = true;
            } catch (Throwable ignored) {
                // QQ 未安装或 scheme 无人处理，走复制兜底
            }
            if (!started) {
                try {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText(group ? "QQ群" : "QQ", num));
                    }
                } catch (Throwable ignored) {
                }
                toast(getString(R.string.about_no_qq));
            }
        });
    }

    /** 读当前安装包的 versionName，失败时给 "-"（关于弹窗用） */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "-";
        }
    }

    // ==================== 多选批量操作 ====================

    private void doClipboard(boolean cut) {
        final List<File> selected = activePane.getSelectedFiles();
        if (selected.isEmpty()) {
            return;
        }
        Clipboard.set(selected, cut);
        activePane.exitSelectionMode();
        updateStatus();
        toast(getString(cut ? R.string.cut_n : R.string.copy_n, selected.size()));
    }

    private void pasteToActivePane() {
        if (Clipboard.isEmpty()) {
            toast(getString(R.string.clipboard_empty));
            return;
        }
        final File targetDir = activePane != null ? activePane.getCurrent() : null;
        if (targetDir == null || !targetDir.isDirectory()) {
            toast(getString(R.string.no_target));
            return;
        }
        if (!targetDir.canWrite()) {
            toast(getString(R.string.target_readonly, targetDir.getAbsolutePath()));
            return;
        }
        final List<File> sources = Clipboard.getFiles();
        final boolean cut = Clipboard.isCut();
        activePane.exitSelectionMode();
        TaskRunner.run(this, getString(cut ? R.string.moving : R.string.copying), true,
                progress -> {
                    int total = 0;
                    for (File file : sources) {
                        total += Math.max(1, FileOps.countFiles(file));
                    }
                    int[] done = {0};
                    for (File file : sources) {
                        if (progress.isCancelled()) {
                            throw new FileOps.Cancelled();
                        }
                        File target = FileOps.uniqueTarget(targetDir, file.getName());
                        if (target.getAbsolutePath().equals(file.getAbsolutePath())) {
                            done[0] += Math.max(1, FileOps.countFiles(file));
                            continue;
                        }
                        if (cut) {
                            FileOps.move(file, target, progress, done, total);
                        } else {
                            FileOps.copy(file, target, progress, done, total);
                        }
                    }
                },
                (ok, message) -> {
                    if (ok) {
                        Clipboard.clear();
                        toast(getString(R.string.paste_done, sources.size()));
                        refreshAll();
                    } else {
                        toast(message);
                    }
                    updateStatus();
                });
    }

    private void confirmDelete() {
        final List<File> selected = activePane.getSelectedFiles();
        if (selected.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_confirm_multi, selected.size()))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    final List<File> sources = selected;
                    activePane.exitSelectionMode();
                    TaskRunner.run(this, getString(R.string.deleting), true, progress -> {
                        for (File file : sources) {
                            if (progress.isCancelled()) {
                                throw new FileOps.Cancelled();
                            }
                            moveToTrashOrDelete(progress, file);
                        }
                    }, (ok, message) -> {
                        if (ok) {
                            toast(getString(R.string.trash_moved_n, sources.size()));
                            refreshAll();
                        } else {
                            toast(message);
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void compressDialog() {
        final List<File> selected = activePane.getSelectedFiles();
        if (selected.isEmpty()) {
            return;
        }
        String defaultName = selected.size() == 1
                ? selected.get(0).getName() + ".zip" : "archive.zip";
        inputDialog(R.string.act_zip, "压缩包名称", defaultName, name -> {
            final String zipName = name.toLowerCase(Locale.US).endsWith(".zip")
                    ? name : name + ".zip";
            final File dir = activePane.getCurrent();
            if (dir == null) {
                return;
            }
            final File target = FileOps.uniqueTarget(dir, zipName);
            final List<File> sources = selected;
            activePane.exitSelectionMode();
            TaskRunner.run(this, getString(R.string.zipping), true,
                    progress -> FileOps.zip(sources, target, progress),
                    (ok, message) -> {
                        if (ok) {
                            toast(getString(R.string.zip_done, target.getName()));
                            refreshAll();
                        } else {
                            toast(message);
                        }
                    });
        });
    }

    private void extractDialog() {
        final List<File> selected = activePane.getSelectedFiles();
        if (selected.size() != 1 || !isZip(selected.get(0))) {
            toast(getString(R.string.not_zip));
            return;
        }
        final File zipFile = selected.get(0);
        final File dir = activePane.getCurrent();
        if (dir == null) {
            return;
        }
        String base = zipFile.getName();
        base = base.substring(0, base.length() - 4);
        final File dest = FileOps.uniqueTarget(dir, base.isEmpty() ? "unzip" : base);
        activePane.exitSelectionMode();
        TaskRunner.run(this, getString(R.string.unzipping), true,
                progress -> FileOps.unzip(zipFile, dest, progress),
                (ok, message) -> {
                    if (ok) {
                        toast(getString(R.string.unzip_done, dest.getName()));
                        refreshAll();
                    } else {
                        toast(message);
                    }
                });
    }

    private void shareSelected() {
        final List<File> selected = activePane.getSelectedFiles();
        if (selected.isEmpty()) {
            return;
        }
        try {
            Intent intent;
            if (selected.size() == 1) {
                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", selected.get(0));
                intent = new Intent(Intent.ACTION_SEND);
                intent.setType(mimeOf(selected.get(0)));
                intent.putExtra(Intent.EXTRA_STREAM, uri);
            } else {
                ArrayList<Uri> uris = new ArrayList<>();
                for (File file : selected) {
                    uris.add(FileProvider.getUriForFile(this,
                            getPackageName() + ".fileprovider", file));
                }
                intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                intent.setType("*/*");
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.act_share)));
        } catch (Exception e) {
            toast("分享失败: " + e.getMessage());
        }
    }

    private void renameDialog(final File file) {
        inputDialog(R.string.rename, null, file.getName(), name -> {
            File target = new File(file.getParentFile(), name);
            if (target.exists()) {
                toast("同名文件已存在");
                return;
            }
            if (file.renameTo(target)) {
                activePane.exitSelectionMode();
                refreshBoth();
            } else {
                toast(getString(R.string.failed));
            }
        });
    }

    private void showProperties(final File file) {
        if (file == null) {
            return;
        }
        if (!file.isDirectory()) {
            showPropertiesDialog(file, file.length(), 1);
            return;
        }
        final long[] size = {0L};
        final int[] count = {0};
        TaskRunner.run(this, getString(R.string.calculating), true, progress -> {
            size[0] = FileOps.folderSize(file, progress);
            count[0] = FileOps.countFiles(file);
        }, (ok, message) -> {
            if (ok) {
                showPropertiesDialog(file, size[0], count[0]);
            } else {
                toast(message);
            }
        });
    }

    private void showPropertiesDialog(File file, long size, int count) {
        StringBuilder builder = new StringBuilder();
        builder.append("名称: ").append(file.getName()).append('\n');
        builder.append("路径: ").append(file.getAbsolutePath()).append('\n');
        builder.append("类型: ").append(file.isDirectory() ? "文件夹" : mimeOf(file)).append('\n');
        if (file.isDirectory()) {
            builder.append("大小: ").append(Util.formatSize(size)).append('\n');
            builder.append("包含文件: ").append(count).append(" 个\n");
        } else {
            builder.append("大小: ").append(Util.formatSize(file.length())).append('\n');
        }
        builder.append("修改时间: ").append(Util.formatDate(file.lastModified())).append('\n');
        builder.append("可读: ").append(file.canRead())
                .append("    可写: ").append(file.canWrite());
        new AlertDialog.Builder(this)
                .setTitle(R.string.act_info)
                .setMessage(builder.toString())
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    // ==================== 侧滑栏 ====================

    private String storageInfo(String path) {
        try {
            StatFs stat = new StatFs(path);
            long total = stat.getTotalBytes();
            long available = stat.getAvailableBytes();
            long used = Math.max(0L, total - available);
            return Util.formatStorage(used) + "用, " + Util.formatStorage(available) + "可用";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * v5.5：侧滑栏「黑盾加固」折叠分组。
     *
     * <p>目前只有一条「打开黑盾」，后续新功能（加固本机应用、批量加固等）继续往
     * 这个分组里加子项即可；折叠状态按 {@link #DRAWER_GROUP_BLACKSHIELD} 持久化。</p>
     */
    private void addBlackShieldGroup(List<DrawerItem> items) {
        items.add(DrawerItem.header(getString(R.string.blackshield_group_header)));
        boolean expanded = prefs.getBoolean(DRAWER_GROUP_BLACKSHIELD, true);
        items.add(DrawerItem.group(DRAWER_GROUP_BLACKSHIELD, R.drawable.ic_shield,
                getString(R.string.drawer_group_blackshield), expanded));
        if (expanded) {
            boolean installed = BlackShield.isInstalled(this);
            items.add(DrawerItem.sub(R.drawable.ic_shield, getString(R.string.blackshield_open),
                    installed ? getString(R.string.blackshield_open_sub)
                            : getString(R.string.blackshield_not_installed_short),
                    DrawerItem.ACTION_BLACKSHIELD_OPEN));
            // v5.8：按用户要求移除「APK 加密加固」侧滑栏入口（用处不大；「已安装应用」页的
            // 「加固 APK」菜单与 ApkCrypto 本身保留，需要时仍可用）
            // v5.7：免Root脱壳（引擎来自本机「Epic」的 BlackDex）
            boolean dumpReady = EpicDump.isInstalled(this);
            items.add(DrawerItem.sub(R.drawable.ic_zip, getString(R.string.rootless_dump_entry),
                    dumpReady ? getString(R.string.rootless_dump_entry_sub)
                            : getString(R.string.rootless_dump_entry_missing),
                    DrawerItem.ACTION_DUMP));
            // v5.9.44：注入到期时间（自研注入器；仅对未加固 APK 有效，注入后会重新签名）
            items.add(DrawerItem.sub(R.drawable.ic_tool, getString(R.string.expiry_title),
                    getString(R.string.expiry_entry_sub),
                    DrawerItem.ACTION_EXPIRY_INJECT));
        }
    }

    private void rebuildDrawer() {
        List<DrawerItem> items = new ArrayList<>();
        items.add(DrawerItem.header(getString(R.string.local)));
        items.add(DrawerItem.storage(R.drawable.ic_root, getString(R.string.root_dir),
                storageInfo("/"), "/"));
        String internal = Environment.getExternalStorageDirectory().getAbsolutePath();
        items.add(DrawerItem.storage(R.drawable.ic_storage, getString(R.string.internal_storage),
                storageInfo(internal), internal));

        items.add(DrawerItem.header(getString(R.string.home_header)));
        String homeLeft = homePath(true);
        String homeRight = homePath(false);
        items.add(DrawerItem.storage(R.drawable.ic_root, getString(R.string.home_left),
                homeLeft == null ? getString(R.string.home_none) : homeLeft, homeLeft));
        items.add(DrawerItem.storage(R.drawable.ic_storage, getString(R.string.home_right),
                homeRight == null ? getString(R.string.home_none) : homeRight, homeRight));

        // 设置项「在侧拉栏显示书签」关闭时不展示书签分组
        if (Settings.drawerBookmarks(this)) {
            items.add(DrawerItem.header(getString(R.string.bookmarks)));
            for (String entry : loadBookmarks()) {
                int bar = entry.indexOf('|');
                String title = bar >= 0 ? entry.substring(0, bar) : entry;
                String path = bar >= 0 ? entry.substring(bar + 1) : entry;
                items.add(DrawerItem.bookmark(R.drawable.ic_folder, title, path));
            }
        }

        items.add(DrawerItem.header(getString(R.string.tools)));
        items.add(DrawerItem.action(R.drawable.ic_trash, getString(R.string.trash_title),
                getString(R.string.drawer_trash_count, Trash.quickCount()), DrawerItem.ACTION_TRASH));
        items.add(DrawerItem.action(R.drawable.ic_apps, getString(R.string.apps_title),
                getString(R.string.drawer_apps_sub), DrawerItem.ACTION_APPS));
        items.add(DrawerItem.action(R.drawable.ic_edit, getString(R.string.editor_title),
                getString(R.string.drawer_editor_sub), DrawerItem.ACTION_EDITOR));
        items.add(DrawerItem.action(R.drawable.ic_record, getString(R.string.record_title),
                getString(R.string.drawer_record_sub), DrawerItem.ACTION_RECORD));
        items.add(DrawerItem.action(R.drawable.ic_settings, getString(R.string.settings_title),
                Settings.accentName(this, Settings.accent(this)) + " · "
                        + Settings.nightName(this, Settings.nightMode(this)),
                DrawerItem.ACTION_SETTINGS));

        // v5.5：「黑盾加固」折叠分组（后续新功能继续往里加）
        addBlackShieldGroup(items);

        // v5.4：按用户要求移除「加固与安全」分组（该分组仅剩的那个入口用户已不需要）
        markActiveItem(items);
        drawerAdapter.setItems(items);
    }

    /** v4.9：把与当前面板目录最匹配（最长前缀）的条目标记为选中态。 */
    private void markActiveItem(List<DrawerItem> items) {
        if (activePane == null) {
            return;
        }
        String current = activePane.getCurrent().getAbsolutePath();
        int bestIndex = -1;
        int bestLen = -1;
        for (int i = 0; i < items.size(); i++) {
            DrawerItem d = items.get(i);
            if (d.type != DrawerItem.TYPE_ITEM || d.path == null) {
                continue;
            }
            String p = d.path;
            boolean match = current.equals(p)
                    || (p.endsWith("/") ? current.startsWith(p) : current.startsWith(p + "/"));
            if (match && p.length() > bestLen) {
                bestLen = p.length();
                bestIndex = i;
            }
        }
        if (bestIndex >= 0) {
            items.get(bestIndex).active = true;
        }
    }

    private void runTool(int action) {
        drawer.closeDrawer(GravityCompat.START);
        switch (action) {
            case DrawerItem.ACTION_NEW_FOLDER:
                newFolderDialog();
                break;
            case DrawerItem.ACTION_REFRESH:
                refreshAll();
                toast(getString(R.string.refresh));
                break;
            case DrawerItem.ACTION_TRASH:
                startActivity(new Intent(this, TrashActivity.class));
                break;
            case DrawerItem.ACTION_APPS:
                startActivity(new Intent(this, AppListActivity.class));
                break;
            case DrawerItem.ACTION_EDITOR:
                openTextEditor();
                break;
            case DrawerItem.ACTION_RECORD:
                startActivity(new Intent(this, ActivityLogActivity.class));
                break;
            case DrawerItem.ACTION_MORE:
                showMoreMenu();
                break;
            case DrawerItem.ACTION_SETTINGS:
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            case DrawerItem.ACTION_HARDEN_PICK:
                pickApkForHarden();
                break;
            case DrawerItem.ACTION_HARDEN_TOOL:
                HardenTool.openTool(this);
                break;
            case DrawerItem.ACTION_HARDEN_AUTO:
                HardenTool.openAccessibilitySettings(this);
                break;
            case DrawerItem.ACTION_BLACKSHIELD_OPEN:
                // v5.5：黑盾安装与否由 BlackShield 自己判断并引导安装
                BlackShield.open(this);
                break;
            case DrawerItem.ACTION_APK_CRYPTO:
                // v5.6：选一个 APK 交给「免费APK加密」
                pickApkForCrypto();
                break;
            case DrawerItem.ACTION_DUMP:
                // v5.7：免Root脱壳（引擎是本机「Epic」的 BlackDex）
                startActivity(new Intent(this, DumpActivity.class));
                break;
            case DrawerItem.ACTION_EXPIRY_INJECT:
                // v5.9.44：注入到期时间（自研注入器，仅对未加固 APK 有效）
                startActivity(new Intent(this, ExpiryInjectActivity.class));
                break;
            default:
                break;
        }
    }

    /**
     * v5.5：侧滑栏「加固 APK」——列出当前目录 + 下载目录里的 APK，选一个直接交给加固工具。
     */
    /**
     * v5.6：侧滑栏「APK 加密加固」——先把 APK 交给「免费APK加密」，或直接传本机已安装应用的安装包。
     */
    private void pickApkForCrypto() {
        final List<File> found = new ArrayList<>();
        File current = activePane != null ? activePane.getCurrent() : null;
        collectApks(current, found);
        collectApks(new File(Environment.getExternalStorageDirectory(), "Download"), found);

        final List<String> names = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        names.add(getString(R.string.apkcrypto_from_installed));
        actions.add(this::openAppListForCrypto);
        names.add(getString(R.string.apkcrypto_open_tool));
        actions.add(() -> ApkCrypto.open(this));
        for (File file : found) {
            names.add(file.getName() + "    " + Util.formatSize(file.length()) + " · "
                    + (file.getParentFile() == null ? "" : file.getParentFile().getName()));
            final File target = file;
            actions.add(() -> ApkCrypto.openWithApk(this, target));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.apkcrypto_pick_title)
                .setItems(names.toArray(new String[0]),
                        (dialog, which) -> actions.get(which).run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** v5.6：以「提取安装包交给「免费APK加密」」模式打开已安装应用列表。 */
    private void openAppListForCrypto() {
        Intent intent = new Intent(this, AppListActivity.class);
        intent.putExtra(AppListActivity.EXTRA_CRYPTO, true);
        startActivity(intent);
    }

    private void pickApkForHarden() {
        final List<File> found = new ArrayList<>();
        File current = activePane != null ? activePane.getCurrent() : null;
        collectApks(current, found);
        collectApks(new File(Environment.getExternalStorageDirectory(), "Download"), found);
        if (found.isEmpty()) {
            toast(getString(R.string.harden_none_apk));
            return;
        }
        String[] names = new String[found.size()];
        for (int i = 0; i < found.size(); i++) {
            File file = found.get(i);
            names[i] = file.getName() + "    " + Util.formatSize(file.length()) + " · "
                    + file.getParentFile().getName();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.harden_pick_title)
                .setItems(names, (dialog, which) -> HardenTool.open(this, found.get(which)))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void collectApks(File dir, List<File> out) {
        if (dir == null || !dir.isDirectory() || out.size() >= 40) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File file : files) {
            if (out.size() >= 40) {
                return;
            }
            if (file.isFile() && file.getName().toLowerCase().endsWith(".apk") && !out.contains(file)) {
                out.add(file);
            }
        }
    }

    private void seedBookmarksIfNeeded() {
        if (!prefs.getBoolean("seeded", false)) {
            String internal = Environment.getExternalStorageDirectory().getAbsolutePath();
            List<String> defaults = new ArrayList<>();
            File download = new File(internal, "Download");
            defaults.add(download.getName() + "|" + download.getAbsolutePath());
            saveBookmarks(defaults);
            prefs.edit().putBoolean("seeded", true).apply();
        }
    }

    private List<String> loadBookmarks() {
        String raw = prefs.getString("bookmarks", "");
        List<String> result = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                if (!line.trim().isEmpty()) {
                    result.add(line);
                }
            }
        }
        return result;
    }

    private void saveBookmarks(List<String> list) {
        StringBuilder builder = new StringBuilder();
        for (String entry : list) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(entry);
        }
        prefs.edit().putString("bookmarks", builder.toString()).apply();
    }

    private void addBookmarkDialog() {
        File current = activePane != null ? activePane.getCurrent() : null;
        inputDialog(R.string.add_bookmark, getString(R.string.bookmark_path_hint),
                current != null ? current.getAbsolutePath() : null, path -> {
                    File target = new File(path);
                    if (!target.exists()) {
                        toast("路径不存在: " + path);
                        return;
                    }
                    String title = target.getName();
                    if (title.isEmpty()) {
                        title = path;
                    }
                    List<String> list = loadBookmarks();
                    list.add(title + "|" + target.getAbsolutePath());
                    saveBookmarks(list);
                    rebuildDrawer();
                });
    }

    private void removeBookmark(DrawerItem item) {
        List<String> kept = new ArrayList<>();
        for (String entry : loadBookmarks()) {
            int bar = entry.indexOf('|');
            String path = bar >= 0 ? entry.substring(bar + 1) : entry;
            if (!path.equals(item.path)) {
                kept.add(entry);
            }
        }
        saveBookmarks(kept);
        rebuildDrawer();
        toast("已移除书签");
    }

    // ==================== 刷新与权限 ====================

    private void refreshBoth() {
        if (leftPane != null) {
            leftPane.refresh();
        }
        if (rightPane != null) {
            rightPane.refresh();
        }
        updateStatus();
    }

    private void refreshAll() {
        rebuildDrawer();
        refreshBoth();
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showPermissionDialog();
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_READ);
        }
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.permission_needed)
                .setPositiveButton(R.string.permission_btn, (dialog, which) -> {
                    try {
                        Intent intent = new Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, REQ_MANAGE);
                    } catch (Exception e) {
                        try {
                            startActivityForResult(new Intent(
                                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_MANAGE);
                        } catch (Exception ignored) {
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 设置页改了主题色 / 深浅模式时，重建界面让其立即生效
        if (appliedAccent != Settings.accent(this)
                || appliedNight != Settings.nightMode(this)) {
            recreate();
            return;
        }
        if (leftPane != null) {
            refreshAll();
            rebuildDrawer();
        }
    }

    /** v5.7：从别处（免Root脱壳结果页）回到主界面时，直接打开指定目录。 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyOpenPath(intent);
    }

    private void applyOpenPath(Intent intent) {
        if (intent == null) {
            return;
        }
        String path = intent.getStringExtra(EXTRA_OPEN_PATH);
        if (path == null || path.isEmpty()) {
            return;
        }
        intent.removeExtra(EXTRA_OPEN_PATH);
        File dir = new File(path);
        if (dir.isFile()) {
            dir = dir.getParentFile();
        }
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        FilePane pane = activePane != null ? activePane : leftPane;
        if (pane == null) {
            return;
        }
        if (activePane == null) {
            setActivePane(pane);
        }
        pane.setPath(dir);
    }

    // ==================== v1.2：APK 与面板互移 ====================

    private FilePane otherPane() {
        return activePane == leftPane ? rightPane : leftPane;
    }

    private void transferToOtherPane(boolean move) {
        List<File> selected = activePane != null ? activePane.getSelectedFiles() : null;
        if (selected == null || selected.isEmpty()) {
            toast("请先选择文件");
            return;
        }
        transferFiles(activePane, selected, move);
    }

    /** 把文件复制/移动到另一个面板当前目录。 */
    private void transferFiles(final FilePane source, final List<File> selected, final boolean move) {
        final File targetDir = otherPane().getCurrent();
        if (targetDir == null || !targetDir.isDirectory()) {
            toast(getString(R.string.no_target));
            return;
        }
        for (File file : selected) {
            String sourcePath = file.getAbsolutePath();
            String targetPath = targetDir.getAbsolutePath();
            if (targetPath.equals(file.getParent())) {
                toast(getString(R.string.same_dir));
                return;
            }
            if (targetPath.startsWith(sourcePath + "/")) {
                toast("不能移动到自身子目录");
                return;
            }
        }
        if (!targetDir.canWrite()) {
            toast(getString(R.string.target_readonly, targetDir.getAbsolutePath()));
            return;
        }
        if (source != null) {
            source.exitSelectionMode();
        }
        TaskRunner.run(this, getString(move ? R.string.moving : R.string.copying), true, progress -> {
            int total = 0;
            for (File file : selected) {
                total += Math.max(1, FileOps.countFiles(file));
            }
            int[] done = {0};
            for (File file : selected) {
                if (progress.isCancelled()) {
                    throw new FileOps.Cancelled();
                }
                File target = FileOps.uniqueTarget(targetDir, file.getName());
                if (move) {
                    FileOps.move(file, target, progress, done, total);
                } else {
                    FileOps.copy(file, target, progress, done, total);
                }
            }
        }, (ok, message) -> {
            if (ok) {
                toast(getString(move ? R.string.moved_other : R.string.copied_other, selected.size()));
                refreshBoth();
                updateStatus();
            } else {
                toast(message);
            }
        });
    }

    /** 删除操作：普通文件移入回收站，回收站内文件直接彻底删除。 */
    private void moveToTrashOrDelete(FileOps.Progress progress, File file) throws Exception {
        if (Trash.isInTrash(file)) {
            progress.publish("正在删除 " + file.getName(), -1);
            FileOps.deleteRecursively(file);
        } else {
            progress.publish(getString(R.string.trash_moved) + "：" + file.getName(), -1);
            Trash.moveToTrash(file);
        }
    }

    /** 打开内置文本编辑器：优先当前选中文件，否则让用户输入路径。 */
    private void openTextEditor() {
        File current = activePane != null ? activePane.getCurrent() : null;
        File target = null;
        if (activePane != null) {
            for (File file : activePane.getSelectedFiles()) {
                if (file.isFile()) {
                    target = file;
                    break;
                }
            }
        }
        // 没有选中文件时直接新建一个可编辑文本（MT 风格：长按标题可另选文件）
        launchEditor(target);
        if (current == null) {
            toast(getString(R.string.editor_new_file));
        }
    }

    private void launchEditor(File file) {
        Intent intent = new Intent(this, TextEditorActivity.class);
        if (file != null) {
            intent.putExtra(TextEditorActivity.EXTRA_PATH, file.getAbsolutePath());
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast("无法打开编辑器：" + e.getMessage());
        }
    }

    /** 文本类文件的专用菜单。 */
    private void showTextFileMenu(final File file) {
        final String[] items = {
                getString(R.string.open_with_editor),
                getString(R.string.apk_open_with),
                getString(R.string.act_copy_other),
                getString(R.string.act_move_other),
                getString(R.string.copy_path),
                getString(R.string.act_rename),
                getString(R.string.act_info),
                getString(R.string.act_share),
                getString(R.string.delete)};
        new AlertDialog.Builder(this)
                .setTitle(file.getName())
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            launchEditor(file);
                            break;
                        case 1:
                            openWith(file);
                            break;
                        case 2:
                            transferFiles(activePane, Collections.singletonList(file), false);
                            break;
                        case 3:
                            transferFiles(activePane, Collections.singletonList(file), true);
                            break;
                        case 4:
                            copyTextToClipboard(file.getAbsolutePath());
                            break;
                        case 5:
                            renameDialog(file);
                            break;
                        case 6:
                            showProperties(file);
                            break;
                        case 7:
                            shareFiles(Collections.singletonList(file));
                            break;
                        default:
                            confirmDeleteFiles(Collections.singletonList(file));
                            break;
                    }
                })
                .show();
    }

    private void copyTextToClipboard(String text) {
        android.content.ClipboardManager manager =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(android.content.ClipData.newPlainText("text", text));
            toast(getString(R.string.path_copied));
        }
    }

    /** 点击 APK 文件时的专用菜单（MT 风格）。 */
    private void showApkMenu(final File apk) {
        List<MtMenu.Item> items = new ArrayList<>();
        items.add(MtMenu.item(getString(R.string.apk_install), R.drawable.ic_apk,
                () -> installApk(apk)));
        items.add(MtMenu.item(getString(R.string.apk_info), R.drawable.ic_apk,
                () -> showApkInfo(apk)));
        items.add(MtMenu.item(getString(R.string.apk_view), R.drawable.ic_folder,
                () -> viewApkContent(apk)));
        items.add(MtMenu.item(getString(R.string.apk_open_with), R.drawable.ic_file,
                () -> openWith(apk)));
        items.add(MtMenu.item(getString(R.string.act_copy_other), R.drawable.ic_file,
                () -> transferFiles(activePane, Collections.singletonList(apk), false)));
        items.add(MtMenu.item(getString(R.string.act_move_other), R.drawable.ic_file,
                () -> transferFiles(activePane, Collections.singletonList(apk), true)));
        items.add(MtMenu.item(getString(R.string.digest), R.drawable.ic_text,
                () -> showDigest(apk)));
        items.add(MtMenu.item(getString(R.string.act_share), R.drawable.ic_file,
                () -> shareFiles(Collections.singletonList(apk))));
        items.add(MtMenu.item(getString(R.string.act_rename), R.drawable.ic_edit,
                () -> renameDialog(apk)));
        items.add(MtMenu.item(getString(R.string.act_info), R.drawable.ic_file,
                () -> showProperties(apk)));
        items.add(MtMenu.item(getString(R.string.delete), R.drawable.ic_trash,
                () -> confirmDeleteFiles(Collections.singletonList(apk))).danger());
        MtMenu.show(this, apk.getName(), Util.formatSize(apk.length()), items);
    }

    /**
     * 启动时请求权限（设置页可开关）：
     * 优先请求 Root；未获得 Root 且开启了 Shell 权限时，尝试通过 Shizuku 获取。
     */
    private void requestStartupPermissions() {
        final boolean wantRoot = Settings.startupRoot(this);
        final boolean wantShell = Settings.startupShell(this);
        if (!wantRoot && !wantShell) {
            return;
        }
        new Thread(() -> {
            boolean root = wantRoot && Shell.isRootAvailable(MainActivity.this);
            boolean shizuku = Installer.isInstalled(MainActivity.this, Installer.PKG_SHIZUKU);
            if (isFinishing()) {
                return;
            }
            runOnUiThread(() -> {
                if (wantRoot) {
                    toast(getString(root ? R.string.startup_root_ok : R.string.startup_root_denied));
                } else if (wantShell) {
                    toast(getString(shizuku
                            ? R.string.startup_shell_ready : R.string.startup_shell_missing));
                }
            });
        }, "startup-permission").start();
    }

    /** 安装 APK：走统一安装器（验证 → 二次确认 → 优先级通道）。 */
    private void installApk(final File apk) {
        if (IconLoader.archiveInfo(this, apk) == null) {
            toast(getString(R.string.apk_parse_failed));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            toast(getString(R.string.install_hint));
        }
        Installer.install(this, apk);
    }

    /** MT 风格 APK 详情页：图标 + 应用名 + 包名 + 全部安装信息 + 查看/安装/更多。 */
    private void showApkDetail(final File apk) {
        try {
            Intent intent = new Intent(this, AppDetailActivity.class);
            intent.putExtra(AppDetailActivity.EXTRA_PATH, apk.getAbsolutePath());
            startActivity(intent);
        } catch (Exception e) {
            toast("无法打开 APK 信息：" + e.getMessage());
        }
    }

    /** 安全绑定点击事件：控件不存在时忽略，避免空指针崩溃。 */
    private void showPermissionList(final ApkInfoHelper.Info info) {
        if (info.permissions.isEmpty()) {
            toast("该应用未申请权限");
            return;
        }
        final String[] items = new String[info.permissions.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = info.permissions.get(i);
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.apk_perm) + "（" + items.length + "）")
                .setItems(items, null)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    /** 展示 APK 包信息（纯文本，备用）。 */
    private void showApkInfo(final File apk) {
        showApkDetail(apk);
    }

    /** 查看内容：把 APK 当作 zip 用内置压缩包浏览器打开（不解压、不跳主页）。 */
    private void viewApkContent(final File apk) {
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

    /** 用内置图片查看器显示原图（MT 风格）。 */
    private void openImageViewer(final File file) {
        if (file == null || !file.exists()) {
            toast(getString(R.string.failed));
            return;
        }
        try {
            Intent intent = new Intent(this, ImageViewerActivity.class);
            intent.putExtra(ImageViewerActivity.EXTRA_PATH, file.getAbsolutePath());
            File parent = file.getParentFile();
            if (parent != null) {
                intent.putExtra(ImageViewerActivity.EXTRA_DIR, parent.getAbsolutePath());
            }
            startActivity(intent);
        } catch (Exception e) {
            toast(getString(R.string.failed) + ": " + e.getMessage());
        }
    }

    private void openWith(final File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeOf(file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.apk_open_with)));
        } catch (Exception e) {
            toast(getString(R.string.failed) + ": " + e.getMessage());
        }
    }

    private void showDigest(final File file) {
        if (file == null) {
            return;
        }
        final String[] result = new String[3];
        TaskRunner.run(this, getString(R.string.digesting), true, progress -> {
            progress.publish("MD5…", -1);
            result[0] = Util.digest(file, "MD5");
            progress.publish("SHA1…", -1);
            result[1] = Util.digest(file, "SHA-1");
            progress.publish("SHA256…", -1);
            result[2] = Util.digest(file, "SHA-256");
        }, (ok, message) -> {
            if (!ok) {
                toast(message);
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.digest)
                    .setMessage("文件: " + file.getName()
                            + "\n\nMD5:\n" + result[0]
                            + "\n\nSHA-1:\n" + result[1]
                            + "\n\nSHA-256:\n" + result[2])
                    .setPositiveButton(R.string.ok, null)
                    .show();
        });
    }

    private void confirmDeleteFiles(final List<File> files) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_confirm_multi, files.size()))
                .setPositiveButton(R.string.delete, (dialog, which) ->
                        TaskRunner.run(this, getString(R.string.deleting), true, progress -> {
                            for (File file : files) {
                                if (progress.isCancelled()) {
                                    throw new FileOps.Cancelled();
                                }
                                moveToTrashOrDelete(progress, file);
                            }
                        }, (ok, message) -> {
                            if (ok) {
                                toast(getString(R.string.trash_moved_n, files.size()));
                                refreshAll();
                            } else {
                                toast(message);
                            }
                        }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void shareFiles(List<File> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        try {
            Intent intent;
            if (files.size() == 1) {
                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", files.get(0));
                intent = new Intent(Intent.ACTION_SEND);
                intent.setType(mimeOf(files.get(0)));
                intent.putExtra(Intent.EXTRA_STREAM, uri);
            } else {
                ArrayList<Uri> uris = new ArrayList<>();
                for (File file : files) {
                    uris.add(FileProvider.getUriForFile(this,
                            getPackageName() + ".fileprovider", file));
                }
                intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                intent.setType("*/*");
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.act_share)));
        } catch (Exception e) {
            toast(getString(R.string.failed) + ": " + e.getMessage());
        }
    }

    @Override
    public void onBackPressed() {
        // 1) 先退出多选模式
        if (activePane != null && activePane.isSelectionMode()) {
            activePane.exitSelectionMode();
            return;
        }
        // 2) 关闭侧滑抽屉
        if (drawer != null && drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
            return;
        }
        // 3) 搜索状态：退出搜索回到原目录
        if (activePane != null && activePane.isSearching()) {
            activePane.goUp();
            return;
        }
        // 4) 列表没在顶部：先回到顶部
        if (activePane != null && activePane.scrollListToTop()) {
            return;
        }
        // 5) 返回上一级目录
        File current = activePane != null ? activePane.getCurrent() : null;
        if (current != null && current.getParentFile() != null) {
            activePane.goUp();
            return;
        }
        // 6) 已在根目录：退出应用
        super.onBackPressed();
    }
}
