package com.mtstyle.fm;

/** 侧滑栏的一条数据（分组标题 / 可点击条目）。 */
public class DrawerItem {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_ITEM = 1;
    /** v5.5：可折叠分组标题（点击展开/收起下面的子项）。 */
    public static final int TYPE_GROUP = 2;

    public static final int ACTION_NONE = 0;
    public static final int ACTION_TOGGLE_HIDDEN = 1;
    public static final int ACTION_REFRESH = 2;
    public static final int ACTION_NEW_FOLDER = 3;
    public static final int ACTION_MORE = 4;
    public static final int ACTION_TRASH = 5;
    public static final int ACTION_APPS = 6;
    public static final int ACTION_EDITOR = 7;
    public static final int ACTION_RECORD = 8;
    public static final int ACTION_SETTINGS = 9;
    // v5.5：侧滑栏「加固与安全」分组
    public static final int ACTION_HARDEN_PICK = 10;   // 选一个 APK 交给加固工具
    public static final int ACTION_HARDEN_TOOL = 11;   // 直接打开加固工具
    public static final int ACTION_HARDEN_AUTO = 12;   // 自动加固（无障碍）开关
    // v5.5：侧滑栏「黑盾加固」分组
    public static final int ACTION_BLACKSHIELD_OPEN = 13; // 打开黑盾（未安装则引导安装）
    public static final int ACTION_APK_CRYPTO = 14;      // v5.6：APK 加固/加密（交给「免费APK加密」）
    public static final int ACTION_DUMP = 15;            // v5.7：免Root脱壳（BlackDex 引擎）
    public static final int ACTION_EXPIRY_INJECT = 16;   // v5.9.44：注入到期时间（自研注入器）
    public static final int ACTION_TOGGLE_GROUP = 90;  // 仅用于分组标题的占位

    public final int type;
    public int iconRes;
    public String title;
    public String subtitle;
    public String path;
    public int action = ACTION_NONE;
    public boolean removable;
    /** v5.5：分组 key（折叠状态按 key 持久化）。 */
    public String group;
    /** v5.5：分组当前是否展开。 */
    public boolean expanded;
    /** v5.5：是否为分组内子项（缩进显示）。 */
    public boolean child;
    /** v4.9：该条目是否对应当前面板所在位置（高亮显示）。 */
    public boolean active;

    private DrawerItem(int type) {
        this.type = type;
    }

    public static DrawerItem header(String title) {
        DrawerItem item = new DrawerItem(TYPE_HEADER);
        item.title = title;
        return item;
    }

    public static DrawerItem storage(int iconRes, String title, String subtitle, String path) {
        DrawerItem item = new DrawerItem(TYPE_ITEM);
        item.iconRes = iconRes;
        item.title = title;
        item.subtitle = subtitle;
        item.path = path;
        return item;
    }

    public static DrawerItem bookmark(int iconRes, String title, String path) {
        DrawerItem item = storage(iconRes, title, path, path);
        item.removable = true;
        return item;
    }

    public static DrawerItem action(int iconRes, String title, int action) {
        return action(iconRes, title, null, action);
    }

    /** v5.5：可折叠分组标题。 */
    public static DrawerItem group(String key, int iconRes, String title, boolean expanded) {
        DrawerItem item = new DrawerItem(TYPE_GROUP);
        item.group = key;
        item.iconRes = iconRes;
        item.title = title;
        item.expanded = expanded;
        item.action = ACTION_TOGGLE_GROUP;
        return item;
    }

    /** v5.5：分组内子项（缩进）。 */
    public static DrawerItem sub(int iconRes, String title, String subtitle, int action) {
        DrawerItem item = action(iconRes, title, subtitle, action);
        item.child = true;
        return item;
    }

    public static DrawerItem action(int iconRes, String title, String subtitle, int action) {
        DrawerItem item = new DrawerItem(TYPE_ITEM);
        item.iconRes = iconRes;
        item.title = title;
        item.subtitle = subtitle;
        item.action = action;
        return item;
    }
}
