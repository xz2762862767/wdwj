package com.mtstyle.fm;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 侧滑栏适配器，支持分组标题与条目两种类型。 */
public class DrawerAdapter extends BaseAdapter {

    private static final String TAG_HEADER = "header";
    private static final String TAG_ITEM = "item";
    private static final String TAG_GROUP = "group";

    private final LayoutInflater inflater;
    private final List<DrawerItem> items = new ArrayList<>();

    public DrawerAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void setItems(List<DrawerItem> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        notifyDataSetChanged();
    }

    public DrawerItem get(int position) {
        return items.get(position);
    }

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
    public int getViewTypeCount() {
        return 3;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        int type = items.get(position).type;
        return type == DrawerItem.TYPE_ITEM || type == DrawerItem.TYPE_GROUP;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        DrawerItem item = items.get(position);
        boolean isHeader = item.type == DrawerItem.TYPE_HEADER;
        boolean isGroup = item.type == DrawerItem.TYPE_GROUP;
        String wantTag = isHeader ? TAG_HEADER : (isGroup ? TAG_GROUP : TAG_ITEM);

        if (convertView == null || !wantTag.equals(convertView.getTag())) {
            int layout = isHeader ? R.layout.item_drawer_header : R.layout.item_drawer;
            convertView = inflater.inflate(layout, parent, false);
            convertView.setTag(wantTag);
        }

        if (isHeader) {
            ((TextView) convertView).setText(item.title);
            return convertView;
        }

        ImageView icon = convertView.findViewById(R.id.icon);
        TextView title = convertView.findViewById(R.id.title);
        TextView subtitle = convertView.findViewById(R.id.subtitle);
        TextView arrow = convertView.findViewById(R.id.arrow);
        Context context = convertView.getContext();

        // v5.5：分组子项缩进，形成层级
        int padLeft = dp(context, item.child ? 44 : 20);
        int padRight = dp(context, 16);
        convertView.setPadding(padLeft, convertView.getPaddingTop(), padRight,
                convertView.getPaddingBottom());

        if (item.iconRes != 0) {
            icon.setVisibility(View.VISIBLE);
            icon.setImageResource(item.iconRes);
            icon.setColorFilter(Util.accentColor(context),
                    android.graphics.PorterDuff.Mode.SRC_IN);
        } else {
            icon.setVisibility(View.GONE);
        }

        // v4.9：高亮当前所在位置（当前面板目录最短前缀匹配的条目）
        if (item.active) {
            convertView.setBackgroundResource(R.drawable.drawer_item_bg_active);
            title.setTextColor(Util.accentColor(context));
        } else {
            convertView.setBackgroundResource(R.drawable.drawer_item_bg);
            title.setTextColor(context.getResources().getColor(R.color.text_primary));
        }

        title.setText(item.title);
        title.setTextSize(isGroup ? 15f : (item.child ? 14.5f : 15f));
        title.setTypeface(null, (isGroup || item.active) ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);

        if (item.subtitle != null && !item.subtitle.isEmpty()) {
            subtitle.setText(item.subtitle);
            subtitle.setVisibility(View.VISIBLE);
        } else {
            subtitle.setVisibility(View.GONE);
        }

        if (arrow != null) {
            if (isGroup) {
                arrow.setVisibility(View.VISIBLE);
                arrow.setText(item.expanded ? "\u25be" : "\u25b8");
            } else {
                arrow.setVisibility(View.GONE);
            }
        }
        return convertView;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
