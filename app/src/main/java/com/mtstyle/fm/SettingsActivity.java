package com.mtstyle.fm;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 设置页（外观 / 文件列表 / 时间日期），所有选项即时生效并持久化。 */
public class SettingsActivity extends AppCompatActivity {

    private static final int KEY_DESKTOP_ICON = 1;
    private static final int KEY_LIGHT_BG = 2;
    private static final int KEY_THEME_COLOR = 3;
    private static final int KEY_LIST_SIZE = 4;
    private static final int KEY_NAME_LINES = 5;
    private static final int KEY_TIME_PREFS = 6;
    private static final int KEY_HIDE_PERMISSIONS = 7;
    private static final int KEY_NONSTORAGE_PERM = 8;
    private static final int KEY_DATE_FORMAT = 9;
    private static final int KEY_INSTALL_VERIFY = 10;
    private static final int KEY_INSTALL_KEEP = 11;
    private static final int KEY_INSTALL_CONFIRM = 12;
    private static final int KEY_INSTALL_SHIZUKU = 13;
    private static final int KEY_INSTALL_DHIZUKU = 14;
    private static final int KEY_INSTALL_ROOT = 15;
    private static final int KEY_INSTALL_CUSTOM = 16;
    private static final int KEY_INSTALL_ORDER = 17;
    private static final int KEY_DRAWER_BOOKMARKS = 18;
    private static final int KEY_STARTUP_ROOT = 19;
    private static final int KEY_STARTUP_SHELL = 20;
    private static final int KEY_SU_COMMAND = 21;
    private static final int KEY_SHIZUKU_APP = 22;
    private static final int KEY_HARDEN_AUTO = 23;
    private static final int KEY_HARDEN_LOG = 24;

