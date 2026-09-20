package com.mtstyle.fm.apk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DEX 解析 + Dalvik 字节码反汇编（纯 Java，无第三方依赖）。
 * 支持字符串池（MUTF-8）、类型池、原型池、字段池、方法池、类定义与 code_item，
 * 可输出 Smali 风格指令，用于 APK 分析页的「查看反编译代码」。
 */
public final class Dex {

    /** 字符常量（避免源码中出现转义字符）。 */
    private static final char QUOTE = 34;
    private static final char BACKSLASH = 92;
    private static final char NEWLINE = 10;
    private static final char TAB = 9;

    /** 字段引用。 */
    public static class FieldId {
        public int classIdx;
        public int typeIdx;
        public int nameIdx;
        public String cls = "";
        public String type = "";
        public String name = "";

        public String reference() {
            return cls + "->" + name + ":" + type;
        }
    }

    /** 方法原型。 */
    public static class Proto {
        public int returnTypeIdx;
        public int parametersOff;
        public String returnType = "";
        public final List<String> parameters = new ArrayList<>();

        public String descriptor() {
            StringBuilder builder = new StringBuilder(32);
            builder.append('(');
            for (String parameter : parameters) {
                builder.append(parameter);
            }
            builder.append(')').append(returnType);
            return builder.toString();
        }
    }

    /** 方法引用。 */
    public static class MethodId {
        public int classIdx;
        public int protoIdx;
        public int nameIdx;
        public String cls = "";
        public String name = "";
        public Proto proto;

        public String reference() {
            return cls + "->" + name + (proto == null ? "()V" : proto.descriptor());
        }
    }

    /** 类中字段（class_data_item 中的 encoded_field）。 */
    public static class EncodedField {
        public int fieldIdx;
        public int accessFlags;
        public String reference = "";
    }

    /** 类中方法（encoded_method）。 */
    public static class EncodedMethod {
        public int methodIdx;
        public int accessFlags;
        public int codeOff;
        public String reference = "";
        public CodeItem code;
    }

    /** code_item。 */
    public static class CodeItem {
        public int registersSize;
        public int insSize;
        public int outsSize;
        public int triesSize;
        public int insnsSize;
        public int handlersOff;
        public short[] insns = new short[0];
        public int[] tryStarts = new int[0];
        public int[] tryInsnCounts = new int[0];
        public int[] tryHandlerOffsets = new int[0];
    }

    /** 类定义。 */
    public static class ClassDef {
        public int classIdx;
        public int accessFlags;
        public int superclassIdx;
        public int sourceFileIdx;
        public String name = "";
        public String superName;
        public String sourceFile;
        public final List<String> interfaces = new ArrayList<>();
        public final List<EncodedField> staticFields = new ArrayList<>();
        public final List<EncodedField> instanceFields = new ArrayList<>();
        public final List<EncodedMethod> directMethods = new ArrayList<>();
        public final List<EncodedMethod> virtualMethods = new ArrayList<>();

        public boolean isInterface() {
            return (accessFlags & 0x0200) != 0;
        }

        /** 形如 com.mtstyle.fm.MainActivity。 */
        public String simpleName() {
            String value = name;
            if (value.startsWith("L") && value.endsWith(";")) {
                value = value.substring(1, value.length() - 1);
            }
            return value.replace('/', '.');
        }

        public List<EncodedMethod> allMethods() {
            List<EncodedMethod> all = new ArrayList<>(directMethods.size() + virtualMethods.size());
            all.addAll(directMethods);
            all.addAll(virtualMethods);
            return all;
        }
    }

    private final byte[] data;
    /** 反汇编时参数寄存器是否使用 p0/p1 别名（false 则统一用 vN）。 */
    public boolean paramAliases = true;
    public final List<String> strings = new ArrayList<>();
    public final List<String> types = new ArrayList<>();
    public final List<Proto> protos = new ArrayList<>();
    public final List<FieldId> fields = new ArrayList<>();
    public final List<MethodId> methods = new ArrayList<>();
    public final List<ClassDef> classes = new ArrayList<>();

    private Dex(byte[] data) {
        this.data = data;
    }

    /** 解析 dex 字节数组。 */
    public static Dex parse(byte[] data) throws IOException {
        if (!isDex(data)) {
            throw new IOException("不是有效的 dex 文件");
        }
        Dex dex = new Dex(data);
        dex.readPools();
        dex.readClasses();
        return dex;
    }

    /** 从文件解析。 */
    public static Dex parse(File file) throws IOException {
        return parse(Files.readAllBytes(file.toPath()));
    }

    /** 是否为 dex（magic 为 dex 加换行）。 */
    public static boolean isDex(byte[] bytes) {
        return bytes != null && bytes.length > 8 && bytes[0] == 'd' && bytes[1] == 'e'
                && bytes[2] == 'x' && bytes[3] == NEWLINE;
    }

    /** dex 版本号，如 035 / 039。 */
    public String version() {
        StringBuilder builder = new StringBuilder(3);
        for (int i = 4; i < 7; i++) {
            builder.append((char) (data[i] & 0xFF));
        }
        return builder.toString();
    }

    private void readPools() {
        int stringIdsSize = readU32(56);
        int stringIdsOff = readU32(60);
        for (int i = 0; i < stringIdsSize; i++) {
            strings.add(readStringData(readU32(stringIdsOff + i * 4)));
        }

        int typeIdsSize = readU32(64);
        int typeIdsOff = readU32(68);
        for (int i = 0; i < typeIdsSize; i++) {
            types.add(stringAt(readU32(typeIdsOff + i * 4)));
        }

        int protoIdsSize = readU32(72);
        int protoIdsOff = readU32(76);
        for (int i = 0; i < protoIdsSize; i++) {
            int base = protoIdsOff + i * 12;
            Proto proto = new Proto();
            proto.returnTypeIdx = readU32(base + 4);
            proto.parametersOff = readU32(base + 8);
            proto.returnType = typeAt(proto.returnTypeIdx);
            int size = proto.parametersOff == 0 ? 0 : readU32(proto.parametersOff);
            for (int p = 0; p < size; p++) {
                proto.parameters.add(typeAt(readU16(proto.parametersOff + 4 + p * 2)));
            }
            protos.add(proto);
        }

        int fieldIdsSize = readU32(80);
        int fieldIdsOff = readU32(84);
        for (int i = 0; i < fieldIdsSize; i++) {
            int base = fieldIdsOff + i * 8;
            FieldId field = new FieldId();
            field.classIdx = readU16(base);
            field.typeIdx = readU16(base + 2);
            field.nameIdx = readU32(base + 4);
            field.cls = typeAt(field.classIdx);
            field.type = typeAt(field.typeIdx);
            field.name = stringAt(field.nameIdx);
            fields.add(field);
        }

        int methodIdsSize = readU32(88);
        int methodIdsOff = readU32(92);
        for (int i = 0; i < methodIdsSize; i++) {
            int base = methodIdsOff + i * 8;
            MethodId method = new MethodId();
            method.classIdx = readU16(base);
            method.protoIdx = readU16(base + 2);
            method.nameIdx = readU32(base + 4);
            method.cls = typeAt(method.classIdx);
            method.name = stringAt(method.nameIdx);
            method.proto = method.protoIdx < protos.size() ? protos.get(method.protoIdx) : null;
            methods.add(method);
        }
    }

