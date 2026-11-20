package com.sodawatch.android;

import android.graphics.Rect;

import java.util.List;
import java.util.regex.Pattern;

import com.sodawatch.android.AdFlow.Phase;
import com.sodawatch.android.AdFlow.Signal;

final class ScreenReading {
    record Label(String text, Rect bounds) {
        Label {
            text = text.replaceAll("[\\s\\p{Z}]", "");
        }
    }

    record Reading(Signal signal, Rect click, String button, boolean rewardVisible, Rect promoDownload) {
        Reading(Signal signal, Rect click, String button) { this(signal, click, button, false, null); }
    }

    private static final Pattern TIMER = Pattern.compile("[0-9０-９]{1,3}(秒|[sS])(?:之后|后)?(?:可(?:以)?|就能)?(?:领取|获得|领)(?:奖励|权益)");
    private static final Pattern LIVE = Pattern.compile("[0-9０-９]{1,2}(秒|[sS])后(?:自动)?进入直播间");
    private static final Pattern COMPLETE = Pattern.compile("(?:(?:奖励)?领取成功|领取奖励成功|已领取奖励|奖励已领取)[!！×✕✖✗xX]?");
    private static final Pattern CLOSE_CONTROL = Pattern.compile("[×✕✖✗xX]|关闭(?:广告)?");
    private static final Pattern ENTRY = Pattern.compile("(?:看|观看)(?:广告|视频)(?:即可)?领(?:取)?(?:免费听歌)?(?:时长|畅听)");
    private static final Pattern OFFER = Pattern.compile("(?:立即)?解锁(?:第[0-9０-９一二三四五六七八九十]+[天日])?畅听|领时长|继续领");
    private static final Pattern DAILY_OFFER = Pattern.compile("第[0-9０-９一二三四五六七八九十]+[天日]畅听");
    private static final Pattern UNLOCK = Pattern.compile("立即解锁");
    private static final Pattern LIMIT = Pattern.compile("今日(?:广告|任务)?(?:次数)?已达上限|今日已领完|明日再来");
    private static final Pattern DAY_GRANTED = Pattern.compile("恭喜获得第[0-9０-９一二三四五六七八九十]+[日天]畅听");
    private static final Pattern CLAIM = Pattern.compile("领取奖励|确认领取|继续看(?:广告|视频)|观看下一(?:条|个)(?:广告|视频)?");
    private static final Pattern CONTINUE_WATCHING = Pattern.compile("继续观看(?:广告|视频)?");
    private static final Pattern TOP_CONTINUE = Pattern.compile("继续观看(?:广告|视频)?(?:[×✕✖✗xX]|[，,]关闭(?:[，,]按钮)?)?");
    private static final Pattern REWARD_GRANTED = Pattern.compile(
            "恭喜(?:你|您)?获得(?:奖励|[+＋]?[0-9０-９,，]+金币)|奖励已领取|已领取奖励");
    private static final Pattern EXIT_PROMPT = Pattern.compile("坚持退出|放弃奖励|退出后.{0,12}(?:无法|不能|失去).{0,8}(?:奖励|时长)");
    private static final Pattern REWARD_CONTEXT = Pattern.compile("再看.{0,20}(?:免费听|畅听)|(?:免费听歌|看广告领|看视频领|免费畅听)");
    private static final Pattern BLOCKED = Pattern.compile("确认支付|输入支付密码|验证支付密码|指纹支付|面容支付|是否允许安装|是否确认下载|允许安装未知应用");
    private static final Pattern PROMO_TITLE = Pattern.compile("恭喜(?:你|您)?获得惊喜福利");
    private static final Pattern PROMO_DOWNLOAD = Pattern.compile("立即下载(?:[（(]?[0-9０-９]{1,2}[sS秒][）)]?)?");
    private static final Pattern PROMO_CLOSE = Pattern.compile("[×✕✖✗xX]|关闭(?:弹窗|下载提示)?");
    private static final Pattern CANCEL = Pattern.compile("取消");

