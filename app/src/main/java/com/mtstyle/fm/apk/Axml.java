package com.mtstyle.fm.apk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Android 二进制 XML（AXML）解码 / 编码（纯 Java，无 Android 依赖）。
 * 解码结果可转成文本直接编辑，文本也能重新编码为合法 AXML。
 * 通过 {@code <?resmap ...?>} 处理指令携带属性资源 ID，保证编码后框架属性仍能被正确解析。
 */
public final class Axml {

    // ==================== 数据模型 ====================

    /** 一份文档。 */
    public static class Doc {
        public Node root;
        /** 属性名 → 资源 ID（编码时用于重建资源映射表）。 */
        public final Map<String, Integer> attrIds = new LinkedHashMap<>();
    }

    /** 元素节点。 */
    public static class Node {
        public String prefix;
        public String name;
        public final List<Attr> attrs = new ArrayList<>();
        public final List<Node> children = new ArrayList<>();
        /** 文本内容（CDATA），null 表示没有。 */
        public String text;
        /** 行号（仅用于生成更接近原文件的二进制，不参与显示）。 */
        public int lineNumber;

        public String fullName() {
            return prefix == null || prefix.isEmpty() ? name : prefix + ":" + name;
        }
    }

    /** 属性。 */
    public static class Attr {
        public String prefix;
        public String name;
        /** 渲染后的文本值（编码时按规则还原类型）。 */
        public String value;
        /** 原始值类型；-1 表示需要按文本推断（编辑后新建的属性）。 */
        public int valueType = -1;

        public String fullName() {
            return prefix == null || prefix.isEmpty() ? name : prefix + ":" + name;
        }
    }

    // ==================== 常量 ====================

    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_XML = 0x0003;
    private static final int CHUNK_XML_START_NS = 0x0100;
    private static final int CHUNK_XML_END_NS = 0x0101;
    private static final int CHUNK_XML_START_ELEMENT = 0x0102;
    private static final int CHUNK_XML_END_ELEMENT = 0x0103;
    private static final int CHUNK_XML_CDATA = 0x0104;
    private static final int CHUNK_XML_RESOURCE_MAP = 0x0180;

    private static final int TYPE_NULL = 0x00;
    private static final int TYPE_REFERENCE = 0x01;
    private static final int TYPE_ATTRIBUTE = 0x02;
    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_FLOAT = 0x04;
    private static final int TYPE_DIMENSION = 0x05;
    private static final int TYPE_FRACTION = 0x06;
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_INT_HEX = 0x11;
    private static final int TYPE_INT_BOOLEAN = 0x12;
    private static final int TYPE_INT_COLOR_ARGB8 = 0x1c;
    private static final int TYPE_INT_COLOR_RGB8 = 0x1d;
    private static final int TYPE_INT_COLOR_ARGB4 = 0x1e;
    private static final int TYPE_INT_COLOR_RGB4 = 0x1f;

    private static final int FLAG_UTF8 = 0x00000100;

    private static final String[] DIMENSION_UNITS = {"px", "dp", "sp", "pt", "in", "mm"};
    private static final String[] FRACTION_UNITS = {"%", "%p"};
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    // ==================== 判定 ====================

    /** 是否为二进制 XML（AXML）。 */
    public static boolean isBinaryXml(byte[] data) {
        return data != null && data.length >= 8
                && (data[0] & 0xFF) == 0x03 && (data[1] & 0xFF) == 0x00
                && (data[2] & 0xFF) == 0x08 && (data[3] & 0xFF) == 0x00;
    }

    // ==================== 解码 ====================

