package com.sodawatch.android;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

final class DiagnosticLog {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Executor WRITER = Executors.newSingleThreadExecutor();

    private final File file;
    private String previousFrame;
    private long start;
    private int bytesWritten;
    private boolean limitReached;

    DiagnosticLog(Context context) { file = file(context); }

    static File file(Context context) {
        return new File(new File(context.getFilesDir(), "diagnostics"), "SodaWatch-运行日志.txt");
    }

    void reset(long now, String header) {
        start = now;
        previousFrame = null;
        write(header + "\n", false);
    }

    void event(long now, String message) {
        write("\n[" + elapsed(now) + "s] " + message + "\n", true);
    }

    void frame(long now, String contents) {
        if (contents.equals(previousFrame)) return;
        previousFrame = contents;
        write("\n[" + elapsed(now) + "s] 界面元素\n" + contents, true);
    }

    private String elapsed(long now) {
        long millis = Math.max(0, now - start);
        return String.format(java.util.Locale.ROOT, "%d.%03d", millis / 1000, millis % 1000);
    }

    private void write(String text, boolean append) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        WRITER.execute(() -> {
            if (!append) {
                bytesWritten = 0;
                limitReached = false;
            }
            if (limitReached) return;
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) {
                Log.e("SodaWatch", "Cannot create diagnostic directory");
                return;
            }
            try (FileOutputStream output = new FileOutputStream(file, append)) {
                if (bytesWritten + bytes.length > MAX_BYTES) {
                    output.write("\n日志已达到 32 MB 上限，后续界面记录已停止。\n".getBytes(StandardCharsets.UTF_8));
                    limitReached = true;
                    return;
                }
                output.write(bytes);
                bytesWritten += bytes.length;
            } catch (IOException error) {
                Log.e("SodaWatch", "Cannot write diagnostic file", error);
            }
        });
    }
}
