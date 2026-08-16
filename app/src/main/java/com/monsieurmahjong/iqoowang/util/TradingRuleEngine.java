package com.monsieurmahjong.iqoowang.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * TradingRuleEngine — 操盘手方法论规则引擎（Layer 1）
 *
 * 依据《操盘手经验终版.md》实现：
 *   · 水下+站上VWAP → 建议底仓（1.3/3.2）
 *   · 突破水线/回踩VWAP → 建议增加50%仓位
 *   · 吃掉上影线≥阈值+放量 → 建议满仓（当日满足全部条件即可）
 *   · 三级止损：抛压预警 / 分歧K线中点 / 最低点 / 前阳破位
 *   · 全触发条件含放量验证（6.3）
 *
 * 所有阈值从 TradingRuleConfig 读取，AI 复核在 RealtimeMonitorService 层完成。
 */
public class TradingRuleEngine {

    public enum Action {
        NONE,
        BUY_STARTER,    // 建议底仓
        ADD_HALF,       // 建议增加50%仓位
        BUY_FULL,       // 建议满仓
        WARN_PRESSURE,  // 建议抛压（一级预警）
        STOP_LOSS       // 建议立即清仓止损
    }

    public enum StopLevel { NONE, LEVEL1_WARN, LEVEL2_MID, LEVEL3_LOW, YANG_BREAK }

    public static class RuleResult {
        public Action action = Action.NONE;
        public String actionLabel = "";
        public String note = "";
        public double triggerPrice;
        public StopLevel stopLevel = StopLevel.NONE;
        /** true=可立即推送（破位/三级）；false=仅收盘前30分钟内推送（二级中点） */
        public boolean notifyImmediate = true;
        public String metrics = "";
        /** 供持久化：更新后的分歧K线/峰值涨幅等 */
        public DivergenceState stateUpdate;
        /** 卖出信号专用：现价触及跌停且疑似封板缺乏对手盘，可能无法成交（对应操盘手经验终版.md 8.5节“涨跌停冻结”） */
        public boolean limitLocked = false;
        /** 供前端结构化展示：本次计算用到的水线/VWAP/日量比快照，0表示未计算或无效 */
        public double waterLine, vwap, volRatio;
    }

    public static class DivergenceState {
        public double divKHigh, divKLow, divMidKline, divMidRetrace, prevYangLow, peakGainPct;
        public String divKDate;
        /** peakGainPct 是按哪个交易日累计的——评估时若和"今天"不一致，说明跨天了，
         *  peakGainPct 要先清零重算，否则"当日峰值涨幅"会变成好几天前的旧值（修复用） */
        public String peakGainDate;
    }

    /**
     * 选股当天（仙人指路形态日）固化下来的OHLC，供满仓"吃掉上影线"判断使用。
     * 入池时存一次，之后不再变化——避免观察多日或期间重新下载过K线数据后，
     * 动态取"最新缓存日"导致算的其实是别的某一天的影线（见 evaluateFullPosition）。
     */
    public static class PatternRef {
        public double open, high, close, low;
        public String date;
        public boolean hasData;
    }

    public static class PrevDayRef {
        public double prevClose, prevLow, prevHigh, prevOpen;
        public String prevDate;
        public boolean hasData;
        public boolean isStale;
        public String expectedDate;
    }

    public static class VolumeCheck {
        public double dayRatio;
        public double recent5Ratio;
        public double threshold;
        public boolean confirmed;
        public boolean shrinkBreak;
        public String detail;
    }

    /** 涨跌停价位与疑似封板检测结果（T+1可卖状态 + 涨跌停检测，对应操盘手经验终版.md 8.5节） */
    public static class LimitInfo {
        public double upPrice, downPrice;
        public boolean atUp, atDown;
        /** 启发式代理信号：疑似封板缺乏对手盘。本地未接入真实买一/卖一挂单量，
         *  仅用一字板特征（开=高=低=现价）或近期分钟成交量相对当日峰值明显萎缩来判断，
         *  不保证100%准确，仅作提示性参考，最终以实际下单结果为准。 */
        public boolean likelyLocked;
    }

    private final TradingRuleConfig mCfg;

    public TradingRuleEngine() {
        mCfg = TradingRuleConfig.get();
    }

