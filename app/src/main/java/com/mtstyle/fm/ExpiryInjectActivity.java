package com.mtstyle.fm;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v5.9.44：注入到期时间。
 *
 * <p>选一个未加固的 APK → 设定到期时刻 → 把一段守卫代码（{@code assets/expiry_guard.dex}）
 * 合并进 APK，并在其 Application 子类的 {@code attachBaseContext} 开头插入调用，
 * 最后用内置密钥重新签名。到期后该 App 启动即弹窗并退出。</p>
 *
 * <p>注意：重新签名后与原应用签名不同，安装前需先卸载原应用；已加壳的 APK 无法注入。</p>
 */
public class ExpiryInjectActivity extends AppCompatActivity {

    /** 外部（如 AppListActivity / 文件管理器）直接带一个 APK 进来。 */
    public static final String EXTRA_APK = "expiry_apk";

    private static final int REQ_PICK_APK = 0x5E11;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA);
    private static final SimpleDateFormat SHOW =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);

    private final Calendar expiry = Calendar.getInstance();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private File srcApk;
    private File outputApk;
    private File outDir;
    private volatile boolean running;

    private View stageSetup;
    private View stageRunning;
    private View stageResult;
    private TextView tvApkPath;
    private TextView tvExpiry;
    private TextView tvOutDir;
    private TextView tvStep;
    private TextView tvRunSub;
    private TextView tvPercent;
    private TextView tvElapsed;
    private long runStart;

    /** 进度页计时器：每 200ms 刷新一次“已用 x.x s”。 */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (tvElapsed != null) {
                tvElapsed.setText(getString(R.string.expiry_elapsed,
                        String.format(Locale.ROOT, "%.1f s",
                                (System.currentTimeMillis() - runStart) / 1000.0)));
            }
            UI.postDelayed(this, 200);
        }
    };
    private TextView tvResultTitle;
    private TextView tvResultDetail;
    private ProgressBar progressBar;
    private EditText etMessage;
    private EditText etQq;
    private EditText etGroup;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_expiry_inject);

        stageSetup = findViewById(R.id.stage_setup);
        stageRunning = findViewById(R.id.stage_running);
        stageResult = findViewById(R.id.stage_result);
        tvApkPath = findViewById(R.id.apk_path);
        tvExpiry = findViewById(R.id.expiry_value);
        tvOutDir = findViewById(R.id.out_dir);
        tvStep = findViewById(R.id.run_step);
        tvRunSub = findViewById(R.id.run_sub);
        tvPercent = findViewById(R.id.run_percent);
        tvElapsed = findViewById(R.id.run_elapsed);
        tvResultTitle = findViewById(R.id.result_title);
        tvResultDetail = findViewById(R.id.result_detail);
        progressBar = findViewById(R.id.progress);
        etMessage = findViewById(R.id.message_input);
        etQq = findViewById(R.id.qq_input);
        etGroup = findViewById(R.id.group_input);
        // 联系方式填一次就记住，下次不用重填
        prefs = getSharedPreferences("expiry_inject", MODE_PRIVATE);
        etQq.setText(prefs.getString("qq", ""));
        etGroup.setText(prefs.getString("group", ""));

        outDir = resolveOutDir();
        tvOutDir.setText(outDir.getAbsolutePath());

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btn_pick_file).setOnClickListener(v -> browseApk());
        findViewById(R.id.btn_pick_installed).setOnClickListener(v -> pickInstalledApp());
        findViewById(R.id.btn_pick_date).setOnClickListener(v -> pickDate());
        findViewById(R.id.btn_pick_time).setOnClickListener(v -> pickTime());
        findViewById(R.id.btn_q_m1).setOnClickListener(v -> quickMinutes(1));
        findViewById(R.id.btn_q_m5).setOnClickListener(v -> quickMinutes(5));
        findViewById(R.id.btn_q_m10).setOnClickListener(v -> quickMinutes(10));
        findViewById(R.id.btn_q_m30).setOnClickListener(v -> quickMinutes(30));
        findViewById(R.id.btn_quick_1).setOnClickListener(v -> quickSet(1));
        findViewById(R.id.btn_quick_7).setOnClickListener(v -> quickSet(7));
        findViewById(R.id.btn_quick_30).setOnClickListener(v -> quickSet(30));
        findViewById(R.id.btn_quick_1y).setOnClickListener(v -> quickSet(365));
        findViewById(R.id.btn_start).setOnClickListener(v -> startInject());
        findViewById(R.id.btn_install).setOnClickListener(v -> {
            if (outputApk != null && outputApk.isFile()) {
                Installer.install(this, outputApk);
            }
        });
        findViewById(R.id.btn_open_dir).setOnClickListener(v -> openOutDir());
        findViewById(R.id.btn_again).setOnClickListener(v -> {
            srcApk = null;
            outputApk = null;
            tvApkPath.setText(R.string.expiry_no_apk);
            showStage(0);
        });

        // 默认 7 天后到期
        quickSet(7);
        etMessage.setHint(R.string.expiry_message_hint);

        String extra = getIntent().getStringExtra(EXTRA_APK);
        if (extra != null && !extra.isEmpty()) {
            File f = new File(extra);
            if (f.isFile()) {
                setSource(f);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (running) {
            Toast.makeText(this, R.string.expiry_writing, Toast.LENGTH_SHORT).show();
            return;
        }
        if (stageResult != null && stageResult.getVisibility() == View.VISIBLE) {
            showStage(0);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ 来源

    /** 文件选择器：拿到的 Uri 统一复制到私有缓存，避免后续读不到。 */
    private void browseApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(Installer.MIME_APK);
        try {
            startActivityForResult(intent, REQ_PICK_APK);
        } catch (Throwable t) {
            toast(getString(R.string.dump_apk_unreadable));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_APK || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        final File dest = new File(getCacheDir(), "expiry-input.apk");
        TaskRunner.run(this, getString(R.string.expiry_pick_apk), true,
                progress -> {
                    progress.publish("正在读取安装包…", 5);
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         OutputStream out = new FileOutputStream(dest)) {
                        if (in == null) {
                            throw new java.io.IOException("无法打开文件");
                        }
                        byte[] buffer = new byte[256 * 1024];
                        long total = 0L;
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                            total += read;
                            progress.publish("正在读取安装包… " + Util.formatSize(total), -1);
                        }
                        out.flush();
                    }
                    progress.publish("完成", 100);
                },
                (ok, message) -> {
                    if (!ok && dest.isFile()) {
                        //noinspection ResultOfMethodCallIgnored
                        dest.delete();
                    }
                    if (ok && dest.isFile() && dest.length() > 0) {
                        setSource(dest);
                    } else {
                        toast(getString(R.string.dump_apk_unreadable));
                    }
                });
    }

    /** 已安装应用选择器：带图标 + 名称 + 包名，支持搜索过滤。 */
    private void pickInstalledApp() {
        final PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved;
        try {
            resolved = pm.queryIntentActivities(probe, 0);
        } catch (Throwable t) {
            resolved = new ArrayList<>();
        }
        final List<ApplicationInfo> apps = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<String> pkgs = new ArrayList<>();
        final HashSet<String> seen = new HashSet<>();
        for (ResolveInfo ri : resolved) {
            if (ri == null || ri.activityInfo == null) {
                continue;
            }
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || !seen.add(pkg)) {
                continue;
            }
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if (ai.sourceDir == null || !new File(ai.sourceDir).isFile()) {
                    continue;
                }
                apps.add(ai);
                CharSequence label = ai.loadLabel(pm);
                labels.add(label == null ? pkg : label.toString());
                pkgs.add(pkg);
            } catch (Throwable ignored) {
            }
        }
        if (apps.isEmpty()) {
            toast(getString(R.string.expiry_no_apps));
            return;
        }
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < apps.size(); i++) {
            order.add(i);
        }
        Collections.sort(order, (a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_app_picker, null);
        final EditText search = view.findViewById(R.id.app_search);
        final TextView count = view.findViewById(R.id.app_count);
        final ListView list = view.findViewById(R.id.app_list);
        final List<Integer> shown = new ArrayList<>(order);
        final AppPickAdapter adapter = new AppPickAdapter(apps, labels, pkgs, shown);
        list.setAdapter(adapter);
        count.setText(getString(R.string.expiry_app_count, order.size()));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.expiry_app_list)
                .setView(view)
                .setNegativeButton(R.string.act_cancel, null)
                .create();

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                // 不需要
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                // 不需要
            }

            @Override
            public void afterTextChanged(Editable s) {
                String q = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                shown.clear();
                for (Integer i : order) {
                    if (q.isEmpty()
                            || labels.get(i).toLowerCase(Locale.ROOT).contains(q)
                            || pkgs.get(i).toLowerCase(Locale.ROOT).contains(q)) {
                        shown.add(i);
                    }
                }
                adapter.notifyDataSetChanged();
                count.setText(q.isEmpty()
                        ? getString(R.string.expiry_app_count, order.size())
                        : getString(R.string.expiry_app_match, shown.size(), order.size()));
            }
        });

        list.setOnItemClickListener((parent, v, position, id) -> {
            int idx = shown.get(position);
            File apk = new File(apps.get(idx).sourceDir);
            dialog.dismiss();
            if (apk.isFile()) {
                setSource(apk);
            } else {
                toast(getString(R.string.dump_apk_unreadable));
            }
        });
        dialog.show();
    }

    /** 应用列表适配器：图标 + 名称 + 包名（图标按需加载并缓存）。 */
    private final class AppPickAdapter extends BaseAdapter {
        private final List<ApplicationInfo> apps;
        private final List<String> labels;
        private final List<String> pkgs;
        private final List<Integer> shown;
        private final PackageManager pm = getPackageManager();
        private final Map<String, Drawable> iconCache = new HashMap<>();

        AppPickAdapter(List<ApplicationInfo> apps, List<String> labels, List<String> pkgs,
                       List<Integer> shown) {
            this.apps = apps;
            this.labels = labels;
            this.pkgs = pkgs;
            this.shown = shown;
        }

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return apps.get(shown.get(position));
        }

        @Override
        public long getItemId(int position) {
            return shown.get(position);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(ExpiryInjectActivity.this)
                        .inflate(R.layout.item_app_pick, parent, false);
            }
            int idx = shown.get(position);
            ApplicationInfo ai = apps.get(idx);
            ((TextView) v.findViewById(R.id.ai_label)).setText(labels.get(idx));
            ((TextView) v.findViewById(R.id.ai_pkg)).setText(pkgs.get(idx));
            ImageView icon = v.findViewById(R.id.ai_icon);
            Drawable d = iconCache.get(pkgs.get(idx));
            if (d == null) {
                try {
                    d = ai.loadIcon(pm);
                } catch (Throwable ignored) {
                    d = null;
                }
                iconCache.put(pkgs.get(idx), d);
            }
            icon.setImageDrawable(d);
            return v;
        }
    }

    private void setSource(File file) {
        srcApk = file;
        String size = Util.formatSize(file.length());
        tvApkPath.setText(file.getName() + "\n" + file.getAbsolutePath() + " · " + size);
    }

    // ------------------------------------------------------------------ 时间

    private void pickDate() {
        new DatePickerDialog(this, (view, year, month, day) -> {
            Calendar old = (Calendar) expiry.clone();
            expiry.set(Calendar.YEAR, year);
            expiry.set(Calendar.MONTH, month);
            expiry.set(Calendar.DAY_OF_MONTH, day);
            keepDayValid(old);
            refreshExpiry();
        }, expiry.get(Calendar.YEAR), expiry.get(Calendar.MONTH),
                expiry.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickTime() {
        new TimePickerDialog(this, (view, hour, minute) -> {
            expiry.set(Calendar.HOUR_OF_DAY, hour);
            expiry.set(Calendar.MINUTE, minute);
            expiry.set(Calendar.SECOND, 0);
            expiry.set(Calendar.MILLISECOND, 0);
            refreshExpiry();
        }, expiry.get(Calendar.HOUR_OF_DAY), expiry.get(Calendar.MINUTE), true).show();
    }

    /** 设置日期时如果原先是当月 31 号，切到 2 月要避免溢出到 3 月。 */
    private void keepDayValid(Calendar old) {
        int want = old.get(Calendar.DAY_OF_MONTH);
        int max = expiry.getActualMaximum(Calendar.DAY_OF_MONTH);
        if (expiry.get(Calendar.DAY_OF_MONTH) > max) {
            expiry.set(Calendar.DAY_OF_MONTH, max);
        } else if (want <= max && expiry.get(Calendar.DAY_OF_MONTH) != want) {
            expiry.set(Calendar.DAY_OF_MONTH, want);
        }
    }

    private void quickSet(int days) {
        expiry.setTimeInMillis(System.currentTimeMillis());
        expiry.add(Calendar.DAY_OF_MONTH, days);
        expiry.set(Calendar.SECOND, 0);
        expiry.set(Calendar.MILLISECOND, 0);
        refreshExpiry();
    }

    /** 分钟级快捷：测“几分钟后到期”时最常用。 */
    private void quickMinutes(int minutes) {
        expiry.setTimeInMillis(System.currentTimeMillis());
        expiry.add(Calendar.MINUTE, minutes);
        expiry.set(Calendar.SECOND, 0);
        expiry.set(Calendar.MILLISECOND, 0);
        refreshExpiry();
    }

    private void refreshExpiry() {
        long left = expiry.getTimeInMillis() - System.currentTimeMillis();
        String text = SHOW.format(expiry.getTime());
        if (left <= 0) {
            text = text + "  （已到期 → 装上就弹窗）";
        } else if (left < 3600_000L) {
            text = text + "  （约 " + ((left + 59_999L) / 60_000L) + " 分钟后）";
        }
        tvExpiry.setText(text);
    }

    // ------------------------------------------------------------------ 执行

    private void startInject() {
        if (running) {
            return;
        }
        if (srcApk == null || !srcApk.isFile()) {
            toast(getString(R.string.expiry_need_apk));
            return;
        }
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            toast(getString(R.string.expiry_failed) + "：无法创建输出目录");
            return;
        }
        String base = srcApk.getName();
        if (base.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            base = base.substring(0, base.length() - 4);
        }
        outputApk = FileOps.uniqueTarget(outDir, base + "_expiry_" + STAMP.format(expiry.getTime())
                + ".apk");

        final long millis = expiry.getTimeInMillis();
        final String message = etMessage.getText() == null
                ? "" : etMessage.getText().toString().trim();
        final String qq = etQq.getText() == null ? "" : etQq.getText().toString().trim();
        final String group = etGroup.getText() == null ? "" : etGroup.getText().toString().trim();
        prefs.edit().putString("qq", qq).putString("group", group).apply();
        final File src = srcApk;
        final File out = outputApk;

        running = true;
        showStage(1);
        progressBar.setProgress(0);
        tvPercent.setText("0%");
        tvElapsed.setText("");
        runStart = System.currentTimeMillis();
        UI.removeCallbacks(ticker);
        UI.postDelayed(ticker, 200);
        tvStep.setText(R.string.expiry_writing);
        tvRunSub.setText(src.getName());

        io.execute(() -> {
            ExpiryInjector.Result result;
            try {
                result = ExpiryInjector.run(ExpiryInjectActivity.this, src, millis, message,
                        qq, group, out,
                        (desc, percent) -> UI.post(() -> {
                            int p = Math.max(progressBar.getProgress(), percent);
                            tvStep.setText(desc);
                            progressBar.setProgress(p);
                            tvPercent.setText(p + "%");
                        }));
            } catch (Throwable t) {
                result = new ExpiryInjector.Result();
                result.ok = false;
                result.error = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            final ExpiryInjector.Result r = result;
            UI.post(() -> {
                running = false;
                if (r.ok && r.output != null && r.output.isFile()) {
                    progressBar.setProgress(100);
                    showResult(true, r);
                } else {
                    showResult(false, r);
                }
            });
        });
    }

    private void showResult(boolean ok, ExpiryInjector.Result r) {
        running = false;
        showStage(2);
        // 页内摘要（弹窗关掉后仍然看得到）
        tvResultTitle.setText(ok ? R.string.expiry_done : R.string.expiry_failed);
        tvResultTitle.setTextColor(ok ? getResources().getColor(R.color.text_primary) : 0xFFD93025);
        tvResultDetail.setText(ok
                ? getString(R.string.expiry_ok_detail, r.injected, SHOW.format(expiry.getTime()),
                r.output.getAbsolutePath(), Util.formatSize(r.size))
                : (r.error == null || r.error.isEmpty() ? "未知错误" : r.error));
        findViewById(R.id.btn_install).setVisibility(ok ? View.VISIBLE : View.GONE);
        showResultDialog(ok, r);
    }

    /** 结果弹窗：圆形状态标 + 标题 + 副标题 + 信息区 + 双按钮。 */
    private void showResultDialog(boolean ok, ExpiryInjector.Result r) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_expiry_result, null);
        TextView mark = v.findViewById(R.id.dlg_mark);
        TextView title = v.findViewById(R.id.dlg_title);
        TextView sub = v.findViewById(R.id.dlg_sub);
        TextView detail = v.findViewById(R.id.dlg_detail);
        TextView b1 = v.findViewById(R.id.dlg_btn1);
        TextView b2 = v.findViewById(R.id.dlg_btn2);

        final int accent = ok ? 0xFF12B76A : 0xFFE5484D;
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(accent);
        mark.setBackground(circle);
        mark.setText(ok ? "✓" : "!");

        title.setText(ok ? R.string.expiry_dlg_ok : R.string.expiry_dlg_fail);
        sub.setText(ok ? R.string.expiry_dlg_ok_sub : R.string.expiry_dlg_fail_sub);

        StringBuilder sb = new StringBuilder();
        if (ok) {
            String entry = r.entry == null || r.entry.isEmpty() ? "Application" : r.entry;
            sb.append("到期时间　").append(SHOW.format(expiry.getTime())).append('\n');
            sb.append("注入入口　").append(entry).append('\n');
            sb.append("注入类数　").append(r.injected).append(" 个\n");
            sb.append("产物大小　").append(Util.formatSize(r.size)).append('\n');
            sb.append("输出文件　").append(r.output.getAbsolutePath());
        } else {
            sb.append("失败原因　").append(r.error == null || r.error.isEmpty() ? "未知错误" : r.error);
        }
        if (r.diag != null && !r.diag.isEmpty()) {
            sb.append("\n\n扫描信息　").append(r.diag);
        }
        detail.setText(sb);

        GradientDrawable primary = new GradientDrawable();
        primary.setCornerRadius(dp(21));
        primary.setColor(accent);
        b1.setBackground(primary);
        GradientDrawable ghost = new GradientDrawable();
        ghost.setCornerRadius(dp(21));
        ghost.setColor(0x14000000);
        b2.setBackground(ghost);

        final AlertDialog dialog = new AlertDialog.Builder(this).setView(v).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
            dialog.getWindow().setDimAmount(0.6f);
        }

        if (ok) {
            b1.setText(R.string.expiry_dlg_install);
            b2.setText(R.string.expiry_dlg_open);
            b1.setOnClickListener(x -> {
                dialog.dismiss();
                try {
                    Installer.install(ExpiryInjectActivity.this, r.output);
                } catch (Throwable t) {
                    toast("安装失败：" + t.getMessage());
                }
            });
            b2.setOnClickListener(x -> {
                dialog.dismiss();
                openOutDir();
            });
        } else {
            b1.setText(R.string.expiry_dlg_retry);
            b2.setText(R.string.expiry_dlg_close);
            b1.setOnClickListener(x -> {
                dialog.dismiss();
                showStage(0);
            });
            b2.setOnClickListener(x -> dialog.dismiss());
        }
        dialog.show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showStage(int stage) {
        stageSetup.setVisibility(stage == 0 ? View.VISIBLE : View.GONE);
        stageRunning.setVisibility(stage == 1 ? View.VISIBLE : View.GONE);
        stageResult.setVisibility(stage == 2 ? View.VISIBLE : View.GONE);
        if (stage != 2) {
            findViewById(R.id.btn_install).setVisibility(View.VISIBLE);
        }
    }

    private void openOutDir() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.EXTRA_OPEN_PATH, outDir.getAbsolutePath());
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Throwable t) {
            toast(getString(R.string.expiry_failed) + "：" + t.getMessage());
        }
    }

    private File resolveOutDir() {
        File base = Environment.getExternalStorageDirectory();
        File dir = new File(new File(base, "Download"), "到期注入");
        return dir;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        UI.removeCallbacks(ticker);
        io.shutdownNow();
    }
}
