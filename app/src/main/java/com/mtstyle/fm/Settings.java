package com.mtstyle.fm;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Environment;

import androidx.appcompat.app.AppCompatDelegate;

import java.io.File;

/** 应用设置（外观 / 文件列表）。所有选项都持久化在 SharedPreferences。 */
public final class Settings {

    private static final String FILE = "fm_settings";

    /** 主题颜色方案 */
    public static final int ACCENT_BLUE = 0;
    public static final int ACCENT_GREEN = 1;
    public static final int ACCENT_PURPLE = 2;
    public static final int ACCENT_ORANGE = 3;
    public static final int ACCENT_RED = 4;
    public static final int ACCENT_TEAL = 5;
    public static final int ACCENT_COUNT = 6;

    /** 浅色背景(自适应) */
    public static final int NIGHT_DARK = 0;
    public static final int NIGHT_LIGHT = 1;
    public static final int NIGHT_SYSTEM = 2;

    /** 文件列表大小 */
    public static final int SIZE_SMALL = 0;
    public static final int SIZE_MEDIUM = 1;
    public static final int SIZE_LARGE = 2;

    public static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    public static final String LAUNCHER_ALIAS = "com.mtstyle.fm.LauncherAlias";

    private static int version;

    private Settings() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 每次修改设置都自增，列表适配器据此重新读取（避免每行都读配置）。 */
    public static int version() {
        return version;
    }

    private static void touch(Context context, SharedPreferences.Editor editor) {
        editor.apply();
        version++;
    }

    // ==================== 外观 ====================

    public static int accent(Context context) {
        return prefs(context).getInt("accent", ACCENT_BLUE);
    }

    public static void setAccent(Context context, int value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putInt("accent", value);
        touch(context, editor);
    }

    public static int nightMode(Context context) {
        return prefs(context).getInt("night", NIGHT_DARK);
    }

    public static void setNightMode(Context context, int value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putInt("night", value);
        touch(context, editor);
        applyNightMode(context);
    }

    /** 把浅色/深色偏好应用到全局（跟随系统 / 强制浅色 / 强制深色）。 */
    public static void applyNightMode(Context context) {
        switch (nightMode(context)) {
            case NIGHT_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case NIGHT_SYSTEM:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
        }
    }

    /** 在 Activity onCreate 最开始调用，切换主题色。 */
    public static void applyTheme(Activity activity) {
        switch (accent(activity)) {
            case ACCENT_GREEN:
                activity.setTheme(R.style.Theme_MyFiles_Green);
                break;
            case ACCENT_PURPLE:
                activity.setTheme(R.style.Theme_MyFiles_Purple);
                break;
            case ACCENT_ORANGE:
                activity.setTheme(R.style.Theme_MyFiles_Orange);
                break;
            case ACCENT_RED:
                activity.setTheme(R.style.Theme_MyFiles_Red);
                break;
            case ACCENT_TEAL:
                activity.setTheme(R.style.Theme_MyFiles_Teal);
                break;
            case ACCENT_BLUE:
            default:
                activity.setTheme(R.style.Theme_MyFiles_Blue);
                break;
        }
    }

    public static String accentName(Context context, int index) {
        switch (index) {
            case ACCENT_GREEN:
                return context.getString(R.string.accent_green);
            case ACCENT_PURPLE:
                return context.getString(R.string.accent_purple);
            case ACCENT_ORANGE:
                return context.getString(R.string.accent_orange);
            case ACCENT_RED:
                return context.getString(R.string.accent_red);
            case ACCENT_TEAL:
                return context.getString(R.string.accent_teal);
            case ACCENT_BLUE:
            default:
                return context.getString(R.string.accent_blue);
        }
    }

    public static String nightName(Context context, int index) {
        switch (index) {
            case NIGHT_LIGHT:
                return context.getString(R.string.night_light);
            case NIGHT_SYSTEM:
                return context.getString(R.string.night_system);
            default:
                return context.getString(R.string.night_dark);
        }
    }

    // ==================== 桌面图标 ====================