    public PrevDayRef getPrevDayRef(String code) {
        PrevDayRef ref = new PrevDayRef();
        try {
            List<MarketDataManager.KlineBar> bars = MarketDataManager.get().getCachedKline(code, 5);
            if (bars.isEmpty()) return ref;
            MarketDataManager.KlineBar last = bars.get(bars.size() - 1);
            ref.prevClose = last.close;
            ref.prevLow = last.low;
            ref.prevHigh = last.high;
            ref.prevOpen = last.open;
            ref.prevDate = last.date;
            ref.hasData = true;
            ref.expectedDate = MarketDataManager.get().computeExpectedTradeDate();
            ref.isStale = ref.prevDate == null || ref.prevDate.compareTo(ref.expectedDate) < 0;
        } catch (Exception ignored) {}
        return ref;
    }

    /**
     * @param trackState 候选池持久化的分歧K线/峰值状态（持仓评估时传入，观察态可null）
     */
    public RuleResult evaluate(String code, String status,
                                RealtimeQuoteManager.Quote quote,
                                List<RealtimeQuoteManager.MinutePoint> minutePoints,
                                PrevDayRef prevDay, DivergenceState trackState, PatternRef pattern) {
        RuleResult result = new RuleResult();
        if (quote == null || !prevDay.hasData) {
            result.note = "行情或前一日参考价缺失，本轮跳过判断";
            return result;
        }
        if (prevDay.isStale) {
            result.note = String.format(Locale.CHINA,
                    "【安全拦截】参考数据已陈旧（缓存最新：%s，预期最近交易日：%s），拒绝产生任何买卖信号，请先重新下载数据",
                    prevDay.prevDate, prevDay.expectedDate);
            return result;
        }

        double waterLine = prevDay.prevClose;
        double vwap = computeVwap(minutePoints, quote);
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        VolumeCheck vol = checkVolume(code, quote, minutePoints, hour, minute);
        LimitInfo limitInfo = computeLimitInfo(code, quote, minutePoints, waterLine);

        result.metrics = String.format(Locale.CHINA,
                "水线¥%.2f VWAP¥%.2f 量比%.2fx(阈值%.2fx) 5分钟量比%.2fx %s",
                waterLine, vwap, vol.dayRatio, vol.threshold, vol.recent5Ratio, vol.detail);

        DivergenceState state = trackState != null ? copyState(trackState) : new DivergenceState();
        updateIntradayPeak(quote, prevDay, state);
        if (isHolding(status)) {
            detectDivergenceKline(code, quote, prevDay, vol, state);
            updatePrevYangLow(code, state);
        } else if (WatchlistManager.STATUS_WATCHING.equals(status)) {
            // 观察中的候选股也追踪前一根阳线最低价——候选股清理机制会用它判断“上涨结构是否已经破坏”，
            // 复用止损同款的独立破位逻辑，而不是另起一套判断标准
            updatePrevYangLow(code, state);
        }

        // ── 持仓：止损优先 ──
        if (isHolding(status)) {
            RuleResult stop = evaluateStopLoss(quote, prevDay, vol, state, hour, minute);
            if (stop.action != Action.NONE) {
                stop.metrics = result.metrics + " | " + stop.metrics;
                stop.stateUpdate = state;
                stop.waterLine = waterLine; stop.vwap = vwap; stop.volRatio = vol.dayRatio;
                annotateLimitAndHoldingPeriod(stop, limitInfo);
                return stop;
            }
        }

        // ── 满仓条件（优先级高于普通加仓）──
        if (WatchlistManager.STATUS_STARTER.equals(status)
                || WatchlistManager.STATUS_ADDED.equals(status)) {
            RuleResult full = evaluateFullPosition(quote, pattern, minutePoints, vol, vwap, waterLine, hour, minute);
            if (full.action != Action.NONE) {
                full.metrics = result.metrics;
                full.stateUpdate = state;
                full.waterLine = waterLine; full.vwap = vwap; full.volRatio = vol.dayRatio;
                annotateLimitAndHoldingPeriod(full, limitInfo);
                return full;
            }
        }

        // ── 加仓：突破水线 或 底仓后回踩VWAP不破 ──
        if (WatchlistManager.STATUS_STARTER.equals(status)) {
            RuleResult add = evaluateAddHalf(quote, prevDay, minutePoints, vol, vwap, waterLine);
            if (add.action != Action.NONE) {
                add.metrics = result.metrics;
                add.stateUpdate = state;
                add.waterLine = waterLine; add.vwap = vwap; add.volRatio = vol.dayRatio;
                annotateLimitAndHoldingPeriod(add, limitInfo);
                return add;
            }
            result.note = String.format(Locale.CHINA,
                    "已持底仓，现价¥%.2f 水线¥%.2f VWAP¥%.2f，未满足加仓/满仓条件，继续观察", quote.price, waterLine, vwap);
            result.stateUpdate = state;
            return result;
        }

        if (WatchlistManager.STATUS_ADDED.equals(status) || WatchlistManager.STATUS_FULL.equals(status)) {
            result.note = String.format(Locale.CHINA,
                    "已%s，现价¥%.2f VWAP¥%.2f，持续监控止损位",
                    WatchlistManager.STATUS_FULL.equals(status) ? "满仓" : "加仓",
                    quote.price, vwap);
            result.stateUpdate = state;
            return result;
        }

        // ── 观察中：水下+站上VWAP → 底仓 ──
        if (WatchlistManager.STATUS_WATCHING.equals(status)) {
            RuleResult starter = evaluateStarter(quote, prevDay, minutePoints, vol, vwap, waterLine, result.metrics);
            starter.stateUpdate = state; // 持久化观察期已累计的峰值涨幅，避免转入底仓后状态从零重算
            starter.waterLine = waterLine; starter.vwap = vwap; starter.volRatio = vol.dayRatio;
            annotateLimitAndHoldingPeriod(starter, limitInfo);
            return starter;
        }

        return result;
    }