    static Reading read(List<Label> labels, Rect window, Phase phase) {
        if (contains(labels, BLOCKED)) return observation(Signal.BLOCKED);
        Label download = findCentered(labels, window, PROMO_DOWNLOAD);
        if (download != null && contains(labels, PROMO_TITLE)) {
            Rect anchor = download.bounds();
            for (Label label : labels) {
                Rect bounds = label.bounds();
                if (!usable(bounds, window) || !PROMO_CLOSE.matcher(label.text()).matches()) continue;
                if (bounds.width() > window.width() * .15f || bounds.height() > window.height() * .09f) continue;
                if (Math.abs(bounds.centerX() - anchor.centerX()) > window.width() * .10f
                        || bounds.centerY() < anchor.bottom + window.height() * .025f
                        || bounds.centerY() > anchor.bottom + window.height() * .15f) continue;
                return new Reading(Signal.DOWNLOAD_PROMO, bounds, "关闭下载弹窗");
            }
            return new Reading(Signal.DOWNLOAD_PROMO, null, "", false, anchor);
        }
        Label continuation = findCentered(labels, window, CONTINUE_WATCHING);
        Label claim = findCentered(labels, window, CLAIM);
        boolean exitPrompt = contains(labels, EXIT_PROMPT);
        if (exitPrompt) {
            if (continuation != null) return click(Signal.RESUME, continuation);
            if (claim != null) return click(Signal.RESUME, claim);
            return observation(Signal.REWARD_DIALOG);
        }

        Label cancel = find(labels, window, CANCEL, 0f, .34f);
        if (cancel != null && contains(labels, LIVE)) return click(Signal.LIVE_CANCEL, cancel);

        Label complete = find(labels, window, COMPLETE, 0f, .33f);
        boolean rewardContext = contains(labels, REWARD_CONTEXT);
        boolean timer = contains(labels, TIMER);
        boolean rewardGranted = complete != null || contains(labels, REWARD_GRANTED);
        Label topContinue = findTopContinuation(labels, window);
        if (topContinue != null && (rewardGranted || phase != Phase.READY)) {
            boolean continuingSameAd = timer || exitPrompt;
            return topContinuationClick(continuingSameAd ? Signal.RESUME : Signal.TOP_CONTINUE,
                    topContinue, rewardGranted && !continuingSameAd);
        }
        if (continuation != null) {
            if (timer) return click(Signal.RESUME, continuation);
            if (phase == Phase.WAITING_FOR_RETURN || complete != null) return click(Signal.CLAIM, continuation);
            if (phase == Phase.WATCHING || phase == Phase.WAITING_FOR_AD) return click(Signal.RESUME, continuation);
            if (rewardContext) return click(Signal.CLAIM, continuation);
        }

        if (claim != null && (phase == Phase.WAITING_FOR_RETURN || (!timer && rewardContext))) {
            return click(Signal.CLAIM, claim);
        }

        if (complete != null) return click(Signal.COMPLETE, closeButton(labels, window, complete));
        if (timer) return observation(Signal.TIMER);
        if (contains(labels, LIMIT)) return observation(Signal.FINISHED);

        Label entry = find(labels, window, ENTRY, .13f, .94f);
        if (entry != null) return click(Signal.ENTRY, entry);

        Label offer = find(labels, window, OFFER, .19f, .94f);
        if (offer != null) return click(Signal.ENTRY, offer);

        Label unlock = find(labels, window, UNLOCK, .19f, .94f);
        if (unlock != null && contains(labels, DAILY_OFFER)) return click(Signal.ENTRY, unlock);

        if (contains(labels, DAY_GRANTED)) return observation(Signal.FINISHED);
        return observation(Signal.UNKNOWN);
    }

    private static Label findTopContinuation(List<Label> labels, Rect window) {
        Label best = null;
        for (Label label : labels) {
            Rect bounds = label.bounds();
            if (!usable(bounds, window) || !TOP_CONTINUE.matcher(label.text()).matches()) continue;
            if (bounds.width() > window.width() * .42f || bounds.centerX() < window.left + window.width() * .63f
                    || bounds.centerY() > window.top + window.height() * .22f) continue;
            if (best == null || bounds.width() < best.bounds().width()) best = label;
        }
        return best;
    }

    private static Reading topContinuationClick(Signal signal, Label label, boolean rewardVisible) {
        Rect bounds = label.bounds();
        if (!CONTINUE_WATCHING.matcher(label.text()).matches()) {
            // The trailing close glyph is a separate action within the same OCR box.
            bounds = new Rect(bounds.left, bounds.top, bounds.left + Math.max(1, bounds.width() * 2 / 3), bounds.bottom);
        }
        return new Reading(signal, bounds, "继续观看", rewardVisible, null);
    }

    private static Label closeButton(List<Label> labels, Rect window, Label complete) {
        Rect marker = complete.bounds();
        Label nearest = complete;
        int distance = window.width() / 4;
        for (Label label : labels) {
            Rect bounds = label.bounds();
            if (!usable(bounds, window) || !CLOSE_CONTROL.matcher(label.text()).matches()) continue;
            int rightward = bounds.centerX() - marker.centerX();
            if (rightward <= 0 || rightward >= distance || bounds.width() > window.width() / 8) continue;
            if (Math.abs(bounds.centerY() - marker.centerY()) > Math.max(marker.height(), window.height() / 46)) continue;
            distance = rightward;
            nearest = label;
        }
        return nearest;
    }

    private static boolean contains(List<Label> labels, Pattern pattern) {
        for (Label label : labels) {
            if (pattern.matcher(label.text()).find()) return true;
        }
        return false;
    }

    private static Label find(List<Label> labels, Rect window, Pattern pattern, float top, float bottom) {
        return find(labels, window, pattern, top, bottom, false);
    }

    private static Label findCentered(List<Label> labels, Rect window, Pattern pattern) {
        return find(labels, window, pattern, .25f, .80f, true);
    }

    private static Label find(List<Label> labels, Rect window, Pattern pattern, float top, float bottom, boolean centered) {
        Label best = null;
        for (Label label : labels) {
            Rect bounds = label.bounds();
            if (!usable(bounds, window)) continue;
            if (centered && Math.abs(bounds.centerX() - window.centerX()) > window.width() * .26f) continue;
            float position = (float) (bounds.centerY() - window.top) / window.height();
            if (position < top || position > bottom || !pattern.matcher(label.text()).matches()) continue;
            if (best == null || bounds.width() < best.bounds().width()) best = label;
        }
        return best;
    }

    private static boolean usable(Rect bounds, Rect window) {
        return bounds != null && !bounds.isEmpty() && window.contains(bounds)
                && bounds.width() <= window.width() * .82f && bounds.height() <= window.height() * .12f;
    }

    private static Reading click(Signal signal, Label label) { return new Reading(signal, label.bounds(), label.text()); }
    private static Reading observation(Signal signal) { return new Reading(signal, null, ""); }
}
