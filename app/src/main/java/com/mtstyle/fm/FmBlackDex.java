package com.mtstyle.fm;

import android.app.Application;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.BlackDexCore;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;
import top.niunaijun.blackbox.core.system.am.IBActivityManagerService;
import top.niunaijun.blackbox.core.system.dump.IBDumpMonitor;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.entity.dump.DumpResult;
import top.niunaijun.blackbox.entity.pm.InstallResult;

/**
 * v5.9：内嵌免 Root 脱壳引擎（BlackBox 沙箱 + BlackDex）。
 *
 * <p>引擎已静态集成进本 App：宿主所需的多进程代理组件、native 库（libblackdex.so）
 * 与 BlackBox 类都已打进 APK，因此不再依赖本机安装的「Epic」。</p>
 *
 * <p>生命周期：</p>
 * <ol>
 *   <li>{@link #attach(Application)}：在 {@code Application.attachBaseContext} 里尽早调用，
 *       只做「认领宿主 + 解除隐藏 API 限制」，开销很小，日常使用几乎无感；</li>
 *   <li>{@link #ensureStarted()}：用户第一次点「开始脱壳」时才拉起 :black 服务进程与
 *       四大 Binder 服务，并注册进度回调；</li>
 *   <li>{@link #startDump(String, boolean)}：把目标 APK 装进沙箱并启动，
 *       真正的脱壳在沙箱进程（:pN）里由 native 完成，结果落在
 *       {@code <dumpDir>/<包名>/}，进度通过 {@link Monitor} 回调回来。</li>
 * </ol>
 *
 * <p>全过程只写一个日志文件（供界面轮询），格式与界面约定一致：
 * {@code [start]/[loading]/[progress] i/n/[success] dir=... /[error]/[timeout]}。</p>
 */
public final class FmBlackDex {

    private static final String TAG = "FmBlackDex";

    /** 引擎自身不带超时，这里兜底（与旧版 Epic 引擎一致按 5 分钟算）。 */
    private static final long WATCHDOG_MS = 120 * 1000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 内存扫描标记文件名。它由沙箱进程（:p0）在产物目录里创建/删除。 */
    private static final String MEM_SCAN_MARK = ".memscan_running";
    /** 扫描标记超过这么久没被 touch，就认定扫描已随沙箱进程一起死了（见 memScanRunning）。 */
    private static final long MEM_MARK_STALE_MS = 90_000L;

    /** 沙箱工作进程的名字（宿主的多进程分身），用来在 /proc 里判断它到底还在不在。 */
    private static final String SANDBOX_PROC_NAME = "com.mtstyle.fm:p0";

    /** 崩溃日志只抓一次，避免每次轮询都抓。 */
    private static volatile boolean crashLogGrabbed;
    /** 内存扫描的总时长上限，超过就当它挂了，别再等。 */
    private static final long MEM_SCAN_MAX_MS = 90000L;
    /**
     * v5.9.62：任务至少跑满这么久才允许判「成功」收工。
     *
     * <p>引擎的 hook dump 几秒就把壳自己的 dex 落盘（hook-*.dex），而真正有价值的
     * 内存扫描（抓 InMemoryDexFile 这类不落文件的内存 dex）要晚得多才开始。之前
     * 一看到 dex 文件就 gotResult，产物永远只有 1 个壳 dex。所以前 90 秒无论如何
     * 都继续等，超时/无进展仍由 WATCHDOG_MS 兜底。</p>
     */
    private static final long MIN_TASK_MS = 45000L;
    /** 沙箱进程确实没了、产物 30 秒没再长，就提前收工（不必死等满 WATCHDOG_MS 那 120 秒）。 */
    private static final long EARLY_DONE_MS = 30_000L;
    /** 本次任务里有没有真正见过沙箱 App 进程（用来区分「还没起来」和「起来后又死了」）。 */
    private static volatile boolean sawSandbox;
    /** 判定「产物里有没有业务类」时，单个 dex 最多扫多久（超时下次轮询再来）。 */
    private static final long BUSINESS_SCAN_BUDGET_MS = 2500L;

    private static volatile Context appContext;
    private static volatile boolean attached;
    private static volatile boolean started;
    private static volatile boolean busy;
    /** busy 置位的时刻：用来识别「上一次任务其实已经僵死、只剩标志没清」 */
    private static volatile long busySince;
    /** 当前任务的产物目录名（包名），abort 时用它定位内存扫描残留标记 */
    private static volatile String currentPkg;
    /** 超过这个时长还没结束，就认为 busy 是僵死标志（正常一次脱壳含内存扫描约 90 秒） */
    private static final long STALE_BUSY_MS = 3 * 60 * 1000L;
    private static volatile String error = "";
    private static volatile File logFile;
    private static volatile long sessionStart;
    private static volatile boolean gotResult;
    /** 是否已有人在等产物目录稳定（避免多次 onDump 各起一个等待线程）。 */
    private static volatile boolean settling;
    /** 上一轮 watchdog 看到的「内存扫描中」状态，用来捕捉 running→结束 的那一次跳变。 */
    private static volatile boolean memWasRunning;
    private static volatile int retryCount;
    private static volatile boolean hiddenApiOk;
    /** 每个目标专属的日志文件名，落在该包的产物目录里。 */
    private static final String TASK_LOG_NAME = "脱壳日志.txt";
    /** 记录本次任务日志路径的私有文件，供 :black 子进程读取。 */
    private static final String TASK_LOG_PATH_NAME = "task_log_path";

    /**
     * 「本次任务目标包名」记录文件的文件名，每次任务覆写。
     *
     * <p>{@link #TASK_LOG_PATH_NAME} 那份是全局共享单文件，沙箱进程（:p0）里读不到宿主私有目录
     * 那份时会退化到外部共享目录那份，而那份可能还是<b>上一个任务</b>留下的。于是 :p0 拿着别人的
     * 包名去解析 manifest、等 ClassLoader，最后不管脱什么 APK 都只出同一个壳 dex。这份单独记包名，
     * 就是给 :p0 一个「当前在跑谁」的权威答案。</p>
     */
    private static final String TASK_PKG_NAME = "task_pkg";
    /**
     * 「本次任务原始 APK 绝对路径」记录文件，每次任务覆写。
     * <p>:p0 里拿它跟沙箱那副本做「同一份文件吗」的直接对比（大小 + SHA-256），
     * 这是判断「引擎有没有改写/重签目标 APK」的唯一硬证据。</p>
     */
    private static final String TASK_SRC_APK_NAME = "task_src_apk";
    /**
     * 产物根目录的固定候选（纯字符串，不经过 Environment）。
     * 沙箱进程里 Environment 会被引擎重定向，两个进程必须靠同一份字符串算出同一个目录。
     */
    private static final String[] FIXED_DUMP_ROOTS = {
            "/storage/emulated/0/Download/脱壳结果",
            "/sdcard/Download/脱壳结果",
    };
    /** 产物根目录缓存；attach 时失效一次（回退分支依赖 appContext）。 */
    private static volatile File dumpDirCache;
    /**
     * 宿主包名。硬编码是刻意的：跨进程算路径时必须得到一个和沙箱里被重定向的 Context
     * 无关的稳定字符串（项目里 {@code BuildConfig} 未启用，且沙箱进程里也拿不到宿主 Context）。
     */
    private static final String HOST_PKG = "com.mtstyle.fm";

    /** 宿主包名（供 FmVmStub 判断「本进程是不是沙箱进程」用）。 */
    static String hostPkg() {
        return HOST_PKG;
    }

    /**
     * v5.9.83：:p0（沙箱 App 进程）里「目标 App 已被引擎绑定」的落盘标记。
     *
     * <p>静默启动（{@code initProcess} + {@code bActivityThread.bindApplication()}）全程没有任何
     * Activity，宿主无法从窗口侧判断「起来没有」，只能等 :p0 的
     * {@code beforeCreateApplication} 回调把这个标记写出来（见 {@link #awaitP0Bound}）。</p>
     */
    private static final String MARK_P0_BOUND = ".p0_bound";

    /**
     * v5.9.88：目标 Application【真的建出来】的硬证据标记。
     *
     * <p>{@link #MARK_P0_BOUND} 写在 {@code beforeCreateApplication} 回调里——那一刻引擎才刚要调
     * {@code LoadedApk.makeApplication}，Application 并不存在。宿主以前拿它当「已就绪」，
     * 就会去投递 launch，而 :p0 主线程此时 {@code ActivityThread.mInitialApplication == null}，
     * launch 一来必抛 NPE（{@code ConfigurationController.updateLocaleListFromAppContext}）并打死进程。
     * 本标记由 {@code FmVmStub.skipDumpBranch} 在引擎写完 {@code mInitialApplication} 之后落盘，
     * 内容就是壳的 Application 类名。</p>
     */
    private static final String MARK_APP_READY = ".app_ready";
    /**
     * 私有目录的固定候选。理由同 {@link #FIXED_DUMP_ROOTS}：沙箱进程里
     * {@code getExternalFilesDir()} / {@code Environment} 都会走 Binder 并可能抛
     * DeadObjectException，不能让日志与标记文件的落点依赖它们。
     */
    private static final String[] FIXED_PRIV_ROOTS = {
            "/storage/emulated/0/Android/data/" + HOST_PKG + "/files",
            "/sdcard/Android/data/" + HOST_PKG + "/files",
    };
    /** 本次任务的专属日志（产物目录/脱壳日志.txt）；没有进行中的任务时为 null。 */
    private static volatile File taskLogFile;

    private FmBlackDex() {
    }

    // ==================== 生命周期 ====================

    /** 在 Application.attachBaseContext 里调用；任何异常都吞掉，绝不影响文件管理器本身。 */
    public static void attach(Application app) {
        if (attached) {
            return;
        }
        appContext = app.getApplicationContext();
        // 回退分支依赖 appContext，之前可能已被缓存过，这里失效一次
        dumpDirCache = null;
        // 跨进程探针：这一步能证明「沙箱进程 :p0 里我们的代码到底有没有被执行到」。
        // 之前几轮排查「只有 1 个壳 dex」全靠猜，就是缺这个。
        probe("FmBlackDex.attach");
        // 必须先解 hidden API：引擎自带的 me.weishu.reflection.Reflection.unseal() 在新系统上
        // 已失效（logcat 里 BootstrapClass 一直在打失败堆栈），导致它反射
        // android.content.pm.PackageParser 被拒 → 沙箱 PM 建不出包信息
        // （install 报成功但 activities=0 / sourceDir=无）→ launchApk 必然 false。
        unsealHiddenApi();
        // 宿主与 :black 是两个进程，静态字段不共享；把任务日志路径经私有文件带过去，
        // 这样引擎在沙箱进程里打的行也会落进「本次这个包」的那一份日志。
        if (taskLogFile == null) {
            taskLogFile = taskLogFromDisk();
        }
        try {
            BlackDexCore.get().doAttachBaseContext(app, new Config(app));
            attached = true;
            started = false;
            Log.i(TAG, "内嵌脱壳引擎已认领宿主进程");
            logLine("[engine] 引擎认领进程：" + procName()
                    + "，hidden API 豁免=" + (hiddenApiOk ? "可用" : "不可用"));
        } catch (Throwable t) {
            attached = false;
            error = describe("初始化", t);
            Log.w(TAG, error);
            logLine("[engine] 引擎初始化失败：" + error);
        }
        // 抽取器刻意不放进上面的 try：它不依赖引擎初始化成功。
        // 沙箱进程（:p0）的运行环境和宿主不同，引擎在那里报错很正常；原来这句挂在 try 末尾，
        // 引擎一报错抽取器就跟着被跳过 —— 逼解密与内存扫描一次都不跑，
        // 结果永远是「只有引擎 hook 抓到的那 1 个壳 dex」（360 加固、网易易盾实测都是）。
    }

    // ===================== v5.9.90：查「壳为什么不解密」 =====================

    /**
     * 把 :p0 里的引擎配置强制成「原版脱壳」配置。
     *
     * <p>v5.9.89 实测：{@code VMCore.cookieDumpDex} 在 :p0 被真调用了，但
     * {@code autoCallAllMethod}(= 主动加载逼补码) 一次都没被调用 ⇒ 说明 :p0 里
     * {@code BlackBoxCore.get().isAutoCallMethod()} 仍是 false。原因很可能是
     * <b>宿主的 Config 并没有被带进沙箱进程</b>：{@code BlackBoxCore} 是用
     * {@code doAttachBaseContext(ctx, cfg)} 传的，而 :p0 里的单例是引擎自己另建的。</p>
     *
     * <p>所以这里直接把 {@code BlackBoxCore.mClientConfiguration} 换成本地 Config：
     * {@code BlackBoxCore} 本身就是 {@code ClientConfiguration} 的子类，所有配置读取都
     * 转发给这个字段，换掉即可生效；且时序在 {@code handleDumpDex}(sleep 500ms 后调
     * {@code cookieDumpDex}) 之前。</p>
     */
    private static void forceGuestConfig(Context ctx) {
        try {
            Object core = Class.forName("top.niunaijun.blackbox.BlackBoxCore")
                    .getMethod("get").invoke(null);
            java.lang.reflect.Field f = core.getClass()
                    .getDeclaredField("mClientConfiguration");
            f.setAccessible(true);
            Object cur = f.get(core);
            logLine("[dec] :p0 引擎原配置=" + (cur == null ? "null" : cur.getClass().getName())
                    + " autoCall=" + invokeBool(cur, "isAutoCallMethod")
                    + " fixCode=" + invokeBool(cur, "isFixCodeItem")
                    + " verify=" + invokeBool(cur, "isVerifyDex"));
            f.set(core, new Config(ctx, HOST_PKG));
            logLine("[dec] :p0 配置已强制为 Config(autoCall=true, fixCode=true)"
                    + " → autoCall=" + invokeBool(core, "isAutoCallMethod"));
        } catch (Throwable t) {
            logLine("[dec] 强制 :p0 配置失败：" + t);
        }
    }