    // ══════════════════════════════════════════
    // 买入：底仓
    // ══════════════════════════════════════════

    private RuleResult evaluateStarter(RealtimeQuoteManager.Quote quote, PrevDayRef prevDay,
                                        List<RealtimeQuoteManager.MinutePoint> minutePoints,
                                        VolumeCheck vol, double vwap, double waterLine, String metrics) {
        RuleResult result = new RuleResult();
        result.metrics = metrics;

        boolean underwater = quote.price < waterLine;
        if (!underwater) {
            result.note = String.format(Locale.CHINA,
                    "现价¥%.2f已在水线¥%.2f上方，底仓条件为【水下+站上VWAP】，本轮不满足——这支股票还没买过，“突破水线加仓”那一条只适用于已持底仓的股票，对这里不适用。如果它回踩到水线以下并站稳VWAP，会重新进入底仓判定；如果一直强势不回踩，按本套规则就不会触发买入，这是预期内的。",
                    quote.price, waterLine);
            return result;
        }

        if (vwap <= 0) {
            result.note = minutePoints == null || minutePoints.isEmpty()
                    ? "分时数据缺失且无法从行情总量/总额倒推VWAP，继续观察"
                    : "VWAP无效，继续观察";
            return result;
        }

        if (quote.price < vwap) {
            result.note = String.format(Locale.CHINA,
                    "水下观察中：现价¥%.2f < VWAP¥%.2f，尚未站上分时均价，一股都不能买",
                    quote.price, vwap);
            return result;
        }

        int aboveMinutes = countConsecutiveAboveVwap(minutePoints);
        if (aboveMinutes < mCfg.vwapConfirmMinutes) {
            result.note = String.format(Locale.CHINA,
                    "现价¥%.2f已高于VWAP¥%.2f，但仅持续%d分钟（需≥%d分钟确认），继续观察",
                    quote.price, vwap, aboveMinutes, mCfg.vwapConfirmMinutes);
            return result;
        }

        if (!vol.confirmed) {
            result.note = String.format(Locale.CHINA,
                    "水下+站上VWAP已满足，但%s，暂不触发底仓信号（日量比%.2fx／近5分钟量比%.2fx，需达到%.2fx）",
                    vol.shrinkBreak ? "缩量突破，需等待放量确认" : "量比未达阈值",
                    vol.dayRatio, vol.recent5Ratio, vol.threshold);
            return result;
        }

        result.action = Action.BUY_STARTER;
        result.actionLabel = "建议底仓";
        result.triggerPrice = quote.price;
        result.note = String.format(Locale.CHINA,
                "【%s】水下(¥%.2f<水线¥%.2f)+站上VWAP(¥%.2f≥¥%.2f，持续%d分钟)+放量确认(%s)",
                result.actionLabel, quote.price, waterLine, quote.price, vwap, aboveMinutes, vol.detail);
        return result;
    }

    // ══════════════════════════════════════════
    // 加仓50%
    // ══════════════════════════════════════════

