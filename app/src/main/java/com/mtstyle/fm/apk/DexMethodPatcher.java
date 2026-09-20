package com.mtstyle.fm.apk;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Adler32;

/**
 * DEX 就地补丁：把编辑后的 Smali 方法重新编码写回 code_item 原位置（不改变文件大小与其它结构），
 * 随后重算 SHA-1 签名与 Adler-32 校验和。
 *
 * <p>前提：新代码（含 tries/handlers）不超过原 code_item 占用的字节数；含 switch/array-data 数据
 * 的方法只能做等长编辑（payload 按原地址复制）。</p>
 */
public final class DexMethodPatcher {

    private static final boolean DEBUG = false;

    /** 补丁失败。 */
    public static class PatchError extends Exception {
        public PatchError(String message) {
            super(message);
        }
    }

    private DexMethodPatcher() {
    }

    /**
     * 编译 + 就地补丁：返回补丁后的 dex 字节（与原数组同长度）。
     *
     * @param dexBytes  原 dex 字节（classes.dex 等）
     * @param dex       已解析的 Dex（必须来自同一字节数组）
     * @param method    目标方法
     * @param smaliText 编辑后的方法文本（含 .method ... .end method）
     */
    public static byte[] patchText(byte[] dexBytes, Dex dex, Dex.EncodedMethod method, String smaliText)
            throws SmaliAssembler.SmaliError, PatchError {
        return patchText(dexBytes, dex, method, smaliText, new SmaliAssembler.DexPool(dex));
    }

    /** 复用池的重载（批量编译时避免重复建池）。 */
    public static byte[] patchText(byte[] dexBytes, Dex dex, Dex.EncodedMethod method, String smaliText,
                                  SmaliAssembler.Pool pool)
            throws SmaliAssembler.SmaliError, PatchError {
        SmaliAssembler.Program program = SmaliAssembler.assemble(dex, method, smaliText, pool);
        byte[] result = Arrays.copyOf(dexBytes, dexBytes.length);
        patch(result, dex, method, program);
        return result;
    }

    /** 就地写入汇编结果。 */
    public static boolean patch(byte[] dexBytes, Dex dex, Dex.EncodedMethod method,
                                SmaliAssembler.Program program) throws PatchError {
        Dex.CodeItem original = dex.code(method);
        if (original == null) {
            throw new PatchError("该方法没有代码，无法修改");
        }
        int codeOff = method.codeOff;
        if (codeOff <= 0 || codeOff + 16 > dexBytes.length) {
            throw new PatchError("code_item 偏移无效：" + codeOff);
        }
        int originalSize = dex.codeItemSize(original);
        int insnsSize = program.insns.length;
        boolean hasTries = program.tryStarts.length > 0;
        int pad = hasTries && (insnsSize & 1) != 0 ? 2 : 0;
        int triesBytes = program.tryStarts.length * 8;
        byte[] handlers = encodeHandlers(program);
        int total = 16 + insnsSize * 2 + pad + triesBytes + handlers.length;
        if (total > originalSize) {
            throw new PatchError("代码过长：新代码需要 " + total + " 字节，原位置只有 " + originalSize
                    + " 字节（请精简指令后再试）");
        }
        // 数据区（switch/array-data）只能等长编辑：按原地址复制原字节
        for (SmaliAssembler.Payload payload : program.payloads) {
            if (payload.address != payload.originalAddress) {
                throw new PatchError("该方法含 switch/array-data 数据，只能修改指令但不改变代码长度"
                        + "（数据位于 " + payload.originalAddress + "，现在到了 " + payload.address + "）");
            }
            if (payload.address + payload.units > program.insns.length
                    || payload.originalAddress + payload.units > original.insns.length) {
                throw new PatchError("数据区越界");
            }
            System.arraycopy(original.insns, payload.originalAddress, program.insns,
                    payload.address, payload.units);
        }
        int outsSize = Math.max(original.outsSize, requiredOuts(dex, program));
        byte[] region = new byte[total];
        writeU16(region, 0, program.registersSize);
        writeU16(region, 2, program.insSize);
        writeU16(region, 4, outsSize);
        writeU16(region, 6, program.tryStarts.length);
        writeU32(region, 8, readU32(dexBytes, codeOff + 8));
        writeU32(region, 12, insnsSize);
        int position = 16;
        for (int i = 0; i < insnsSize; i++) {
            writeU16(region, position + i * 2, program.insns[i] & 0xFFFF);
        }
        position += insnsSize * 2 + pad;
        int handlersStart = position + triesBytes;
        // handlers 区开头是 uleb 形式的「去重后处理组个数」；组偏移相对 handlersStart（含计数本身）
        List<List<SmaliAssembler.Handler>> groups = uniqueGroups(program);
        int[] groupIndex = new int[program.handlers.size()];
        for (int i = 0; i < program.handlers.size(); i++) {
            for (int j = 0; j < groups.size(); j++) {
                if (sameGroup(groups.get(j), program.handlers.get(i))) {
                    groupIndex[i] = j;
                    break;
                }
            }
        }
        int[] groupOffsets = new int[groups.size()];
        int cursor = ulebSize(groups.size());
        for (int j = 0; j < groups.size(); j++) {
            groupOffsets[j] = cursor;
            cursor += handlerGroupSize(groups.get(j));
        }
        for (int i = 0; i < program.tryStarts.length; i++) {
            writeU32(region, position + i * 8, program.tryStarts[i]);
            writeU16(region, position + i * 8 + 4, program.tryInsnCounts[i]);
            writeU16(region, position + i * 8 + 6, groupOffsets[groupIndex[i]]);
        }
        if (!groups.isEmpty()) {
            writeUleb(region, handlersStart, groups.size());
            for (int j = 0; j < groups.size(); j++) {
                writeHandlerGroup(region, handlersStart + groupOffsets[j], groups.get(j));
            }
        }
        boolean changed = false;
        for (int i = 0; i < total; i++) {
            if (region[i] != dexBytes[codeOff + i]) {
                if (DEBUG) {
                    System.err.println("DEBUG diff@" + i + " total=" + total + " new=" + (region[i] & 0xFF)
                            + " old=" + (dexBytes[codeOff + i] & 0xFF) + " insns=" + insnsSize
                            + " pad=" + pad + " tries=" + program.tryStarts.length);
                }
                changed = true;
                break;
            }
        }
        if (!changed) {
            // 内容与原方法完全一致：不写回，保留原字节（含行号调试信息）
            return false;
        }
        // 代码已改：清除该方法的行号调试信息（debug_info_off = 0），避免地址错位
        writeU32(region, 8, 0);
        System.arraycopy(region, 0, dexBytes, codeOff, total);
        refreshDigests(dexBytes);
        return true;
    }

