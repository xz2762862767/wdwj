package com.mtstyle.fm;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.mtstyle.fm.apk.ApkSign;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * MT 风格「APK 分析」入口：
 * 查看签名、编辑 AndroidManifest.xml、查看资源（resources.arsc）、查看包内文件。
 * 全部为离线自研实现，不需要 root / 外部工具。
 */
public class ApkAnalysisActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "apk_analysis_path";

    private File apk;
    private SimpleRowAdapter adapter;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apk_analysis);

        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PATH);
        apk = path == null ? null : new File(path);
        if (apk == null || !apk.exists()) {
            toast(getString(R.string.analysis_missing));
            finish();
            return;
        }

        TextView title = findViewById(R.id.analysis_title);
        title.setText(apk.getName());
        statusView = findViewById(R.id.analysis_status);
        statusView.setText(Util.formatSize(apk.length()) + " · " + apk.getAbsolutePath());

        findViewById(R.id.analysis_back).setOnClickListener(v -> finish());

        adapter = new SimpleRowAdapter(this);
        ListView listView = findViewById(R.id.analysis_list);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            switch (position) {
                case 0:
                    showSignature();
                    break;
                case 1:
                    openXmlEditor();
                    break;
                case 2:
                    openResources();
                    break;
                case 3:
                    openDexViewer();
                    break;
                default:
                    openArchive();
                    break;
            }
        });
        bindRows();
    }

    private void bindRows() {
        List<SimpleRowAdapter.Row> rows = new ArrayList<>();
        rows.add(row(getString(R.string.analysis_sign), getString(R.string.analysis_sign_sub),
                R.drawable.ic_apk));
        rows.add(row(getString(R.string.analysis_xml), getString(R.string.analysis_xml_sub),
                R.drawable.ic_code));
        rows.add(row(getString(R.string.analysis_res), getString(R.string.analysis_res_sub),
                R.drawable.ic_tool));
        rows.add(row(getString(R.string.analysis_dex), getString(R.string.analysis_dex_sub),
                R.drawable.ic_code));
        rows.add(row(getString(R.string.analysis_zip), getString(R.string.analysis_zip_sub),
                R.drawable.ic_zip));
        adapter.setRows(rows);
    }

    private SimpleRowAdapter.Row row(String title, String subtitle, int iconRes) {
        SimpleRowAdapter.Row row = new SimpleRowAdapter.Row();
        row.title = title;
        row.subtitle = subtitle;
        row.iconRes = iconRes;
        return row;
    }

    /** 查看签名：v1/v2/v3 方案 + 证书 MD5 / SHA-1 / SHA-256、主题、有效期。 */
    private void showSignature() {
        final String[] result = new String[1];
        TaskRunner.run(this, getString(R.string.sign_title), false, progress -> {
            ApkSign.Info info = ApkSign.read(apk);
            result[0] = ApkSign.describe(info);
        }, (ok, message) -> {
            if (!ok) {
                toast(getString(R.string.analysis_failed, String.valueOf(message)));
                return;
            }
            showTextDialog(getString(R.string.sign_title), result[0]);
        });
    }

    private void showTextDialog(String title, String content) {
        TextView view = new TextView(this);
        view.setText(content == null ? "" : content);
        view.setTextSize(12f);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setTextColor(getResources().getColor(R.color.text_primary));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad / 2, pad, pad / 2);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(R.string.act_copy,
                        (d, w) -> copy(content == null ? "" : content))
                .setNegativeButton(R.string.ok, null)
                .show();
    }

    private void openXmlEditor() {
        Intent intent = new Intent(this, XmlEditorActivity.class);
        intent.putExtra(XmlEditorActivity.EXTRA_PATH, apk.getAbsolutePath());
        intent.putExtra(XmlEditorActivity.EXTRA_ENTRY, "AndroidManifest.xml");
        startActivity(intent);
    }

    private void openResources() {
        Intent intent = new Intent(this, ArscActivity.class);
        intent.putExtra(ArscActivity.EXTRA_PATH, apk.getAbsolutePath());
        startActivity(intent);
    }

    private void openDexViewer() {
        Intent intent = new Intent(this, DexActivity.class);
        intent.putExtra(DexActivity.EXTRA_PATH, apk.getAbsolutePath());
        startActivity(intent);
    }

    private void openArchive() {
        Intent intent = new Intent(this, ArchiveActivity.class);
        intent.putExtra(ArchiveActivity.EXTRA_PATH, apk.getAbsolutePath());
        File parent = apk.getParentFile();
        if (parent != null) {
            intent.putExtra(ArchiveActivity.EXTRA_DIR, parent.getAbsolutePath());
        }
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
