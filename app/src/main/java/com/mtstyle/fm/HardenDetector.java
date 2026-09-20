package com.mtstyle.fm;

import com.mtstyle.fm.apk.Axml;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * APK 加固（壳）精准识别引擎。
 *
 * <p>多特征加权评分：native 库名、DEX 字符串池特征（Aho-Corasick 字节级单遍扫描，
 * 不把 dex 转成字符串，速度极快，且有字节预算上限）、AndroidManifest 的 Application 桩类、
 * assets 特征文件。命中任一“强特征”即判定为对应平台；仅命中弱特征则给“疑似”；
 * 未命中已知平台但存在明显壳特征时，给出“私人/自研加固”小提示。</p>
 *
 * <p>注意：本类中的特征串均以“反转字面量”存储（见 {@link #r(String)}），运行时还原，
 * 避免本应用自身的 classes.dex 出现完整特征串而在分析自身时误报。</p>
 */
public final class HardenDetector {

    private HardenDetector() {
    }

    /**
     * 默认最多扫描的 DEX 字节数：加固壳的加载器类几乎都在 classes.dex，
     * 16MB 预算足够覆盖绝大多数加固样本，同时避免为非加固大应用做无谓扫描。
     */
    public static final long DEFAULT_DEX_SCAN_LIMIT = 16L * 1024 * 1024;

    /** 识别结果。 */
    public static class Result {
        public boolean detected;
        public String platform = "";
        public int confidence;
        public String level = "";
        public final List<String> evidence = new ArrayList<>();
        public boolean suspectSelfMade;
        public final List<String> selfMadeHints = new ArrayList<>();
        public String displayText = "未检测到加固";
    }

    private static final class Packer {
        final String key;
        final String[] strongSo;
        final String[] strongStr;
        final String[] weakSo;
        final String[] weakStr;
        final String[] assets;

        Packer(String key, String[] strongSo, String[] strongStr,
               String[] weakSo, String[] weakStr, String[] assets) {
            this.key = key;
            this.strongSo = strongSo;
            this.strongStr = strongStr;
            this.weakSo = weakSo;
            this.weakStr = weakStr;
            this.assets = assets;
        }
    }

    private static final Pattern RANDOM_SO = Pattern.compile("lib[0-9a-f]{10,}\\.so");

    // ==================== 对外入口 ====================

    public static Result detect(File apk) {
        return detect(apk, DEFAULT_DEX_SCAN_LIMIT);
    }

    public static Result detect(File apk, long maxDexScanBytes) {
        Result result = new Result();
        if (apk == null || !apk.isFile()) {
            return result;
        }

        List<String> soNames = new ArrayList<>();
        List<String> assetSigs = new ArrayList<>();
        List<ZipEntry> dexEntries = new ArrayList<>();
        List<String> randomSo = new ArrayList<>();
        List<String> bigAssets = new ArrayList<>();
        long totalDexSize = 0L;
        long classesDexSize = -1L;
        String appClass = "";
        String appDescriptor = "";
        int appPattern = -1;
        boolean appClassFoundInDex = true;

        List<Packer> packers = database();
        Aho aho = new Aho();
        int[] counts = new int[0];
        int[] strongHit = new int[packers.size()];
        int[] weakHit = new int[packers.size()];
        List<List<String>> evidence = new ArrayList<>();
        for (int i = 0; i < packers.size(); i++) {
            evidence.add(new ArrayList<String>());
        }

        // ---------- 1~4. 只打开一次 zip：列条目、读 Manifest、单遍扫描 DEX ----------
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                String lower = name.toLowerCase(Locale.US);
                String baseName = base(lower);

                if (lower.endsWith(".so")) {
                    soNames.add(baseName);
                    if (RANDOM_SO.matcher(baseName).matches()) {
                        randomSo.add(baseName);
                    }
                }
                if (lower.startsWith("assets/")) {
                    assetSigs.add(baseName);
                    long size = entry.getSize();
                    if (size > 200 * 1024
                            && (lower.endsWith(".dat") || lower.endsWith(".ajm")
                            || lower.endsWith(".bin"))) {
                        bigAssets.add(baseName + "（" + Util.formatSize(size) + "）");
                    }
                }
                if (baseName.matches("classes\\d*\\.dex")) {
                    dexEntries.add(entry);
                    long size = entry.getSize();
                    if (size > 0) {
                        totalDexSize += size;
                    }
                    if (lower.equals("classes.dex")) {
                        classesDexSize = size;
                    }
                }
            }

            // ---------- 2. 读取 AndroidManifest，取 Application 桩类 ----------
            ZipEntry manifestEntry = zip.getEntry("AndroidManifest.xml");
            if (manifestEntry != null) {
                byte[] manifest = readEntryLimited(zip, manifestEntry, 2L * 1024 * 1024);
                if (manifest != null && Axml.isBinaryXml(manifest)) {
                    Axml.Doc doc = Axml.decode(manifest);
                    appClass = findApplicationClass(doc);
                }
            }
            appDescriptor = appClass.isEmpty() ? "" : "L" + appClass.replace('.', '/') + ";";

            // ---------- 3. 先算“廉价但强”的证据：native 库名 / assets 特征 / Application 桩类 ----------
            collectCheapEvidence(packers, soNames, assetSigs, appClass, strongHit, weakHit, evidence);

            // 廉价证据已足够定论时，直接跳过昂贵的 DEX 字符串扫描 —— 这是“大 APK 也能立即出结果”
            // 的关键：加固壳的 so/assets 特征命中率极高，无需再扫十几 MB 的 DEX。
            if (bestConfidence(strongHit, weakHit) < MIN_DECIDE_CONFIDENCE) {
                // ---------- 4. 构建 Aho-Corasick 字节自动机（DEX 字符串特征） ----------
                for (int i = 0; i < packers.size(); i++) {
                    Packer packer = packers.get(i);
                    for (String token : packer.strongStr) {
                        aho.add(token, i, 0);
                        addDotted(aho, token, i, 0);
                    }
                    for (String token : packer.weakStr) {
                        aho.add(token, i, 1);
                        addDotted(aho, token, i, 1);
                    }
                }
                if (!appDescriptor.isEmpty()) {
                    appPattern = aho.add(appDescriptor, -1, 2);
                }
                aho.build();

                // ---------- 4b. 单遍扫描 DEX（受字节预算限制，超出即停） ----------
                // classes.dex 排在最前：加固壳的加载器类几乎都在这里，确保预算花在关键文件上
                Collections.sort(dexEntries, (a, b) -> a.getName().compareTo(b.getName()));
                counts = new int[aho.size()];
                long scanned = 0L;
                for (ZipEntry dexEntry : dexEntries) {
                    long size = dexEntry.getSize();
                    if (size <= 0 || scanned >= maxDexScanBytes) {
                        continue;
                    }
                    byte[] data = readEntryLimited(zip, dexEntry, maxDexScanBytes - scanned);
                    if (data == null || data.length == 0) {
                        continue;
                    }
                    aho.scan(data, counts);
                    scanned += data.length;
                }

                appClassFoundInDex = appDescriptor.isEmpty()
                        || (appPattern >= 0 && counts[appPattern] > 0);
                addDexEvidence(counts, aho, strongHit, weakHit, evidence);
            } else {
                appClassFoundInDex = true;
            }
        } catch (Exception ignored) {
        }

        // ---------- 6. 计算置信度，选最佳平台 ----------
        int bestIndex = -1;
        int bestConf = 0;
        for (int i = 0; i < packers.size(); i++) {
            int conf = confidenceOf(strongHit[i], weakHit[i]);
            if (conf > bestConf) {
                bestConf = conf;
                bestIndex = i;
            }
        }

        if (bestIndex >= 0 && bestConf >= 55) {
            result.detected = true;
            result.platform = platformName(packers.get(bestIndex).key);
            result.confidence = bestConf;
            result.level = bestConf >= 85 ? "高" : (bestConf >= 70 ? "中" : "低");
            result.evidence.addAll(dedup(evidence.get(bestIndex)));
            String evidenceText = String.join("、", result.evidence);
            result.displayText = result.platform + "（置信度 " + bestConf + "% · " + result.level + "）"
                    + (evidenceText.isEmpty() ? "" : "\n特征：" + evidenceText);
            return result;
        }

        // ---------- 7. 私人/自研加固启发式 + 弱特征提示 ----------
        int score = 0;
        if (!appDescriptor.isEmpty() && !appClassFoundInDex) {
            score += 45;
            result.selfMadeHints.add("Application 类未出现在任何 DEX 中（" + appClass + "，疑似壳动态加载）");
        }
        if (!randomSo.isEmpty()) {
            score += 30;
            result.selfMadeHints.add("随机命名 native 库：" + joinLimit(randomSo, 3));
        }
        if (classesDexSize >= 0 && classesDexSize < 64 * 1024 && totalDexSize > 512 * 1024) {
            score += 30;
            result.selfMadeHints.add("classes.dex 极小（" + Util.formatSize(classesDexSize)
                    + "），整体 DEX 体积 " + Util.formatSize(totalDexSize));
        }
        if (!bigAssets.isEmpty()) {
            score += 20;
            result.selfMadeHints.add("存在大体积可疑资源：" + joinLimit(bigAssets, 3));
        }

        boolean weakPlatformHint = score < 30 && bestIndex >= 0
                && !evidence.get(bestIndex).isEmpty() && bestConf == 32;

        if (score >= 30) {
            result.suspectSelfMade = true;
            result.level = "疑似";
            result.confidence = Math.min(90, Math.max(30, score));
            result.displayText = "疑似私人/自研加固（置信度 " + result.confidence + "%）\n"
                    + "特征：" + String.join("\n· ", result.selfMadeHints)
                    + "\n提示：未匹配到已知加固平台，可能为私人或自研壳，建议进一步人工分析。";
        } else if (weakPlatformHint) {
            result.suspectSelfMade = true;
            result.level = "疑似";
            result.confidence = bestConf;
            result.platform = platformName(packers.get(bestIndex).key);
            result.evidence.addAll(dedup(evidence.get(bestIndex)));
            result.displayText = "疑似 " + result.platform + "（置信度 " + bestConf + "% · 证据不足）\n"
                    + "弱特征：" + String.join("、", result.evidence)
                    + "\n提示：仅命中弱特征，可能误报，仅供参考。";
        } else {
            result.displayText = "未检测到加固";
        }
        return result;
    }

    /** 从 zip 条目读取至多 limit 字节；已知大小时一次性分配，避免缓冲区反复扩容拷贝。 */
    private static byte[] readEntryLimited(ZipFile zip, ZipEntry entry, long limit) {
        if (limit <= 0) {
            return null;
        }
        int cap = (int) Math.min(limit, 64L * 1024 * 1024);
        long declared = entry.getSize();
        try (InputStream in = zip.getInputStream(entry)) {
            if (declared > 0 && declared <= cap) {
                byte[] data = new byte[(int) declared];
                int total = 0;
                while (total < data.length) {
                    int read = in.read(data, total, data.length - total);
                    if (read <= 0) {
                        break;
                    }
                    total += read;
                }
                if (total == data.length) {
                    return data;
                }
                if (total <= 0) {
                    return null;
                }
                byte[] exact = new byte[total];
                System.arraycopy(data, 0, exact, 0, total);
                return exact;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(cap, 1 << 20));
            byte[] buffer = new byte[Math.min(cap, 1 << 16)];
            int total = 0;
            while (total < cap) {
                int read = in.read(buffer, 0, Math.min(buffer.length, cap - total));
                if (read <= 0) {
                    break;
                }
                out.write(buffer, 0, read);
                total += read;
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Aho-Corasick ====================

    /**
     * 字节级 Aho-Corasick（ASCII 模式），对 DEX 做单遍多模式匹配。
     * 跳转表用扁平 int 数组（node*128 + c），避免 HashMap 装箱开销，扫描速度数倍于 Map 实现。
     */
    private static final class Aho {
        private static final int ALPHA = 128;
        private static final int NONE = -1;

        private int[] next = new int[ALPHA];
        private int[] fail = new int[1];
        private int nodeCount = 1;
        private final List<int[]> out = new ArrayList<>();
        private final List<String> labels = new ArrayList<>();
        private final List<int[]> meta = new ArrayList<>();

        Aho() {
            java.util.Arrays.fill(next, NONE);
            out.add(new int[0]);
        }

        int size() {
            return labels.size();
        }

        String label(int id) {
            return labels.get(id);
        }

        int packer(int id) {
            return meta.get(id)[0];
        }

        int kind(int id) {
            return meta.get(id)[1];
        }

        int add(String pattern, int packerIndex, int kind) {
            int id = labels.size();
            labels.add(pattern);
            meta.add(new int[]{packerIndex, kind});
            int node = 0;
            for (int i = 0; i < pattern.length(); i++) {
                int c = pattern.charAt(i);
                if (c >= ALPHA || c < 0) {
                    continue;
                }
                int idx = node * ALPHA + c;
                if (next[idx] == NONE) {
                    int created = nodeCount++;
                    if (created * ALPHA + ALPHA > next.length) {
                        next = java.util.Arrays.copyOf(next, created * ALPHA + ALPHA);
                        java.util.Arrays.fill(next, (created - 1) * ALPHA + ALPHA,
                                created * ALPHA + ALPHA, NONE);
                    }
                    next[idx] = created;
                    fail = java.util.Arrays.copyOf(fail, created + 1);
                    fail[created] = 0;
                    out.add(new int[0]);
                }
                node = next[idx];
            }
            int[] old = out.get(node);
            int[] updated = new int[old.length + 1];
            System.arraycopy(old, 0, updated, 0, old.length);
            updated[old.length] = id;
            out.set(node, updated);
            return id;
        }

        void build() {
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int c = 0; c < ALPHA; c++) {
                int n = next[c];
                if (n != NONE) {
                    fail[n] = 0;
                    queue.add(n);
                }
            }
            while (!queue.isEmpty()) {
                int node = queue.poll();
                int f = fail[node];
                if (f != 0) {
                    int[] own = out.get(node);
                    int[] inherited = out.get(f);
                    if (inherited.length > 0) {
                        int[] merged = new int[own.length + inherited.length];
                        System.arraycopy(own, 0, merged, 0, own.length);
                        System.arraycopy(inherited, 0, merged, own.length, inherited.length);
                        out.set(node, merged);
                    }
                }
                int rowBase = node * ALPHA;
                for (int c = 0; c < ALPHA; c++) {
                    int child = next[rowBase + c];
                    if (child == NONE) {
                        continue;
                    }
                    int back = f;
                    int t = next[back * ALPHA + c];
                    while (back != 0 && t == NONE) {
                        back = fail[back];
                        t = next[back * ALPHA + c];
                    }
                    if (t != NONE && t == child) {
                        t = NONE;
                    }
                    fail[child] = (t == NONE) ? 0 : t;
                    queue.add(child);
                }
            }
        }

        void scan(byte[] data, int[] counts) {
            int node = 0;
            for (int i = 0; i < data.length; i++) {
                int c = data[i] & 0xFF;
                if (c >= ALPHA) {
                    // 所有模式均为 ASCII，高位字节不可能参与匹配，直接回到根
                    node = 0;
                    continue;
                }
                int t = next[node * ALPHA + c];
                while (node != 0 && t == NONE) {
                    node = fail[node];
                    t = next[node * ALPHA + c];
                }
                node = (t == NONE) ? 0 : t;
                int[] o = out.get(node);
                for (int k = 0; k < o.length; k++) {
                    counts[o[k]]++;
                }
            }
        }
    }

    private static void addDotted(Aho aho, String token, int packerIndex, int kind) {
        String dotted = token.replace('/', '.');
        if (!dotted.equals(token)) {
            aho.add(dotted, packerIndex, kind);
        }
    }

    // ==================== 工具方法 ====================

    private static String r(String reversed) {
        return new StringBuilder(reversed).reverse().toString();
    }

    private static String base(String path) {
        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    /** 廉价证据足以直接定论的最低置信度（与最终判定阈值一致）。 */
    private static final int MIN_DECIDE_CONFIDENCE = 55;

    /** 由 strong/weak 命中数计算置信度。 */
    private static int confidenceOf(int strong, int weak) {
        if (strong > 0) {
            return Math.min(98, 78 + 8 * strong + 4 * weak);
        }
        if (weak >= 2) {
            return Math.min(72, 40 + 10 * weak);
        }
        if (weak == 1) {
            return 32;
        }
        return 0;
    }

    /** 当前所有平台中的最高置信度。 */
    private static int bestConfidence(int[] strongHit, int[] weakHit) {
        int best = 0;
        for (int i = 0; i < strongHit.length; i++) {
            int conf = confidenceOf(strongHit[i], weakHit[i]);
            if (conf > best) {
                best = conf;
            }
        }
        return best;
    }

    /** 收集“廉价但强”的证据：native 库名 / assets 特征 / Application 桩类。 */
    private static void collectCheapEvidence(List<Packer> packers, List<String> soNames,
            List<String> assetSigs, String appClass, int[] strongHit, int[] weakHit,
            List<List<String>> evidence) {
        for (int i = 0; i < packers.size(); i++) {
            Packer packer = packers.get(i);
            for (String token : packer.strongSo) {
                String hit = findSo(soNames, token);
                if (hit != null) {
                    strongHit[i]++;
                    evidence.get(i).add(hit + ".so");
                }
            }
            for (String token : packer.weakSo) {
                String hit = findSo(soNames, token);
                if (hit != null) {
                    weakHit[i]++;
                    evidence.get(i).add(hit + ".so");
                }
            }
            for (String token : packer.assets) {
                if (assetSigs.contains(token)) {
                    strongHit[i]++;
                    evidence.get(i).add(token);
                }
            }
            if (!appClass.isEmpty() && matchesStubClass(appClass, packer)) {
                strongHit[i]++;
                evidence.get(i).add("Application=" + appClass);
            }
        }
    }

    /** 把 DEX 字符串扫描结果累加为证据。 */
    private static void addDexEvidence(int[] counts, Aho aho, int[] strongHit, int[] weakHit,
            List<List<String>> evidence) {
        for (int id = 0; id < counts.length; id++) {
            if (counts[id] == 0) {
                continue;
            }
            int kind = aho.kind(id);
            if (kind == 2) {
                continue;
            }
            int packerIndex = aho.packer(id);
            if (kind == 0) {
                strongHit[packerIndex]++;
            } else {
                weakHit[packerIndex]++;
            }
            evidence.get(packerIndex).add(aho.label(id));
        }
    }

    private static String findSo(List<String> soNames, String token) {
        String lowerToken = token.toLowerCase(Locale.US);
        for (String name : soNames) {
            if (name.contains(lowerToken)) {
                return token;
            }
        }
        return null;
    }

    private static boolean matchesStubClass(String appClass, Packer packer) {
        for (String token : packer.strongStr) {
            if (token.replace('/', '.').equals(appClass)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> dedup(List<String> input) {
        List<String> out = new ArrayList<>();
        for (String item : input) {
            if (!out.contains(item)) {
                out.add(item);
            }
        }
        return out;
    }

    private static String joinLimit(List<String> items, int limit) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < items.size() && i < limit; i++) {
            if (i > 0) {
                builder.append("、");
            }
            builder.append(items.get(i));
        }
        if (items.size() > limit) {
            builder.append(" 等 ").append(items.size()).append(" 项");
        }
        return builder.toString();
    }

    private static String platformName(String key) {
        switch (key) {
            case "360": return "360 加固";
            case "legu": return "腾讯乐固";
            case "ysafe": return "腾讯御安全";
            case "ijiami": return "爱加密";
            case "bangcle": return "梆梆加固";
            case "bangcle_ent": return "梆梆加固（企业版）";
            case "nagain": return "娜迦加固";
            case "ali": return "阿里聚安全";
            case "baidu": return "百度加固";
            case "dingxiang": return "顶象加固";
            case "tpf": return "通付盾加固";
            case "kiwi": return "几维安全";
            case "netease": return "网易易盾";
            case "apkguard": return "APKGuard加固";
            default: return key;
        }
    }

    private static String findApplicationClass(Axml.Doc doc) {
        if (doc == null || doc.root == null) {
            return "";
        }
        String pkg = attr(doc.root, "package");
        Axml.Node application = findNode(doc.root, "application");
        if (application == null) {
            return "";
        }
        String name = attr(application, "name");
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (name.startsWith(".")) {
            return pkg + name;
        }
        if (!name.contains(".")) {
            return pkg.isEmpty() ? name : pkg + "." + name;
        }
        return name;
    }

    private static Axml.Node findNode(Axml.Node node, String name) {
        if (node == null) {
            return null;
        }
        if (name.equalsIgnoreCase(node.name)) {
            return node;
        }
        for (Axml.Node child : node.children) {
            Axml.Node found = findNode(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String attr(Axml.Node node, String name) {
        if (node == null) {
            return "";
        }
        for (Axml.Attr attribute : node.attrs) {
            if (name.equalsIgnoreCase(attribute.name)) {
                return attribute.value == null ? "" : attribute.value;
            }
        }
        return "";
    }

    // ==================== 特征库 ====================

    private static List<Packer> database() {
        List<Packer> list = new ArrayList<>();
        add(list, "360",
                new String[]{r("ugaijbil"), r("tdgjbil")},
                new String[]{r("ppAbutS/buts/moc"), r("noitacilppAbutS/buts/moc"),
                        r("noitacilppAbutS/litu/oohiq/moc"), r("ppAbutS/litu/oohiq/moc")},
                new String[]{},
                new String[]{},
                new String[]{r("raj.ugaijbil")});
        add(list, "legu",
                new String[]{r("allehsbil"), r("xllehsbil"), r("putbil"), r("repus-llehsbil")},
                new String[]{r("yrtnEppAxT/llehSbutS/tnecnet/moc"), r("llehSbutS/tnecnet/moc")},
                new String[]{},
                new String[]{},
                new String[]{r("l1l111l00OO0"), r("OOO0OOoo0o")});
        add(list, "ysafe",
                new String[]{r("noitcetorpsotbil")},
                new String[]{},
                new String[]{},
                new String[]{r("ytiruces/tnecnet/moc")},
                new String[]{});
        add(list, "ijiami",
                new String[]{r("niamcexebil"), r("imaijibil")},
                new String[]{r("S/l/l/e/h/s"), r("noitacilppArepuS/llehs/moc"), r("imaiji/moc")},
                new String[]{r("cexebil")},
                new String[]{},
                new String[]{r("tad.imaiji"), r("mja.imaiji")});
        add(list, "bangcle",
                new String[]{r("execesbil"), r("niamcesbil"), r("daolerpcesbil")},
                new String[]{r("repparWnoitacilppA/repparwkpa/oences/moc"), r("repparwkpa/oences/moc")},
                new String[]{},
                new String[]{},
                new String[]{});
        add(list, "bangcle_ent",
                new String[]{r("llehscesbil"), r("replehxedbil")},
                new String[]{r("elcgnab/moc")},
                new String[]{},
                new String[]{},
                new String[]{});
        add(list, "nagain",
                new String[]{r("pmvsoahcbil"), r("goddbil"), r("godfbil"), r("godebil"), r("dleihsqnbil")},
                new String[]{},
                new String[]{},
                new String[]{r("niagan/moc")},
                new String[]{});
        add(list, "ali",
                new String[]{r("cesibombil")},
                new String[]{r("noitacilppAniaM/ecnahnecesibom/ila/moc"), r("ecnahnecesibom/ila/moc")},
                new String[]{},
                new String[]{},
                new String[]{});
        add(list, "baidu",
                new String[]{r("tcetorpudiabbil")},
                new String[]{r("butSppA/tcetorp/udiab/moc"), r("tcetorp/udiab/moc")},
                new String[]{},
                new String[]{},
                new String[]{});
        // 实测样本：assets/stub/<hash>/<abi>/stub.enc（头 "SOENCv1!"）+ bootstrap.bin(ELF)，
        // 根 classes.dex 只有壳框架（StubApplication/SecureComponentFactory/AppClassLoaderHook/NativeBridge），
        // 业务 dex 全加密放在随机目录（*.enc/*.vmp/*.dat）里。
        add(list, "apkguard",
                new String[]{},
                new String[]{r("dracgpa/moc"), r("noitacilppAbutS/buts/dracgpa/moc"),
                        r("yrotcaFtnenopmoCeruceS/buts/dracgpa/moc"),
                        r("buts/buts/dracgpa/moc")},
                new String[]{},
                new String[]{},
                new String[]{r("cne.buts"), r("nib.tatsorb"), r("cne/stessa")});
        add(list, "dingxiang",
                new String[]{},
                new String[]{},
                new String[]{r("gnaixgnidbil")},
                new String[]{r("gnaixgnid/moc"), r("elibom/xd/moc")},
                new String[]{});
        add(list, "tpf",
                new String[]{},
                new String[]{},
                new String[]{r("fptbil")},
                new String[]{r("fpt/moc"), r("yapnot")},
                new String[]{});
        add(list, "kiwi",
                new String[]{r("tcetorpswkbil"), r("kdsswkbil"), r("iwikbil")},
                new String[]{r("cesiwik/moc")},
                new String[]{},
                new String[]{},
                new String[]{});
        add(list, "netease",
                new String[]{r("cesenbil")},
                new String[]{r("sin/esaeten/moc")},
                new String[]{},
                new String[]{},
                new String[]{});
        return list;
    }

    private static void add(List<Packer> list, String key, String[] strongSo, String[] strongStr,
                            String[] weakSo, String[] weakStr, String[] assets) {
        list.add(new Packer(key, strongSo, strongStr, weakSo, weakStr, assets));
    }
}