    public static Doc decode(byte[] data) throws IOException {
        Reader reader = new Reader(data);
        int header = reader.readInt();
        int headerSize = (header >>> 16) & 0xFFFF;
        reader.readInt();               // size
        reader.skip(headerSize - 8);

        Doc doc = new Doc();
        List<Node> stack = new ArrayList<>();
        StringPool pool = null;
        int[] resourceIds = null;
        Map<String, String> nsUriByPrefix = new LinkedHashMap<>();

        while (reader.remaining() >= 8) {
            int position = reader.position();
            int type = reader.readShort();
            int chunkHeaderSize = reader.readShort();
            int chunkSize = reader.readInt();
            if (chunkSize < 8 || position + chunkSize > data.length) {
                break;
            }
            int bodyStart = position + chunkHeaderSize;
            if (Boolean.getBoolean("axml.debug")) {
                System.out.println("[axml] chunk @" + position + " type=0x"
                        + Integer.toHexString(type) + " headerSize=" + chunkHeaderSize
                        + " size=" + chunkSize);
            }
            if (type == CHUNK_STRING_POOL) {
                pool = readStringPool(data, position, chunkHeaderSize);
            } else if (type == CHUNK_XML_RESOURCE_MAP) {
                int count = (chunkSize - chunkHeaderSize) / 4;
                resourceIds = new int[count];
                reader.seek(bodyStart);
                for (int i = 0; i < count; i++) {
                    resourceIds[i] = reader.readInt();
                }
            } else if (type == CHUNK_XML_START_NS) {
                reader.seek(bodyStart);
                int prefixIndex = reader.readInt();
                int uriIndex = reader.readInt();
                if (pool != null && prefixIndex >= 0 && uriIndex >= 0) {
                    nsUriByPrefix.put(pool.get(prefixIndex), pool.get(uriIndex));
                }
            } else if (type == CHUNK_XML_START_ELEMENT) {
                reader.seek(position + 8);
                int elementLine = reader.readInt();
                reader.seek(bodyStart);
                int nsIndex = reader.readInt();
                int nameIndex = reader.readInt();
                reader.readShort();             // attributeStart
                reader.readShort();             // attributeSize
                int attributeCount = reader.readShort();
                reader.readShort();             // idIndex
                reader.readShort();             // classIndex
                reader.readShort();             // styleIndex
                Node node = new Node();
                node.lineNumber = elementLine;
                String rawNs = pool == null || nsIndex < 0 ? null : pool.get(nsIndex);
                node.prefix = resolvePrefix(rawNs, nsUriByPrefix);
                node.name = pool == null ? "" : pool.get(nameIndex);
                for (int i = 0; i < attributeCount; i++) {
                    Attr attr = new Attr();
                    int attrNs = reader.readInt();
                    int attrName = reader.readInt();
                    int rawValue = reader.readInt();
                    reader.readShort();         // value size
                    reader.readByte();          // res0
                    int dataType = reader.readByte();
                    int valueData = reader.readInt();
                    String attrPrefix = pool == null || attrNs < 0 ? null : pool.get(attrNs);
                    attr.prefix = resolvePrefix(attrPrefix, nsUriByPrefix);
                    attr.name = pool == null ? "" : pool.get(attrName);
                    if ((attr.prefix == null || attr.prefix.isEmpty())
                            && attr.name.startsWith("xmlns")
                            && dataType == TYPE_STRING && pool != null) {
                        String uri = pool.get(valueData);
                        String declared = attr.name.contains(":")
                                ? attr.name.substring(attr.name.indexOf(':') + 1) : "";
                        nsUriByPrefix.put(declared, uri);
                    }
                    attr.value = formatValue(pool, dataType, valueData, rawValue);
                    attr.valueType = dataType;
                    node.attrs.add(attr);
                    if (resourceIds != null && attrName >= 0 && attrName < resourceIds.length
                            && resourceIds[attrName] != 0) {
                        doc.attrIds.put(attr.name, resourceIds[attrName]);
                    }
                }
                if (stack.isEmpty()) {
                    doc.root = node;
                } else {
                    stack.get(stack.size() - 1).children.add(node);
                }
                stack.add(node);
            } else if (type == CHUNK_XML_END_ELEMENT) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            } else if (type == CHUNK_XML_CDATA) {
                reader.seek(bodyStart);
                int dataIndex = reader.readInt();
                if (!stack.isEmpty() && pool != null) {
                    String text = pool.get(dataIndex);
                    Node parent = stack.get(stack.size() - 1);
                    parent.text = parent.text == null ? text : parent.text + text;
                }
            }
            reader.seek(position + chunkSize);
        }
        if (doc.root == null) {
            throw new IOException("不是有效的二进制 XML");
        }
        return doc;
    }

    private static String resolvePrefix(String raw, Map<String, String> nsUriByPrefix) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (nsUriByPrefix.containsKey(raw)) {
            return raw;
        }
        for (Map.Entry<String, String> entry : nsUriByPrefix.entrySet()) {
            if (entry.getValue().equals(raw)) {
                return entry.getKey();
            }
        }
        return raw;
    }

    /** 值 → 文本。 */
    private static String formatValue(StringPool pool, int dataType, int data, int rawValue) {
        switch (dataType) {
            case TYPE_NULL:
                return "@null";
            case TYPE_REFERENCE:
                return "@0x" + Integer.toHexString(data);
            case TYPE_ATTRIBUTE:
                return "?0x" + Integer.toHexString(data);
            case TYPE_STRING:
                if (pool == null) {
                    return "";
                }
                return rawValue >= 0 ? pool.get(rawValue) : pool.get(data);
            case TYPE_FLOAT:
                return Float.toString(Float.intBitsToFloat(data)) + "f";
            case TYPE_DIMENSION:
                return floatOf(data) + DIMENSION_UNITS[unitOf(data)];
            case TYPE_FRACTION:
                return floatOf(data) + FRACTION_UNITS[unitOf(data)];
            case TYPE_INT_DEC:
                return Integer.toString(data);
            case TYPE_INT_HEX:
                return "0x" + Integer.toHexString(data);
            case TYPE_INT_BOOLEAN:
                return data != 0 ? "true" : "false";
            case TYPE_INT_COLOR_ARGB8:
                return "#" + hex8(data);
            case TYPE_INT_COLOR_RGB8:
                return "#" + hex8(data).substring(2);
            case TYPE_INT_COLOR_ARGB4:
                return "#" + hex4(data);
            case TYPE_INT_COLOR_RGB4:
                return "#" + hex4(data).substring(1);
            default:
                return "0x" + Integer.toHexString(data);
        }
    }

    static String hex8(int value) {
        String hex = Integer.toHexString(value);
        while (hex.length() < 8) {
            hex = "0" + hex;
        }
        return hex;
    }

    static String hex4(int value) {
        String hex = hex8(value);
        return "" + hex.charAt(0) + hex.charAt(4) + hex.charAt(5) + hex.charAt(7);
    }

    private static final double[] RADIX_SCALE = {256.0, 32768.0, 8388608.0, 2147483648.0};

    static float floatOf(int data) {
        int mantissa = data >> 8;
        int radix = (data >> 4) & 0x3;
        return (float) (mantissa / RADIX_SCALE[radix]);
    }

    static int unitOf(int data) {
        return data & 0xF;
    }

    // ==================== 文本化 ====================

    public static String toText(Doc doc) {
        StringBuilder builder = new StringBuilder();
        builder.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        if (!doc.attrIds.isEmpty()) {
            builder.append("\n<?resmap");
            for (Map.Entry<String, Integer> entry : doc.attrIds.entrySet()) {
                builder.append(' ').append(entry.getKey()).append("=0x")
                        .append(Integer.toHexString(entry.getValue()));
            }
            builder.append(" ?>");
        }
        writeNode(doc.root, builder, 0);
        builder.append('\n');
        return builder.toString();
    }

    private static void writeNode(Node node, StringBuilder builder, int depth) {
        builder.append('\n');
        indent(builder, depth);
        builder.append('<').append(node.fullName());
        for (Attr attr : node.attrs) {
            builder.append(' ').append(attr.fullName()).append("=\"")
                    .append(escape(displayValue(attr))).append('"');
        }
        boolean empty = node.children.isEmpty() && node.text == null;
        if (empty) {
            builder.append("/>");
            return;
        }
        builder.append('>');
        if (node.text != null) {
            builder.append(escape(node.text));
        }
        for (Node child : node.children) {
            writeNode(child, builder, depth + 1);
        }
        if (!node.children.isEmpty()) {
            builder.append('\n');
            indent(builder, depth);
        }
        builder.append("</").append(node.fullName()).append('>');
    }

    private static void indent(StringBuilder builder, int depth) {
        for (int i = 0; i < depth; i++) {
            builder.append("    ");
        }
    }

    /** 文本显示值：字符串类型但文本形似数字/布尔等时补引号，保证可无损还原。 */
    static String displayValue(Attr attr) {
        String value = attr.value == null ? "" : attr.value;
        if (attr.valueType != TYPE_STRING) {
            return value;
        }
        boolean ambiguous = looksLikeNonString(value)
                || (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""));
        return ambiguous ? "\"" + value + "\"" : value;
    }

    static boolean looksLikeNonString(String value) {
        return tokenType(value) != TYPE_STRING;
    }

    /** 仅根据文本形态判断值类型（不需要字符串池）。 */
    private static int tokenType(String value) {
        if (value == null) {
            return TYPE_STRING;
        }
        if ("@null".equals(value)) {
            return TYPE_NULL;
        }
        if (isHexPrefixed(value, '@')) {
            return TYPE_REFERENCE;
        }
        if (isHexPrefixed(value, '?')) {
            return TYPE_ATTRIBUTE;
        }
        if ("true".equals(value) || "false".equals(value)) {
            return TYPE_INT_BOOLEAN;
        }
        if (isColor(value)) {
            return TYPE_INT_COLOR_ARGB8;
        }
        if (value.endsWith("%p") && isNumber(value.substring(0, value.length() - 2))) {
            return TYPE_FRACTION;
        }
        if (value.endsWith("%") && isNumber(value.substring(0, value.length() - 1))) {
            return TYPE_FRACTION;
        }
        if (dimensionValue(value) != null) {
            return TYPE_DIMENSION;
        }
        if (value.endsWith("f") && isNumber(value.substring(0, value.length() - 1))) {
            return TYPE_FLOAT;
        }
        if (isHexPrefixed(value, '0')) {
            return TYPE_INT_HEX;
        }
        if (isInteger(value)) {
            return TYPE_INT_DEC;
        }
        return TYPE_STRING;
    }

    private static boolean isHexPrefixed(String value, char prefix) {
        if (value == null || value.length() < 4 || value.charAt(0) != prefix) {
            return false;
        }
        int offset = prefix == '0' ? 2 : 1;
        if (prefix != '0' && (value.charAt(1) != '0' || (value.charAt(2) != 'x' && value.charAt(2) != 'X'))) {
            return false;
        }
        return isHex(value.substring(offset));
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value) + "\"";
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                case '&':
                    builder.append("&amp;");
                    break;
                case '<':
                    builder.append("&lt;");
                    break;
                case '>':
                    builder.append("&gt;");
                    break;
                default:
                    builder.append(c);
                    break;
            }
        }
        return builder.toString();
    }

    static String unescape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                if (next == 'n') {
                    builder.append('\n');
                } else if (next == 'r') {
                    builder.append('\r');
                } else if (next == 't') {
                    builder.append('\t');
                } else {
                    builder.append(next);
                }
            } else if (c == '&') {
                if (value.startsWith("&amp;", i)) {
                    builder.append('&');
                    i += 4;
                } else if (value.startsWith("&lt;", i)) {
                    builder.append('<');
                    i += 3;
                } else if (value.startsWith("&gt;", i)) {
                    builder.append('>');
                    i += 3;
                } else if (value.startsWith("&quot;", i)) {
                    builder.append('"');
                    i += 5;
                } else {
                    builder.append(c);
                }
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    // ==================== 文本 → 模型 ====================

    public static Doc fromText(String text) throws IOException {
        Doc doc = new Doc();
        Parser parser = new Parser(text, doc);
        doc.root = parser.parseDocument();
        return doc;
    }

    private static final class Parser {
        private final String text;
        private final Doc doc;
        private final int[] lineStarts;
        private int pos;

        Parser(String text, Doc doc) {
            this.text = text;
            this.doc = doc;
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }
            lineStarts = new int[starts.size()];
            for (int i = 0; i < starts.size(); i++) {
                lineStarts[i] = starts.get(i);
            }
        }

        /** 当前位置所在行号（从 1 开始）。 */
        private int lineOf(int index) {
            int low = 0;
            int high = lineStarts.length - 1;
            while (low < high) {
                int mid = (low + high + 1) / 2;
                if (lineStarts[mid] <= index) {
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            return low + 1;
        }

        Node parseDocument() throws IOException {
            readProlog();
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != '<') {
                throw new IOException("XML 内容为空");
            }
            return parseElement();
        }

        private void readProlog() throws IOException {
            while (true) {
                skipWhitespace();
                if (startsWith("<!--")) {
                    skipComment();
                    continue;
                }
                if (startsWith("<?")) {
                    int end = text.indexOf("?>", pos);
                    if (end < 0) {
                        throw new IOException("处理指令未闭合");
                    }
                    parseResmap(text.substring(pos + 2, end));
                    pos = end + 2;
                    continue;
                }
                break;
            }
        }

        private void parseResmap(String instruction) {
            if (!instruction.startsWith("resmap")) {
                return;
            }
            String rest = instruction.substring("resmap".length()).trim();
            if (rest.isEmpty()) {
                return;
            }
            for (String token : rest.split("\\s+")) {
                int eq = token.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = token.substring(0, eq);
                String value = token.substring(eq + 1).trim();
                try {
                    doc.attrIds.put(key, (int) Long.parseLong(
                            value.startsWith("0x") ? value.substring(2) : value, 16));
                } catch (Exception ignored) {
                }
            }
        }

        private Node parseElement() throws IOException {
            expect('<');
            Node node = new Node();
            node.lineNumber = lineOf(pos);
            String name = readName();
            int colon = name.indexOf(':');
            if (colon > 0) {
                node.prefix = name.substring(0, colon);
                node.name = name.substring(colon + 1);
            } else {
                node.name = name;
            }
            while (true) {
                skipWhitespace();
                if (pos >= text.length()) {
                    throw new IOException("元素未闭合：" + name);
                }
                char c = text.charAt(pos);
                if (c == '/') {
                    pos++;
                    expect('>');
                    return node;
                }
                if (c == '>') {
                    pos++;
                    break;
                }
                Attr attr = new Attr();
                String attrName = readName();
                int attrColon = attrName.indexOf(':');
                if (attrColon > 0) {
                    attr.prefix = attrName.substring(0, attrColon);
                    attr.name = attrName.substring(attrColon + 1);
                } else {
                    attr.name = attrName;
                }
                skipWhitespace();
                expect('=');
                skipWhitespace();
                attr.value = readQuoted();
                if (attr.value.length() >= 2 && attr.value.startsWith("\"")
                        && attr.value.endsWith("\"")) {
                    attr.value = attr.value.substring(1, attr.value.length() - 1);
                    attr.valueType = TYPE_STRING;
                } else {
                    attr.valueType = -1;
                }
                node.attrs.add(attr);
            }
            while (true) {
                if (pos >= text.length()) {
                    throw new IOException("元素未闭合：" + name);
                }
                if (startsWith("</")) {
                    pos += 2;
                    readName();
                    skipWhitespace();
                    expect('>');
                    break;
                }
                if (startsWith("<!--")) {
                    skipComment();
                    continue;
                }
                if (text.charAt(pos) == '<') {
                    node.children.add(parseElement());
                    continue;
                }
                int next = text.indexOf('<', pos);
                if (next < 0) {
                    next = text.length();
                }
                String raw = text.substring(pos, next);
                pos = next;
                if (raw.trim().isEmpty()) {
                    continue;
                }
                String value = unescape(raw);
                node.text = node.text == null ? value : node.text + value;
            }
            return node;
        }

        private String readName() {
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (Character.isWhitespace(c) || c == '=' || c == '/' || c == '>') {
                    break;
                }
                pos++;
            }
            return text.substring(start, pos);
        }

        private String readQuoted() throws IOException {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '\\' && pos + 1 < text.length()) {
                    builder.append(c).append(text.charAt(pos + 1));
                    pos += 2;
                    continue;
                }
                if (c == '"') {
                    pos++;
                    return unescape(builder.toString());
                }
                builder.append(c);
                pos++;
            }
            throw new IOException("属性值未闭合");
        }

        private void skipComment() throws IOException {
            int end = text.indexOf("-->", pos);
            if (end < 0) {
                throw new IOException("注释未闭合");
            }
            pos = end + 3;
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private boolean startsWith(String value) {
            return text.startsWith(value, pos);
        }

        private void expect(char expected) throws IOException {
            if (pos >= text.length() || text.charAt(pos) != expected) {
                throw new IOException("期望 '" + expected + "'（位置 " + pos + "）");
            }
            pos++;
        }
    }

    // ==================== 编码 ====================

    public static byte[] encode(Doc doc) throws IOException {
        if (doc == null || doc.root == null) {
            throw new IOException("没有可编码的 XML 内容");
        }
        StringPool pool = new StringPool();
        Map<String, Integer> attrNameIndex = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : doc.attrIds.entrySet()) {
            if (!attrNameIndex.containsKey(entry.getKey())) {
                attrNameIndex.put(entry.getKey(), pool.add(entry.getKey()));
            }
        }
        List<String> extraNames = new ArrayList<>();
        collectAttrNames(doc.root, extraNames);
        for (String name : extraNames) {
            if (!attrNameIndex.containsKey(name)) {
                attrNameIndex.put(name, pool.add(name));
            }
        }
        Map<String, String> nsUris = new LinkedHashMap<>();
        collectNsUris(doc.root, nsUris);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        encodeNode(doc.root, pool, nsUris, body, new ArrayList<>());
        byte[] poolBytes = pool.toBytes();
        byte[] mapBytes = attrNameIndex.isEmpty()
                ? new byte[0] : buildResourceMap(attrNameIndex, doc.attrIds);
        int total = 8 + poolBytes.length + mapBytes.length + body.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        writeShort(out, CHUNK_XML);
        writeShort(out, 8);
        writeInt(out, total);
        out.write(poolBytes, 0, poolBytes.length);
        out.write(mapBytes, 0, mapBytes.length);
        byte[] bodyBytes = body.toByteArray();
        out.write(bodyBytes, 0, bodyBytes.length);
        return out.toByteArray();
    }

    private static byte[] buildResourceMap(Map<String, Integer> attrNameIndex,
                                           Map<String, Integer> attrIds) {
        int count = attrNameIndex.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + count * 4);
        writeShort(out, CHUNK_XML_RESOURCE_MAP);
        writeShort(out, 8);
        writeInt(out, 8 + count * 4);
        for (String name : attrNameIndex.keySet()) {
            Integer id = attrIds.get(name);
            writeInt(out, id == null ? 0 : id);
        }
        return out.toByteArray();
    }

    private static void collectAttrNames(Node node, List<String> names) {
        for (Attr attr : node.attrs) {
            if (!isXmlns(attr) && !names.contains(attr.name)) {
                names.add(attr.name);
            }
        }
        for (Node child : node.children) {
            collectAttrNames(child, names);
        }
    }

    private static void collectNsUris(Node node, Map<String, String> nsUris) {
        for (Attr attr : node.attrs) {
            if (!isXmlns(attr)) {
                continue;
            }
            String uri = stripQuotes(attr.value);
            if (uri != null && !uri.isEmpty()) {
                nsUris.put(xmlnsPrefix(attr), uri);
            }
        }
        for (Node child : node.children) {
            collectNsUris(child, nsUris);
        }
    }

    static boolean isXmlns(Attr attr) {
        String name = attr.fullName();
        return "xmlns".equals(name) || name.startsWith("xmlns:");
    }

    static String xmlnsPrefix(Attr attr) {
        String name = attr.fullName();
        return "xmlns".equals(name) ? "" : name.substring("xmlns:".length());
    }

    static String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void encodeNode(Node node, StringPool pool, Map<String, String> nsUris,
                                   ByteArrayOutputStream out, List<String> declared) {
        List<String> needed = new ArrayList<>();
        if (node.prefix != null && !node.prefix.isEmpty() && !declared.contains(node.prefix)) {
            needed.add(node.prefix);
        }
        for (Attr attr : node.attrs) {
            if (isXmlns(attr)) {
                continue;
            }
            if (attr.prefix != null && !attr.prefix.isEmpty()
                    && !declared.contains(attr.prefix) && !needed.contains(attr.prefix)) {
                needed.add(attr.prefix);
            }
        }
        for (String prefix : needed) {
            writeShort(out, CHUNK_XML_START_NS);
            writeShort(out, 16);
            writeInt(out, 24);
            writeInt(out, 0);
            writeInt(out, -1);
            writeInt(out, pool.add(prefix));
            writeInt(out, pool.add(nsUriOf(prefix, nsUris)));
            declared.add(prefix);
        }

        List<Attr> written = new ArrayList<>();
        for (Attr attr : node.attrs) {
            if (!isXmlns(attr)) {
                written.add(attr);
            }
        }
        int attributeCount = written.size();
        ByteArrayOutputStream attrs = new ByteArrayOutputStream(attributeCount * 20);
        for (Attr attr : written) {
            String uri = attr.prefix == null || attr.prefix.isEmpty()
                    ? null : nsUriOf(attr.prefix, nsUris);
            writeInt(attrs, uri == null ? -1 : pool.add(uri));
            writeInt(attrs, pool.add(attr.name));
            int[] value = encodeValue(attr, pool);
            writeInt(attrs, value[0] == TYPE_STRING ? value[1] : -1);
            writeShort(attrs, 8);
            attrs.write(0);
            attrs.write(value[0]);
            writeInt(attrs, value[1]);
        }

        writeShort(out, CHUNK_XML_START_ELEMENT);
        writeShort(out, 16);
        writeInt(out, 36 + attributeCount * 20);
        writeInt(out, node.lineNumber);
        writeInt(out, -1);
        String elementNs = node.prefix == null || node.prefix.isEmpty()
                ? null : nsUriOf(node.prefix, nsUris);
        writeInt(out, elementNs == null ? -1 : pool.add(elementNs));
        writeInt(out, pool.add(node.name));
        writeShort(out, 20);
        writeShort(out, 20);
        writeShort(out, attributeCount);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        byte[] attrBytes = attrs.toByteArray();
        out.write(attrBytes, 0, attrBytes.length);

        if (node.text != null && !node.text.isEmpty()) {
            int index = pool.add(node.text);
            writeShort(out, CHUNK_XML_CDATA);
            writeShort(out, 16);
            writeInt(out, 28);
            writeInt(out, 0);
            writeInt(out, -1);
            writeInt(out, index);
            writeShort(out, 8);
            out.write(0);
            out.write(TYPE_STRING);
            writeInt(out, index);
        }
        for (Node child : node.children) {
            encodeNode(child, pool, nsUris, out, declared);
        }

        writeShort(out, CHUNK_XML_END_ELEMENT);
        writeShort(out, 16);
        writeInt(out, 24);
        writeInt(out, node.lineNumber);
        writeInt(out, -1);
        writeInt(out, -1);
        writeInt(out, pool.add(node.name));
        for (String prefix : needed) {
            writeShort(out, CHUNK_XML_END_NS);
            writeShort(out, 16);
            writeInt(out, 24);
            writeInt(out, 0);
            writeInt(out, -1);
            writeInt(out, pool.add(prefix));
            writeInt(out, pool.add(nsUriOf(prefix, nsUris)));
            declared.remove(prefix);
        }
    }

    private static String nsUriOf(String prefix, Map<String, String> nsUris) {
        String uri = nsUris.get(prefix);
        if (uri != null && !uri.isEmpty()) {
            return uri;
        }
        return "android".equals(prefix) ? ANDROID_NS : prefix;
    }

    // ==================== 值编码 ====================

    private static int[] encodeValue(Attr attr, StringPool pool) {
        String raw = attr.value == null ? "" : attr.value;
        if (attr.valueType == TYPE_STRING) {
            return new int[]{TYPE_STRING, pool.add(raw)};
        }
        String value = raw.trim();
        if ("@null".equals(value)) {
            return new int[]{TYPE_NULL, 0};
        }
        if (value.length() > 3 && value.charAt(0) == '@' && (value.charAt(1) == '0')
                && (value.charAt(2) == 'x' || value.charAt(2) == 'X') && isHex(value.substring(3))) {
            return new int[]{TYPE_REFERENCE, (int) parseLongHex(value.substring(3))};
        }
        if (value.length() > 3 && value.charAt(0) == '?' && (value.charAt(1) == '0')
                && (value.charAt(2) == 'x' || value.charAt(2) == 'X') && isHex(value.substring(3))) {
            return new int[]{TYPE_ATTRIBUTE, (int) parseLongHex(value.substring(3))};
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return new int[]{TYPE_STRING, pool.add(value.substring(1, value.length() - 1))};
        }
        if ("true".equals(value)) {
            return new int[]{TYPE_INT_BOOLEAN, 1};
        }
        if ("false".equals(value)) {
            return new int[]{TYPE_INT_BOOLEAN, 0};
        }
        if (isColor(value)) {
            return encodeColor(value);
        }
        if (value.endsWith("%p") && isNumber(value.substring(0, value.length() - 2))) {
            return new int[]{TYPE_FRACTION,
                    packComplex(parseFloat(value.substring(0, value.length() - 2)), 1)};
        }
        if (value.endsWith("%") && isNumber(value.substring(0, value.length() - 1))) {
            return new int[]{TYPE_FRACTION,
                    packComplex(parseFloat(value.substring(0, value.length() - 1)), 0)};
        }
        String dimension = dimensionValue(value);
        if (dimension != null) {
            return new int[]{TYPE_DIMENSION,
                    packComplex(parseFloat(dimension), dimensionUnit(value))};
        }
        if (value.endsWith("f") && isNumber(value.substring(0, value.length() - 1))) {
            return new int[]{TYPE_FLOAT,
                    Float.floatToIntBits(parseFloat(value.substring(0, value.length() - 1)))};
        }
        if (value.length() > 2 && value.startsWith("0") && (value.charAt(1) == 'x' || value.charAt(1) == 'X')
                && isHex(value.substring(2))) {
            return new int[]{TYPE_INT_HEX, (int) parseLongHex(value.substring(2))};
        }
        if (isInteger(value)) {
            return new int[]{TYPE_INT_DEC, (int) parseLong(value)};
        }
        return new int[]{TYPE_STRING, pool.add(stripQuotes(value))};
    }

    private static boolean isColor(String value) {
        if (value == null || value.length() < 4 || value.charAt(0) != '#') {
            return false;
        }
        int length = value.length() - 1;
        return (length == 3 || length == 4 || length == 6 || length == 8)
                && isHex(value.substring(1));
    }

    private static int[] encodeColor(String value) {
        String hex = value.substring(1);
        long parsed = parseLongHex(hex);
        if (hex.length() == 8) {
            return new int[]{TYPE_INT_COLOR_ARGB8, (int) parsed};
        }
        if (hex.length() == 6) {
            return new int[]{TYPE_INT_COLOR_RGB8, (int) parsed};
        }
        if (hex.length() == 4) {
            int a = (int) ((parsed >> 12) & 0xF);
            int r = (int) ((parsed >> 8) & 0xF);
            int g = (int) ((parsed >> 4) & 0xF);
            int b = (int) (parsed & 0xF);
            return new int[]{TYPE_INT_COLOR_ARGB4,
                    (a << 28) | (a << 24) | (r << 20) | (r << 16) | (g << 12) | (g << 8) | (b << 4) | b};
        }
        int r = (int) ((parsed >> 8) & 0xF);
        int g = (int) ((parsed >> 4) & 0xF);
        int b = (int) (parsed & 0xF);
        return new int[]{TYPE_INT_COLOR_RGB4, (r << 12) | (g << 8) | b};
    }

    private static int packComplex(float value, int unit) {
        for (int radix = 0; radix <= 3; radix++) {
            double scaled = value * RADIX_SCALE[radix];
            if (Math.abs(scaled - Math.round(scaled)) < 0.005 && Math.abs(scaled) < 8388607) {
                return (((int) Math.round(scaled)) << 8) | (radix << 4) | (unit & 0xF);
            }
        }
        double scaled = value * RADIX_SCALE[3];
        return (((int) Math.round(scaled)) << 8) | (3 << 4) | (unit & 0xF);
    }

    private static String dimensionValue(String value) {
        String lower = value.toLowerCase();
        String[] units = {"dip", "dp", "sp", "px", "pt", "in", "mm"};
        for (String unit : units) {
            if (lower.endsWith(unit)) {
                String prefix = value.substring(0, value.length() - unit.length());
                if (isNumber(prefix)) {
                    return prefix;
                }
            }
        }
        return null;
    }

    private static int dimensionUnit(String value) {
        String lower = value.toLowerCase();
        if (lower.endsWith("dip") || lower.endsWith("dp")) {
            return 1;
        }
        if (lower.endsWith("sp")) {
            return 2;
        }
        if (lower.endsWith("px")) {
            return 0;
        }
        if (lower.endsWith("pt")) {
            return 3;
        }
        if (lower.endsWith("in")) {
            return 4;
        }
        if (lower.endsWith("mm")) {
            return 5;
        }
        return 0;
    }

    private static boolean isHex(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNumber(String value) {
        return isInteger(value) || isFloat(value);
    }

    private static boolean isInteger(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int start = (value.charAt(0) == '-' || value.charAt(0) == '+') ? 1 : 0;
        if (start >= value.length()) {
            return false;
        }
        for (int i = start; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFloat(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        boolean digit = false;
        boolean dot = false;
        int start = (value.charAt(0) == '-' || value.charAt(0) == '+') ? 1 : 0;
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                digit = true;
            } else if (c == '.' && !dot) {
                dot = true;
            } else {
                return false;
            }
        }
        return digit;
    }

    private static float parseFloat(String value) {
        try {
            return Float.parseFloat(value);
        } catch (Exception e) {
            return 0f;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long parseLongHex(String value) {
        try {
            return Long.parseLong(value, 16);
        } catch (Exception e) {
            return 0L;
        }
    }

    // ==================== 字符串池 ====================

    static final class StringPool {
        private final List<String> strings = new ArrayList<>();
        private final Map<String, Integer> indexes = new LinkedHashMap<>();

        int add(String value) {
            String string = value == null ? "" : value;
            Integer existing = indexes.get(string);
            if (existing != null) {
                return existing;
            }
            int index = strings.size();
            strings.add(string);
            indexes.put(string, index);
            return index;
        }

        /** 按原始下标写入（解码时使用，不能去重，否则下标会错位）。 */
        void addRaw(String value) {
            String string = value == null ? "" : value;
            strings.add(string);
            if (!indexes.containsKey(string)) {
                indexes.put(string, strings.size() - 1);
            }
        }

        String get(int index) {
            return index >= 0 && index < strings.size() ? strings.get(index) : "";
        }

        int size() {
            return strings.size();
        }

        byte[] toBytes() {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            int count = strings.size();
            int[] offsets = new int[count];
            for (int i = 0; i < count; i++) {
                String string = strings.get(i);
                offsets[i] = data.size();
                byte[] utf8 = string.getBytes(StandardCharsets.UTF_8);
                writeVarint8(data, string.length());
                writeVarint8(data, utf8.length);
                data.write(utf8, 0, utf8.length);
                data.write(0);
            }
            while (data.size() % 4 != 0) {
                data.write(0);
            }
            int headerSize = 28;
            int stringsStart = headerSize + count * 4;
            byte[] dataBytes = data.toByteArray();
            int total = stringsStart + dataBytes.length;
            ByteArrayOutputStream out = new ByteArrayOutputStream(total);
            writeShort(out, CHUNK_STRING_POOL);
            writeShort(out, headerSize);
            writeInt(out, total);
            writeInt(out, count);
            writeInt(out, 0);
            writeInt(out, FLAG_UTF8);
            writeInt(out, stringsStart);
            writeInt(out, 0);
            for (int i = 0; i < count; i++) {
                writeInt(out, offsets[i]);
            }
            out.write(dataBytes, 0, dataBytes.length);
            return out.toByteArray();
        }
    }

    private static void writeVarint8(ByteArrayOutputStream out, int length) {
        if (length > 0x7F) {
            out.write(((length >> 8) & 0x7F) | 0x80);
        }
        out.write(length & 0xFF);
    }

    static StringPool readStringPool(byte[] data, int start, int headerSize) {
        StringPool pool = new StringPool();
        Reader reader = new Reader(data);
        reader.seek(start + 8);
        int count = reader.readInt();
        reader.readInt();               // styleCount
        int flags = reader.readInt();
        int stringsStart = reader.readInt();
        boolean utf8 = (flags & FLAG_UTF8) != 0;
        for (int i = 0; i < count; i++) {
            reader.seek(start + headerSize + i * 4);
            int offset = reader.readInt();
            int position = start + stringsStart + offset;
            if (position < 0 || position >= data.length) {
                pool.addRaw("");
                continue;
            }
            if (utf8) {
                int[] first = readVarint8(data, position);
                int[] second = readVarint8(data, position + first[1]);
                int dataStart = position + first[1] + second[1];
                int end = Math.min(dataStart + second[0], data.length);
                pool.addRaw(end > dataStart
                        ? new String(data, dataStart, end - dataStart, StandardCharsets.UTF_8) : "");
            } else {
                int[] first = readVarint16(data, position);
                int dataStart = position + first[1];
                int byteLength = Math.min(first[0] * 2, Math.max(0, data.length - dataStart));
                pool.addRaw(new String(data, dataStart, byteLength, StandardCharsets.UTF_16LE));
            }
        }
        return pool;
    }

    private static int[] readVarint8(byte[] data, int position) {
        int value = data[position] & 0xFF;
        if ((value & 0x80) != 0) {
            return new int[]{((value & 0x7F) << 8) | (data[position + 1] & 0xFF), 2};
        }
        return new int[]{value, 1};
    }

    private static int[] readVarint16(byte[] data, int position) {
        int value = (data[position] & 0xFF) | ((data[position + 1] & 0xFF) << 8);
        if ((value & 0x8000) != 0) {
            int rest = (data[position + 2] & 0xFF) | ((data[position + 3] & 0xFF) << 8);
            return new int[]{((value & 0x7FFF) << 16) | rest, 4};
        }
        return new int[]{value, 2};
    }

    // ==================== 字节辅助 ====================

    static final class Reader {
        private final byte[] data;
        private int position;

        Reader(byte[] data) {
            this.data = data;
        }

        int position() {
            return position;
        }

        int remaining() {
            return data.length - position;
        }

        void seek(int target) {
            position = target < 0 ? 0 : Math.min(target, data.length);
        }

        void skip(int count) {
            seek(position + count);
        }

        int readByte() {
            return position < data.length ? data[position++] & 0xFF : 0;
        }

        int readShort() {
            int a = readByte();
            int b = readByte();
            return (a | (b << 8)) & 0xFFFF;
        }

        int readInt() {
            int a = readByte();
            int b = readByte();
            int c = readByte();
            int d = readByte();
            return a | (b << 8) | (c << 16) | (d << 24);
        }
    }

    static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }
}
