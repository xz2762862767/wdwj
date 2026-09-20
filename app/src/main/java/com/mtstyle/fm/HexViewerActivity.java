package com.mtstyle.fm;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * 十六进制查看器：每行 16 字节，左侧文件偏移、中间 HEX、右侧 ASCII。
 *
 * <p>在此之前二进制文件（so / bin / 签名块等）一律丢给文本编辑器，屏幕上只剩零星几个
 * 可打印字符和满屏替换符，等于看不了。换成真正的 hex 视图后，至少能读魔数、长度字段、
 * 资源名这类字符串常量。</p>
 *
 * <p>一次只渲染一页（16KB），超大文件靠上一页 / 下一页翻，不会把内存吃光。</p>
 */
public class HexViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "hex_path";

    /** 每行字节数。 */
    private static final int COLS = 16;
    /** 一页行数，16 × 1024 = 16KB。 */
    private static final int PAGE_ROWS = 1024;

    private File file;
    private TextView textView;
    private TextView statusView;
    private TextView prevView;
    private TextView nextView;
    private long offset;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Settings.applyTheme(this);
        setContentView(R.layout.activity_hex_viewer);

        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PATH);
        file = path == null ? null : new File(path);
        if (file == null || !file.isFile()) {
            toast(getString(R.string.analysis_missing));
            finish();
            return;
        }

        TextView title = findViewById(R.id.hex_title);
        title.setText(getString(R.string.hex_title, file.getName()));
        textView = findViewById(R.id.hex_text);
        statusView = findViewById(R.id.hex_status);
        prevView = findViewById(R.id.hex_prev);
        nextView = findViewById(R.id.hex_next);
        findViewById(R.id.hex_back).setOnClickListener(v -> finish());

        long page = (long) COLS * PAGE_ROWS;
        prevView.setOnClickListener(v -> {
            offset = Math.max(0L, offset - page);
            render();
        });
        nextView.setOnClickListener(v -> {
            offset += page;
            render();
        });
        render();
    }

    private void render() {
        long total = file.length();
        long page = (long) COLS * PAGE_ROWS;
        if (offset > 0 && offset >= total) {
            // 翻过头时退回到最后一页
            offset = Math.max(0L, (total - 1) / page * page);
        }
        byte[] buffer = new byte[COLS * PAGE_ROWS];
        int read;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            read = raf.read(buffer);
        } catch (Exception e) {
            toast(getString(R.string.analysis_failed, String.valueOf(e.getMessage())));
            finish();
            return;
        }
        if (read < 0) {
            read = 0;
        }

        StringBuilder sb = new StringBuilder(read / COLS * 78 + 64);
        for (int i = 0; i < read; i += COLS) {
            sb.append(String.format(Locale.ROOT, "%08X  ", offset + i));
            for (int j = 0; j < COLS; j++) {
                if (i + j < read) {
                    sb.append(String.format(Locale.ROOT, "%02X ", buffer[i + j] & 0xFF));
                } else {
                    sb.append("   ");
                }
                if (j == COLS / 2 - 1) {
                    sb.append(' ');
                }
            }
            sb.append(" |");
            for (int j = 0; j < COLS && i + j < read; j++) {
                int c = buffer[i + j] & 0xFF;
                sb.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
            }
            sb.append("|\n");
        }
        if (read == 0) {
            sb.append(getString(R.string.hex_empty));
        }
        textView.setText(sb);

        long pageNo = offset / page + 1;
        long pageCount = Math.max(1L, (total + page - 1) / page);
        statusView.setText(getString(R.string.hex_status, Util.formatSize(total),
                Util.formatSize(offset), pageNo, pageCount, read));
        prevView.setEnabled(offset > 0);
        nextView.setEnabled(offset + read < total);
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
    }
}
