package com.mtstyle.fm;

import android.content.Context;
import android.util.Log;

import com.android.apksig.ApkSigner;
import com.mtstyle.fm.apk.Axml;
import com.mtstyle.fm.apk.AxmlEditor;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.MethodParameter;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.formats.Instruction21c;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodImplementation;
import org.jf.dexlib2.immutable.ImmutableMethodParameter;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 「注入到期时间」引擎：把一个到期检查塞进目标 APK 的 Application 入口，再重签名。
 *
 * <p>做法（与业界同类工具一致）：</p>
 * <ol>
 *   <li>从 assets 取出预编译的守卫类 {@code expiry/ExpiryGuard}（dex）；</li>
 *   <li>用 dexlib2 打开目标 APK 的每个 {@code classesN.dex}，找到所有
 *       {@code android.app.Application} 的子类；</li>
 *   <li>在这些类的 {@code attachBaseContext}（优先）或 {@code onCreate} 的<b>最前面</b>
 *       插入 {@code invoke-static {p0}, Lexpiry/ExpiryGuard;->check(Landroid/content/Context;)V}，
 *       并改写守卫类里 {@code EXPIRY}/{@code MESSAGE} 两个字符串常量的初始值；</li>
 *   <li>用 {@link AlignedZip} 重打包（丢 META-INF、条目对齐）；</li>
 *   <li>用 apksig 以内置密钥重新签名（v1+v2+v3）。</li>
 * </ol>
 *
 * <p><b>局限</b>：只对未加固、且 Application 类确实在 dex 里的 APK 有效；重签名后
 * 与原应用签名不同，安装前需要先卸载原应用（或换包名）。</p>
 */
public final class ExpiryInjector {

    private static final String TAG = "ExpiryInjector";

    /** assets 里预编译好的守卫类（包 {@code expiry}）。 */
    /** manifest 没写 application/android:name 时，塞进 manifest 的兜底 Application 类名。 */
    private static final String FALLBACK_APP_CLASS = "com.mtstyle.fm.expiry.ExpiryApp";

    private static final String GUARD_ASSET = "expiry_guard.dex";
    /** assets 里的内置签名密钥（PKCS12）。 */
    private static final String KEYSTORE_ASSET = "inject.keystore";
    private static final String KS_PASS = "mtstyle";
    private static final String KS_ALIAS = "mtinject";

    private static final String GUARD_DESC = "Lexpiry/ExpiryGuard;";
    private static final String CTX_DESC = "Landroid/content/Context;";
    private static final String OBJ_DESC = "Ljava/lang/Object;";
    private static final String APP_DESC = "Landroid/app/Application;";
    private static final String PROVIDER_DESC = "Landroid/content/ContentProvider;";
    private static final String ACTIVITY_DESC = "Landroid/app/Activity;";
    private static final String CHECK_ANY = "checkAny";
    private static final String STR_EXPIRY_DEFAULT = "0";
    private static final String STR_MESSAGE_DEFAULT = "试用已到期";
    private static final String STR_QQ_DEFAULT = "__DEV_QQ__";
    private static final String STR_GROUP_DEFAULT = "__DEV_GROUP__";

    /** 进度回调（在工作线程被调用，实现方自己切主线程；percent 为真实进度 0-100）。 */
    public interface Step {
        void on(String desc, int percent);
    }

    public static final class Result {
        public boolean ok;
        public String error;
        public File output;
        /** 被注入的入口类个数。 */
        public int injected;
        /** 输出文件大小。 */
        public long size;
        /** 注入点类型描述（Application / ContentProvider / Activity）。 */
        public String entry = "";
        /** 失败时的诊断线索：dex 数、类数、manifest 情况、候选统计。 */
        public String diag = "";
    }

    private ExpiryInjector() {
    }

    /**
     * 执行一次注入。
     *
     * @param src          源 APK
     * @param expiryMillis 到期时刻（UTC 毫秒），必须大于 0
     * @param message      到期提示文案，可空
     * @param out          输出 APK
     */
    public static Result run(Context ctx, File src, long expiryMillis, String message,
                             String qq, String qqGroup, File out, Step step) {
        File work = new File(ctx.getCacheDir(), "expiry-inject");
        try {
            deleteTree(work);
            //noinspection ResultOfMethodCallIgnored
            work.mkdirs();
            File guard = new File(work, GUARD_ASSET);
            copyAsset(ctx, GUARD_ASSET, guard);
            File keystore = new File(work, KEYSTORE_ASSET);
            copyAsset(ctx, KEYSTORE_ASSET, keystore);
            return inject(src, guard, keystore, KS_PASS, KS_ALIAS, expiryMillis, message,
                    qq, qqGroup, out, work, step);
        } catch (Throwable t) {
            deleteTree(work);
            Log.e(TAG, "注入准备失败", t);
            Result r = new Result();
            String m = t.getMessage();
            return fail(r, "注入失败：" + (m == null || m.isEmpty()
                    ? t.getClass().getSimpleName() : m));
        }
    }

