package com.mtstyle.fm;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 全局剪贴板：记录待复制/移动的文件列表。 */
public final class Clipboard {

    private static final List<File> FILES = new ArrayList<>();
    private static boolean cut;

    private Clipboard() {
    }

    public static void set(List<File> files, boolean isCut) {
        FILES.clear();
        if (files != null) {
            FILES.addAll(files);
        }
        cut = isCut;
    }

    public static List<File> getFiles() {
        return new ArrayList<>(FILES);
    }

    public static boolean isEmpty() {
        return FILES.isEmpty();
    }

    public static boolean isCut() {
        return cut;
    }

    public static int size() {
        return FILES.size();
    }

    public static void clear() {
        FILES.clear();
        cut = false;
    }
}
