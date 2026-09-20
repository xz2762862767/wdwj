package com.mtstyle.fm.apk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Smali 汇编器：把反汇编文本重新编码为 Dalvik 指令（纯 Java，可单测）。
 *
 * <p>只处理一个方法体：.method 行必须与原方法一致，引用（字符串/类型/字段/方法/原型）
 * 必须已存在于原 dex 池中；含 switch/array-data 数据的方法只能做等长编辑（数据区按原地址复制）。</p>
 */
public final class SmaliAssembler {

    private static final char QUOTE = 34;
    private static final char HASH = 35;
    private static final char COMMA = 44;
    private static final char DOT = 46;
    private static final char COLON = 58;
    private static final char LBRACE = 123;
    private static final char RBRACE = 125;
    private static final char BACKSLASH = 92;

    private static Map<String, Integer> opcodeMap;

    private SmaliAssembler() {
    }

    /** 汇编错误（带行号，便于在 UI 上提示）。 */
    public static class SmaliError extends Exception {
        public final int line;

        public SmaliError(int line, String message) {
            super(message);
            this.line = line;
        }
    }

    /** 池查询：找不到返回 -1。 */
    public interface Pool {
        int stringIndex(String value);

        int typeIndex(String descriptor);

        int fieldIndex(String reference);

        int methodIndex(String reference);

        int protoIndex(String descriptor);
    }

    /** 基于 Dex 的池实现（构建一次即可重复使用）。 */
    public static final class DexPool implements Pool {
        private final Map<String, Integer> strings = new HashMap<>();
        private final Map<String, Integer> types = new HashMap<>();
        private final Map<String, Integer> fields = new HashMap<>();
        private final Map<String, Integer> methods = new HashMap<>();
        private final Map<String, Integer> protos = new HashMap<>();

        public DexPool(Dex dex) {
            for (int i = 0; i < dex.strings.size(); i++) {
                String value = dex.strings.get(i);
                if (!strings.containsKey(value)) {
                    strings.put(value, i);
                }
            }
            for (int i = 0; i < dex.types.size(); i++) {
                String value = dex.types.get(i);
                if (!types.containsKey(value)) {
                    types.put(value, i);
                }
            }
            for (int i = 0; i < dex.fields.size(); i++) {
                String value = dex.fields.get(i).reference();
                if (!fields.containsKey(value)) {
                    fields.put(value, i);
                }
            }
            for (int i = 0; i < dex.methods.size(); i++) {
                String value = dex.methods.get(i).reference();
                if (!methods.containsKey(value)) {
                    methods.put(value, i);
                }
            }
            for (int i = 0; i < dex.protos.size(); i++) {
                String value = dex.protos.get(i).descriptor();
                if (!protos.containsKey(value)) {
                    protos.put(value, i);
                }
            }
        }

        public int stringIndex(String value) {
            Integer index = strings.get(value);
            return index == null ? -1 : index;
        }

        public int typeIndex(String descriptor) {
            Integer index = types.get(descriptor);
            return index == null ? -1 : index;
        }

        public int fieldIndex(String reference) {
            Integer index = fields.get(reference);
            return index == null ? -1 : index;
        }

        public int methodIndex(String reference) {
            Integer index = methods.get(reference);
            return index == null ? -1 : index;
        }

        public int protoIndex(String descriptor) {
            Integer index = protos.get(descriptor);
            return index == null ? -1 : index;
        }
    }

    /** payload（packed/sparse-switch、fill-array-data）占位，数据由补丁器从原方法复制。 */
    public static final class Payload {
        public int address;
        public int originalAddress;
        public int units;
    }

    /** 单个 catch 处理器。 */
    public static final class Handler {
        /** 捕获类型索引，catch-all 为 -1。 */
        public int typeIdx = -1;
        public int address;
    }

    /** 汇编结果。 */
    public static final class Program {
        public Pool pool;
        public int registersSize;
        public int insSize;
        public int maxRegister = -1;
        public short[] insns = new short[0];
        public int[] tryStarts = new int[0];
        public int[] tryInsnCounts = new int[0];
        public List<List<Handler>> handlers = new ArrayList<>();
        public final List<Payload> payloads = new ArrayList<>();

        public boolean hasPayloads() {
            return !payloads.isEmpty();
        }
    }

    /** 分支目标修复项。 */
    private static final class Fixup {
        int address;
        byte format;
        String label;
        int line;

        Fixup(int address, byte format, String label, int line) {
            this.address = address;
            this.format = format;
            this.label = label;
            this.line = line;
        }
    }