    private void readClasses() {
        int classDefsSize = readU32(96);
        int classDefsOff = readU32(100);
        for (int i = 0; i < classDefsSize; i++) {
            int base = classDefsOff + i * 32;
            ClassDef def = new ClassDef();
            def.classIdx = readU32(base);
            def.accessFlags = readU32(base + 4);
            def.superclassIdx = readU32(base + 8);
            def.sourceFileIdx = readU32(base + 16);
            def.name = typeAt(def.classIdx);
            if (def.superclassIdx != 0xFFFFFFFF) {
                def.superName = typeAt(def.superclassIdx);
            }
            if (def.sourceFileIdx != 0xFFFFFFFF) {
                def.sourceFile = stringAt(def.sourceFileIdx);
            }
            int interfacesOff = readU32(base + 12);
            if (interfacesOff != 0) {
                int size = readU32(interfacesOff);
                for (int k = 0; k < size; k++) {
                    def.interfaces.add(typeAt(readU16(interfacesOff + 4 + k * 2)));
                }
            }
            int classDataOff = readU32(base + 24);
            if (classDataOff != 0) {
                readClassData(def, classDataOff);
            }
            classes.add(def);
        }
    }

    private void readClassData(ClassDef def, int offset) {
        Cursor cursor = new Cursor(offset);
        int staticFieldsSize = cursor.readUleb128();
        int instanceFieldsSize = cursor.readUleb128();
        int directMethodsSize = cursor.readUleb128();
        int virtualMethodsSize = cursor.readUleb128();
        readEncodedFields(def.staticFields, cursor, staticFieldsSize);
        readEncodedFields(def.instanceFields, cursor, instanceFieldsSize);
        readEncodedMethods(def.directMethods, cursor, directMethodsSize);
        readEncodedMethods(def.virtualMethods, cursor, virtualMethodsSize);
    }

    private void readEncodedFields(List<EncodedField> target, Cursor cursor, int count) {
        int fieldIdx = 0;
        for (int i = 0; i < count; i++) {
            EncodedField field = new EncodedField();
            fieldIdx += cursor.readUleb128();
            field.accessFlags = cursor.readUleb128();
            field.fieldIdx = fieldIdx;
            field.reference = fieldIdx < fields.size() ? fields.get(fieldIdx).reference() : "?";
            target.add(field);
        }
    }

    private void readEncodedMethods(List<EncodedMethod> target, Cursor cursor, int count) {
        int methodIdx = 0;
        for (int i = 0; i < count; i++) {
            EncodedMethod method = new EncodedMethod();
            methodIdx += cursor.readUleb128();
            method.accessFlags = cursor.readUleb128();
            method.codeOff = cursor.readUleb128();
            method.methodIdx = methodIdx;
            method.reference = methodIdx < methods.size() ? methods.get(methodIdx).reference() : "?";
            target.add(method);
        }
    }

    /** 惰性解析方法代码（首次访问时才读取 insns）。 */
    public CodeItem code(EncodedMethod method) {
        if (method.code == null && method.codeOff != 0) {
            method.code = readCodeItem(method.codeOff);
        }
        return method.code;
    }

    private CodeItem readCodeItem(int offset) {
        CodeItem code = new CodeItem();
        code.registersSize = readU16(offset);
        code.insSize = readU16(offset + 2);
        code.outsSize = readU16(offset + 4);
        code.triesSize = readU16(offset + 6);
        code.insnsSize = readU32(offset + 12);
        code.insns = new short[code.insnsSize];
        for (int i = 0; i < code.insnsSize; i++) {
            code.insns[i] = (short) readU16(offset + 16 + i * 2);
        }
        if (code.triesSize > 0) {
            int triesOff = offset + 16 + code.insnsSize * 2;
            if ((code.insnsSize & 1) != 0) {
                triesOff += 2;
            }
            code.tryStarts = new int[code.triesSize];
            code.tryInsnCounts = new int[code.triesSize];
            code.tryHandlerOffsets = new int[code.triesSize];
            for (int i = 0; i < code.triesSize; i++) {
                code.tryStarts[i] = readU32(triesOff + i * 8);
                code.tryInsnCounts[i] = readU16(triesOff + i * 8 + 4);
                code.tryHandlerOffsets[i] = readU16(triesOff + i * 8 + 6);
            }
            code.handlersOff = triesOff + code.triesSize * 8;
        }
        return code;
    }

    // ---------------------------------------------------------------- 底层读取

    /** 带位置的 uleb128 游标。 */
    private class Cursor {
        int position;

        Cursor(int position) {
            this.position = position;
        }

        int readUleb128() {
            int result = 0;
            int shift = 0;
            int current;
            do {
                if (position >= data.length) {
                    return result;
                }
                current = data[position++] & 0xFF;
                result |= (current & 0x7F) << shift;
                shift += 7;
            } while ((current & 0x80) != 0);
            return result;
        }

        int readSleb128() {
            int result = 0;
            int shift = 0;
            int current = 0;
            do {
                if (position >= data.length) {
                    break;
                }
                current = data[position++] & 0xFF;
                result |= (current & 0x7F) << shift;
                shift += 7;
            } while ((current & 0x80) != 0);
            if (shift < 32 && (current & 0x40) != 0) {
                result |= -(1 << shift);
            }
            return result;
        }
    }

    private String stringAt(int index) {
        return index >= 0 && index < strings.size() ? strings.get(index) : "<" + index + ">";
    }

    private String typeAt(int index) {
        return index >= 0 && index < types.size() ? types.get(index) : "<type " + index + ">";
    }