    private RuleResult evaluateAddHalf(RealtimeQuoteManager.Quote quote, PrevDayRef prevDay,
                                        List<RealtimeQuoteManager.MinutePoint> minutePoints,
                                        VolumeCheck vol, double vwap, double waterLine) {
        RuleResult result = new RuleResult();
        boolean breakWater = quote.price > waterLine;
        boolean pullbackHold = false;

        if (!breakWater && minutePoints != null && vwap > 0) {
            // 底仓后回踩VWAP不破：最近若干分钟低点≥VWAP*(1-0.001)
            int n = minutePoints.size();
            int look = Math.min(10, n);
            double minRecent = Double.MAX_VALUE;
            for (int i = n - look; i < n; i++) {
                if (minutePoints.get(i).price < minRecent) minRecent = minutePoints.get(i).price;
            }
            pullbackHold = minRecent >= vwap * 0.999 && quote.price >= vwap;
        }

        if (!breakWater && !pullbackHold) {
            return result;
        }

        if (!vol.confirmed) {
            result.note = String.format(Locale.CHINA,
                    "%s但量比未确认(%s)，暂不触发加仓",
                    breakWater ? "已突破水线" : "回踩VWAP不破", vol.detail);
            return result;
        }

        result.action = Action.ADD_HALF;
        result.actionLabel = "建议增加50%仓位";
        result.triggerPrice = quote.price;
        result.note = String.format(Locale.CHINA,
                "【%s】%s，现价¥%.2f 水线¥%.2f VWAP¥%.2f，放量确认(%s)",
                result.actionLabel,
                breakWater ? "突破水线(昨收)" : "底仓后回踩分时均价不破",
                quote.price, waterLine, vwap, vol.detail);
        return result;
    }

    // ══════════════════════════════════════════
    // 满仓
    // ══════════════════════════════════════════

    /**
     * 【修复】之前这里用 MarketDataManager.getCachedKline(code,3) 取"最新缓存日"当作仙人指路形态日来算
     * 上影线——但股票入池后可能在 WATCHING 状态躺好几天才等到条件成立，或中途又重新跑过盘后下载，
     * “最新缓存日”早就不是当初真正出现长上影线的那一天了，影线长度会算错。
     * 现改为读入池时固化下来的 PatternRef（真正的形态日OHLC），没存过（比如手动持仓同步进来的票）
     * 就直接跳过满仓判断，不拿错误的天硬算。
     */
    private RuleResult evaluateFullPosition(RealtimeQuoteManager.Quote quote,
                                             PatternRef pattern,
                                             List<RealtimeQuoteManager.MinutePoint> minutePoints,
                                             VolumeCheck vol, double vwap, double waterLine,
                                             int hour, int minute) {
        RuleResult result = new RuleResult();
        if (pattern == null || !pattern.hasData) return result; // 无形态日参考（非选股器来源，如手动持仓），不判断满仓

        double bodyTop = Math.max(pattern.open, pattern.close);
        double shadowTop = pattern.high;
        double shadowLen = shadowTop - bodyTop;
        if (shadowLen <= 0) return result;

        double eaten = (quote.price - bodyTop) / shadowLen;
        if (eaten < mCfg.shadowEatRatio) return result;

        if (quote.price <= waterLine) return result;
        if (vwap > 0 && quote.price < vwap) return result;
        if (!vol.confirmed) return result;

        // 【修复】之前是 Math.min(vwapConfirmMinutes, fullConfirmMinutes)，默认配置下恒等于 min(5,45)=5，
        // 导致 fullConfirmMinutes(45分钟) 从未真正生效——满仓和底仓用的是同一条 5分钟确认线。
        // 现改为 Math.max(...)，让确认时长真正拉到45分钟，符合文档里
        // “满仓渐进确认”的精神。注意：这是一处行为变化——满仓信号会比修复前更难触发（需持续站稳
        // VWAP上默认45分钟而非5分钟），但更符合文档“资金安全第一”的原则。
        int aboveMinutes = countConsecutiveAboveVwap(minutePoints);
        if (aboveMinutes < Math.max(mCfg.vwapConfirmMinutes, mCfg.fullConfirmMinutes)) return result;

        result.action = Action.BUY_FULL;
        result.actionLabel = "建议满仓";
        result.triggerPrice = quote.price;
        result.notifyImmediate = true;
        result.note = String.format(Locale.CHINA,
                "【%s】当日吃掉上影线%.0f%%(阈值%.0f%%，形态日%s)+突破水线¥%.2f+站上VWAP¥%.2f+放量(%s)，全部技术条件满足",
                result.actionLabel, eaten * 100, mCfg.shadowEatRatio * 100, pattern.date, waterLine, vwap, vol.detail);
        return result;
    }

