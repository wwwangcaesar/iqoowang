package com.monsieurmahjong.iqoowang.util;

import java.util.List;
import java.util.Locale;

/**
 * TradingRuleEngine — 确定性交易规则引擎
 *
 * 【重要】这里的判断全部是精确的数值阈值比较，不调用任何AI模型。
 * 原因见此前的架构讨论：买卖触发这种不能有侥幸的判断，必须100%确定性、
 * 可复现，LLM的不确定性和延迟都不适合放在这个位置。
 *
 * 当前内置规则（对应用户的操盘手话术，硬编码为量化规则）：
 *   1. 买入（WATCHING → STARTER）：
 *      今开 < 昨收（低开）且 现价已站稳分时低点（现价明显高于今日分时最低点，
 *      且最近几个分时点未再创新低），买入底仓
 *   2. 加仓（STARTER → ADDED）：
 *      现价突破前一交易日收盘价，加仓
 *   3. 止损（STARTER/ADDED → STOPPED）：
 *      现价跌破前一交易日最低价，止损清仓
 *
 * 后续用户会不断追加新话术——设计上这些规则以独立方法+可调常量的形式组织，
 * 方便后续扩展新规则条目，而不用改动整体调用结构。
 */
public class TradingRuleEngine {

    private static final String TAG = "TradingRuleEngine";

    // ── 可调参数（用户话术里没给出精确数字的地方，这里是当前的量化默认值）──

    /** "站稳分时低点"的缓冲幅度：现价需高于今日分时最低点至少这个百分比 */
    private static final double STABILIZE_BUFFER_PCT = 0.3;
    /** 确认止跌企稳，需要看最近几个分时点没有再创新低 */
    private static final int STABILIZE_LOOKBACK_POINTS = 5;
    /** 底仓建议仓位比例（用于生成建议文本，不代表自动下单比例） */
    public static final double STARTER_POSITION_PCT = 0.3;
    /** 加仓建议仓位比例 */
    public static final double ADD_POSITION_PCT = 0.3;

    public enum Action { NONE, BUY_STARTER, ADD_POSITION, STOP_LOSS }

    public static class RuleResult {
        public Action action = Action.NONE;
        public String note = "";
        public double triggerPrice;
    }

    /** 前一交易日参考价（收盘价 / 最低价），用于低开/突破/止损判断 */
    public static class PrevDayRef {
        public double prevClose, prevLow;
        public String prevDate;
        public boolean hasData;
    }

    /**
     * 取"距今最近一个已收盘交易日"的收盘价/最低价作为参照。
     * 直接用 MarketDataManager 里缓存的日K最后一条——由于日K是收盘后统一下载的，
     * 交易时段内缓存里的最新一条天然就是"昨天"（不会包含今天还没走完的这一根）。
     */
    public PrevDayRef getPrevDayRef(String code) {
        PrevDayRef ref = new PrevDayRef();
        try {
            List<MarketDataManager.KlineBar> bars = MarketDataManager.get().getCachedKline(code, 5);
            if (bars.isEmpty()) return ref;
            MarketDataManager.KlineBar last = bars.get(bars.size() - 1);
            ref.prevClose = last.close;
            ref.prevLow = last.low;
            ref.prevDate = last.date;
            ref.hasData = true;
        } catch (Exception ignored) {}
        return ref;
    }

    /**
     * 综合评估一只候选股/持仓股当前应该触发什么动作。
     *
     * @param status       当前状态（WatchlistManager.STATUS_*）
     * @param quote        实时行情
     * @param minutePoints 今日分时序列（评估买入条件时需要；止损/加仓不需要可传null）
     * @param prevDay      前一交易日参考价
     */
    public RuleResult evaluate(String status, RealtimeQuoteManager.Quote quote,
                                List<RealtimeQuoteManager.MinutePoint> minutePoints,
                                PrevDayRef prevDay) {
        RuleResult result = new RuleResult();
        if (quote == null || !prevDay.hasData) {
            result.note = "行情或前一日参考价缺失，本轮跳过判断";
            return result;
        }

        // 任何持仓状态下，止损判断优先级最高，先判断
        if (WatchlistManager.STATUS_STARTER.equals(status) || WatchlistManager.STATUS_ADDED.equals(status)) {
            if (quote.price < prevDay.prevLow) {
                result.action = Action.STOP_LOSS;
                result.triggerPrice = quote.price;
                result.note = String.format(Locale.CHINA,
                        "现价%.2f跌破前一交易日(%s)最低价%.2f，触发止损",
                        quote.price, prevDay.prevDate, prevDay.prevLow);
                return result;
            }
        }

        if (WatchlistManager.STATUS_STARTER.equals(status)) {
            if (quote.price > prevDay.prevClose) {
                result.action = Action.ADD_POSITION;
                result.triggerPrice = quote.price;
                result.note = String.format(Locale.CHINA,
                        "现价%.2f突破前一交易日(%s)收盘价%.2f，触发加仓",
                        quote.price, prevDay.prevDate, prevDay.prevClose);
                return result;
            }
            result.note = String.format(Locale.CHINA,
                    "已持底仓，现价%.2f尚未突破前收盘%.2f，继续观察", quote.price, prevDay.prevClose);
            return result;
        }

        if (WatchlistManager.STATUS_WATCHING.equals(status)) {
            boolean lowOpen = quote.open > 0 && quote.open < prevDay.prevClose;
            if (!lowOpen) {
                result.note = String.format(Locale.CHINA,
                        "今开%.2f未低于前收盘%.2f，不满足低开条件，继续观察", quote.open, prevDay.prevClose);
                return result;
            }

            if (minutePoints == null || minutePoints.isEmpty()) {
                result.note = "低开条件满足，但分时数据缺失，无法判断是否站稳低点，继续观察";
                return result;
            }

            double todayMinLow = Double.MAX_VALUE;
            for (RealtimeQuoteManager.MinutePoint p : minutePoints) {
                if (p.price > 0 && p.price < todayMinLow) todayMinLow = p.price;
            }
            if (todayMinLow == Double.MAX_VALUE) {
                result.note = "分时数据无有效价格，继续观察";
                return result;
            }

            boolean aboveStabilizeLine = quote.price >= todayMinLow * (1 + STABILIZE_BUFFER_PCT / 100.0);

            boolean noNewLowRecently = true;
            int n = minutePoints.size();
            int lookback = Math.min(STABILIZE_LOOKBACK_POINTS, n);
            if (lookback >= 2) {
                double minInWindow = Double.MAX_VALUE;
                for (int i = n - lookback; i < n - 1; i++) {
                    if (minutePoints.get(i).price < minInWindow) minInWindow = minutePoints.get(i).price;
                }
                double latest = minutePoints.get(n - 1).price;
                noNewLowRecently = latest >= minInWindow;
            }

            if (aboveStabilizeLine && noNewLowRecently) {
                result.action = Action.BUY_STARTER;
                result.triggerPrice = quote.price;
                result.note = String.format(Locale.CHINA,
                        "低开(今开%.2f<前收%.2f)且现价%.2f已站稳分时低点%.2f（+%.1f%%以上、近%d个分时点未创新低），触发买入底仓",
                        quote.open, prevDay.prevClose, quote.price, todayMinLow,
                        STABILIZE_BUFFER_PCT, STABILIZE_LOOKBACK_POINTS);
                return result;
            }

            result.note = String.format(Locale.CHINA,
                    "低开条件满足，但%s，继续观察",
                    !aboveStabilizeLine ? "现价距分时低点缓冲不足" : "近期分时仍在创新低，尚未止跌企稳");
            return result;
        }

        return result;
    }
}
