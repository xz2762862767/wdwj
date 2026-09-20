package com.mtstyle.fm;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.mtstyle.fm.apk.Arsc;
import com.mtstyle.fm.apk.ZipEdit;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 资源查看：解析 APK 内 resources.arsc，列出「类型 / 名称 = 值」，支持按名称、值或资源 ID 搜索。
 * 只读浏览，不修改资源表。
 */
public class ArscActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "arsc_apk_path";
    /** 单次最多渲染的行数，避免超大资源表卡顿。 */
    private static final int MAX_ROWS = 2000;

    private File apk;
    private final List<Arsc.Entry> entries = new ArrayList<>();
    private final List<Arsc.Entry> filtered = new ArrayList<>();
    private SimpleRowAdapter adapter;
    private TextView statusView;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_arsc);

        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PATH);
        apk = path == null ? null : new File(path);
        if (apk == null || !apk.exists()) {
            toast(getString(R.string.analysis_missing));
            finish();
            return;
        }

        TextView title = findViewById(R.id.arsc_title);
        title.setText(getString(R.string.res_title) + " · " + apk.getName());
        statusView = findViewById(R.id.arsc_status);
        emptyView = findViewById(R.id.arsc_empty);
        statusView.setText(getString(R.string.res_reading));

        findViewById(R.id.arsc_back).setOnClickListener(v -> finish());

        adapter = new SimpleRowAdapter(this);
        ListView listView = findViewById(R.id.arsc_list);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < filtered.size()) {
                showDetail(filtered.get(position));
            }
        });

        EditText search = findViewById(R.id.arsc_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter(s == null ? "" : s.toString());
            }
        });

        load();
    }

    private void load() {
        TaskRunner.run(this, getString(R.string.analysis_reading), false, progress -> {
            byte[] bytes = ZipEdit.readEntryOrFile(apk, "resources.arsc");
            if (bytes == null) {
                throw new Exception("包内没有 resources.arsc");
            }
            Arsc.Table table = Arsc.parse(bytes);
            entries.clear();
            entries.addAll(table.entries);
        }, (ok, message) -> {
            if (!ok) {
                statusView.setText(getString(R.string.analysis_failed, String.valueOf(message)));
                toast(getString(R.string.analysis_failed, String.valueOf(message)));
                return;
            }
            applyFilter("");
        });
    }

    private void applyFilter(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.US);
        filtered.clear();
        int matched = 0;
        for (Arsc.Entry entry : entries) {
            if (needle.length() == 0 || matches(entry, needle)) {
                matched++;
                if (filtered.size() < MAX_ROWS) {
                    filtered.add(entry);
                }
            }
        }
        if (needle.length() == 0) {
            statusView.setText(getString(R.string.res_count, entries.size()));
        } else {
            statusView.setText(getString(R.string.res_count_filtered, matched, entries.size(),
                    filtered.size()));
        }
        emptyView.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);

        List<SimpleRowAdapter.Row> rows = new ArrayList<>(filtered.size());
        for (Arsc.Entry entry : filtered) {
            SimpleRowAdapter.Row row = new SimpleRowAdapter.Row();
            row.title = entry.typeName + "/" + entry.key;
            StringBuilder subtitle = new StringBuilder(entry.resourceId());
            if (entry.config != null && entry.config.length() > 0) {
                subtitle.append(" · ").append(entry.config);
            }
            row.subtitle = subtitle.toString();
            String value = entry.value == null ? "" : entry.value;
            row.trailing = value.length() > 40 ? value.substring(0, 40) + "…" : value;
            row.iconRes = iconOf(entry.typeName);
            rows.add(row);
        }
        adapter.setRows(rows);
    }

    private boolean matches(Arsc.Entry entry, String needle) {
        if (entry.key != null && entry.key.toLowerCase(Locale.US).contains(needle)) {
            return true;
        }
        if (entry.value != null && entry.value.toLowerCase(Locale.US).contains(needle)) {
            return true;
        }
        return entry.resourceId().toLowerCase(Locale.US).contains(needle);
    }

    private int iconOf(String typeName) {
        if (typeName == null) {
            return R.drawable.ic_tool;
        }
        switch (typeName) {
            case "string":
                return R.drawable.ic_text;
            case "layout":
            case "xml":
            case "anim":
            case "animator":
            case "interpolator":
                return R.drawable.ic_code;
            case "drawable":
            case "mipmap":
                return R.drawable.ic_image;
            case "color":
                return R.drawable.ic_palette;
            case "dimen":
            case "integer":
            case "bool":
                return R.drawable.ic_settings;
            default:
                return R.drawable.ic_tool;
        }
    }

    private void showDetail(Arsc.Entry entry) {
        StringBuilder builder = new StringBuilder();
        builder.append("资源 ID：").append(entry.resourceId()).append('\n');
        builder.append("类型：").append(entry.typeName).append('\n');
        builder.append("名称：").append(entry.key).append('\n');
        if (entry.config != null && entry.config.length() > 0) {
            builder.append(getString(R.string.res_config)).append("：").append(entry.config).append('\n');
        }
        builder.append("值：\n").append(entry.value);
        String content = builder.toString();
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(12f);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setTextColor(getResources().getColor(R.color.text_primary));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad / 2, pad, pad / 2);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.res_detail))
                .setView(scroll)
                .setPositiveButton(R.string.act_copy, (d, w) -> copy(content))
                .setNegativeButton(R.string.ok, null)
                .show();
    }

    private void copy(String text) {
        try {
            ClipboardManager manager =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("fm", text));
                toast(getString(R.string.copied));
            }
        } catch (Exception ignored) {
        }
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }
}
