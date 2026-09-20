package com.mtstyle.fm;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 一个独立的文件浏览面板（双列布局中的一列）。 */
public class FilePane {

    public static final int SORT_NAME = 0;
    public static final int SORT_SIZE = 1;
    public static final int SORT_DATE = 2;
    public static final int SORT_TYPE = 3;

    /** 面板底部工具按钮动作 */
    public static final int TOOL_UP = 0;
    public static final int TOOL_HOME = 1;
    public static final int TOOL_NEW = 2;
    public static final int TOOL_SORT = 3;
    public static final int TOOL_MORE = 4;

    /** 两个面板共享的显示配置。 */
    public static boolean showHidden = false;
    public static int sortMode = SORT_NAME;

    public interface Listener {
        void onPaneChanged(FilePane pane);

        void onPaneActivated(FilePane pane);

        void onSelectionChanged(FilePane pane);

        /** 点击 APK 等需要专用菜单的文件。 */
        void onFileMenu(FilePane pane, File file);

        /** 点击路径栏（MT 风格：弹出跳转路径）。 */
        void onPathBarClick(FilePane pane);

        /** 点击面板底部工具按钮，action 取 FilePane.TOOL_* 。 */
        void onToolAction(FilePane pane, int action);
    }

    private final Activity activity;
    private final TextView pathBar;
    private final TextView statusView;
    private final TextView emptyView;
    private final FileAdapter adapter;
    private final ImageButton[] toolButtons = new ImageButton[5];
    private final Listener listener;

    private File current;
    private ListView listView;
    private MtPullRefresh swipe;
    private boolean active;
    private int folderCount;
    private int fileCount;
    private String searchKeyword;

