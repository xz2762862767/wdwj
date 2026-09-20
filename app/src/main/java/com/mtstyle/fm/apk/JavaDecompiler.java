package com.mtstyle.fm.apk;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.core.dex.nodes.MethodNode;
import jadx.plugins.input.dex.DexInputPlugin;

/**
 * 「转 Java」：使用 jadx 把 dex 反编译为 Java 源码。
 *
 * <p>反编译引擎内存开销较大（大 dex 可达上百 MB），因此按
 * 「apk 路径 + dex 条目 + 文件长度 + 时间戳」缓存一个引擎实例，
 * 空闲一段时间后自动释放。</p>
 */
public final class JavaDecompiler {

    /** 引擎空闲多久后释放（毫秒）。 */
    private static final long IDLE_RELEASE_MS = 120000L;

    private static final Object LOCK = new Object();
    private static JadxDecompiler engine;
    private static String engineKey;
    private static File engineInput;
    private static File engineDir;
    private static Timer timer;

    private JavaDecompiler() {
    }

    /** 反编译整个类；descriptor 形如 Lcom/foo/Bar;。 */
    public static String decompileClass(Context context, File apk, String dexEntry, String descriptor)
            throws Exception {
        return findClass(context, apk, dexEntry, descriptor).getCode();
    }

    /** 反编译单个方法；shortId 形如 read(Ljava/io/File;)V，未找到返回 null。 */
    public static String decompileMethod(Context context, File apk, String dexEntry, String descriptor,
                                        String shortId) throws Exception {
        JavaClass cls = findClass(context, apk, dexEntry, descriptor);
        List<JavaMethod> methods = cls.getMethods();
        for (int i = 0; i < methods.size(); i++) {
            JavaMethod method = methods.get(i);
            MethodNode node = method.getMethodNode();
            if (node == null || node.getMethodInfo() == null) {
                continue;
            }
            if (shortId.equals(node.getMethodInfo().getShortId())) {
                String code = method.getCodeStr();
                if (code != null && code.length() > 0) {
                    return code;
                }
            }
        }
        return null;
    }

    /** 释放引擎与临时 dex 文件，下次调用会重新加载。 */
    public static void release() {
        synchronized (LOCK) {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            if (engine != null) {
                try {
                    engine.close();
                } catch (Throwable ignored) {
                }
                engine = null;
            }
            engineKey = null;
            engineInput = null;
            if (engineDir != null) {
                File[] children = engineDir.listFiles();
                if (children != null) {
                    for (int i = 0; i < children.length; i++) {
                        try {
                            children[i].delete();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
    }

    private static JavaClass findClass(Context context, File apk, String dexEntry, String descriptor)
            throws Exception {
        JadxDecompiler jadx = obtain(context, apk, dexEntry);
        String wanted = descriptorToName(descriptor);
        List<JavaClass> classes = jadx.getClasses();
        for (int i = 0; i < classes.size(); i++) {
            JavaClass cls = classes.get(i);
            if (wanted.equals(normalize(cls.getFullName()))) {
                return cls;
            }
        }
        throw new Exception("class not found in dex: " + wanted);
    }

    private static JadxDecompiler obtain(Context context, File apk, String dexEntry) throws Exception {
        String key = apk.getAbsolutePath() + '|' + dexEntry + '|' + apk.length() + '|' + apk.lastModified();
        synchronized (LOCK) {
            if (engine != null && key.equals(engineKey)) {
                scheduleRelease();
                return engine;
            }
            release();
            File dir = new File(context.getCacheDir(), "jadx");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            engineDir = dir;
            byte[] bytes = ZipEdit.readEntry(apk, dexEntry);
            if (bytes == null) {
                throw new Exception("dex not found in apk: " + dexEntry);
            }
            File input = new File(dir, dexEntry.replace('/', '_'));
            FileOutputStream out = new FileOutputStream(input);
            try {
                out.write(bytes);
                out.flush();
            } finally {
                out.close();
            }

            JadxArgs args = new JadxArgs();
            args.setInputFile(input);
            args.setSkipResources(true);
            args.setThreadsCount(1);
            args.setShowInconsistentCode(false);

            JadxDecompiler jadx = new JadxDecompiler(args);
            try {
                jadx.load();
            } catch (Throwable first) {
                // Android 上 ServiceLoader 可能读不到 jar 内的 META-INF/services，改为显式注册输入插件
                try {
                    jadx.close();
                } catch (Throwable ignored) {
                }
                jadx = new JadxDecompiler(args);
                jadx.registerPlugin(new DexInputPlugin());
                jadx.load();
            }
            if (jadx.getClasses().isEmpty()) {
                jadx.close();
                throw new Exception("no class decoded from " + dexEntry);
            }
            engine = jadx;
            engineKey = key;
            engineInput = input;
            scheduleRelease();
            return engine;
        }
    }

    private static void scheduleRelease() {
        synchronized (LOCK) {
            if (timer != null) {
                timer.cancel();
            }
            timer = new Timer("fm-jadx-idle", true);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    release();
                }
            }, IDLE_RELEASE_MS);
        }
    }

    /** Lcom/foo/Bar$Baz; -> com.foo.Bar.Baz */
    private static String descriptorToName(String descriptor) {
        String name = descriptor == null ? "" : descriptor;
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }
        return normalize(name.replace('/', '.'));
    }

    private static String normalize(String name) {
        return name == null ? "" : name.replace('$', '.');
    }
}
