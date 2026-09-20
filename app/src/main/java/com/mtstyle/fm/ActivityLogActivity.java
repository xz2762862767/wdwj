package com.mtstyle.fm;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Activity 记录列表（依赖无障碍服务）。 */
public class ActivityLogActivity extends AppCompatActivity {

    private final Map<String, String> labels = new HashMap<>();
    private final List<ActivityLog.Record> records = new ArrayList<>();
    private SimpleRowAdapter adapter;
    private TextView subtitle;
    private TextView emptyView;
    private TextView action2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Settings.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        ((TextView) findViewById(R.id.list_title)).setText(R.string.record_title);
        subtitle = findViewById(R.id.list_subtitle);
        emptyView = findViewById(R.id.list_empty);
        action2 = findViewById(R.id.list_action2);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        TextView clear = findViewById(R.id.list_action1);
        clear.setText(R.string.record_clear);
        clear.setOnClickListener(v -> confirmClear());
        action2.setText(R.string.record_enable);
        action2.setOnClickListener(v -> ActivityLog.openSettings(this));

        ListView listView = findViewById(R.id.list_view);
        adapter = new SimpleRowAdapter(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            ActivityLog.Record record = records.get(position);
            new AlertDialog.Builder(this)
                    .setTitle(label(record.packageName))
                    .setMessage(record.className + "\n\n" + record.packageName)
                    .setPositiveButton(R.string.ok, null)
                    .show();
        });
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        records.clear();
        records.addAll(ActivityLog.read());
        boolean enabled = ActivityLog.isServiceEnabled(this);
        List<SimpleRowAdapter.Row> rows = new ArrayList<>();
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        for (ActivityLog.Record record : records) {
            SimpleRowAdapter.Row row = new SimpleRowAdapter.Row();
            row.iconRes = R.drawable.ic_record;
            row.title = label(record.packageName);
            String cls = record.className;
            int dot = cls.lastIndexOf('.');
            row.subtitle = format.format(new Date(record.time)) + "   "
                    + (dot >= 0 ? cls.substring(dot + 1) : cls);
            rows.add(row);
        }
        adapter.setRows(rows);
        String status = enabled ? "记录服务已开启" : getString(R.string.record_disabled);
        subtitle.setText(getString(R.string.record_summary, records.size()) + " · " + status);
        emptyView.setText(enabled ? getString(R.string.record_empty)
                : getString(R.string.record_disabled));
        emptyView.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        action2.setText(enabled ? "无障碍设置" : getString(R.string.record_enable));
    }

    private String label(String packageName) {
        String cached = labels.get(packageName);
        if (cached != null) {
            return cached;
        }
        PackageManager pm = getPackageManager();
        String value = packageName;
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (label != null && label.length() > 0) {
                value = label.toString();
            }
        } catch (Exception ignored) {
        }
        labels.put(packageName, value);
        return value;
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.record_clear)
                .setMessage("确定清空全部 Activity 记录？")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    ActivityLog.clear();
                    reload();
                })
                .show();
    }
}
