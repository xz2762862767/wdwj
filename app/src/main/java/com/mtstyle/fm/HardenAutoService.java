package com.mtstyle.fm;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「加固 APK」自动化：无障碍服务，代替人工去点加固工具的界面。
 *
 * <p>目标：在 APK 详情页点一次「加固 APK」，就自动完成「选择该 APK → 开始加固」。</p>
 *
 * <p>实测加固工具的主页按钮是「选择APK」，点开后弹出的选择器窗口可能<b>读不到内容</b>
 * （getRootInActiveWindow 返回空），这时退化为<b>坐标盲点</b>：按候选位置点第一/第二/第三项，
 * 每次点完都回到工具主页校验“选中的文件名”是否为目标文件；选错就重新打开选择器换下一个位置。</p>
 *
 * <p>安全约束：只有用户刚点过「加固 APK」（{@link HardenAuto} 下发会话）时才动作，
 * 只操作“当前活动窗口”，只在加固工具 / 文件选择器 / 系统弹窗上动作，其余界面一律不碰；
 * 会话超时自动失效。过程与失败原因全部写进 /sdcard/Download/加固自动化日志.txt。</p>
 */
public class HardenAutoService extends AccessibilityService {

    private static final int STEP_ENTRY = 0;   // 工具主页：等「选择APK」入口
    private static final int STEP_PICK = 1;    // 选择器：定位目标 APK
    private static final int STEP_CONFIRM = 2; // 主页：点「开始加固」

    private static final long ACTION_GAP = 600L;          // 两次动作最小间隔
    private static final long SESSION_TIMEOUT = 240000L;  // 会话最长自动操作时间
    private static final long STEP_TIMEOUT = 45000L;      // 单步最长等待
    private static final long STUCK_REPORT_AT = 7000L;    // 卡住多久记录界面
    private static final long WINDOW_LOG_GAP = 2500L;     // 窗口快照间隔
    private static final long BLIND_START_AFTER = 1800L;  // 活动窗口不可读多久后开始盲点
    private static final long BLIND_TAP_GAP = 1400L;      // 盲点间隔

    /** 工具主页标识（主页一定有这个按钮）。 */
    private static final String HOME_MARK = "开始加固";

    /** 「选择APK」入口。 */
    private static final String[] ENTRY_EXACT = {
            "选择apk", "选择加固", "加固apk", "选择文件", "文件选择", "添加apk", "选择安装包"
    };
    private static final String[] ENTRY_STRONG = {
            "选择apk", "选择加固", "选择安装包", "选择要加固", "导入apk", "选择目标", "选择应用"
    };
    private static final String[] ENTRY_WEAK = {"选择", "导入", "选取", "添加", "浏览"};

    /** 「开始加固」。 */
    private static final String[] CONFIRM_EXACT = {
            "开始加固", "立即加固", "一键加固", "马上加固", "开始保护", "确认加固", "立即开始"
    };
    private static final String[] CONFIRM_STRONG = {
            "开始加固", "立即加固", "一键加固", "马上加固", "开始处理", "开始保护", "确认加固"
    };
    private static final String[] CONFIRM_WEAK = {"开始加固", "立即开始", "下一步", "确认"};
    /** 选择器底部的确认按钮（已点中文件后才会用到）。 */
    private static final String[] PICKER_DONE = {"确定", "完成", "选择此文件", "打开", "选择"};

    /**
     * 工具主页「已选中 APK」的标识。加固工具选中文件后显示的是内部 id（如 msf:1000045946），
     * 并不显示文件名，所以不能用文件名判断“选好了没有”。
     */
    private static final String[] SELECTED_KEYS = {"已选择apk", "已选择", "取消选择", "已选apk"};

    /** 「取消选择」：清掉工具里可能残留的上一次选中的 APK。 */
    private static final String[] DESELECT_KEYS = {"取消选择", "清除选择"};

    /** 系统弹窗 / 权限页按钮。 */
    private static final String[] DIALOG_STRONG = {
            "允许访问所有文件", "允许访问", "始终允许", "我同意", "同意并继续", "立即体验",
            "我知道了", "知道了", "跳过", "开启权限", "授予权限", "允许管理"
    };
    private static final String[] DIALOG_WEAK = {"允许", "同意", "继续", "确定", "好的", "开启"};