    // ══════════════════════════════════════════
    // 止损三级 + 抛压
    // ══════════════════════════════════════════

    private RuleResult evaluateStopLoss(RealtimeQuoteManager.Quote quote, PrevDayRef prevDay,
                                         VolumeCheck vol, DivergenceState state,
                                         int hour, int minute) {
        RuleResult result = new RuleResult();
        double mid = computeStopMid(prevDay, state);
        double divLow = state.divKLow;
        double yangLow = state.prevYangLow;

        // 独立破位：前一根阳线最低价
        if (yangLow > 0 && quote.price < yangLow) {
            if (vol.shrinkBreak && !vol.confirmed) {
                return buildWarnPressure(quote, prevDay, state,
                        String.format(Locale.CHINA, "缩量跌破前阳低¥%.2f，降级为抛压观察", yangLow));
            }
            return buildStopLoss(quote, StopLevel.YANG_BREAK, yangLow,
                    String.format(Locale.CHINA,
                            "【建议立即清仓止损】跌破前一根阳线最低价¥%.2f（独立破位规则），现价¥%.2f，%s",
                            yangLow, quote.price, vol.detail),
                    true, state);
        }

        // 三级：分歧K线最低点
        if (divLow > 0 && quote.price < divLow) {
            return buildStopLoss(quote, StopLevel.LEVEL3_LOW, divLow,
                    String.format(Locale.CHINA,
                            "【建议立即清仓止损】跌破分歧K线最低点¥%.2f（最终警示线），现价¥%.2f",
                            divLow, quote.price),
                    true, state);
        }

        // 二级：分歧K线中点
        if (mid > 0 && quote.price < mid) {
            boolean inWindow = isStopNotifyWindow(hour, minute);
            if (vol.shrinkBreak && !vol.confirmed) {
                return buildWarnPressure(quote, prevDay, state,
                        String.format(Locale.CHINA, "缩量跌破分歧中点¥%.2f，先观察%d分钟", mid, mCfg.sellObserveMinutes));
            }
            RuleResult r = buildStopLoss(quote, StopLevel.LEVEL2_MID, mid,
                    String.format(Locale.CHINA,
                            "【建议立即清仓止损】跌破分歧K线中点¥%.2f(%s)，现价¥%.2f，%s",
                            mid, mCfg.divergenceMidMode, quote.price, vol.detail),
                    false, state);
            r.notifyImmediate = inWindow;
            if (!inWindow) {
                r.note += String.format(Locale.CHINA, "（已达离场标准，将在收盘前%d分钟推送提醒）", mCfg.stopNotifyMinutesBeforeClose);
            }
            return r;
        }

        // 一级：峰值涨幅回撤50%
        if (state.peakGainPct > 0.5) {
            double peakPrice = prevDay.prevClose * (1 + state.peakGainPct / 100.0);
            double currentGain = (quote.price - prevDay.prevClose) / prevDay.prevClose * 100;
            double retraceLevel = state.peakGainPct * mCfg.peakRetraceRatio;
            if (currentGain <= retraceLevel && currentGain < state.peakGainPct - 0.3) {
                return buildWarnPressure(quote, prevDay, state,
                        String.format(Locale.CHINA,
                                "【建议抛压】当日峰值涨幅%.2f%%回撤至%.2f%%（回撤阈值50%%），需密切关注",
                                state.peakGainPct, currentGain));
            }
        }

        return result;
    }

    private RuleResult buildStopLoss(RealtimeQuoteManager.Quote quote, StopLevel level, double levelPrice,
                                      String note, boolean immediate, DivergenceState state) {
        RuleResult r = new RuleResult();
        r.action = Action.STOP_LOSS;
        r.actionLabel = "建议立即清仓止损";
        r.stopLevel = level;
        r.triggerPrice = quote.price;
        r.note = note;
        r.notifyImmediate = immediate;
        r.stateUpdate = state;
        r.metrics = String.format(Locale.CHINA, "止损位¥%.2f 级别=%s", levelPrice, level.name());
        return r;
    }

