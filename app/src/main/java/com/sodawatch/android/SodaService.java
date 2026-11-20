package com.sodawatch.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sodawatch.android.AdFlow.Action;
import com.sodawatch.android.AdFlow.Signal;
import com.sodawatch.android.MusicWindow.Observation;
import com.sodawatch.android.MusicWindow.Target;
import com.sodawatch.android.ScreenReading.Label;
import com.sodawatch.android.ScreenReading.Reading;

public final class SodaService extends AccessibilityService {
    static final String MUSIC_PACKAGE = "com.luna.music";
    private static final long BETWEEN_FRAMES = 850L;
    private static final long AFTER_CLICK = 1_400L;
    private static final long IDLE_TIMEOUT = 78_000L;
    private static final long SESSION_LIMIT = 12 * 60_000L;
    private static final int TRACE_LIMIT = 32;
    private static final int NODE_SCAN_LIMIT = 1_200;
    private static final String LOG_PREFS = "last_run";
    private static final String LOG_KEY = "events";

    record Status(boolean running, String detail, int completed, int goal) { }
    private record NodeEntry(AccessibilityNodeInfo node, int parent) { }
    private record NodeSnapshot(List<Label> labels, List<Rect> inputBounds, String details) { }
    private record OcrSnapshot(List<Label> labels, String details) { }

    private static SodaService connected;
    private static Status status = new Status(false, "", 0, 0);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::scan;
    private final ArrayDeque<String> trace = new ArrayDeque<>();
    private final MusicWindow musicWindow = new MusicWindow(this);
    private DiagnosticLog diagnostic;
    private WindowWait windowWait;
    private String lastTraceEvent;
    private Signal lastLoggedSignal;
    private String lastWindowDescription;
    private String lastDecision;
    private AdFlow session;
    private TextRecognizer recognizer;
    private long startedAt;
    private long lastProgress;
    private boolean reachedMusic;
    private boolean screenshotBlocked;
    private int displayCaptureWindow;
    private Target previousWindow;
    private Reading previousReading;

    static SodaService connected() { return connected; }
    static Status status() { return status; }

    static String lastRunLog(Context context) {
        return context.getSharedPreferences(LOG_PREFS, MODE_PRIVATE).getString(LOG_KEY, "");
    }