    /**
     * 核心注入流程：与 Android 环境解耦（守卫 dex、密钥都作为普通文件传入），
     * 方便在 PC（JDK）上做端到端回归，避免在设备上盲改。
     */
    public static Result inject(File src, File guard, File keystore, String ksPass,
                                String ksAlias, long expiryMillis, String message,
                                String qq, String qqGroup,
                                File out, File work, Step step) {
        Result r = new Result();
        try {
            //noinspection ResultOfMethodCallIgnored
            work.mkdirs();

            String msg = message == null || message.trim().isEmpty()
                    ? STR_MESSAGE_DEFAULT : message.trim();
            String qqValue = qq == null ? "" : qq.trim();
            String groupValue = qqGroup == null ? "" : qqGroup.trim();

            progress(step, "扫描 APK 结构…", 6);
            List<String> dexNames = listDex(src);
            if (dexNames.isEmpty()) {
                return fail(r, "这个 APK 里没有 classes.dex，可能是特殊结构的包");
            }

            File tmp = new File(work, "dex");
            //noinspection ResultOfMethodCallIgnored
            tmp.mkdirs();
            Map<String, List<String>> typesByDex = new LinkedHashMap<>();
            Map<String, String> superOf = new HashMap<>();
            Set<String> existing = new HashSet<>();
            int totalClasses = 0;
            for (String n : dexNames) {
                File f = new File(tmp, n);
                extract(src, n, f);
                // 只读 dex 的 string/type/class_defs 三张表，不构造 ClassDef：
                // 大包（几万类）上比 dexlib2 全量解析快得多，且这几个 dex 后面未必都要改写。
                DexScan scan = scanDex(f);
                typesByDex.put(n, scan.types);
                superOf.putAll(scan.superOf);
                existing.addAll(scan.types);
                totalClasses += scan.types.size();
                Log.d(TAG, n + " -> " + scan.types.size() + " 类（轻量扫描）");
            }

            progress(step, "读取 AndroidManifest…", 18);
            ManifestInfo mf = readManifest(src);

            progress(step, "定位注入入口…", 24);
            List<String> targets = new ArrayList<>();
            StringBuilder diag = new StringBuilder();
            diag.append("dex ").append(dexNames.size()).append(" 个/类 ")
                    .append(totalClasses).append(" 个");
            boolean sawHardenHint = mf != null && mf.hardenHint;

            // 1) 首选：manifest 里 application/android:name 指定的类。
            //    这是最权威的定位方式，不受“父类链被裁剪/断链”影响。
            if (!mf.appDescriptor.isEmpty()) {
                if (existing.contains(mf.appDescriptor)) {
                    targets.add(mf.appDescriptor);
                    r.entry = "Application";
                } else {
                    diag.append("；manifest Application=").append(mf.appDescriptor)
                            .append("（该类不在 dex 中，业务代码可能被加密）");
                }
            } else {
                diag.append("；manifest 未指定 Application");
            }
            // 2) 次选：扫描所有 dex，找 android.app.Application 的子类。
            if (targets.isEmpty()) {
                for (String t : existing) {
                    if (isApplicationSubclass(superOf, t)) {
                        targets.add(t);
                    }
                }
                if (!targets.isEmpty()) {
                    r.entry = "Application";
                } else {
                    diag.append("；未扫到 Application 子类");
                }
            }
            // 3) 回退：manifest 里的 ContentProvider（启动比 Activity 早，适合兜底）。
            if (targets.isEmpty()) {
                targets.addAll(filterInjectable(typesByDex, tmp, mf.providers));
                if (!targets.isEmpty()) {
                    r.entry = "ContentProvider";
                } else if (!mf.providers.isEmpty()) {
                    diag.append("；Provider ").append(mf.providers.size())
                            .append(" 个，均不在 dex 中或没有 onCreate");
                }
            }
            // 4) 回退：manifest 里的 Activity.onCreate（打开界面时才触发，聊胜于无）。
            if (targets.isEmpty()) {
                targets.addAll(filterInjectable(typesByDex, tmp, mf.activities));
                if (!targets.isEmpty()) {
                    r.entry = "Activity";
                } else if (!mf.activities.isEmpty()) {
                    diag.append("；Activity ").append(mf.activities.size())
                            .append(" 个，均不在 dex 中或没有 onCreate");
                }
            }
            // 5) 最后手段：manifest 指定的 Application 类不在 dex 中（业务被加密/裁剪）。
            //    就地“补”一个同名的 Application 子类，让系统加载我们的类 —— 不需要改 AndroidManifest。
            boolean createAppClass = false;
            String manifestAppClass = null;
            if (targets.isEmpty() && !mf.appDescriptor.isEmpty()) {
                targets.add(mf.appDescriptor);
                createAppClass = true;
                r.entry = "Application（新建）";
                diag.append("；已新建 ").append(mf.appDescriptor).append(" 顶替缺失的 Application");
            }
            // 6) 终极兜底：manifest 根本没写 application/android:name（用系统默认 Application）。
            //    这时四大组件全都没有入口，唯一解法是把我们的类名写进 AndroidManifest。
            //    manifest 是二进制 AXML，用自研 AxmlEditor 直接改写（只追加属性，不动其它结构）。
            if (targets.isEmpty()) {
                manifestAppClass = FALLBACK_APP_CLASS;
                targets.add("L" + FALLBACK_APP_CLASS.replace('.', '/') + ";");
                createAppClass = true;
                r.entry = "Application（改写 manifest）";
                diag.append("；manifest 未指定 Application，已新建 ").append(FALLBACK_APP_CLASS)
                        .append(" 并把类名写入 AndroidManifest");
            }
            r.diag = diag.toString();
            if (targets.isEmpty()) {
                return fail(r, "没找到可注入的入口类，无法注入（" + diag + "）。"
                        + (sawHardenHint
                        ? "该 APK 检测到加固特征，业务代码不在标准 dex 里，无法注入"
                        : "请确认是未加固的完整 APK（不是 split 分包里的单个 apk）"));
            }
            Log.d(TAG, "注入入口(" + r.entry + ")：" + targets);

            Map<String, List<String>> targetsByDex = new LinkedHashMap<>();
            for (String t : targets) {
                String dn = dexOf(typesByDex, t);
                if (dn != null) {
                    List<String> l = targetsByDex.get(dn);
                    if (l == null) {
                        l = new ArrayList<>();
                        targetsByDex.put(dn, l);
                    }
                    l.add(t);
                }
            }

            progress(step, "写入到期时间…", 32);
            List<ClassDef> patchedGuard = new ArrayList<>();
            for (ClassDef cd : DexFileFactory.loadDexFile(guard, opcodesOf(guard)).getClasses()) {
                patchedGuard.add(patchGuard(cd, String.valueOf(expiryMillis), msg,
                        qqValue, groupValue));
            }

            // 守卫类放到类数最少的 dex，尽量远离 64K 方法数上限
            String guardDexName = dexNames.get(0);
            int min = Integer.MAX_VALUE;
            for (String n : dexNames) {
                if (typesByDex.get(n).size() < min) {
                    min = typesByDex.get(n).size();
                    guardDexName = n;
                }
            }

            progress(step, "改写 dex…", 40);
            Map<String, File> replaced = new LinkedHashMap<>();
            int injected = 0;
            for (String n : dexNames) {
                List<String> hits = targetsByDex.get(n);
                boolean hasHits = hits != null && !hits.isEmpty();
                boolean guardHere = n.equals(guardDexName);
                if (!hasHits && !guardHere) {
                    continue;   // 不需要改写的 dex 直接跳过，不做 dexlib2 全量解析（大包提速关键）
                }
                File cur = new File(tmp, n);
                List<ClassDef> outClasses = new ArrayList<>();
                boolean changed = false;
                for (ClassDef cd : DexFileFactory.loadDexFile(cur, opcodesOf(cur)).getClasses()) {
                    if (hasHits && hits.contains(cd.getType())) {
                        ClassDef nn = injectInto(cd);
                        if (nn != null) {
                            injected++;
                            outClasses.add(nn);
                            changed = true;
                            continue;
                        }
                    }
                    outClasses.add(cd);
                }
                if (guardHere) {
                    for (ClassDef g : patchedGuard) {
                        if (!existing.contains(g.getType())) {
                            outClasses.add(g);
                            changed = true;
                        }
                    }
                    if (createAppClass) {
                        outClasses.add(buildApplicationClass(targets.get(0)));
                        existing.add(targets.get(0));
                        injected++;
                        changed = true;
                    }
                }
                if (changed) {
                    File nf = new File(tmp, "new-" + n);
                    DexFileFactory.writeDexFile(nf.getAbsolutePath(),
                            new ImmutableDexFile(opcodesOf(cur), outClasses));
                    replaced.put(n, nf);
                }
            }
            if (injected == 0) {
                return fail(r, "找到了 Application 类，但没能注入（入口方法形态特殊）");
            }

            progress(step, "重新打包…", 72);
            if (manifestAppClass != null) {
                File mfOut = new File(work, "AndroidManifest.xml");
                AxmlEditor.setApplicationName(src, mfOut, manifestAppClass);
                replaced.put("AndroidManifest.xml", mfOut);
                Log.d(TAG, "已改写 AndroidManifest：application android:name = " + manifestAppClass);
            }
            File unsigned = new File(work, "unsigned.apk");
            AlignedZip.write(src, replaced, unsigned);

            progress(step, "重新签名…", 88);
            sign(unsigned, out, keystore, ksPass, ksAlias);

            //noinspection ResultOfMethodCallIgnored
            unsigned.delete();

            r.ok = true;
            r.output = out;
            r.injected = injected;
            r.size = out.length();
            return r;
        } catch (Throwable t) {
            Log.e(TAG, "注入失败", t);
            String m = t.getMessage();
            if (m == null || m.isEmpty()) {
                m = t.getClass().getSimpleName();
            }
            return fail(r, "注入失败：" + m);
        }
    }