    private SimpleRowAdapter adapter;
    private final List<Integer> keys = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Settings.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);

        TextView title = findViewById(R.id.list_title);
        title.setText(R.string.settings_title);
        TextView subtitle = findViewById(R.id.list_subtitle);
        subtitle.setText(R.string.settings_subtitle);
        findViewById(R.id.list_action1).setVisibility(View.GONE);
        findViewById(R.id.list_action2).setVisibility(View.GONE);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        ListView list = findViewById(R.id.list_view);
        adapter = new SimpleRowAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onRowClick(position));
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    private void rebuild() {
        List<SimpleRowAdapter.Row> rows = new ArrayList<>();
        keys.clear();

        addHeader(rows, getString(R.string.group_appearance));
        addRow(rows, KEY_DESKTOP_ICON, R.drawable.ic_launcher, getString(R.string.set_desktop_icon),
                null, getString(Settings.isDesktopIconEnabled(this)
                        ? R.string.value_show : R.string.value_hide));
        addRow(rows, KEY_LIGHT_BG, R.drawable.ic_brightness, getString(R.string.set_light_bg),
                null, Settings.nightName(this, Settings.nightMode(this)));
        addRow(rows, KEY_THEME_COLOR, R.drawable.ic_palette, getString(R.string.set_theme_color),
                getString(R.string.set_theme_color_sub),
                Settings.accentName(this, Settings.accent(this)));

        addHeader(rows, getString(R.string.group_file_list));
        addRow(rows, KEY_LIST_SIZE, R.drawable.ic_file, getString(R.string.set_list_size),
                null, Settings.listSizeName(this, Settings.listSize(this)));
        addRow(rows, KEY_NAME_LINES, R.drawable.ic_text, getString(R.string.set_name_lines),
                null, String.valueOf(Settings.nameLines(this)));
        addRow(rows, KEY_TIME_PREFS, R.drawable.ic_record, getString(R.string.set_time_prefs),
                getString(R.string.set_time_prefs_sub), timePrefsValue());
        addRow(rows, KEY_HIDE_PERMISSIONS, R.drawable.ic_check,
                getString(R.string.set_hide_permissions), null,
                getString(Settings.hidePermissions(this) ? R.string.value_on : R.string.value_off));
        addRow(rows, KEY_NONSTORAGE_PERM, R.drawable.ic_check,
                getString(R.string.set_nonstorage_perm), null,
                getString(Settings.nonStoragePermSize(this) ? R.string.value_on : R.string.value_off));

        addHeader(rows, getString(R.string.group_date));
        addRow(rows, KEY_DATE_FORMAT, R.drawable.ic_settings, getString(R.string.set_date_format),
                null, Settings.effectiveDatePattern(this));

        addHeader(rows, getString(R.string.group_root_shell));
        addSwitchRow(rows, KEY_STARTUP_ROOT, R.drawable.ic_check,
                getString(R.string.set_startup_root),
                getString(R.string.set_startup_root_sub), Settings.startupRoot(this));
        addSwitchRow(rows, KEY_STARTUP_SHELL, R.drawable.ic_check,
                getString(R.string.set_startup_shell),
                getString(R.string.set_startup_shell_sub), Settings.startupShell(this));
        addRow(rows, KEY_SU_COMMAND, R.drawable.ic_root,
                getString(R.string.set_su_command),
                getString(R.string.set_su_command_sub),
                Settings.suCommand(this).isEmpty()
                        ? getString(R.string.value_auto_detect) : Settings.suCommand(this));
        addRow(rows, KEY_SHIZUKU_APP, R.drawable.ic_apps,
                getString(R.string.set_install_shizuku_app),
                getString(R.string.set_install_shizuku_app_sub),
                Installer.isInstalled(this, Installer.PKG_SHIZUKU) ? "" : getString(R.string.value_not_installed));

        addHeader(rows, getString(R.string.group_install));
        addSwitchRow(rows, KEY_INSTALL_VERIFY, R.drawable.ic_check,
                getString(R.string.set_install_verify),
                getString(R.string.set_install_verify_sub), Settings.installVerify(this));
        addSwitchRow(rows, KEY_INSTALL_KEEP, R.drawable.ic_check,
                getString(R.string.set_install_keep),
                getString(R.string.set_install_keep_sub), Settings.installKeepSource(this));
        addSwitchRow(rows, KEY_INSTALL_CONFIRM, R.drawable.ic_check,
                getString(R.string.set_install_confirm),
                getString(R.string.set_install_confirm_sub), Settings.installConfirm(this));
        addSwitchRow(rows, KEY_INSTALL_SHIZUKU, R.drawable.ic_check,
                getString(R.string.set_install_shizuku), channelSub(Installer.PKG_SHIZUKU,
                        getString(R.string.set_install_shizuku_sub), "（未检测到 Shizuku）"),
                Settings.installShizuku(this));
        addSwitchRow(rows, KEY_INSTALL_DHIZUKU, R.drawable.ic_check,
                getString(R.string.set_install_dhizuku), channelSub(Installer.PKG_DHIZUKU,
                        getString(R.string.set_install_dhizuku_sub), "（未检测到 Dhizuku）"),
                Settings.installDhizuku(this));
        addSwitchRow(rows, KEY_INSTALL_ROOT, R.drawable.ic_check,
                getString(R.string.set_install_root), Installer.hasRootBinary(this)
                        ? getString(R.string.set_install_root_sub)
                        : getString(R.string.set_install_root_sub) + "（未检测到 Root）",
                Settings.installRoot(this));
        addRow(rows, KEY_INSTALL_CUSTOM, R.drawable.ic_apps,
                getString(R.string.set_install_custom),
                getString(R.string.set_install_custom_sub), customInstallerName());
        addRow(rows, KEY_INSTALL_ORDER, R.drawable.ic_settings,
                getString(R.string.set_install_order), null, "");

        addHeader(rows, getString(R.string.group_apk_harden));
        addRow(rows, KEY_HARDEN_AUTO, R.drawable.ic_tool,
                getString(R.string.set_harden_auto),
                getString(R.string.set_harden_auto_sub),
                getString(HardenTool.isAutoEnabled(this) ? R.string.value_on : R.string.value_off));
        addRow(rows, KEY_HARDEN_LOG, R.drawable.ic_text,
                getString(R.string.set_harden_log),
                getString(R.string.set_harden_log_sub),
                HardenAuto.logFile().exists() ? "" : getString(R.string.set_harden_log_empty));

        addHeader(rows, getString(R.string.group_drawer_bar));
        addSwitchRow(rows, KEY_DRAWER_BOOKMARKS, R.drawable.ic_check,
                getString(R.string.set_drawer_bookmarks),
                getString(R.string.set_drawer_bookmarks_sub), Settings.drawerBookmarks(this));

        adapter.setRows(rows);
    }

    /** 通道可用性提示：未检测到对应应用时在副标题追加说明。 */
    private String channelSub(String packageName, String base, String suffix) {
        return Installer.isInstalled(this, packageName) ? base : base + suffix;
    }

    private String customInstallerName() {
        String pkg = Settings.customInstaller(this);
        if (pkg == null || pkg.isEmpty()) {
            return getString(R.string.value_system_default);
        }
        String label = Installer.installerLabel(this, pkg);
        return label == null ? pkg : label;
    }

    private String timePrefsValue() {
        List<String> parts = new ArrayList<>();
        if (Settings.hideSeconds(this)) {
            parts.add(getString(R.string.hide_seconds));
        }
        if (Settings.shortYear(this)) {
            parts.add(getString(R.string.short_year));
        }
        return parts.isEmpty() ? getString(R.string.value_off) : join(parts);
    }

    private String join(List<String> parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(part);
        }
        return builder.toString();
    }

    private void addHeader(List<SimpleRowAdapter.Row> rows, String title) {
        SimpleRowAdapter.Row row = new SimpleRowAdapter.Row();
        row.header = true;
        row.title = title;
        rows.add(row);
        keys.add(0);
    }

    private void addRow(List<SimpleRowAdapter.Row> rows, int key, int iconRes,
                        String title, String subtitle, String trailing) {
        SimpleRowAdapter.Row row = new SimpleRowAdapter.Row();
        row.iconRes = iconRes;
        row.title = title;
        row.subtitle = subtitle == null ? "" : subtitle;
        row.trailing = trailing == null ? "" : trailing;
        rows.add(row);
        keys.add(key);
    }

    /** 开关型设置行（MT 风格，点击整行切换）。 */
    private void addSwitchRow(List<SimpleRowAdapter.Row> rows, int key, int iconRes,
                              String title, String subtitle, boolean checked) {
        SimpleRowAdapter.Row row = new SimpleRowAdapter.Row();
        row.iconRes = iconRes;
        row.title = title;
        row.subtitle = subtitle == null ? "" : subtitle;
        row.switchRow = true;
        row.switchOn = checked;
        rows.add(row);
        keys.add(key);
    }

    private void onRowClick(int position) {
        if (position < 0 || position >= keys.size()) {
            return;
        }
        switch (keys.get(position)) {
            case KEY_DESKTOP_ICON:
                toggleDesktopIcon();
                break;
            case KEY_LIGHT_BG:
                chooseNightMode();
                break;
            case KEY_THEME_COLOR:
                chooseAccent();
                break;
            case KEY_LIST_SIZE:
                chooseListSize();
                break;
            case KEY_NAME_LINES:
                chooseNameLines();
                break;
            case KEY_TIME_PREFS:
                chooseTimePrefs();
                break;
            case KEY_HIDE_PERMISSIONS:
                Settings.setHidePermissions(this, !Settings.hidePermissions(this));
                rebuild();
                break;
            case KEY_NONSTORAGE_PERM:
                Settings.setNonStoragePermSize(this, !Settings.nonStoragePermSize(this));
                rebuild();
                break;
            case KEY_DATE_FORMAT:
                chooseDateFormat();
                break;
            case KEY_INSTALL_VERIFY:
                Settings.setInstallVerify(this, !Settings.installVerify(this));
                rebuild();
                break;
            case KEY_INSTALL_KEEP:
                Settings.setInstallKeepSource(this, !Settings.installKeepSource(this));
                rebuild();
                break;
            case KEY_INSTALL_CONFIRM:
                Settings.setInstallConfirm(this, !Settings.installConfirm(this));
                rebuild();
                break;
            case KEY_INSTALL_SHIZUKU:
                Settings.setInstallShizuku(this, !Settings.installShizuku(this));
                rebuild();
                break;
            case KEY_INSTALL_DHIZUKU:
                Settings.setInstallDhizuku(this, !Settings.installDhizuku(this));
                rebuild();
                break;
            case KEY_INSTALL_ROOT:
                Settings.setInstallRoot(this, !Settings.installRoot(this));
                rebuild();
                break;
            case KEY_INSTALL_CUSTOM:
                chooseSystemInstaller();
                break;
            case KEY_INSTALL_ORDER:
                new AlertDialog.Builder(this)
                        .setTitle(R.string.set_install_order)
                        .setMessage(R.string.set_install_order_text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                break;
            case KEY_DRAWER_BOOKMARKS:
                Settings.setDrawerBookmarks(this, !Settings.drawerBookmarks(this));
                rebuild();
                break;
            case KEY_STARTUP_ROOT:
                Settings.setStartupRoot(this, !Settings.startupRoot(this));
                rebuild();
                toast(getString(R.string.set_startup_root_sub));
                break;
            case KEY_STARTUP_SHELL:
                Settings.setStartupShell(this, !Settings.startupShell(this));
                rebuild();
                toast(getString(R.string.set_startup_shell_sub));
                break;
            case KEY_SU_COMMAND:
                editSuCommand();
                break;
            case KEY_SHIZUKU_APP:
                openUrl("https://shizuku.rikka.app/");
                break;
            case KEY_HARDEN_AUTO:
                HardenTool.openAccessibilitySettings(this);
                break;
            case KEY_HARDEN_LOG:
                openHardenLog();
                break;
            default:
                break;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** 自定义 su 命令：留空表示自动识别。 */
    private void editSuCommand() {
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);
        final EditText input = new EditText(this);
        input.setHint("su");
        input.setSingleLine(true);
        input.setText(Settings.suCommand(this));
        FrameLayout container = new FrameLayout(this);
        container.setPadding(pad, Math.round(8 * density), pad, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_su_command)
                .setMessage(R.string.set_su_command_sub)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Settings.setSuCommand(this, input.getText().toString());
                    rebuild();
                })
                .setNegativeButton(R.string.act_cancel, null)
                .show();
    }

    /** 查看加固自动化日志（失败排查用）。长按可清空。 */
    private void openHardenLog() {
        final java.io.File log = HardenAuto.logFile();
        if (!log.exists() || log.length() == 0) {
            toast(getString(R.string.set_harden_log_empty));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_harden_log)
                .setMessage(log.getAbsolutePath())
                .setPositiveButton(R.string.harden_auto_view, (dialog, which) -> {
                    Intent intent = new Intent(this, TextEditorActivity.class);
                    intent.putExtra(TextEditorActivity.EXTRA_PATH, log.getAbsolutePath());
                    startActivity(intent);
                })
                .setNegativeButton(R.string.set_harden_log_clear, (dialog, which) -> {
                    HardenAuto.clearLog();
                    rebuild();
                })
                .setNeutralButton(R.string.act_cancel, null)
                .show();
    }

    /** 打开外部链接（用浏览器）。 */
    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast(getString(R.string.open_url_failed, url));
        }
    }

    /** 自定义系统安装器：列出系统中可处理 APK 的安装器。 */
    private void chooseSystemInstaller() {
        Map<String, String> installers = Installer.listSystemInstallers(this);
        final List<String> packages = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        packages.add(null);
        labels.add(getString(R.string.value_system_default));
        for (Map.Entry<String, String> entry : installers.entrySet()) {
            packages.add(entry.getKey());
            labels.add(entry.getValue() + "（" + entry.getKey() + "）");
        }
        String current = Settings.customInstaller(this);
        int checked = 0;
        for (int i = 0; i < packages.size(); i++) {
            String pkg = packages.get(i);
            if (current != null && current.equals(pkg)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_install_custom)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked,
                        (dialog, which) -> {
                            Settings.setCustomInstaller(this, packages.get(which));
                            dialog.dismiss();
                            rebuild();
                        })
                .show();
    }

    // ==================== 各项设置 ====================

    private void toggleDesktopIcon() {
        final boolean enabled = Settings.isDesktopIconEnabled(this);
        if (enabled) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.set_desktop_icon)
                    .setMessage(R.string.desktop_icon_off_warning)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.value_hide, (dialog, which) -> {
                        Settings.setDesktopIconEnabled(this, false);
                        rebuild();
                    })
                    .show();
        } else {
            Settings.setDesktopIconEnabled(this, true);
            rebuild();
        }
    }

    private void chooseNightMode() {
        final int[] values = {Settings.NIGHT_DARK, Settings.NIGHT_LIGHT, Settings.NIGHT_SYSTEM};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = Settings.nightName(this, values[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_light_bg)
                .setSingleChoiceItems(labels, indexOf(values, Settings.nightMode(this)),
                        (dialog, which) -> {
                            Settings.setNightMode(this, values[which]);
                            dialog.dismiss();
                            rebuild();
                        })
                .show();
    }

    private void chooseAccent() {
        final int[] values = {Settings.ACCENT_BLUE, Settings.ACCENT_GREEN, Settings.ACCENT_PURPLE,
                Settings.ACCENT_ORANGE, Settings.ACCENT_RED, Settings.ACCENT_TEAL};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = Settings.accentName(this, values[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_theme_color)
                .setSingleChoiceItems(labels, indexOf(values, Settings.accent(this)),
                        (dialog, which) -> {
                            Settings.setAccent(this, values[which]);
                            dialog.dismiss();
                            recreate();
                        })
                .show();
    }

    private void chooseListSize() {
        final int[] values = {Settings.SIZE_SMALL, Settings.SIZE_MEDIUM, Settings.SIZE_LARGE};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = Settings.listSizeName(this, values[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_list_size)
                .setSingleChoiceItems(labels, indexOf(values, Settings.listSize(this)),
                        (dialog, which) -> {
                            Settings.setListSize(this, values[which]);
                            dialog.dismiss();
                            rebuild();
                        })
                .show();
    }

    private void chooseNameLines() {
        final int[] values = {1, 2, 3, 4};
        String[] labels = {"1", "2", "3", "4"};
        int current = Settings.nameLines(this);
        int checked = 1;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_name_lines)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    Settings.setNameLines(this, values[which]);
                    dialog.dismiss();
                    rebuild();
                })
                .show();
    }

    private void chooseTimePrefs() {
        final String[] labels = {getString(R.string.hide_seconds), getString(R.string.short_year)};
        final boolean[] checked = {Settings.hideSeconds(this), Settings.shortYear(this)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_time_prefs)
                .setMultiChoiceItems(labels, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    Settings.setTimePrefs(this, checked[0], checked[1]);
                    rebuild();
                })
                .show();
    }

    private void chooseDateFormat() {
        final String[] presets = {Settings.DEFAULT_DATE_FORMAT, "yyyy-MM-dd HH:mm",
                "yy-MM-dd HH:mm", "MM-dd HH:mm", "yyyy/MM/dd HH:mm",
                getString(R.string.date_custom)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_date_format)
                .setItems(presets, (dialog, which) -> {
                    if (which == presets.length - 1) {
                        inputDateFormat();
                    } else {
                        Settings.setDateFormat(this, presets[which]);
                        rebuild();
                    }
                })
                .show();
    }

    private void inputDateFormat() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(Settings.dateFormat(this));
        input.setSingleLine(true);
        LinearLayout wrapper = new LinearLayout(this);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        wrapper.setPadding(pad, pad / 2, pad, 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_date_format)
                .setView(wrapper)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    String pattern = input.getText().toString().trim();
                    if (pattern.isEmpty()) {
                        return;
                    }
                    try {
                        new SimpleDateFormat(pattern, java.util.Locale.getDefault())
                                .format(new java.util.Date());
                    } catch (Exception e) {
                        android.widget.Toast.makeText(this, R.string.date_custom_invalid,
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Settings.setDateFormat(this, pattern);
                    rebuild();
                })
                .show();
    }

    private int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rebuild();
    }
}
