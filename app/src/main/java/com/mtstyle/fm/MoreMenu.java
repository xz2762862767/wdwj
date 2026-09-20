package com.mtstyle.fm;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * v5.2：「更多」弹窗——方角卡片，单排（每行一个功能），比双排更宽、更好点。
 * 结构：标题条（强调色竖条 + 标题 + 当前路径）/ 单列图标列表（行间细分隔线）/ 取消。
 * 内容超出屏幕高度时自动限高并可滚动（见 {@link #configureWindow}）。
 */
public final class MoreMenu {

    /** 一个操作项：图标 + 文字 + 点击动作。 */
    public static class Item {
        final String label;
        final int iconRes;
        final Runnable action;

        public Item(String label, int iconRes, Runnable action) {
            this.label = label;
            this.iconRes = iconRes;
            this.action = action;
        }
    }

    private MoreMenu() {
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
        final View root = LayoutInflater.from(context)
                .inflate(R.layout.dialog_more_menu, null);

        TextView titleView = root.findViewById(R.id.more_menu_title);
        TextView subtitleView = root.findViewById(R.id.more_menu_subtitle);
        LinearLayout list = root.findViewById(R.id.more_menu_grid);

        if (title != null && title.length() > 0) {
            titleView.setText(title);
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
        if (list != null) {
            for (int i = 0; i < items.size(); i++) {
                final Item item = items.get(i);
                View cell = inflater.inflate(R.layout.item_more_cell, list, false);
                ImageView icon = cell.findViewById(R.id.cell_icon);
                TextView label = cell.findViewById(R.id.cell_text);
                if (icon != null) {
                    if (item.iconRes != 0) {
                        icon.setImageResource(item.iconRes);
                        icon.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
                        icon.setVisibility(View.VISIBLE);
                    } else {
                        icon.setVisibility(View.INVISIBLE);
                    }
                }
                if (label != null) {
                    label.setText(item.label);
                }
                cell.setOnClickListener(v -> {
                    dismissQuietly(dialog);
                    if (item.action != null) {
                        try {
                            item.action.run();
                        } catch (Exception ignored) {
                        }
                    }
                });
                list.addView(cell);
                if (i < items.size() - 1) {
                    View line = new View(context);
                    line.setBackgroundColor(dividerColor);
                    list.addView(line, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 1));
                }
            }
        }

        root.findViewById(R.id.more_menu_cancel).setOnClickListener(v -> dismissQuietly(dialog));

        dialog.setContentView(root);
        configureWindow(context, dialog, root);
        dialog.setCanceledOnTouchOutside(true);
        try {
            dialog.show();
        } catch (Exception ignored) {
            return;
        }
        // 部分机型在 show() 之后才真正应用尺寸，这里再兜底一次
        configureWindow(context, dialog, root);
    }

    /** 宽度取 94% 屏宽（上限 460dp）；高度按内容自适应，超出屏高 82% 时改为滚动。 */
    private static void configureWindow(Context context, Dialog dialog, View root) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.dimAmount = 0.45f;
        window.setAttributes(attrs);

        float density = context.getResources().getDisplayMetrics().density;
        int width = (int) Math.min(
                context.getResources().getDisplayMetrics().widthPixels * 0.94f, 460 * density);
        int maxHeight = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.88f);
        int height = ViewGroup.LayoutParams.WRAP_CONTENT;
        if (root != null) {
            try {
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                height = Math.min(root.getMeasuredHeight(), maxHeight);
            } catch (Exception ignored) {
            }
        }
        window.setLayout(width, height);
        window.setGravity(Gravity.CENTER);
    }

    private static void dismissQuietly(Dialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
        } catch (Exception ignored) {
        }
    }
}
