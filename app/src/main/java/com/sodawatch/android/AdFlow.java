package com.sodawatch.android;

final class AdFlow {
    enum Signal { UNKNOWN, BLOCKED, ENTRY, CLAIM, TOP_CONTINUE, RESUME, REWARD_DIALOG, DOWNLOAD_PROMO, TIMER, COMPLETE, LIVE_CANCEL, FINISHED }
    enum Action { WAIT, OPEN, RESUME, CLOSE, CANCEL_LIVE, DISMISS_PROMO, STOP }
    enum Phase { READY, WAITING_FOR_AD, WATCHING, WAITING_FOR_RETURN }

    private static final long CLICK_RETRY_DELAY = 4_000L;

    private final int target;
    private Phase phase = Phase.READY;
    private int completed;
    private boolean liveCancelled;
    private boolean changed;
    private int openClicks;
    private int closeClicks;
    private int resumeClicks;
    private long lastOpenClick;
    private long lastCloseClick;
    private long lastResumeClick;
    private long lastPromoClose = -CLICK_RETRY_DELAY;
    private boolean rewardBeforeTopContinuation;

    AdFlow(int target) {
        if (target < 1 || target > 10) {
            throw new IllegalArgumentException("Target must be between 1 and 10");
        }
        this.target = target;
    }

    int completed() { return completed; }
    int target() { return target; }
    boolean changed() { return changed; }
    Phase phase() { return phase; }

    void noteReward() { rewardBeforeTopContinuation = true; }

    Action observe(Signal signal, long now) {
        changed = false;
        if (signal == Signal.BLOCKED) {
            return Action.STOP;
        }
        if (signal == Signal.DOWNLOAD_PROMO) {
            return now - lastPromoClose >= CLICK_RETRY_DELAY ? Action.DISMISS_PROMO : Action.WAIT;
        }
        if (signal == Signal.TOP_CONTINUE) {
            signal = rewardBeforeTopContinuation ? Signal.CLAIM : Signal.RESUME;
        }
        if (signal == Signal.RESUME) {
            return canClick(now, lastResumeClick, resumeClicks) ? Action.RESUME : Action.WAIT;
        }
        if (phase == Phase.WAITING_FOR_RETURN) {
            if (signal == Signal.COMPLETE) {
                return canClick(now, lastCloseClick, closeClicks) ? Action.CLOSE : Action.WAIT;
            }
            if (signal == Signal.ENTRY || signal == Signal.CLAIM || signal == Signal.FINISHED || signal == Signal.TIMER) {
                finishRound();
            } else {
                return Action.WAIT;
            }
        } else if (phase == Phase.WATCHING && signal == Signal.CLAIM) {
            finishRound();
        }
        if (completed >= target || signal == Signal.FINISHED) {
            return Action.STOP;
        }
        if (signal == Signal.TIMER || signal == Signal.LIVE_CANCEL || signal == Signal.COMPLETE) {
            changePhase(Phase.WATCHING);
            if (signal == Signal.TIMER) {
                resumeClicks = 0;
                rewardBeforeTopContinuation = false;
            }
            if (signal == Signal.COMPLETE) {
                return canClick(now, lastCloseClick, closeClicks) ? Action.CLOSE : Action.WAIT;
            }
            if (signal == Signal.LIVE_CANCEL && !liveCancelled) {
                return Action.CANCEL_LIVE;
            }
            return Action.WAIT;
        }
        if (signal == Signal.ENTRY || signal == Signal.CLAIM) {
            if (phase == Phase.READY || (phase == Phase.WAITING_FOR_AD && canClick(now, lastOpenClick, openClicks))) {
                return Action.OPEN;
            }
        }
        return Action.WAIT;
    }

    void dispatched(Action action, long now) {
        switch (action) {
            case OPEN -> {
                openClicks++;
                lastOpenClick = now;
                rewardBeforeTopContinuation = false;
                changePhase(Phase.WAITING_FOR_AD);
            }
            case RESUME -> {
                resumeClicks++;
                lastResumeClick = now;
                changePhase(Phase.WATCHING);
            }
            case CLOSE -> {
                closeClicks++;
                lastCloseClick = now;
                changePhase(Phase.WAITING_FOR_RETURN);
            }
            case CANCEL_LIVE -> liveCancelled = true;
            case DISMISS_PROMO -> lastPromoClose = now;
            default -> throw new IllegalArgumentException("Expected a click action");
        }
    }

    private static boolean canClick(long now, long previous, int clicks) {
        return clicks == 0 || (clicks < 2 && now - previous >= CLICK_RETRY_DELAY);
    }

    private void finishRound() {
        completed++;
        liveCancelled = false;
        rewardBeforeTopContinuation = false;
        openClicks = closeClicks = resumeClicks = 0;
        changePhase(Phase.READY);
    }

    String status() {
        return switch (phase) {
            case READY -> "寻找汽水音乐的领时长入口";
            case WAITING_FOR_AD -> "等待广告开始";
            case WATCHING -> "等待广告播完";
            case WAITING_FOR_RETURN -> "等待返回领取页面";
        };
    }

    private void changePhase(Phase next) {
        if (phase != next) {
            phase = next;
            changed = true;
        }
    }
}
