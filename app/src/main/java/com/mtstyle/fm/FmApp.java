package com.mtstyle.fm;

import android.app.Application;
import android.content.Context;

/** 应用入口：安装全局崩溃捕获，并认领内嵌脱壳引擎的宿主进程。 */
public class FmApp extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // v5.9：内嵌脱壳引擎（BlackBox 沙箱 + BlackDex）必须在最早的时机认领宿主进程，
        // 否则沙箱进程（:p0…）里的类加载器没法被接管。
        // FmBlackDex.attach 内部做了完整的异常隔离：即使失败，也只是「免Root脱壳」不可用，
        // 文件管理器本身照常运行。
        FmBlackDex.attach(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
        Settings.applyNightMode(this);
    }
}
