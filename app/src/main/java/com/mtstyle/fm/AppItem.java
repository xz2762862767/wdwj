package com.mtstyle.fm;

import android.graphics.drawable.Drawable;

/** 已安装应用条目（应用名 / 版本 / 大小 / 包名 / 图标 / 加固平台）。 */
public class AppItem {

    public String label = "";
    public String packageName = "";
    public String versionName = "-";
    public int versionCode;
    public long size;
    public boolean system;
    public Drawable icon;
    public String sourceDir;
    /** application 的类名（加固壳入口，用来识别加固平台）。 */
    public String appClass;
    /** 加固平台（{@link HardenDetect} 的识别结果，空串表示还没检测）。 */
    public String harden = "";
}