    @Override protected void onServiceConnected() {
        diagnostic = new DiagnosticLog(this);
        connected = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (session == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String change = musicWindow.onWindowChanged(event);
        if (change != null) diagnostic.event(SystemClock.elapsedRealtime(), change);
    }

    @Override public void onInterrupt() { stop("系统中断了无障碍服务"); }

    @Override public void onDestroy() {
        if (connected == this) connected = null;
        stop("无障碍服务已关闭");
        if (recognizer != null) recognizer.close();
        super.onDestroy();
    }

    void begin(int target) {
        handler.removeCallbacks(tick);
        session = new AdFlow(target);
        startedAt = lastProgress = SystemClock.elapsedRealtime();
        windowWait = new WindowWait(startedAt);
        musicWindow.reset();
        reachedMusic = screenshotBlocked = false;
        displayCaptureWindow = -1;
        previousWindow = null;
        previousReading = null;
        trace.clear();
        lastTraceEvent = null;
        lastLoggedSignal = null;
        lastWindowDescription = null;
        lastDecision = null;
        diagnostic.reset(startedAt, "汽水领时长 / Android API " + Build.VERSION.SDK_INT
                + "\n坐标为屏幕像素。AX=无障碍控件，OCR=截图上识别到的文字。只记录最新一次运行。");
        logEvent("开始任务，目标 " + target + " 轮");
        publish("正在打开汽水音乐");
        schedule(350L);
    }

    void stop(String reason) {
        if (session == null) return;
        logEvent("结束：" + reason + "（完成 " + session.completed() + " / " + session.target() + " 轮）");
        status = new Status(false, reason, session.completed(), session.target());
        session = null;
        handler.removeCallbacks(tick);
        previousWindow = null;
        previousReading = null;
    }

    private void logEvent(String event) {
        if (event.equals(lastTraceEvent)) return;
        lastTraceEvent = event;
        while (trace.size() >= TRACE_LIMIT) trace.removeFirst();
        long now = SystemClock.elapsedRealtime();
        long seconds = (now - startedAt) / 1_000L;
        trace.addLast(seconds + "s  " + event);
        getSharedPreferences(LOG_PREFS, MODE_PRIVATE).edit().putString(LOG_KEY, String.join("\n", trace)).apply();
        diagnostic.event(now, event);
    }

    private void publish(String detail) {
        status = new Status(true, detail, session.completed(), session.target());
    }

    private void schedule(long delay) {
        handler.removeCallbacks(tick);
        if (session != null) handler.postDelayed(tick, delay);
    }

    private void scan() {
        AdFlow current = session;
        if (current == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!getSystemService(PowerManager.class).isInteractive()
                || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            stop("手机已锁屏，任务已停止");
            return;
        }
        if (now - startedAt >= SESSION_LIMIT) {
            stop("已到本次运行时间上限");
            return;
        }
        Observation observed = musicWindow.inspect();
        recordForeground(now, observed);
        Target window = observed.target();
        switch (windowWait.observe(now, window != null, observed.otherApp())) {
            case LAUNCH_TIMEOUT -> { stop("没有取得汽水音乐窗口，请查看完整日志"); return; }
            case UNAVAILABLE -> { stop("长时间无法读取汽水音乐窗口，请查看完整日志"); return; }
            case LEFT_APP -> { stop("确认前台切换到 " + observed.otherApp()); return; }
            case WAIT -> {
                previousWindow = null;
                previousReading = null;
                if (reachedMusic) publish("等待汽水音乐窗口重新可读");
                schedule(470L);
                return;
            }
            case SCAN -> { }
        }
        if (!reachedMusic) logEvent("已进入汽水音乐");
        reachedMusic = true;
        if (now - lastProgress >= IDLE_TIMEOUT) {
            stop(screenshotBlocked ? "当前页面限制截图，且没有可识别的按钮" : "长时间未识别到下一步，请检查汽水音乐页面");
            return;
        }
        publish(current.status());
        capture(current, window);
    }

    private void recordForeground(long now, Observation observed) {
        String description = observed.description();
        if (description.equals(lastWindowDescription)) return;
        lastWindowDescription = description;
        diagnostic.event(now, (observed.target() == null ? "暂停采集 " : "汽水音乐窗口 ") + description);
    }

    private Target currentWindow() {
        return musicWindow.inspect().target();
    }

    private void capture(AdFlow current, Target window) {
        boolean fullDisplay = Build.VERSION.SDK_INT < 34 || displayCaptureWindow == window.windowId();
        AtomicBoolean finished = new AtomicBoolean();
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true) && session == current) {
                analyze(current, window, null, "截图服务 4 秒内未回调");
            }
        };
        TakeScreenshotCallback callback = new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                if (!finished.compareAndSet(false, true)) {
                    result.getHardwareBuffer().close();
                    return;
                }
                handler.removeCallbacks(timeout);
                screenshot(current, window, result, fullDisplay);
            }

            @Override public void onFailure(int code) {
                if (!finished.compareAndSet(false, true)) return;
                handler.removeCallbacks(timeout);
                if (session != current) return;
                screenshotBlocked = true;
                if (Build.VERSION.SDK_INT >= 34 && !fullDisplay && code != ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                    displayCaptureWindow = window.windowId();
                }
                analyze(current, window, null, "截图服务返回 " + code + (fullDisplay ? "（屏幕）" : "（窗口）"));
            }
        };
        try {
            handler.postDelayed(timeout, 4_000L);
            if (fullDisplay) {
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), callback);
            } else if (Build.VERSION.SDK_INT >= 34) {
                takeScreenshotOfWindow(window.windowId(), getMainExecutor(), callback);
            }
        } catch (RuntimeException error) {
            handler.removeCallbacks(timeout);
            if (!finished.compareAndSet(false, true)) return;
            screenshotBlocked = true;
            analyze(current, window, null, "无法请求截图：" + error.getClass().getSimpleName());
        }
    }

    private void screenshot(AdFlow current, Target target, ScreenshotResult result, boolean fullDisplay) {
        try (HardwareBuffer buffer = result.getHardwareBuffer()) {
            if (session != current) return;
            if (!target.equals(currentWindow())) {
                schedule(BETWEEN_FRAMES);
                return;
            }
            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (hardware == null) {
                analyze(current, target, null, "截图没有可读的像素");
                return;
            }
            Bitmap image;
            String size = hardware.getWidth() + "×" + hardware.getHeight();
            try {
                image = copyWindow(hardware, target.bounds(), fullDisplay);
            } finally {
                hardware.recycle();
            }
            if (image == null) {
                if (!fullDisplay) displayCaptureWindow = target.windowId();
                analyze(current, target, null, "截图 " + size + " 与窗口坐标不匹配");
                return;
            }
            screenshotBlocked = false;
            recognize(current, target, image, (fullDisplay ? "屏幕裁剪 " : "窗口截图 ")
                    + image.getWidth() + "×" + image.getHeight());
        } catch (RuntimeException error) {
            if (session == current) analyze(current, target, null, "截图不可用：" + error.getClass().getSimpleName());
        }
    }

    private Bitmap copyWindow(Bitmap source, Rect bounds, boolean fullDisplay) {
        if (!fullDisplay) {
            if (Math.abs(source.getWidth() - bounds.width()) > 4
                    || Math.abs(source.getHeight() - bounds.height()) > 4) return null;
            return source.copy(Bitmap.Config.ARGB_8888, false);
        }
        if (bounds.left < 0 || bounds.top < 0 || bounds.right > source.getWidth()
                || bounds.bottom > source.getHeight()) return null;
        Bitmap cropped = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width(), bounds.height());
        try {
            return cropped.copy(Bitmap.Config.ARGB_8888, false);
        } finally {
            if (cropped != source) cropped.recycle();
        }
    }

    private void recognize(AdFlow current, Target target, Bitmap image, String source) {
        try {
            if (recognizer == null) {
                recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            }
            recognizer.process(InputImage.fromBitmap(image, 0)).addOnCompleteListener(getMainExecutor(), task -> {
                try {
                    if (session != current) return;
                    if (!task.isSuccessful()) {
                        String error = task.getException() == null ? "未知原因" : task.getException().getClass().getSimpleName();
                        analyze(current, target, null, "OCR 失败：" + error, image);
                        return;
                    }
                    analyze(current, target, task.getResult(), source, image);
                } finally {
                    image.recycle();
                }
            });
        } catch (RuntimeException error) {
            image.recycle();
            analyze(current, target, null, "OCR 启动失败：" + error.getClass().getSimpleName());
        }
    }

    private void analyze(AdFlow current, Target target, Text text, String imageStatus) {
        analyze(current, target, text, imageStatus, null);
    }

    private void analyze(AdFlow current, Target target, Text text, String imageStatus, Bitmap image) {
        if (session != current) return;
        Observation observed = musicWindow.inspect();
        if (!target.equals(observed.target())) {
            recordForeground(SystemClock.elapsedRealtime(), observed);
            previousWindow = null;
            previousReading = null;
            schedule(BETWEEN_FRAMES);
            return;
        }

        NodeSnapshot nodes = inspectNodes(observed.root(), target);
        OcrSnapshot ocr = inspectOcr(text, target.bounds(), nodes.inputBounds());
        diagnostic.frame(SystemClock.elapsedRealtime(), observed.description() + "\n"
                + "图像识别：" + imageStatus + "\n" + nodes.details() + ocr.details());

        AdFlow.Phase phase = current.phase();
        if (phase == AdFlow.Phase.READY && observed.adActivity()) phase = AdFlow.Phase.WAITING_FOR_AD;
        ocr.labels().addAll(nodes.labels());
        Reading reading = ScreenReading.read(ocr.labels(), target.bounds(), phase);
        if (reading.promoDownload() != null && image != null) {
            Rect localDownload = new Rect(reading.promoDownload());
            localDownload.offset(-target.bounds().left, -target.bounds().top);
            Rect close = PromoCloseMatcher.find(image.getWidth(), image.getHeight(), localDownload, image::getPixel);
            if (close != null) {
                close.offset(target.bounds().left, target.bounds().top);
                reading = new Reading(Signal.DOWNLOAD_PROMO, close, "关闭下载弹窗");
            }
        }
        accept(current, target, reading);
    }

    private static OcrSnapshot inspectOcr(Text text, Rect bounds, List<Rect> inputBounds) {
        List<Label> labels = new ArrayList<>();
        StringBuilder details = new StringBuilder();
        if (text == null || text.getTextBlocks().isEmpty()) return new OcrSnapshot(labels, "OCR：无识别元素\n");
        int blockIndex = 0;
        for (Text.TextBlock block : text.getTextBlocks()) {
            int lineIndex = 0;
            blockIndex++;
            for (Text.Line line : block.getLines()) {
                int elementIndex = 0;
                String lineId = blockIndex + "." + ++lineIndex;
                addOcrElement(labels, details, "OCR 行 " + lineId, line.getText(), line.getBoundingBox(), bounds, inputBounds);
                for (Text.Element element : line.getElements()) {
                    String elementId = lineId + "." + ++elementIndex;
                    addOcrElement(labels, details, "OCR 词 " + elementId, element.getText(), element.getBoundingBox(), bounds, inputBounds);
                    List<Text.Symbol> symbols = element.getSymbols();
                    if (!symbols.isEmpty() && line.getText().contains("领取")) {
                        Text.Symbol last = symbols.get(symbols.size() - 1);
                        String glyph = last.getText();
                        if (glyph.length() == 1 && "×✕✖✗xX".contains(glyph)) {
                            addOcrElement(labels, details, "OCR 符 " + elementId, glyph, last.getBoundingBox(), bounds, inputBounds);
                        }
                    }
                    if (symbols.size() >= 2 && element.getText().endsWith("取消")
                            && line.getText().contains("进入直播间")) {
                        Text.Symbol first = symbols.get(symbols.size() - 2);
                        Text.Symbol last = symbols.get(symbols.size() - 1);
                        Rect firstBox = first.getBoundingBox();
                        Rect lastBox = last.getBoundingBox();
                        if ("取".equals(first.getText()) && "消".equals(last.getText()) && firstBox != null && lastBox != null) {
                            Rect cancel = new Rect(firstBox);
                            cancel.union(lastBox);
                            addOcrElement(labels, details, "OCR 按钮 " + elementId, "取消", cancel, bounds, inputBounds);
                        }
                    }
                }
            }
        }
        return new OcrSnapshot(labels, details.toString());
    }

    private static void addOcrElement(List<Label> labels, StringBuilder details, String id, String text,
                                      Rect local, Rect window, List<Rect> inputBounds) {
        Rect onScreen = local == null ? null : new Rect(local);
        if (onScreen != null) onScreen.offset(window.left, window.top);
        details.append(id).append(" bounds=").append(onScreen == null ? "?" : onScreen.toShortString());
        if (onScreen != null) {
            for (Rect input : inputBounds) {
                if (!Rect.intersects(input, onScreen)) continue;
                details.append(" text=<输入框已隐藏>\n");
                return;
            }
        }
        details.append(" text=").append(quoted(text)).append('\n');
        if (onScreen != null && !text.trim().isEmpty()) labels.add(new Label(text, onScreen));
    }

    private static NodeSnapshot inspectNodes(AccessibilityNodeInfo root, Target target) {
        if (root == null || root.getWindowId() != target.windowId()) {
            return new NodeSnapshot(List.of(), List.of(), "AX：系统未提供此窗口的控件树\n");
        }
        List<Label> labels = new ArrayList<>();
        List<Rect> inputBounds = new ArrayList<>();
        StringBuilder details = new StringBuilder();
        ArrayDeque<NodeEntry> pending = new ArrayDeque<>();
        pending.add(new NodeEntry(root, 0));
        int visited = 0;
        while (!pending.isEmpty() && visited < NODE_SCAN_LIMIT) {
            NodeEntry entry = pending.removeFirst();
            AccessibilityNodeInfo node = entry.node();
            if (node.getWindowId() != target.windowId()) continue;
            int index = ++visited;
            Rect box = new Rect();
            node.getBoundsInScreen(box);
            boolean input = node.isPassword() || node.isEditable();
            if (input && node.isVisibleToUser() && !box.isEmpty()) inputBounds.add(box);
            details.append("AX #").append(index).append(" parent=").append(entry.parent())
                    .append(" class=").append(quoted(node.getClassName())).append(" id=").append(quoted(node.getViewIdResourceName()))
                    .append(" bounds=").append(box.toShortString()).append(" visible=").append(node.isVisibleToUser())
                    .append(" clickable=").append(node.isClickable()).append(" enabled=").append(node.isEnabled())
                    .append(" focused=").append(node.isFocused()).append(" selected=").append(node.isSelected())
                    .append(" children=").append(node.getChildCount()).append(" text=")
                    .append(input ? "<输入框已隐藏>" : quoted(node.getText())).append(" desc=")
                    .append(input ? "<输入框已隐藏>" : quoted(node.getContentDescription())).append('\n');
            if (!input && node.isVisibleToUser() && node.isEnabled()) {
                if (node.getText() != null) labels.add(new Label(node.getText().toString(), box));
                if (node.getContentDescription() != null) labels.add(new Label(node.getContentDescription().toString(), box));
            }
            for (int child = 0; child < node.getChildCount(); child++) {
                AccessibilityNodeInfo next = node.getChild(child);
                if (next != null) pending.add(new NodeEntry(next, index));
            }
        }
        if (!pending.isEmpty()) details.append("AX 达到单帧节点读取上限：").append(NODE_SCAN_LIMIT).append('\n');
        return new NodeSnapshot(labels, inputBounds, "AX 节点数=" + visited + "\n" + details);
    }

    private static String quoted(CharSequence value) {
        if (value == null) return "\"\"";
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    private void accept(AdFlow current, Target target, Reading reading) {
        if (session != current) return;
        if (!target.equals(currentWindow())) {
            previousReading = null;
            schedule(BETWEEN_FRAMES);
            return;
        }
        boolean confirmed = target.equals(previousWindow) && sameReading(previousReading, reading, target.bounds());
        previousWindow = target;
        previousReading = reading;
        if (reading.rewardVisible()) current.noteReward();
        if (!confirmed && reading.signal() != Signal.BLOCKED) {
            schedule(BETWEEN_FRAMES);
            return;
        }
        logReading(reading);
        int previousCount = current.completed();
        long now = SystemClock.elapsedRealtime();
        Action action = current.observe(reading.signal(), now);
        String decision = "流程 phase=" + current.phase() + " signal=" + reading.signal() + " action=" + action;
        if (!decision.equals(lastDecision)) {
            diagnostic.event(now, decision);
            lastDecision = decision;
        }
        if (current.changed()) lastProgress = now;
        if (previousCount != current.completed()) logEvent("确认完成第 " + current.completed() + " 轮");
        if (action == Action.STOP) {
            if (reading.signal() == Signal.BLOCKED) stop("遇到付款或系统确认页面，任务已停止");
            else stop(current.completed() >= current.target() ? "已完成目标轮数" : "已到领取结果页面，请查看畅听时长");
            return;
        }
        publish(current.status());
        if (action == Action.WAIT || reading.click() == null) {
            schedule(BETWEEN_FRAMES);
            return;
        }
        tap(current, target, reading, action);
    }

    private void logReading(Reading reading) {
        Signal signal = reading.signal();
        if (lastLoggedSignal == signal) return;
        lastLoggedSignal = signal;
        if (!reading.button().isEmpty()) {
            logEvent("识别到「" + reading.button() + "」");
        } else if (signal == Signal.TIMER) {
            logEvent("识别到广告领奖倒计时");
        } else if (signal == Signal.REWARD_DIALOG) {
            logEvent("奖励弹窗已出现，等待可识别的按钮");
        } else if (signal == Signal.DOWNLOAD_PROMO) {
            logEvent("发现下载弹窗，等待确认关闭按钮");
        } else if (signal == Signal.UNKNOWN) {
            logEvent("未识别到可操作的按钮");
        }
    }

    private void tap(AdFlow current, Target target, Reading reading, Action action) {
        Rect button = reading.click();
        if (!target.equals(currentWindow()) || !target.bounds().contains(button)) {
            schedule(BETWEEN_FRAMES);
            return;
        }
        Path point = new Path();
        point.moveTo(button.exactCenterX(), button.exactCenterY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(point, 0L, 50L)).build();
        diagnostic.event(SystemClock.elapsedRealtime(), "请求点击 " + quoted(reading.button()) + " bounds=" + button.toShortString()
                + " x=" + button.centerX() + " y=" + button.centerY() + " window=" + target.windowId());
        previousReading = null;
        if (!dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) {
                if (session != current) return;
                lastProgress = SystemClock.elapsedRealtime();
                current.dispatched(action, lastProgress);
                logEvent("已发送点击：「" + reading.button() + "」");
                publish(current.status());
                schedule(AFTER_CLICK);
            }

            @Override public void onCancelled(GestureDescription description) {
                if (session != current) return;
                logEvent("系统取消点击：「" + reading.button() + "」");
                schedule(BETWEEN_FRAMES);
            }
        }, handler)) {
            logEvent("系统未接受点击：「" + reading.button() + "」");
            schedule(BETWEEN_FRAMES);
        }
    }

    private static boolean sameReading(Reading a, Reading b, Rect window) {
        if (a == null || a.signal() != b.signal()) return false;
        if (a.click() == null || b.click() == null) return a.click() == b.click();
        int tolerance = Math.max(12, window.width() / 25);
        return Math.abs(a.click().centerX() - b.click().centerX()) <= tolerance
                && Math.abs(a.click().centerY() - b.click().centerY()) <= tolerance;
    }
}