    /** 待解析的 catch 行。 */
    private static final class CatchLine {
        String startLabel;
        int typeIdx;
        String handlerLabel;
        int line;
    }

    /**
     * 汇编一个方法体。
     *
     * @param dex    原 dex（提供格式表与原型）
     * @param method 目标方法（必须带 code）
     * @param text   编辑后的 Smali 方法文本
     * @param pool   池查询
     */
    public static Program assemble(Dex dex, Dex.EncodedMethod method, String text, Pool pool)
            throws SmaliError {
        Dex.CodeItem original = dex.code(method);
        if (original == null) {
            throw new SmaliError(0, "该方法没有代码（abstract/native 方法不可修改）");
        }
        Program program = new Program();
        program.pool = pool;
        program.insSize = original.insSize;
        program.registersSize = original.registersSize;
        Map<String, Integer> labels = new HashMap<>();
        List<CatchLine> catchLines = new ArrayList<>();
        List<Fixup> fixups = new ArrayList<>();
        ShortSink sink = new ShortSink(original.insnsSize + 64);
        String expectedHeader = expectedHeader(dex, method);
        String[] lines = splitLines(text);
        boolean headerSeen = false;
        for (int index = 0; index < lines.length; index++) {
            int lineNo = index + 1;
            String raw = lines[index];
            String trimmed = stripComment(raw).trim();
            if (trimmed.length() == 0) {
                continue;
            }
            char first = trimmed.charAt(0);
            if (first == DOT) {
                if (trimmed.startsWith(".method")) {
                    headerSeen = true;
                    if (!collapse(trimmed).equals(collapse(expectedHeader))) {
                        throw new SmaliError(lineNo, "只能修改方法体，.method 行需保持为： " + expectedHeader);
                    }
                    continue;
                }
                if (trimmed.startsWith(".registers")) {
                    int value = parseIntSafe(trimmed.substring(".registers".length()).trim(), -1);
                    if (value < 0) {
                        throw new SmaliError(lineNo, ".registers 数值无效：" + trimmed);
                    }
                    program.registersSize = value;
                    continue;
                }
                if (trimmed.startsWith(".end")) {
                    continue;
                }
                throw new SmaliError(lineNo, "暂不支持该伪指令：" + trimmed);
            }
            if (first == COLON) {
                String token = firstToken(trimmed);
                if (token.startsWith(":catch")) {
                    catchLines.add(parseCatchLine(trimmed, pool, lineNo));
                    continue;
                }
                if (labels.containsKey(token)) {
                    throw new SmaliError(lineNo, "重复的标签：" + token);
                }
                labels.put(token, sink.size());
                continue;
            }
            if (!headerSeen) {
                throw new SmaliError(lineNo, "缺少 .method 行");
            }
            int payloadUnits = payloadUnits(trimmed);
            if (payloadUnits > 0) {
                Payload payload = new Payload();
                payload.address = sink.size();
                payload.originalAddress = trailingAddress(raw);
                payload.units = payloadUnits;
                program.payloads.add(payload);
                for (int i = 0; i < payloadUnits; i++) {
                    sink.add(0);
                }
                continue;
            }
            if (trimmed.startsWith("0x") && trimmed.indexOf(':') > 0) {
                // payload 明细行，例如：0x1 -> :label_0004
                continue;
            }
            String mnemonic = firstToken(trimmed);
            Integer opcode = opcodeMap().get(mnemonic);
            if (opcode == null) {
                throw new SmaliError(lineNo, "未知指令：" + mnemonic);
            }
            String rest = trimmed.substring(mnemonic.length()).trim();
            encode(program, sink, fixups, opcode, splitOperands(rest), lineNo);
        }
        program.insns = sink.toArray();
        if (program.registersSize < program.insSize) {
            throw new SmaliError(0, ".registers(" + program.registersSize + ") 不能小于参数占用("
                    + program.insSize + ")");
        }
        if (program.maxRegister >= program.registersSize) {
            throw new SmaliError(0, "用到了超出 .registers(" + program.registersSize + ") 的寄存器 v"
                    + program.maxRegister + "，请调大 .registers");
        }
        applyFixups(program.insns, fixups, labels);
        buildTries(program, catchLines, labels);
        return program;
    }