    int readU16(int offset) {
        if (offset < 0 || offset + 2 > data.length) {
            return 0;
        }
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    int readU32(int offset) {
        if (offset < 0 || offset + 4 > data.length) {
            return 0;
        }
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    /** 读取 string_data_item（MUTF-8）。 */
    private String readStringData(int offset) {
        if (offset <= 0 || offset >= data.length) {
            return "";
        }
        Cursor cursor = new Cursor(offset);
        int utf16Size = cursor.readUleb128();
        StringBuilder builder = new StringBuilder(utf16Size > 0 ? utf16Size : 16);
        int position = cursor.position;
        while (position < data.length) {
            int first = data[position++] & 0xFF;
            if (first == 0) {
                break;
            }
            if ((first & 0x80) == 0) {
                builder.append((char) first);
            } else if ((first & 0xE0) == 0xC0) {
                if (position >= data.length) {
                    break;
                }
                int second = data[position++] & 0x3F;
                builder.append((char) (((first & 0x1F) << 6) | second));
            } else {
                if (position + 1 >= data.length) {
                    break;
                }
                int second = data[position++] & 0x3F;
                int third = data[position++] & 0x3F;
                builder.append((char) (((first & 0x0F) << 12) | (second << 6) | third));
            }
        }
        return builder.toString();
    }

    // ---------------------------------------------------------------- 指令表

    // 指令格式
    private static final byte F10x = 0;
    private static final byte F12x = 1;
    private static final byte F11n = 2;
    private static final byte F11x = 3;
    private static final byte F10t = 4;
    private static final byte F20t = 5;
    private static final byte F22x = 6;
    private static final byte F21t = 7;
    private static final byte F21s = 8;
    private static final byte F21h = 9;
    private static final byte F21c = 10;
    private static final byte F23x = 11;
    private static final byte F22b = 12;
    private static final byte F22t = 13;
    private static final byte F22s = 14;
    private static final byte F22c = 15;
    private static final byte F30t = 16;
    private static final byte F32x = 17;
    private static final byte F31i = 18;
    private static final byte F31t = 19;
    private static final byte F31c = 20;
    private static final byte F35c = 21;
    private static final byte F3rc = 22;
    private static final byte F51l = 23;
    private static final byte F45cc = 24;
    private static final byte F4rcc = 25;

    // 引用类型
    private static final byte R_NONE = 0;
    private static final byte R_STRING = 1;
    private static final byte R_TYPE = 2;
    private static final byte R_FIELD = 3;
    private static final byte R_METHOD = 4;
    private static final byte R_CALL_SITE = 5;
    private static final byte R_METHOD_HANDLE = 6;
    private static final byte R_PROTO = 7;

    private static final String[] OP_NAMES = new String[256];
    private static final byte[] OP_FORMATS = new byte[256];
    private static final byte[] OP_REFS = new byte[256];

    /** 操作码助记符表（索引=操作码值），供语法高亮与汇编复用。 */
    public static String[] opNames() {
        return OP_NAMES;
    }

    /** 操作码格式表（索引=操作码值）。 */
    public static byte[] opFormats() {
        return OP_FORMATS;
    }

    /** 操作码引用类型表（索引=操作码值，值为 REF_* 常量）。 */
    public static byte[] opRefs() {
        return OP_REFS;
    }

    /** 引用类型常量（汇编器据此判断操作数形态）。 */
    public static final byte REF_NONE = R_NONE;
    public static final byte REF_STRING = R_STRING;
    public static final byte REF_TYPE = R_TYPE;
    public static final byte REF_FIELD = R_FIELD;
    public static final byte REF_METHOD = R_METHOD;
    public static final byte REF_CALL_SITE = R_CALL_SITE;
    public static final byte REF_METHOD_HANDLE = R_METHOD_HANDLE;
    public static final byte REF_PROTO = R_PROTO;

    /** 指令格式常量（供汇编器使用）。 */
    public static final byte FMT_10x = F10x;
    public static final byte FMT_12x = F12x;
    public static final byte FMT_11n = F11n;
    public static final byte FMT_11x = F11x;
    public static final byte FMT_10t = F10t;
    public static final byte FMT_20t = F20t;
    public static final byte FMT_22x = F22x;
    public static final byte FMT_21t = F21t;
    public static final byte FMT_21s = F21s;
    public static final byte FMT_21h = F21h;
    public static final byte FMT_21c = F21c;
    public static final byte FMT_23x = F23x;
    public static final byte FMT_22b = F22b;
    public static final byte FMT_22t = F22t;
    public static final byte FMT_22s = F22s;
    public static final byte FMT_22c = F22c;
    public static final byte FMT_30t = F30t;
    public static final byte FMT_32x = F32x;
    public static final byte FMT_31i = F31i;
    public static final byte FMT_31t = F31t;
    public static final byte FMT_31c = F31c;
    public static final byte FMT_35c = F35c;
    public static final byte FMT_3rc = F3rc;
    public static final byte FMT_51l = F51l;
    public static final byte FMT_45cc = F45cc;
    public static final byte FMT_4rcc = F4rcc;

    /**
     * code_item 在文件中占用的字节数（含 2 字节 padding、tries 与 handlers），
     * 就地补丁据此判断新代码能否放进原位置。
     */
    public int codeItemSize(CodeItem code) {
        if (code == null) {
            return 0;
        }
        int total = 16 + code.insnsSize * 2;
        if (code.triesSize > 0) {
            if ((code.insnsSize & 1) != 0) {
                total += 2;
            }
            total += code.triesSize * 8;
            total += Math.max(0, handlersEnd(code) - code.handlersOff);
        }
        return total;
    }

    /** handlers 区域结束位置（绝对文件偏移），无 handlers 时返回 handlersOff。 */
    public int handlersEnd(CodeItem code) {
        if (code == null || code.triesSize == 0 || code.handlersOff == 0) {
            return code == null ? 0 : code.handlersOff;
        }
        int end = code.handlersOff;
        for (int i = 0; i < code.triesSize; i++) {
            Cursor cursor = new Cursor(code.handlersOff + code.tryHandlerOffsets[i]);
            int count = cursor.readSleb128();
            int typed = count > 0 ? count : -count;
            for (int k = 0; k < typed; k++) {
                cursor.readUleb128();
                cursor.readUleb128();
            }
            if (count <= 0) {
                cursor.readUleb128();
            }
            if (cursor.position > end) {
                end = cursor.position;
            }
        }
        return end;
    }

    /** 方法原型中参数占用的寄存器字数（供 outs_size 计算）。 */
    public int parameterWords(Proto proto) {
        int words = 0;
        if (proto != null) {
            for (String parameter : proto.parameters) {
                words += isWideType(parameter) ? 2 : 1;
            }
        }
        return words;
    }

    /** 是否为 long/double（占两个寄存器）。 */
    public static boolean isWideType(String descriptor) {
        if (descriptor == null || descriptor.length() == 0) {
            return false;
        }
        return descriptor.charAt(0) == 'J' || descriptor.charAt(0) == 'D';
    }

    private static void op(int code, String name, byte format, byte ref) {
        OP_NAMES[code] = name;
        OP_FORMATS[code] = format;
        OP_REFS[code] = ref;
    }

    static {
        op(0x00, "nop", F10x, R_NONE);
        op(0x01, "move", F12x, R_NONE);
        op(0x02, "move/from16", F22x, R_NONE);
        op(0x03, "move/16", F32x, R_NONE);
        op(0x04, "move-wide", F12x, R_NONE);
        op(0x05, "move-wide/from16", F22x, R_NONE);
        op(0x06, "move-wide/16", F32x, R_NONE);
        op(0x07, "move-object", F12x, R_NONE);
        op(0x08, "move-object/from16", F22x, R_NONE);
        op(0x09, "move-object/16", F32x, R_NONE);
        op(0x0a, "move-result", F11x, R_NONE);
        op(0x0b, "move-result-wide", F11x, R_NONE);
        op(0x0c, "move-result-object", F11x, R_NONE);
        op(0x0d, "move-exception", F11x, R_NONE);
        op(0x0e, "return-void", F10x, R_NONE);
        op(0x0f, "return", F11x, R_NONE);
        op(0x10, "return-wide", F11x, R_NONE);
        op(0x11, "return-object", F11x, R_NONE);
        op(0x12, "const/4", F11n, R_NONE);
        op(0x13, "const/16", F21s, R_NONE);
        op(0x14, "const", F31i, R_NONE);
        op(0x15, "const/high16", F21h, R_NONE);
        op(0x16, "const-wide/16", F21s, R_NONE);
        op(0x17, "const-wide/32", F31i, R_NONE);
        op(0x18, "const-wide", F51l, R_NONE);
        op(0x19, "const-wide/high16", F21h, R_NONE);
        op(0x1a, "const-string", F21c, R_STRING);
        op(0x1b, "const-string/jumbo", F31c, R_STRING);
        op(0x1c, "const-class", F21c, R_TYPE);
        op(0x1d, "monitor-enter", F11x, R_NONE);
        op(0x1e, "monitor-exit", F11x, R_NONE);
        op(0x1f, "check-cast", F21c, R_TYPE);
        op(0x20, "instance-of", F22c, R_TYPE);
        op(0x21, "array-length", F12x, R_NONE);
        op(0x22, "new-instance", F21c, R_TYPE);
        op(0x23, "new-array", F22c, R_TYPE);
        op(0x24, "filled-new-array", F35c, R_TYPE);
        op(0x25, "filled-new-array/range", F3rc, R_TYPE);
        op(0x26, "fill-array-data", F31t, R_NONE);
        op(0x27, "throw", F11x, R_NONE);
        op(0x28, "goto", F10t, R_NONE);
        op(0x29, "goto/16", F20t, R_NONE);
        op(0x2a, "goto/32", F30t, R_NONE);
        op(0x2b, "packed-switch", F31t, R_NONE);
        op(0x2c, "sparse-switch", F31t, R_NONE);
        op(0x2d, "cmpl-float", F23x, R_NONE);
        op(0x2e, "cmpg-float", F23x, R_NONE);
        op(0x2f, "cmpl-double", F23x, R_NONE);
        op(0x30, "cmpg-double", F23x, R_NONE);
        op(0x31, "cmp-long", F23x, R_NONE);
        op(0x32, "if-eq", F22t, R_NONE);
        op(0x33, "if-ne", F22t, R_NONE);
        op(0x34, "if-lt", F22t, R_NONE);
        op(0x35, "if-ge", F22t, R_NONE);
        op(0x36, "if-gt", F22t, R_NONE);
        op(0x37, "if-le", F22t, R_NONE);
        op(0x38, "if-eqz", F21t, R_NONE);
        op(0x39, "if-nez", F21t, R_NONE);
        op(0x3a, "if-ltz", F21t, R_NONE);
        op(0x3b, "if-gez", F21t, R_NONE);
        op(0x3c, "if-gtz", F21t, R_NONE);
        op(0x3d, "if-lez", F21t, R_NONE);
        op(0x44, "aget", F23x, R_NONE);
        op(0x45, "aget-wide", F23x, R_NONE);
        op(0x46, "aget-object", F23x, R_NONE);
        op(0x47, "aget-boolean", F23x, R_NONE);
        op(0x48, "aget-byte", F23x, R_NONE);
        op(0x49, "aget-char", F23x, R_NONE);
        op(0x4a, "aget-short", F23x, R_NONE);
        op(0x4b, "aput", F23x, R_NONE);
        op(0x4c, "aput-wide", F23x, R_NONE);
        op(0x4d, "aput-object", F23x, R_NONE);
        op(0x4e, "aput-boolean", F23x, R_NONE);
        op(0x4f, "aput-byte", F23x, R_NONE);
        op(0x50, "aput-char", F23x, R_NONE);
        op(0x51, "aput-short", F23x, R_NONE);
        op(0x52, "iget", F22c, R_FIELD);
        op(0x53, "iget-wide", F22c, R_FIELD);
        op(0x54, "iget-object", F22c, R_FIELD);
        op(0x55, "iget-boolean", F22c, R_FIELD);
        op(0x56, "iget-byte", F22c, R_FIELD);
        op(0x57, "iget-char", F22c, R_FIELD);
        op(0x58, "iget-short", F22c, R_FIELD);
        op(0x59, "iput", F22c, R_FIELD);
        op(0x5a, "iput-wide", F22c, R_FIELD);
        op(0x5b, "iput-object", F22c, R_FIELD);
        op(0x5c, "iput-boolean", F22c, R_FIELD);
        op(0x5d, "iput-byte", F22c, R_FIELD);
        op(0x5e, "iput-char", F22c, R_FIELD);
        op(0x5f, "iput-short", F22c, R_FIELD);
        op(0x60, "sget", F21c, R_FIELD);
        op(0x61, "sget-wide", F21c, R_FIELD);
        op(0x62, "sget-object", F21c, R_FIELD);
        op(0x63, "sget-boolean", F21c, R_FIELD);
        op(0x64, "sget-byte", F21c, R_FIELD);
        op(0x65, "sget-char", F21c, R_FIELD);
        op(0x66, "sget-short", F21c, R_FIELD);
        op(0x67, "sput", F21c, R_FIELD);
        op(0x68, "sput-wide", F21c, R_FIELD);
        op(0x69, "sput-object", F21c, R_FIELD);
        op(0x6a, "sput-boolean", F21c, R_FIELD);
        op(0x6b, "sput-byte", F21c, R_FIELD);
        op(0x6c, "sput-char", F21c, R_FIELD);
        op(0x6d, "sput-short", F21c, R_FIELD);
        op(0x6e, "invoke-virtual", F35c, R_METHOD);
        op(0x6f, "invoke-super", F35c, R_METHOD);
        op(0x70, "invoke-direct", F35c, R_METHOD);
        op(0x71, "invoke-static", F35c, R_METHOD);
        op(0x72, "invoke-interface", F35c, R_METHOD);
        op(0x74, "invoke-virtual/range", F3rc, R_METHOD);
        op(0x75, "invoke-super/range", F3rc, R_METHOD);
        op(0x76, "invoke-direct/range", F3rc, R_METHOD);
        op(0x77, "invoke-static/range", F3rc, R_METHOD);
        op(0x78, "invoke-interface/range", F3rc, R_METHOD);
        op(0x7b, "neg-int", F12x, R_NONE);
        op(0x7c, "not-int", F12x, R_NONE);
        op(0x7d, "neg-long", F12x, R_NONE);
        op(0x7e, "not-long", F12x, R_NONE);
        op(0x7f, "neg-float", F12x, R_NONE);
        op(0x80, "neg-double", F12x, R_NONE);
        op(0x81, "int-to-long", F12x, R_NONE);
        op(0x82, "int-to-float", F12x, R_NONE);
        op(0x83, "int-to-double", F12x, R_NONE);
        op(0x84, "long-to-int", F12x, R_NONE);
        op(0x85, "long-to-float", F12x, R_NONE);
        op(0x86, "long-to-double", F12x, R_NONE);
        op(0x87, "float-to-int", F12x, R_NONE);
        op(0x88, "float-to-long", F12x, R_NONE);
        op(0x89, "float-to-double", F12x, R_NONE);
        op(0x8a, "double-to-int", F12x, R_NONE);
        op(0x8b, "double-to-long", F12x, R_NONE);
        op(0x8c, "double-to-float", F12x, R_NONE);
        op(0x8d, "int-to-byte", F12x, R_NONE);
        op(0x8e, "int-to-char", F12x, R_NONE);
        op(0x8f, "int-to-short", F12x, R_NONE);
        op(0x90, "add-int", F23x, R_NONE);
        op(0x91, "sub-int", F23x, R_NONE);
        op(0x92, "mul-int", F23x, R_NONE);
        op(0x93, "div-int", F23x, R_NONE);
        op(0x94, "rem-int", F23x, R_NONE);
        op(0x95, "and-int", F23x, R_NONE);
        op(0x96, "or-int", F23x, R_NONE);
        op(0x97, "xor-int", F23x, R_NONE);
        op(0x98, "shl-int", F23x, R_NONE);
        op(0x99, "shr-int", F23x, R_NONE);
        op(0x9a, "ushr-int", F23x, R_NONE);
        op(0x9b, "add-long", F23x, R_NONE);
        op(0x9c, "sub-long", F23x, R_NONE);
        op(0x9d, "mul-long", F23x, R_NONE);
        op(0x9e, "div-long", F23x, R_NONE);
        op(0x9f, "rem-long", F23x, R_NONE);
        op(0xa0, "and-long", F23x, R_NONE);
        op(0xa1, "or-long", F23x, R_NONE);
        op(0xa2, "xor-long", F23x, R_NONE);
        op(0xa3, "shl-long", F23x, R_NONE);
        op(0xa4, "shr-long", F23x, R_NONE);
        op(0xa5, "ushr-long", F23x, R_NONE);
        op(0xa6, "add-float", F23x, R_NONE);
        op(0xa7, "sub-float", F23x, R_NONE);
        op(0xa8, "mul-float", F23x, R_NONE);
        op(0xa9, "div-float", F23x, R_NONE);
        op(0xaa, "rem-float", F23x, R_NONE);
        op(0xab, "add-double", F23x, R_NONE);
        op(0xac, "sub-double", F23x, R_NONE);
        op(0xad, "mul-double", F23x, R_NONE);
        op(0xae, "div-double", F23x, R_NONE);
        op(0xaf, "rem-double", F23x, R_NONE);
        op(0xb0, "add-int/2addr", F12x, R_NONE);
        op(0xb1, "sub-int/2addr", F12x, R_NONE);
        op(0xb2, "mul-int/2addr", F12x, R_NONE);
        op(0xb3, "div-int/2addr", F12x, R_NONE);
        op(0xb4, "rem-int/2addr", F12x, R_NONE);
        op(0xb5, "and-int/2addr", F12x, R_NONE);
        op(0xb6, "or-int/2addr", F12x, R_NONE);
        op(0xb7, "xor-int/2addr", F12x, R_NONE);
        op(0xb8, "shl-int/2addr", F12x, R_NONE);
        op(0xb9, "shr-int/2addr", F12x, R_NONE);
        op(0xba, "ushr-int/2addr", F12x, R_NONE);
        op(0xbb, "add-long/2addr", F12x, R_NONE);
        op(0xbc, "sub-long/2addr", F12x, R_NONE);
        op(0xbd, "mul-long/2addr", F12x, R_NONE);
        op(0xbe, "div-long/2addr", F12x, R_NONE);
        op(0xbf, "rem-long/2addr", F12x, R_NONE);
        op(0xc0, "and-long/2addr", F12x, R_NONE);
        op(0xc1, "or-long/2addr", F12x, R_NONE);
        op(0xc2, "xor-long/2addr", F12x, R_NONE);
        op(0xc3, "shl-long/2addr", F12x, R_NONE);
        op(0xc4, "shr-long/2addr", F12x, R_NONE);
        op(0xc5, "ushr-long/2addr", F12x, R_NONE);
        op(0xc6, "add-float/2addr", F12x, R_NONE);
        op(0xc7, "sub-float/2addr", F12x, R_NONE);
        op(0xc8, "mul-float/2addr", F12x, R_NONE);
        op(0xc9, "div-float/2addr", F12x, R_NONE);
        op(0xca, "rem-float/2addr", F12x, R_NONE);
        op(0xcb, "add-double/2addr", F12x, R_NONE);
        op(0xcc, "sub-double/2addr", F12x, R_NONE);
        op(0xcd, "mul-double/2addr", F12x, R_NONE);
        op(0xce, "div-double/2addr", F12x, R_NONE);
        op(0xcf, "rem-double/2addr", F12x, R_NONE);
        op(0xd0, "add-int/lit16", F22s, R_NONE);
        op(0xd1, "rsub-int", F22s, R_NONE);
        op(0xd2, "mul-int/lit16", F22s, R_NONE);
        op(0xd3, "div-int/lit16", F22s, R_NONE);
        op(0xd4, "rem-int/lit16", F22s, R_NONE);
        op(0xd5, "and-int/lit16", F22s, R_NONE);
        op(0xd6, "or-int/lit16", F22s, R_NONE);
        op(0xd7, "xor-int/lit16", F22s, R_NONE);
        op(0xd8, "add-int/lit8", F22b, R_NONE);
        op(0xd9, "rsub-int/lit8", F22b, R_NONE);
        op(0xda, "mul-int/lit8", F22b, R_NONE);
        op(0xdb, "div-int/lit8", F22b, R_NONE);
        op(0xdc, "rem-int/lit8", F22b, R_NONE);
        op(0xdd, "and-int/lit8", F22b, R_NONE);
        op(0xde, "or-int/lit8", F22b, R_NONE);
        op(0xdf, "xor-int/lit8", F22b, R_NONE);
        op(0xe0, "shl-int/lit8", F22b, R_NONE);
        op(0xe1, "shr-int/lit8", F22b, R_NONE);
        op(0xe2, "ushr-int/lit8", F22b, R_NONE);
        op(0xf0, "invoke-polymorphic", F45cc, R_METHOD);
        op(0xf1, "invoke-polymorphic/range", F4rcc, R_METHOD);
        op(0xf2, "invoke-custom", F35c, R_CALL_SITE);
        op(0xf3, "invoke-custom/range", F3rc, R_CALL_SITE);
        op(0xf4, "const-method-handle", F21c, R_METHOD_HANDLE);
        op(0xf5, "const-method-type", F21c, R_PROTO);
    }

    // ---------------------------------------------------------------- 反汇编

    /** 指令占用的 16 位码元数（含 payload）。 */
    private int instructionWidth(short[] insns, int address) {
        int unit = insns[address] & 0xFFFF;
        int opcode = unit & 0xFF;
        if (opcode == 0x00 && (unit >> 8) != 0) {
            return payloadWidth(insns, address);
        }
        switch (OP_FORMATS[opcode]) {
            case F51l:
                return 5;
            case F45cc:
            case F4rcc:
                return 4;
            case F30t:
            case F32x:
            case F31i:
            case F31t:
            case F31c:
            case F35c:
            case F3rc:
                return 3;
            case F20t:
            case F22x:
            case F21t:
            case F21s:
            case F21h:
            case F21c:
            case F23x:
            case F22b:
            case F22t:
            case F22s:
            case F22c:
                return 2;
            default:
                return 1;
        }
    }

    /** payload 指令长度（码元数），与 dexdump 的 units 一致。 */
    private int payloadWidth(short[] insns, int address) {
        int ident = (insns[address] & 0xFFFF) & 0xFF00;
        int size = valueAt(insns, address + 1);
        if (ident == 0x0100) {
            return 4 + size * 2;
        }
        if (ident == 0x0200) {
            return 2 + size * 4;
        }
        if (ident == 0x0300) {
            int elementWidth = valueAt(insns, address + 1);
            int elementCount = combined32(insns, address + 2);
            return 4 + (elementCount * elementWidth + 1) / 2;
        }
        return 1;
    }

    /** 跳转类指令的目标地址（绝对）。 */
    private int branchTarget(short[] insns, int address, int opcode) {
        switch (OP_FORMATS[opcode]) {
            case F10t:
                return address + (byte) ((insns[address] & 0xFFFF) >> 8);
            case F20t:
                return address + (short) valueAt(insns, address + 1);
            case F30t:
                return address + combined32(insns, address + 1);
            case F21t:
            case F22t:
                return address + (short) valueAt(insns, address + 1);
            case F31t:
                return address + combined32(insns, address + 1);
            default:
                return address;
        }
    }

    private int valueAt(short[] insns, int index) {
        return index >= 0 && index < insns.length ? insns[index] & 0xFFFF : 0;
    }

    /** 两个码元组成的 32 位值。 */
    private int combined32(short[] insns, int index) {
        return valueAt(insns, index) | (valueAt(insns, index + 1) << 16);
    }

    /** 寄存器名（可选用 p 别名）。 */
    private String reg(int index, int registersSize, int insSize, boolean paramAliases) {
        if (paramAliases && insSize > 0 && index >= registersSize - insSize) {
            return "p" + (index - (registersSize - insSize));
        }
        return "v" + index;
    }

    private static String hex(int value) {
        return Integer.toHexString(value);
    }

    private static String hex4(int value) {
        String text = Integer.toHexString(value & 0xFFFF);
        StringBuilder builder = new StringBuilder();
        for (int i = text.length(); i < 4; i++) {
            builder.append('0');
        }
        return builder.append(text).toString();
    }

    /** Smali 风格立即数（有符号十六进制）。 */
    private static String lit(int value) {
        if (value < 0) {
            return "-0x" + Integer.toHexString(-value);
        }
        return "0x" + Integer.toHexString(value);
    }

    /** Smali 风格 64 位立即数。 */
    private static String lit(long value) {
        if (value < 0) {
            return "-0x" + Long.toHexString(-value);
        }
        return "0x" + Long.toHexString(value);
    }

    private static String signed(int value) {
        return value >= 0 ? "+" + value : String.valueOf(value);
    }

    /** 按引用类型格式化操作数。 */
    private String refText(byte kind, int index) {
        switch (kind) {
            case R_STRING:
                return escapeString(stringAt(index));
            case R_TYPE:
                return typeAt(index);
            case R_FIELD:
                return index < fields.size() ? fields.get(index).reference() : "field@" + hex4(index);
            case R_METHOD:
                return index < methods.size() ? methods.get(index).reference() : "method@" + hex4(index);
            case R_PROTO:
                return index < protos.size() ? protos.get(index).descriptor() : "proto@" + hex4(index);
            case R_METHOD_HANDLE:
                return "method_handle@" + hex4(index);
            case R_CALL_SITE:
                return "call_site@" + hex4(index);
            default:
                return "";
        }
    }

    /** Smali 风格字符串字面量。 */
    public static String escapeString(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append(QUOTE);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == QUOTE || c == BACKSLASH) {
                builder.append(BACKSLASH).append(c);
            } else if (c == NEWLINE) {
                builder.append(BACKSLASH).append('n');
            } else if (c == 13) {
                builder.append(BACKSLASH).append('r');
            } else if (c == TAB) {
                builder.append(BACKSLASH).append('t');
            } else if (c < 32) {
                builder.append(BACKSLASH).append('u').append(hex4(c));
            } else {
                builder.append(c);
            }
        }
        return builder.append(QUOTE).toString();
    }