    /** 不该点的按钮。 */
    private static final String[] NEGATIVE = {
            "取消", "拒绝", "不允许", "以后再说", "暂不", "关闭", "退出", "不同意", "放弃", "删除"
    };

    /** 选择器里进下载目录。 */
    private static final String[] NAV_STRONG = {"download", "下载"};
    private static final String[] SEARCH_KEYS = {"搜索", "search"};
    private static final String[] ROOTS_KEYS = {"显示根目录", "根目录", "show roots", "roots"};
    private static final String[] NAV_WEAK = {
            "内部存储", "手机存储", "sdcard", "存储", "本地", "所有文件", "最近", "recent"
    };
    private static final String[] NAV_BACK = {"返回", "上级", "..", "上一级"};

    /** 盲点候选位置（屏幕宽高比例），依次尝试：先按列表行往下走，再试左右两列。 */
    private static final float[][] BLIND_POINTS = {
            {0.5f, 0.10f}, {0.5f, 0.17f}, {0.5f, 0.24f}, {0.5f, 0.31f}, {0.5f, 0.38f},
            {0.5f, 0.45f}, {0.25f, 0.17f}, {0.75f, 0.17f}, {0.25f, 0.24f}, {0.75f, 0.24f}
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean busy;
    private long lastActionAt;
    private long sessionStartAt;
    private long stepAt;
    private long lastDumpAt;
    private long lastWindowLogAt;
    private long unreadableSince;
    private long blindTappedAt;
    private String lastEventKey = "";
    private int step = STEP_ENTRY;
    private String targetPath = "";
    private String targetName = "";
    private int entryClicks;
    private int dialogClicks;
    private int searchTries;
    private int backTries;
    private boolean blindMode;
    private int blindIndex;
    private int blindReopen;
    private String fingerprint = "";
    private String entryFingerprint = "";
    // v5.4：选择器分步策略 + 主页离开判定（避免假"点不动"）
    private boolean pickerInUse;
    private boolean searchTried;
    private boolean searchTyped;
    private boolean rootsTried;
    private boolean downloadTried;
    private int scrollTries;
    private int lastToolHeight;
    // v5.5：只有我们自己点中了目标文件，"已选中"才认；否则先清掉工具里的残留选择
    private boolean pickedInSession;
    private boolean clearTried;
    private long lastPickClickAt;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            Context context = getApplicationContext();
            if (HardenAuto.isSessionActive(context)) {
                if (sessionStartAt == 0L) {
                    sessionStartAt = System.currentTimeMillis();
                }
                drive();
                handler.postDelayed(this, 700L);
            } else {
                reset();
                handler.postDelayed(this, 1500L);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        HardenAuto.log("无障碍服务已连接（" + Build.MANUFACTURER + " " + Build.MODEL
                + " 屏幕 " + getResources().getDisplayMetrics().widthPixels + "x"
                + getResources().getDisplayMetrics().heightPixels + "）");
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }
        if (!HardenAuto.isSessionActive(getApplicationContext())) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null) {
            return;
        }
        String key = pkg + "/" + cls;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !key.equals(lastEventKey)) {
            lastEventKey = key;
            HardenAuto.log("窗口变化：" + key);
        }
        if (!isRelevant(pkg.toString())) {
            return;
        }
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override
    public void onInterrupt() {
    }

    // ==================== 主流程 ====================

    private void drive() {
        final Context context = getApplicationContext();
        if (busy) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastActionAt < ACTION_GAP) {
            return;
        }
        if (sessionStartAt != 0L && now - sessionStartAt > SESSION_TIMEOUT) {
            fail(context, "自动操作超时未完成，请在工具里手动选择 APK");
            return;
        }
        if (targetPath.isEmpty()) {
            targetPath = HardenAuto.pendingPath(context);
            targetName = new File(targetPath).getName();
            HardenAuto.log("目标：" + targetPath);
        }
        if (stepAt == 0L) {
            stepAt = now;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        logWindows(now, root);

        if (root == null) {
            AccessibilityNodeInfo alt = readableWindowRoot();
            if (alt != null && !str(alt.getPackageName()).equals(HardenTool.PKG)) {
                HardenAuto.log("活动窗口不可读，改用窗口列表里的窗口："
                        + shorten(str(alt.getPackageName())));
                root = alt;
            }
        }

        if (root == null) {
            // 活动窗口读不到内容（加固工具的选择器就是这种）
            if (unreadableSince == 0L) {
                unreadableSince = now;
                HardenAuto.log("活动窗口不可读，尝试用窗口列表判断");
            }
            if (blindMode) {
                blindTap(context, now);
                return;
            }
            if (now - unreadableSince > BLIND_START_AFTER && hasUnreadableFullscreenWindow()) {
                blindMode = true;
                blindIndex = 0;
                blindReopen = 0;
                blindTappedAt = 0L;
                HardenAuto.log("开始盲点（活动窗口不可读，共 " + BLIND_POINTS.length + " 个候选位置）");
                blindTap(context, now);
                return;
            }
            if (now - unreadableSince > 20000L) {
                fail(context, "读不到选择文件的界面，自动选择失败（已记录日志）");
            }
            return;
        }
        unreadableSince = 0L;

        if (blindMode) {
            blindCheck(context, root);
            return;
        }
        busy = true;
        try {
            String pkg = str(root.getPackageName());
            if (pkg.equals(getPackageName())) {
                return;
            }
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            collect(root, nodes, 0);
            // 只在与加固有关的窗口记录界面文字，避免用户切到别的应用时把日志刷满
            if (pkg.equals(HardenTool.PKG)) {
                logFingerprintIfChanged(nodes);
                stepLogic(context, nodes, root);
            } else if (isPicker(pkg)) {
                pickerInUse = true;
                logFingerprintIfChanged(nodes);
                pickerLogic(context, nodes, root);
            } else if (isSystemUi(pkg)) {
                pickerInUse = true;
                logFingerprintIfChanged(nodes);
                handleDialog(nodes);
            }
        } catch (Throwable t) {
            HardenAuto.log("自动操作异常：" + t);
        } finally {
            busy = false;
        }
    }

    /** 记录窗口快照（排查用）。 */
    private void logWindows(long now, AccessibilityNodeInfo activeRoot) {
        if (now - lastWindowLogAt < WINDOW_LOG_GAP) {
            return;
        }
        lastWindowLogAt = now;
        StringBuilder builder = new StringBuilder("窗口：active=");
        builder.append(activeRoot == null ? "不可读" : shorten(str(activeRoot.getPackageName())));
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                Rect bounds = new Rect();
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) {
                        continue;
                    }
                    window.getBoundsInScreen(bounds);
                    AccessibilityNodeInfo root = window.getRoot();
                    builder.append(" || t").append(window.getType());
                    builder.append(root == null ? " 不可读" : shorten(str(root.getPackageName())));
                    if (window.isActive()) {
                        builder.append("(活动)");
                    }
                    builder.append(' ').append(bounds.width()).append('x').append(bounds.height());
                }
            }
        } catch (Throwable ignored) {
        }
        HardenAuto.log(builder.toString());
    }

    /** 活动窗口读不到时，从窗口列表里找一个可读的窗口。 */
    private AccessibilityNodeInfo readableWindowRoot() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) {
                return null;
            }
            for (AccessibilityWindowInfo window : windows) {
                if (window != null && window.isActive() && window.getRoot() != null) {
                    return window.getRoot();
                }
            }
            for (AccessibilityWindowInfo window : windows) {
                if (window != null && window.getRoot() != null
                        && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    return window.getRoot();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 是否存在“读不到内容的全屏窗口”（多半就是加固工具弹出的选择器）。 */
    private boolean hasUnreadableFullscreenWindow() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) {
                return false;
            }
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            Rect bounds = new Rect();
            for (AccessibilityWindowInfo window : windows) {
                if (window == null || window.getRoot() != null) {
                    continue;
                }
                window.getBoundsInScreen(bounds);
                if (bounds.width() >= metrics.widthPixels * 0.7
                        && bounds.height() >= metrics.heightPixels * 0.5) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 加固工具自己的界面：主页 or 工具内置选择器。 */
    private void stepLogic(Context context, List<AccessibilityNodeInfo> nodes,
                           AccessibilityNodeInfo root) {
        if (containsText(nodes, HOME_MARK)) {
            // 主页
            boolean selected = isApkSelected(nodes);
            if (findFile(nodes) != null || (selected && pickedInSession)) {
                if (step != STEP_CONFIRM) {
                    HardenAuto.log("主页已选中目标 APK，点「开始加固」");
                }
                enterStep(STEP_CONFIRM);
                clickConfirm(context, nodes);
                return;
            }
            if (selected && !clearTried) {
                // 工具里残留着上一次选中的 APK：先清掉，避免加固错文件
                clearTried = true;
                pickedInSession = false;
                HardenAuto.log("工具里已有选中的 APK，先点「取消选择」");
                clickKeyword(nodes, DESELECT_KEYS, false);
                return;
            }
            if (step != STEP_ENTRY) {
                enterStep(STEP_ENTRY);
            }
            if (handleDialogIfAny(nodes)) {
                return;
            }
            List<AccessibilityNodeInfo> candidates =
                    findKeywords(nodes, ENTRY_EXACT, ENTRY_STRONG, ENTRY_WEAK);
            if (candidates.isEmpty()) {
                reportIfStuck(context, nodes, "工具主页没找到「选择APK」");
                return;
            }
            if (entryClicks == 0) {
                entryFingerprint = fingerprint;
            }
            if (entryClicks >= 9 && !pickerInUse) {
                dump("点「选择APK」多次界面文字无变化", nodes);
                structureDump(nodes);
                fail(context, "点不动「选择APK」（界面结构已写进日志）");
                return;
            }
            if (pickerInUse) {
                // 选择器已经打开过（可能被系统弹回）。重新走一遍，从头点入口
                pickerInUse = false;
                entryClicks = 0;
                entryFingerprint = "";
                HardenAuto.log("回到工具主页，重新打开文件选择器");
            }
            // 首帧坐标会漂几十像素导致点击落空：等窗口高度稳定再点
            int height = windowHeight();
            if (lastToolHeight == 0) {
                lastToolHeight = height;
                HardenAuto.log("记录工具窗口高 " + height + "，等布局稳定");
            } else if (lastToolHeight != height) {
                lastToolHeight = height;
                HardenAuto.log("工具窗口高度变化中（" + height + "），等布局稳定");
                return;
            }
            int candidateCount = Math.min(candidates.size(), 3);
            AccessibilityNodeInfo candidate = candidates.get((entryClicks / 3) % candidateCount);
            int variant = entryClicks % 3;
            entryClicks++;
            enterStep(STEP_PICK);
            resetPickerFlags();
            HardenAuto.log("点击选择入口（第 " + entryClicks + " 次，候选 "
                    + candidates.size() + " 个，坐标变体 " + variant + "）");
            clickEntry(candidate, variant);
            return;
        }
        pickerLogic(context, nodes, root);
    }

    /** 文件选择器（系统的 documentsui / 工具自带的列表）。 */
    private void pickerLogic(Context context, List<AccessibilityNodeInfo> nodes,
                             AccessibilityNodeInfo root) {
        if (step != STEP_PICK) {
            enterStep(STEP_PICK);
        }
        pickerInUse = true;
        long inStep = System.currentTimeMillis() - stepAt;

        // 0) 目标就在眼前
        long now = System.currentTimeMillis();
        AccessibilityNodeInfo file = findFile(nodes);
        if (file != null) {
            if (now - lastPickClickAt < 2000L) {
                // 刚点过：等界面切回工具主页，避免连点把选择器点乱
                return;
            }
            lastPickClickAt = now;
            pickedInSession = true;
            HardenAuto.log("选择器里看到目标文件，点它");
            enterStep(STEP_CONFIRM);
            click(file, "目标文件：" + targetName);
            return;
        }
        if (handleDialogIfAny(nodes)) {
            return;
        }

        // 1) 用搜索框按文件名搜（最可靠）
        if (!searchTried && inStep > 1200L) {
            if (clickKeyword(nodes, SEARCH_KEYS, true)) {
                searchTried = true;
                HardenAuto.log("打开选择器搜索");
                return;
            }
        }
        if (searchTried && !searchTyped) {
            AccessibilityNodeInfo input = findEditable(root);
            if (input != null) {
                searchTyped = true;
                HardenAuto.log("搜索框输入文件名：" + targetName);
                setText(input, targetName);
                return;
            }
        }
        if (searchTyped && inStep < 22000L) {
            HardenAuto.log("等待搜索结果…");
            return;
        }

        // 2) 打开存储根目录列表 → 下载
        if (!rootsTried && inStep > 2500L && clickKeyword(nodes, ROOTS_KEYS, true)) {
            rootsTried = true;
            HardenAuto.log("打开存储根目录列表");
            return;
        }
        if (rootsTried && !downloadTried && clickKeyword(nodes, NAV_STRONG, true)) {
            downloadTried = true;
            HardenAuto.log("进入下载目录");
            return;
        }

        // 3) 列表往下翻找
        if (scrollTries < 8 && inStep > 3500L) {
            AccessibilityNodeInfo list = findScrollable(root);
            if (list != null
                    && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                scrollTries++;
                lastActionAt = System.currentTimeMillis();
                HardenAuto.log("文件列表下翻（第 " + scrollTries + " 次）");
                return;
            }
        }
        reportIfStuck(context, nodes, "选择器里没找到目标 APK");
    }

    private void resetPickerFlags() {
        searchTried = false;
        searchTyped = false;
        rootsTried = false;
        downloadTried = false;
        scrollTries = 0;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            if (child.isScrollable() && child.isVisibleToUser()) {
                return child;
            }
            AccessibilityNodeInfo deep = findScrollable(child);
            if (deep != null) {
                return deep;
            }
        }
        return null;
    }

    /** 点「开始加固」。 */
    private void clickConfirm(Context context, List<AccessibilityNodeInfo> nodes) {
        if (clickKeyword(nodes, CONFIRM_EXACT, false)
                || clickKeyword(nodes, CONFIRM_STRONG, true)
                || clickKeyword(nodes, CONFIRM_WEAK, true)
                || clickKeyword(nodes, PICKER_DONE, false)) {
            HardenAuto.log("已点击「开始加固」，自动操作结束");
            toast("已自动提交加固：" + targetName);
            HardenAuto.endSession(context);
            reset();
            return;
        }
        reportIfStuck(context, nodes, "没找到「开始加固」按钮");
    }

    private boolean handleDialogIfAny(List<AccessibilityNodeInfo> nodes) {
        if (dialogClicks >= 10) {
            return false;
        }
        if (clickKeyword(nodes, DIALOG_STRONG, true) || clickKeyword(nodes, DIALOG_WEAK, true)) {
            dialogClicks++;
            HardenAuto.log("处理弹窗（第 " + dialogClicks + " 次）");
            return true;
        }
        return false;
    }

    private void handleDialog(List<AccessibilityNodeInfo> nodes) {
        handleDialogIfAny(nodes);
    }

    // ==================== 盲点（选择器不可读时） ====================

    private void blindTap(Context context, long now) {
        if (now - blindTappedAt < BLIND_TAP_GAP) {
            return;
        }
        blindTappedAt = now;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        if (blindIndex >= BLIND_POINTS.length) {
            fail(context, "选择文件的界面无法读取，自动选择失败（已记录日志）");
            return;
        }
        float[] point = BLIND_POINTS[blindIndex];
        blindIndex++;
        int x = (int) (metrics.widthPixels * point[0]);
        int y = (int) (metrics.heightPixels * point[1]);
        HardenAuto.log("盲点第 " + blindIndex + " 处（" + x + "," + y + "）");
        tap(x, y);
    }

    /** 盲点之后回到工具界面：校验选中的是不是目标文件。 */
    private void blindCheck(Context context, AccessibilityNodeInfo root) {
        String pkg = str(root.getPackageName());
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes, 0);
        if (isSystemUi(pkg) || (!pkg.equals(HardenTool.PKG) && isPicker(pkg))) {
            handleDialogIfAny(nodes);
            return;
        }
        if (!pkg.equals(HardenTool.PKG)) {
            return;
        }
        boolean home = containsText(nodes, HOME_MARK);
        boolean ours = findFile(nodes) != null;
        if (!home) {
            // 工具内置的列表页：目标就在眼前就直接点
            if (ours) {
                blindMode = false;
                AccessibilityNodeInfo file = findFile(nodes);
                click(file, "目标文件：" + targetName);
                enterStep(STEP_CONFIRM);
                HardenAuto.log("盲点后已在可见列表里点中目标");
            }
            return;
        }
        HardenAuto.log("盲点后回到主页：选中本文件=" + ours);
        if (ours) {
            blindMode = false;
            enterStep(STEP_CONFIRM);
            clickConfirm(context, nodes);
            return;
        }
        if (blindReopen < 3 && blindIndex < BLIND_POINTS.length) {
            blindReopen++;
            HardenAuto.log("没选中目标文件，重新打开选择器（第 " + blindReopen + " 次）");
            if (!clickKeyword(nodes, ENTRY_EXACT, false)
                    && !clickKeyword(nodes, ENTRY_STRONG, true)) {
                clickKeyword(nodes, ENTRY_WEAK, true);
            }
            blindTappedAt = System.currentTimeMillis() + 600L;
            return;
        }
        fail(context, "自动选择失败（选择器界面不可读），请在工具里手动选一次");
    }

    // ==================== 基础能力 ====================

    private void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 60 || out.size() > 400) {
            return;
        }
        String text = str(node.getText());
        String desc = str(node.getContentDescription());
        if (!text.isEmpty() || !desc.isEmpty() || node.isClickable()) {
            out.add(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            collect(node.getChild(i), out, depth + 1);
        }
    }

    private boolean clickKeyword(List<AccessibilityNodeInfo> nodes, String[] keys, boolean fuzzy) {
        for (String key : keys) {
            String want = norm(key);
            for (AccessibilityNodeInfo node : nodes) {
                if (!node.isVisibleToUser()) {
                    continue;
                }
                String label = label(node);
                if (label.isEmpty() || isNegative(label)) {
                    continue;
                }
                boolean hit = fuzzy ? label.contains(want) : label.equals(want);
                if (hit && click(node, "「" + shorten(label) + "」")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsText(List<AccessibilityNodeInfo> nodes, String key) {
        String want = norm(key);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isVisibleToUser() && label(node).contains(want)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在选择器里找目标文件。优先命中“文件名本身”的节点，避开「预览 xxx」「删除 xxx」这类操作项，
     * 否则点下去会打开预览而不是选中文件。
     */
    private AccessibilityNodeInfo findFile(List<AccessibilityNodeInfo> nodes) {
        if (targetName.isEmpty()) {
            return null;
        }
        String want = norm(targetName);
        AccessibilityNodeInfo loose = null;
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser()) {
                continue;
            }
            String text = label(node);
            if (text.isEmpty() || !text.contains(want)) {
                continue;
            }
            if (text.contains("预览") || text.contains("删除") || text.startsWith("preview")) {
                if (loose == null) {
                    loose = node;
                }
                continue;
            }
            if (text.equals(want)) {
                return node;
            }
            if (loose == null) {
                loose = node;
            }
        }
        return loose;
    }

    /** 工具主页是否已经选中了某个 APK（工具显示的是内部 id，不含文件名）。 */
    private boolean isApkSelected(List<AccessibilityNodeInfo> nodes) {
        for (String key : SELECTED_KEYS) {
            if (containsText(nodes, key)) {
                return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        if (node.isEditable()) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo found = findEditable(node.getChild(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void setText(AccessibilityNodeInfo node, String text) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            lastActionAt = System.currentTimeMillis();
        }
    }

    /** 匹配出候选节点（只取用户可见的）。 */
    private List<AccessibilityNodeInfo> findKeywords(List<AccessibilityNodeInfo> nodes,
                                                     String[] exactKeys, String[] strongKeys,
                                                     String[] weakKeys) {
        List<AccessibilityNodeInfo> hits = new ArrayList<>();
        addMatches(hits, nodes, exactKeys, false);
        addMatches(hits, nodes, strongKeys, true);
        addMatches(hits, nodes, weakKeys, true);
        return hits;
    }

    private void addMatches(List<AccessibilityNodeInfo> hits, List<AccessibilityNodeInfo> nodes,
                            String[] keys, boolean fuzzy) {
        for (String key : keys) {
            String want = norm(key);
            for (AccessibilityNodeInfo node : nodes) {
                if (!node.isVisibleToUser()) {
                    continue;
                }
                String label = label(node);
                if (label.isEmpty() || isNegative(label)) {
                    continue;
                }
                boolean hit = fuzzy ? label.contains(want) : label.equals(want);
                if (hit && !hasLabel(hits, label)) {
                    hits.add(node);
                }
            }
        }
    }

    private boolean hasLabel(List<AccessibilityNodeInfo> nodes, String label) {
        for (AccessibilityNodeInfo node : nodes) {
            if (label(node).equals(label)) {
                return true;
            }
        }
        return false;
    }

    /** 入口点击：同一个按钮换几种坐标（0=节点中心，1=可点父容器中心，2=按窗口高缩放）。 */
    private void clickEntry(AccessibilityNodeInfo node, int variant) {
        AccessibilityNodeInfo target = node;
        if (variant == 1) {
            AccessibilityNodeInfo up = node;
            for (int i = 0; i < 4 && up != null && !up.isClickable(); i++) {
                up = up.getParent();
            }
            if (up != null && up.isClickable()) {
                target = up;
            }
        }
        Rect bounds = new Rect();
        target.getBoundsInScreen(bounds);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int windowHeight = windowHeight();
        int x = bounds.centerX();
        int y = bounds.centerY();
        if (variant == 2) {
            y = y - Math.round(metrics.heightPixels * 0.05f);
        }
        x = Math.max(2, Math.min(x, metrics.widthPixels - 2));
        y = Math.max(2, Math.min(y, metrics.heightPixels - 2));
        HardenAuto.log("入口坐标：变体 " + variant + " 点击(" + x + "," + y + ") 节点框="
                + bounds.left + "," + bounds.top + "," + bounds.width() + "x" + bounds.height()
                + " 屏幕=" + metrics.widthPixels + "x" + metrics.heightPixels
                + " 工具窗口高=" + windowHeight + " 类=" + shorten(str(target.getClassName())));
        tap(x, y);
    }

    /** 活动窗口里最高窗口的高度（判断是否与屏幕高度不一致）。 */
    private int windowHeight() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                Rect bounds = new Rect();
                int best = 0;
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null
                            || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                        continue;
                    }
                    window.getBoundsInScreen(bounds);
                    best = Math.max(best, bounds.height());
                }
                if (best > 0) {
                    return best;
                }
            }
        } catch (Throwable ignored) {
        }
        return getResources().getDisplayMetrics().heightPixels;
    }

    /** 界面可见文字“指纹”：一变就记一条，用来判断点击是否生效。 */
    private void logFingerprintIfChanged(List<AccessibilityNodeInfo> nodes) {
        String value = visibleTexts(nodes);
        if (value.equals(fingerprint)) {
            return;
        }
        fingerprint = value;
        HardenAuto.log("界面文字：" + value);
    }

    private String visibleTexts(List<AccessibilityNodeInfo> nodes) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser()) {
                continue;
            }
            String text = label(node);
            if (text.isEmpty() || builder.indexOf(text) >= 0) {
                continue;
            }
            if (count++ > 0) {
                builder.append(" | ");
            }
            builder.append(text);
            if (count >= 25) {
                break;
            }
        }
        return builder.toString();
    }

    private boolean click(AccessibilityNodeInfo node, String what) {
        if (node == null) {
            return false;
        }
        AccessibilityNodeInfo target = node;
        for (int i = 0; i < 6 && target != null; i++) {
            if (target.isClickable()) {
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    lastActionAt = System.currentTimeMillis();
                    HardenAuto.log("点击：" + what);
                    return true;
                }
                break;
            }
            target = target.getParent();
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }
        if (tap(bounds.centerX(), bounds.centerY())) {
            HardenAuto.log("手势点击：" + what + " @(" + bounds.centerX() + ","
                    + bounds.centerY() + ")");
            return true;
        }
        return false;
    }

    private boolean tap(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, 60L);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean ok = dispatchGesture(gesture, null, null);
        lastActionAt = System.currentTimeMillis();
        return ok;
    }

    // ==================== 判断/记录 ====================

    private boolean isNegative(String label) {
        for (String word : NEGATIVE) {
            if (label.contains(norm(word))) {
                return true;
            }
        }
        return false;
    }

    private boolean isPicker(String pkg) {
        String p = pkg.toLowerCase();
        return p.contains("documentsui") || p.contains("filemanager") || p.contains("fileexplorer")
                || p.contains("picker") || p.contains("mediadoc") || p.contains("android.files")
                || p.contains("storage");
    }

    private boolean isSystemUi(String pkg) {
        String p = pkg.toLowerCase();
        return p.contains("systemui") || p.contains("permissioncontroller")
                || p.contains("packageinstaller") || p.endsWith("settings")
                || p.contains("coloros.filemanager.settings");
    }

    private boolean isRelevant(String pkg) {
        return pkg.equals(HardenTool.PKG) || isPicker(pkg) || isSystemUi(pkg);
    }

    private void enterStep(int next) {
        if (step != next) {
            dialogClicks = 0;
        }
        step = next;
        stepAt = System.currentTimeMillis();
    }

    private void reportIfStuck(Context context, List<AccessibilityNodeInfo> nodes, String reason) {
        long now = System.currentTimeMillis();
        if (now - stepAt > STEP_TIMEOUT) {
            fail(context, reason);
            return;
        }
        if (now - stepAt > STUCK_REPORT_AT && now - lastDumpAt > 8000L) {
            lastDumpAt = now;
            dump(reason, nodes);
            structureDump(nodes);
        }
    }

    private void dump(String reason, List<AccessibilityNodeInfo> nodes) {
        StringBuilder builder = new StringBuilder();
        int limit = 0;
        for (AccessibilityNodeInfo node : nodes) {
            String label = label(node);
            if (label.isEmpty() || builder.indexOf(label) >= 0) {
                continue;
            }
            if (limit++ > 0) {
                builder.append(" | ");
            }
            builder.append(label);
            if (limit >= 60) {
                break;
            }
        }
        HardenAuto.log("未完成（" + reason + "），当前界面文字：" + builder);
    }

    /** 节点结构（排查用）：文字/类名/可点击/坐标。 */
    private void structureDump(List<AccessibilityNodeInfo> nodes) {
        int limit = 0;
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isClickable() && label(node).isEmpty()) {
                continue;
            }
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            HardenAuto.log("节点：" + (node.isClickable() ? "[可点] " : "[   ] ")
                    + (node.isVisibleToUser() ? "" : "[不可见] ")
                    + "text=" + shorten(label(node)) + " id=" + shorten(str(node.getViewIdResourceName()))
                    + " cls=" + shorten(str(node.getClassName()))
                    + " b=" + bounds.left + "," + bounds.top + "," + bounds.width() + "x" + bounds.height());
            if (++limit >= 45) {
                return;
            }
        }
    }

    private void fail(Context context, String message) {
        HardenAuto.log("中止：" + message);
        toast(message);
        HardenAuto.endSession(context);
        reset();
    }

    private void reset() {
        step = STEP_ENTRY;
        stepAt = 0L;
        sessionStartAt = 0L;
        entryClicks = 0;
        dialogClicks = 0;
        searchTries = 0;
        backTries = 0;
        pickerInUse = false;
        resetPickerFlags();
        lastToolHeight = 0;
        pickedInSession = false;
        clearTried = false;
        lastPickClickAt = 0L;
        targetPath = "";
        targetName = "";
        unreadableSince = 0L;
        blindMode = false;
        blindIndex = 0;
        blindReopen = 0;
        blindTappedAt = 0L;
        lastActionAt = 0L;
        lastDumpAt = 0L;
        lastWindowLogAt = 0L;
        lastEventKey = "";
    }

    private void toast(String message) {
        try {
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private String label(AccessibilityNodeInfo node) {
        String text = str(node.getText());
        if (text.isEmpty()) {
            text = str(node.getContentDescription());
        }
        return norm(text);
    }

    private String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String str(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private String shorten(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        return text.length() > 36 ? text.substring(0, 36) : text;
    }
}