    public static boolean isDesktopIconEnabled(Context context) {
        int state = context.getPackageManager().getComponentEnabledSetting(
                new ComponentName(context, LAUNCHER_ALIAS));
        if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            return true;
        }
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            return false;
        }
        return prefs(context).getBoolean("desktop_icon", true);
    }

    public static void setDesktopIconEnabled(Context context, boolean enabled) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean("desktop_icon", enabled);
        touch(context, editor);
        context.getPackageManager().setComponentEnabledSetting(
                new ComponentName(context, LAUNCHER_ALIAS),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    // ==================== 文件列表 ====================

    public static int listSize(Context context) {
        return prefs(context).getInt("list_size", SIZE_MEDIUM);
    }

    public static void setListSize(Context context, int value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putInt("list_size", value);
        touch(context, editor);
    }

    public static String listSizeName(Context context, int index) {
        switch (index) {
            case SIZE_SMALL:
                return context.getString(R.string.size_small);
            case SIZE_LARGE:
                return context.getString(R.string.size_large);
            default:
                return context.getString(R.string.size_medium);
        }
    }

    public static int nameLines(Context context) {
        return prefs(context).getInt("name_lines", 2);
    }

    public static void setNameLines(Context context, int value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putInt("name_lines", value);
        touch(context, editor);
    }

    public static boolean hideSeconds(Context context) {
        return prefs(context).getBoolean("hide_seconds", true);
    }

    public static boolean shortYear(Context context) {
        return prefs(context).getBoolean("short_year", true);
    }

    public static void setTimePrefs(Context context, boolean hideSec, boolean shortYr) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean("hide_seconds", hideSec);
        editor.putBoolean("short_year", shortYr);
        touch(context, editor);
    }

    public static boolean hidePermissions(Context context) {
        return prefs(context).getBoolean("hide_permissions", false);
    }

    public static void setHidePermissions(Context context, boolean value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean("hide_permissions", value);
        touch(context, editor);
    }

    public static boolean nonStoragePermSize(Context context) {
        return prefs(context).getBoolean("nonstorage_perm_size", true);
    }

    public static void setNonStoragePermSize(Context context, boolean value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean("nonstorage_perm_size", value);
        touch(context, editor);
    }

    public static String dateFormat(Context context) {
        return prefs(context).getString("date_format", DEFAULT_DATE_FORMAT);
    }

    public static void setDateFormat(Context context, String pattern) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putString("date_format", pattern);
        touch(context, editor);
    }

    /** 应用“隐藏秒数 / 精简年份”后的实际格式。 */
    public static String effectiveDatePattern(Context context) {
        String pattern = dateFormat(context);
        if (pattern == null || pattern.trim().isEmpty()) {
            pattern = DEFAULT_DATE_FORMAT;
        }
        if (shortYear(context)) {
            pattern = pattern.replace("yyyy", "yy");
        }
        if (hideSeconds(context)) {
            pattern = pattern.replace(":ss", "");
        }
        return pattern;
    }

    // ==================== APK 安装 ====================

    /** 安装 APK 前验证签名和版本号。 */
    public static boolean installVerify(Context context) {
        return prefs(context).getBoolean("install_verify", true);
    }

    public static void setInstallVerify(Context context, boolean value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean("install_verify", value);
        touch(context, editor);
    }

    /** 防自动删除：先复制到私有目录再安装，避免原 APK 被系统装完删除。 */
    public static boolean installKeepSource(Context context) {
        return prefs(context).getBoolean("install_keep_source", true);
    }

    public static void setInstallKeepSource(Context context, boolean value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean("install_keep_source", value);
        touch(context, editor);
    }

    /** 安装前二次确认。 */
    public static boolean installConfirm(Context context) {
        return prefs(context).getBoolean("install_confirm", false);
    }

    public static void setInstallConfirm(Context context, boolean value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean("install_confirm", value);
        touch(context, editor);
    }

    public static boolean installShizuku(Context context) {
        return prefs(context).getBoolean("install_shizuku", false);
    }

    public static void setInstallShizuku(Context context, boolean value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean("install_shizuku", value);
        touch(context, editor);
    }

    public static boolean installDhizuku(Context context) {
        return prefs(context).getBoolean("install_dhizuku", false);
    }

    public static void setInstallDhizuku(Context context, boolean value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean("install_dhizuku", value);
        touch(context, editor);
    }

    public static boolean installRoot(Context context) {
        return prefs(context).getBoolean("install_root", false);
    }

    public static void setInstallRoot(Context context, boolean value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean("install_root", value);
        touch(context, editor);
    }

    /** 自定义系统安装器包名，null 表示跟随系统默认。 */
    public static String customInstaller(Context context) {
        return prefs(context).getString("install_custom_pkg", null);
    }

    public static void setCustomInstaller(Context context, String packageName) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (packageName == null || packageName.isEmpty()) {
            editor.remove("install_custom_pkg");
        } else {
            editor.putString("install_custom_pkg", packageName);
        }
        touch(context, editor);
    }

    // ==================== Root & Shell ====================

    /** 启动时请求 Root 权限。 */
    public static boolean startupRoot(Context context) {
        return prefs(context).getBoolean("startup_root", false);
    }

    public static void setStartupRoot(Context context, boolean value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean("startup_root", value);
        touch(context, editor);
    }

    /** 启动时请求 Shell 权限（未获得 Root 时尝试通过 Shizuku 获取）。 */
    public static boolean startupShell(Context context) {
        return prefs(context).getBoolean("startup_shell", false);
    }

    public static void setStartupShell(Context context, boolean value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean("startup_shell", value);
        touch(context, editor);
    }

    /** 自定义 su 命令，留空表示自动识别。 */
    public static String suCommand(Context context) {
        String value = prefs(context).getString("su_command", null);
        return value == null ? "" : value.trim();
    }

    public static void setSuCommand(Context context, String command) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (command == null || command.trim().isEmpty()) {
            editor.remove("su_command");
        } else {
            editor.putString("su_command", command.trim());
        }
        touch(context, editor);
    }

    // ==================== 侧拉栏 & 底栏 ====================

    /** 在侧拉栏显示书签。 */
    public static boolean drawerBookmarks(Context context) {
        return prefs(context).getBoolean("drawer_bookmarks", true);
    }

    public static void setDrawerBookmarks(Context context, boolean value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean("drawer_bookmarks", value);
        touch(context, editor);
    }

    // ==================== 其它工具 ====================

    /** 近似权限串：d/rwx 形式，用 canRead/canWrite/canExecute 推断。 */
    public static String permissions(File file) {
        StringBuilder builder = new StringBuilder(10);
        builder.append(file.isDirectory() ? 'd' : '-');
        for (int i = 0; i < 3; i++) {
            builder.append(file.canRead() ? 'r' : '-');
            builder.append(file.canWrite() ? 'w' : '-');
            builder.append(file.canExecute() ? 'x' : '-');
        }
        return builder.toString();
    }

    public static boolean isUnderStorage(String path) {
        String storage = Environment.getExternalStorageDirectory().getAbsolutePath();
        return path != null && (path.equals(storage) || path.startsWith(storage + "/"));
    }
}