    private static Result fail(Result r, String msg) {
        r.ok = false;
        r.error = msg;
        return r;
    }

    private static void progress(Step step, String desc, int percent) {
        Log.d(TAG, desc + " (" + percent + "%)");
        if (step != null) {
            step.on(desc, percent);
        }
    }

    // ------------------------------------------------------------------
    // dex 改写
    // ------------------------------------------------------------------

    /** <clinit> 里把四个字符串常量（到期时间/标题/开发者QQ/官方QQ群）的初始值换掉。 */
    private static ClassDef patchGuard(ClassDef cd, String expiry, String message,
                                       String qq, String group) {
        List<Method> newMethods = new ArrayList<>();
        for (Method m : cd.getMethods()) {
            if (!"<clinit>".equals(m.getName()) || m.getImplementation() == null) {
                newMethods.add(m);
                continue;
            }
            MutableMethodImplementation mm = new MutableMethodImplementation(m.getImplementation());
            List<BuilderInstruction> ins = mm.getInstructions();
            for (int i = 0; i < ins.size(); i++) {
                Instruction inst = ins.get(i);
                if (inst.getOpcode() != Opcode.CONST_STRING || !(inst instanceof Instruction21c)) {
                    continue;
                }
                Instruction21c c = (Instruction21c) inst;
                if (!(c.getReference() instanceof StringReference)) {
                    continue;
                }
                String s = ((StringReference) c.getReference()).getString();
                if (STR_EXPIRY_DEFAULT.equals(s)) {
                    mm.replaceInstruction(i, new BuilderInstruction21c(Opcode.CONST_STRING,
                            c.getRegisterA(), new ImmutableStringReference(expiry)));
                } else if (STR_MESSAGE_DEFAULT.equals(s)) {
                    mm.replaceInstruction(i, new BuilderInstruction21c(Opcode.CONST_STRING,
                            c.getRegisterA(), new ImmutableStringReference(message)));
                } else if (STR_QQ_DEFAULT.equals(s)) {
                    mm.replaceInstruction(i, new BuilderInstruction21c(Opcode.CONST_STRING,
                            c.getRegisterA(), new ImmutableStringReference(qq)));
                } else if (STR_GROUP_DEFAULT.equals(s)) {
                    mm.replaceInstruction(i, new BuilderInstruction21c(Opcode.CONST_STRING,
                            c.getRegisterA(), new ImmutableStringReference(group)));
                }
            }
            newMethods.add(newMethod(m, immutable(mm)));
        }
        return new ImmutableClassDef(cd.getType(), cd.getAccessFlags(), cd.getSuperclass(),
                cd.getInterfaces(), cd.getSourceFile(), cd.getAnnotations(),
                cd.getFields(), newMethods);
    }