    /** 反汇编方法代码，返回 Smali 风格指令文本（每行一条指令）。 */
    public String disassembleCode(CodeItem code) {
        short[] insns = code.insns;
        int regs = code.registersSize;
        int insSize = code.insSize;
        StringBuilder builder = new StringBuilder(Math.max(256, insns.length * 24));
        List<Integer> labels = new ArrayList<>();
        int address = 0;
        while (address < insns.length) {
            int unit = insns[address] & 0xFFFF;
            int opcode = unit & 0xFF;
            if (opcode == 0x00 && (unit >> 8) != 0) {
                address += payloadWidth(insns, address);
                continue;
            }
            if (OP_FORMATS[opcode] == F10t || OP_FORMATS[opcode] == F20t || OP_FORMATS[opcode] == F30t
                    || OP_FORMATS[opcode] == F21t || OP_FORMATS[opcode] == F22t || OP_FORMATS[opcode] == F31t) {
                labels.add(branchTarget(insns, address, opcode));
            }
            address += instructionWidth(insns, address);
        }
        // catch 处理器地址也需要打印标签，否则文本里的 -> :label_xxxx 无法定位
        for (CatchEntry entry : catchEntries(code)) {
            if (!labels.contains(entry.handlerAddress)) {
                labels.add(entry.handlerAddress);
            }
        }

        address = 0;
        while (address < insns.length) {
            int unit = insns[address] & 0xFFFF;
            int opcode = unit & 0xFF;
            if (labels.contains(address)) {
                builder.append("    :label_").append(hex4(address)).append(NEWLINE);
            }
            for (int i = 0; i < code.triesSize; i++) {
                if (code.tryStarts[i] == address) {
                    builder.append("    :try_start_").append(i).append(NEWLINE);
                }
                if (code.tryStarts[i] + code.tryInsnCounts[i] == address) {
                    builder.append("    :try_end_").append(i).append(NEWLINE);
                }
            }
            if (opcode == 0x00 && (unit >> 8) != 0) {
                appendPayload(builder, insns, address);
                address += payloadWidth(insns, address);
                continue;
            }
            String mnemonic = OP_NAMES[opcode];
            if (mnemonic == null) {
                mnemonic = "unused";
            }
            builder.append("    ").append(mnemonic);
            String operands = decodeOperands(OP_FORMATS[opcode], OP_REFS[opcode], insns, address, regs, insSize);
            if (operands.length() > 0) {
                builder.append(' ').append(operands);
            }
            builder.append("    # ").append(hex4(address)).append(NEWLINE);
            address += instructionWidth(insns, address);
        }
        appendCatchBlocks(builder, code);
        return builder.toString();
    }

