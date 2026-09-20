package com.mtstyle.fm;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.mtstyle.fm.apk.ApkSignerV2;
import com.mtstyle.fm.apk.SigningKey;
import com.mtstyle.fm.apk.Dex;
import com.mtstyle.fm.apk.DexMethodPatcher;
import com.mtstyle.fm.apk.JavaDecompiler;
import com.mtstyle.fm.apk.ZipEdit;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Smali 字节码查看：展示单个方法或整类的 Dalvik 反汇编文本，支持页内查找高亮与复制。
 * 超大内容仅渲染前 N 个字符（复制仍为完整内容），避免一次性布局导致卡顿。
 */
public class SmaliActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "smali_apk_path";
    public static final String EXTRA_ENTRY = "smali_dex_entry";
    public static final String EXTRA_CLASS = "smali_class";
    /** 方法索引，-1 表示整类。 */
    public static final String EXTRA_METHOD = "smali_method";
    public static final String EXTRA_TITLE = "smali_title";
    /** 代码语言：smali（默认）或 java。 */
    public static final String EXTRA_LANG = "smali_lang";
    /** 方法引用串（Lcls;->name(desc)ret），用于 Java 模式直接定位方法。 */
    public static final String EXTRA_METHOD_REF = "smali_method_ref";
    public static final String LANG_SMALI = "smali";
    public static final String LANG_JAVA = "java";

    private static final int MAX_DISPLAY = 400_000;
    private static final int MAX_MATCHES = 500;
    private static final int MAX_COPY = 2_000_000;

    private File apk;
    private String dexEntry;
    private String className;
    private int methodIdx;
    /** 不可编辑的原因（如 abstract/native 方法没有代码），为 null 表示可编辑。 */
    private String editDisabledReason;
    private String lang = LANG_SMALI;
    private String methodRef;
    private TextView langButton;

    private TextView contentView;
    private ScrollView scrollView;
    private TextView statusView;
    private String fullText = "";
    private String displayText = "";
    private CharSequence highlighted;
    private int[] palette;
    private boolean truncated;
    private String subTitle = "";

    private static final String PREF_SCALE = "code_text_scale";
    private static final String STATE_EDITING = "smali_editing";
    private static final String STATE_EDIT_TEXT = "smali_edit_text";
    private static final String STATE_DIRTY = "smali_dirty";

    private PinchZoom viewZoom;
    private PinchZoom editZoom;
    private float textScale = PinchZoom.DEFAULT_SCALE;
    private String pendingEditText;
    private boolean pendingDirty;

    private TextView editButton;
    private EditText editor;
    private boolean editing;
    private boolean dirty;
    private boolean usedFallback;

    private final List<Integer> matches = new ArrayList<>();
    private int currentMatch = -1;
    private String needle = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Settings.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smali);

        Intent intent = getIntent();
        String path = intent == null ? null : intent.getStringExtra(EXTRA_PATH);
        dexEntry = intent == null ? null : intent.getStringExtra(EXTRA_ENTRY);
        className = intent == null ? null : intent.getStringExtra(EXTRA_CLASS);
        methodIdx = intent == null ? -1 : intent.getIntExtra(EXTRA_METHOD, -1);
        methodRef = intent == null ? null : intent.getStringExtra(EXTRA_METHOD_REF);
        String title = intent == null ? null : intent.getStringExtra(EXTRA_TITLE);
        apk = path == null ? null : new File(path);
        if (apk == null || !apk.exists() || className == null) {
            toast(getString(R.string.analysis_missing));
            finish();
            return;
        }
        if (dexEntry == null) {
            dexEntry = "classes.dex";
        }
        lang = intent == null ? LANG_SMALI : intent.getStringExtra(EXTRA_LANG);
        if (!LANG_JAVA.equals(lang)) {
            lang = LANG_SMALI;
        }

        TextView toolbar = findViewById(R.id.smali_title);
        toolbar.setText(title == null ? getString(R.string.dex_smali_title) : title);
        langButton = findViewById(R.id.smali_lang);
        updateLangButton();
        langButton.setOnClickListener(v -> {
            lang = LANG_JAVA.equals(lang) ? LANG_SMALI : LANG_JAVA;
            updateLangButton();
            load();
        });
        contentView = findViewById(R.id.smali_text);
        contentView.setTypeface(Typeface.MONOSPACE);
        editor = findViewById(R.id.smali_editor);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (editing) {
                    dirty = true;
                    updateStatus();
                }
            }
        });
        editButton = findViewById(R.id.smali_edit);
        editButton.setOnClickListener(v -> onEditClicked());
        textScale = Settings.prefs(this).getFloat(PREF_SCALE, PinchZoom.DEFAULT_SCALE);
        viewZoom = new PinchZoom(this, contentView, textScale, this::onZoomChanged);
        editZoom = new PinchZoom(this, editor, textScale, this::onZoomChanged);
        scrollView = findViewById(R.id.smali_scroll);
        statusView = findViewById(R.id.smali_status);

        findViewById(R.id.smali_back).setOnClickListener(v -> handleBack());
        findViewById(R.id.smali_copy).setOnClickListener(v -> copyAll());
        findViewById(R.id.smali_next).setOnClickListener(v -> runSearch(needle, true));

        EditText search = findViewById(R.id.smali_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                runSearch(s == null ? "" : s.toString(), false);
            }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
            if (enter) {
                runSearch(needle, true);
                return true;
            }
            return false;
        });

        load();
    }

    /** 切换按钮显示的是「切过去」的语言。 */
    private void updateLangButton() {
        if (langButton == null) {
            return;
        }
        if (editing) {
            exitEdit();
        }
        if (editButton != null) {
            editButton.setVisibility(LANG_SMALI.equals(lang) ? android.view.View.VISIBLE
                    : android.view.View.GONE);
        }
        langButton.setText(LANG_JAVA.equals(lang)
                ? getString(R.string.java_switch_to_smali)
                : getString(R.string.java_switch_to_java));
    }

    private void load() {
        boolean java = LANG_JAVA.equals(lang);
        contentView.setText(java ? getString(R.string.java_reading, dexEntry)
                : getString(R.string.dex_reading, dexEntry));
        statusView.setText("");
        new Thread(() -> {
            String text = null;
            String sub = null;
            String error = null;
            try {
                editDisabledReason = null;
                Dex local = DexActivity.sharedDex;
                Dex.ClassDef def = null;
                Dex.EncodedMethod target = null;
                String ref = methodRef;
                // Java 模式的「整类」与「带引用串的方法」不需要自研解析器
                boolean needDex = !java || (methodIdx >= 0 && ref == null);
                if (needDex) {
                    String key = DexActivity.key(apk, dexEntry);
                    if (local == null || !key.equals(DexActivity.sharedKey)) {
                        byte[] bytes = ZipEdit.readEntry(apk, dexEntry);
                        if (bytes == null) {
                            throw new Exception(getString(R.string.dex_missing, dexEntry));
                        }
                        local = Dex.parse(bytes);
                        DexActivity.sharedDex = local;
                        DexActivity.sharedKey = key;
                    }
                    for (Dex.ClassDef candidate : local.classes) {
                        if (candidate.name.equals(className)) {
                            def = candidate;
                            break;
                        }
                    }
                    if (def == null) {
                        throw new Exception(getString(R.string.dex_class_missing, className));
                    }
                    if (methodIdx >= 0) {
                        for (Dex.EncodedMethod method : def.allMethods()) {
                            if (method.methodIdx == methodIdx) {
                                target = method;
                                break;
                            }
                        }
                        if (target == null) {
                            throw new Exception(getString(R.string.dex_method_missing));
                        }
                        if (ref == null) {
                            ref = target.reference;
                        }
                        if (local.code(target) == null) {
                            // abstract/native 方法没有代码，不允许编辑
                            editDisabledReason = getString(R.string.smali_edit_no_code);
                        }
                    }
                }
                if (java) {
                    sub = (ref == null ? className : ref) + " · "
                            + getString(R.string.dex_java_title) + " · "
                            + getString(R.string.java_engine_note);
                    if (ref == null) {
                        text = JavaDecompiler.decompileClass(SmaliActivity.this, apk, dexEntry, className);
                    } else {
                        String shortId = ref.substring(ref.indexOf("->") + 2);
                        text = JavaDecompiler.decompileMethod(SmaliActivity.this, apk, dexEntry,
                                className, shortId);
                        if (text == null || text.isEmpty()) {
                            throw new Exception(getString(R.string.java_method_missing));
                        }
                    }
                } else if (target == null) {
                    text = local.smali(def);
                    sub = def.name + " · " + getString(R.string.dex_whole_class) + " · "
                            + getString(R.string.dex_members, def.allMethods().size(),
                            def.staticFields.size() + def.instanceFields.size());
                } else {
                    text = local.smaliMethod(target);
                    sub = target.reference;
                }
            } catch (OutOfMemoryError oom) {
                JavaDecompiler.release();
                error = getString(R.string.java_oom);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String finalText = text;
            final String finalSub = sub;
            final String finalError = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (finalError != null) {
                    contentView.setText(finalError);
                    statusView.setText("");
                    toast(getString(R.string.analysis_failed, finalError));
                    return;
                }
                subTitle = finalSub == null ? "" : finalSub;
                fullText = finalText == null ? "" : finalText;
                truncated = fullText.length() > MAX_DISPLAY;
                displayText = truncated ? fullText.substring(0, MAX_DISPLAY) : fullText;
                highlighted = highlight(displayText, LANG_JAVA.equals(lang));
                updateEditButton();
                runSearch(needle, false);
                if (pendingEditText != null) {
                    editing = true;
                    editor.setText(pendingEditText);
                    dirty = pendingDirty;
                    pendingEditText = null;
                    editor.setVisibility(android.view.View.VISIBLE);
                    scrollView.setVisibility(android.view.View.GONE);
                    updateEditButton();
                    updateStatus();
                }
            });
        }, "fm-smali").start();
    }

    /** 页内查找：高亮全部匹配并把当前匹配滚动到可见位置。 */
    private void runSearch(String query, boolean next) {
        if (displayText == null || displayText.isEmpty()) {
            return;
        }
        needle = query == null ? "" : query;
        int previousMatch = currentMatch;
        matches.clear();
        currentMatch = -1;
        if (needle.length() > 0) {
            String lower = displayText.toLowerCase(Locale.US);
            String target = needle.toLowerCase(Locale.US);
            int index = lower.indexOf(target);
            while (index >= 0 && matches.size() < MAX_MATCHES) {
                matches.add(index);
                index = lower.indexOf(target, index + target.length());
            }
        }
        if (!matches.isEmpty()) {
            currentMatch = next ? (previousMatch + 1) % matches.size() : 0;
        }
        applySpans();
        updateStatus();
        scrollToCurrent();
    }

    /** 语法高亮：把分词结果变成前景色 span（搜索结果的前景/背景色叠加其上）。 */
    private CharSequence highlight(String text, boolean java) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int[] tokens = CodeHighlighter.tokenize(text, java, CodeHighlighter.MAX_TOKENS);
        if (tokens.length == 0) {
            return text;
        }
        SpannableString span = new SpannableString(text);
        int[] palette = codePalette();
        for (int i = 0; i + 2 < tokens.length; i += 3) {
            int type = tokens[i + 2];
            if (type < 0 || type >= palette.length) {
                continue;
            }
            span.setSpan(new ForegroundColorSpan(palette[type]), tokens[i], tokens[i + 1],
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }

    /** 语法配色（日/夜主题各一套，来自资源）。 */
    private int[] codePalette() {
        if (palette == null) {
            int[] colors = new int[CodeHighlighter.TYPE_COUNT];
            colors[CodeHighlighter.T_KEYWORD] = getResources().getColor(R.color.code_keyword);
            colors[CodeHighlighter.T_OPCODE] = getResources().getColor(R.color.code_opcode);
            colors[CodeHighlighter.T_STRING] = getResources().getColor(R.color.code_string);
            colors[CodeHighlighter.T_COMMENT] = getResources().getColor(R.color.code_comment);
            colors[CodeHighlighter.T_NUMBER] = getResources().getColor(R.color.code_number);
            colors[CodeHighlighter.T_TYPE] = getResources().getColor(R.color.code_type);
            colors[CodeHighlighter.T_FUNC] = getResources().getColor(R.color.code_func);
            colors[CodeHighlighter.T_LABEL] = getResources().getColor(R.color.code_label);
            colors[CodeHighlighter.T_REGISTER] = getResources().getColor(R.color.code_register);
            palette = colors;
        }
        return palette;
    }

    private void applySpans() {
        SpannableString span = new SpannableString(highlighted != null ? highlighted : displayText);
        int colorSoft = getResources().getColor(R.color.accent_dim);
        int colorStrong = getResources().getColor(R.color.accent_soft);
        for (int i = 0; i < matches.size(); i++) {
            int start = matches.get(i);
            int end = Math.min(displayText.length(), start + needle.length());
            if (end <= start) {
                continue;
            }
            span.setSpan(new BackgroundColorSpan(i == currentMatch ? colorStrong : colorSoft),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        contentView.setText(span);
    }

    private void updateStatus() {
        if (statusView == null) {
            return;
        }
        String current = editing ? editor.getText().toString() : displayText;
        StringBuilder builder = new StringBuilder(64);
        builder.append(getString(R.string.dex_smali_size, lineCount(current), current.length()));
        if (!editing && truncated) {
            builder.append(" · ").append(getString(R.string.dex_smali_truncated, MAX_DISPLAY));
        }
        builder.append(" · ").append(getString(R.string.smali_zoom, Math.round(textScale * 100)));
        if (!editing && needle.length() > 0) {
            if (matches.isEmpty()) {
                builder.append(" · ").append(getString(R.string.dex_find_none));
            } else {
                builder.append(" · ").append(getString(R.string.dex_find_count,
                        currentMatch + 1, matches.size()));
            }
        }
        if (subTitle.length() > 0) {
            builder.append('\n').append(subTitle);
        }
        statusView.setText(builder.toString());
        statusView.setSingleLine(false);
        statusView.setMaxLines(3);
    }

    private int lineCount(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private void scrollToCurrent() {
        if (currentMatch < 0 || currentMatch >= matches.size()) {
            return;
        }
        final int offset = matches.get(currentMatch);
        contentView.post(() -> {
            Layout layout = contentView.getLayout();
            if (layout == null) {
                return;
            }
            int line = layout.getLineForOffset(offset);
            int y = layout.getLineTop(line);
            scrollView.scrollTo(0, Math.max(0, y - scrollView.getHeight() / 3));
        });
    }

    private void copyAll() {
        if (fullText.isEmpty()) {
            return;
        }
        if (fullText.length() > MAX_COPY) {
            toast(getString(R.string.dex_copy_too_large));
            return;
        }
        if (fullText.length() > 200_000) {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.dex_copy_confirm, fullText.length() / 1024))
                    .setPositiveButton(R.string.ok, (d, w) -> copy(fullText))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        copy(fullText);
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

    // ---------- Smali 编辑与写回 ----------

    private void updateEditButton() {
        if (editButton == null) {
            return;
        }
        editButton.setVisibility(LANG_SMALI.equals(lang) ? android.view.View.VISIBLE
                : android.view.View.GONE);
        editButton.setText(editing ? getString(R.string.smali_edit_save)
                : getString(R.string.smali_edit));
        boolean editable = editing || editDisabledReason == null;
        editButton.setEnabled(editable);
        editButton.setAlpha(editable ? 1f : 0.45f);
        android.view.View langView = findViewById(R.id.smali_lang);
        if (langView != null) {
            langView.setVisibility(editing ? android.view.View.GONE : android.view.View.VISIBLE);
        }
        android.view.View copyView = findViewById(R.id.smali_copy);
        if (copyView != null) {
            copyView.setVisibility(editing ? android.view.View.GONE : android.view.View.VISIBLE);
        }
        android.view.View searchRow = findViewById(R.id.smali_search_row);
        if (searchRow != null) {
            searchRow.setVisibility(editing ? android.view.View.GONE : android.view.View.VISIBLE);
        }
    }

    private void onEditClicked() {
        if (editing) {
            if (!dirty) {
                exitEdit();
                toast(getString(R.string.smali_edit_exit));
                return;
            }
            promptWriteBack(editor.getText().toString());
            return;
        }
        if (!LANG_SMALI.equals(lang)) {
            toast(getString(R.string.smali_edit_java_mode));
            return;
        }
        if (methodIdx < 0) {
            toast(getString(R.string.smali_edit_only_method));
            return;
        }
        if (editDisabledReason != null) {
            toast(editDisabledReason);
            return;
        }
        if (truncated) {
            toast(getString(R.string.smali_edit_truncated));
            return;
        }
        if (fullText.isEmpty()) {
            return;
        }
        editing = true;
        editor.setText(fullText);
        dirty = false;
        editor.setVisibility(android.view.View.VISIBLE);
        scrollView.setVisibility(android.view.View.GONE);
        updateEditButton();
    }

    /** 双指缩放回调：查看与编辑同步字号，结束时持久化。 */
    private void onZoomChanged(float scale, boolean finished) {
        textScale = scale;
        if (viewZoom != null && Math.abs(viewZoom.getScale() - scale) > 0.0001f) {
            viewZoom.setScale(scale, false);
        }
        if (editZoom != null && Math.abs(editZoom.getScale() - scale) > 0.0001f) {
            editZoom.setScale(scale, false);
        }
        updateStatus();
        if (finished) {
            Settings.prefs(this).edit().putFloat(PREF_SCALE, scale).apply();
        }
    }

    /** 返回：编辑中先确认，避免误丢修改。 */
    private void handleBack() {
        if (editing) {
            if (dirty) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.smali_edit_confirm_title)
                        .setMessage(R.string.smali_edit_discard_msg)
                        .setPositiveButton(R.string.smali_edit_discard, (d, w) -> {
                            exitEdit();
                            finish();
                        })
                        .setNegativeButton(R.string.smali_edit_keep, null)
                        .show();
            } else {
                exitEdit();
                finish();
            }
            return;
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (editing) {
            outState.putBoolean(STATE_EDITING, true);
            outState.putString(STATE_EDIT_TEXT, editor.getText().toString());
            outState.putBoolean(STATE_DIRTY, dirty);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        if (state != null && state.getBoolean(STATE_EDITING)) {
            pendingEditText = state.getString(STATE_EDIT_TEXT);
            pendingDirty = state.getBoolean(STATE_DIRTY);
        }
    }

    private void exitEdit() {
        editing = false;
        dirty = false;
        editor.setVisibility(android.view.View.GONE);
        scrollView.setVisibility(android.view.View.VISIBLE);
        updateEditButton();
    }

    /** 输出文件：优先原 APK 同目录，不可写时落到应用外部目录。 */
    private File pickOutput() {
        String name = apk.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String outName = base + "_edit.apk";
        File parent = apk.getParentFile();
        usedFallback = false;
        if (parent != null && parent.canWrite()) {
            File candidate = new File(parent, outName);
            if (!candidate.exists() || candidate.canWrite()) {
                return candidate;
            }
        }
        usedFallback = true;
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            dir = getFilesDir();
        }
        return new File(dir, outName);
    }

    private void promptWriteBack(final String text) {
        final File target = pickOutput();
        new AlertDialog.Builder(this)
                .setTitle(R.string.smali_edit_confirm_title)
                .setMessage(getString(R.string.smali_edit_confirm, target.getAbsolutePath()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.smali_edit_ok, (d, w) -> writeBack(text, target))
                .show();
    }

    private void writeBack(final String text, final File target) {
        if (usedFallback) {
            toast(getString(R.string.smali_edit_no_space));
        }
        TaskRunner.run(this, getString(R.string.smali_edit_working), false, progress -> {
            progress.publish(getString(R.string.smali_edit_read_dex, dexEntry), -1);
            byte[] dexBytes = ZipEdit.readEntry(apk, dexEntry);
            if (dexBytes == null) {
                throw new Exception(getString(R.string.dex_missing, dexEntry));
            }
            Dex parsed = Dex.parse(dexBytes);
            Dex.ClassDef def = null;
            for (Dex.ClassDef candidate : parsed.classes) {
                if (candidate.name.equals(className)) {
                    def = candidate;
                    break;
                }
            }
            if (def == null) {
                throw new Exception(getString(R.string.dex_class_missing, className));
            }
            Dex.EncodedMethod targetMethod = null;
            for (Dex.EncodedMethod method : def.allMethods()) {
                if (method.methodIdx == methodIdx) {
                    targetMethod = method;
                    break;
                }
            }
            if (targetMethod == null) {
                throw new Exception(getString(R.string.dex_method_missing));
            }
            progress.publish(getString(R.string.smali_edit_assemble), -1);
            byte[] patched = DexMethodPatcher.patchText(dexBytes, parsed, targetMethod, text);
            DexMethodPatcher.refreshDigests(patched);
            if (!DexMethodPatcher.digestsValid(patched)) {
                throw new Exception(getString(R.string.smali_edit_digest_bad));
            }
            progress.publish(getString(R.string.smali_edit_repack), -1);
            File temp = File.createTempFile("repack", ".apk", getCacheDir());
            try {
                Map<String, byte[]> replacements = new HashMap<>();
                replacements.put(dexEntry, patched);
                ZipEdit.replaceEntries(apk, temp, replacements);
                progress.publish(getString(R.string.smali_edit_sign), -1);
                // 签名密钥在本机首次使用时生成，仓库与 APK 中都不内置私钥
                SigningKey.Material key = SigningKey.load(this);
                ApkSignerV2.signFile(temp, target, key.pkcs8, key.certDer);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }, (ok, message) -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (!ok) {
                toast(getString(R.string.smali_edit_failed, message == null ? "" : message));
                return;
            }
            dirty = false;
            DexActivity.sharedDex = null;
            DexActivity.sharedKey = null;
            toast(getString(R.string.smali_edit_done, target.getAbsolutePath()));
            new AlertDialog.Builder(this)
                    .setTitle(R.string.smali_edit_confirm_title)
                    .setMessage(getString(R.string.smali_edit_done, target.getAbsolutePath()))
                    .setPositiveButton(R.string.smali_edit_install,
                            (d, w) -> Installer.install(this, target))
                    .setNegativeButton(R.string.smali_edit_finish, null)
                    .show();
        });
    }


    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }
}
