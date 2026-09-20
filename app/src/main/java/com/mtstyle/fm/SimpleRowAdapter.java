package com.mtstyle.fm;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 通用两行列表适配器（回收站 / 已安装应用 / Activity 记录共用）。 */
public class SimpleRowAdapter extends BaseAdapter {

    public static class Row {
        public int iconRes;
        public Drawable icon;
        public String title = "";
        public String subtitle = "";
        public String trailing = "";
        /** 分组标题行：只显示标题文字。 */
        public boolean header;
        /** 开关型设置行：右侧显示 Switch（点击整行切换）。 */
        public boolean switchRow;
        public boolean switchOn;
        /** 动态图标键（如压缩包内条目名），由 {@link IconProvider} 决定实际图。 */
        public String iconKey;
    }

    /** 异步加载的图标来源：getView 时按 key 现取，取不到再退回 icon / iconRes。 */
    public interface IconProvider {
        Drawable iconFor(String key);
    }

    private final LayoutInflater inflater;
    private final List<Row> rows = new ArrayList<>();
    private IconProvider iconProvider;

    public void setIconProvider(IconProvider provider) {
        iconProvider = provider;
    }

    public SimpleRowAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void setRows(List<Row> data) {
        rows.clear();
        if (data != null) {
            rows.addAll(data);
        }
        notifyDataSetChanged();
    }

    public Row getRow(int position) {
        return rows.get(position);
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Object getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_app, parent, false);
        }
        Row row = rows.get(position);
        if (row.header) {
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            view.setMinimumHeight(Math.round(34
                    * view.getResources().getDisplayMetrics().density));
            ImageView headerIcon = view.findViewById(R.id.item_icon);
            headerIcon.setVisibility(View.GONE);
            TextView headerTitle = view.findViewById(R.id.item_title);
            headerTitle.setText(row.title);
            headerTitle.setTextColor(view.getResources().getColor(R.color.text_secondary));
            headerTitle.setTextSize(12);
            headerTitle.setPadding(0, Math.round(10
                    * view.getResources().getDisplayMetrics().density), 0, 0);
            view.findViewById(R.id.item_subtitle).setVisibility(View.GONE);
            view.findViewById(R.id.item_trailing).setVisibility(View.GONE);
            view.findViewById(R.id.item_switch).setVisibility(View.GONE);
            return view;
        }
        float density = view.getResources().getDisplayMetrics().density;
        view.setMinimumHeight(Math.round(62 * density));
        view.findViewById(R.id.item_icon).setVisibility(View.VISIBLE);
        ImageView iconView = view.findViewById(R.id.item_icon);
        TextView titleView = view.findViewById(R.id.item_title);
        TextView subtitleView = view.findViewById(R.id.item_subtitle);
        TextView trailingView = view.findViewById(R.id.item_trailing);
        trailingView.setVisibility(View.VISIBLE);
        Switch switchView = view.findViewById(R.id.item_switch);

        Drawable dynamic = null;
        if (row.iconKey != null && iconProvider != null) {
            dynamic = iconProvider.iconFor(row.iconKey);
        }
        if (dynamic != null) {
            iconView.setImageDrawable(dynamic);
        } else if (row.icon != null) {
            iconView.setImageDrawable(row.icon);
        } else if (row.iconRes != 0) {
            iconView.setImageResource(row.iconRes);
        } else {
            iconView.setImageResource(R.drawable.ic_file);
        }
        titleView.setText(row.title);
        titleView.setTextColor(view.getResources().getColor(R.color.text_primary));
        titleView.setTextSize(14);
        titleView.setPadding(0, 0, 0, 0);
        subtitleView.setVisibility(row.subtitle == null || row.subtitle.isEmpty()
                ? View.GONE : View.VISIBLE);
        subtitleView.setText(row.subtitle);
        trailingView.setText(row.trailing);
        trailingView.setVisibility(row.trailing == null || row.trailing.isEmpty()
                ? View.GONE : View.VISIBLE);
        if (row.switchRow) {
            trailingView.setVisibility(View.GONE);
            switchView.setVisibility(View.VISIBLE);
            switchView.setChecked(row.switchOn);
        } else {
            switchView.setVisibility(View.GONE);
        }
        return view;
    }
}
