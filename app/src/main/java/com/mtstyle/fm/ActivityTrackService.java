package com.mtstyle.fm;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** 无障碍服务：监听窗口变化并记录 Activity 跳转。 */
public class ActivityTrackService extends AccessibilityService {

    private String lastPackage = "";
    private String lastClass = "";
    private long lastTime;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        CharSequence className = event.getClassName();
        if (packageName == null) {
            return;
        }
        String pkg = packageName.toString();
        String cls = className == null ? "" : className.toString();
        if (cls.isEmpty() || pkg.equals(getPackageName())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (pkg.equals(lastPackage) && cls.equals(lastClass) && now - lastTime < 1000L) {
            return;
        }
        if (pkg.equals(lastPackage) && cls.equals(lastClass)
                && now - lastTime < 1500L) {
            return;
        }
        lastPackage = pkg;
        lastClass = cls;
        lastTime = now;
        ActivityLog.append(pkg, cls);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
    }
}
