package com.mtstyle.fm;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.mtstyle.fm.apk.Dex;
import com.mtstyle.fm.apk.ZipEdit;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 类详情：显示类的父类/接口/来源/标志，并按「方法」「字段」分组列出成员。
 * 点击方法查看该方法的 Smali 字节码，点击字段复制字段声明。
 */
public class ClassActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "class_apk_path";
    public static final String EXTRA_ENTRY = "class_dex_entry";
    public static final String EXTRA_CLASS = "class_descriptor";

    private File apk;
    private String dexEntry;
    private String className;
    private Dex dex;
    private Dex.ClassDef classDef;

    /** 与列表行一一对应的目标（分组标题行为 null）。 */
    private final List<Object> targets = new ArrayList<>();
    private final List<Object> allTargets = new ArrayList<>();
    private SimpleRowAdapter adapter;
    private TextView statusView;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Settings.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_class);

        Intent intent = getIntent();
        String path = intent == null ? null : intent.getStringExtra(EXTRA_PATH);
        dexEntry = intent == null ? "classes.dex" : intent.getStringExtra(EXTRA_ENTRY);
        className = intent == null ? null : intent.getStringExtra(EXTRA_CLASS);
        apk = path == null ? null : new File(path);
        if (apk == null || !apk.exists() || className == null) {
            toast(getString(R.string.analysis_missing));
            finish();
            return;
        }
        if (dexEntry == null) {
            dexEntry = "classes.dex";
        }

        TextView title = findViewById(R.id.class_title);
        title.setText(DexActivity.shortName(className));
        statusView = findViewById(R.id.class_status);
        emptyView = findViewById(R.id.class_empty);

        findViewById(R.id.class_back).setOnClickListener(v -> finish());
        findViewById(R.id.class_java).setOnClickListener(v -> openWholeClassJava());
        findViewById(R.id.class_copy).setOnClickListener(v -> openWholeClass());

        adapter = new SimpleRowAdapter(this);
        ListView listView = findViewById(R.id.class_list);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= targets.size()) {
                return;
            }
            Object target = targets.get(position);
            if (target instanceof Dex.EncodedMethod) {
                openMethod((Dex.EncodedMethod) target);
            } else if (target instanceof Dex.EncodedField) {
                copy(((Dex.EncodedField) target).reference);
            }
        });

        EditText search = findViewById(R.id.class_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                bind(s == null ? "" : s.toString());
            }
        });

        load();
    }

    private void load() {
        String key = DexActivity.key(apk, dexEntry);
        if (DexActivity.sharedDex != null && key.equals(DexActivity.sharedKey)) {
            dex = DexActivity.sharedDex;
            classDef = findClass();
            if (classDef != null) {
                bind("");
                return;
            }
        }
        TaskRunner.run(this, getString(R.string.dex_reading, dexEntry), false, progress -> {
            byte[] bytes = ZipEdit.readEntry(apk, dexEntry);
            if (bytes == null) {
                throw new Exception(getString(R.string.dex_missing, dexEntry));
            }
            Dex parsed = Dex.parse(bytes);
            DexActivity.sharedDex = parsed;
            DexActivity.sharedKey = key;
            dex = parsed;
            classDef = findClass();
            if (classDef == null) {
                throw new Exception(getString(R.string.dex_class_missing, className));
            }
        }, (ok, message) -> {
            if (!ok) {
                toast(getString(R.string.analysis_failed, String.valueOf(message)));
                finish();
                return;
            }
            bind("");
        });
    }

    private Dex.ClassDef findClass() {
        if (dex == null) {
            return null;
        }
        for (Dex.ClassDef def : dex.classes) {
            if (def.name.equals(className)) {
                return def;
            }
        }
        return null;
    }

    /** 头部信息 + 成员列表。 */
    private void bind(String query) {
        if (classDef == null) {
            return;
        }
        TextView header = findViewById(R.id.class_header);
        header.setText(headerText());

        String needle = query == null ? "" : query.trim().toLowerCase(Locale.US);
        allTargets.clear();
        for (Dex.EncodedMethod method : classDef.directMethods) {
            allTargets.add(method);
        }
        for (Dex.EncodedMethod method : classDef.virtualMethods) {
            allTargets.add(method);
        }
        for (Dex.EncodedField field : classDef.staticFields) {
            allTargets.add(field);
        }
        for (Dex.EncodedField field : classDef.instanceFields) {
            allTargets.add(field);
        }

        targets.clear();
        List<SimpleRowAdapter.Row> rows = new ArrayList<>();
        int matched = 0;
        boolean methodHeaderAdded = false;
        boolean fieldHeaderAdded = false;
        for (Object target : allTargets) {
            if (needle.length() > 0 && !matches(target, needle)) {
                continue;
            }
            matched++;
            if (target instanceof Dex.EncodedMethod) {
                if (!methodHeaderAdded) {
                    methodHeaderAdded = true;
                    targets.add(null);
                    rows.add(headerRow(getString(R.string.dex_methods_header,
                            classDef.directMethods.size() + classDef.virtualMethods.size())));
                }
                Dex.EncodedMethod method = (Dex.EncodedMethod) target;
                targets.add(method);
                rows.add(methodRow(method));
            } else {
                if (!fieldHeaderAdded) {
                    fieldHeaderAdded = true;
                    targets.add(null);
                    rows.add(headerRow(getString(R.string.dex_fields_header,
                            classDef.staticFields.size() + classDef.instanceFields.size())));
                }
                targets.add(target);
                rows.add(fieldRow((Dex.EncodedField) target));
            }
        }
        adapter.setRows(rows);
        emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        if (needle.length() == 0) {
            statusView.setText(getString(R.string.dex_members,
                    classDef.directMethods.size() + classDef.virtualMethods.size(),
                    classDef.staticFields.size() + classDef.instanceFields.size()));
        } else {
            statusView.setText(getString(R.string.dex_member_filtered, matched, allTargets.size()));
        }
    }

    private String headerText() {
        StringBuilder builder = new StringBuilder(256);
        builder.append("类名：").append(classDef.name).append('\n');
        builder.append("父类：").append(classDef.superName == null ? "-" : classDef.superName)
                .append('\n');
        if (!classDef.interfaces.isEmpty()) {
            builder.append("接口：");
            for (int i = 0; i < classDef.interfaces.size(); i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(classDef.interfaces.get(i));
            }
            builder.append('\n');
        }
        if (classDef.sourceFile != null) {
            builder.append("来源：").append(classDef.sourceFile).append('\n');
        }
        builder.append("标志：").append(Dex.modifiers(classDef.accessFlags, false).trim()).append('\n');
        builder.append("成员：方法 ").append(classDef.directMethods.size() + classDef.virtualMethods.size())
                .append("（直接 ").append(classDef.directMethods.size())
                .append(" / 虚 ").append(classDef.virtualMethods.size()).append("）")
                .append("，字段 ").append(classDef.staticFields.size() + classDef.instanceFields.size())
                .append("（静态 ").append(classDef.staticFields.size())
                .append(" / 实例 ").append(classDef.instanceFields.size()).append("）");
        return builder.toString();
    }

    private SimpleRowAdapter.Row headerRow(String title) {
        SimpleRowAdapter.Row row = new SimpleRowAdapter.Row();
        row.header = true;
        row.title = title;
        return row;
    }

    private SimpleRowAdapter.Row methodRow(Dex.EncodedMethod method) {
        SimpleRowAdapter.Row row = new SimpleRowAdapter.Row();
        row.title = memberName(method.reference);
        StringBuilder subtitle = new StringBuilder(48);
        subtitle.append(memberDescriptor(method.reference));
        if (method.codeOff == 0) {
            subtitle.append(" · 无代码");
        } else {
            Dex.CodeItem code = dex.code(method);
            if (code != null) {
                subtitle.append(" · ").append(code.insnsSize).append(" 条指令");
            }
        }
        row.subtitle = subtitle.toString();
        row.trailing = compactFlags(method.accessFlags, true);
        row.iconRes = R.drawable.ic_code;
        return row;
    }

    private SimpleRowAdapter.Row fieldRow(Dex.EncodedField field) {
        SimpleRowAdapter.Row row = new SimpleRowAdapter.Row();
        row.title = memberName(field.reference);
        row.subtitle = fieldType(field.reference);
        row.trailing = compactFlags(field.accessFlags, false);
        row.iconRes = R.drawable.ic_tool;
        return row;
    }

    private String compactFlags(int flags, boolean isMethod) {
        String full = Dex.modifiers(flags, isMethod).trim();
        return full.length() <= 14 ? full : full.substring(0, 13) + "…";
    }

    /** 从引用串取成员名：Lcls;->name(desc)ret 或 Lcls;->name:type。 */
    private String memberName(String reference) {
        int arrow = reference.indexOf("->");
        if (arrow < 0) {
            return reference;
        }
        String rest = reference.substring(arrow + 2);
        int cut = rest.indexOf('(');
        if (cut < 0) {
            cut = rest.indexOf(':');
        }
        return cut < 0 ? rest : rest.substring(0, cut);
    }

    /** 方法取原型，字段取类型。 */
    private String memberDescriptor(String reference) {
        int arrow = reference.indexOf("->");
        if (arrow < 0) {
            return "";
        }
        String rest = reference.substring(arrow + 2);
        int cut = rest.indexOf('(');
        if (cut >= 0) {
            return rest.substring(cut);
        }
        int colon = rest.indexOf(':');
        return colon < 0 ? "" : rest.substring(colon + 1);
    }

    private String fieldType(String reference) {
        int colon = reference.lastIndexOf(':');
        return colon < 0 ? "" : reference.substring(colon + 1);
    }

    private boolean matches(Object target, String needle) {
        if (target instanceof Dex.EncodedMethod) {
            return ((Dex.EncodedMethod) target).reference.toLowerCase(Locale.US).contains(needle);
        }
        return ((Dex.EncodedField) target).reference.toLowerCase(Locale.US).contains(needle);
    }

    private void openMethod(Dex.EncodedMethod method) {
        Intent intent = new Intent(this, SmaliActivity.class);
        intent.putExtra(SmaliActivity.EXTRA_PATH, apk.getAbsolutePath());
        intent.putExtra(SmaliActivity.EXTRA_ENTRY, dexEntry);
        intent.putExtra(SmaliActivity.EXTRA_CLASS, className);
        intent.putExtra(SmaliActivity.EXTRA_METHOD, method.methodIdx);
        intent.putExtra(SmaliActivity.EXTRA_METHOD_REF, method.reference);
        intent.putExtra(SmaliActivity.EXTRA_TITLE, memberName(method.reference));
        startActivity(intent);
    }

    /** 查看整类 Java 源码（jadx 反编译）。 */
    private void openWholeClassJava() {
        Intent intent = new Intent(this, SmaliActivity.class);
        intent.putExtra(SmaliActivity.EXTRA_PATH, apk.getAbsolutePath());
        intent.putExtra(SmaliActivity.EXTRA_ENTRY, dexEntry);
        intent.putExtra(SmaliActivity.EXTRA_CLASS, className);
        intent.putExtra(SmaliActivity.EXTRA_METHOD, -1);
        intent.putExtra(SmaliActivity.EXTRA_LANG, SmaliActivity.LANG_JAVA);
        intent.putExtra(SmaliActivity.EXTRA_TITLE, getString(R.string.dex_java_action));
        startActivity(intent);
    }

    /** 查看整类 Smali（超大类会在详情页提示截断）。 */
    private void openWholeClass() {
        Intent intent = new Intent(this, SmaliActivity.class);
        intent.putExtra(SmaliActivity.EXTRA_PATH, apk.getAbsolutePath());
        intent.putExtra(SmaliActivity.EXTRA_ENTRY, dexEntry);
        intent.putExtra(SmaliActivity.EXTRA_CLASS, className);
        intent.putExtra(SmaliActivity.EXTRA_METHOD, -1);
        intent.putExtra(SmaliActivity.EXTRA_TITLE, getString(R.string.dex_whole_class));
        startActivity(intent);
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
