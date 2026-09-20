package com.mtstyle.fm;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.mtstyle.fm.apk.Axml;
import com.mtstyle.fm.apk.ZipEdit;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 编辑 APK（或普通 zip）内的 XML 条目：
 * 二进制 AndroidManifest.xml / res/xml 解码为文本供编辑，保存时重新编码为二进制 XML 回写。
 * 保存后原签名必然失效，会剥离 META-INF 下的旧签名文件并保留 .bak 备份。
 */
public class XmlEditorActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "xml_editor_path";
    public static final String EXTRA_ENTRY = "xml_editor_entry";

    private File archive;
    private String entryName = "AndroidManifest.xml";
    private EditText editor;
    private TextView statusView;
    private boolean binary = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_xml_editor);

        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PATH);
        String entry = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ENTRY);
        if (entry != null && entry.length() > 0) {
            entryName = entry;
        }
        archive = path == null ? null : new File(path);
        if (archive == null || !archive.exists()) {
            toast(getString(R.string.analysis_missing));
            finish();
            return;
        }

        TextView title = findViewById(R.id.xml_title);
        title.setText(getString(R.string.xml_editor_title, entryName));
        statusView = findViewById(R.id.xml_status);
        editor = findViewById(R.id.xml_editor);
        findViewById(R.id.xml_back).setOnClickListener(v -> finish());
        findViewById(R.id.xml_save).setOnClickListener(v -> confirmSave());

        loadEntry();
    }

    private void loadEntry() {
        final String[] text = new String[1];
        TaskRunner.run(this, getString(R.string.analysis_reading), false, progress -> {
            byte[] bytes = ZipEdit.readEntryOrFile(archive, entryName);
            if (bytes == null) {
                throw new Exception("找不到条目：" + entryName);
            }
            if (Axml.isBinaryXml(bytes)) {
                binary = true;
                text[0] = Axml.toText(Axml.decode(bytes));
            } else {
                binary = false;
                text[0] = new String(bytes, StandardCharsets.UTF_8);
            }
        }, (ok, message) -> {
            if (!ok) {
                toast(getString(R.string.analysis_failed, String.valueOf(message)));
                finish();
                return;
            }
            editor.setText(text[0]);
            statusView.setText(binary ? getString(R.string.xml_editor_binary)
                    : getString(R.string.xml_editor_text));
        });
    }

    private void confirmSave() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_save)
                .setMessage(R.string.xml_save_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> save())
                .show();
    }

    private void save() {
        final String text = editor.getText().toString();
        final String[] backup = new String[1];
        TaskRunner.run(this, getString(R.string.editor_save), false, progress -> {
            byte[] bytes;
            if (binary) {
                try {
                    bytes = Axml.encode(Axml.fromText(text));
                } catch (Exception e) {
                    throw new Exception(getString(R.string.xml_parse_failed, String.valueOf(e.getMessage())));
                }
            } else {
                bytes = text.getBytes(StandardCharsets.UTF_8);
            }
            File parent = archive.getAbsoluteFile().getParentFile();
            if (!ZipEdit.isZip(archive)) {
                // 已解压出来的独立 xml：没有 zip 条目可替换，直接原地写回
                File single = new File(parent, archive.getName() + ".tmp");
                File singleBak = new File(parent, archive.getName() + ".bak");
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(single)) {
                    os.write(bytes);
                }
                if (singleBak.exists() && !singleBak.delete()) {
                    single.delete();
                    throw new Exception("无法覆盖旧备份：" + singleBak.getName());
                }
                if (!archive.renameTo(singleBak) || !single.renameTo(archive)) {
                    singleBak.renameTo(archive);
                    throw new Exception("无法写入原文件");
                }
                backup[0] = singleBak.getName();
                return;
            }
            File temp = new File(parent, archive.getName() + ".tmp");
            File backupFile = new File(parent, archive.getName() + ".bak");
            if (temp.exists() && !temp.delete()) {
                throw new Exception("无法清理临时文件");
            }
            Map<String, byte[]> replacements = new HashMap<>();
            replacements.put(entryName, bytes);
            ZipEdit.replaceEntries(archive, temp, replacements);
            if (backupFile.exists() && !backupFile.delete()) {
                temp.delete();
                throw new Exception("无法覆盖旧备份：" + backupFile.getName());
            }
            if (!archive.renameTo(backupFile)) {
                temp.delete();
                throw new Exception("无法备份原文件");
            }
            if (!temp.renameTo(archive)) {
                backupFile.renameTo(archive);
                throw new Exception("无法写入原文件");
            }
            backup[0] = backupFile.getName();
        }, (ok, message) -> {
            if (ok) {
                toast(getString(R.string.xml_saved, backup[0]));
            } else {
                toast(getString(R.string.xml_save_failed, String.valueOf(message)));
            }
        });
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
    }
}