    private RuleResult buildWarnPressure(RealtimeQuoteManager.Quote quote, PrevDayRef prevDay,
                                          DivergenceState state, String note) {
        RuleResult r = new RuleResult();
        r.action = Action.WARN_PRESSURE;
        r.actionLabel = "建议抛压";
        r.stopLevel = StopLevel.LEVEL1_WARN;
        r.triggerPrice = quote.price;
        r.note = note;
        r.notifyImmediate = true;
        r.stateUpdate = state;
        return r;
    }

    // ══════════════════════════════════════════
    // 工具方法
    // ══════════════════════════════════════════

    private boolean isHolding(String status) {
        return WatchlistManager.STATUS_STARTER.equals(status)
                || WatchlistManager.STATUS_ADDED.equals(status)
                || WatchlistManager.STATUS_FULL.equals(status);
    }

    /** 今天的日历日期字符串，用于判断状态是否跨交易日（peakGainPct重置）以及实时分歧K线的日期标记 */
    private static String todayStr() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    private double computeVwap(List<RealtimeQuoteManager.MinutePoint> points, RealtimeQuoteManager.Quote quote) {
        if (points != null && !points.isEmpty()) {
            RealtimeQuoteManager.MinutePoint last = points.get(points.size() - 1);
            if (last.avgPrice > 0) return last.avgPrice;
        }
        if (quote != null && quote.amount > 0 && quote.volume > 0) {
            return quote.amount / (quote.volume * 100.0);
        }
        return 0;
    }

    private int countConsecutiveAboveVwap(List<RealtimeQuoteManager.MinutePoint> points) {
        if (points == null || points.isEmpty()) return 0;
        int count = 0;
        for (int i = points.size() - 1; i >= 0; i--) {
            RealtimeQuoteManager.MinutePoint p = points.get(i);
            if (p.avgPrice > 0 && p.price >= p.avgPrice) count++;
            else break;
        }
        return count;
    }

    VolumeCheck checkVolume(String code, RealtimeQuoteManager.Quote quote,
                             List<RealtimeQuoteManager.MinutePoint> points, int hour, int minute) {
        VolumeCheck v = new VolumeCheck();
        v.threshold = mCfg.effectiveVolumeThreshold(hour, minute);

        try {
            List<MarketDataManager.KlineBar> bars = MarketDataManager.get().getCachedKline(code, mCfg.volumeMaDays + 2);
            long sum = 0;
            int cnt = 0;
            for (int i = Math.max(0, bars.size() - mCfg.volumeMaDays - 1); i < bars.size() - 1; i++) {
                sum += bars.get(i).volume;
                cnt++;
            }
            long avgVol = cnt > 0 ? sum / cnt : 0;
            long todayVol = quote.volume > 0 ? quote.volume : 0;
            v.dayRatio = avgVol > 0 ? (double) todayVol / avgVol : 0;

            if (points != null && points.size() >= 5) {
                long recent5 = 0;
                for (int i = points.size() - 5; i < points.size(); i++) recent5 += points.get(i).volume;
                double avgMin = points.size() > 0 ? (double) todayVol / points.size() : 0;
                v.recent5Ratio = avgMin > 0 ? recent5 / (avgMin * 5) : 0;
            }

            v.confirmed = v.dayRatio >= v.threshold || v.recent5Ratio >= v.threshold;
            v.shrinkBreak = v.dayRatio < v.threshold * 0.6 && v.recent5Ratio < v.threshold * 0.6;
            v.detail = String.format(Locale.CHINA, "日量比%.2fx 近5分钟量比%.2fx 阈值%.2fx", v.dayRatio, v.recent5Ratio, v.threshold);
        } catch (Exception e) {
            v.detail = "量比计算失败";
        }
        return v;
    }

