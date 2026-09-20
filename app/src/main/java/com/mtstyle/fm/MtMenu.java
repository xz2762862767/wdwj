package com.mtstyle.fm;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * MT 风格弹出菜单：方角卡片 + 头部标题 + 带图标的操作行 + 底部取消。
 * 用于长按应用/文件的上下文操作，替代系统 AlertDialog 列表。
 */
public final class MtMenu {

    /** 单个菜单项：图标 + 文字 + 点击动作。 */
    public static class Item {
        final String label;
        final int iconRes;
        final Runnable action;
        boolean danger;

        public Item(String label, int iconRes, Runnable action) {
            this.label = label;
            this.iconRes = iconRes;
            this.action = action;
        }

        /** 危险操作（卸载/删除）文字用红色。 */
        public Item danger() {
            this.danger = true;
            return this;
        }
    }

    private MtMenu() {
    }

    public static Item item(String label, int iconRes, Runnable action) {
        return new Item(label, iconRes, action);
    }

    public static void show(final Context context, String title, String subtitle,
                            List<Item> items) {
        if (context == null || items == null || items.isEmpty()) {
            return;
        }
        final Dialog dialog = new Dialog(context);
        View root = LayoutInflater.from(context).inflate(R.layout.dialog_mt_menu, null);
        TextView titleView = root.findViewById(R.id.menu_title);
        TextView subtitleView = root.findViewById(R.id.menu_subtitle);
        LinearLayout container = root.findViewById(R.id.menu_items);

        if (title != null && title.length() > 0) {
            titleView.setText(title);
            titleView.setVisibility(View.VISIBLE);
        } else {
            titleView.setVisibility(View.GONE);
        }
        if (subtitle != null && subtitle.length() > 0) {
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(View.VISIBLE);
        } else {
            subtitleView.setVisibility(View.GONE);
        }

        final int accent = Util.accentColor(context);
        final int dividerColor = context.getResources().getColor(R.color.divider);
        LayoutInflater inflater = LayoutInflater.from(context);
        for (int i = 0; i < items.size(); i++) {
            final Item item = items.get(i);
            View row = inflater.inflate(R.layout.item_mt_menu, container, false);
            ImageView icon = row.findViewById(R.id.menu_icon);
            TextView label = row.findViewById(R.id.menu_text);
            if (item.iconRes != 0) {
                icon.setImageResource(item.iconRes);
                icon.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
                icon.setVisibility(View.VISIBLE);
            } else {
                icon.setVisibility(View.INVISIBLE);
            }
            label.setText(item.label);
            if (item.danger) {
                label.setTextColor(0xFFD9504A);
            }
            row.setOnClickListener(v -> {
                dismissQuietly(dialog);
                if (item.action != null) {
                    try {
                        item.action.run();
                    } catch (Exception ignored) {
                    }
                }
            });
            container.addView(row);
            if (i < items.size() - 1) {
                View line = new View(context);
                line.setBackgroundColor(dividerColor);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                container.addView(line, params);
            }
        }

        TextView cancel = root.findViewById(R.id.menu_cancel);
        cancel.setOnClickListener(v -> dismissQuietly(dialog));

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.45f;
            int width = (int) (Math.min(context.getResources().getDisplayMetrics().widthPixels
                    * 0.84f, 320 * context.getResources().getDisplayMetrics().density));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(android.view.Gravity.CENTER);
        }
        dialog.setCanceledOnTouchOutside(true);
        try {
            dialog.show();
        } catch (Exception ignored) {
        }
    }

    private static void dismissQuietly(Dialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
        } catch (Exception ignored) {
        }
    }

    /** 便捷构建：依次传入 label/icon/action 三元组。 */
    public static List<Item> items(Object... triples) {
        List<Item> list = new ArrayList<>();
        for (int i = 0; i + 2 < triples.length; i += 3) {
            list.add(item((String) triples[i], (Integer) triples[i + 1],
                    (Runnable) triples[i + 2]));
        }
        return list;
    }
}
