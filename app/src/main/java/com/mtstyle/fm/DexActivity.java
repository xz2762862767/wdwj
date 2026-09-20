package com.mtstyle.fm;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.mtstyle.fm.apk.Dex;
import com.mtstyle.fm.apk.ZipEdit;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * DEX 反汇编查看器入口：列出 APK 内 classes*.dex 的类，支持按类名/成员名搜索，
 * 逐类查看方法列表与 Smali 风格字节码。自研离线实现，反汇编结果已与 Android SDK
 * dexdump 交叉验证一致（指令 / 字符串池 / 异常表逐条比对）。
 */
public class DexActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "dex_apk_path";
    /** 可选：直接打开指定的 dex 条目。 */
    public static final String EXTRA_ENTRY = "dex_entry";
    /** 单次最多渲染的类行数，避免超大 dex 卡顿。 */
    private static final int MAX_ROWS = 3000;

    /** 已解析的 Dex 缓存，供类详情页与反汇编页复用，避免重复解析。 */
    static Dex sharedDex;
    static String sharedKey;

    private File apk;
    private String dexEntry = "classes.dex";
    private final List<String> dexNames = new ArrayList<>();
    private final List<Dex.ClassDef> classes = new ArrayList<>();
    private final List<Dex.ClassDef> filtered = new ArrayList<>();
    private SimpleRowAdapter adapter;
    private TextView statusView;
    private TextView emptyView;
    private TextView pickView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Settings.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dex);

        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PATH);
        apk = path == null ? null : new File(path);
        if (apk == null || !apk.exists()) {
            toast(getString(R.string.analysis_missing));
            finish();
            return;
        }

        TextView title = findViewById(R.id.dex_title);
        title.setText(getString(R.string.dex_title) + " · " + apk.getName());
        statusView = findViewById(R.id.dex_status);
        emptyView = findViewById(R.id.dex_empty);
        pickView = findViewById(R.id.dex_dexpick);
        statusView.setText(getString(R.string.dex_reading, dexEntry));

        findViewById(R.id.dex_back).setOnClickListener(v -> finish());

        adapter = new SimpleRowAdapter(this);
        ListView listView = findViewById(R.id.dex_list);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < filtered.size()) {
                openClass(filtered.get(position));
            }
        });

        EditText search = findViewById(R.id.dex_search);
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

        String wanted = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ENTRY);
        collectDexNames();
        pickView.setOnClickListener(v -> pickDex());
        if (wanted != null && dexNames.contains(wanted)) {
            load(wanted);
        } else {
            load(dexNames.contains("classes.dex") ? "classes.dex" : firstDex());
        }
    }

    private String firstDex() {
        return dexNames.isEmpty() ? "classes.dex" : dexNames.get(0);
    }

    /** 列出包内 classes*.dex。 */
    private void collectDexNames() {
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("classes") && name.endsWith(".dex")
                        && name.indexOf('/') < 0) {
                    dexNames.add(name);
                }
            }
            Collections.sort(dexNames);
        } catch (Exception ignored) {
        } finally {
            closeQuietly(zip);
        }
        if (dexNames.isEmpty()) {
            dexNames.add("classes.dex");
        }
        if (dexNames.size() > 1) {
            pickView.setVisibility(View.VISIBLE);
        } else {
            pickView.setVisibility(View.GONE);
        }
    }

    private void closeQuietly(ZipFile zip) {
        if (zip != null) {
            try {
                zip.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 多 dex 时切换 dex 文件。 */
    private void pickDex() {
        final String[] items = dexNames.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dex_switch)
                .setItems(items, (d, which) -> load(items[which]))
                .setNegativeButton(R.string.ok, null)
                .show();
    }

    private void load(final String entry) {
        dexEntry = entry;
        if (dexNames.size() > 1) {
            pickView.setText(entry);
        }
        statusView.setText(getString(R.string.dex_reading, entry));
        TaskRunner.run(this, getString(R.string.dex_reading, entry), false, progress -> {
            byte[] bytes = ZipEdit.readEntryOrFile(apk, entry);
            if (bytes == null) {
                throw new Exception(getString(R.string.dex_missing, entry));
            }
            if (!Dex.isDex(bytes)) {
                throw new Exception(getString(R.string.dex_invalid, entry));
            }
            Dex parsed = Dex.parse(bytes);
            sharedDex = parsed;
            sharedKey = key(apk, entry);
            classes.clear();
            classes.addAll(parsed.classes);
            Collections.sort(classes, (a, b) -> a.name.compareTo(b.name));
        }, (ok, message) -> {
            if (!ok) {
                statusView.setText(getString(R.string.analysis_failed, String.valueOf(message)));
                toast(getString(R.string.analysis_failed, String.valueOf(message)));
                return;
            }
            applyFilter("");
        });
    }

    static String key(File apk, String entry) {
        return apk.getAbsolutePath() + "#" + entry;
    }

    private void applyFilter(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.US);
        filtered.clear();
        int matched = 0;
        for (Dex.ClassDef def : classes) {
            if (needle.length() == 0 || matches(def, needle)) {
                matched++;
                if (filtered.size() < MAX_ROWS) {
                    filtered.add(def);
                }
            }
        }
        if (needle.length() == 0) {
            statusView.setText(getString(R.string.dex_count, classes.size(), methodCount(classes),
                    fieldCount(classes)));
        } else {
            statusView.setText(getString(R.string.dex_count_filtered, matched, filtered.size()));
        }
        emptyView.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);

        List<SimpleRowAdapter.Row> rows = new ArrayList<>(filtered.size());
        for (Dex.ClassDef def : filtered) {
            SimpleRowAdapter.Row row = new SimpleRowAdapter.Row();
            row.title = shortName(def.name);
            row.subtitle = subtitleOf(def);
            row.iconRes = def.isInterface() ? R.drawable.ic_tool : R.drawable.ic_code;
            rows.add(row);
        }
        adapter.setRows(rows);
    }

    private String subtitleOf(Dex.ClassDef def) {
        StringBuilder builder = new StringBuilder(64);
        String pkg = packageOf(def.name);
        if (pkg.length() > 0) {
            builder.append(pkg);
        }
        if (def.isInterface()) {
            builder.insert(0, "interface · ");
        } else if ((def.accessFlags & 0x4000) != 0) {
            builder.insert(0, "enum · ");
        } else if ((def.accessFlags & 0x0400) != 0) {
            builder.insert(0, "abstract · ");
        }
        int methods = def.directMethods.size() + def.virtualMethods.size();
        int fields = def.staticFields.size() + def.instanceFields.size();
        if (builder.length() > 0) {
            builder.append(" · ");
        }
        builder.append(getString(R.string.dex_members, methods, fields));
        return builder.toString();
    }

    /** 支持按类名或成员（方法/字段）名搜索。 */
    private boolean matches(Dex.ClassDef def, String needle) {
        if (def.name.toLowerCase(Locale.US).contains(needle)) {
            return true;
        }
        for (Dex.EncodedMethod method : def.directMethods) {
            if (method.reference.toLowerCase(Locale.US).contains(needle)) {
                return true;
            }
        }
        for (Dex.EncodedMethod method : def.virtualMethods) {
            if (method.reference.toLowerCase(Locale.US).contains(needle)) {
                return true;
            }
        }
        for (Dex.EncodedField field : def.staticFields) {
            if (field.reference.toLowerCase(Locale.US).contains(needle)) {
                return true;
            }
        }
        for (Dex.EncodedField field : def.instanceFields) {
            if (field.reference.toLowerCase(Locale.US).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private int methodCount(List<Dex.ClassDef> defs) {
        int total = 0;
        for (Dex.ClassDef def : defs) {
            total += def.directMethods.size() + def.virtualMethods.size();
        }
        return total;
    }

    private int fieldCount(List<Dex.ClassDef> defs) {
        int total = 0;
        for (Dex.ClassDef def : defs) {
            total += def.staticFields.size() + def.instanceFields.size();
        }
        return total;
    }

    static String shortName(String descriptor) {
        String value = descriptor;
        int index = value.lastIndexOf('/');
        if (index >= 0) {
            value = value.substring(index + 1);
        }
        if (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    static String packageOf(String descriptor) {
        String value = descriptor;
        if (value.startsWith("L")) {
            value = value.substring(1);
        }
        int index = value.lastIndexOf('/');
        return index < 0 ? "" : value.substring(0, index);
    }

    private void openClass(Dex.ClassDef def) {
        Intent intent = new Intent(this, ClassActivity.class);
        intent.putExtra(ClassActivity.EXTRA_PATH, apk.getAbsolutePath());
        intent.putExtra(ClassActivity.EXTRA_ENTRY, dexEntry);
        intent.putExtra(ClassActivity.EXTRA_CLASS, def.name);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 退出查看器时释放解析结果（子页面返回时本页不会销毁）
        if (isFinishing()) {
            sharedDex = null;
            sharedKey = null;
        }
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }
}
