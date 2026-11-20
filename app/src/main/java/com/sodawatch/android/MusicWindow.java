package com.sodawatch.android;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

final class MusicWindow {
    record Target(int windowId, Rect bounds) { }
    record Observation(Target target, AccessibilityNodeInfo root, String otherApp, String description, boolean adActivity) { }
    private record Owner(String packageName, String className) { }

    private final AccessibilityService service;
    private final SparseArray<Owner> owners = new SparseArray<>();

    MusicWindow(AccessibilityService service) { this.service = service; }

    void reset() { owners.clear(); }

    String onWindowChanged(AccessibilityEvent event) {
        String owner = string(event.getPackageName());
        if (owner.isEmpty() || event.getWindowId() < 0) return null;
        String className = string(event.getClassName());
        Owner previous = owners.get(event.getWindowId());
        Owner incoming = new Owner(owner, className);
        if (incoming.equals(previous)) return null;
        owners.put(event.getWindowId(), incoming);
        return "窗口事件 window=" + event.getWindowId() + " package=" + owner + " class=" + className;
    }

    Observation inspect() {
        AccessibilityWindowInfo focusedApp = null;
        AccessibilityWindowInfo activeApp = null;
        AccessibilityWindowInfo topApp = null;
        AccessibilityWindowInfo overlay = null;
        for (AccessibilityWindowInfo window : service.getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                if (isHigher(window, topApp)) topApp = window;
                if (window.isFocused() && isHigher(window, focusedApp)) focusedApp = window;
                if (window.isActive() && isHigher(window, activeApp)) activeApp = window;
            } else if ((window.isFocused() || window.isActive()) && isHigher(window, overlay)) {
                overlay = window;
            }
        }

        AccessibilityWindowInfo choice = focusedApp != null ? focusedApp : activeApp;
        if (choice != null) {
            AccessibilityNodeInfo root = choice.getRoot();
            if (topApp != null && topApp.getId() != choice.getId() && SodaService.MUSIC_PACKAGE.equals(packageName(choice, root))) {
                AccessibilityNodeInfo topRoot = topApp.getRoot();
                if (SodaService.MUSIC_PACKAGE.equals(packageName(topApp, topRoot)) && coversCenter(topApp, choice)) {
                    return describe(topApp, topRoot, overlay, true);
                }
            }
            return describe(choice, root, overlay, true);
        }

        AccessibilityNodeInfo activeRoot = service.getRootInActiveWindow();
        AccessibilityWindowInfo activeWindow = activeRoot == null ? null : activeRoot.getWindow();
        if (activeWindow != null && activeWindow.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
            return describe(activeWindow, activeRoot, overlay, true);
        }
        if (topApp != null && activeRoot != null && activeRoot.getWindowId() == topApp.getId()) {
            return describe(topApp, activeRoot, overlay, true);
        }
        if (activeRoot != null && overlay == null && SodaService.MUSIC_PACKAGE.contentEquals(string(activeRoot.getPackageName()))) {
            return fromActiveRoot(activeRoot);
        }
        if (overlay != null) {
            return waiting("当前焦点是系统窗口 type=" + overlay.getType() + " window=" + overlay.getId());
        }
        if (activeRoot != null && !string(activeRoot.getPackageName()).isEmpty()) {
            return waiting("活动根节点暂未关联应用窗口 package=" + activeRoot.getPackageName());
        }
        if (topApp != null) {
            return describe(topApp, topApp.getRoot(), null, false);
        }
        return waiting("系统未返回活动应用窗口");
    }

    private Observation describe(AccessibilityWindowInfo window, AccessibilityNodeInfo root,
                                 AccessibilityWindowInfo overlay, boolean actionable) {
        Rect bounds = new Rect();
        window.getBoundsInScreen(bounds);
        if (overlay != null && overlay.getLayer() > window.getLayer()) {
            Rect overlayBounds = new Rect();
            overlay.getBoundsInScreen(overlayBounds);
            if (overlay.isFocused() || overlayBounds.contains(bounds.centerX(), bounds.centerY())) {
                return waiting("系统浮层占用焦点 type=" + overlay.getType() + " window=" + overlay.getId());
            }
        }
        Owner reported = owners.get(window.getId());
        String packageName = packageName(window, root);
        String className = reported == null ? "" : reported.className();
        String description = "window=" + window.getId() + " package=" + (packageName.isEmpty() ? "?" : packageName)
                + " class=" + className + " active=" + window.isActive() + " focused=" + window.isFocused()
                + " root=" + (root == null ? "无" : "有") + " bounds=" + bounds.toShortString();
        if (SodaService.MUSIC_PACKAGE.equals(packageName) && !bounds.isEmpty()) {
            owners.put(window.getId(), new Owner(packageName, className));
            boolean adActivity = className.endsWith(".ExcitingVideoActivity") || className.endsWith(".TTRewardVideoActivity")
                    || className.endsWith(".Stub_Standard_Portrait_Activity");
            return new Observation(new Target(window.getId(), bounds), root, null, description, adActivity);
        }
        String other = actionable && !packageName.isEmpty() && !isSystemOverlayPackage(packageName) ? packageName : null;
        return new Observation(null, null, other, description, false);
    }

    private String packageName(AccessibilityWindowInfo window, AccessibilityNodeInfo root) {
        String rootPackage = root == null ? "" : string(root.getPackageName());
        Owner reported = owners.get(window.getId());
        return !rootPackage.isEmpty() || reported == null ? rootPackage : reported.packageName();
    }

    private static boolean coversCenter(AccessibilityWindowInfo top, AccessibilityWindowInfo bottom) {
        Rect topBounds = new Rect();
        Rect bottomBounds = new Rect();
        top.getBoundsInScreen(topBounds);
        bottom.getBoundsInScreen(bottomBounds);
        return topBounds.contains(bottomBounds.centerX(), bottomBounds.centerY());
    }

    private Observation fromActiveRoot(AccessibilityNodeInfo root) {
        Rect bounds = new Rect();
        root.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return waiting("汽水音乐活动节点没有窗口坐标");
        String description = "window=" + root.getWindowId() + " package=" + SodaService.MUSIC_PACKAGE
                + " root=有 bounds=" + bounds.toShortString() + "（活动根节点）";
        return new Observation(new Target(root.getWindowId(), bounds), root, null, description, false);
    }

    private static boolean isHigher(AccessibilityWindowInfo window, AccessibilityWindowInfo other) {
        return other == null || window.getLayer() > other.getLayer();
    }

    private static boolean isSystemOverlayPackage(String owner) {
        return "com.android.systemui".equals(owner) || "com.android.permissioncontroller".equals(owner)
                || "com.google.android.permissioncontroller".equals(owner) || "com.android.intentresolver".equals(owner);
    }

    private static String string(CharSequence value) { return value == null ? "" : value.toString(); }

    private static Observation waiting(String reason) { return new Observation(null, null, null, reason, false); }
}
