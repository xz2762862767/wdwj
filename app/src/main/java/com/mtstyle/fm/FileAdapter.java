package com.mtstyle.fm;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 单个文件面板的列表适配器，支持普通浏览与多选两种状态，并按设置渲染。 */
public class FileAdapter extends BaseAdapter {

    private final Context context;
    private final LayoutInflater inflater;
    private final List<File> items = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selectionMode;

    // 列表外观设置缓存
    private int prefsVersion = -1;
    private int listSize = Settings.SIZE_MEDIUM;
    private int nameLines = 2;
    private String datePattern = Settings.DEFAULT_DATE_FORMAT;
    private boolean showPermissions;
    private boolean nonStoragePermSize = true;

    public FileAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        loadPrefs();
    }

    private void loadPrefs() {
        prefsVersion = Settings.version();
        listSize = Settings.listSize(context);
        nameLines = Math.max(1, Settings.nameLines(context));
        datePattern = Settings.effectiveDatePattern(context);
        nonStoragePermSize = Settings.nonStoragePermSize(context);
        showPermissions = !Settings.hidePermissions(context);
    }

    public void setItems(List<File> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        // 清理已不存在的选中项
        Set<String> alive = new LinkedHashSet<>();
        for (File f : items) {
            String key = f.getAbsolutePath();
            if (selected.contains(key)) {
                alive.add(key);
            }
        }
        selected.clear();
        selected.addAll(alive);
        notifyDataSetChanged();
    }

    public List<File> getItems() {
        return items;
    }

    // ==================== 多选 ====================

    public void setSelectionMode(boolean on) {
        if (selectionMode == on) {
            return;
        }
        selectionMode = on;
        if (!on) {
            selected.clear();
        }
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public boolean isSelected(File file) {
        return selected.contains(file.getAbsolutePath());
    }

    public void toggle(File file) {
        String key = file.getAbsolutePath();
        if (!selected.remove(key)) {
            selected.add(key);
        }
        notifyDataSetChanged();
    }

    public void selectAll() {
        for (File f : items) {
            selected.add(f.getAbsolutePath());
        }
        notifyDataSetChanged();
    }

    public void clearSelection() {
        selected.clear();
        notifyDataSetChanged();
    }

    public void invertSelection() {
        for (File f : items) {
            String key = f.getAbsolutePath();
            if (!selected.remove(key)) {
                selected.add(key);
            }
        }
        notifyDataSetChanged();
    }

    public int getSelectedCount() {
        return selected.size();
    }

    public boolean isAllSelected() {
        return !items.isEmpty() && selected.size() >= items.size();
    }

    /** 按列表顺序返回选中项。 */
    public List<File> getSelected() {
        List<File> result = new ArrayList<>();
        for (File f : items) {
            if (selected.contains(f.getAbsolutePath())) {
                result.add(f);
            }
        }
        return result;
    }

    // ==================== 渲染 ====================

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (prefsVersion != Settings.version()) {
            loadPrefs();
        }
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_file, parent, false);
        }
        File file = items.get(position);
        ImageView icon = view.findViewById(R.id.icon);
        TextView name = view.findViewById(R.id.name);
        TextView size = view.findViewById(R.id.size);
        TextView date = view.findViewById(R.id.date);

        float density = context.getResources().getDisplayMetrics().density;
        int iconDp;
        int nameSp;
        int rowDp;
        switch (listSize) {
            case Settings.SIZE_SMALL:
                iconDp = 26;
                nameSp = 12;
                rowDp = 46;
                break;
            case Settings.SIZE_LARGE:
                iconDp = 34;
                nameSp = 15;
                rowDp = 64;
                break;
            default:
                iconDp = 30;
                nameSp = 13;
                rowDp = 54;
                break;
        }
        ViewGroup.LayoutParams iconParams = icon.getLayoutParams();
        if (iconParams != null && iconParams.width != Math.round(iconDp * density)) {
            iconParams.width = Math.round(iconDp * density);
            iconParams.height = Math.round(iconDp * density);
            icon.setLayoutParams(iconParams);
        }
        view.setMinimumHeight(Math.round(rowDp * density));
        name.setTextSize(nameSp);
        name.setMaxLines(nameLines);
        name.setSingleLine(nameLines == 1);
        name.setEllipsize(nameLines == 1
                ? android.text.TextUtils.TruncateAt.MIDDLE
                : android.text.TextUtils.TruncateAt.END);

        boolean isDir = file.isDirectory();
        boolean checked = selectionMode && isSelected(file);

        if (selectionMode) {
            icon.setTag(null);
            icon.setImageResource(checked ? R.drawable.ic_check : R.drawable.ic_uncheck);
        } else {
            IconLoader.bind(icon, file);
        }

        View selBar = view.findViewById(R.id.sel_bar);
        if (selBar != null) {
            selBar.setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
        }
        if (checked) {
            view.setBackgroundColor(Util.accentDimColor(context));
            name.setTextColor(Util.accentColor(context));
        } else {
            view.setBackgroundResource(R.drawable.list_selector);
            name.setTextColor(context.getResources().getColor(R.color.text_primary));
        }

        String fileName = file.getName();
        name.setText(fileName.isEmpty() ? file.getPath() : fileName);

        // MT 风格：第二行 = 时间 + 大小（可按设置附加权限串）
        date.setText(Util.formatDate(file.lastModified(), datePattern));

        String sizeText = "";
        if (!isDir) {
            long length = file.length();
            sizeText = length > 0 ? Util.formatSize(length) : "";
        }
        boolean withPermissions = showPermissions
                && (nonStoragePermSize ? !Settings.isUnderStorage(file.getAbsolutePath()) : true);
        if (withPermissions) {
            String perms = Settings.permissions(file);
            sizeText = sizeText.isEmpty() ? perms : sizeText + "  " + perms;
        }
        size.setText(sizeText);
        return view;
    }
}