    /** 在 attachBaseContext（优先）/ onCreate 最前面插 invoke-static。 */
    private static ClassDef injectInto(ClassDef cd) {
        boolean hasAttach = hasAttach(cd);
        List<Method> newMethods = new ArrayList<>();
        boolean done = false;
        for (Method m : cd.getMethods()) {
            boolean attach = isAttach(m);
            boolean create = "onCreate".equals(m.getName()) && m.getParameters().isEmpty()
                    && ("V".equals(m.getReturnType()) || "Z".equals(m.getReturnType()));
            // attachBaseContext 比 onCreate 更早，能挡住后面的初始化；两者都有时只注入 attach
            if (!done && m.getImplementation() != null && (attach || (create && !hasAttach))) {
                MethodImplementation impl = m.getImplementation();
                int paramWidth = 0;
                for (MethodParameter p : m.getParameters()) {
                    paramWidth += width(p.getType());
                }
                if ((m.getAccessFlags() & 0x8) == 0) {
                    paramWidth += 1;    // this
                }
                int p0 = impl.getRegisterCount() - paramWidth;
                MutableMethodImplementation mm = new MutableMethodImplementation(impl);
                ImmutableMethodReference ref = new ImmutableMethodReference(GUARD_DESC, CHECK_ANY,
                        Collections.singletonList(OBJ_DESC), "V");
                mm.addInstruction(0, new BuilderInstruction35c(Opcode.INVOKE_STATIC,
                        1, p0, 0, 0, 0, 0, ref));
                newMethods.add(newMethod(m, immutable(mm)));
                Log.d(TAG, "注入 " + cd.getType() + "->" + m.getName() + " p" + p0);
                done = true;
                continue;
            }
            newMethods.add(m);
        }
        if (!done) {
            return null;
        }
        return new ImmutableClassDef(cd.getType(), cd.getAccessFlags(), cd.getSuperclass(),
                cd.getInterfaces(), cd.getSourceFile(), cd.getAnnotations(),
                cd.getFields(), newMethods);
    }