    /** 把 catch 行按 try 区间整理成 tries + handlers（区间为空的 try 丢弃）。 */
    private static void buildTries(Program program, List<CatchLine> catchLines,
                                   Map<String, Integer> labels) throws SmaliError {
        Map<String, Integer> seen = new HashMap<>();
        Map<String, List<Handler>> handlers = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (CatchLine line : catchLines) {
            if (!seen.containsKey(line.startLabel)) {
                seen.put(line.startLabel, order.size());
                handlers.put(line.startLabel, new ArrayList<>());
                order.add(line.startLabel);
            }
            Handler handler = new Handler();
            handler.typeIdx = line.typeIdx;
            handler.address = label(labels, line.handlerLabel, line.line);
            handlers.get(line.startLabel).add(handler);
        }
        List<Integer> starts = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (String startLabel : order) {
            int start = label(labels, startLabel, 0);
            String endLabel = ":try_end" + startLabel.substring(":try_start".length());
            Integer end = labels.get(endLabel);
            if (end == null || end <= start) {
                continue;
            }
            starts.add(start);
            counts.add(end - start);
            program.handlers.add(handlers.get(startLabel));
        }
        program.tryStarts = new int[starts.size()];
        program.tryInsnCounts = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            program.tryStarts[i] = starts.get(i);
            program.tryInsnCounts[i] = counts.get(i);
        }
    }

    /** 解析标签行（:name）。 */
    private static int label(Map<String, Integer> labels, String name, int line) throws SmaliError {
        Integer address = labels.get(name);
        if (address == null) {
            throw new SmaliError(line, "未找到标签：" + name);
        }
        return address;
    }

    private static String[] splitLines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 10) {
                int end = i;
                if (end > start && text.charAt(end - 1) == 13) {
                    end--;
                }
                out.add(text.substring(start, end));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out.toArray(new String[0]);
    }

    /** 去掉行尾注释（跳过字符串字面量内的 #）。 */
    private static String stripComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == BACKSLASH) {
                    i++;
                    continue;
                }
                if (c == QUOTE) {
                    inString = false;
                }
                continue;
            }
            if (c == QUOTE) {
                inString = true;
                continue;
            }
            if (c == HASH) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** 折叠连续空白（比较 .method 行时容忍空格差异）。 */
    private static String collapse(String line) {
        StringBuilder builder = new StringBuilder(line.length());
        boolean space = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ' || c == 9) {
                space = true;
                continue;
            }
            if (space && builder.length() > 0) {
                builder.append(' ');
            }
            space = false;
            builder.append(c);
        }
        return builder.toString();
    }

    private static String firstToken(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) != ' ' && line.charAt(i) != 9) {
            i++;
        }
        return line.substring(0, i);
    }

    /** 按逗号切分操作数（忽略 {} 内与字符串内的逗号）。 */
    private static List<String> splitOperands(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.length() == 0) {
            return out;
        }
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                current.append(c);
                if (c == BACKSLASH) {
                    if (i + 1 < text.length()) {
                        current.append(text.charAt(++i));
                    }
                } else if (c == QUOTE) {
                    inString = false;
                }
                continue;
            }
            if (c == QUOTE) {
                inString = true;
                current.append(c);
                continue;
            }
            if (c == LBRACE) {
                depth++;
            } else if (c == RBRACE) {
                depth--;
            } else if (c == COMMA && depth == 0) {
                out.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        String tail = current.toString().trim();
        if (tail.length() > 0) {
            out.add(tail);
        }
        return out;
    }

    private static int parseIntSafe(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 解析立即数（支持 0x / -0x / 十进制）。 */
    private static long parseLiteral(String token, int line) throws SmaliError {
        String text = token.trim();
        boolean negative = false;
        if (text.startsWith("-")) {
            negative = true;
            text = text.substring(1);
        } else if (text.startsWith("+")) {
            text = text.substring(1);
        }
        boolean hex = text.startsWith("0x") || text.startsWith("0X");
        try {
            long value = hex ? Long.parseUnsignedLong(text.substring(2), 16) : Long.parseLong(text);
            return negative ? -value : value;
        } catch (NumberFormatException e) {
            throw new SmaliError(line, "立即数无效：" + token);
        }
    }

    /** 解析字符串字面量（去引号 + 反转义）。 */
    private static String unescape(String token, int line) throws SmaliError {
        String text = token.trim();
        if (text.length() < 2 || text.charAt(0) != QUOTE) {
            throw new SmaliError(line, "字符串字面量无效：" + token);
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == QUOTE) {
                return out.toString();
            }
            if (c != BACKSLASH) {
                out.append(c);
                continue;
            }
            if (i + 1 >= text.length()) {
                break;
            }
            char next = text.charAt(++i);
            if (next == 'n') {
                out.append((char) 10);
            } else if (next == 'r') {
                out.append((char) 13);
            } else if (next == 't') {
                out.append((char) 9);
            } else if (next == 'u') {
                if (i + 4 >= text.length()) {
                    throw new SmaliError(line, "转义无效：" + token);
                }
                String hex = text.substring(i + 1, i + 5);
                if (!isHex(hex)) {
                    throw new SmaliError(line, "转义无效：" + token);
                }
                out.append((char) Integer.parseInt(hex, 16));
                i += 4;
            } else {
                out.append(next);
            }
        }
        throw new SmaliError(line, "字符串未闭合：" + token);
    }

    private static boolean isHex(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return text.length() > 0;
    }

    private static void set32(short[] insns, int index, int value) {
        insns[index] = (short) (value & 0xFFFF);
        insns[index + 1] = (short) ((value >>> 16) & 0xFFFF);
    }

    /** 可自增长的 16 位码元缓冲。 */
    private static final class ShortSink {
        private short[] data;
        private int count;

        ShortSink(int capacity) {
            data = new short[Math.max(16, capacity)];
        }

        int size() {
            return count;
        }

        void add(int value) {
            if (count == data.length) {
                data = Arrays.copyOf(data, count * 2);
            }
            data[count++] = (short) value;
        }

        short[] toArray() {
            return Arrays.copyOf(data, count);
        }
    }

    /** 按空白切分（忽略 {} 内与字符串内的空白）。 */
    private static List<String> splitSpaces(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                current.append(c);
                if (c == BACKSLASH && i + 1 < text.length()) {
                    current.append(text.charAt(++i));
                } else if (c == QUOTE) {
                    inString = false;
                }
                continue;
            }
            if (c == QUOTE) {
                inString = true;
                current.append(c);
                continue;
            }
            if (c == LBRACE) {
                depth++;
            } else if (c == RBRACE) {
                depth--;
            }
            if ((c == ' ' || c == 9) && depth == 0) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /** 解析 catch / catchall 行。 */
    private static CatchLine parseCatchLine(String line, Pool pool, int lineNo) throws SmaliError {
        List<String> parts = splitSpaces(line);
        if (parts.size() < 4) {
            throw new SmaliError(lineNo, "catch 行格式错误：" + line);
        }
        String range = parts.get(1);
        int dots = range.indexOf("..");
        if (dots < 0 || range.length() < 2) {
            throw new SmaliError(lineNo, "catch 行区间格式错误：" + line);
        }
        CatchLine result = new CatchLine();
        result.line = lineNo;
        result.startLabel = range.substring(1, dots).trim();
        String end = range.substring(dots + 2).trim();
        if (end.length() > 1 && end.charAt(end.length() - 1) == RBRACE) {
            end = end.substring(0, end.length() - 1).trim();
        }
        if (!end.startsWith(":try_end") || !result.startLabel.startsWith(":try_start")) {
            throw new SmaliError(lineNo, "catch 行区间标签需为 :try_start_N .. :try_end_N：" + line);
        }
        int typeIdx = -1;
        String type = parts.get(2);
        if (type.length() > 0 && type.charAt(0) != '-') {
            typeIdx = pool.typeIndex(type);
            if (typeIdx < 0) {
                throw new SmaliError(lineNo, "dex 中不存在捕获类型：" + type);
            }
        }
        result.typeIdx = typeIdx;
        result.handlerLabel = parts.get(parts.size() - 1);
        return result;
    }

    /** payload 伪指令占用的码元数（非 payload 返回 0）。 */
    private static int payloadUnits(String line) {
        boolean payload = line.startsWith("packed-switch-data") || line.startsWith("sparse-switch-data")
                || line.startsWith("fill-array-data");
        if (!payload) {
            return 0;
        }
        int open = line.indexOf('(');
        if (open < 0) {
            return 0;
        }
        int close = line.indexOf(' ', open + 1);
        if (close < 0) {
            close = line.length();
        }
        return parseIntSafe(line.substring(open + 1, close).trim(), 0);
    }

    /** 行尾注释里的地址（payload 行使用，返回 -1 表示无）。 */
    private static int trailingAddress(String line) {
        int hash = line.lastIndexOf(HASH);
        if (hash < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(line.substring(hash + 1).trim(), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 期望的 .method 行（必须与原方法一致）。 */
    private static String expectedHeader(Dex dex, Dex.EncodedMethod method) {
        Dex.MethodId id = method.methodIdx >= 0 && method.methodIdx < dex.methods.size()
                ? dex.methods.get(method.methodIdx) : null;
        if (id == null || id.proto == null) {
            return ".method";
        }
        return ".method " + Dex.modifiers(method.accessFlags, true) + id.name + id.proto.descriptor();
    }

    private static Map<String, Integer> opcodeMap() {
        Map<String, Integer> map = opcodeMap;
        if (map == null) {
            map = new HashMap<>();
            String[] names = Dex.opNames();
            for (int i = 0; i < names.length; i++) {
                if (names[i] != null && !map.containsKey(names[i])) {
                    map.put(names[i], i);
                }
            }
            opcodeMap = map;
        }
        return map;
    }

    /** 修复分支目标偏移。 */
    private static void applyFixups(short[] insns, List<Fixup> fixups,
                                    Map<String, Integer> labels) throws SmaliError {
        for (Fixup fixup : fixups) {
            Integer target = labels.get(fixup.label);
            if (target == null) {
                throw new SmaliError(fixup.line, "未找到分支目标标签：" + fixup.label);
            }
            int offset = target - fixup.address;
            if (fixup.format == Dex.FMT_10t) {
                if (offset < -128 || offset > 127) {
                    throw new SmaliError(fixup.line, "分支距离超出 10t 范围（±127）：" + fixup.label);
                }
                insns[fixup.address] = (short) ((insns[fixup.address] & 0x00FF) | ((offset & 0xFF) << 8));
            } else if (fixup.format == Dex.FMT_20t || fixup.format == Dex.FMT_21t
                    || fixup.format == Dex.FMT_22t) {
                if (offset < -32768 || offset > 32767) {
                    throw new SmaliError(fixup.line, "分支距离超出 16 位范围：" + fixup.label);
                }
                insns[fixup.address + 1] = (short) offset;
            } else if (fixup.format == Dex.FMT_30t || fixup.format == Dex.FMT_31t) {
                set32(insns, fixup.address + 1, offset);
            }
        }
    }

    /** 解析寄存器（支持 vN 与 pN）。 */
    private static int reg(String token, Program program, int line) throws SmaliError {
        String text = token.trim();
        if (text.length() < 2) {
            throw new SmaliError(line, "寄存器无效：" + text);
        }
        char kind = text.charAt(0);
        int value = parseIntSafe(text.substring(1), -1);
        if (value < 0) {
            throw new SmaliError(line, "寄存器无效：" + text);
        }
        int index;
        if (kind == 'v') {
            index = value;
        } else if (kind == 'p') {
            index = program.registersSize - program.insSize + value;
        } else {
            throw new SmaliError(line, "寄存器无效：" + text);
        }
        if (index < 0 || index >= program.registersSize) {
            throw new SmaliError(line, "寄存器越界（.registers " + program.registersSize + "）：" + text);
        }
        if (index > program.maxRegister) {
            program.maxRegister = index;
        }
        return index;
    }

    /** 解析 {} 寄存器列表（35c/45cc，最多 5 个）。 */
    private static int[] regList(List<String> operands, int index, Program program,
                                 int line) throws SmaliError {
        String text = operands.get(index).trim();
        if (text.length() < 2 || text.charAt(0) != LBRACE) {
            throw new SmaliError(line, "寄存器列表格式错误：" + text);
        }
        String inner = text.substring(1, text.endsWith("" + RBRACE) ? text.length() - 1 : text.length());
        List<String> parts = splitOperands(inner);
        int[] registers = new int[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            registers[i] = reg(parts.get(i), program, line);
        }
        if (registers.length > 5) {
            throw new SmaliError(line, "寄存器列表最多 5 个：" + text);
        }
        return registers;
    }

    /** 解析范围寄存器列表（3rc/4rcc：{v0 .. v3}）。 */
    private static int[] regRange(List<String> operands, int index, Program program,
                                  int line) throws SmaliError {
        String text = operands.get(index).trim();
        if (text.length() < 2 || text.charAt(0) != LBRACE) {
            throw new SmaliError(line, "寄存器列表格式错误：" + text);
        }
        String inner = text.substring(1, text.endsWith("" + RBRACE) ? text.length() - 1 : text.length());
        int dots = inner.indexOf("..");
        if (dots < 0) {
            return new int[] {reg(inner.trim(), program, line)};
        }
        int first = reg(inner.substring(0, dots).trim(), program, line);
        int last = reg(inner.substring(dots + 2).trim(), program, line);
        if (last < first) {
            throw new SmaliError(line, "寄存器范围顺序错误：" + text);
        }
        int[] registers = new int[last - first + 1];
        for (int i = 0; i < registers.length; i++) {
            registers[i] = first + i;
        }
        return registers;
    }

    private static void require(List<String> operands, int count, String name,
                                int line) throws SmaliError {
        if (operands.size() != count) {
            throw new SmaliError(line, name + " 指令需要 " + count + " 个操作数，实际 "
                    + operands.size() + " 个");
        }
    }

    private static void checkNibble(int register, int line) throws SmaliError {
        if (register > 15) {
            throw new SmaliError(line, "该格式只支持 4 位寄存器（v0-v15）：v" + register);
        }
    }

    private static void checkByte(int register, int line) throws SmaliError {
        if (register > 255) {
            throw new SmaliError(line, "该格式只支持 8 位寄存器（v0-v255）：v" + register);
        }
    }

    /** 操作数引用 → dex 池索引。 */
    private static int resolveRef(byte refKind, String token, Pool pool, int line)
            throws SmaliError {
        int index = -1;
        if (refKind == Dex.REF_STRING) {
            index = pool.stringIndex(unescape(token, line));
        } else if (refKind == Dex.REF_TYPE) {
            index = pool.typeIndex(token.trim());
        } else if (refKind == Dex.REF_FIELD) {
            index = pool.fieldIndex(token.trim());
        } else if (refKind == Dex.REF_METHOD) {
            index = pool.methodIndex(token.trim());
        } else if (refKind == Dex.REF_PROTO) {
            index = pool.protoIndex(token.trim());
        } else if (refKind == Dex.REF_NONE) {
            return 0;
        } else {
            throw new SmaliError(line, "暂不支持该引用类型：" + token);
        }
        if (index < 0) {
            throw new SmaliError(line, "该引用不在原 dex 池中（暂不支持新增池项）：" + token);
        }
        return index;
    }

    /** 编码一条指令（全部 26 种格式）。 */
    private static void encode(Program program, ShortSink sink, List<Fixup> fixups, int opcode,
                               List<String> ops, int line) throws SmaliError {
        byte format = Dex.opFormats()[opcode];
        byte refKind = Dex.opRefs()[opcode];
        int address = sink.size();
        if (format == Dex.FMT_10x) {
            require(ops, 0, "10x", line);
            sink.add(opcode);
        } else if (format == Dex.FMT_12x) {
            require(ops, 2, "12x", line);
            int a = reg(ops.get(0), program, line);
            int b = reg(ops.get(1), program, line);
            checkNibble(a, line);
            checkNibble(b, line);
            sink.add(opcode | (a << 8) | (b << 12));
        } else if (format == Dex.FMT_11n) {
            require(ops, 2, "11n", line);
            int a = reg(ops.get(0), program, line);
            checkNibble(a, line);
            int value = (int) parseLiteral(ops.get(1), line);
            if (value < -8 || value > 7) {
                throw new SmaliError(line, "11n 立即数范围 -8..7：" + ops.get(1));
            }
            sink.add(opcode | (a << 8) | ((value & 0xF) << 12));
        } else if (format == Dex.FMT_11x) {
            require(ops, 1, "11x", line);
            int a = reg(ops.get(0), program, line);
            checkByte(a, line);
            sink.add(opcode | (a << 8));
        } else if (format == Dex.FMT_10t || format == Dex.FMT_20t || format == Dex.FMT_30t) {
            require(ops, 1, "分支", line);
            sink.add(opcode);
            if (format == Dex.FMT_20t) {
                sink.add(0);
            } else if (format == Dex.FMT_30t) {
                sink.add(0);
                sink.add(0);
            }
            fixups.add(new Fixup(address, format, ops.get(0).trim(), line));
        } else if (format == Dex.FMT_22x) {
            require(ops, 2, "22x", line);
            int a = reg(ops.get(0), program, line);
            int b = reg(ops.get(1), program, line);
            checkByte(a, line);
            sink.add(opcode | (a << 8));
            sink.add(b);
        } else if (format == Dex.FMT_21t) {
            require(ops, 2, "21t", line);
            int a = reg(ops.get(0), program, line);
            checkByte(a, line);
            sink.add(opcode | (a << 8));
            sink.add(0);
            fixups.add(new Fixup(address, format, ops.get(1).trim(), line));
        } else if (format == Dex.FMT_21s) {
            require(ops, 2, "21s", line);
            int a = reg(ops.get(0), program, line);
            checkByte(a, line);
            int value = (int) parseLiteral(ops.get(1), line);
            if (value < -32768 || value > 32767) {
                throw new SmaliError(line, "21s 立即数范围 ±32768：" + ops.get(1));
            }
            sink.add(opcode | (a << 8));
            sink.add(value & 0xFFFF);
        } else if (format == Dex.FMT_21h) {
            require(ops, 2, "21h", line);
            int a = reg(ops.get(0), program, line);
            checkByte(a, line);
            long value = parseLiteral(ops.get(1), line);
            int unit;
            if (opcode == 0x19) {
                if ((value & 0x0000FFFFFFFFFFFFL) != 0) {
                    throw new SmaliError(line, "const-wide/high16 立即数须为 16 位高位对齐：" + ops.get(1));
                }
                unit = (int) ((value >>> 48) & 0xFFFF);
            } else {
                if ((value & 0xFFFFL) != 0) {
                    throw new SmaliError(line, "const/high16 立即数须为 0x????0000：" + ops.get(1));
                }
                unit = (int) ((value >> 16) & 0xFFFF);
            }
            sink.add(opcode | (a << 8));
            sink.add(unit);
        } else if (format == Dex.FMT_21c) {
            require(ops, 2, "21c", line);
            int a = reg(ops.get(0), program, line);
            checkByte(a, line);
            int index = resolveRef(refKind, ops.get(1), program.pool, line);
            if (index > 0xFFFF) {
                throw new SmaliError(line, "引用索引超出 16 位：" + ops.get(1));
            }
            sink.add(opcode | (a << 8));
            sink.add(index);
        } else if (format == Dex.FMT_23x) {
            require(ops, 3, "23x", line);
            int a = reg(ops.get(0), program, line);
            int b = reg(ops.get(1), program, line);
            int c = reg(ops.get(2), program, line);
            checkByte(a, line);
            checkByte(b, line);
            checkByte(c, line);
            sink.add(opcode | (a << 8));
            sink.add(b | (c << 8));
        } else if (format == Dex.FMT_22b) {
            require(ops, 3, "22b", line);
            int a = reg(ops.get(0), program, line);
            int b = reg(ops.get(1), program, line);
            int value = (int) parseLiteral(ops.get(2), line);
            if (value < -128 || value > 127) {
                throw new SmaliError(line, "22b 立即数范围 ±128：" + ops.get(2));
            }
            checkByte(a, line);
            checkByte(b, line);
            // 22b：A/B 都是 8 位寄存器；A 在 unit0 的 8-15 位，B 在 unit1 的低字节，字面量在 unit1 的高字节
            sink.add(opcode | (a << 8));
            sink.add(b | ((value & 0xFF) << 8));
        } else if (format == Dex.FMT_22t) {
            require(ops, 3, "22t", line);
            int a = reg(ops.get(0), program, line);
            int b = reg(ops.get(1), program, line);
            checkNibble(a, line);
            checkNibble(b, line);
            sink.add(opcode | (a << 8) | (b << 12));
            sink.add(0);
            fixups.add(new Fixup(address, format, ops.get(2).trim(), line));
        } else if (format == Dex.FMT_22s) {
            require(ops, 3, "22s", line);
            int a = reg(ops.get(0), program, line);
            int b = reg(ops.get(1), program, line);
            checkNibble(a, line);
            checkNibble(b, line);
            int value = (int) parseLiteral(ops.get(2), line);
            if (value < -32768 || value > 32767) {
                throw new SmaliError(line, "22s 立即数范围 ±32768：" + ops.get(2));
            }
            sink.add(opcode | (a << 8) | (b << 12));
            sink.add(value & 0xFFFF);
        } else if (format == Dex.FMT_22c) {
            require(ops, 3, "22c", line);
            int a = reg(ops.get(0), program, line);
            int b = reg(ops.get(1), program, line);
            checkNibble(a, line);
            checkNibble(b, line);
            int index = resolveRef(refKind, ops.get(2), program.pool, line);
            if (index > 0xFFFF) {
                throw new SmaliError(line, "引用索引超出 16 位：" + ops.get(2));
            }
            sink.add(opcode | (a << 8) | (b << 12));
            sink.add(index);
        } else if (format == Dex.FMT_32x) {
            require(ops, 2, "32x", line);
            int a = reg(ops.get(0), program, line);
            int b = reg(ops.get(1), program, line);
            sink.add(opcode);
            sink.add(a);
            sink.add(b);
        } else if (format == Dex.FMT_31i) {
            require(ops, 2, "31i", line);
            int a = reg(ops.get(0), program, line);
            checkByte(a, line);
            long value = parseLiteral(ops.get(1), line);
            sink.add(opcode | (a << 8));
            sink.add((int) (value & 0xFFFF));
            sink.add((int) ((value >>> 16) & 0xFFFF));
        } else if (format == Dex.FMT_31t) {
            require(ops, 2, "31t", line);
            int a = reg(ops.get(0), program, line);
            checkByte(a, line);
            sink.add(opcode | (a << 8));
            sink.add(0);
            sink.add(0);
            fixups.add(new Fixup(address, format, ops.get(1).trim(), line));
        } else if (format == Dex.FMT_31c) {
            require(ops, 2, "31c", line);
            int a = reg(ops.get(0), program, line);
            checkByte(a, line);
            int index = resolveRef(refKind, ops.get(1), program.pool, line);
            sink.add(opcode | (a << 8));
            sink.add(index & 0xFFFF);
            sink.add((index >>> 16) & 0xFFFF);
        } else {
            encodeCall(program, sink, opcode, format, refKind, ops, line);
        }
    }

    /** 编码 {list} / range 形式的调用指令（35c/3rc/45cc/4rcc）与 51l。 */
    private static void encodeCall(Program program, ShortSink sink, int opcode, byte format,
                                   byte refKind, List<String> ops, int line) throws SmaliError {
        if (format == Dex.FMT_35c) {
            require(ops, 2, "35c", line);
            int[] registers = regList(ops, 0, program, line);
            int index = resolveRef(refKind, ops.get(1), program.pool, line);
            if (index > 0xFFFF) {
                throw new SmaliError(line, "引用索引超出 16 位：" + ops.get(1));
            }
            int g = registers.length > 4 ? registers[4] : 0;
            checkNibble(g, line);
            int unit2 = 0;
            for (int i = 0; i < registers.length && i < 4; i++) {
                checkNibble(registers[i], line);
                unit2 |= registers[i] << (i * 4);
            }
            sink.add(opcode | (g << 8) | (registers.length << 12));
            sink.add(index);
            sink.add(unit2);
        } else if (format == Dex.FMT_3rc) {
            require(ops, 2, "3rc", line);
            int[] registers = regRange(ops, 0, program, line);
            int index = resolveRef(refKind, ops.get(1), program.pool, line);
            if (index > 0xFFFF) {
                throw new SmaliError(line, "引用索引超出 16 位：" + ops.get(1));
            }
            if (registers.length > 255) {
                throw new SmaliError(line, "寄存器范围过大（最多 255）：" + ops.get(0));
            }
            sink.add(opcode | (registers.length << 8));
            sink.add(index);
            sink.add(registers[0]);
        } else if (format == Dex.FMT_45cc) {
            require(ops, 3, "45cc", line);
            int[] registers = regList(ops, 0, program, line);
            int index = resolveRef(Dex.REF_METHOD, ops.get(1), program.pool, line);
            int proto = resolveRef(Dex.REF_PROTO, ops.get(2), program.pool, line);
            if (index > 0xFFFF || proto > 0xFFFF) {
                throw new SmaliError(line, "引用索引超出 16 位：" + ops.get(1));
            }
            int g = registers.length > 4 ? registers[4] : 0;
            checkNibble(g, line);
            int unit2 = 0;
            for (int i = 0; i < registers.length && i < 4; i++) {
                checkNibble(registers[i], line);
                unit2 |= registers[i] << (i * 4);
            }
            sink.add(opcode | (g << 8) | (registers.length << 12));
            sink.add(index);
            sink.add(unit2);
            sink.add(proto);
        } else if (format == Dex.FMT_4rcc) {
            require(ops, 3, "4rcc", line);
            int[] registers = regRange(ops, 0, program, line);
            int index = resolveRef(Dex.REF_METHOD, ops.get(1), program.pool, line);
            int proto = resolveRef(Dex.REF_PROTO, ops.get(2), program.pool, line);
            if (index > 0xFFFF || proto > 0xFFFF) {
                throw new SmaliError(line, "引用索引超出 16 位：" + ops.get(1));
            }
            if (registers.length > 255) {
                throw new SmaliError(line, "寄存器范围过大（最多 255）：" + ops.get(0));
            }
            sink.add(opcode | (registers.length << 8));
            sink.add(index);
            sink.add(registers[0]);
            sink.add(proto);
        } else if (format == Dex.FMT_51l) {
            require(ops, 2, "51l", line);
            int a = reg(ops.get(0), program, line);
            checkByte(a, line);
            long value = parseLiteral(ops.get(1), line);
            sink.add(opcode | (a << 8));
            sink.add((int) (value & 0xFFFF));
            sink.add((int) ((value >>> 16) & 0xFFFF));
            sink.add((int) ((value >>> 32) & 0xFFFF));
            sink.add((int) ((value >>> 48) & 0xFFFF));
        } else {
            throw new SmaliError(line, "暂不支持该指令格式：" + format);
        }
    }
}