    /** payload 指令内容。 */
    private void appendPayload(StringBuilder builder, short[] insns, int address) {
        int unit = insns[address] & 0xFFFF;
        int ident = unit & 0xFF00;
        int size = valueAt(insns, address + 1);
        if (ident == 0x0100) {
            builder.append("    packed-switch-data (").append(payloadWidth(insns, address)).append(" units)");
            builder.append(" # ").append(hex4(address)).append(NEWLINE);
            int firstKey = combined32(insns, address + 2);
            for (int i = 0; i < size; i++) {
                builder.append("        0x").append(Integer.toHexString(firstKey + i))
                        .append(" -> :label_")
                        .append(hex4(address + combined32(insns, address + 4 + i * 2)))
                        .append(NEWLINE);
            }
        } else if (ident == 0x0200) {
            builder.append("    sparse-switch-data (").append(payloadWidth(insns, address)).append(" units)");
            builder.append(" # ").append(hex4(address)).append(NEWLINE);
            for (int i = 0; i < size; i++) {
                builder.append("        0x").append(Integer.toHexString(combined32(insns, address + 2 + i * 2)))
                        .append(" -> :label_")
                        .append(hex4(address + combined32(insns, address + 2 + size * 2 + i * 2)))
                        .append(NEWLINE);
            }
        } else if (ident == 0x0300) {
            int elementWidth = valueAt(insns, address + 1);
            int elementCount = combined32(insns, address + 2);
            builder.append("    fill-array-data (").append(payloadWidth(insns, address)).append(" units)");
            builder.append(" # ").append(hex4(address));
            builder.append(NEWLINE);
            builder.append("    # element_width=").append(elementWidth);
            builder.append(" size=").append(elementCount).append(NEWLINE);
        } else {
            builder.append("    unknown-payload # ").append(hex4(address)).append(NEWLINE);
        }
    }

