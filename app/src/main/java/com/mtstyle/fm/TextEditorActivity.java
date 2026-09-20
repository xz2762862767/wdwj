package com.mtstyle.fm;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 内置文本编辑器（对齐 MT）：可直接新建/编辑文本，支持查看与编辑模式切换、
 * UTF-8/GBK 编码识别与切换、撤销、状态栏（行列/字符），保存失败时可用系统文件选择器另存。
 */
public class TextEditorActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";
    private static final long MAX_SIZE = 5L * 1024 * 1024;
    /** 超过编辑阈值仍可用只读查看，超过该值才拒绝。 */
    private static final long MAX_VIEW_SIZE = 20L * 1024 * 1024;
    private static final int REQ_OPEN = 0x11;
    private static final int REQ_SAVE_AS = 0x12;
    private static final int UNDO_LIMIT = 40;

    private EditText editor;
    private TextView title;
    private TextView modeButton;
    private TextView charsetView;
    private TextView statusView;
    private TextView readonlyHint;

    private File file;
    private String originalText = "";
    private boolean modified;
    private boolean editMode = true;
    private boolean newFile;
    private Charset charset = Charset.forName("UTF-8");
    private final Deque<String> undoStack = new ArrayDeque<>();
    private boolean suppressing;
    private boolean forceReadOnly;
    private String pendingSaveContent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Settings.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        editor = findViewById(R.id.editor);
        title = findViewById(R.id.editor_title);
        modeButton = findViewById(R.id.editor_mode);
        charsetView = findViewById(R.id.editor_charset);
        statusView = findViewById(R.id.editor_status);
        readonlyHint = findViewById(R.id.editor_readonly_hint);

        findViewById(R.id.editor_back).setOnClickListener(v -> confirmExit());
        findViewById(R.id.editor_undo).setOnClickListener(v -> undo());
        TextView save = findViewById(R.id.editor_save);
        save.setText(R.string.editor_save);
        save.setOnClickListener(v -> save(false));
        TextView saveAs = findViewById(R.id.editor_save_as);
        saveAs.setText(R.string.editor_save_as);
        saveAs.setOnClickListener(v -> chooseSaveLocation());
        modeButton.setOnClickListener(v -> toggleMode());
        charsetView.setOnClickListener(v -> chooseCharset());
        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (!suppressing && editMode && s.length() <= 200000) {
                    pushUndo(s.toString());
                }
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                modified = !s.toString().equals(originalText);
                updateTitle();
                updateStatus();
            }
        });
        editor.setOnClickListener(v -> updateStatus());
        title.setOnLongClickListener(v -> {
            chooseOpenFile();
            return true;
        });

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || path.length() == 0) {
            // 侧滑栏直接进入：新建一个可编辑的空文本
            newFile = true;
            applyMode(true);
            originalText = "";
            updateTitle();
            updateStatus();
            editor.requestFocus();
            showKeyboard();
            return;
        }
        file = new File(path);
        if (file.exists() && !file.isFile()) {
            toast("不是文件：" + path);
            finish();
            return;
        }
        newFile = !file.exists();
        if (newFile) {
            // 路径不存在时按"新建"处理，保存时再询问是否创建
            applyMode(true);
            originalText = "";
            updateTitle();
            updateStatus();
            editor.requestFocus();
            showKeyboard();
            return;
        }
        forceReadOnly = file.length() > MAX_SIZE;
        loadFile();
        applyMode(!forceReadOnly);
        if (forceReadOnly) {
            toast(getString(R.string.editor_large_view));
        }
    }

    private void loadFile() {
        if (file.length() > MAX_VIEW_SIZE) {
            toast(getString(R.string.editor_too_large));
            finish();
            return;
        }
        try {
            byte[] data = readAll(file);
            charset = detectCharset(data);
            String text = decode(data, charset);
            suppressing = true;
            originalText = text;
            editor.setText(text);
            suppressing = false;
            modified = false;
            updateTitle();
            updateStatus();
        } catch (Exception e) {
            toast("读取失败：" + e.getMessage());
            finish();
        }
    }

    private void updateTitle() {
        String name = file != null ? file.getName() : getString(R.string.editor_untitled);
        title.setText(name + (modified ? " *" : ""));
    }

    private void updateStatus() {
        String text = editor.getText().toString();
        int line = 1;
        int column = 1;
        int selection = Math.max(0, Math.min(editor.getSelectionEnd(), text.length()));
        for (int i = 0; i < selection; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        statusView.setText(getString(R.string.editor_status, line, column, text.length(), lines));
        charsetView.setText(charset.name());
        readonlyHint.setVisibility(editMode ? View.GONE : View.VISIBLE);
    }

    private void applyMode(boolean edit) {
        editMode = edit;
        modeButton.setText(edit ? R.string.editor_mode_view : R.string.editor_mode_edit);
        editor.setCursorVisible(edit);
        editor.setFocusable(edit);
        editor.setFocusableInTouchMode(edit);
        if (edit) {
            editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else {
            editor.setInputType(android.text.InputType.TYPE_NULL);
            hideKeyboard();
        }
        updateStatus();
    }

    private void toggleMode() {
        applyMode(!editMode);
        toast(getString(editMode ? R.string.editor_mode_tip_edit : R.string.editor_mode_tip_view));
        if (editMode) {
            editor.requestFocus();
            showKeyboard();
        }
    }

    private void chooseCharset() {
        final String[] names = {"UTF-8", "GBK", "UTF-16LE", "ISO-8859-1"};
        int checked = 0;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equalsIgnoreCase(charset.name())) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_select_encoding)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    dialog.dismiss();
                    charset = Charset.forName(names[which]);
                    if (file != null && file.exists() && !newFile) {
                        loadFile();
                    } else {
                        updateStatus();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            toast(getString(R.string.editor_nothing_undo));
            return;
        }
        String previous = undoStack.pop();
        suppressing = true;
        editor.setText(previous);
        editor.setSelection(previous.length());
        suppressing = false;
        modified = !previous.equals(originalText);
        updateTitle();
        updateStatus();
    }

    private void pushUndo(String state) {
        if (undoStack.isEmpty() || !state.equals(undoStack.peek())) {
            undoStack.push(state);
            while (undoStack.size() > UNDO_LIMIT) {
                undoStack.removeLast();
            }
        }
    }

    private boolean save(boolean silent) {
        String content = editor.getText().toString();
        if (file == null) {
            pendingSaveContent = content;
            chooseSaveLocation();
            return false;
        }
        try {
            writeFile(file, content, charset);
            originalText = content;
            newFile = false;
            modified = false;
            updateTitle();
            if (!silent) {
                toast(getString(R.string.editor_saved));
            }
            return true;
        } catch (Exception e) {
            // 多为无「所有文件访问权限」导致，交给系统文件选择器另存
            toast(getString(R.string.editor_save_failed, String.valueOf(e.getMessage())));
            pendingSaveContent = content;
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.editor_save_failed, String.valueOf(e.getMessage())))
                    .setMessage(R.string.editor_save_picker)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.editor_save_as, (dialog, which) -> chooseSaveLocation())
                    .show();
            return false;
        }
    }

    /** 使用系统文件选择器选择另存位置（无需存储权限）。 */
    private void chooseSaveLocation() {
        pendingSaveContent = editor.getText().toString();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        String name = file != null ? file.getName() : getString(R.string.editor_untitled);
        intent.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(intent, REQ_SAVE_AS);
        } catch (Exception e) {
            toast(String.valueOf(e.getMessage()));
        }
    }

    /** 用系统文件选择器打开其它文本文件。 */
    private void chooseOpenFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        try {
            startActivityForResult(intent, REQ_OPEN);
        } catch (Exception e) {
            toast(String.valueOf(e.getMessage()));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_SAVE_AS) {
            writeToUri(uri, pendingSaveContent != null
                    ? pendingSaveContent : editor.getText().toString());
            return;
        }
        if (requestCode == REQ_OPEN) {
            try {
                readFromUri(uri);
            } catch (Exception e) {
                toast("读取失败：" + e.getMessage());
            }
        }
    }

    private void writeToUri(Uri uri, String content) {
        try {
            OutputStream out = getContentResolver().openOutputStream(uri, "wt");
            if (out == null) {
                throw new Exception("无法写入所选位置");
            }
            Writer writer = new OutputStreamWriter(out, charset);
            writer.write(content);
            writer.flush();
            writer.close();
            toast(getString(R.string.editor_saved_to, String.valueOf(uri.getLastPathSegment())));
            originalText = content;
            newFile = false;
            modified = false;
            updateTitle();
        } catch (Exception e) {
            toast(getString(R.string.editor_save_failed, String.valueOf(e.getMessage())));
        }
    }

    private void readFromUri(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new Exception("无法打开所选文件");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        in.close();
        byte[] data = buffer.toByteArray();
        charset = detectCharset(data);
        String text = decode(data, charset);
        suppressing = true;
        editor.setText(text);
        suppressing = false;
        originalText = text;
        modified = false;
        undoStack.clear();
        updateTitle();
        updateStatus();
    }

    private static byte[] readAll(File file) throws Exception {
        try (InputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private static void writeFile(File target, String content, Charset charset) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Exception("无法创建目录：" + parent.getAbsolutePath());
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(target), charset)) {
            writer.write(content);
            writer.flush();
        }
        if (!target.exists()) {
            throw new Exception("写入未生效（可能缺少存储权限）");
        }
    }

    /** UTF-8 严格解码失败则按 GBK，再退回 ISO-8859-1。 */
    private static Charset detectCharset(byte[] data) {
        if (hasBom(data, 0xEF, 0xBB, 0xBF)) {
            return Charset.forName("UTF-8");
        }
        if (hasBom(data, 0xFF, 0xFE)) {
            return Charset.forName("UTF-16LE");
        }
        if (hasBom(data, 0xFE, 0xFF)) {
            return Charset.forName("UTF-16BE");
        }
        if (strictDecode(data, Charset.forName("UTF-8"))) {
            return Charset.forName("UTF-8");
        }
        if (strictDecode(data, Charset.forName("GBK"))) {
            return Charset.forName("GBK");
        }
        return Charset.forName("ISO-8859-1");
    }

    private static boolean strictDecode(byte[] data, Charset charset) {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            decoder.decode(ByteBuffer.wrap(data));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String decode(byte[] data, Charset charset) {
        int offset = 0;
        if (charset.name().equals("UTF-8") && hasBom(data, 0xEF, 0xBB, 0xBF)) {
            offset = 3;
        } else if (charset.name().startsWith("UTF-16") && data.length >= 2
                && ((data[0] == (byte) 0xFF && data[1] == (byte) 0xFE)
                || (data[0] == (byte) 0xFE && data[1] == (byte) 0xFF))) {
            return new String(data, charset);
        }
        CharBuffer buffer = charset.decode(ByteBuffer.wrap(data, offset, data.length - offset));
        return buffer.toString().replace("\r\n", "\n");
    }

    private static boolean hasBom(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private void confirmExit() {
        if (!modified) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(file != null ? file.getName() : getString(R.string.editor_untitled))
                .setMessage(R.string.editor_unsaved)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.editor_save, (dialog, which) -> {
                    if (save(true)) {
                        finish();
                    }
                })
                .setPositiveButton("不保存", (dialog, which) -> finish())
                .show();
    }

    @Override
    public void onBackPressed() {
        confirmExit();
    }

    private void showKeyboard() {
        editor.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager manager =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.showSoftInput(editor,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        }, 120L);
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager manager =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
