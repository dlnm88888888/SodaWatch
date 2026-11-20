package com.sodawatch.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class MainActivity extends Activity {
    private static final int PAGE = 0xFFEFF5F1;
    private static final int INK = 0xFF172D25;
    private static final int MUTED = 0xFF4F6258;
    private static final int GREEN = 0xFF14654F;
    private static final int TINT = 0xFFE0F2E8;
    private static final int BORDER = 0xFFD9E6DD;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 600L);
        }
    };

    private TextView stateTitle;
    private TextView stateDetail;
    private TextView countLabel;
    private TextView errorLabel;
    private Button startButton;
    private Button decrease;
    private Button increase;
    private int rounds;
    private SodaService.Status lastStatus;
    private boolean lastConnection;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rounds = getPreferences(MODE_PRIVATE).getInt("rounds", 3);
        getWindow().setDecorFitsSystemWindows(false);
        buildScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        ticker.run();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAGE);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        FrameLayout widthContainer = new FrameLayout(this);
        scroll.addView(widthContainer);
        LinearLayout body = column();
        body.setPadding(dp(24), dp(28), dp(24), dp(28));
        FrameLayout.LayoutParams centered = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        widthContainer.addView(body, centered);
        widthContainer.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.min(right - left, dp(560));
            if (width > 0 && centered.width != width) {
                centered.width = width;
                body.setLayoutParams(centered);
            }
        });

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_soda);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        body.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView title = text(getString(R.string.app_name), 30, INK, true);
        title.setAccessibilityHeading(true);
        add(body, title, 16);
        add(body, text("自动等待广告播完并领取听歌时长", 15, MUTED, false), 6);

        LinearLayout stateCard = card();
        add(body, stateCard, 26);
        stateTitle = text("", 18, INK, true);
        stateTitle.setAccessibilityHeading(true);
        add(stateCard, stateTitle, 0);
        stateDetail = text("", 14, MUTED, false);
        stateDetail.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(stateCard, stateDetail, 6);

        LinearLayout roundsCard = card();
        add(body, roundsCard, 12);
        TextView roundsTitle = text("本次广告轮数", 17, INK, true);
        roundsTitle.setAccessibilityHeading(true);
        add(roundsCard, roundsTitle, 0);
        LinearLayout stepper = new LinearLayout(this);
        stepper.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        add(roundsCard, stepper, 12);
        decrease = stepButton("−", "减少一轮", -1);
        stepper.addView(decrease, new LinearLayout.LayoutParams(dp(54), dp(48)));
        countLabel = text("", 26, INK, true);
        countLabel.setGravity(Gravity.CENTER);
        stepper.addView(countLabel, new LinearLayout.LayoutParams(0, -2, 1));
        increase = stepButton("+", "增加一轮", 1);
        stepper.addView(increase, new LinearLayout.LayoutParams(dp(54), dp(48)));

        startButton = new Button(this);
        startButton.setMinHeight(dp(56));
        startButton.setAllCaps(false);
        startButton.setTextColor(Color.WHITE);
        startButton.setTextSize(17);
        startButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        startButton.setBackground(ripple(GREEN, Color.WHITE, 16));
        startButton.setStateListAnimator(null);
        startButton.setOnClickListener(view -> primaryAction());
        add(body, startButton, 18);

        errorLabel = text("", 14, 0xFF9C3626, true);
        errorLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorLabel.setVisibility(View.GONE);
        add(body, errorLabel, 9);

        add(body, text("启动后保持汽水音乐在前台、手机亮屏。退出汽水音乐即可停止。画面在手机本地识别。", 14, MUTED, false), 14);
        Button logButton = new Button(this);
        logButton.setText("查看运行日志");
        logButton.setAllCaps(false);
        logButton.setTextColor(GREEN);
        logButton.setMinHeight(dp(48));
        logButton.setBackground(ripple(TINT, GREEN, 14));
        logButton.setStateListAnimator(null);
        logButton.setOnClickListener(view -> showRunLog());
        add(body, logButton, 12);
        setContentView(scroll);
        refresh();
    }

    private void refresh() {
        SodaService.Status current = SodaService.status();
        boolean enabled = SodaService.connected() != null;
        displayRounds(current.running());
        if (current.equals(lastStatus) && enabled == lastConnection) return;
        lastStatus = current;
        lastConnection = enabled;
        if (!enabled) {
            stateTitle.setText("需要开启无障碍服务");
            stateDetail.setText("在系统无障碍列表里选择「汽水领时长」并开启，然后返回这里。");
            startButton.setText("打开无障碍设置");
        } else if (current.running()) {
            stateTitle.setText("任务运行中");
            stateDetail.setText(current.detail() + " · " + current.completed() + " / " + current.goal() + " 轮");
            startButton.setText("停止任务");
        } else {
            boolean hasResult = current.goal() > 0;
            stateTitle.setText(hasResult ? "上次任务" : "可以开始");
            stateDetail.setText(hasResult ? "完成 " + current.completed() + " / " + current.goal() + " 轮 · " + current.detail()
                    : "选好轮数后，助手会自动打开汽水音乐。");
            startButton.setText("开始并打开汽水音乐");
        }
    }

    private void primaryAction() {
        showError(null);
        SodaService service = SodaService.connected();
        if (service == null) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (SodaService.status().running()) {
            service.stop("已手动停止");
            refresh();
            return;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(SodaService.MUSIC_PACKAGE);
        if (launch == null) {
            showError("没有找到已安装的汽水音乐，请先安装安卓版汽水音乐。");
            return;
        }
        service.begin(rounds);
        try {
            startActivity(launch);
        } catch (ActivityNotFoundException | SecurityException error) {
            service.stop("无法打开汽水音乐");
            showError("无法打开汽水音乐，请检查它是否已安装并可以正常打开。");
        }
        refresh();
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    private void showRunLog() {
        String log = SodaService.lastRunLog(this);
        File detail = DiagnosticLog.file(this);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("最近一次运行")
                .setMessage(log.isEmpty() ? "还没有运行记录。" : log)
                .setNegativeButton("关闭", null);
        if (detail.isFile() && detail.length() > 0) {
            dialog.setPositiveButton("分享完整日志", (current, which) -> shareRunLog(detail));
            dialog.setNeutralButton("保存到下载", (current, which) -> saveRunLog(detail));
        }
        dialog.show();
    }

    private void saveRunLog(File detail) {
        String name = "SodaWatch-log-" + System.currentTimeMillis() + ".txt";
        new Thread(() -> {
            try {
                copyToDownloads(detail, name);
                runOnUiThread(() -> showExportResult("日志已保存", "下载/" + name));
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> showExportResult("保存失败", "日志还在应用里。请确认手机有剩余存储空间后重试。"));
            }
        }, "SodaWatch-log-export").start();
    }

    private void copyToDownloads(File detail, String name) throws IOException {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (destination == null) throw new IOException("Downloads did not create a destination");
        boolean finished = false;
        try {
            try (FileInputStream input = new FileInputStream(detail); OutputStream output = resolver.openOutputStream(destination)) {
                if (output == null) throw new IOException("Downloads did not open the destination");
                byte[] buffer = new byte[16 * 1024];
                int bytes;
                while ((bytes = input.read(buffer)) != -1) output.write(buffer, 0, bytes);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            finished = resolver.update(destination, values, null, null) == 1;
            if (!finished) throw new IOException("Downloads did not publish the file");
        } finally {
            if (!finished) resolver.delete(destination, null, null);
        }
    }

    private void showExportResult(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("关闭", null).show();
    }

    private void shareRunLog(File detail) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".diagnostics", detail);
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(ClipData.newUri(getContentResolver(), "汽水领时长运行日志", uri));
        startActivity(Intent.createChooser(share, "分享完整运行日志"));
    }

    public static final class RunLogProvider extends FileProvider { }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout card() {
        LinearLayout card = column();
        card.setBackground(shape(Color.WHITE, BORDER, 20));
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        return card;
    }

    private TextView text(String content, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(content);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setLineSpacing(dp(3), 1f);
        return label;
    }

    private Button stepButton(String symbol, String description, int change) {
        Button button = new Button(this);
        button.setText(symbol);
        button.setContentDescription(description);
        button.setTextSize(22);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{0xFF678175, GREEN}));
        button.setBackground(ripple(TINT, GREEN, 14));
        button.setStateListAnimator(null);
        button.setOnClickListener(view -> {
            rounds = Math.max(1, Math.min(10, rounds + change));
            getPreferences(MODE_PRIVATE).edit().putInt("rounds", rounds).apply();
            displayRounds(SodaService.status().running());
        });
        return button;
    }

    private void displayRounds(boolean running) {
        String label = rounds + " 轮";
        if (!label.contentEquals(countLabel.getText())) countLabel.setText(label);
        decrease.setEnabled(!running && rounds > 1);
        increase.setEnabled(!running && rounds < 10);
    }

    private void add(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(gap);
        parent.addView(child, params);
    }

    private RippleDrawable ripple(int color, int ink, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x22000000 | (ink & 0xFFFFFF)), shape(color, 0, radius), null);
    }

    private GradientDrawable shape(int color, int stroke, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (stroke != 0) background.setStroke(dp(1), stroke);
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