    /** 解码指令操作数。 */
    private String decodeOperands(byte format, byte refKind, short[] insns, int address, int regs, int insSize) {
        int unit0 = insns[address] & 0xFFFF;
        int a = (unit0 >> 8) & 0xFF;
        int aNibble = (unit0 >> 8) & 0xF;
        int b = (unit0 >> 12) & 0xF;
        int unit1 = valueAt(insns, address + 1);
        int unit2 = valueAt(insns, address + 2);
        switch (format) {
            case F10x:
                return "";
            case F12x:
                return reg(aNibble, regs, insSize, paramAliases) + ", " + reg(b, regs, insSize, paramAliases);
            case F11n:
                return reg(aNibble, regs, insSize, paramAliases) + ", " + lit((byte) (b << 4) >> 4);
            case F11x:
                return reg(a, regs, insSize, paramAliases);
            case F10t:
            case F20t:
            case F30t:
                return ":label_" + hex4(branchTarget(insns, address, insns[address] & 0xFF));
            case F22x:
                return reg(a, regs, insSize, paramAliases) + ", " + reg(unit1, regs, insSize, paramAliases);
            case F21t:
                return reg(a, regs, insSize, paramAliases) + ", :label_"
                        + hex4(branchTarget(insns, address, insns[address] & 0xFF));
            case F21s:
                return reg(a, regs, insSize, paramAliases) + ", " + lit((short) unit1);
            case F21h:
                if ((insns[address] & 0xFF) == 0x19) {
                    return reg(a, regs, insSize, paramAliases) + ", " + lit((long) (short) unit1 << 48);
                }
                return reg(a, regs, insSize, paramAliases) + ", " + lit((short) unit1 << 16);
            case F21c:
                return reg(a, regs, insSize, paramAliases) + ", " + refText(refKind, unit1);
            case F23x:
                return reg(a, regs, insSize, paramAliases) + ", " + reg(unit1 & 0xFF, regs, insSize, paramAliases)
                        + ", " + reg(unit1 >> 8, regs, insSize, paramAliases);
            case F22b:
                return reg(a, regs, insSize, paramAliases) + ", " + reg(unit1 & 0xFF, regs, insSize, paramAliases)
                        + ", " + lit((byte) (unit1 >> 8));
            case F22t:
                return reg(aNibble, regs, insSize, paramAliases) + ", " + reg(b, regs, insSize, paramAliases) + ", :label_"
                        + hex4(branchTarget(insns, address, insns[address] & 0xFF));
            case F22s:
                return reg(aNibble, regs, insSize, paramAliases) + ", " + reg(b, regs, insSize, paramAliases)
                        + ", " + lit((short) unit1);
            case F22c:
                return reg(aNibble, regs, insSize, paramAliases) + ", " + reg(b, regs, insSize, paramAliases)
                        + ", " + refText(refKind, unit1);
            case F32x:
                return reg(unit1, regs, insSize, paramAliases) + ", " + reg(unit2, regs, insSize, paramAliases);
            case F31i:
                return reg(a, regs, insSize, paramAliases) + ", " + lit(combined32(insns, address + 1));
            case F31t:
                return reg(a, regs, insSize, paramAliases) + ", :label_"
                        + hex4(branchTarget(insns, address, insns[address] & 0xFF));
            case F31c:
                return reg(a, regs, insSize, paramAliases) + ", " + refText(refKind, combined32(insns, address + 1));
            case F35c:
                return "{" + regList5(unit2, b, aNibble, regs, insSize) + "}, "
                        + refText(refKind, unit1);
            case F3rc: {
                StringBuilder builder = new StringBuilder();
                builder.append('{').append(reg(unit2, regs, insSize, paramAliases));
                if (a > 1) {
                    builder.append(" .. ").append(reg(unit2 + a - 1, regs, insSize, paramAliases));
                }
                builder.append("}, ").append(refText(refKind, unit1));
                return builder.toString();
            }
            case F45cc:
                return "{" + regList5(unit2, b, aNibble, regs, insSize) + "}, " + refText(R_METHOD, unit1)
                        + ", " + refText(R_PROTO, valueAt(insns, address + 3));
            case F4rcc:
                return "{" + reg(unit2, regs, insSize, paramAliases)
                        + (a > 1 ? " .. " + reg(unit2 + a - 1, regs, insSize, paramAliases) : "") + "}, "
                        + refText(R_METHOD, unit1) + ", " + refText(R_PROTO, valueAt(insns, address + 3));
            case F51l: {
                long low = combined32(insns, address + 1) & 0xFFFFFFFFL;
                long high = combined32(insns, address + 3) & 0xFFFFFFFFL;
                return reg(a, regs, insSize, paramAliases) + ", " + lit((high << 32) | low);
            }
            default:
                return "";
        }
    }