    private static boolean hasAttach(ClassDef cd) {
        for (Method m : cd.getMethods()) {
            if (isAttach(m)) {
                return true;
            }
        }
        return false;
    }

    /** attachBaseContext(Landroid/content/Context;)V —— 老版 dexlib2 的 Method 没有 getSignature，只能逐参判断。 */
    private static boolean isAttach(Method m) {
        if (!"attachBaseContext".equals(m.getName())) {
            return false;
        }
        List<? extends MethodParameter> ps = m.getParameters();
        return ps.size() == 1 && CTX_DESC.equals(ps.get(0).getType())
                && "V".equals(m.getReturnType());
    }

    private static int width(String type) {
        return ("J".equals(type) || "D".equals(type)) ? 2 : 1;
    }

    private static ImmutableMethodImplementation immutable(MutableMethodImplementation mm) {
        return new ImmutableMethodImplementation(mm.getRegisterCount(), mm.getInstructions(),
                mm.getTryBlocks(), mm.getDebugItems());
    }

    private static Method newMethod(Method m, ImmutableMethodImplementation impl) {
        return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(),
                m.getReturnType(), m.getAccessFlags(), m.getAnnotations(),
                m.getHiddenApiRestrictions(), impl);
    }

    // ------------------------------------------------------------------
    // manifest 解析与候选定位
    // ------------------------------------------------------------------

    /** 从 AndroidManifest.xml 抽出的关键信息。 */
    private static final class ManifestInfo {
        /** {@code application/android:name} 的 dex 描述符，空表示未指定。 */
        String appDescriptor = "";
        final List<String> providers = new ArrayList<>();
        final List<String> activities = new ArrayList<>();
        /** APK 里是否出现加固特征（用于给出更准确的失败提示）。 */
        boolean hardenHint;
    }

    /**
     * 读取 manifest：{@code application/android:name}、provider、activity。
     *
     * <p>用 android:name 定位入口比“扫描谁继承了 Application”权威得多：有的 APK 里
     * Application 的父类链被裁剪（父类不在本 dex），扫描会漏判。</p>
     */
    private static ManifestInfo readManifest(File src) {
        ManifestInfo mf = new ManifestInfo();
        ZipFile zip = null;
        try {
            zip = new ZipFile(src);
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) {
                return mf;
            }
            byte[] data = readLimited(zip, entry, 8L * 1024 * 1024);
            if (data == null || !Axml.isBinaryXml(data)) {
                return mf;
            }
            Axml.Doc doc = Axml.decode(data);
            if (doc == null || doc.root == null) {
                return mf;
            }
            Axml.Node app = child(doc.root, "application");
            if (app == null) {
                return mf;
            }
            String pkg = attr(doc.root, "package");
            mf.appDescriptor = descriptor(attr(app, "name"), pkg);
            collect(app, "provider", pkg, mf.providers);
            collect(app, "activity", pkg, mf.activities);
            mf.hardenHint = zip.getEntry("classes0.dex") != null
                    || zip.getEntry("assets/ondev/p") != null
                    || zip.getEntry("assets/x86/libDexHelper.so") != null;
        } catch (Throwable t) {
            Log.w(TAG, "读取 manifest 失败", t);
        } finally {
            close(zip);
        }
        return mf;
    }

    /** 类名 + 包名 → dex 描述符；支持 ".Foo" / "Foo" 这类相对写法。 */
    private static String descriptor(String name, String pkg) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (name.startsWith(".")) {
            name = pkg + name;
        } else if (name.indexOf('.') < 0 && pkg != null && !pkg.isEmpty()) {
            name = pkg + "." + name;
        }
        return "L" + name.replace('.', '/') + ";";
    }

    private static void collect(Axml.Node app, String tag, String pkg, List<String> out) {
        for (Axml.Node node : app.children) {
            if (node.name == null || !tag.equalsIgnoreCase(node.name)) {
                continue;
            }
            String d = descriptor(attr(node, "name"), pkg);
            if (!d.isEmpty() && !out.contains(d)) {
                out.add(d);
            }
        }
    }

    private static Axml.Node child(Axml.Node node, String name) {
        for (Axml.Node c : node.children) {
            if (c.name != null && name.equalsIgnoreCase(c.name)) {
                return c;
            }
        }
        return null;
    }

    private static String attr(Axml.Node node, String name) {
        if (node == null) {
            return "";
        }
        for (Axml.Attr a : node.attrs) {
            if (name.equalsIgnoreCase(a.name)) {
                return a.value == null ? "" : a.value;
            }
        }
        return "";
    }

    /** 目标类在哪个 dex 里（查轻量扫描结果）。 */
    private static String dexOf(Map<String, List<String>> typesByDex, String type) {
        for (Map.Entry<String, List<String>> e : typesByDex.entrySet()) {
            if (e.getValue().contains(type)) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * 从候选描述符里挑出“确实在 dex 中、且有无参 onCreate”的类（取第一个即可）。
     * 只对候选类所在的那个 dex 做 dexlib2 解析，而不是把所有 dex 都解析一遍。
     */
    private static List<String> filterInjectable(Map<String, List<String>> typesByDex,
                                                 File dexDir, List<String> descs) {
        List<String> out = new ArrayList<>();
        if (descs == null || descs.isEmpty()) {
            return out;
        }
        for (String d : descs) {
            String dn = dexOf(typesByDex, d);
            if (dn == null) {
                continue;
            }
            File f = new File(dexDir, dn);
            try {
                for (ClassDef cd : DexFileFactory.loadDexFile(f, opcodesOf(f)).getClasses()) {
                    if (d.equals(cd.getType()) && hasOnCreate(cd)) {
                        out.add(d);
                        return out;
                    }
                }
            } catch (Throwable ignored) {
                // 单个 dex 解析失败不影响其它候选
            }
        }
        return out;
    }

    /**
     * ContentProvider.onCreate 返回 boolean，Activity/Application 返回 void —— 两种都接受。
     */
    /**
     * 造一个 Application 子类：manifest 里 application/android:name 指向的类不在 dex 中时，
     * 用同名类“顶”上去，系统就会加载我们，从而在 attachBaseContext 里触发守卫。
     * 生成的类等价于：
     * <pre>
     * public class X extends Application {
     *     public X() { super(); }
     *     protected void attachBaseContext(Context base) {
     *         ExpiryGuard.checkAny(base);
     *         super.attachBaseContext(base);
     *     }
     * }
     * </pre>
     */
    private static ClassDef buildApplicationClass(String type) {
        ImmutableMethodReference superInit = new ImmutableMethodReference("Landroid/app/Application;",
                "<init>", new ArrayList<CharSequence>(), "V");
        ImmutableMethodReference superAttach = new ImmutableMethodReference("Landroid/app/Application;",
                "attachBaseContext", Arrays.<CharSequence>asList("Landroid/content/Context;"), "V");
        ImmutableMethodReference guardCheck = new ImmutableMethodReference(GUARD_DESC, CHECK_ANY,
                Arrays.<CharSequence>asList("Ljava/lang/Object;"), "V");

        List<BuilderInstruction> ctor = new ArrayList<>();
        ctor.add(new BuilderInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0, superInit));
        ctor.add(new BuilderInstruction10x(Opcode.RETURN_VOID));
        ImmutableMethod init = new ImmutableMethod(type, "<init>",
                new ArrayList<MethodParameter>(), "V", 0x1, null, null,
                new ImmutableMethodImplementation(1, ctor, null, null));

        List<BuilderInstruction> attach = new ArrayList<>();
        attach.add(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0, guardCheck));
        attach.add(new BuilderInstruction35c(Opcode.INVOKE_SUPER, 2, 0, 1, 0, 0, 0, superAttach));
        attach.add(new BuilderInstruction10x(Opcode.RETURN_VOID));
        List<MethodParameter> ps = new ArrayList<>();
        ps.add(new ImmutableMethodParameter("Landroid/content/Context;", null, null));
        ImmutableMethod attachM = new ImmutableMethod(type, "attachBaseContext", ps, "V", 0x4, null, null,
                new ImmutableMethodImplementation(2, attach, null, null));

        return new ImmutableClassDef(type, 0x1, "Landroid/app/Application;", null, null, null,
                null, null, Arrays.<Method>asList(init), Arrays.<Method>asList(attachM));
    }

    private static boolean hasOnCreate(ClassDef cd) {
        for (Method m : cd.getMethods()) {
            if ("onCreate".equals(m.getName()) && m.getParameters().isEmpty()
                    && ("V".equals(m.getReturnType()) || "Z".equals(m.getReturnType()))
                    && m.getImplementation() != null) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readLimited(ZipFile zip, ZipEntry entry, long limit) throws IOException {
        long size = entry.getSize();
        if (size <= 0 || size > limit) {
            return null;
        }
        byte[] data = new byte[(int) size];
        try (InputStream in = zip.getInputStream(entry)) {
            int total = 0;
            while (total < data.length) {
                int read = in.read(data, total, data.length - total);
                if (read <= 0) {
                    break;
                }
                total += read;
            }
            return total == data.length ? data : null;
        }
    }

    private static void close(ZipFile zip) {
        if (zip != null) {
            try {
                zip.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    /** 沿 superclass 链判断是不是 android.app.Application 的子类。 */
    private static boolean isApplicationSubclass(Map<String, String> superOf, String type) {
        String cur = superOf.get(type);
        int guard = 0;
        while (cur != null && guard++ < 256) {
            if (APP_DESC.equals(cur)) {
                return true;
            }
            cur = superOf.get(cur);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 轻量 dex 扫描：只读 header / string_ids / type_ids / class_defs
    // ------------------------------------------------------------------

    /** 轻量扫描结果：全部类描述符 + 类→父类。 */
    private static final class DexScan {
        final List<String> types = new ArrayList<>();
        final Map<String, String> superOf = new HashMap<>();
    }

    /**
     * 不构造任何 dexlib2 对象，直接按偏移读 dex 的三张表得到“类名 + 父类名”。
     * 大包（几万类）上比 loadDexFile().getClasses() 快一个数量级，且内存占用小得多。
     */
    private static DexScan scanDex(File f) throws IOException {
        byte[] b = readAll(f);
        if (b.length < 112 || b[0] != 'd' || b[1] != 'e' || b[2] != 'x') {
            throw new IOException("不是有效的 dex：" + f.getName());
        }
        int stringIdsSize = u4(b, 56);
        int stringIdsOff = u4(b, 60);
        int typeIdsSize = u4(b, 64);
        int typeIdsOff = u4(b, 68);
        int classDefsSize = u4(b, 96);
        int classDefsOff = u4(b, 100);
        if (typeIdsSize < 0 || classDefsSize < 0 || stringIdsSize <= 0
                || typeIdsOff < 0 || classDefsOff < 0) {
            throw new IOException("dex 头部异常：" + f.getName());
        }

        int[] typeStr = new int[typeIdsSize];
        for (int i = 0; i < typeIdsSize; i++) {
            typeStr[i] = u4(b, typeIdsOff + i * 4);
        }
        String[] pool = new String[stringIdsSize];

        DexScan s = new DexScan();
        for (int i = 0; i < classDefsSize; i++) {
            int base = classDefsOff + i * 32;
            int classIdx = u4(b, base);
            int superIdx = u4(b, base + 8);
            if (classIdx < 0 || classIdx >= typeIdsSize) {
                continue;
            }
            String t = dexString(b, stringIdsOff, typeStr[classIdx], pool);
            s.types.add(t);
            String sup = null;
            if (superIdx >= 0 && superIdx < typeIdsSize) {
                sup = dexString(b, stringIdsOff, typeStr[superIdx], pool);
            }
            s.superOf.put(t, sup);
        }
        return s;
    }

    /** 读 string_ids[idx] 指向的 MUTF-8 字符串（带缓存；类描述符基本是 ASCII）。 */
    private static String dexString(byte[] b, int stringIdsOff, int idx, String[] pool) {
        if (idx < 0 || idx >= pool.length) {
            return "";
        }
        String c = pool[idx];
        if (c != null) {
            return c;
        }
        int off = u4(b, stringIdsOff + idx * 4);
        int size = 0;
        int shift = 0;
        while (off < b.length) {
            int x = b[off++] & 0xff;
            size |= (x & 0x7f) << shift;
            if ((x & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        if (size < 0 || size > (1 << 20)) {
            size = 0;
        }
        char[] out = new char[size];
        int n = 0;
        while (n < size && off < b.length) {
            int x = b[off++] & 0xff;
            if (x < 0x80) {
                out[n++] = (char) x;
            } else if ((x & 0xe0) == 0xc0) {
                int x2 = b[off++] & 0xff;
                out[n++] = (char) (((x & 0x1f) << 6) | (x2 & 0x3f));
            } else {
                int x2 = b[off++] & 0xff;
                int x3 = b[off++] & 0xff;
                out[n++] = (char) (((x & 0x0f) << 12) | ((x2 & 0x3f) << 6) | (x3 & 0x3f));
            }
        }
        c = new String(out);
        pool[idx] = c;
        return c;
    }

    private static int u4(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int n = 0;
            while (n < b.length) {
                int k = in.read(b, n, b.length - n);
                if (k < 0) {
                    break;
                }
                n += k;
            }
        } finally {
            in.close();
        }
        return b;
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    /**
     * 按 dex 文件头的版本号选 opcodes。
     *
     * <p>重要：写回时必须沿用源 dex 的版本，否则会把低版本 dex（035）写成 039，
     * 在 Android 7 及以下无法加载。</p>
     */
    private static Opcodes opcodesOf(File dex) {
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(dex, "r");
            byte[] h = new byte[8];
            f.readFully(h);
            String v = new String(h, 4, 4, "US-ASCII");
            if (v.startsWith("035")) {
                return Opcodes.forApi(21);
            }
            if (v.startsWith("037")) {
                return Opcodes.forApi(24);
            }
            if (v.startsWith("038")) {
                return Opcodes.forApi(26);
            }
            if (v.startsWith("039")) {
                return Opcodes.forApi(28);
            }
        } catch (Throwable t) {
            Log.w(TAG, "读 dex 版本失败，回退默认", t);
        } finally {
            close(f);
        }
        return Opcodes.forApi(26);
    }

    private static List<String> listDex(File apk) throws IOException {
        final List<String> names = new ArrayList<>();
        ZipFile zf = new ZipFile(apk);
        try {
            Enumeration<? extends ZipEntry> it = zf.entries();
            while (it.hasMoreElements()) {
                String n = it.nextElement().getName();
                if (n.matches("classes\\d*\\.dex")) {
                    names.add(n);
                }
            }
        } finally {
            zf.close();
        }
        Collections.sort(names, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return dexNum(a) - dexNum(b);
            }
        });
        return names;
    }

    private static int dexNum(String name) {
        if ("classes.dex".equals(name)) {
            return 1;
        }
        try {
            return Integer.parseInt(name.substring("classes".length(), name.length() - 4));
        } catch (Throwable t) {
            return 9999;
        }
    }

    private static void extract(File apk, String name, File out) throws IOException {
        ZipFile zf = new ZipFile(apk);
        try {
            ZipEntry e = zf.getEntry(name);
            if (e == null) {
                throw new IOException("APK 里没有 " + name);
            }
            InputStream is = zf.getInputStream(e);
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            } finally {
                os.close();
                is.close();
            }
        } finally {
            zf.close();
        }
    }

    private static void copyAsset(Context ctx, String name, File dst) throws IOException {
        InputStream is = ctx.getAssets().open(name);
        OutputStream os = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        } finally {
            os.close();
            is.close();
        }
    }

    /** 用内置密钥做 v1+v2+v3 签名。 */
    private static void sign(File in, File out, File keystore, String pass, String alias)
            throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        InputStream is = new FileInputStream(keystore);
        try {
            ks.load(is, pass.toCharArray());
        } finally {
            is.close();
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, pass.toCharArray());
        Certificate[] chain = ks.getCertificateChain(alias);
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : chain) {
            certs.add((X509Certificate) c);
        }
        ApkSigner.SignerConfig cfg =
                new ApkSigner.SignerConfig.Builder("MTSTYLE", key, certs).build();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(in)
                .setOutputApk(out)
                // minSdk 压到 21，让 apksig 同时产出 v1（老设备）+ v2/v3（新设备）
                .setMinSdkVersion(21)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteTree(k);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void close(RandomAccessFile f) {
        if (f != null) {
            try {
                f.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
