package com.mtstyle.fm;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 回收站：查看 / 恢复 / 彻底删除 / 清空。 */
public class TrashActivity extends AppCompatActivity {

    private final List<Trash.Entry> entries = new ArrayList<>();
    private SimpleRowAdapter adapter;
    private TextView subtitle;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Settings.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        ((TextView) findViewById(R.id.list_title)).setText(R.string.trash_title);
        subtitle = findViewById(R.id.list_subtitle);
        emptyView = findViewById(R.id.list_empty);
        emptyView.setText(R.string.trash_empty);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.list_action2).setVisibility(View.GONE);
        TextView clear = findViewById(R.id.list_action1);
        clear.setText(R.string.trash_clear);
        clear.setOnClickListener(v -> confirmClear());

        ListView listView = findViewById(R.id.list_view);
        adapter = new SimpleRowAdapter(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) ->
                showEntryMenu(entries.get(position)));
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        entries.clear();
        entries.addAll(Trash.list());
        List<SimpleRowAdapter.Row> rows = new ArrayList<>();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        for (Trash.Entry entry : entries) {
            SimpleRowAdapter.Row row = new SimpleRowAdapter.Row();
            row.iconRes = R.drawable.ic_trash;
            row.title = entry.name;
            String original = entry.original == null || entry.original.isEmpty()
                    ? "未知" : entry.original;
            row.subtitle = "原位置：" + original
                    + "\n删除时间：" + format.format(new Date(entry.time))
                    + "   共 " + entry.count + " 项";
            row.trailing = Util.formatSize(entry.size);
            rows.add(row);
        }
        adapter.setRows(rows);
        subtitle.setText(getString(R.string.trash_summary, entries.size(),
                Util.formatSize(Trash.totalSize())));
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showEntryMenu(final Trash.Entry entry) {
        final String[] items = {getString(R.string.trash_restore), getString(R.string.trash_delete),
                getString(R.string.copy_path)};
        new AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        restore(entry);
                    } else if (which == 1) {
                        deleteForever(entry);
                    } else {
                        copyPath(entry.original);
                    }
                })
                .show();
    }

    private void restore(final Trash.Entry entry) {
        TaskRunner.run(this, getString(R.string.trash_restore), true,
                progress -> Trash.restore(entry),
                (ok, message) -> {
                    toast(ok ? getString(R.string.trash_restored) : "恢复失败：" + message);
                    reload();
                });
    }

    private void deleteForever(final Trash.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.trash_delete)
                .setMessage(entry.name)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    Trash.deleteForever(entry);
                    reload();
                })
                .show();
    }

    private void confirmClear() {
        if (entries.isEmpty()) {
            toast(getString(R.string.trash_empty));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.trash_clear)
                .setMessage(R.string.trash_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> TaskRunner.run(this,
                        getString(R.string.trash_clear), true,
                        progress -> Trash.clearAll(),
                        (ok, message) -> {
                            toast(getString(R.string.trash_cleared));
                            reload();
                        }))
                .show();
    }

    private void copyPath(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("path", text));
            toast(getString(R.string.path_copied));
        }
    }

    private void toast(String text) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show();
    }
}