    /** 35c / 45cc 格式的寄存器列表（C/D/E/F 在低码元，G 在高 4 位）。 */
    private String regList5(int registerUnit, int count, int g, int regs, int insSize) {
        StringBuilder builder = new StringBuilder();
        int[] registers = {registerUnit & 0xF, (registerUnit >> 4) & 0xF, (registerUnit >> 8) & 0xF,
                (registerUnit >> 12) & 0xF, g};
        for (int i = 0; i < count && i < registers.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(reg(registers[i], regs, insSize, paramAliases));
        }
        return builder.toString();
    }

    /** catch 处理器条目。 */
    public static class CatchEntry {
        public int tryIndex;
        public int start;
        public int count;
        /** 捕获类型描述符，catch-all 时为 null。 */
        public String type;
        public int handlerAddress;
    }

    /** 解析某方法的全部 catch 处理器。 */
    public List<CatchEntry> catchEntries(CodeItem code) {
        List<CatchEntry> entries = new ArrayList<>();
        if (code.triesSize == 0 || code.handlersOff == 0) {
            return entries;
        }
        for (int i = 0; i < code.triesSize; i++) {
            Cursor cursor = new Cursor(code.handlersOff + code.tryHandlerOffsets[i]);
            int handlerCount = cursor.readSleb128();
            int typedCount = handlerCount > 0 ? handlerCount : -handlerCount;
            for (int k = 0; k < typedCount; k++) {
                CatchEntry entry = new CatchEntry();
                entry.tryIndex = i;
                entry.start = code.tryStarts[i];
                entry.count = code.tryInsnCounts[i];
                entry.type = typeAt(cursor.readUleb128());
                entry.handlerAddress = cursor.readUleb128();
                entries.add(entry);
            }
            if (handlerCount <= 0) {
                CatchEntry entry = new CatchEntry();
                entry.tryIndex = i;
                entry.start = code.tryStarts[i];
                entry.count = code.tryInsnCounts[i];
                entry.type = null;
                entry.handlerAddress = cursor.readUleb128();
                entries.add(entry);
            }
        }
        return entries;
    }