    /**
     * 在 :p0 里按时间点采样，一次跑完回答四个问题：
     * <ol>
     *   <li>:p0 引擎的实际配置（isAutoCallMethod 到底 true 没有）；</li>
     *   <li>目标 App 的真实类 {@code com.coolapk.market.CoolMarketApplication} 能不能加载
     *       —— 能加载就说明壳已解密并把真实 dex 挂进了 CL（mhook 的 dump_log 就是这种状态）；</li>
     *   <li>各个候选 ClassLoader 的 dexElements 有几个、是什么（mhook 基准 =
     *       base.apk + 10 个 InMemoryDexFile）；</li>
     *   <li>壳的 native 库有没有被引擎解出来、有没有真的加载（libnesec.so）。</li>
     * </ol>
     */
    private static void startDecryptProbe(final String pkg, final Context ctx) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long[] at = {800, 2000, 4000, 8000, 15000, 30000, 50000};
                long last = 0;
                for (int i = 0; i < at.length; i++) {
                    try {
                        Thread.sleep(Math.max(50, at[i] - last));
                    } catch (Throwable ignored) {
                    }
                    last = at[i];
                    try {
                        decryptSample(pkg, ctx, i + 1, at[i]);
                    } catch (Throwable th) {
                        logLine("[dec] 采样#" + (i + 1) + " 异常：" + th);
                    }
                }
            }
        }, "fm-decrypt-probe");
        t.setDaemon(true);
        t.start();
    }

    private static void decryptSample(String pkg, Context ctx, int idx, long atMs) {
        StringBuilder sb = new StringBuilder("[dec] #" + idx + " @" + atMs + "ms");

        sb.append(" | autoCall=").append(invokeBool(safeGetCore(), "isAutoCallMethod"));
        // 引擎 dump 分支跑完会把 sDumping 置回 false、mAppConfig 置空并卸包 —— 这两个读数
        // 能直接判定「沙箱是不是在 bind+500ms 就被拆了」（拆了壳当然没机会解密）。
        try {
            Class<?> bat = Class.forName("top.niunaijun.blackbox.app.BActivityThread");
            Object dumping = readField(bat, "sDumping");
            Object appCfg = bat.getMethod("getAppConfig").invoke(null);
            sb.append(" | sDumping=").append(dumping).append(" appConfig=")
                    .append(appCfg == null ? "null(已拆)" : "ok");
        } catch (Throwable t) {
            sb.append(" | bat?(err)");
        }

        // 目标 App 的真实类能不能加载：这是「壳解密成功」最直接的判据
        ClassLoader guestCl = null;
        try {
            guestCl = (ClassLoader) Class.forName("top.niunaijun.blackbox.app.BActivityThread")
                    .getField("loadedApkClassLoader").get(null);
        } catch (Throwable ignored) {
        }
        String real = pkg + ".CoolMarketApplication";
        sb.append(" | 真实类[").append(real).append("]=").append(tryLoadName(real, guestCl));

        sb.append(" | loadedApkCL=").append(describeLoader(guestCl));
        sb.append(" | curCL=").append(describeLoader(Thread.currentThread().getContextClassLoader()));
        sb.append(" | mainAppCL=").append(describeLoader(mainAppClassLoader()));

        // 壳的 native 库：APK 里声明了哪些 / 沙箱目录里解出了哪些 / maps 里加载了哪些
        String src = null;
        String libDir = null;
        try {
            android.content.pm.ApplicationInfo ai = ctx.getApplicationInfo();
            src = ai.sourceDir;
            libDir = ai.nativeLibraryDir;
        } catch (Throwable t) {
            sb.append(" | ai?(").append(t).append(")");
        }
        sb.append(" | libDir=").append(libDir).append(" 已解出=").append(listDir(libDir));
        sb.append(" | APK内lib=").append(apkLibs(src));
        sb.append(" | maps私有so=").append(privateSos());
        logLine(sb.toString());
        // 同时写进 dump 目录，便于事后（不用用户传文件）直接核对
        probeToFile(pkg, sb.toString());
    }

    /** 把采样行追加进 {@code <dump>/<pkg>/dec_probe.txt}。 */
    private static void probeToFile(String pkg, String line) {
        java.io.FileOutputStream out = null;
        try {
            java.io.File f = new java.io.File(pkgDumpDir(pkg), "dec_probe.txt");
            out = new java.io.FileOutputStream(f, true);
            out.write((line + "\n").getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Object safeGetCore() {
        try {
            return Class.forName("top.niunaijun.blackbox.BlackBoxCore")
                    .getMethod("get").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String invokeBool(Object o, String m) {
        if (o == null) {
            return "?";
        }
        try {
            Object v = o.getClass().getMethod(m).invoke(o);
            return String.valueOf(v);
        } catch (Throwable t) {
            return "err";
        }
    }

    /** 用指定 ClassLoader 试着加载一个类名；成功=解密后的真实 dex 已就位。 */
    private static String tryLoadName(String name, ClassLoader cl) {
        try {
            Class<?> c = cl != null ? Class.forName(name, false, cl) : Class.forName(name, false,
                    FmBlackDex.class.getClassLoader());
            return "OK(" + c.getClassLoader() + ")";
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }

    /** 列出 ClassLoader 的 dexElements 数量与前几个元素（对齐 mhook 的 cl=... 打印）。 */
    private static String describeLoader(ClassLoader cl) {
        if (cl == null) {
            return "null";
        }
        try {
            Object pathList = readField(cl, "pathList");
            Object elems = pathList == null ? null : readField(pathList, "dexElements");
            if (!(elems instanceof Object[])) {
                return cl.getClass().getSimpleName() + "(no-elems)";
            }
            Object[] arr = (Object[]) elems;
            StringBuilder sb = new StringBuilder();
            sb.append(arr.length).append("{");
            for (int i = 0; i < arr.length && i < 4; i++) {
                String s = String.valueOf(arr[i]);
                if (s.length() > 90) {
                    s = s.substring(0, 90);
                }
                sb.append(i == 0 ? "" : ", ").append(s);
            }
            return sb.append("}").toString();
        } catch (Throwable t) {
            return cl.getClass().getSimpleName() + "(err:" + t.getClass().getSimpleName() + ")";
        }
    }

    /** 主线程上目标 App 的 Application 的 ClassLoader（壳换 CL 的话这里最能反映）。 */
    private static ClassLoader mainAppClassLoader() {
        final ClassLoader[] out = new ClassLoader[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Object at = Class.forName("android.app.ActivityThread")
                                .getMethod("currentActivityThread").invoke(null);
                        Object app = readField(at, "mInitialApplication");
                        if (app != null) {
                            Object cl = app.getClass().getMethod("getClassLoader").invoke(app);
                            if (cl instanceof ClassLoader) {
                                out[0] = (ClassLoader) cl;
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                }
            });
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
        return out[0];
    }

    private static String listDir(String dir) {
        if (dir == null) {
            return "?";
        }
        try {
            String[] names = new java.io.File(dir).list();
            if (names == null) {
                return "(不存在)";
            }
            java.util.TreeSet<String> set = new java.util.TreeSet<>();
            for (String n : names) {
                set.add(n.length() > 24 ? n.substring(0, 24) : n);
            }
            return set.toString();
        } catch (Throwable t) {
            return "err";
        }
    }

    /** APK 里声明的 native 库（lib/…/x.so），用于和沙箱解出来的目录对比。 */
    private static String apkLibs(String apk) {
        if (apk == null) {
            return "?";
        }
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(apk);
            java.util.TreeSet<String> set = new java.util.TreeSet<>();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("lib/") && n.endsWith(".so")) {
                    set.add(n.substring(n.lastIndexOf('/') + 1));
                }
            }
            return set.toString();
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** /proc/self/maps 里从 /data/ 加载的 .so（壳自己的 native 就在这一类里）。 */
    private static String privateSos() {
        java.io.BufferedReader r = null;
        try {
            java.util.TreeSet<String> set = new java.util.TreeSet<>();
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"));
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.indexOf("/data/");
                if (i < 0) {
                    continue;
                }
                String path = line.substring(i).trim();
                if (path.endsWith(".so")) {
                    set.add(path.substring(path.lastIndexOf('/') + 1));
                }
            }
            return set.isEmpty() ? "(无)" : set.toString();
        } catch (Throwable t) {
            return "err";
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }


    /**
     * v5.9.83：把「:p0 已经绑定目标 App」这一事实落盘，供宿主判断静默启动是否生效。
     *
     * <p>写的是目标包的输出目录（:p0 与宿主都能读写 /sdcard 下的这个目录，v5.9.81 的产物
     * 就是 :p0 里写出来的）。宿主在发起静默启动前会先删掉它，所以「文件存在」= 本轮起来了。</p>
     */
    private static void markP0Bound(String pkg) {
        try {
            File dir = pkgDumpDir(pkg);
            if (!dir.exists() && !dir.mkdirs()) {
                logLine("[sbx] 建输出目录失败：" + dir);
            }
            FileOutputStream fos = new FileOutputStream(new File(dir, MARK_P0_BOUND));
            fos.write(Long.toString(System.currentTimeMillis())
                    .getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Throwable t) {
            logLine("[sbx] 写 " + MARK_P0_BOUND + " 失败：" + t);
        }
    }

    /**
     * v5.9.88：落盘「目标 Application 已创建」标记（由 {@code FmVmStub.skipDumpBranch} 调用）。
     *
     * @param app 引擎写完的 {@code ActivityThread.mInitialApplication} 类名（壳的 Application）
     */
    public static void markAppReady(String pkg, String app) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(HOST_PKG)) {
            return;
        }
        try {
            File dir = pkgDumpDir(pkg);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            FileOutputStream fos = new FileOutputStream(new File(dir, MARK_APP_READY));
            fos.write((System.currentTimeMillis() + " " + app).getBytes(StandardCharsets.UTF_8));
            fos.close();
            logLine("[sbx] Application 已创建：" + app);
        } catch (Throwable t) {
            logLine("[sbx] 写 " + MARK_APP_READY + " 失败：" + t);
        }
    }

    // ===================== v5.9.84：:p0 侧探针（壳为何不解密） =====================
    //
    // v5.9.83 实测：脱壳库/Dobby 名字已从 :p0 的 maps 消失（「精简 :p0」成功）、静默启动也生效
    // （.p0_bound 落盘、目标 App ClassLoader 就绪），但壳【依然没解密】——全程只有
    // 97.7M(同安装包) / 188K / 632B 三个 dex。最可能是壳的「证书 SHA-256 → 密钥」派生在沙箱里
    // 拿到了错的签名（它自己的 dex 里有 signatures×16 / getPackageArchiveInfo×8 / SHA-256×11），
    // 于是静默走人不解密。这一版把 :p0 里【应用自己看到的】签名、PM 实现类、Binder 代理、
    // dex 映射数、logcat 快照都落盘，一次实测就能定性。

    private static volatile boolean sP0CrashHooked;
    private static volatile boolean sP0Probed;

    /** :p0 里记录 Java 层未捕获异常（引擎会吞异常，落到文件才看得见）。 */
    private static void installP0CrashRecorder(final String pkg) {
        if (sP0CrashHooked) {
            return;
        }
        sP0CrashHooked = true;
        try {
            final Thread.UncaughtExceptionHandler prev =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        File f = new File(pkgDumpDir(pkg), "crash_p0.txt");
                        FileOutputStream fos = new FileOutputStream(f, true);
                        fos.write((stamp() + " 线程=" + t.getName() + " 崩溃=" + e + "\n")
                                .getBytes(StandardCharsets.UTF_8));
                        java.io.PrintWriter pw = new java.io.PrintWriter(fos);
                        e.printStackTrace(pw);
                        pw.flush();
                        fos.close();
                    } catch (Throwable ignored) {
                    }
                    if (prev != null) {
                        prev.uncaughtException(t, e);
                    } else {
                        try {
                            android.os.Process.killProcess(android.os.Process.myPid());
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
            logLine("[p0] 已安装 :p0 Java 崩溃记录器");
        } catch (Throwable t) {
            logLine("[p0] 装崩溃记录器失败：" + t);
        }
    }

    /** 探针结果独立落盘：任务日志在任务结束后就不再写了（v5.9.84 就是这么丢掉 #2/#3 采样的）。 */
    private static void probeLine(String pkg, String line) {
        logLine(line);
        try {
            File dir = new File(dumpDir(), pkg);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            File f = new File(dir, "p0_probe.txt");
            if (f.length() > 1024L * 1024L) {
                return;
            }
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write((ts + " " + line + "\n").getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    /** 起探针线程：2/5/10/20/35/60/90s 七个采样点 + 收尾一份 logcat 快照。 */
    private static void startP0Probe(final String pkg, final ClassLoader cl) {
        if (sP0Probed) {
            return;
        }
        sP0Probed = true;
        probeLine(pkg, "[p0] ===== 探针开始 时间基准 t0（进程名=" + procName() + "）=====");
        new Thread(new Runnable() {
            @Override
            public void run() {
                long prev = 0;
                long[] at = {2000, 5000, 10000, 20000, 35000, 60000, 90000};
                for (int i = 0; i < at.length; i++) {
                    try {
                        Thread.sleep(at[i] - prev);
                    } catch (InterruptedException ie) {
                        return;
                    }
                    prev = at[i];
                    try {
                        p0ProbeOnce(pkg, cl, i + 1);
                    } catch (Throwable t) {
                        probeLine(pkg, "[p0] 探针#" + (i + 1) + " 异常：" + t);
                    }
                    // 壳自己（运行时才加载的 dex）不会被我们 patch，它的日志只能从 logcat 捞。
                    // 采两个中间点，避免「只有收尾快照」而错过中途发生的自毁/退出。
                    if (i + 1 == 3 || i + 1 == 5) {
                        dumpP0Logcat(pkg, "logcat_p0_" + at[i] / 1000 + "s.txt");
                    }
                }
                dumpP0Logcat(pkg, "logcat_p0_final.txt");
            }
        }, "fm-p0-probe").start();
    }

    /** 把读数投递到主线程执行（Application/ClassLoader 只在主线程可见）。 */
    private static void probeMainThread(final String pkg, final int round, final String tag) {
        try {
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final String[] out = {"<超时>"};
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        out[0] = describeAppOnMain();
                    } catch (Throwable t) {
                        out[0] = "异常：" + t;
                    } finally {
                        latch.countDown();
                    }
                }
            });
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
            probeLine(pkg, tag + "主线程读数 " + out[0]);
        } catch (Throwable t) {
            probeLine(pkg, tag + "主线程读数失败：" + t);
        }
    }

    /** 主线程里读目标 App 的真实状态（Application、壳类是否已加载、dex 元素清单）。 */
    private static String describeAppOnMain() {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> bt = Class.forName("top.niunaijun.blackbox.app.BActivityThread");
            Object app = bt.getMethod("getApplication").invoke(null);
            sb.append("目标App=").append(app == null ? "null" : app.getClass().getName());
            sb.append(" AppConfig=").append(bt.getMethod("getAppConfig").invoke(null) == null ? "null" : "有");
            sb.append(" sDumping=").append(bt.getField("sDumping").get(null));
            ClassLoader cl = null;
            if (app instanceof android.content.Context) {
                cl = ((android.content.Context) app).getClassLoader();
            }
            if (cl == null) {
                Object l = bt.getField("loadedApkClassLoader").get(null);
                if (l instanceof ClassLoader) {
                    cl = (ClassLoader) l;
                }
            }
            sb.append(" 壳类已加载=").append(isClassLoaded(cl, "com.netease.nis.wrapper.MyApplication"));
            sb.append(" 业务类已加载=").append(isClassLoaded(cl, "com.coolapk.market.app.CoolapkApplication"));
            sb.append(' ').append(dexInventory(cl));
        } catch (Throwable t) {
            sb.append(" 异常=").append(t);
        }
        return sb.toString();
    }

    /** 沿类层次读字段（读不到返回 null）。 */
    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 列出 ClassLoader 的 dex 元素（文件名/大小；内存 dex 给容量）。只看不写盘。 */
    private static String dexInventory(ClassLoader cl) {
        if (cl == null) {
            return "dex元素=<无 ClassLoader>";
        }
        try {
            Object pathList = readField(cl, "pathList");
            Object els = readField(pathList, "dexElements");
            if (!(els instanceof Object[])) {
                return "dex元素=<取不到>";
            }
            Object[] arr = (Object[]) els;
            StringBuilder sb = new StringBuilder("dex元素=").append(arr.length).append('[');
            for (int i = 0; i < arr.length; i++) {
                Object dexFile = readField(arr[i], "dexFile");
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(describeDexFile(dexFile));
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "dex元素=<异常 " + t + ">";
        }
    }

    private static String describeDexFile(Object dexFile) {
        if (dexFile == null) {
            return "null";
        }
        String label = dexFile.getClass().getSimpleName();
        try {
            Object n = dexFile.getClass().getMethod("getName").invoke(dexFile);
            if (n != null && !String.valueOf(n).isEmpty()) {
                String path = String.valueOf(n);
                File f = new File(path);
                label = f.getName() + "(" + (f.exists() ? f.length() / 1024 + "K" : "?") + ")";
                return label;
            }
        } catch (Throwable ignored) {
        }
        // 内存 dex（InMemoryDexFile）：没有文件名，用 ByteBuffer 容量当大小
        try {
            for (java.lang.reflect.Field f : dexFile.getClass().getDeclaredFields()) {
                if (java.nio.ByteBuffer.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object b = f.get(dexFile);
                    if (b instanceof java.nio.ByteBuffer) {
                        label += "(" + ((java.nio.ByteBuffer) b).capacity() / 1024 + "K)";
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return label;
    }

    /** 这个类是否已经被某个 ClassLoader 加载过（不动手加载，除非回退路径）。 */
    private static boolean isClassLoaded(ClassLoader cl, String name) {
        if (cl == null) {
            return false;
        }
        try {
            java.lang.reflect.Method m = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            m.setAccessible(true);
            return m.invoke(cl, name) != null;
        } catch (Throwable ignored) {
        }
        try {
            Class.forName(name, false, cl);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void p0ProbeOnce(String pkg, ClassLoader cl, int round) {
        final String tag = "[p0] 探针#" + round + " ";
        Application app = null;
        Object cfg = null;
        try {
            Class<?> bt = Class.forName("top.niunaijun.blackbox.app.BActivityThread");
            Object o = bt.getMethod("getApplication").invoke(null);
            if (o instanceof Application) {
                app = (Application) o;
            }
            cfg = bt.getMethod("getAppConfig").invoke(null);
        } catch (Throwable t) {
            probeLine(pkg, tag + "取目标 App 失败：" + t);
        }
        probeLine(pkg, tag + "进程名=" + procName() + " 目标App="
                + (app == null ? "null" : app.getClass().getName())
                + " AppConfig包名=" + (cfg == null ? "null" : cfgField(cfg, "packageName"))
                + " sourceDir=" + cfgField(cfg, "sourceDir"));
        // ★ 上面那条的「目标App」走的是 BActivityThread.currentActivityThread()，那是 ThreadLocal：
        //   探针线程不是主线程，永远拿到 null（v5.9.84~86 连续误判「Application 没建起来」就是这个假象）。
        //   所以真实读数必须投递到主线程执行。
        probeMainThread(pkg, round, tag);
        // ★ 这三条不依赖 app，是「壳自己要比较的那两份签名」的直接证据
        probeLine(pkg, tag + "引擎PM签名(目标)=" + enginePmSig(pkg)
                + " | 引擎PM签名(宿主)=" + enginePmSig(HOST_PKG));
        probeLine(pkg, tag + "BinderPM签名(目标)=" + binderPmSig(pkg));
        if (app != null) {
            try {
                android.content.pm.PackageManager pm = app.getPackageManager();
                probeLine(pkg, tag + "PM实现类=" + (pm == null ? "null" : pm.getClass().getName()));
                String shell = null;
                String src = null;
                try {
                    android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    shell = ai.className;
                    src = ai.sourceDir;
                } catch (Throwable t) {
                    probeLine(pkg, tag + "getApplicationInfo(目标) 失败：" + t);
                }
                probeLine(pkg, tag + "目标 sourceDir=" + src + " 壳App类=" + shell);
                probeLine(pkg, tag + "签名 AppPM(目标)=" + pkgSigSha(app, pkg)
                        + " AppPM(宿主)=" + pkgSigSha(app, HOST_PKG));
                probeLine(pkg, tag + "签名 ArchiveInfo(sourceDir)=" + archiveSigSha(app, src));
                if (shell != null && cl != null) {
                    try {
                        Class<?> c = cl.loadClass(shell);
                        probeLine(pkg, tag + "壳App类可加载=true loader=" + c.getClassLoader());
                    } catch (Throwable t) {
                        probeLine(pkg, tag + "壳App类加载失败：" + t);
                    }
                }
            } catch (Throwable t) {
                probeLine(pkg, tag + "PM 探测异常：" + t);
            }
        }
        try {
            Object svc = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "package");
            probeLine(pkg, tag + "package 服务=" + (svc == null ? "null" : svc.getClass().getName()));
        } catch (Throwable t) {
            probeLine(pkg, tag + "package 服务探测失败：" + t);
        }
        probeLine(pkg, tag + countDexMaps());
        probeLine(pkg, tag + mapsSummary());
    }

    private static String cfgField(Object cfg, String name) {
        try {
            Object v = cfg.getClass().getField(name).get(cfg);
            return String.valueOf(v);
        } catch (Throwable t) {
            return "(取不到)";
        }
    }

    /** 引擎服务端 PM（= 引擎自己安装/查询包用的那条链）。 */
    private static String enginePmSig(String pkg) {
        try {
            Object bpm = Class.forName("top.niunaijun.blackbox.BlackBoxCore")
                    .getMethod("getBPackageManager").invoke(null);
            Object pi = bpm.getClass()
                    .getMethod("getPackageInfo", String.class, int.class, int.class)
                    .invoke(bpm, pkg, 64, 0);
            return signerShaOf((android.content.pm.PackageInfo) pi);
        } catch (Throwable t) {
            return "(失败 " + t + ")";
        }
    }

    /** 走 Binder「package」服务：壳经 Context.getPackageManager() 实际走的那条链。 */
    private static String binderPmSig(String pkg) {
        try {
            Object svc = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "package");
            if (svc == null) {
                return "(服务为 null)";
            }
            Class<?> ipm = Class.forName("android.content.pm.IPackageManager");
            Object pi = ipm.getMethod("getPackageInfo", String.class, int.class, int.class)
                    .invoke(svc, pkg, 64, 0);
            return signerShaOf((android.content.pm.PackageInfo) pi)
                    + " 实现类=" + svc.getClass().getName();
        } catch (Throwable t) {
            return "(失败 " + t + ")";
        }
    }

    /** maps 里「脱壳/框架库名」是否可见（壳扫这些名字做环境检测）。 */
    private static String mapsSummary() {
        final String[] keys = {"blackdex", "godump", "cdumpdex", "libdump", "dumpdex",
                "dobby", "epic", "xposed", "sandhook", "substrate", "npatch",
                // 壳自己的 native 库：它在 maps 里出现 = 壳的 native 部分真的起来了
                // （这是判断「壳到底跑到哪一步」最直接的旁证）
                "nesec", "libblackbox"};
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"));
            java.util.LinkedHashSet<String> hits = new java.util.LinkedHashSet<String>();
            String line;
            while ((line = r.readLine()) != null) {
                String lower = line.toLowerCase(Locale.US);
                for (String k : keys) {
                    if (lower.contains(k)) {
                        hits.add(k);
                    }
                }
            }
            return "maps关键字命中=" + (hits.isEmpty() ? "(无)" : hits.toString());
        } catch (Throwable t) {
            return "maps扫描失败=" + t;
        } finally {
            try {
                if (r != null) {
                    r.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 数一数 /proc/self/maps 里的 dex 类映射（壳解密出来的 dex 常是 memfd/匿名映射）。 */
    private static String countDexMaps() {
        int total = 0;
        int dex = 0;
        int memfd = 0;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(
                    "/proc/self/maps"));
            String line;
            while ((line = r.readLine()) != null) {
                total++;
                if (line.contains(".dex")) {
                    dex++;
                }
                if (line.contains("memfd") || line.contains("/dev/ashmem")) {
                    memfd++;
                }
            }
            r.close();
        } catch (Throwable t) {
            return "maps 读取失败：" + t;
        }
        return "maps 行数=" + total + " 含.dex=" + dex + " memfd/ashmem=" + memfd;
    }

    /** 把 :p0 自己的 logcat 快照落盘（应用只能看自己进程的日志，但 :p0 就是壳的进程）。 */
    private static void dumpP0Logcat(String pkg, String name) {
        try {
            java.lang.Process p = Runtime.getRuntime().exec(
                    new String[]{"logcat", "-d", "-v", "time", "-t", "600"});
            java.io.InputStream is = p.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long end = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < end) {
                int n = is.read(buf);
                if (n <= 0) {
                    break;
                }
                bos.write(buf, 0, n);
            }
            p.destroy();
            FileOutputStream fos = new FileOutputStream(new File(pkgDumpDir(pkg), name));
            fos.write(bos.toByteArray());
            fos.close();
            logLine("[p0] logcat 快照 " + name + "=" + bos.size() + "B");
        } catch (Throwable t) {
            logLine("[p0] logcat 快照失败：" + t);
        }
    }

    // ===================== v5.9.82 根因诊断：壳为何在沙箱里自毁 =====================

    /**
     * 网易易盾的 {@code libnesec.so} 在我们沙箱 :p0 里必 SIGSEGV（早于本 dump 内核就存在，
     * 而 mhook 的引擎能跑通）。两条最可能的原因：
     * <ol>
     *   <li>引擎安装时改写了目标 APK（补 stub、重签）→ 壳自校验签名/摘要不过；</li>
     *   <li>壳扫 {@code /proc/self/maps} 看到了我们的脱壳 native 库（libblackdex/libgodump…）。</li>
     * </ol>
     * 一次跑完就能定性：沙箱副本与原包的大小 + SHA-256、三处签名 SHA-256、maps 命中项。
     */
    private static void sandboxDiag(String pkg, Context ctx) {
        try {
            String sandboxApk = null;
            String appClass = null;
            try {
                android.content.pm.ApplicationInfo ai = ctx.getApplicationInfo();
                if (ai != null) {
                    sandboxApk = ai.sourceDir;
                    appClass = ai.className;
                }
            } catch (Throwable t) {
                logLine("[diag] 取 ApplicationInfo 失败：" + t);
            }
            String srcApk = taskSrcApk();
            logLine("[diag] 沙箱 sourceDir=" + sandboxApk + " appClass=" + appClass);
            logLine("[diag] 本次原始 APK=" + srcApk);

            // 1) 文件级：大小 + SHA-256（完全一致 = 引擎没动过这个文件）
            if (sandboxApk != null) {
                File f = new File(sandboxApk);
                logLine("[diag] 沙箱副本 size=" + f.length() + " sha256=" + fileSha256(f));
            }
            if (srcApk != null) {
                File f = new File(srcApk);
                logLine("[diag] 原始 APK size=" + f.length() + " sha256=" + fileSha256(f));
            }

            // 2) 签名级：壳用证书 SHA-256 派生解密密钥，三处取值必须一致
            logLine("[diag] 签名 PackageManager(" + pkg + ")=" + pkgSigSha(ctx, pkg));
            logLine("[diag] 签名 ArchiveInfo(沙箱副本)=" + archiveSigSha(ctx, sandboxApk));
            logLine("[diag] 签名 ArchiveInfo(原包)=" + archiveSigSha(ctx, srcApk));

            // 3) maps：壳扫 /proc/self/maps 能看到我们哪些库
            logSuspiciousMaps();

            // 4) 目录：logcat 里看到 "Failed to ensure /storage/emulated/0/Android/data/
            //    com.coolapk.market/cache: SecurityException" —— 壳写不了自己的外部目录时
            //    很可能在后面某处拿到 null 就崩了，这里把每个关键目录的可写性实测一遍。
            logDirProbe("filesDir", safePath(ctx.getFilesDir()));
            logDirProbe("cacheDir", safePath(ctx.getCacheDir()));
            logDirProbe("externalFilesDir", safePath(ctx.getExternalFilesDir(null)));
            logDirProbe("externalCacheDir", safePath(ctx.getExternalCacheDir()));
            logDirProbe("dataDir", safePath(ctx.getDataDir()));
            try {
                logLine("[diag] codeCacheDir=" + safePath(ctx.getCodeCacheDir())
                        + " noBackupFilesDir=" + safePath(ctx.getNoBackupFilesDir()));
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            logLine("[diag] 异常：" + t);
        }
    }

    private static String safePath(File f) {
        try {
            return f == null ? "(null)" : f.getAbsolutePath();
        } catch (Throwable t) {
            return "(异常)";
        }
    }

    /** 目录「存在 / 能建 / 能写」实测：壳常拿 getExternalFilesDir 之类的返回值直接当非空用。 */
    private static void logDirProbe(String tag, String path) {
        if (path == null || path.startsWith("(")) {
            logLine("[diag] " + tag + "=" + path);
            return;
        }
        File d = new File(path);
        boolean exists = d.isDirectory();
        boolean mk = exists;
        if (!mk) {
            try {
                mk = d.mkdirs();
            } catch (Throwable ignored) {
            }
        }
        boolean wr = false;
        try {
            File p = new File(d, ".fm_probe");
            java.io.FileOutputStream out = new java.io.FileOutputStream(p);
            out.write(1);
            out.close();
            wr = true;
            p.delete();
        } catch (Throwable ignored) {
        }
        logLine("[diag] " + tag + "=" + path + " 存在=" + exists + " 能建=" + mk + " 能写=" + wr);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String fileSha256(File f) {
        if (f == null || !f.isFile()) {
            return "(不存在)";
        }
        FileInputStream in = null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(f);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return hex(md.digest());
        } catch (Throwable t) {
            return "(失败)";
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 某个 APK 文件里的签名证书 SHA-256（壳取证书也是走 PackageManager 这条路）。 */
    private static String archiveSigSha(Context ctx, String apkPath) {
        if (apkPath == null) {
            return "(无路径)";
        }
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            int flags = android.os.Build.VERSION.SDK_INT >= 28
                    ? android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                    : android.content.pm.PackageManager.GET_SIGNATURES;
            return signerShaOf(pm.getPackageArchiveInfo(apkPath, flags));
        } catch (Throwable t) {
            return "(失败 " + t + ")";
        }
    }

    /** 沙箱里这个包当前对外呈现的签名证书 SHA-256。 */
    private static String pkgSigSha(Context ctx, String pkg) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            int flags = android.os.Build.VERSION.SDK_INT >= 28
                    ? android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                    : android.content.pm.PackageManager.GET_SIGNATURES;
            return signerShaOf(pm.getPackageInfo(pkg, flags));
        } catch (Throwable t) {
            return "(失败 " + t + ")";
        }
    }

    private static String signerShaOf(android.content.pm.PackageInfo pi) {
        if (pi == null) {
            return "(解析失败)";
        }
        try {
            android.content.pm.Signature[] sigs = null;
            if (android.os.Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                sigs = pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getApkContentsSigners()
                        : pi.signingInfo.getSigningCertificateHistory();
                if (sigs == null || sigs.length == 0) {
                    sigs = pi.signingInfo.getApkContentsSigners();
                }
            }
            if ((sigs == null || sigs.length == 0) && pi.signatures != null) {
                sigs = pi.signatures;
            }
            if (sigs == null || sigs.length == 0) {
                return "(无签名)";
            }
            StringBuilder sb = new StringBuilder();
            for (android.content.pm.Signature s : sigs) {
                sb.append(hex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(s.toByteArray()))).append(' ');
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "(失败 " + t + ")";
        }
    }

    /** 扫自身 maps，把「脱壳/框架类」库名和匿名可执行段统计出来。 */
    private static void logSuspiciousMaps() {
        final String[] keys = {"blackdex", "godump", "cdumpdex", "libdump", "dumpdex",
                "dobby", "epic", "xposed", "whale", "sandhook", "frida", "substrate",
                "npatch", "blackbox"};
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"));
            java.util.LinkedHashSet<String> hits = new java.util.LinkedHashSet<String>();
            int total = 0;
            int anonExec = 0;
            String line;
            while ((line = r.readLine()) != null) {
                total++;
                String lower = line.toLowerCase();
                int sp = line.lastIndexOf(' ');
                String path = sp > 0 ? line.substring(sp + 1).trim() : "";
                if (lower.length() > 4 && lower.charAt(2) == 'x'
                        && (path.isEmpty() || path.startsWith("["))) {
                    anonExec++;
                }
                if (!path.isEmpty() && !path.startsWith("[")) {
                    for (String k : keys) {
                        if (lower.contains(k)) {
                            hits.add(path);
                            break;
                        }
                    }
                }
            }
            logLine("[diag] maps 行数=" + total + " 匿名/特殊可执行段=" + anonExec);
            logLine("[diag] maps 命中脱壳/框架关键字（壳扫得到这些名字）："
                    + (hits.isEmpty() ? "（无）" : ""));
            int i = 0;
            for (String h : hits) {
                if (++i > 12) {
                    logLine("[diag]   ...");
                    break;
                }
                logLine("[diag]   " + h);
            }
        } catch (Throwable t) {
            logLine("[diag] maps 读取失败：" + t);
        } finally {
            try {
                if (r != null) {
                    r.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 载体进程形如 {@code com.mtstyle.fm:p0}；引擎服务进程 {@code :black} 必须排除。 */
    private static boolean isSandboxAppProcess(String procName) {
        return procName != null && procName.contains(":") && !procName.endsWith(":black");
    }

    /** 当前进程名（宿主进程和 :black 进程都要各自豁免一次）。 */
    private static String procName() {
        try {
            return android.app.Application.getProcessName();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /**
     * 解除 hidden API 限制。BlackBox 只调了 FreeReflection 的 unseal()，它在 Android 14+
     * 基本失效；这里补上引擎里已经打包、却没被调用的 LSPosed HiddenApiBypass，并做一次
     * 反射自测确认。豁免是按进程生效的，所以宿主进程与 :black 进程各跑一次。
     */
    private static void unsealHiddenApi() {
        try {
            Class<?> bypass = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass");
            java.lang.reflect.Method add = bypass.getMethod("addHiddenApiExemptions", String[].class);
            Object ok = add.invoke(null, (Object) new String[]{""});
            Log.i(TAG, "HiddenApiBypass.addHiddenApiExemptions=" + ok);
        } catch (Throwable t) {
            Log.w(TAG, "HiddenApiBypass 不可用：" + describe("unseal", t));
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method cur = at.getDeclaredMethod("currentActivityThread");
            cur.setAccessible(true);
            cur.invoke(null);
            hiddenApiOk = true;
        } catch (Throwable t) {
            hiddenApiOk = false;
            Log.w(TAG, "hidden API 自测失败：" + describe("probe", t));
        }
    }

    /** 首次真正要用引擎时才拉起服务进程。 */
    private static synchronized boolean ensureStarted() {
        if (started) {
            return true;
        }
        if (!attached) {
            error = "引擎未初始化";
            return false;
        }
        try {
            long begin = System.currentTimeMillis();
            // 必须用 BlackDexCore.doCreate()：它在 BlackBoxCore.doCreate() 之后还会把沙箱里
            // 已装的应用全部卸载。v5.9.3 曾为「省一次安装」改成 BlackBoxCore.doCreate()，
            // 结果残留的安装状态让 dump 不再触发，界面卡在「正在脱壳」直到超时，已回退。
            BlackDexCore.get().doCreate();
            logJarEnv();
            started = true;
            logLine("[engine] 沙箱服务已就绪（" + (System.currentTimeMillis() - begin) + "ms）");
        } catch (Throwable t) {
            error = describe("启动沙箱", t);
            logLine("[error] " + error);
            return false;
        }
        try {
            BlackDexCore.get().registerDumpMonitor(new Monitor());
        } catch (Throwable t) {
            // 注册不上只影响进度显示，脱壳本身继续
            logLine("[engine] 进度回调注册失败（不影响脱壳）");
            Log.w(TAG, "registerDumpMonitor: " + t);
        }
        return true;
    }

    /** 引擎是否可用（已认领宿主即可；服务进程按需启动）。 */
    public static boolean isAvailable() {
        return attached;
    }

    /**
     * v5.9.3 预热：进入脱壳页时就让沙箱服务在后台起来。
     *
     * <p>冷启动要拉起 :black 服务进程 + 四大 Binder 服务，通常 1~3 秒。
     * 放在用户「挑应用」的这段时间里做掉，点开始脱壳时就能直接进入安装/启动环节。</p>
     */
    public static void warmUp() {
        if (!attached || started || busy) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                ensureStarted();
            }
        }, "fm-dump-warmup").start();
    }

    public static String lastError() {
        return error == null ? "" : error;
    }

    public static boolean isRunning() {
        return busy;
    }

    // ==================== 结果目录与日志 ====================

    /**
     * 结果目录：优先 Download/脱壳结果（用户可见），实在不可用时回退到应用外部私有目录。
     *
     * <p>刻意不缓存：引擎（宿主进程与沙箱进程）和界面都会调用它，
     * 每次都按同一套判定（能不能真的建出目录）算出来，才能保证两边指到同一个地方。</p>
     */
    public static File dumpDir() {
        File cached = dumpDirCache;
        if (cached != null) {
            return cached;
        }
        File dir = openDumpDir();
        dumpDirCache = dir;
        return dir;
    }

    /**
     * 打不开固定路径时的兜底探测，只在第一次（或 attach 后）真正跑一次。
     *
     * <p><b>为什么固定路径必须排在 {@code Environment} 前面：</b>沙箱进程（{@code :p0}）里
     * {@code Environment.getExternalStoragePublicDirectory()} 会被引擎的沙箱路径重定向接管，
     * 拿到的是虚拟目录，于是「标记文件 .memscan_running / 任务日志 / 产物」在宿主进程看来
     * 根本不存在。实测后果是 watchdog 第 3 秒看到「有壳自己的 hook dex、没有标记」就判定成功
     * 收工，5 轮内存扫描一轮都没跑完，用户看到「成功却只有 2 个 hook dex」。</p>
     *
     * <p>引擎自己写 dex 落盘用的就是真实绝对路径（{@code BlackBoxCore.getDexDumpDir()}），
     * 已验证沙箱进程可以直接写，所以这里照做：两进程各算各的，但算出来是同一个字符串。</p>
     */
    private static File openDumpDir() {
        for (String path : FIXED_DUMP_ROOTS) {
            try {
                File dir = new File(path);
                if (dir.isDirectory() || dir.mkdirs()) {
                    return dir;
                }
            } catch (Throwable ignored) {
                // 试下一个候选
            }
        }
        try {
            File base = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (base != null) {
                File dir = new File(base, "脱壳结果");
                if (dir.isDirectory() || dir.mkdirs()) {
                    return dir;
                }
            }
        } catch (Throwable ignored) {
            // 继续走回退分支
        }
        File alt = new File(privDir(), "dump");
        alt.mkdirs();
        return alt;
    }

    private static File privDir() {
        File dir = null;
        Context context = appContext;
        if (context != null) {
            try {
                dir = context.getExternalFilesDir(null);
            } catch (Throwable ignored) {
                dir = null;
            }
        }
        // 沙箱进程（:p0）里 Context 已被引擎换成目标 App 的，getExternalFilesDir 要走 Binder 到
        // IStorageManagerService；:black 那边没接管时直接 DeadObjectException。所以整段回退都必须
        // 兜住：老代码里 Environment.getExternalStorageDirectory() 裸露在 try 外，一炸就把调用链
        // 打断（实测堆栈 FmMemScan.scan → logLine → publicLogFile → dumpDir → Environment → 崩）。
        if (dir == null) {
            for (String p : FIXED_PRIV_ROOTS) {
                try {
                    File f = new File(p);
                    if (f.isDirectory() || f.mkdirs()) {
                        return f;
                    }
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
            dir = new File("/data/data/" + HOST_PKG + "/files");
        }
        try {
            dir.mkdirs();
        } catch (Throwable ignored) {
        }
        return dir;
    }

    /** 引擎日志（界面轮询它显示进度）。 */
    /** 跨进程探针：把「哪个进程、什么时刻、执行到我们的哪段代码」追加进产物目录的文件。
     *
     *  <p>沙箱进程 :p0 里打的任何日志都不会出现在界面上，前几轮排查「只有 1 个壳 dex」只能猜。
     *  这个文件由沙箱脱壳页转发到界面，能一锤定音地看出 :p0 里 {@code FmApp.attachBaseContext}
     *  → {@code FmBlackDex.attach} → {@code FmDexGrabber.run} 这条链到底断在哪一环。</p>
     */
    public static void probe(String tag) {
        try {
            File dir = dumpDir();
            if (dir == null) {
                return;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            File f = new File(dir, "_probe.log");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
            String line = "[" + tag + "] proc=" + procName()
                    + " pid=" + android.os.Process.myPid()
                    + " t=" + System.currentTimeMillis() + "\n";
            fos.write(line.getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    /** 本次目标的专属日志文件（产物目录/脱壳日志.txt）。
     *
     *  <p>沙箱进程 :p0 里 {@code FmDexGrabber} 打的 {@code [grab]}/{@code [mem]} 诊断行只会落进
     *  这个文件，界面上的进度回调查不到它们。沙箱脱壳页据此把新行增量转发到界面，
     *  否则「只有 1 个壳 dex」这类问题永远只能靠猜。</p>
     */
    public static File taskLog() {
        return taskLogFile;
    }

    public static File logFile() {
        File cached = logFile;
        if (cached != null) {
            return cached;
        }
        File file = new File(privDir(), "dump_engine.log");
        logFile = file;
        return file;
    }

    /**
     * 界面退出、或用户重新点「开始脱壳」时调用：把「正在脱壳」状态和内存扫描残留标记一起清掉。
     *
     * <p>不清的话，下一次进来的 startDump 会被残留的 busy 直接顶回 false，
     * 界面就瞬间跳到失败页，而且报的还是牛头不对马嘴的「引擎不可用」。</p>
     */
    public static void abort() {
        if (!busy) {
            return;
        }
        busy = false;
        settling = false;
        memWasRunning = false;
        synchronized (BUSINESS_CHECKED) {
            BUSINESS_CHECKED.clear();
        }
        clearMemScanMark(currentPkg);
    }

    /** 当前是否真有一次脱壳在跑。界面用它区分「引擎不可用」与「上一次还没结束」。 */
    public static boolean isBusy() {
        return busy;
    }

    public static long logLength() {
        File file = logFile();
        return file.isFile() ? file.length() : 0L;
    }

    /** 追加一行日志（多进程安全由 "a" 模式保证）。 */
    static void logLine(String line) {
        appendPublic(line);
        if (line == null) {
            return;
        }
        try {
            FileOutputStream out = new FileOutputStream(logFile(), true);
            try {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // 日志写不进去不影响脱壳
        }
        // 再写一份到本次目标的产物目录：全局日志是跨任务累积的（resetPublicLog 只在文件
        // 不存在时补头部），而结果页「查看日志」要看的是「这一次、这个包」的过程。
        File task = taskLogFile;
        if (task == null) {
            return;
        }
        try {
            FileOutputStream out = new FileOutputStream(task, true);
            try {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // 同上，日志失败不影响脱壳
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
    }

    /** 读取日志里 offset 之后新增的完整行。 */
    public static List<String> readNewLines(long offset, long[] newOffset) {
        List<String> lines = new ArrayList<>();
        newOffset[0] = offset;
        File file = logFile();
        if (!file.isFile() || file.length() <= offset) {
            return lines;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            raf.seek(offset);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8 * 1024];
            int read;
            while ((read = raf.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            byte[] data = buffer.toByteArray();
            int lastBreak = -1;
            for (int i = data.length - 1; i >= 0; i--) {
                if (data[i] == '\n') {
                    lastBreak = i;
                    break;
                }
            }
            newOffset[0] = offset + (lastBreak < 0 ? 0 : lastBreak + 1);
            if (lastBreak < 0) {
                return lines;
            }
            String text = new String(data, 0, lastBreak, StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
        } catch (Throwable ignored) {
            newOffset[0] = offset;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
        return lines;
    }

    // ==================== 脱壳 ====================

    /**
     * 把目标交给内嵌引擎。
     *
     * @param source        包名（packageSource=true）或 APK 绝对路径
     * @param packageSource 目标是本机已安装应用
     * @return 是否成功把任务发出去（true 表示后续进度看日志）
     */
    public static boolean startDump(final String source, final boolean packageSource) {
        if (source == null || source.trim().isEmpty()) {
            return false;
        }
        if (busy) {
            long age = System.currentTimeMillis() - busySince;
            if (age < STALE_BUSY_MS) {
                // 确实还有任务在跑，不抢占，让界面去提示「上一次还没结束」
                return false;
            }
            // 上一次任务已经僵死：页面被关、进程被杀，或线程卡在引擎调用里没回来。
            // 现象就是「脱壳失败后立刻再脱，一进去就跳失败页」——被这里残留的 busy 顶掉的。
            busy = false;
            clearMemScanMark(currentPkg);
        }
        busy = true;
        busySince = System.currentTimeMillis();
        gotResult = false;
        settling = false;
        gotCallback = false;
        pollTick = 0;
        retryCount = 0;
        memWasRunning = false;
        sawSandbox = false;
        crashLogGrabbed = false;
        synchronized (BUSINESS_CHECKED) {
            BUSINESS_CHECKED.clear();
        }
        sessionStart = System.currentTimeMillis();
        final String target = source.trim();
        packageName = packageSource ? target : null;
        currentPkg = pkgNameOf(target, packageSource);
        // 立刻把「本次任务包名」写进共享目录：:p0 里那份 task_log_path 可能还是上一个任务的，
        // 于是它拿着别人的包名去查 manifest、等 ClassLoader，最后永远只出同一个壳 dex。
        rememberTaskPkg(currentPkg);
        rememberTaskSrcApk(resolveSrcApk(packageSource, target));
        resetPublicLog();
        // 上次脱壳若在扫描中途被打断，标记文件会残留，会把这次的 watchdog 一直卡住
        clearMemScanMark(pkgNameOf(target, packageSource));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 先定下本次目标，日志从第一行起就写进它自己的产物目录，
                    // 这样结果页「查看日志」拿到的就是这一次的完整过程。
                    beginTaskLog(pkgNameOf(target, packageSource));
                    logLine("---- " + stamp() + " 新任务 ----");
                    logLine("[start] " + (packageSource ? "pkg=" : "path=") + target);
                    if (!ensureStarted()) {
                        logLine("[error] " + error);
                        busy = false;
                        return;
                    }
                    // v5.9.7 关键修复：每次脱壳前把沙箱复位。
                    // 引擎的 dump 挂钩挂在沙箱应用「进程创建 → Application 构造」时，只要沙箱里还留着
                    // 上一次的安装、或上一次的沙箱进程还活着，再启动就不会重走这段流程，于是永远等不到结果
                    // ——这正是「第一次脱壳成功，之后一直卡在正在脱壳」的原因。
                    // 先把上一轮 seal 过的 jar/dex 放回可写，免得这次 install 拷新 jar 失败
                    sealSandboxDex(false);
                    resetSandbox(packageSource ? target : null);
                    lastProgressAt = System.currentTimeMillis();
                    long begin = System.currentTimeMillis();
                    // 每次都重新安装：引擎靠「全新安装 + 启动」来触发 dex 抽取，
                    // 复用上一次的安装状态会让 dump 不触发（见上面 doCreate 的说明）。
                    // 也刻意不用 BlackDexCore.dumpDex()：它把 install 与 launch 的失败混成 null 返回，
                    // 而 launchApk 的返回值常为 false（沙箱其实已经起来并跑完）——那会导致「脱壳明明成功却报失败」。
                    InstallResult install = packageSource
                            ? BlackBoxCore.get().installPackage(target)
                            : BlackBoxCore.get().installPackage(new File(target));
                    if (install == null || !install.success) {
                        // 沙箱可能已被系统回收或状态过期：重建一次再试，别让用户干等
                        logLine("[engine] 安装失败，重建沙箱后重试…");
                        started = false;
                        if (ensureStarted()) {
                            install = installToSandbox(packageSource, target);
                        }
                    }
                    if (install == null || !install.success) {
                        logLine("[error] 安装失败："
                                + (install == null || install.msg == null ? "这个包装不进沙箱（可能是系统应用或加固后无法解析）" : install.msg));
                        busy = false;
                        return;
                    }
                    packageName = install.packageName;
                    logLine("[engine] 已装进沙箱：" + packageName
                            + "（" + (System.currentTimeMillis() - begin) + "ms）");
                    // 装完立刻把沙箱 jar/dex 设成只读：Android 14+ 拒绝加载可写路径下的 dex，
                    // 否则 :p0 里 bindApplication 必抛 SecurityException（Writable dex file
                    // ... is not allowed），目标 App 根本起不来，后面全白做。
                    logLine("[engine] 沙箱 dex 只读化：" + sealSandboxDex(true) + " 个");
                    boolean launched = launchInSandbox(packageName,
                            resolveApk(packageSource, target));
                    logLine("[engine] 启动沙箱应用：" + (launched ? "已启动" : "返回失败"));
                    if (!launched) {
                        // v5.9.5 曾把这里写成「忽略返回值」，结果是真实失败被吞掉、界面只能无限等。
                        // 引擎自己的判定就是「launch 失败 = 这次没戏」，所以重建一次沙箱再试。
                        logLine("[engine] 首次启动失败，复位沙箱后重试…");
                        started = false;
                        sealSandboxDex(false);
                        resetSandbox(packageName);
                        if (ensureStarted()) {
                            InstallResult again = installToSandbox(packageSource, target);
                            if (again != null && again.success) {
                                packageName = again.packageName;
                                sealSandboxDex(true);
                                launched = launchInSandbox(packageName,
                                        resolveApk(packageSource, target));
                                logLine("[engine] 重试启动：" + (launched ? "已启动" : "仍失败"));
                            }
                        }
                    }
                    if (!launched) {
                        busy = false;
                        logLine("[error] 沙箱没能启动这个应用（多是上一次的残留状态或系统回收导致）。"
                                + "请返回再点一次；若仍失败，强行停止本应用后重开。");
                        return;
                    }
                    logLine("[loading] 沙箱应用已启动，正在抽取 dex…");
                    watchdog();
                } catch (Throwable t) {
                    logLine("[error] " + describe("脱壳", t));
                    busy = false;
                }
            }
        }, "fm-dump").start();
        return true;
    }

    /**
     * 装进沙箱。按包名（已安装应用）时引擎走 installBySystem：直接把宿主的 PackageInfo
     * 交给沙箱，沙箱侧解析出来的组件表常常匹配不到 MAIN/LAUNCHER（现象就是 install 只花
     * 一百多毫秒、紧接着 launchApk 必然 false）。所以这里优先拿该包真实的 apk 路径按
     * 「文件」方式安装，让沙箱自己重新解析 manifest；不成功再退回引擎自带方式。
     */
    private static InstallResult installToSandbox(boolean packageSource, String target) {
        File apk = resolveApk(packageSource, target);
        if (apk != null) {
            probeParser(apk);
        }
        if (!packageSource) {
            return BlackBoxCore.get().installPackage(new File(target));
        }
        if (apk != null) {
            InstallResult r = BlackBoxCore.get().installPackage(apk);
            if (r != null && r.success) {
                logLine("[engine] 按文件方式安装：" + apk.getAbsolutePath());
                return r;
            }
            logLine("[engine] 按文件安装未成功，退回按包名："
                    + (r == null || r.msg == null ? "无错误信息" : r.msg));
        }
        return BlackBoxCore.get().installPackage(target);
    }

    /** 沙箱里可能被 DexPathList 加载的 jar/dex 的根目录。 */
    private static java.util.List<File> sandboxDexDirs() {
        java.util.List<File> out = new java.util.ArrayList<File>();
        try {
            Context ctx = appContext;
            if (ctx == null) {
                ctx = BlackBoxCore.getContext();
            }
            File data = ctx == null ? null : ctx.getFilesDir();
            File host = data == null ? null : data.getParentFile();
            File virtual = host == null ? null : new File(host, "virtual");
            if (virtual != null) {
                out.add(new File(virtual, "cache"));
                out.add(new File(virtual, "data/app"));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 把沙箱自己准备、又要被 DexPathList 加载的 jar/dex 设成只读（{@code readOnly=true}），
     * 或放回可写（{@code readOnly=false}）。
     *
     * <p><b>这是「目标 App 能不能在沙箱里真正启动」的前提。</b>Android 14 起 ART 会拒绝加载
     * 「可写路径下的 dex」：引擎把 vm.jar / junit.jar 放在应用私有目录 {@code virtual/cache/}，
     * 该目录可写，于是沙箱里 {@code BActivityThread.bindApplication} 直接抛
     * {@code SecurityException: Writable dex file '.../virtual/cache/junit.apk' is not allowed}，
     * 紧接着第二次是 {@code ClassLoader.loadClass ... on a null object reference} —— 进程绑定
     * 因此彻底失败（日志里的 {@code [grab] 目标包名确认：沙箱绑定=null} 就是它）。目标 App
     * 从未启动 → 壳不会解密 → 引擎 hook 和内存扫描拿到的全是<b>宿主自己</b>的 dex，这就是
     * 「产物永远是 fm/BlackBox/jadx 的类」的真正源头。</p>
     *
     * <p>安装后启动前 seal、下次安装前 unseal，避免影响引擎重新拷贝这些 jar。</p>
     *
     * @return 实际改动的文件数
     */
    private static int sealSandboxDex(boolean readOnly) {
        int n = 0;
        for (File dir : sandboxDexDirs()) {
            n += sealTree(dir, readOnly, 0);
        }
        // 引擎自己的三个 jar 常量（vm/junit/empty）再兜一遍，防止目录推断有偏差
        for (String field : new String[]{"VM_JAR", "JUNIT_JAR", "EMPTY_JAR"}) {
            try {
                Object v = Class.forName("top.niunaijun.blackbox.BEnvironment")
                        .getField(field).get(null);
                File f = v instanceof File ? (File) v
                        : (v instanceof String ? new File((String) v) : null);
                if (f != null && sealOne(f, readOnly)) {
                    n++;
                }
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    private static int sealTree(File dir, boolean readOnly, int depth) {
        if (dir == null || depth > 3 || !dir.isDirectory()) {
            return 0;
        }
        File[] fs = dir.listFiles();
        if (fs == null) {
            return 0;
        }
        int n = 0;
        for (File f : fs) {
            if (f.isDirectory()) {
                n += sealTree(f, readOnly, depth + 1);
                continue;
            }
            String name = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".apk") || name.endsWith(".jar")
                    || name.endsWith(".dex") || name.endsWith(".zip")) {
                if (sealOne(f, readOnly)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 单个文件的只读/可写切换。 */
    private static boolean sealOne(File f, boolean readOnly) {
        try {
            if (readOnly) {
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
                return f.setWritable(false, false);
            }
            return f.setWritable(true, true);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取「这个包真实、可读的 apk 文件」，取不到返回 null。 */
    private static File resolveApk(boolean packageSource, String target) {
        try {
            String src = packageSource
                    ? BlackBoxCore.getContext().getPackageManager()
                            .getPackageInfo(target, 0).applicationInfo.sourceDir
                    : target;
            if (src == null) {
                return null;
            }
            File f = new File(src);
            return (f.exists() && f.canRead()) ? f : null;
        } catch (Throwable t) {
            logLine("[engine] 取源 apk 失败，退回按包名：" + describe("install", t));
            return null;
        }
    }

    /**
     * 自测引擎到底能不能解析 apk。沙箱 PM 里的包信息全靠这条反射链
     * （PackageParserCompat → android.content.pm.PackageParser），hidden API 没解禁时
     * 这里会直接抛异常，是「install 假成功」的最早可观测点。
     */
    private static void probeParser(File apk) {
        try {
            Class<?> compat = Class.forName("top.niunaijun.blackbox.utils.compat.PackageParserCompat");
            Object parser = compat.getMethod("createParser", File.class).invoke(null, apk);
            Object pkg = compat.getMethod("parsePackage",
                    Class.forName("android.content.pm.PackageParser"), File.class, int.class)
                    .invoke(null, parser, apk, 0);
            if (pkg == null) {
                logLine("[engine] 解析自测：返回 null（hidden API 被拒）");
                return;
            }
            Object acts = pkg.getClass().getField("activities").get(pkg);
            logLine("[engine] 解析自测：activities="
                    + (acts instanceof java.util.List ? ((java.util.List<?>) acts).size() : "?")
                    + "（" + apk.getName() + "）");
        } catch (Throwable t) {
            logLine("[engine] 解析自测失败：" + describe("parse", t));
        }
    }

    /**
     * 最后一招：用引擎自己的解析器从 apk 里挑出启动组件，显式指定 component 交给引擎启动。
     *
     * <p>绕开宿主 PM 与沙箱 PM 的组件表查询——从 APK 文件脱壳时这两个表都可能是空的，
     * 但 apk 本身的 manifest 是好的，能解析就一定能启动。</p>
     */
    private static boolean launchByOwnParser(File apk, String packageName) {
        if (apk == null || !apk.exists()) {
            return false;
        }
        try {
            Class<?> compat = Class.forName("top.niunaijun.blackbox.utils.compat.PackageParserCompat");
            Object parser = compat.getMethod("createParser", File.class).invoke(null, apk);
            Object pkg = compat.getMethod("parsePackage",
                            Class.forName("android.content.pm.PackageParser"), File.class, int.class)
                    .invoke(null, parser, apk, 0);
            if (pkg == null) {
                return false;
            }
            Object acts = pkg.getClass().getField("activities").get(pkg);
            if (!(acts instanceof java.util.List)) {
                return false;
            }
            String byIntent = null;
            String byName = null;
            String first = null;
            for (Object act : (java.util.List<?>) acts) {
                Object info = act.getClass().getField("info").get(act);
                String name = info == null ? null
                        : (String) info.getClass().getField("name").get(info);
                if (name == null || name.isEmpty()) {
                    continue;
                }
                if (first == null) {
                    first = name;
                }
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (byName == null && (lower.contains("main") || lower.contains("launch")
                        || lower.contains("splash"))) {
                    byName = name;
                }
                if (byIntent == null) {
                    Object intents = act.getClass().getField("intents").get(act);
                    if (intents instanceof java.util.List) {
                        for (Object filter : (java.util.List<?>) intents) {
                            try {
                                boolean main = (Boolean) filter.getClass()
                                        .getMethod("hasAction", String.class)
                                        .invoke(filter, android.content.Intent.ACTION_MAIN);
                                boolean launcher = (Boolean) filter.getClass()
                                        .getMethod("hasCategory", String.class)
                                        .invoke(filter, android.content.Intent.CATEGORY_LAUNCHER);
                                if (main && launcher) {
                                    byIntent = name;
                                    break;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
            String chosen = byIntent != null ? byIntent : (byName != null ? byName : first);
            if (chosen == null) {
                logLine("[engine] 自己解析：这个 apk 里没有可启动的 Activity");
                return false;
            }
            logLine("[engine] 自己解析出启动组件：" + chosen
                    + "（来源：" + (byIntent != null ? "MAIN/LAUNCHER" : (byName != null ? "类名" : "第一个")) + "）");
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            it.setClassName(packageName, chosen);
            it.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            it.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            BlackBoxCore.get().startActivity(it, 0);
            logLine("[engine] 已用自己解析的组件启动：" + packageName + "/" + chosen);
            return true;
        } catch (Throwable t) {
            logLine("[engine] 自己解析启动组件失败：" + describe("launch", t));
            return false;
        }
    }

    /**
     * v5.9.83：mhook 式「静默启动」——不启动目标任何 Activity，让引擎在 :p0 里直接建目标 Application。
     *
     * <p>引擎服务端的 {@code IBActivityManagerService.initProcess(pkg,proc,userId)} 只做两件事：
     * {@code BProcessManager.startProcessLocked} 建 ProcessRecord、再由 {@code initAppProcessL}
     * 用 {@code ProviderCall.callSafely(pkg 的 proxy_content_provider_N, "_Black_|_init_process_")}
     * 把 {@code :pN} 进程拉起来（系统起进程 → 进程里 {@code BActivityThread.initProcess(cfg)}
     * 记住 AppConfig → 把 client binder 挂回 ProcessRecord）。这一步全在 Java/Binder 里，
     * 与 Activity 启动链无关。</p>
     *
     * <p>但【我们这版引擎】到此为止，不会创建目标 Application（v5.9.79 就是因为看不到
     * 目标 ClassLoader 而误判成「initProcess 不起进程」——其实进程起来了、只是没 bind）。
     * mhook 的引擎多一步：其服务端 {@code initProcess} 末尾会调
     * {@code ProcessRecord.bActivityThread.bindApplication()}（Binder 调进 :pN）。
     * 我们引擎的 {@code IBActivityThread} 同样有 {@code bindApplication()}，
     * {@code ProcessRecord.bActivityThread} 也是 public 字段，所以在这里用反射自己补上。</p>
     *
     * @return 已经把 {@code bindApplication()} 调进 :pN 才返回 true（是否真的 bind 成功还要
     *         看 {@link #awaitP0Bound} 的落盘标记）
     */
    private static boolean silentStartInSandbox(String packageName) {
        try {
            // 先清掉上一轮的标记，否则 awaitP0Bound 会把旧标记当成本轮成功
            new File(pkgDumpDir(packageName), MARK_P0_BOUND).delete();
            new File(pkgDumpDir(packageName), MARK_APP_READY).delete();
        } catch (Throwable ignored) {
        }
        if (!initProcessInSandbox(packageName)) {
            return false;
        }
        // :pN 是被 ContentProvider 拉起来的，client binder 挂回 ProcessRecord 需要几百毫秒
        for (int i = 0; i < 40; i++) {
            try {
                Class<?> clz = Class.forName("top.niunaijun.blackbox.core.system.BProcessManager");
                Object bpm = clz.getMethod("get").invoke(null);
                Object rec = clz.getMethod("findProcessRecord", String.class, String.class, int.class)
                        .invoke(bpm, packageName, packageName, 0);
                if (rec != null) {
                    Object at = rec.getClass().getField("bActivityThread").get(rec);
                    if (at != null) {
                        final Object thread = at;
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    thread.getClass().getMethod("bindApplication").invoke(thread);
                                } catch (Throwable t) {
                                    logLine("[engine] 静默 bindApplication 异常：" + describe("silent", t));
                                }
                            }
                        }, "fm-silent-bind").start();
                        logLine("[engine] 已发起 :p0 静默 bindApplication（等待 " + (i * 100L) + "ms）");
                        startGuestMonitor(packageName);
                        return true;
                    }
                }
            } catch (Throwable t) {
                logLine("[engine] 静默 bindApplication 异常：" + describe("silent", t));
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        logLine("[engine] :p0 的 client binder 一直没挂回 ProcessRecord，静默启动放弃");
        return false;
    }

    /**
     * 等 :p0 里目标 App 被引擎绑定成功的落盘标记（静默启动没有 Activity，这是唯一证据）。
     *
     * @return 超时前拿到标记返回 true
     */
    /** 宿主侧只启动一次 :p0 生死监控。 */
    private static volatile boolean sGuestMonStarted;

    /**
     * 宿主侧盯住 :pN：每 2s 看它在不在。
     *
     * <p>为什么需要它：v5.9.85 的 :p0 探针只写到 +7s 就没下文了，而「探针停写」既可能是进程被杀、
     * 也可能是探针线程卡在 binder 上，光看 :p0 自己的日志分不清。引擎的
     * {@code handleBindApplication → handleDumpDex} 走完会 {@code uninstallPackage} +
     * {@code Process.killProcess(myPid())}，所以 :p0 随时可能消失，必须从外部盯。</p>
     */
    private static void startGuestMonitor(final String pkg) {
        if (sGuestMonStarted || pkg == null || isSandboxAppProcess(procName())) {
            return;
        }
        sGuestMonStarted = true;
        final long begin = System.currentTimeMillis();
        final int[] last = {-1};
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean deathDumped = false;
                for (int i = 0; i < 75; i++) {
                    int pid = findGuestPid(pkg);
                    if (pid != last[0]) {
                        logLine("[mon] 沙箱进程 pid " + (last[0] < 0 ? "(无)" : String.valueOf(last[0]))
                                + " → " + (pid < 0 ? "(消失)" : String.valueOf(pid))
                                + "，t=" + ((System.currentTimeMillis() - begin) / 1000) + "s");
                        last[0] = pid;
                        if (pid < 0 && !deathDumped) {
                            deathDumped = true;
                            /** 消失的这一刻抓 logcat：崩溃 tombstone 一般就在 crash 缓冲里。 */
                            dumpP0Logcat(pkg, "host_logcat_after_death.txt");
                        }
                    }
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
                if (!deathDumped) {
                    dumpP0Logcat(pkg, "host_logcat_final.txt");
                }
            }
        }, "fm-guest-mon").start();
    }

    /** 扫 /proc 找沙箱 App 进程：cmdline 为 {@code com.mtstyle.fm:p0} 或被引擎改名成目标包名。 */
    private static int findGuestPid(String pkg) {
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) {
            return -1;
        }
        for (File d : dirs) {
            String n = d.getName();
            if (n.isEmpty() || !Character.isDigit(n.charAt(0))) {
                continue;
            }
            java.io.FileInputStream in = null;
            try {
                in = new java.io.FileInputStream(new File(d, "cmdline"));
                byte[] buf = new byte[256];
                int len = in.read(buf);
                if (len <= 0) {
                    continue;
                }
                String cmd = new String(buf, 0, len, StandardCharsets.UTF_8)
                        .replace('\0', ' ').trim();
                if (cmd.startsWith(HOST_PKG + ":p")) {
                    return Integer.parseInt(n);
                }
                if (!HOST_PKG.equals(pkg) && cmd.startsWith(pkg)) {
                    return Integer.parseInt(n);
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (in != null) {
                        in.close();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return -1;
    }

    private static boolean awaitP0Bound(String packageName, long timeoutMs) {
        File dir = pkgDumpDir(packageName);
        File ready = new File(dir, MARK_APP_READY);
        File bound = new File(dir, MARK_P0_BOUND);
        long begin = System.currentTimeMillis();
        long end = begin + timeoutMs;
        boolean sawBound = false;
        while (System.currentTimeMillis() < end) {
            if (ready.exists() && ready.length() > 0) {
                logLine("[engine] Application 就绪：" + readSmallText(ready)
                        + "（用时 " + (System.currentTimeMillis() - begin) + "ms）");
                return true;
            }
            if (!sawBound && bound.exists() && bound.length() > 0) {
                sawBound = true;
                logLine("[engine] 只见到 " + MARK_P0_BOUND + "（bind 已进 beforeCreateApplication，"
                        + "Application 还没建），继续等 " + MARK_APP_READY);
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        logLine("[engine] 等 " + MARK_APP_READY + " 超时 " + timeoutMs + "ms"
                + (sawBound ? "（只见到 " + MARK_P0_BOUND + "）" : "（连 " + MARK_P0_BOUND + " 都没有）"));
        return false;
    }

    /** 读一个小文本文件（标记文件用，失败给空串）。 */
    private static String readSmallText(File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[256];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * v5.9.78：走 mhook 2.0.7 原版的启动路——{@code IBActivityManagerService.initProcess}。
     *
     * <p>引擎客户端侧没有暴露 {@code initProcess}（{@code BActivityManager} 只有
     * {@code startActivity}），但 {@code IBActivityManagerService} 本身是公开 AIDL 接口：
     * {@code TRANSACTION_initProcess} 存在，server 端实现是
     * {@code BProcessManager.get().startProcessLocked(pkg, procName, userId, -1, callingUid, callingPid)}
     * 并返回 {@code ProcessRecord.getClientConfig()}。所以直接用
     * {@code BlackBoxCore.get().getService("activity_manager")} 拿 Binder 调即可，
     * 不需要改引擎 jar。</p>
     *
     * <p>为什么它比 {@code startActivity} 强：{@code startActivity} 必须先把目标 Activity
     * 拉起来，加固壳在 {@code HCallbackProxy.handleLaunchActivity} 就动手——引擎 hook 在
     * ART 刚加载壳 dex 时就 dump，抓到的全是壳自己那 100MB 假 dex，跟手 {@code :p0} 被壳的
     * native（libnesec.so）SIGSEGV 打死，目标 App 自己的类一个都没加载到。
     * {@code initProcess} 由 server 端 fork 目标进程 + bindApplication，
     * 不经过 Activity 启动链路，壳的 Activity 层反调试没有触发点，而
     * {@code Application.onCreate} 里的解密逻辑照跑。</p>
     *
     * @return 成功 fork 出目标沙箱进程并拿到 AppConfig 才返回 true
     */
    private static boolean initProcessInSandbox(String packageName) {
        try {
            IBinder binder = BlackBoxCore.get().getService("activity_manager");
            if (binder == null) {
                logLine("[engine] activity_manager 服务取不到（沙箱服务未就绪？）");
                return false;
            }
            IBActivityManagerService ams = IBActivityManagerService.Stub.asInterface(binder);
            AppConfig cfg = ams.initProcess(packageName, packageName, 0);
            if (cfg == null) {
                logLine("[engine] initProcess 返回 null（startProcessLocked 没建出进程）");
                return false;
            }
            logLine("[engine] initProcess 已拉起沙箱进程：" + packageName
                    + "（processName=" + cfg.processName + "，bpid=" + cfg.bpid
                    + "，buid=" + cfg.buid + "，userId=" + cfg.userId + "）");
            return true;
        } catch (Throwable t) {
            logLine("[engine] initProcess 失败：" + describe("launch", t));
            return false;
        }
    }

    /**
     * 启动沙箱应用。
     *
     * <p>v5.9.78 起首选 {@link #initProcessInSandbox}（mhook 原版路径），它走不通时才回退到
     * 这条老链：引擎的 {@code launchApk} 用 MAIN/LAUNCHER 匹配沙箱 PM 里的 intent-filter，
     * 匹配不到就返回 false，改用宿主 PM 解析出的精确组件（包名+Activity 全名）再启动一次，
     * 绕开 intent-filter 匹配。顺带把沙箱内组件表打进日志便于定位。</p>
     */
    private static boolean launchInSandbox(String packageName, File apk) {
        // v5.9.83：优先走 mhook 的「静默启动」链。
        // v5.9.79 当时的结论（「initProcess 只登记、不起进程」）是错的：日志证明
        // initProcess 之后 41ms :p0 就起来了（服务端走 ProxyContentProvider.call 把进程拉起）。
        // 真正缺的是下一步——mhook 的引擎服务端 initProcess 末尾会调
        // ProcessRecord.bActivityThread.bindApplication()，我们这版引擎没调，
        // 所以 :p0 只有空进程、目标 App 永远不 bind；而 mhook 靠这一步在
        // 【完全没有 Activity】的情况下把目标 Application 建起来，壳的 Activity 层
        // 反调试根本没有触发点。这里我们自己用反射把这一步补上。
        if (silentStartInSandbox(packageName)) {
            if (awaitP0Bound(packageName, 25000)) {
                logLine("[engine] 静默启动生效：:p0 已创建目标 Application（全程未启动 Activity）");
                return true;
            }
            // v5.9.88 实测（v5.9.87 日志 + crash_p0.txt）：这条回退链投出的 LaunchActivityItem 一到
            // :p0 主线程就撞上 mInitialApplication==null，抛
            //   NPE at ConfigurationController.updateLocaleListFromAppContext
            //   → ActivityThread.handleLaunchActivity
            // 进程当场死亡，Application 和壳的解密线程一起没了。而静默链已经把
            // bindApplication() 调进 :p0，缺的只是时间，所以这里【绝不再投递 launch】。
            logLine("[engine] 静默启动没等到 Application 就绪标记，不再投递 launch"
                    + "（投递会以 NPE 打死 :p0）；继续观察沙箱进程");
            return true;
        } else {
            logLine("[engine] 静默启动不可用，直接走 startActivity 启动链");
        }
        try {
            if (BlackBoxCore.get().launchApk(packageName)) {
                return true;
            }
        } catch (Throwable t) {
            logLine("[engine] launchApk 抛异常：" + describe("launch", t));
        }
        try {
            android.content.Intent li = BlackBoxCore.getBPackageManager()
                    .getLaunchIntentForPackage(packageName, 0);
            logLine("[engine] 沙箱内启动组件："
                    + (li == null || li.getComponent() == null ? "无（组件表匹配不到）"
                    : li.getComponent().flattenToShortString()));
        } catch (Throwable ignored) {
        }
        try {
            android.content.pm.PackageInfo pi = BlackBoxCore.getBPackageManager()
                    .getPackageInfo(packageName, 0, 0);
            logLine("[engine] 沙箱内组件表：activities="
                    + (pi == null || pi.activities == null ? 0 : pi.activities.length)
                    + "，sourceDir=" + (pi == null || pi.applicationInfo == null
                    ? "无" : pi.applicationInfo.sourceDir));
        } catch (Throwable ignored) {
        }
        // 从 APK 文件脱壳时目标包往往没装在设备上：宿主 PM「包可见性受限」、沙箱 activities=0，
        // 两边都问不到启动组件，但这不代表装不进去——引擎自己的解析器明明能解出完整 manifest
        // （实测 com.baidu.input_oppo：解析自测 activities=418，沙箱内却是 0）。所以直接自己挑。
        if (launchByOwnParser(apk, packageName)) {
            return true;
        }
        try {
            android.content.Intent host = BlackBoxCore.getContext().getPackageManager()
                    .getLaunchIntentForPackage(packageName);
            if (host == null || host.getComponent() == null) {
                logLine("[engine] 宿主也解析不到启动组件（包可见性受限），也无法自己解析");
                return false;
            }
            android.content.Intent sb = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            sb.setComponent(host.getComponent());
            sb.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            sb.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            BlackBoxCore.get().startActivity(sb, 0);
            logLine("[engine] 已用精确组件兜底启动：" + host.getComponent().flattenToShortString());
            return true;
        } catch (Throwable t) {
            logLine("[engine] 精确组件启动失败：" + describe("launch", t));
            return false;
        }
    }

    /**
     * 收工判定：每 3 秒看一眼产物目录。
     *
     * <p>不依赖引擎的跨进程进度回调（它可能没送达），产物目录里出现文件就是成功；
     * 空等到超时则明确报失败——这一点必须做到，否则界面只会永远停在「正在脱壳」。</p>
     */
    /** 沙箱进程是否还在做内存扫描（看产物目录里的标记文件）。 */
    /**
     * 内存扫描是否还在跑。
     *
     * <p>扫描跑在沙箱进程里，静态字段跨进程读不到，只能看产物目录里的标记文件。
     * v5.9.74：标记必须配「新鲜度」判定 —— 沙箱进程被 native SIGSEGV 打死时根本来不及删标记，
     * 残留的 .memscan_running 会让 watchdog 永远以为「扫描还在跑」，提前收工条件永远不成立，
     * 一路空等到 120 秒（实测 com.coolapk.market 就是这样白等了 121s）。
     * 真正在跑的扫描每轮都 touch 这个文件，所以「mtime 超过 {@link #MEM_MARK_STALE_MS} 没动」
     * 就等于「扫描已随进程一起死了」。</p>
     */
    /**
     * 直接翻进程表，看有没有「com.mtstyle.fm:p0」这个进程。
     *
     * <p>v5.9.77：{@code sandboxAlive()} 问的是引擎自己的 isRunning()，它只在「引擎自己觉得还在跑」
     * 时才为真；实测 :p0 已经被 native SIGSEGV 打死之后它才转 false，而 :p0 从启动到死只有 0.3 秒
     * （12:14:18.851 加载 libblackdex.so → 12:14:19.173 SIGSEGV），watchdog 3 秒一轮根本抓不到它
     * 「活着」的那一刻 —— 于是「曾经见过沙箱进程」这个前置条件从来没成立过，靠它的分支全是死代码。
     * 直接看 /proc 里进程在不在，既准又实时（:p0 是宿主自己的多进程分身，cmdline 可读）。</p>
     */
    private static boolean sbxProcExists() {
        File[] ps = new File("/proc").listFiles();
        if (ps == null) {
            return false;
        }
        for (File d : ps) {
            String n = d.getName();
            if (n.isEmpty() || n.charAt(0) < '0' || n.charAt(0) > '9') {
                continue;
            }
            try (java.io.FileInputStream in =
                         new java.io.FileInputStream(new File(d, "cmdline"))) {
                byte[] buf = new byte[256];
                int len = in.read(buf);
                if (len <= 0) {
                    continue;
                }
                if (new String(buf, 0, len).trim().equals(SANDBOX_PROC_NAME)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 进程刚退出 / 无权限读，跳过
            }
        }
        return false;
    }

    private static boolean memScanRunning(String pkg) {
        if (pkg == null) {
            return false;
        }
        try {
            File f = new File(pkgDumpDir(pkg), MEM_SCAN_MARK);
            if (!f.exists()) {
                return false;
            }
            long age = System.currentTimeMillis() - f.lastModified();
            if (age > MEM_MARK_STALE_MS) {
                logLine("[mem] 扫描标记已过期（" + (age / 1000) + "s 未更新），判定扫描已随沙箱进程结束");
                f.delete();
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void clearMemScanMark(String pkg) {
        if (pkg == null) {
            return;
        }
        try {
            File f = new File(pkgDumpDir(pkg), MEM_SCAN_MARK);
            if (f.exists()) {
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void watchdog() {
        final long tag = sessionStart;
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!busy || gotResult || sessionStart != tag) {
                    return;
                }
                // 看产物目录时用 currentPkg（= pkgNameOf(target, packageSource)，和产物目录同源）：
                // packageName 在「按 APK 路径脱壳」时是 null，一 null 到底 memScanRunning/hasDexFiles
                // 就恒为 false，watchdog 会彻底失效。
                final String pkg = currentPkg;
                // 内存扫描还在跑时先不要报完成：壳自己的 dex 几秒就落盘了，
                // 但真正有价值的业务 dex 要等扫描才出来，提前收工会让用户看到「成功却只有壳 dex」。
                // 注意扫描跑在沙箱进程里，静态字段跨进程读不到，只能看产物目录里的标记文件。
                boolean memRunning = memScanRunning(pkg);
                // 沙箱进程的死活要单独记：它崩溃退出后引擎可能还会再拉一次，只有「见过、现在
                // 没了」才说明目标 App 这一轮确实结束了。
                //
                // v5.9.77：alive 同时看引擎状态和 /proc 里 :p0 在不在。sandboxAlive() 返回的是
                // 给人看的「是/否」（日志里直接拼），这里要布尔值；而且它问的是引擎自己的
                // isRunning()，要等 :p0 死了之后才转 false —— 而 :p0 从启动到被 native SIGSEGV
                // 打死只有 0.3 秒（12:14:18.851 加载 libblackdex.so → 12:14:19.173 SIGSEGV），
                // 3 秒一轮的 watchdog 根本抓不到它「活着」的那一刻，于是 sawSandbox 从来没置位过，
                // 所有依赖它的分支全是死代码。直接翻 /proc 才看得到它的完整生命周期。
                boolean procAlive = sbxProcExists();
                boolean alive = "是".equals(sandboxAlive()) || procAlive;
                if (procAlive) {
                    sawSandbox = true;
                }
                if (sawSandbox && !procAlive && !crashLogGrabbed) {
                    // :p0 刚没：native 崩溃的调用栈这会儿还挂在 logcat 尾巴上，立刻抓一份。
                    // 之前都是等 30 秒提前收工之后才抓，那几行早被别的日志挤出去了 —— 这也是
                    // 十几轮下来一直只看到「SIGSEGV, fault addr 0x0」、从没见过栈的另一个原因。
                    crashLogGrabbed = true;
                    logLine("[crash] 沙箱进程 :p0 已消失，立刻抓 logcat（趁 native 调用栈还没被挤掉）");
                    captureLogcatAsync("crash");
                }
                // 最快的成功信号：产物里已经出现目标 App 自己的类。壳 dex 里不会有这类名，
                // 所以一命中就是真结果，不必再等内存扫描那几十秒。
                // v5.9.66：不再据 hasBusinessDex 走「快速成功」。
                // 实测酷安：壳自己的 dex 里也带着 L<包名>/ 字符串，被判成业务 dex，
                // 结果 13 秒就 gotResult=true，内存扫描从没跑完 —— 产物永远只有 1 个壳 dex。
                // 现在一律等满 MIN_TASK_MS，之后按内存扫描标记决定收工。
                if (hasBusinessDex(pkg)) {
                    logLine("[success] 出现目标 App 自己的类（仅记录，不再据此提前收工）");
                }
                if (System.currentTimeMillis() - sessionStart < MIN_TASK_MS) {
                    pollTick++;
                    if (pollTick == 2 || pollTick == 20) {
                        captureLogcatAsync("poll" + pollTick);
                    }
                    watchdog();
                    return;
                }
                // 光「有 dex」不算成功：引擎 hook 最先落盘的往往就是它自己的 dex（实测
                // com.coolapk.market 的 13 个产物 dex 全是 fm/BlackBox/jadx 的类），
                // 必须等到产物里出现目标 App 自己的类，才算真拿到东西。
                if (pkg != null && hasDexFiles(pkg) && !memRunning
                        && hasBusinessDex(pkg)) {
                    gotResult = true;
                    busy = false;
                    File dir = pkgDumpDir(pkg);
                    logLine("[success] dir=" + dir.getAbsolutePath()
                            + " files=" + countDex(dir.getAbsolutePath()));
                    captureLogcatAsync("success");
                    return;
                }
                pollTick++;
                if (pollTick == 2 || pollTick == 20) {
                    // 中途也抓一次：引擎挂 native 钩子失败时是在启动早期打的，超时再抓可能已被冲掉
                    captureLogcatAsync("poll" + pollTick);
                }
                if (memRunning) {
                    memWasRunning = true;
                    if (System.currentTimeMillis() - sessionStart < MEM_SCAN_MAX_MS) {
                        // 扫描有自己的时间预算，这期间不计入超时，否则会被提前打断
                        watchdog();
                        return;
                    }
                    logLine("[mem] 扫描超过 " + (MEM_SCAN_MAX_MS / 1000) + "s 仍未结束，按中断处理");
                    clearMemScanMark(pkg);
                    memWasRunning = false;
                } else if (memWasRunning) {
                    // 刚从「扫描中」变成「扫描结束」：等待基准必须重置到此刻。否则 idle 是从
                    // 任务开始算的（扫描本身就要 100 秒上下），标记一消失就立刻超过 120s 阈值，
                    // 直接把「产物还没落盘」误报成超时。
                    memWasRunning = false;
                    lastProgressAt = System.currentTimeMillis();
                }
                long idle = System.currentTimeMillis() - Math.max(sessionStart, lastProgressAt);
                // v5.9.73：沙箱进程一旦全没了，产物就再也不会长，死等到 120 秒只是让用户干等
                // （实测酷安 :p0 在启动后 ~1 秒就被 native SIGSEGV 打死，主进程还是一路等到
                // [timeout] 121s，用户白坐两分钟）。
                // v5.9.74：去掉 sawSandbox 这个前置条件 —— 它只在「探测时沙箱正好活着」才置位，
                // 而 :p0 一秒就死，第一轮 3 秒后的探测看到的就已经是死的，于是永远不置位、
                // 这条分支从来没生效过。现在只要「沙箱已死 + 已有 dex 落盘 + 30 秒无新进展」就收工。
                if (pkg != null && !alive && hasDexFiles(pkg) && !memRunning
                        && idle >= EARLY_DONE_MS) {
                    gotResult = true;
                    busy = false;
                    clearMemScanMark(pkg);
                    logLine("[done] 沙箱进程已退出、" + (EARLY_DONE_MS / 1000) + "s 没有新产物，提前收工："
                            + countDex(pkgDumpDir(pkg).getAbsolutePath()) + " 个 dex");
                    captureLogcatAsync("done");
                    return;
                }
                if (idle >= WATCHDOG_MS) {
                    gotResult = true;
                    busy = false;
                    // 结束前必须清掉标记：残留的 .memscan_running 会让下一次任务的 watchdog
                    // 一直以为「扫描还在跑」，白白干等 90 秒（实测 com.reathin.adbassist 就这样）。
                    clearMemScanMark(pkg);
                    logLine("[timeout] 等了 " + (idle / 1000) + "s 没等到 dex，已停止等待"
                            + "（收到过引擎回调：" + (gotCallback ? "是" : "否")
                            + "；沙箱进程还活着：" + sandboxAlive() + "）");
                    captureLogcatAsync("timeout");
                    return;
                }
                watchdog();
            }
        }, 3000L);
    }

    /** 取消当前任务（离开界面时调用）。 */
    public static void stop() {
        busy = false;
        gotResult = true;
    }

    /**
     * v5.9.6 自检：沙箱的 classpath 依赖 assets 里的 vm.jar / junit.jar / empty.jar，
     * 而引擎复制这三个文件用的 copyFile 会把异常整个吞掉——少了它们沙箱应用根本起不来，
     * 表现就是永远「正在脱壳」。所以这里主动看一眼有没有真的部署成功。
     */
    private static void logJarEnv() {
        try {
            logLine("[engine] 沙箱 jar：vm=" + jarSize(BEnvironment.VM_JAR)
                    + "，junit=" + jarSize(BEnvironment.JUNIT_JAR)
                    + "，empty=" + jarSize(BEnvironment.EMPTY_JAR));
        } catch (Throwable t) {
            logLine("[engine] 沙箱 jar 自检异常：" + describe("jarEnv", t));
        }
    }

    private static String jarSize(File file) {
        return (file != null && file.isFile() && file.length() > 0L) ? (file.length() + "B") : "缺失!";
    }

    private static String describe(String stage, Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = t.getClass().getSimpleName();
        }
        return stage + "失败：" + msg;
    }

    // ==================== 引擎回调 ====================

    /** 引擎进度回调（跨进程 Binder 回到本 App 主进程）。 */
    public static final class Monitor extends IBDumpMonitor.Stub {
        @Override
        public void onDump(DumpResult result) {
            if (result == null) {
                return;
            }
            gotCallback = true;
            if (result.isRunning()) {
                lastProgressAt = System.currentTimeMillis();
                logLine("[progress] " + result.currProcess + "/" + result.totalProcess);
                return;
            }
            if (result.isSuccess()) {
                logLine("[success] dir=" + (result.dir == null ? "" : result.dir)
                        + " files=" + countDex(result.dir));
                // 沙箱侧的增量抽取器还要多轮往后写新 dex，这里先别收工，
                // 等产物目录稳定再通知结果页，免得只显示第一批。
                if (!settling) {
                    settling = true;
                    waitForDirSettleAsync(result.dir);
                }
                return;
            }
            gotResult = true;
            busy = false;
            String msg = result.msg == null ? "脱壳失败" : result.msg;
            captureLogcatAsync("onDump-fail");
            // 沙箱里残留的旧状态偶尔会拿不到 dex，清掉重装一次（E.重试）
            if (retryCount < 1 && packageName != null && msg.contains("not found dex file")) {
                retryCount++;
                logLine("[engine] 沙箱状态异常（" + msg + "），清理后重试…");
                retryDump(packageName);
                return;
            }
            logLine("[error] " + msg);
        }
    }

    /** 抽取器收工后再宽限这么久，仍没有目标 App 的类就按「没拿到」收工。 */
    private static final long GRAB_GRACE_MS = 15000L;
    /** 「产物稳定」判定的最长等待上限。 */
    private static final long SETTLE_MAX_MS = 45000L;

    /**
     * 等产物「真正」稳定再收工。
     *
     * <p>老版本只看 dex 数量在不在变：引擎 hook 最先落盘的往往不是目标 App 的 dex，而是
     * <b>它自己</b>的（实测 com.coolapk.market 的产物里 13 个 dex 全是 fm/BlackBox/jadx 的类），
     * 数量 4 秒就「稳定」了，于是主进程立刻 gotResult 收工、沙箱被拆——:p0 里的抽取器连
     * 「进程已被绑定为目标 App」那一步都没走到，真正的目标 dex 一个都没抓。这就是
     * 「zip 里永远只有引擎自己那几个 dex」的最后一环。</p>
     *
     * <p>现在必须满足其一才收工：产物里出现目标 App 自己的类（{@link #hasBusinessDex}），
     * 或沙箱侧抽取器已收工、宽限期届满且确认拿不到。否则一直等到 {@link #SETTLE_MAX_MS}。</p>
     */
    private static void waitForDirSettleAsync(final String dir) {
        final String pkg = dir == null ? null : new File(dir).getName();
        Thread t = new Thread(() -> {
            long start = System.currentTimeMillis();
            int last = -1;
            int stable = 0;
            while (true) {
                long elapsed = System.currentTimeMillis() - start;
                int now = countDex(dir);
                boolean business = hasBusinessDex(pkg);
                if (business && elapsed >= 4000L && now == last) {
                    stable++;
                    if (stable >= 2) {
                        break;
                    }
                } else {
                    stable = 0;
                }
                // 抽取器收工 + 宽限期已过：它不会再产出新 dex 了，没必要干等满上限
                if (!business && !memScanRunning(pkg) && elapsed >= GRAB_GRACE_MS) {
                    logLine("[grab] 抽取器已收工，产物里仍没有目标 App 自己的类");
                    break;
                }
                if (elapsed >= SETTLE_MAX_MS) {
                    break;
                }
                last = now;
                try {
                    Thread.sleep(900L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            boolean ok = hasBusinessDex(pkg);
            logLine("[grab] 产物已" + (ok ? "稳定" : "停止增长") + "，dex 文件=" + countDex(dir)
                    + "，含目标 App 自己的类=" + (ok ? "是" : "否")
                    + "，耗时 " + (System.currentTimeMillis() - start) + "ms");
            if (!ok) {
                logLine("[warn] 只有引擎自身的 dex，没拿到目标 App 的真实 dex"
                        + "（壳可能在本进程外解密）；产物会按归属过滤，zip 里不混进 fm 自己的 dex");
            }
            gotResult = true;
            busy = false;
        }, "fm-settle");
        t.setDaemon(true);
        t.start();
    }

    /** 最后一次任务的目标包名（重试用）。 */
    private static volatile String packageName;

    private static void retryDump(final String pkg) {
        busy = true;
        gotResult = false;
        settling = false;
        memWasRunning = false;
        synchronized (BUSINESS_CHECKED) {
            BUSINESS_CHECKED.clear();
        }
        sessionStart = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    resetSandbox(pkg);
                    Thread.sleep(600L);
                    InstallResult install = BlackBoxCore.get().installPackage(pkg);
                    if (install == null || !install.success) {
                        logLine("[error] 重试仍未成功，放弃");
                        busy = false;
                        return;
                    }
                    BlackBoxCore.get().launchApk(install.packageName);
                    logLine("[loading] 已重新启动，正在抽取 dex…");
                    watchdog();
                } catch (Throwable t) {
                    logLine("[error] " + describe("重试", t));
                    busy = false;
                }
            }
        }, "fm-dump-retry").start();
    }

    /** 沙箱引擎/反编译器自己的类名前缀——出现在 dex 里就说明这个 dex 不是目标 App 的。 */
    private static final String[] ENGINE_SIGNS = {
            "Lcom/mtstyle/fm/",
            "Ltop/niunaijun/",
            "Lorg/jf/dexlib2/",
            "Ljadx/",
            "Lorg/lsposed/hiddenapibypass/",
            // 壳自己的类。实测 com.coolapk.market 那份 97.7MB 的 base.apk!classes.dex 只有 30 个
            // 类、全是网易易盾的 wrapper（Lcom/netease/nis/wrapper/*），它不是目标 App 的东西，
            // 不该跟真产物一起打进导出包（200MB 的包里 199MB 是它）。
            "Lcom/netease/nis/",
            "Lcn/netease/",
    };

    /**
     * 这个 dex 是不是「引擎自己」的。
     *
     * <p>实测非要把这一步单独拎出来：沙箱进程里引擎 hook 最先 dump 出来的 dex，内容其实是沙箱引擎
     * 与反编译器自己（{@code top/niunaijun}=BlackBox、{@code org/jf}=dexlib2、{@code jadx}、
     * {@code com.mtstyle.fm}=fm 本体），跟目标 App 毫无关系。这类 dex 留在产物里只会让用户
     * 以为「脱出来了」——实测 com.coolapk.market 的产物 13 个 dex 全是这一类。</p>
     */
    public static boolean isEngineDex(File f) {
        if (f == null || !f.isFile()) {
            return false;
        }
        for (String sign : ENGINE_SIGNS) {
            try {
                if (containsBytes(f, sign.getBytes(StandardCharsets.US_ASCII))) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** 这个 dex 里有没有 {@code pkg} 包下的类（即 {@code L<pkg>/} 形式的名字）。 */
    public static boolean dexHasPackage(File f, String pkg) {
        if (f == null || !f.isFile() || pkg == null || pkg.isEmpty()) {
            return false;
        }
        byte[] needle = ("L" + pkg.replace('.', '/') + "/")
                .getBytes(StandardCharsets.US_ASCII);
        try {
            return containsBytes(f, needle);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int countDex(String dir) {
        if (dir == null) {
            return 0;
        }
        File[] files = new File(dir).listFiles();
        return files == null ? 0 : files.length;
    }

    /** 记录当前目标包名，供重试使用。 */
    static void setTarget(String pkg) {
        packageName = pkg;
    }

    // ==================== 诊断用日志（落到用户可见目录） ====================

    /** 我能直接读到的日志文件（Download/脱壳结果/脱壳日志.txt），用于远程定位问题。 */
    private static File publicLogFile() {
        return new File(dumpDir(), "脱壳日志.txt");
    }

    private static void resetPublicLog() {
        try {
            File f = publicLogFile();
            if (!f.isFile() || f.length() == 0L) {
                appendPublic("==== 我的文件 脱壳日志 ====");
                appendPublic("设备：" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                        + " / Android " + android.os.Build.VERSION.RELEASE
                        + "(" + android.os.Build.VERSION.SDK_INT + ") / "
                        + (android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "?"));
                appendPublic("产物目录：" + dumpDir().getAbsolutePath());
            }
        } catch (Throwable ignored) {
        }
    }

    private static void appendPublic(String line) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(publicLogFile(), true);
            try {
                w.write(line + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 沙箱工作进程（宿主包名 :p* )是否还在。 */
    private static String sandboxAlive() {
        try {
            return BlackDexCore.get().isRunning() ? "是" : "否";
        } catch (Throwable t) {
            return "未知(" + t.getClass().getSimpleName() + ")";
        }
    }

    /**
     * 抓一份同 UID 的 logcat 追加到日志里。
     *
     * <p>引擎在沙箱进程里挂 native 钩子失败时只写 logcat（而且异常被 catch 掉了），
     * 宿主侧看不到任何错误，所以必须把 logcat 抓回来。</p>
     */
    private static void captureLogcatAsync(final String tag) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                captureLogcat(tag);
            }
        }, "fm-logcat").start();
    }

    private static void captureLogcat(String tag) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/logcat", "-d", "-v", "time", "-t", "2000")
                    .redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            int kept = 0;
            // 关键字命中的行 + 紧跟其后的堆栈行：Java 异常的真正信息全在缩进的 at/.../Caused by 里。
            // v5.9.10 就因为只留关键字行，堆栈被截断成三行，白跑了一轮实机验证。
            boolean keepStack = false;
            while ((line = r.readLine()) != null && kept < 400) {
                String low = line.toLowerCase(java.util.Locale.ROOT);
                boolean hit = low.contains("blackbox") || low.contains("blackdex") || low.contains("vmcore")
                        || low.contains("androidruntime") || low.contains("fatal")
                        || low.contains("dump") || low.contains("sandbox") || low.contains("hook")
                        || low.contains("niunaijun") || low.contains("hiddenapi")
                        || low.contains("system.err") || low.contains("exception")
                        || low.contains("packageparser") || low.contains("bpac")
                        // v5.9.77：native 崩溃的调用栈只挂在 libc / DEBUG / tombstone 这几个 tag 下
                        // （形式是 "#00 pc 0000000000xxxxx /data/app/.../libblackdex.so"）。
                        // 之前这句白名单把它们全滤掉了，所以花了十几轮都只知道「SIGSEGV, fault addr 0x0」，
                        // 完全看不到崩在哪个 .so 的哪个函数 —— 这次必须把它抓回来。
                        || low.contains("libc") || low.contains("debug")
                        || low.contains("tombstone") || low.contains("backtrace")
                        || low.contains("abort message") || low.contains("signal ")
                        || low.contains("native bridge") || low.contains("art::");
                boolean stack = keepStack && (line.contains("\tat ") || line.contains("Caused by")
                        || line.contains("Suppressed:") || line.contains("... "));
                if (hit || stack) {
                    sb.append("    ").append(line).append('\n');
                    kept++;
                    keepStack = true;
                } else {
                    keepStack = false;
                }
            }
            r.close();
            appendPublic("---- logcat(" + tag + ") ----");
            appendPublic(sb.length() == 0 ? "    (无匹配日志)" : sb.toString());
        } catch (Throwable t) {
            appendPublic("---- logcat(" + tag + ") 抓取失败：" + t + " ----");
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    // ==================== 沙箱复位 ====================

    /** 最近一次收到引擎进度的时间（用来判断是「在跑」还是「卡死」）。 */
    private static volatile long lastProgressAt;

    /** 本次任务是否收到过引擎的任何回调（progress/success/fail）。 */
    private static volatile boolean gotCallback;

    /** 产物轮询次数（用于中途抓 logcat）。 */
    private static volatile int pollTick;

    /**
     * 每次脱壳前把沙箱恢复到干净状态：杀掉残留的沙箱进程、卸载残留的同名安装、清掉同名旧产物。
     *
     * <p>旧产物必须清掉：判定成功是看「产物目录里有没有文件」，留着上次的会立刻被判成成功。</p>
     */
    private static void resetSandbox(String pkg) {
        if (killSandboxProcesses()) {
            // 进程表稳定一下再动手：install/launchApk 抢在旧进程退出前会把新目标装进
            // 已经绑着别的包的 :p0 槽位里，后面 manifest 查询、ClassLoader 定位全跟着错
            sleepQuiet(400L);
        }
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        try {
            File dir = pkgDumpDir(pkg);
            File[] old = dir.isDirectory() ? dir.listFiles() : null;
            if (old != null) {
                for (File f : old) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            BlackBoxCore.get().uninstallPackage(pkg);
            logLine("[engine] 沙箱已复位（" + pkg + "）");
        } catch (Throwable t) {
            // DeadObjectException = :black 服务进程还没接管这次调用（实测它比宿主这边慢一拍），
            // 补一次启动再卸一遍；再失败就只是残留安装，下一次装有覆盖逻辑，继续跑。
            logLine("[engine] 复位沙箱时出错（继续跑）：" + describe("reset", t));
            try {
                ensureStarted();
                sleepQuiet(300L);
                BlackBoxCore.get().uninstallPackage(pkg);
                logLine("[engine] 沙箱复位重试成功（" + pkg + "）");
            } catch (Throwable t2) {
                logLine("[engine] 复位重试仍失败（继续跑）：" + describe("reset2", t2));
            }
        }
    }

    /**
     * 杀掉上一轮遗留的沙箱进程（与宿主同包名同 UID，宿主有权直接杀），并等它们真正退出。
     *
     * @return 是否真的杀过进程
     */
    private static boolean killSandboxProcesses() {
        boolean killedAny = false;
        try {
            Context ctx = appContext;
            if (ctx == null) {
                return false;
            }
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            String prefix = ctx.getPackageName() + ":p";
            // 最多两轮：第一轮 kill，等进程表更新；还有残留就再补一轮
            for (int round = 0; round < 2; round++) {
                List<android.app.ActivityManager.RunningAppProcessInfo> running =
                        am.getRunningAppProcesses();
                if (running == null) {
                    break;
                }
                boolean any = false;
                for (android.app.ActivityManager.RunningAppProcessInfo info : running) {
                    if (info != null && info.processName != null
                            && info.processName.startsWith(prefix)) {
                        if (round == 0) {
                            logLine("[engine] 清理残留沙箱进程 " + info.processName
                                    + "(" + info.pid + ")");
                        }
                        android.os.Process.killProcess(info.pid);
                        any = true;
                        killedAny = true;
                    }
                }
                if (!any) {
                    break;
                }
                long deadline = System.currentTimeMillis() + 1500L;
                while (System.currentTimeMillis() < deadline && hasProcess(am, prefix)) {
                    sleepQuiet(100L);
                }
            }
        } catch (Throwable t) {
            logLine("[engine] 清理沙箱进程失败（继续跑）：" + describe("kill", t));
        }
        return killedAny;
    }

    /** 进程表里还有没有 {@code <宿主包名>:p*}。 */
    private static boolean hasProcess(android.app.ActivityManager am, String prefix) {
        try {
            List<android.app.ActivityManager.RunningAppProcessInfo> running =
                    am.getRunningAppProcesses();
            if (running == null) {
                return false;
            }
            for (android.app.ActivityManager.RunningAppProcessInfo info : running) {
                if (info != null && info.processName != null
                        && info.processName.startsWith(prefix)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 引擎把每个目标的产物放在 getDexDumpDir()/<包名>/ 下。 */
    private static File pkgDumpDir(String pkg) {
        return new File(dumpDir(), pkg);
    }

    /**
     * 开一份「本次目标专属」的日志，落在该包的产物目录里并覆盖同名旧文件，
     * 这样结果页的「查看日志」看到的就一定是这一次。
     */
    private static void beginTaskLog(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            taskLogFile = null;
            return;
        }
        try {
            File dir = pkgDumpDir(pkg);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                taskLogFile = null;
                return;
            }
            File f = new File(dir, TASK_LOG_NAME);
            if (f.isFile()) {
                f.delete();
            }
            taskLogFile = f;
        } catch (Throwable t) {
            taskLogFile = null;
        }
        rememberTaskLog(taskLogFile);
    }

    /** 把本次任务的日志路径记到私有文件里，供 :black 子进程复用。 */
    private static void rememberTaskLog(File file) {
        try {
            if (file == null) {
                deleteQuietly(new File(privDir(), TASK_LOG_PATH_NAME));
                deleteQuietly(new File(EpicDump.dumpDir(), TASK_LOG_PATH_NAME));
                return;
            }
            // 私有目录 + 外部共享目录各记一份：沙箱进程里读得到哪份就用哪份
            String path = file.getAbsolutePath();
            writeText(new File(privDir(), TASK_LOG_PATH_NAME), path);
            writeText(new File(EpicDump.dumpDir(), TASK_LOG_PATH_NAME), path);
        } catch (Throwable ignored) {
            // 记不住只是少了子进程那几行，不影响脱壳
        }
    }

    /** 记下本次任务的目标包名，宿主私有目录与外部共享目录各写一份（:p0 读得到哪份用哪份）。 */
    private static void rememberTaskPkg(String pkg) {
        String v = pkg == null ? "" : pkg;
        writeText(new File(privDir(), TASK_PKG_NAME), v);
        writeText(new File(EpicDump.dumpDir(), TASK_PKG_NAME), v);
    }

    /** 读回本次任务的目标包名：宿主私有目录优先，退化到外部共享目录。 */
    public static String taskPkg() {
        String v = readText(new File(privDir(), TASK_PKG_NAME));
        if (v == null || v.isEmpty()) {
            v = readText(new File(EpicDump.dumpDir(), TASK_PKG_NAME));
        }
        return v == null || v.isEmpty() ? null : v;
    }

    /** 记下本次任务的原始 APK 路径（诊断用；包名任务时写宿主查到的 sourceDir）。 */
    private static void rememberTaskSrcApk(String path) {
        String v = path == null ? "" : path;
        writeText(new File(privDir(), TASK_SRC_APK_NAME), v);
        writeText(new File(EpicDump.dumpDir(), TASK_SRC_APK_NAME), v);
    }

    /** 宿主侧推断「原始 APK 在哪」：路径任务直接用路径，包名任务查宿主 PackageManager。 */
    private static String resolveSrcApk(boolean packageSource, String target) {
        if (!packageSource) {
            return target;
        }
        try {
            android.content.pm.ApplicationInfo ai = BlackBoxCore.getContext().getPackageManager()
                    .getApplicationInfo(target, 0);
            return ai == null ? null : ai.sourceDir;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 读回本次任务的原始 APK 路径。 */
    private static String taskSrcApk() {
        String v = readText(new File(privDir(), TASK_SRC_APK_NAME));
        if (v == null || v.isEmpty()) {
            v = readText(new File(EpicDump.dumpDir(), TASK_SRC_APK_NAME));
        }
        return v == null || v.isEmpty() ? null : v;
    }

    private static String readText(File f) {
        try {
            if (f == null || !f.isFile() || f.length() <= 0L) {
                return null;
            }
            byte[] data = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try {
                int off = 0;
                while (off < data.length) {
                    int read = in.read(data, off, data.length - off);
                    if (read <= 0) {
                        break;
                    }
                    off += read;
                }
            } finally {
                in.close();
            }
            return new String(data, StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 本 App 自己的包名（沙箱里 getAppPackageName() 也可能返回它，那不算绑定到目标）。 */
    public static String hostPackageName() {
        try {
            return appContext == null ? "" : appContext.getPackageName();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeText(File f, String text) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f, false);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {
            // 忽略
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.isFile()) {
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 本次要脱的目标包名。
     *
     * <p>先读 {@link #TASK_PKG_NAME} 那份「本次任务」记录——每次任务都会被覆写，是 :p0 里唯一
     * 可靠的答案；读不到才退回「日志路径的父目录名」这条老路。实测日志里 :p0 拿到的宿主传入值
     * 雷打不动是 {@code com.fka.zhuru}（上一次任务的残留），正是老路读到陈旧文件造成的：
     * 于是 manifest 查不到、ClassLoader 等不到、抽取器一个 dex 都落不了盘，不管脱什么 APK
     * 都只剩引擎 hook 抓到的那 1 个壳 dex。</p>
     */
    private static String currentTaskPkg() {
        String fresh = taskPkg();
        if (fresh != null && !fresh.isEmpty()) {
            return fresh;
        }
        File log = taskLogFromDisk();
        File parent = log == null ? null : log.getParentFile();
        return parent == null ? null : parent.getName();
    }

    /** 子进程启动时取回本次任务的日志路径。 */
    private static File taskLogFromDisk() {
        File f = new File(privDir(), TASK_LOG_PATH_NAME);
        // 沙箱进程（:p0）里我们的 Context 未必与宿主相同，退化到外部共享目录里那份
        if (!f.isFile() || f.length() <= 0L) {
            f = new File(EpicDump.dumpDir(), TASK_LOG_PATH_NAME);
        }
        try {
            if (!f.isFile() || f.length() <= 0L) {
                return null;
            }
            byte[] data = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try {
                int off = 0;
                while (off < data.length) {
                    int read = in.read(data, off, data.length - off);
                    if (read <= 0) {
                        break;
                    }
                    off += read;
                }
            } finally {
                in.close();
            }
            String path = new String(data, StandardCharsets.UTF_8).trim();
            return path.isEmpty() ? null : new File(path);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 某个目标专属的日志文件；包名未知时回退到全局公开日志。 */
    public static File taskLog(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return publicLogFile();
        }
        return new File(pkgDumpDir(pkg), TASK_LOG_NAME);
    }

    /**
     * 界面用的「当前正在做什么」。
     *
     * <p>脱壳跑在沙箱进程里，宿主读不到它的内存状态，唯一能反映真实进度的就是两边共同写的
     * 那个产物目录里的任务日志。取最后一条有效行并把时间戳/标签剥掉。</p>
     */
    public static String tailTaskStage() {
        List<String> lines = readTaskLogLines();
        for (int i = lines.size() - 1; i >= 0; i--) {   // 从最后一行往回找：最新的一条才是当前阶段
            String text = stripTag(lines.get(i));
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    /**
     * 任务日志里是否已经出现「这次基本没戏」的特征。
     *
     * <p>返回中文原因，没有则返回 null。只在一条 dex 都没产出时才该采信——已经出了产物
     * 就说明链路是通的，日志里的报错只是局部失败。</p>
     */
    public static String fatalTaskHint() {
        for (String l : readTaskLogLines()) {
            if (l.contains("拿不到目标包名")) {
                return "沙箱里没能确认目标包名，脱壳无法继续";
            }
            if (l.contains("on a null object reference")
                    && l.contains("Binder")) {
                return "引擎服务没启动成功，建议退出「我的文件」后重试";
            }
        }
        return null;
    }

    /** 读任务日志的尾部若干行；文件不是本次任务写的就返回空（避免把上一次的内容当进展）。 */
    private static List<String> readTaskLogLines() {
        List<String> lines = new ArrayList<>();
        File f = taskLog(currentPkg);
        try {
            if (!f.isFile()) {
                return lines;
            }
            // 任务日志是每次开始覆盖写的，但回退到全局日志时是累积的，所以用 mtime 兜一道。
            if (f.lastModified() < sessionStart) {
                return lines;
            }
            long len = f.length();
            long from = Math.max(0L, len - 8192L);
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            try {
                raf.seek(from);
                byte[] buf = new byte[(int) (len - from)];
                int read = 0;
                while (read < buf.length) {
                    int n = raf.read(buf, read, buf.length - read);
                    if (n <= 0) {
                        break;
                    }
                    read += n;
                }
                String text = new String(buf, 0, read, StandardCharsets.UTF_8);
                for (String l : text.split("\n")) {
                    lines.add(l);
                }
            } finally {
                raf.close();
            }
        } catch (Throwable ignored) {
        }
        return lines;
    }

    /** 把 {@code 12:34:56 [grab] 逼解密…} 剥成 {@code 逼解密…}。 */
    private static String stripTag(String line) {
        if (line == null) {
            return "";
        }
        String l = line.trim();
        if (l.isEmpty()) {
            return "";
        }
        int close = l.lastIndexOf(']');
        if (close >= 0 && close + 1 < l.length()) {
            return l.substring(close + 1).trim();
        }
        return l;
    }

    /** 目标包名：已安装应用直接用入参；APK 文件先解析 manifest 取包名，取不到返回 null。 */
    private static String pkgNameOf(String source, boolean packageSource) {
        if (packageSource) {
            return source;
        }
        try {
            android.content.pm.PackageInfo info = appContext.getPackageManager()
                    .getPackageArchiveInfo(source, 0);
            if (info != null && info.packageName != null) {
                return info.packageName;
            }
        } catch (Throwable ignored) {
            // 解析不出来就继续走下面的兜底
        }
        // 兜底：host PM 读不到（按 APK 路径脱壳时 apk 常在沙箱私有目录，或 manifest 被加固过）时，
        // 用引擎自带的解析器再解析一次。这条很关键：解析不出来 currentPkg 就是 null，
        // task_pkg 会被记成空文件（实测 0 字节），:p0 里 awaitBoundPkg 就只能退化到宿主的陈旧值，
        // 整个抽取链（组件类、ClassLoader、内存扫描）跟着全部跑偏。
        try {
            File apk = new File(source);
            if (apk.isFile()) {
                Class<?> compat = Class.forName(
                        "top.niunaijun.blackbox.utils.compat.PackageParserCompat");
                Object parser = compat.getMethod("createParser", File.class).invoke(null, apk);
                Object pkg = compat.getMethod("parsePackage",
                        Class.forName("android.content.pm.PackageParser"), File.class, int.class)
                        .invoke(null, parser, apk, 0);
                if (pkg != null) {
                    Object name = pkg.getClass().getField("packageName").get(pkg);
                    if (name instanceof String && !((String) name).isEmpty()) {
                        return (String) name;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean hasDexFiles(String pkg) {
        File[] files = pkgDumpDir(pkg).listFiles();
        return files != null && files.length > 0;
    }

    /** 已经判过「有没有业务类」的产物文件（名字+长度做键），避免每 3 秒重扫几十 MB。 */
    private static final java.util.Set<String> BUSINESS_CHECKED = new java.util.HashSet<>();

    /**
     * 产物里是否已经出现「目标 App 自己的类」——这是「有可用结果」的最快信号。
     *
     * <p>壳自己的 dex 里几乎不会出现 {@code L<目标包名路径>/}，真正的业务 dex 才会。只要它
     * 落盘就没有必要再等内存扫描跑完那几十秒，可以立刻收工。每次只检查尚未检查过的新文件，
     * 每个文件有独立时间预算，保证轮询本身不会变慢。</p>
     */
    /** 判定「真业务 dex」的目标包自己的类定义数下限。壳 dex 里一个都没有，真实 dex 动辄几千。 */
    private static final int MIN_BUSINESS_CLASSES = 3;
    /** 解析 class_defs 的条数上限，防畸形 dex 让循环跑飞。 */
    private static final int MAX_CLASS_DEFS = 300000;

    /**
     * 产物里是否真的出现了「目标 App 自己的类」。
     *
     * <p>v5.9.73 从「字节匹配」改成「解析 dex 的 class_defs 表数类定义」。
     *
     * <p>老的字节匹配有个致命误判：网易易盾的壳 dex（com.coolapk.market 实测那份 97.7MB 的
     * {@code base.apk!classes.dex}）只有 30 个类、全是 {@code Lcom/netease/nis/wrapper/*}，
     * 但它的字符串常量里带着目标 Application 的类名，于是 {@code containsBytes("Lcom/coolapk/")}
     * 命中，日志打出「已拿到业务 dex」——实际一个目标类的定义都没有，导出全是壳。
     *
     * <p>现在直接解析 {@code class_defs}：descriptor 以 {@code L<包名>/} 开头的**类定义**数
     * 达到 {@link #MIN_BUSINESS_CLASSES} 才算数，字符串常量里出现包名不算。
     */
    private static boolean hasBusinessDex(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        String prefix = "L" + pkg.replace('.', '/') + "/";
        File[] files = pkgDumpDir(pkg).listFiles();
        if (files == null) {
            return false;
        }
        int best = 0;
        String bestName = "(无)";
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            String n = f.getName();
            if (!n.endsWith(".dex")) {
                continue;   // 只认落盘的 dex，跳过标记文件/日志/壳写出来的 jar
            }
            String key = n + ":" + f.length();
            synchronized (BUSINESS_CHECKED) {
                if (BUSINESS_CHECKED.contains(key)) {
                    continue;
                }
                BUSINESS_CHECKED.add(key);
            }
            try {
                int own = countOwnClassDefs(f, prefix);
                if (own > best) {
                    best = own;
                    bestName = n;
                }
                if (own >= MIN_BUSINESS_CLASSES) {
                    logLine("[success] 已拿到业务 dex：" + n + "（目标类定义 " + own + " 个）");
                    return true;
                }
            } catch (Throwable t) {
                logLine("[cls] 解析 " + n + " 失败：" + t);
            }
        }
        logLine("[cls] 还没有业务 dex：最好的是 " + bestName + "，目标类定义仅 " + best
                + " 个（阈值 " + MIN_BUSINESS_CLASSES + "）");
        return false;
    }

    /**
     * 数一个 dex 里 descriptor 以 {@code prefix} 开头的类定义有几个。
     *
     * <p>全程随机读、不把整份 dex 读进内存（真实业务 dex 上百 MB）：header 112 字节 → type_ids
     * 表 → class_defs 表，每条只读 32 字节，descriptor 只读前缀那几个字节做比较。
     */
    static int countOwnClassDefs(File f, String prefix) throws java.io.IOException {
        byte[] pfx = prefix.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        try {
            byte[] h = new byte[0x70];
            raf.readFully(h);
            if (h[0] != 'd' || h[1] != 'e' || h[2] != 'x' || h[3] != '\n') {
                return 0;
            }
            int clsSize = le32(h, 0x60);
            int clsOff = le32(h, 0x64);
            int typeSize = le32(h, 0x40);
            int typeOff = le32(h, 0x44);
            int strSize = le32(h, 0x38);
            int strOff = le32(h, 0x3c);
            if (clsSize <= 0 || clsOff <= 0 || typeSize <= 0 || typeOff <= 0
                    || strSize <= 0 || strOff <= 0 || typeSize > 4000000) {
                return 0;
            }
            // type_ids 表一次读进来：它只有 typeSize*4 字节，而每条 class_def 都要查它
            byte[] types = new byte[typeSize * 4];
            raf.seek(typeOff);
            raf.readFully(types);
            byte[] cd = new byte[32];
            int own = 0;
            int limit = Math.min(clsSize, MAX_CLASS_DEFS);
            for (int i = 0; i < limit; i++) {
                raf.seek(clsOff + (long) i * 32);
                raf.readFully(cd);
                int typeIdx = le32(cd, 0);
                if (typeIdx < 0 || typeIdx >= typeSize) {
                    continue;
                }
                int strIdx = le32(types, typeIdx * 4);
                if (strIdx < 0 || strIdx >= strSize) {
                    continue;
                }
                if (dexStringHasPrefix(raf, strOff + (long) strIdx * 4, pfx)) {
                    own++;
                }
            }
            return own;
        } finally {
            raf.close();
        }
    }

    /**
     * 读 string_id_item 指向的 MUTF8 字符串，只判断它是否以 {@code pfx} 开头。
     *
     * <p>不整串读出来：真实业务 dex 有几万条 class_def，每条都读完整 descriptor 会产生几万次长读；
     * 只读「uleb128 长度 + 前缀字节」就够判前缀，也省掉 UTF-8 解码。
     */
    private static boolean dexStringHasPrefix(java.io.RandomAccessFile raf, long strDataOff,
            byte[] pfx) throws java.io.IOException {
        byte[] b = new byte[3 + pfx.length];
        raf.seek(strDataOff);
        int r = raf.read(b);
        if (r <= 0) {
            return false;
        }
        int i = 0;
        long len = 0;
        int shift = 0;
        while (i < r) {
            int c = b[i++] & 0xff;
            len |= ((long) (c & 0x7f)) << shift;
            shift += 7;
            if ((c & 0x80) == 0) {
                break;
            }
            if (i >= r) {
                return false;
            }
        }
        if (len < pfx.length || i + pfx.length > r) {
            return false;
        }
        for (int k = 0; k < pfx.length; k++) {
            if (b[i + k] != pfx[k]) {
                return false;
            }
        }
        return true;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    /** 在文件里找一段字节：分块读 + 首字节预筛，单文件预算 {@code BUSINESS_SCAN_BUDGET_MS}。 */
    private static boolean containsBytes(File f, byte[] needle) throws java.io.IOException {
        if (needle.length == 0) {
            return false;
        }
        final byte first = needle[0];
        final long deadline = System.currentTimeMillis() + BUSINESS_SCAN_BUDGET_MS;
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        try {
            long len = raf.length();
            int chunk = 1 << 20;
            byte[] buf = new byte[chunk + needle.length - 1];
            long pos = 0;
            while (pos < len) {
                raf.seek(pos);
                int n = raf.read(buf, 0, (int) Math.min((long) buf.length, len - pos));
                if (n <= 0) {
                    break;
                }
                int limit = n - needle.length;
                for (int i = 0; i <= limit; i++) {
                    if (buf[i] != first) {
                        continue;   // 绝大多数字节在这里就被跳过，不必进内层循环
                    }
                    boolean ok = true;
                    for (int j = 1; j < needle.length; j++) {
                        if (buf[i + j] != needle[j]) {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) {
                        return true;
                    }
                }
                pos += Math.max(1, n - (needle.length - 1));
                if (System.currentTimeMillis() > deadline) {
                    return false;   // 超时就先放弃，下次轮询再来（文件会重新排队）
                }
            }
        } finally {
            try {
                raf.close();
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // ==================== 引擎配置 ====================

    /** 引擎配置：宿主包名 + 结果目录 + 提速选项。 */
    private static final class Config extends ClientConfiguration {

        private final String hostPkg;

        Config(Context context) {
            hostPkg = context.getPackageName();
        }

        /** v5.9.90：在 :p0 里 context 是目标 App 的，宿主包名必须显式传进来。 */
        Config(Context context, String hostPkgOverride) {
            hostPkg = hostPkgOverride != null ? hostPkgOverride : context.getPackageName();
        }

        @Override
        public String getHostPackageName() {
            return hostPkg;
        }

        @Override
        public String getDexDumpDir() {
            return dumpDir().getAbsolutePath();
        }

        @Override
        public String getDumpSubDir() {
            return "";
        }

        @Override
        public boolean isEnableHookDump() {
            // v5.9.74：关掉引擎的 hook dump。
            // 实测（com.coolapk.market / 网易易盾）它在 ART 刚加载完壳 dex 时就 hook 住 LoadClass，
            // 把那个 size=102478344 的【假】dex（真实数据只有 63840B）整个 dump 出来，
            // 而 dump 完成后仅 15ms，:p0 就 SIGSEGV（fault addr 0x0）—— 目标 App 的 Application
            // 连壳解密都还没跑到，内存里除了宿主自己的 dex 什么都没有。
            // 它 dump 出来的东西也本来就是垃圾：30 个类全是 Lcom/netease/nis/wrapper/*。
            // 关掉之后：① 省掉一次 102MB 读写和疑似崩在 fixCodeItem 的收尾；
            //           ② 目标 App 才有可能活到"壳把真实 dex 解出来"那一步。
            // 真实 dex 改由 FmMemScan 独立扫内存 + dexElements 抽取来拿（与引擎 hook 无关）。
            return false;
        }

        @Override
        public boolean isVerifyDex() {
            return false;
        }

        @Override
        public boolean isFixCodeItem() {
            // 抽取壳 dump 出来的方法体常常还是空壳，交给引擎按 code item 回填修复。
            return true;
        }

        @Override
        public boolean isAutoCallMethod() {
            // v5.9.89 A/B：恢复引擎原版的「主动调用」。
            //
            // 这是 mhook 脱壳发动机的关键一步：VMCore.cookieDumpDex 里
            //     if (BlackBoxCore.get().isAutoCallMethod()) autoCallAllMethod(cl);
            // 会把目标 ClassLoader 里所有类逐个 loadClass + 反射调用方法，
            // 逼方法抽取壳（易盾这类）在运行时把被抽走的方法体补回来 ⇒ 补码后的 dex 才完整。
            //
            // v5.9.74 曾把它关掉（当时理由是"易盾会自杀式崩溃 fault addr 0x0"）。但那次是在
            // 「跳过引擎 dump 分支 + VMCore 全 no-op」的配置下测的，主动加载只是孤零零地跑，
            // 没有 dump 上下文；而且同一时间我们自研的 FmSandboxDumper 也只在枚举 CL / 扫 maps，
            // 拿不到 runtime cookie 里那份已解密的 dex。现在按引擎原版配置一起恢复。
            return true;
        }
    }
}
