package com.sodawatch.android;

import java.util.Objects;

final class WindowWait {
    enum Result { SCAN, WAIT, LAUNCH_TIMEOUT, UNAVAILABLE, LEFT_APP }

    private static final long LAUNCH_TIMEOUT = 18_000L;
    private static final long WINDOW_TIMEOUT = 60_000L;
    private static final long OTHER_APP_GRACE = 4_000L;

    private final long startedAt;
    private long lastConfirmed;
    private long otherSince;
    private String other;
    private boolean entered;

    WindowWait(long startedAt) {
        this.startedAt = startedAt;
    }

    Result observe(long now, boolean targetAvailable, String otherApp) {
        if (targetAvailable) {
            entered = true;
            lastConfirmed = now;
            other = null;
            return Result.SCAN;
        }

        if (!entered) {
            return now - startedAt >= LAUNCH_TIMEOUT ? Result.LAUNCH_TIMEOUT : Result.WAIT;
        }

        if (!Objects.equals(other, otherApp)) {
            other = otherApp;
            otherSince = now;
        }
        if (other != null && now - otherSince >= OTHER_APP_GRACE) return Result.LEFT_APP;
        return now - lastConfirmed >= WINDOW_TIMEOUT ? Result.UNAVAILABLE : Result.WAIT;
    }
}
