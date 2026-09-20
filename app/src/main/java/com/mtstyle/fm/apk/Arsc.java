package com.mtstyle.fm.apk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * resources.arsc 解析（纯 Java，无 Android 依赖）。
 * 解析出包 → 类型 → 条目（键名 + 值），用于「查看资源」界面。
 */
public final class Arsc {

    /** 单条资源。 */
    public static class Entry {
        public String packageName;
        public int typeId;
        public String typeName;
        public int entryId;
        public String key;
        public String value;
        public int valueType;
        public String config = "";

        /** 资源 ID，如 0x7f010001。 */
        public String resourceId() {
            return String.format("0x%02x%02x%04x",
                    packageId & 0xFF, typeId & 0xFF, entryId & 0xFFFF);
        }

        public int packageId;
    }

    /** 解析结果。 */
    public static class Table {
        public final List<Entry> entries = new ArrayList<>();
        public String packageName = "";
        public int packageId;
    }

    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_TABLE = 0x0002;
    private static final int CHUNK_PACKAGE = 0x0200;
    private static final int CHUNK_TYPE = 0x0201;

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

    private static final String[] DIMENSION_UNITS = {"px", "dp", "sp", "pt", "in", "mm"};
    private static final String[] FRACTION_UNITS = {"%", "%p"};

    private Arsc() {
    }

    public static Table parse(byte[] data) throws IOException {
        if (data == null || data.length < 12) {
            throw new IOException("resources.arsc 内容为空");
        }
        int headerType = readShort(data, 0);
        if (headerType != CHUNK_TABLE) {
            throw new IOException("不是有效的 resources.arsc");
        }
        int headerSize = readShort(data, 2);
        Table table = new Table();
        Axml.StringPool valueStrings = null;
        int position = headerSize;
        while (position + 8 <= data.length) {
            int type = readShort(data, position);
            int chunkHeaderSize = readShort(data, position + 2);
            int chunkSize = readInt(data, position + 4);
            if (chunkSize < 8 || position + chunkSize > data.length) {
                break;
            }
            if (type == CHUNK_STRING_POOL && valueStrings == null) {
                valueStrings = Axml.readStringPool(data, position, chunkHeaderSize);
            } else if (type == CHUNK_PACKAGE) {
                readPackage(data, position, chunkHeaderSize, chunkSize, valueStrings, table);
            }
            position += chunkSize;
        }
        return table;
    }

    private static void readPackage(byte[] data, int start, int headerSize, int chunkSize,
                                    Axml.StringPool valueStrings, Table table) {
        int packageId = readInt(data, start + 8) & 0xFF;
        table.packageId = packageId;
        table.packageName = readUtf16(data, start + 12, 128);
        int typeStringsOffset = readInt(data, start + 268);
        int keyStringsOffset = readInt(data, start + 276);
        Axml.StringPool typeNames = null;
        Axml.StringPool keyNames = null;
        if (typeStringsOffset > 0 && start + typeStringsOffset + 8 <= data.length) {
            typeNames = Axml.readStringPool(data, start + typeStringsOffset,
                    readShort(data, start + typeStringsOffset + 2));
        }
        if (keyStringsOffset > 0 && start + keyStringsOffset + 8 <= data.length) {
            keyNames = Axml.readStringPool(data, start + keyStringsOffset,
                    readShort(data, start + keyStringsOffset + 2));
        }
        int position = start + headerSize;
        int end = start + chunkSize;
        while (position + 8 <= end) {
            int type = readShort(data, position);
            int itemHeaderSize = readShort(data, position + 2);
            int itemSize = readInt(data, position + 4);
            if (itemSize < 8 || position + itemSize > end) {
                break;
            }
            if (type == CHUNK_TYPE) {
                readType(data, position, itemHeaderSize, itemSize, typeNames, keyNames,
                        valueStrings, table, packageId);
            }
            position += itemSize;
        }
    }