    private static int readU32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    /** handlers 组的字节数。 */
    private static int handlerGroupSize(List<SmaliAssembler.Handler> handlers) {
        int typed = 0;
        boolean catchAll = false;
        for (SmaliAssembler.Handler handler : handlers) {
            if (handler.typeIdx < 0) {
                catchAll = true;
            } else {
                typed++;
            }
        }
        // 计数为 sleb：有 catch-all 时写 -(typed)，长度必须按有符号算
        int size = slebSize(catchAll ? -typed : typed);
        for (SmaliAssembler.Handler handler : handlers) {
            if (handler.typeIdx >= 0) {
                size += ulebSize(handler.typeIdx);
            }
            size += ulebSize(handler.address);
        }
        return size;
    }

    /** 写入 handlers 组，返回写入字节数。 */
    private static int writeHandlerGroup(byte[] out, int offset,
                                         List<SmaliAssembler.Handler> handlers) {
        int typed = 0;
        boolean catchAll = false;
        for (SmaliAssembler.Handler handler : handlers) {
            if (handler.typeIdx < 0) {
                catchAll = true;
            } else {
                typed++;
            }
        }
        int position = offset;
        position += writeSleb128(out, position, catchAll ? -typed : typed);
        for (SmaliAssembler.Handler handler : handlers) {
            if (handler.typeIdx >= 0) {
                position += writeUleb128(out, position, handler.typeIdx);
            }
            position += writeUleb128(out, position, handler.address);
        }
        return position - offset;
    }

    /** 编码全部 handlers（仅用于判断总长度）：uleb 组数 + 去重后的组。 */
    private static byte[] encodeHandlers(SmaliAssembler.Program program) {
        List<List<SmaliAssembler.Handler>> groups = uniqueGroups(program);
        if (groups.isEmpty()) {
            return new byte[0];
        }
        int size = ulebSize(groups.size());
        for (List<SmaliAssembler.Handler> group : groups) {
            size += handlerGroupSize(group);
        }
        byte[] out = new byte[size];
        int position = writeUleb(out, 0, groups.size());
        for (List<SmaliAssembler.Handler> group : groups) {
            position += writeHandlerGroup(out, position, group);
        }
        return out;
    }

