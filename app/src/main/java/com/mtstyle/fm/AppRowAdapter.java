package com.mtstyle.fm;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 已安装应用列表适配器（MT 风格：图标 + 应用名 + 版本/大小 + 包名）。
 *
 * <p>图标按行懒加载：只有真正显示出来的行才会去解析 APK 图标，因此打开列表是“立即出现”，
 * 图标再逐个补上，避免为了加载几百个图标而卡住进入页面。</p>
 */
public class AppRowAdapter extends BaseAdapter {

    /** 图标缓存上限（按包名），超出后按最久未用淘汰。 */
    private static final int ICON_CACHE_MAX = 320;

    private final Context context;
    private final LayoutInflater inflater;
    private final List<AppItem> items = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService iconLoader = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "app-icon");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final Map<String, Drawable> iconCache = new LinkedHashMap<String, Drawable>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Drawable> eldest) {
            return size() > ICON_CACHE_MAX;
        }
    };
    private final java.util.HashSet<String> iconPending = new java.util.HashSet<>();

    public AppRowAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    public void setItems(List<AppItem> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        notifyDataSetChanged();
    }

    public AppItem getItemAt(int position) {
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
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_app_row, parent, false);
        }
        AppItem item = items.get(position);
        ImageView icon = view.findViewById(R.id.app_icon);
        TextView name = view.findViewById(R.id.app_name);
        TextView version = view.findViewById(R.id.app_version);
        TextView size = view.findViewById(R.id.app_size);
        TextView pkg = view.findViewById(R.id.app_package);
        TextView harden = view.findViewById(R.id.app_harden);
        TextView systemTag = view.findViewById(R.id.app_system);

        bindIcon(icon, item);
        name.setText(item.label);
        version.setText(item.versionName);
        size.setText(item.size > 0 ? Util.formatSize(item.size) : "");
        pkg.setText(item.packageName);
        // v5.9.37：系统自带的应用在版本号后面挂个「系统」小标
        if (systemTag != null) {
            systemTag.setVisibility(item.system ? View.VISIBLE : View.GONE);
        }
        bindHarden(harden, item);
        return view;
    }

    /** 加固标签：有平台名就用警示色，确认没加固用次要色，没检测出来就不显示。 */
    private void bindHarden(TextView view, AppItem item) {
        String text = item.harden == null ? "" : item.harden;
        if (text.isEmpty()) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setText(text);
        boolean hardened = !HardenDetect.NOT_HARDENED.equals(text);
        view.setTextColor(ContextCompat.getColor(context,
                hardened ? R.color.accent_orange : R.color.text_secondary));
    }

    private void bindIcon(ImageView view, AppItem item) {
        // 标记该 ImageView 当前绑定的包名，避免异步回填时贴到被复用的行上
        view.setTag(item.packageName);
        Drawable cached;
        synchronized (iconCache) {
            cached = iconCache.get(item.packageName);
        }
        if (cached != null) {
            view.setImageDrawable(cached);
            return;
        }
        view.setImageResource(R.drawable.ic_file);
        requestIcon(item.packageName, view);
    }

    private void requestIcon(final String packageName, final ImageView view) {
        synchronized (iconPending) {
            if (!iconPending.add(packageName)) {
                return;
            }
        }
        iconLoader.execute(() -> {
            Drawable loaded = null;
            try {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                loaded = info.loadIcon(pm);
            } catch (Throwable ignored) {
            }
            final Drawable drawable = loaded;
            mainHandler.post(() -> {
                synchronized (iconPending) {
                    iconPending.remove(packageName);
                }
                Drawable result = drawable;
                if (result == null) {
                    // 失败也写入缓存，避免该行在滚动时反复重试
                    result = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_file);
                }
                if (result == null) {
                    return;
                }
                synchronized (iconCache) {
                    iconCache.put(packageName, result);
                }
                Object tag = view.getTag();
                if (packageName.equals(tag)) {
                    view.setImageDrawable(result);
                }
            });
        });
    }
}
