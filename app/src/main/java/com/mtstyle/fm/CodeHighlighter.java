package com.mtstyle.fm;

import com.mtstyle.fm.apk.Dex;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 代码语法高亮分词器（纯 Java，无 Android 依赖，可独立单测）。
 *
 * <p>{@link #tokenize(String, boolean, int)} 返回扁平数组
 * {@code [start, end, type, ...]}（end 为开区间），UI 层据此添加
 * {@code ForegroundColorSpan}。实现为单遍扫描、无正则、无中间对象，
 * 便于在手机上处理几十万字符的文本。</p>
 */
public final class CodeHighlighter {

    /** Java 关键字 / Smali 伪指令（.method 等）。 */
    public static final int T_KEYWORD = 0;
    /** Smali 操作码（invoke-virtual、move-result 等）。 */
    public static final int T_OPCODE = 1;
    /** 字符串/字符字面量。 */
    public static final int T_STRING = 2;
    /** 注释。 */
    public static final int T_COMMENT = 3;
    /** 数字。 */
    public static final int T_NUMBER = 4;
    /** 类型/描述符。 */
    public static final int T_TYPE = 5;
    /** 成员名（-> 之后的方法/字段）。 */
    public static final int T_FUNC = 6;
    /** 标签（:label_x）与注解（@Override）。 */
    public static final int T_LABEL = 7;
    /** 寄存器（v0、p1）。 */
    public static final int T_REGISTER = 8;
    /** 调色板颜色个数（UI 层按此顺序提供颜色）。 */
    public static final int TYPE_COUNT = 9;
    /** 需上色的 token 数量上限，超出后剩余文本不再上色。 */
    public static final int MAX_TOKENS = 60000;

    private static final char QUOTE = 34;
    private static final char SINGLE_QUOTE = 39;
    private static final char HASH = 35;
    private static final char SLASH = 47;
    private static final char STAR = 42;
    private static final char DOT = 46;
    private static final char COLON = 58;
    private static final char AT = 64;
    private static final char LBRACKET = 91;
    private static final char NEWLINE = 10;
    private static final char HYPHEN = 45;
    private static final char GT = 62;
    private static final char SPACE = 32;
    private static final char TAB = 9;

    private static final int[] EMPTY = new int[0];

    private static final Set<String> JAVA_KEYWORDS = new HashSet<>(Arrays.asList(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "var", "record", "sealed", "yield", "true", "false", "null"));

    private static Set<String> opcodeCache;

    private CodeHighlighter() {
    }

    /**
     * 分词。
     *
     * @param text      待分词文本
     * @param java      true 按 Java 源码分词，false 按 Smali 分词
     * @param maxTokens 最多产出的 token 数
     * @return 扁平数组 [start, end, type, ...]
     */
    public static int[] tokenize(String text, boolean java, int maxTokens) {
        if (text == null || text.length() == 0) {
            return EMPTY;
        }
        Sink sink = new Sink(maxTokens > 0 ? maxTokens : 1);
        Set<String> ops = java ? null : opcodes();
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (sink.count >= sink.limit) {
                break;
            }
            char c = text.charAt(i);
            if (c == SPACE || c == TAB || c == NEWLINE || c == 13) {
                i++;
                continue;
            }
            if (c == QUOTE) {
                int end = stringEnd(text, i, QUOTE);
                sink.add(i, end, T_STRING);
                i = end;
                continue;
            }
            if (c == HASH && !java) {
                int end = lineEnd(text, i);
                sink.add(i, end, T_COMMENT);
                i = end;
                continue;
            }
            if (c == SLASH && java && i + 1 < n) {
                char next = text.charAt(i + 1);
                if (next == SLASH) {
                    int end = lineEnd(text, i);
                    sink.add(i, end, T_COMMENT);
                    i = end;
                    continue;
                }
                if (next == STAR) {
                    int end = blockEnd(text, i);
                    sink.add(i, end, T_COMMENT);
                    i = end;
                    continue;
                }
            }
            if (c == SINGLE_QUOTE && java) {
                int end = stringEnd(text, i, SINGLE_QUOTE);
                sink.add(i, end, T_STRING);
                i = end;
                continue;
            }
            if (c == AT && java) {
                int end = wordEnd(text, i + 1, true);
                if (end > i + 1) {
                    sink.add(i, end, T_LABEL);
                    i = end;
                    continue;
                }
                i++;
                continue;
            }
            if (c == COLON && !java) {
                int end = labelEnd(text, i);
                if (end > i + 1) {
                    sink.add(i, end, T_LABEL);
                    i = end;
                    continue;
                }
                i++;
                continue;
            }
            if (c == DOT && !java) {
                int end = wordEnd(text, i + 1, false);
                if (end > i + 1) {
                    sink.add(i, end, T_KEYWORD);
                    i = end;
                    continue;
                }
                i++;
                continue;
            }
            if (isDigit(c)) {
                int end = numberEnd(text, i);
                sink.add(i, end, T_NUMBER);
                i = end;
                continue;
            }
            if (isIdentStart(c) || c == LBRACKET) {
                int end = refEnd(text, i, java);
                if (end <= i) {
                    i++;
                    continue;
                }
                classify(text, i, end, java, ops, sink);
                i = end;
                continue;
            }
            i++;
        }
        return sink.result();
    }

    /** 按「引用串 / 寄存器 / 描述符 / 操作码 / 关键字 / 类型名」归类产出 token。 */
    private static void classify(String text, int start, int end, boolean java, Set<String> ops,
                                 Sink sink) {
        if (!java) {
            int arrow = -1;
            for (int i = start; i + 1 < end; i++) {
                if (text.charAt(i) == HYPHEN && text.charAt(i + 1) == GT) {
                    arrow = i;
                    break;
                }
            }
            if (arrow > start) {
                sink.add(start, arrow, T_TYPE);
                int memberStart = arrow + 2;
                int cut = -1;
                for (int i = memberStart; i < end; i++) {
                    char c = text.charAt(i);
                    if (c == 40 || c == COLON) {
                        cut = i;
                        break;
                    }
                }
                if (cut > memberStart) {
                    sink.add(memberStart, cut, T_FUNC);
                    sink.add(cut, end, T_TYPE);
                } else if (memberStart < end) {
                    sink.add(memberStart, end, T_FUNC);
                }
                return;
            }
            String word = text.substring(start, end);
            if (isRegister(word)) {
                sink.add(start, end, T_REGISTER);
            } else if (word.charAt(0) == 40) {
                // 方法原型 (Ljava/lang/String;)V
                sink.add(start, end, T_TYPE);
            } else if (isDescriptor(word)) {
                sink.add(start, end, T_TYPE);
            } else if (ops.contains(word)) {
                sink.add(start, end, T_OPCODE);
            }
            return;
        }

        String word = text.substring(start, end);
        if (JAVA_KEYWORDS.contains(word)) {
            sink.add(start, end, T_KEYWORD);
            return;
        }
        int dot = word.lastIndexOf(DOT);
        String tail = dot < 0 ? word : word.substring(dot + 1);
        if (isTypeName(tail)) {
            sink.add(start, end, T_TYPE);
        }
    }

    private static Set<String> opcodes() {
        if (opcodeCache == null) {
            Set<String> set = new HashSet<>(512);
            String[] names = Dex.opNames();
            for (int i = 0; i < names.length; i++) {
                if (names[i] != null) {
                    set.add(names[i]);
                }
            }
            // 反汇编里的 payload 伪指令
            set.add("packed-switch-data");
            set.add("sparse-switch-data");
            set.add("fill-array-data");
            opcodeCache = set;
        }
        return opcodeCache;
    }

    /** v0 / p12 形式的寄存器。 */
    private static boolean isRegister(String word) {
        if (word.length() < 2) {
            return false;
        }
        char first = word.charAt(0);
        if (first != 'v' && first != 'p') {
            return false;
        }
        for (int i = 1; i < word.length(); i++) {
            if (!isDigit(word.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Ljava/lang/String; 或 [B 形式的类型描述符。 */
    private static boolean isDescriptor(String word) {
        if (word.length() == 0) {
            return false;
        }
        if (word.charAt(0) == LBRACKET) {
            return true;
        }
        return word.length() > 2 && word.charAt(0) == 'L'
                && word.charAt(word.length() - 1) == 59;
    }

    /** 大驼峰命名（类名）；全大写常量与普通标识符不上色。 */
    private static boolean isTypeName(String word) {
        int len = word.length();
        if (len == 0) {
            return false;
        }
        char first = word.charAt(0);
        if (first < 'A' || first > 'Z') {
            return false;
        }
        if (len == 1) {
            return true;
        }
        char second = word.charAt(1);
        return second < 'A' || second > 'Z';
    }

    private static boolean isDigit(char c) {
        return c >= 48 && c <= 57;
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == 36;
    }

    private static boolean isIdentChar(char c) {
        return isIdentStart(c) || isDigit(c);
    }

    /** Java：字母数字 _$ 与点；Smali：额外允许 / ; ( ) [ ] < > : - 等描述符字符。 */
    private static boolean isRefChar(char c, boolean java) {
        if (isIdentChar(c)) {
            return true;
        }
        if (java) {
            return c == DOT;
        }
        return c == SLASH || c == 59 || c == 40 || c == 41 || c == LBRACKET || c == 93
                || c == 60 || c == GT || c == COLON || c == HYPHEN || c == 38 || c == 43
                || c == 61 || c == 124 || c == 94 || c == 126 || c == 33 || c == 42;
    }

    private static int refEnd(String text, int from, boolean java) {
        int i = from;
        int n = text.length();
        while (i < n && isRefChar(text.charAt(i), java)) {
            i++;
        }
        return i;
    }

    private static int wordEnd(String text, int from, boolean java) {
        int i = from;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (java ? isIdentChar(c) : (isIdentChar(c) || c == HYPHEN)) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static int labelEnd(String text, int from) {
        int i = from + 1;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (isIdentChar(c)) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static int lineEnd(String text, int from) {
        int i = text.indexOf(NEWLINE, from);
        return i < 0 ? text.length() : i;
    }

    private static int blockEnd(String text, int from) {
        int i = text.indexOf("*/", from + 2);
        return i < 0 ? text.length() : i + 2;
    }

    /** 字符串/字符字面量（正确处理反斜杠转义，未闭合时到行尾）。 */
    private static int stringEnd(String text, int from, char quote) {
        int i = from + 1;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == 92) {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            if (c == NEWLINE) {
                return i;
            }
            i++;
        }
        return n;
    }

    private static int numberEnd(String text, int from) {
        int i = from;
        int n = text.length();
        boolean hex = false;
        if (text.charAt(i) == 48 && i + 1 < n
                && (text.charAt(i + 1) == 'x' || text.charAt(i + 1) == 'X')) {
            hex = true;
            i += 2;
        }
        while (i < n) {
            char c = text.charAt(i);
            if (isDigit(c) || c == 46 || c == 95
                    || (hex && ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')))) {
                i++;
                continue;
            }
            break;
        }
        // 数值后缀 L/f/d（后面不能紧跟标识符字符）
        if (i < n) {
            char c = text.charAt(i);
            if (c == 'L' || c == 'l' || c == 'f' || c == 'F' || c == 'd' || c == 'D') {
                if (i + 1 >= n || !isIdentChar(text.charAt(i + 1))) {
                    i++;
                }
            }
        }
        return i;
    }

    /** 可增长的 token 收集器（避免每步新建数组）。 */
    private static final class Sink {
        int[] data;
        int cap;
        int count;
        final int limit;

        Sink(int limit) {
            this.limit = limit;
            this.cap = Math.min(limit, 2048);
            this.data = new int[cap * 3];
        }

        void add(int start, int end, int type) {
            if (count >= limit || end <= start) {
                return;
            }
            if (count == cap) {
                cap = Math.min(limit, cap * 2);
                data = Arrays.copyOf(data, cap * 3);
            }
            int at = count * 3;
            data[at] = start;
            data[at + 1] = end;
            data[at + 2] = type;
            count++;
        }

        int[] result() {
            return count * 3 == data.length ? data : Arrays.copyOf(data, count * 3);
        }
    }
}