    public FilePane(Activity activity, View root, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.pathBar = root.findViewById(R.id.path_bar);
        this.statusView = root.findViewById(R.id.pane_status);
        this.emptyView = root.findViewById(R.id.pane_empty);
        listView = root.findViewById(R.id.file_list);
        this.adapter = new FileAdapter(activity);
        listView.setAdapter(adapter);
        this.swipe = root.findViewById(R.id.pane_swipe);
        if (this.swipe != null) {
            // 仅当列表已滑到顶部时允许下拉刷新；下拉提示为 MT 风格细条。
            this.swipe.setScrollChecker(() -> listView.canScrollVertically(-1));
            this.swipe.setOnRefreshListener(this::refresh);
        }

        pathBar.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPathBarClick(this);
            }
        });
        pathBar.setOnLongClickListener(v -> {
            goUp();
            return true;
        });

        listView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN && !active) {
                listener.onPaneActivated(this);
            }
            return false;
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            File file = adapter.getItems().get(position);
            if (adapter.isSelectionMode()) {
                adapter.toggle(file);
                listener.onSelectionChanged(this);
                return;
            }
            if (file.isDirectory()) {
                setPath(file);
            } else if (openWithBuiltin(file)) {
                // 内置查看器优先：apk/dex/arsc/二进制 xml，能自己打开就绝不甩给外部应用
            } else if (IconLoader.isArchiveOpenable(file)) {
                openArchive(file);
            } else if (IconLoader.isArchiveOther(file)) {
                Toast.makeText(activity, R.string.archive_unsupported, Toast.LENGTH_SHORT).show();
                openWithExternal(file);
            } else if (IconLoader.isImage(file)) {
                // MT 风格：点击图片直接用内置查看器显示原图
                openImageViewer(file);
            } else if (IconLoader.isTextLike(file)) {
                openInEditor(file);
            } else {
                openFile(file);
            }
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            File file = adapter.getItems().get(position);
            if (!adapter.isSelectionMode()) {
                listener.onPaneActivated(this);
                adapter.setSelectionMode(true);
            }
            adapter.toggle(file);
            listener.onSelectionChanged(this);
            return true;
        });

        // 底部操作栏（MT 风格：上级 / 主页 / 新建 / 排序 / 更多）
        toolButtons[TOOL_UP] = root.findViewById(R.id.tool_up);
        toolButtons[TOOL_HOME] = root.findViewById(R.id.tool_home);
        toolButtons[TOOL_NEW] = root.findViewById(R.id.tool_new);
        toolButtons[TOOL_SORT] = root.findViewById(R.id.tool_sort);
        toolButtons[TOOL_MORE] = root.findViewById(R.id.tool_more);
        for (int i = 0; i < toolButtons.length; i++) {
            final int action = i;
            if (toolButtons[i] == null) {
                continue;
            }
            toolButtons[i].setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPaneActivated(this);
                    listener.onToolAction(this, action);
                }
            });
        }
    }

    /** 底部工具按钮着色：活动面板用主题色，非活动面板用次要色。 */
    private void tintToolButtons(int color) {
        for (ImageButton button : toolButtons) {
            if (button != null) {
                button.setColorFilter(color);
            }
        }
    }

    // ==================== 状态 ====================

    public File getCurrent() {
        return current;
    }

    public int getFolderCount() {
        return folderCount;
    }

    public int getFileCount() {
        return fileCount;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isSearching() {
        return searchKeyword != null;
    }

    /** 列表是否已滚动到顶部。 */
    public boolean isListAtTop() {
        return listView == null || listView.getFirstVisiblePosition() <= 0;
    }

    /** 把列表滚回顶部，返回是否真的发生了滚动。 */
    public boolean scrollListToTop() {
        if (listView == null) {
            return false;
        }
        if (listView.getFirstVisiblePosition() > 0) {
            listView.setSelection(0);
            return true;
        }
        return false;
    }

    public void setActive(boolean value) {
        this.active = value;
        pathBar.setBackgroundColor(ContextCompat.getColor(activity,
                value ? R.color.toolbar : R.color.surface));
        pathBar.setTextColor(value ? Util.accentColor(activity)
                : ContextCompat.getColor(activity, R.color.text_secondary));
        tintToolButtons(value ? Util.accentColor(activity)
                : ContextCompat.getColor(activity, R.color.text_secondary));
    }

    // ==================== 多选 ====================

    public boolean isSelectionMode() {
        return adapter.isSelectionMode();
    }

    public int getSelectedCount() {
        return adapter.getSelectedCount();
    }

    public boolean isAllSelected() {
        return adapter.isAllSelected();
    }

    public List<File> getSelectedFiles() {
        return adapter.getSelected();
    }

    public void enterSelectionMode(File seed) {
        adapter.setSelectionMode(true);
        adapter.clearSelection();
        if (seed != null) {
            adapter.toggle(seed);
        }
        listener.onSelectionChanged(this);
    }

    public void exitSelectionMode() {
        if (!adapter.isSelectionMode()) {
            return;
        }
        adapter.setSelectionMode(false);
        listener.onSelectionChanged(this);
    }

    public void selectAll() {
        if (adapter.isAllSelected()) {
            adapter.clearSelection();
        } else {
            adapter.selectAll();
        }
        listener.onSelectionChanged(this);
    }

    public void invertSelection() {
        adapter.invertSelection();
        listener.onSelectionChanged(this);
    }

    // ==================== 浏览 ====================

    public void setPath(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            Toast.makeText(activity, "无法访问目录", Toast.LENGTH_SHORT).show();
            return;
        }
        searchKeyword = null;
        this.current = dir;
        adapter.clearSelection();
        adapter.setSelectionMode(false);
        reload();
        listener.onSelectionChanged(this);
    }

    public void refresh() {
        if (current != null) {
            reload();
        }
    }

    public void goUp() {
        if (searchKeyword != null) {
            searchKeyword = null;
            reload();
            return;
        }
        if (current == null) {
            return;
        }
        File parent = current.getParentFile();
        if (parent != null) {
            setPath(parent);
        } else {
            Toast.makeText(activity, "已经是根目录", Toast.LENGTH_SHORT).show();
        }
    }

    public void showSearchResult(String keyword, List<File> results, int scanned) {
        this.searchKeyword = keyword;
        List<File> list = new ArrayList<>(results);
        adapter.setItems(list);
        folderCount = 0;
        fileCount = 0;
        for (File f : list) {
            if (f.isDirectory()) {
                folderCount++;
            } else {
                fileCount++;
            }
        }
        updatePathBar();
        updatePaneStatus(null);
        emptyView.setText("未找到匹配项");
        emptyView.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        listener.onPaneChanged(this);
    }

    private Comparator<File> comparator() {
        return (a, b) -> {
            boolean dirA = a.isDirectory();
            boolean dirB = b.isDirectory();
            if (dirA != dirB) {
                return dirA ? -1 : 1;
            }
            switch (sortMode) {
                case SORT_SIZE:
                    return Long.compare(b.length(), a.length());
                case SORT_DATE:
                    return Long.compare(b.lastModified(), a.lastModified());
                case SORT_TYPE: {
                    int c = ext(a).compareToIgnoreCase(ext(b));
                    if (c != 0) {
                        return c;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                }
                default:
                    return a.getName().compareToIgnoreCase(b.getName());
            }
        };
    }

    private static String ext(File file) {
        String name = file.getName();
        int index = name.lastIndexOf('.');
        return index > 0 ? name.substring(index + 1) : "";
    }

    /** MT 风格：路径栏下方显示「文件夹:N 文件:N 储存:已用/总量」，extra 用于追加剪贴板状态。 */
    public void updatePaneStatus(String extra) {
        if (statusView == null) {
            return;
        }
        String used = "-";
        String total = "-";
        try {
            String path = current != null ? current.getAbsolutePath() : "/";
            android.os.StatFs stat = new android.os.StatFs(path);
            long totalBytes = stat.getTotalBytes();
            total = Util.formatStorage(totalBytes);
            used = Util.formatStorage(Math.max(0L, totalBytes - stat.getAvailableBytes()));
        } catch (Exception ignored) {
        }
        String text = activity.getString(R.string.file_count, folderCount, fileCount, used, total);
        if (extra != null && extra.length() > 0) {
            text = text + "   |   " + extra;
        }
        statusView.setText(text);
    }

    /** MT 风格：点击文本文件直接用内置编辑器打开（可编辑/查看）。 */
    private void openInEditor(File file) {
        try {
            android.content.Intent intent = new android.content.Intent(activity,
                    TextEditorActivity.class);
            intent.putExtra(TextEditorActivity.EXTRA_PATH, file.getAbsolutePath());
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "无法打开编辑器：" + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** 用内置压缩包浏览器打开 zip/jar。 */
    private void openArchive(File file) {
        try {
            android.content.Intent intent = new android.content.Intent(activity,
                    ArchiveActivity.class);
            intent.putExtra(ArchiveActivity.EXTRA_PATH, file.getAbsolutePath());
            if (current != null) {
                intent.putExtra(ArchiveActivity.EXTRA_DIR, current.getAbsolutePath());
            }
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "无法打开压缩包：" + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** MT 风格：用内置查看器显示原图（支持同目录左右滑动切换）。 */
    private void openImageViewer(File file) {
        try {
            android.content.Intent intent = new android.content.Intent(activity,
                    ImageViewerActivity.class);
            intent.putExtra(ImageViewerActivity.EXTRA_PATH, file.getAbsolutePath());
            if (current != null) {
                intent.putExtra(ImageViewerActivity.EXTRA_DIR, current.getAbsolutePath());
            }
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "无法查看图片：" + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** rar/7z 等内置浏览器不支持的压缩包交给外部应用。 */
    private void openWithExternal(File file) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", file);
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/octet-stream");
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(android.content.Intent.createChooser(intent,
                    activity.getString(R.string.apk_open_with)));
        } catch (Exception ignored) {
        }
    }

    private void updatePathBar() {
        if (searchKeyword != null) {
            pathBar.setText("搜索「" + searchKeyword + "」 " + adapter.getCount() + " 项");
        } else if (current != null) {
            String path = current.getAbsolutePath();
            if (!path.endsWith("/")) {
                path = path + "/";
            }
            pathBar.setText(path);
        }
    }

    private void reload() {
        List<File> list = new ArrayList<>();
        folderCount = 0;
        fileCount = 0;
        if (current != null) {
            File[] files = current.listFiles();
            if (files != null) {
                Arrays.sort(files, comparator());
                for (File file : files) {
                    String name = file.getName();
                    if (!showHidden && name.startsWith(".")) {
                        continue;
                    }
                    list.add(file);
                    if (file.isDirectory()) {
                        folderCount++;
                    } else {
                        fileCount++;
                    }
                }
            }
        }
        adapter.setItems(list);
        updatePathBar();
        updatePaneStatus(null);
        emptyView.setText("（空文件夹）");
        if (emptyView != null) {
            emptyView.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        }
        listener.onPaneChanged(this);
    }

    // ==================== 打开文件 ====================

    private String getMime(File file) {
        String name = file.getName();
        int index = name.lastIndexOf('.');
        if (index >= 0) {
            String extension = name.substring(index + 1).toLowerCase(Locale.US);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) {
                return mime;
            }
        }
        return "*/*";
    }

    /** 没有可用的外部应用时，提供内置文本编辑器入口。 */
    private void openWithEditorFallback(final File file) {
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(file.getName())
                .setMessage("没有可以打开该文件的应用，是否用内置文本编辑器打开？")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(com.mtstyle.fm.R.string.open_with_editor, (dialog, which) -> {
                    Intent intent = new Intent(activity, TextEditorActivity.class);
                    intent.putExtra(TextEditorActivity.EXTRA_PATH, file.getAbsolutePath());
                    try {
                        activity.startActivity(intent);
                    } catch (Exception ignored) {
                    }
                })
                .show();
    }

    /** 判断系统里是否有应用可以打开该文件。 */
    private boolean canOpenExternally(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, getMime(file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return activity.getPackageManager().resolveActivity(intent, 0) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 内置优先：能识别的类型一律用本应用自己的查看器打开，返回 true 表示已经处理掉了。
     *
     * <p>以前这些类型会一路落到外部的 ACTION_VIEW，而 dex/arsc/二进制 xml 在系统里根本没有
     * 应用能接，用户看到的是「找不到应用 → 要不要用文本编辑器打开？」这种绕圈提示；
     * 其实项目里早就有对应的内置查看器（DexActivity / ArscActivity / ApkAnalysisActivity …），
     * 只是没接到分发链上。</p>
     */
    private boolean openWithBuiltin(File file) {
        // 实际分发逻辑在 BuiltinOpen，压缩包条目预览走的是同一套
        return BuiltinOpen.open(activity, file);
    }

    /** 最后兜底：交给外部应用。 */
    private void openFile(File file) {
        openWithExternal(file);
    }
}