    /** 输出 try/catch 处理器。 */
    private void appendCatchBlocks(StringBuilder builder, CodeItem code) {
        int typedIndex = 0;
        for (CatchEntry entry : catchEntries(code)) {
            builder.append("    ");
            if (entry.type == null) {
                builder.append(":catchall_").append(entry.tryIndex);
            } else {
                builder.append(":catch_").append(entry.tryIndex).append('_').append(typedIndex++);
            }
            builder.append(" {:try_start_").append(entry.tryIndex)
                    .append(" .. :try_end_").append(entry.tryIndex).append("} ")
                    .append(entry.type == null ? "" : entry.type + " ")
                    .append("-> :label_").append(hex4(entry.handlerAddress)).append(NEWLINE);
        }
    }

    /** Dex 访问修饰符（Smali 风格）。 */
    public static String modifiers(int flags, boolean isMethod) {
        StringBuilder builder = new StringBuilder(64);
        if ((flags & 0x1) != 0) {
            builder.append("public ");
        } else if ((flags & 0x2) != 0) {
            builder.append("private ");
        } else if ((flags & 0x4) != 0) {
            builder.append("protected ");
        }
        if ((flags & 0x8) != 0) {
            builder.append("static ");
        }
        if ((flags & 0x10) != 0) {
            builder.append("final ");
        }
        if (isMethod) {
            if ((flags & 0x20) != 0) {
                builder.append("synchronized ");
            }
            if ((flags & 0x40) != 0) {
                builder.append("bridge ");
            }
            if ((flags & 0x80) != 0) {
                builder.append("varargs ");
            }
            if ((flags & 0x100) != 0) {
                builder.append("native ");
            }
            if ((flags & 0x200) != 0) {
                builder.append("interface ");
            }
            if ((flags & 0x400) != 0) {
                builder.append("abstract ");
            }
            if ((flags & 0x800) != 0) {
                builder.append("strictfp ");
            }
            if ((flags & 0x1000) != 0) {
                builder.append("synthetic ");
            }
            if ((flags & 0x10000) != 0) {
                builder.append("constructor ");
            }
            if ((flags & 0x20000) != 0) {
                builder.append("declared-synchronized ");
            }
        } else {
            if ((flags & 0x40) != 0) {
                builder.append("volatile ");
            }
            if ((flags & 0x80) != 0) {
                builder.append("transient ");
            }
            if ((flags & 0x200) != 0) {
                builder.append("interface ");
            }
            if ((flags & 0x1000) != 0) {
                builder.append("synthetic ");
            }
            if ((flags & 0x2000) != 0) {
                builder.append("annotation ");
            }
            if ((flags & 0x4000) != 0) {
                builder.append("enum ");
            }
        }
        return builder.toString().trim();
    }

    /** 单个方法的 Smali 文本。 */
    public String smaliMethod(EncodedMethod method) {
        MethodId id = method.methodIdx >= 0 && method.methodIdx < methods.size()
                ? methods.get(method.methodIdx) : null;
        String name = id != null ? id.name : "?";
        String prototype = id != null && id.proto != null ? id.proto.descriptor() : "()V";
        StringBuilder builder = new StringBuilder(2048);
        builder.append(".method ").append(modifiers(method.accessFlags, true))
                .append(name).append(prototype).append(NEWLINE);
        CodeItem code = code(method);
        if (code != null) {
            builder.append("    .registers ").append(code.registersSize).append(NEWLINE);
            builder.append(disassembleCode(code));
        }
        builder.append(".end method").append(NEWLINE);
        return builder.toString();
    }

    /** 整个类的 Smali 文本。 */
    public String smali(ClassDef def) {
        StringBuilder builder = new StringBuilder(16384);
        builder.append(".class ").append(modifiers(def.accessFlags, false)).append(' ')
                .append(def.name).append(NEWLINE);
        if (def.superName != null) {
            builder.append(".super ").append(def.superName).append(NEWLINE);
        }
        if (def.sourceFile != null) {
            builder.append(".source ").append(escapeString(def.sourceFile)).append(NEWLINE);
        }
        for (String itf : def.interfaces) {
            builder.append(".implements ").append(itf).append(NEWLINE);
        }
        if (!def.staticFields.isEmpty()) {
            builder.append(NEWLINE).append("# static fields").append(NEWLINE);
            for (EncodedField field : def.staticFields) {
                builder.append(".field ").append(modifiers(field.accessFlags, false)).append(' ')
                        .append(field.reference).append(NEWLINE);
            }
        }
        if (!def.instanceFields.isEmpty()) {
            builder.append(NEWLINE).append("# instance fields").append(NEWLINE);
            for (EncodedField field : def.instanceFields) {
                builder.append(".field ").append(modifiers(field.accessFlags, false)).append(' ')
                        .append(field.reference).append(NEWLINE);
            }
        }
        for (EncodedMethod method : def.directMethods) {
            builder.append(NEWLINE).append(smaliMethod(method));
        }
        for (EncodedMethod method : def.virtualMethods) {
            builder.append(NEWLINE).append(smaliMethod(method));
        }
        return builder.toString();
    }

    /** 简要统计信息（UI 头部显示）。 */
    public String summary() {
        int methodCount = 0;
        int instructionCount = 0;
        for (ClassDef def : classes) {
            for (EncodedMethod method : def.allMethods()) {
                methodCount++;
                if (method.codeOff != 0) {
                    instructionCount += readU32(method.codeOff + 12);
                }
            }
        }
        return "dex " + version() + " | 类 " + classes.size() + " | 方法 " + methodCount
                + " | 字段 " + fields.size() + " | 字符串 " + strings.size()
                + " | 类型 " + types.size() + " | 代码单元 " + instructionCount;
    }
}