    /** 按内容去重后的处理组列表（多个 try 可共享同一组）。 */
    private static List<List<SmaliAssembler.Handler>> uniqueGroups(SmaliAssembler.Program program) {
        List<List<SmaliAssembler.Handler>> unique = new ArrayList<>();
        for (List<SmaliAssembler.Handler> group : program.handlers) {
            boolean dup = false;
            for (List<SmaliAssembler.Handler> seen : unique) {
                if (sameGroup(seen, group)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                unique.add(group);
            }
        }
        return unique;
    }

    /** 两个处理组是否等价（类型与地址序列一致）。 */
    private static boolean sameGroup(List<SmaliAssembler.Handler> a, List<SmaliAssembler.Handler> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).typeIdx != b.get(i).typeIdx || a.get(i).address != b.get(i).address) {
                return false;
            }
        }
        return true;
    }

    /** 写 uleb128，返回写入字节数。 */
    private static int writeUleb(byte[] out, int offset, int value) {
        int position = offset;
        while (true) {
            int b = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                out[position++] = (byte) (b | 0x80);
            } else {
                out[position++] = (byte) b;
                break;
            }
        }
        return position - offset;
    }

    /** 方法需要的 outs_size（= 调用时参数寄存器占用的字数）。 */
    private static int requiredOuts(Dex dex, SmaliAssembler.Program program) {
        short[] insns = program.insns;
        byte[] formats = Dex.opFormats();
        int words = 0;
        int address = 0;
        int payloadIndex = 0;
        while (address < insns.length) {
            if (payloadIndex < program.payloads.size()
                    && program.payloads.get(payloadIndex).address == address) {
                address += program.payloads.get(payloadIndex).units;
                payloadIndex++;
                continue;
            }
            int unit = insns[address] & 0xFFFF;
            int opcode = unit & 0xFF;
            int count = -1;
            int methodIdx = -1;
            int protoIdx = -1;
            if (opcode >= 0x6e && opcode <= 0x72) {
                count = unit >> 12;
                methodIdx = insns[address + 1] & 0xFFFF;
            } else if (opcode >= 0x74 && opcode <= 0x78) {
                count = (unit >> 8) & 0xFF;
                methodIdx = insns[address + 1] & 0xFFFF;
            } else if (opcode == 0xfa || opcode == 0xfb) {
                count = opcode == 0xfa ? unit >> 12 : (unit >> 8) & 0xFF;
                methodIdx = insns[address + 1] & 0xFFFF;
                protoIdx = insns[address + 3] & 0xFFFF;
            }
            if (count > 0) {
                words = Math.max(words, callWords(dex, count, methodIdx, protoIdx));
            }
            address += widthOf(formats[opcode], insns, address);
        }
        return words;
    }

    /** 一次调用占用的参数空间：即寄存器列表字数（宽类型已占两个寄存器，无需再加）。 */
    private static int callWords(Dex dex, int count, int methodIdx, int protoIdx) {
        return count;
    }

    private static int widthOf(byte format, short[] insns, int address) {
        if (format == Dex.FMT_51l) {
            return 5;
        }
        if (format == Dex.FMT_45cc || format == Dex.FMT_4rcc) {
            return 4;
        }
        if (format == Dex.FMT_30t || format == Dex.FMT_32x || format == Dex.FMT_31i
                || format == Dex.FMT_31t || format == Dex.FMT_31c || format == Dex.FMT_35c
                || format == Dex.FMT_3rc) {
            return 3;
        }
        if (format == Dex.FMT_20t || format == Dex.FMT_22x || format == Dex.FMT_21t
                || format == Dex.FMT_21s || format == Dex.FMT_21h || format == Dex.FMT_21c
                || format == Dex.FMT_23x || format == Dex.FMT_22b || format == Dex.FMT_22t
                || format == Dex.FMT_22s || format == Dex.FMT_22c) {
            return 2;
        }
        return 1;
    }

    private static int ulebSize(int value) {
        return writeUleb128(new byte[5], 0, value);
    }

    private static int slebSize(int value) {
        return writeSleb128(new byte[5], 0, value);
    }

    private static int writeUleb128(byte[] out, int offset, int value) {
        int position = offset;
        int remaining = value;
        do {
            int current = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                current |= 0x80;
            }
            out[position++] = (byte) current;
        } while (remaining != 0);
        return position - offset;
    }

    private static int writeSleb128(byte[] out, int offset, int value) {
        int position = offset;
        int remaining = value;
        boolean more = true;
        while (more) {
            int current = remaining & 0x7F;
            remaining >>= 7;
            boolean sign = (current & 0x40) != 0;
            more = !((remaining == 0 && !sign) || (remaining == -1 && sign));
            if (more) {
                current |= 0x80;
            }
            out[position++] = (byte) current;
        }
        return position - offset;
    }

    private static void writeU16(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xFF);
        out[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private static void writeU32(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xFF);
        out[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        out[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        out[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    /** 重算 dex 的 SHA-1 签名（12..32 字节）与 Adler-32 校验和（8..12 字节）。 */
    public static void refreshDigests(byte[] dexBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(dexBytes, 32, dexBytes.length - 32);
            byte[] signature = digest.digest();
            System.arraycopy(signature, 0, dexBytes, 12, 20);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 不可用：" + e.getMessage());
        }
        Adler32 adler = new Adler32();
        adler.update(dexBytes, 12, dexBytes.length - 12);
        writeU32(dexBytes, 8, (int) adler.getValue());
    }

    /** 校验 dex 的校验和与签名是否正确。 */
    public static boolean digestsValid(byte[] dexBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(dexBytes, 32, dexBytes.length - 32);
            byte[] signature = digest.digest();
            for (int i = 0; i < 20; i++) {
                if (signature[i] != dexBytes[12 + i]) {
                    return false;
                }
            }
            Adler32 adler = new Adler32();
            adler.update(dexBytes, 12, dexBytes.length - 12);
            long expected = adler.getValue();
            long actual = (dexBytes[8] & 0xFFL) | ((dexBytes[9] & 0xFFL) << 8)
                    | ((dexBytes[10] & 0xFFL) << 16) | ((dexBytes[11] & 0xFFL) << 24);
            return expected == actual;
        } catch (Exception e) {
            return false;
        }
    }
}