    private static void readType(byte[] data, int start, int headerSize, int chunkSize,
                                 Axml.StringPool typeNames, Axml.StringPool keyNames,
                                 Axml.StringPool valueStrings, Table table, int packageId) {
        int typeId = data[start + 8] & 0xFF;
        int entryCount = readInt(data, start + 12);
        int entriesStart = readInt(data, start + 16);
        int configSize = readInt(data, start + 20);
        int offsetsPosition = start + 20 + configSize;
        if ((offsetsPosition & 3) != 0) {
            offsetsPosition += 4 - (offsetsPosition & 3);
        }
        if (offsetsPosition + entryCount * 4 > start + chunkSize) {
            return;
        }
        String typeName = typeNames == null ? String.valueOf(typeId)
                : typeNames.get(typeId - 1);
        String config = describeConfig(data, start + 20, configSize);
        for (int i = 0; i < entryCount; i++) {
            int offset = readInt(data, offsetsPosition + i * 4);
            if (offset == -1 || offset == 0xFFFFFFFF) {
                continue;
            }
            int entryPosition = start + entriesStart + offset;
            if (entryPosition + 16 > data.length) {
                continue;
            }
            int flags = readShort(data, entryPosition + 2);
            int keyIndex = readInt(data, entryPosition + 4);
            Entry entry = new Entry();
            entry.packageId = packageId;
            entry.packageName = table.packageName;
            entry.typeId = typeId;
            entry.typeName = typeName;
            entry.entryId = i;
            entry.key = keyNames == null ? "#" + keyIndex : keyNames.get(keyIndex);
            entry.config = config;
            if ((flags & 0x0001) != 0) {
                entry.valueType = -1;
                entry.value = "(复杂资源)";
            } else if ("id".equals(typeName)) {
                entry.valueType = -1;
                entry.value = "(id)";
            } else {
                int dataType = data[entryPosition + 11] & 0xFF;
                int valueData = readInt(data, entryPosition + 12);
                entry.valueType = dataType;
                entry.value = formatValue(valueStrings, dataType, valueData);
            }
            table.entries.add(entry);
        }
    }

    private static String describeConfig(byte[] data, int position, int configSize) {
        if (configSize < 28 || position + configSize > data.length) {
            return "";
        }
        int language = readShort(data, position + 8) & 0xFFFF;
        int country = readShort(data, position + 10) & 0xFFFF;
        int density = readShort(data, position + 14) & 0xFFFF;
        StringBuilder builder = new StringBuilder();
        if (language != 0) {
            builder.append(unpackLanguage(language));
            if (country != 0) {
                builder.append('-').append(unpackCountry(country));
            }
        }
        if (density != 0) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(densityName(density));
        }
        return builder.toString();
    }

    private static String unpackLanguage(int value) {
        char first = (char) ((value & 0x1F) + 'a' - 1);
        char second = (char) (((value >> 5) & 0x1F) + 'a' - 1);
        return "" + first + second;
    }

    private static String unpackCountry(int value) {
        char first = (char) ((value & 0x7F) + 'A' - 1);
        char second = (char) (((value >> 8) & 0x7F) + 'A' - 1);
        return "" + first + second;
    }

    private static String densityName(int density) {
        switch (density) {
            case 120:
                return "ldpi";
            case 160:
                return "mdpi";
            case 213:
                return "tvdpi";
            case 240:
                return "hdpi";
            case 320:
                return "xhdpi";
            case 480:
                return "xxhdpi";
            case 640:
                return "xxxhdpi";
            case 0xFFFE:
                return "anydpi";
            case 0xFFFF:
                return "nodpi";
            default:
                return density + "dpi";
        }
    }

    private static String formatValue(Axml.StringPool valueStrings, int dataType, int data) {
        switch (dataType) {
            case TYPE_NULL:
                return "null";
            case TYPE_REFERENCE:
                return "@0x" + Integer.toHexString(data);
            case TYPE_ATTRIBUTE:
                return "?0x" + Integer.toHexString(data);
            case TYPE_STRING:
                return valueStrings == null ? "" : valueStrings.get(data);
            case TYPE_FLOAT:
                return Float.toString(Float.intBitsToFloat(data));
            case TYPE_DIMENSION:
                return Axml.floatOf(data) + DIMENSION_UNITS[Axml.unitOf(data) & 0x7];
            case TYPE_FRACTION:
                return Axml.floatOf(data) + FRACTION_UNITS[Axml.unitOf(data) & 0x1];
            case TYPE_INT_DEC:
                return Integer.toString(data);
            case TYPE_INT_HEX:
                return "0x" + Integer.toHexString(data);
            case TYPE_INT_BOOLEAN:
                return data != 0 ? "true" : "false";
            case 0x1c:
                return "#" + Axml.hex8(data);
            case 0x1d:
                return "#" + Axml.hex8(data).substring(2);
            case 0x1e:
                return "#" + Axml.hex4(data);
            case 0x1f:
                return "#" + Axml.hex4(data).substring(1);
            default:
                return "0x" + Integer.toHexString(data);
        }
    }

    private static int readShort(byte[] data, int position) {
        if (position + 2 > data.length) {
            return 0;
        }
        return (data[position] & 0xFF) | ((data[position + 1] & 0xFF) << 8);
    }

    private static int readInt(byte[] data, int position) {
        if (position + 4 > data.length) {
            return 0;
        }
        return (data[position] & 0xFF) | ((data[position + 1] & 0xFF) << 8)
                | ((data[position + 2] & 0xFF) << 16) | ((data[position + 3] & 0xFF) << 24);
    }

    private static String readUtf16(byte[] data, int position, int length) {
        int end = Math.min(position + length, data.length);
        int count = 0;
        while (position + count * 2 + 1 < end && (data[position + count * 2] | data[position + count * 2 + 1]) != 0) {
            count++;
        }
        return new String(data, position, count * 2, StandardCharsets.UTF_16LE);
    }
}