    /**
     * 涨跌停价 + 疑似封板检测（8.5节"涨跌停冻结"的实时监控版）。
     * 涨跌停幅度按板块区分：主板/ST 10%，创业板(300/301)、科创板(688) 20%，北交所(8/4开头) 30%（阈值可在 trading_rules.json 调整）。
     */
    LimitInfo computeLimitInfo(String code, RealtimeQuoteManager.Quote quote,
                                List<RealtimeQuoteManager.MinutePoint> points, double prevClose) {
        LimitInfo li = new LimitInfo();
        if (quote == null || prevClose <= 0) return li;
        double pct = limitPctForCode(code);
        li.upPrice = roundTick(prevClose * (1 + pct));
        li.downPrice = roundTick(prevClose * (1 - pct));
        double tol = 0.011; // 容忍取整/浮点误差（约1分钱）
        li.atUp = quote.price >= li.upPrice - tol;
        li.atDown = quote.price <= li.downPrice + tol;
        if (!li.atUp && !li.atDown) return li;

        boolean oneWordBoard = Math.abs(quote.open - quote.price) < 0.005
                && Math.abs(quote.high - quote.low) < 0.005;
        boolean thinRecent = false;
        if (points != null && points.size() >= 5) {
            long peakMinuteVol = 0;
            for (RealtimeQuoteManager.MinutePoint p : points) peakMinuteVol = Math.max(peakMinuteVol, p.volume);
            int look = Math.min(5, points.size());
            long recentSum = 0;
            for (int k = points.size() - look; k < points.size(); k++) recentSum += points.get(k).volume;
            double recentAvg = recentSum / (double) look;
            thinRecent = peakMinuteVol > 0 && recentAvg < peakMinuteVol * 0.05;
        }
        li.likelyLocked = oneWordBoard || thinRecent;
        return li;
    }

    private double limitPctForCode(String code) {
        if (code == null) return mCfg.mainboardLimitPct;
        if (code.startsWith("300") || code.startsWith("301") || code.startsWith("688")) return mCfg.gemStarLimitPct;
        if (code.startsWith("8") || code.startsWith("4")) return mCfg.bjExchangeLimitPct;
        return mCfg.mainboardLimitPct;
    }

    private double roundTick(double p) {
        return Math.round(p * 100) / 100.0;
    }

    /**
     * 给可执行动作追加涨跌停/T+1提示文案，只做标注不改变规则本身是否触发。
     * 买入类信号统一提醒T+1锁仓；触及涨跌停且疑似封板时额外提示可能无法成交。
     */
    private void annotateLimitAndHoldingPeriod(RuleResult r, LimitInfo li) {
        if (r == null || r.action == Action.NONE || li == null) return;
        boolean isBuySide = r.action == Action.BUY_STARTER || r.action == Action.ADD_HALF || r.action == Action.BUY_FULL;
        boolean isSellSide = r.action == Action.STOP_LOSS || r.action == Action.WARN_PRESSURE;

        if (isBuySide) {
            r.note += "（成交后该部分份额T+1前不可卖，下一交易日起解锁）";
            if (li.atUp) {
                r.note += li.likelyLocked
                        ? String.format(Locale.CHINA, "；现价已触及涨停¥%.2f疑似封板缺乏对手盘，可能无法实际买入，请以真实盘口为准", li.upPrice)
                        : String.format(Locale.CHINA, "；现价已触及涨停¥%.2f，注意排队风险", li.upPrice);
            }
        } else if (isSellSide && li.atDown) {
            r.limitLocked = li.likelyLocked;
            r.note += li.likelyLocked
                    ? String.format(Locale.CHINA, "；现价已触及跌停¥%.2f疑似封板缺乏对手盘，若无法成交将顺延至下一交易日开盘执行", li.downPrice)
                    : String.format(Locale.CHINA, "；现价已触及跌停¥%.2f，卖出可能有滑点或排队，请留意盘口", li.downPrice);
        }
    }

    private void updateIntradayPeak(RealtimeQuoteManager.Quote quote, PrevDayRef prevDay, DivergenceState state) {
        if (prevDay.prevClose <= 0) return;
        String today = todayStr();
        // 【修复】peakGainPct 之前只会单调递增且从不重置，导致跨交易日后"当日峰值涨幅"其实用的是
        // 好几天前的旧峰值——现在先比对记录的 peakGainDate 是否就是今天，不是就先清零重新累计，
        // 真正对齐文档“当日涨幅回撤至峰值涨幅的50%”这个“当日”语义。
        if (!today.equals(state.peakGainDate)) {
            state.peakGainPct = 0;
            state.peakGainDate = today;
        }
        double gain = (quote.high > 0 ? quote.high : quote.price) - prevDay.prevClose;
        double gainPct = gain / prevDay.prevClose * 100;
        if (gainPct > state.peakGainPct) state.peakGainPct = gainPct;
    }

    /**
     * 实时识别分歧K线（持仓期间放量+滞涨/长上影）。
     *
     * 【修复】之前这里用 MarketDataManager.getCachedKline(code,2) 取"今日"行情——但那个缓存是
     * 盘后手动下载才会更新的静态日K线，盘中轮询不会实时刷新（RealtimeMonitorService的tick循环
     * 从不调用下载方法），所以盘中取到的"最新缓存日"实际上永远是昨天（或上次下载时）的已收盘
     * K线，不是今天正在走的行情——分歧K线识别就永远慢一天，止损参照位也跟着错。
     * 现改为直接用 quote 的实时 open/high/low/price重构今天这根还在形成中的日K，才能真正在盘中实时
     * 识别到文档 1.4/5.3 要求的"上涨途中再度放量、收十字星"，而不是进入下一交易日才后知后觉发现。
     */
    private void detectDivergenceKline(String code, RealtimeQuoteManager.Quote quote,
                                        PrevDayRef prevDay, VolumeCheck vol, DivergenceState state) {
        if (!vol.confirmed) return;
        if (quote == null || quote.high <= 0 || quote.low <= 0 || quote.open <= 0) return;
        double range = quote.high - quote.low;
        if (range <= 0) return;
        double body = Math.abs(quote.price - quote.open);
        boolean stall = body / range <= mCfg.divergenceBodyMaxRatio;
        boolean upperShadow = (quote.high - Math.max(quote.open, quote.price)) / range > 0.35;
        if (!stall && !upperShadow) return;

        state.divKHigh = quote.high;
        state.divKLow = quote.low;
        state.divMidKline = (state.divKHigh + state.divKLow) / 2.0;
        state.divMidRetrace = prevDay.prevClose * (1 + state.peakGainPct * mCfg.peakRetraceRatio / 100.0);
        state.divKDate = todayStr();
    }

    private void updatePrevYangLow(String code, DivergenceState state) {
        // 【修复】起点原来是 bars.size()-2，会跳过缓存里最新的一天。但盘中监控时这个
        // 最新缓存日实际上永远是水线那天（昨天），不是今天，如果水线那天本身是阳线，
        // 它应该是最贴近“前一根阳线”定义、最应该被选中的那一根，之前的写法会把它跳过去找
        // 更早的一根，导致止损参照价比应有的更低、止损触发得更晚。现改为从 bars.size()-1（缓存
        // 最新一天）开始往前找。
        List<MarketDataManager.KlineBar> bars = MarketDataManager.get().getCachedKline(code, 10);
        for (int i = bars.size() - 1; i >= 0; i--) {
            MarketDataManager.KlineBar b = bars.get(i);
            if (b.close > b.open) {
                state.prevYangLow = b.low;
                return;
            }
        }
    }

    private double computeStopMid(PrevDayRef prevDay, DivergenceState state) {
        if ("RETRACE_MID".equals(mCfg.divergenceMidMode) && state.divMidRetrace > 0) {
            return state.divMidRetrace;
        }
        if (state.divMidKline > 0) return state.divMidKline;
        return state.divMidRetrace;
    }

    boolean isStopNotifyWindow(int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, mCfg.marketCloseHour);
        cal.set(Calendar.MINUTE, mCfg.marketCloseMinute);
        cal.set(Calendar.SECOND, 0);
        long closeMs = cal.getTimeInMillis();
        long nowMs = System.currentTimeMillis();
        long windowMs = mCfg.stopNotifyMinutesBeforeClose * 60_000L;
        return closeMs - nowMs <= windowMs && nowMs <= closeMs;
    }

    private DivergenceState copyState(DivergenceState s) {
        DivergenceState c = new DivergenceState();
        if (s == null) return c;
        c.divKHigh = s.divKHigh;
        c.divKLow = s.divKLow;
        c.divMidKline = s.divMidKline;
        c.divMidRetrace = s.divMidRetrace;
        c.prevYangLow = s.prevYangLow;
        c.peakGainPct = s.peakGainPct;
        c.divKDate = s.divKDate;
        c.peakGainDate = s.peakGainDate;
        return c;
    }

    public static String actionToKey(Action action) {
        switch (action) {
            case BUY_STARTER: return "BUY_STARTER";
            case ADD_HALF: return "ADD_HALF";
            case BUY_FULL: return "BUY_FULL";
            case WARN_PRESSURE: return "WARN_PRESSURE";
            case STOP_LOSS: return "STOP_LOSS";
            default: return "NONE";
        }
    }
}
