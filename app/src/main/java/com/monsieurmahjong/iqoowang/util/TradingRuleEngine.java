package com.monsieurmahjong.iqoowang.util;

import android.util.Log;

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

    private static final String TAG = "TradingRuleEngine";

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
        /** 【R3】本轮是否刚从“未监听”转为“重点监听”（即NONE→WATCHING这一次转变），
         *  RealtimeMonitorService据此额外推送一条轻量通知，不占用PENDING确认状态位 */
        public boolean focusWatchJustEntered = false;
    }

    public static class DivergenceState {
        public double divKHigh, divKLow, divMidKline, divMidRetrace, prevYangLow, peakGainPct;
        public String divKDate;
        /** peakGainPct 是按哪个交易日累计的——评估时若和"今天"不一致，说明跨天了，
         *  peakGainPct 要先清零重算，否则"当日峰值涨幅"会变成好几天前的旧值（修复用） */
        public String peakGainDate;
        /** 【2026-09-10新增·R3】高开/平开路径“重点监听”开始时间戳（0＝未在监听），
         *  与状态 NONE/WATCHING/CONFIRMED，见 evaluateStarterGapUpOrFlat() 注释 */
        public long focusWatchStartTime;
        public String focusWatchStatus = "NONE";
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
        /** 【2026-08-20新增】昨日全天真实VWAP（成交量加权均价，来自RealtimeQuoteManager.
         *  fetchPrevDayVwap()异步获取），不是近似值。<=0表示还没抓到（App刚重启或刚换新的一天，
         *  异步请求还在路上），调用方（低开底仓路径）此时应跳过本轮判断，等下一轮tick自动重试。 */
        public double prevAvgPrice;
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

            // 【2026-08-20新增】昨日全天真实VWAP——有缓存且日期对得上（同一个"昨日"）就直接用；
            // 没有或者日期对不上（App刚重启、或者刚跨入新交易日还没抓过）就先给0，调用方
            // （低开底仓路径）要自己处理"暂时没有，先跳过本轮评估"，同时这里顺手在后台异步
            // 补抓一次，抓到后存进缓存，下一轮tick（60~120秒后）自然就有了，不会一直卡住。
            Double cachedAvg = WatchlistManager.get().getPrevDayVwapIfMatches(code, ref.prevDate);
            if (cachedAvg != null) {
                ref.prevAvgPrice = cachedAvg;
            } else {
                ref.prevAvgPrice = 0;
                RealtimeQuoteManager.get().fetchPrevDayVwap(code, (c, vwap, date) -> {
                    if (vwap > 0 && date != null) {
                        WatchlistManager.get().savePrevDayVwap(c, vwap, date);
                        Log.i(TAG, "已获取" + c + "昨日(" + date + ")真实VWAP=" + String.format(Locale.CHINA, "%.4f", vwap));
                        try {
                            DecisionLogger.get().logPrevDayVwapFetch(c, true, vwap, date, null);
                        } catch (Exception ignored) {}
                    } else {
                        Log.w(TAG, "获取" + c + "昨日真实VWAP失败，本轮低开路径底仓判断将跳过");
                        try {
                            DecisionLogger.get().logPrevDayVwapFetch(c, false, 0, null, "接口请求或解析失败");
                        } catch (Exception ignored) {}
                    }
                });
            }
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
            RuleResult stop = evaluateStopLoss(quote, prevDay, vol, state, pattern, hour, minute);
            if (stop.action != Action.NONE) {
                stop.metrics = result.metrics + " | " + stop.metrics;
                stop.stateUpdate = state;
                stop.waterLine = waterLine; stop.vwap = vwap; stop.volRatio = vol.dayRatio;
                annotateLimitAndHoldingPeriod(stop, limitInfo);
                return stop;
            }
        }

        // ── 满仓条件（优先级高于普通加仓）──
        // 【R8】fullSkipNote保存"为什么没有触发满仓"的具体原因（哪怕本轮不适用满仓判断也会有一句说明），
        // 供下面STARTER/ADDED分支拼进最终提示里，不再像之前那样直接丢弃evaluateFullPosition的note
        String fullSkipNote = null;
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
            fullSkipNote = full.note;
        }

        // ── 加仓：突破水线 或 底仓后回踩VWAP不破 ──
        if (WatchlistManager.STATUS_STARTER.equals(status)) {
            RuleResult add = evaluateAddHalf(quote, prevDay, minutePoints, vol, vwap, waterLine, state);
            if (add.action != Action.NONE) {
                add.metrics = result.metrics;
                add.stateUpdate = state;
                add.waterLine = waterLine; add.vwap = vwap; add.volRatio = vol.dayRatio;
                annotateLimitAndHoldingPeriod(add, limitInfo);
                return add;
            }
            // 【R8】之前这里只给"未满足加仓/满仓条件，继续观察"一句话，看不出具体差多少、
            // 卡在哪个条件上。现在把满仓/加仓两条路径各自的具体原因（evaluateFullPosition／
            // evaluateAddHalf内部已经带上了中间值）都拼进来，纯粹是日志明细增强，不改变
            // 任何一条已有的触发条件或判断顺序。
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.CHINA, "已持底仓，现价¥%.2f 水线¥%.2f VWAP¥%.2f，继续观察。",
                    quote.price, waterLine, vwap));
            if (fullSkipNote != null && !fullSkipNote.isEmpty()) sb.append(" [满仓]").append(fullSkipNote).append("。");
            if (add.note != null && !add.note.isEmpty()) sb.append(" [加仓]").append(add.note).append("。");
            result.note = sb.toString();
            result.stateUpdate = state;
            return result;
        }

        if (WatchlistManager.STATUS_ADDED.equals(status) || WatchlistManager.STATUS_FULL.equals(status)) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.CHINA, "已%s，现价¥%.2f VWAP¥%.2f，持续监控止损位。",
                    WatchlistManager.STATUS_FULL.equals(status) ? "满仓" : "加仓",
                    quote.price, vwap));
            // 【R8】ADDED状态下，满仓评估的具体差距一并带出；FULL已是终态，不再需要
            if (WatchlistManager.STATUS_ADDED.equals(status) && fullSkipNote != null && !fullSkipNote.isEmpty()) {
                sb.append(" [满仓]").append(fullSkipNote).append("。");
            }
            result.note = sb.toString();
            result.stateUpdate = state;
            return result;
        }

        // ── 观察中：水下+站上VWAP → 底仓 ──
        if (WatchlistManager.STATUS_WATCHING.equals(status)) {
            RuleResult starter = evaluateStarter(quote, prevDay, minutePoints, vol, vwap, waterLine, result.metrics, hour, minute, state);
            starter.stateUpdate = state; // 持久化观察期已累计的峰值涨幅/重点监听计时状态，避免转入底仓后状态从零重算
            starter.waterLine = waterLine; starter.vwap = vwap; starter.volRatio = vol.dayRatio;
            annotateLimitAndHoldingPeriod(starter, limitInfo);
            return starter;
        }

        return result;
    }

    // ══════════════════════════════════════════
    // 买入：底仓
    // ══════════════════════════════════════════

    /**
     * 【2026-08-20 买入逻辑改造】底仓判断——按今开 vs 昨收分成两条路径，不再是单一的
     * "水下(相对昨收)+站上今日VWAP+持续N分钟"。这是根据操盘手新材料重新梳理的：
     *   低开(今开<昨收，视为示弱)：等现价站上"昨日全天真实VWAP"（不是昨收，是真正的
     *   昨天成交量加权均价），站上即视为解套确认，不再额外要求持续分钟数。
     *   高开/平开(今开大于等于昨收，不要求现价低于任何东西)：只要求现价回踩到当日VWAP、
     *   不破，到了当日VWAP就算数——不再要求连续N分钟站稳，简化为即时判定。
     * 这条改动直接解决了"股票一直卡在候选池、永远等不到买入提示"的问题——原来的实现只有
     * "水下"这一条路径，一支高开/平开、一直在水线上方运行的强势股，不管它表现多好，
     * 永远没有路径能触发底仓，这是之前版本的一个真实缺口，不是市场行情本身导致的。
     */
    private RuleResult evaluateStarter(RealtimeQuoteManager.Quote quote, PrevDayRef prevDay,
                                        List<RealtimeQuoteManager.MinutePoint> minutePoints,
                                        VolumeCheck vol, double vwap, double waterLine, String metrics,
                                        int hour, int minute, DivergenceState state) {
        boolean gapDown = quote.open > 0 && waterLine > 0 && quote.open < waterLine;
        return gapDown
                ? evaluateStarterGapDown(quote, prevDay, vol, vwap, waterLine, metrics, hour, minute)
                : evaluateStarterGapUpOrFlat(quote, minutePoints, vol, vwap, waterLine, metrics, state);
    }

    /**
     * 低开路径：今开 < 昨收，视为示弱，严禁在"昨日均价下方"直接买入。等现价站上"昨日全天
     * 真实VWAP"（成交量加权均价，见RealtimeQuoteManager.fetchPrevDayVwap()注释）才打底仓——
     * 站上意味着昨日被套资金解套、抛压减轻。如果现价一直在昨日均价下方运行，说明大部分
     * 资金仍被套，坚决不介入，这是预期内的、不算bug。
     */
    private RuleResult evaluateStarterGapDown(RealtimeQuoteManager.Quote quote, PrevDayRef prevDay,
                                               VolumeCheck vol, double vwap, double waterLine, String metrics,
                                               int hour, int minute) {
        RuleResult result = new RuleResult();
        result.metrics = metrics;

        if (prevDay.prevAvgPrice <= 0) {
            result.note = "【低开路径】正在异步获取昨日全天真实均价数据，本轮暂不评估底仓，下一轮tick自动重试";
            return result;
        }

        int minsFromOpen = hour * 60 + minute - (9 * 60 + 30);
        boolean withinSwitchWindow = minsFromOpen >= 0 && minsFromOpen < mCfg.vwapDualRefSwitchMinutes;
        double ref;
        String refLabel;
        if (withinSwitchWindow || vwap <= 0) {
            ref = prevDay.prevAvgPrice;
            refLabel = String.format(Locale.CHINA, "昨日全天真实均价¥%.2f（开盘%d分钟内，当日VWAP尚不具统计意义）", ref, mCfg.vwapDualRefSwitchMinutes);
        } else if (prevDay.prevAvgPrice <= vwap) {
            ref = prevDay.prevAvgPrice;
            refLabel = String.format(Locale.CHINA, "昨日全天真实均价¥%.2f（低于当日VWAP¥%.2f，取较松的一个）", ref, vwap);
        } else {
            ref = vwap;
            refLabel = String.format(Locale.CHINA, "当日VWAP¥%.2f（低于昨日均价¥%.2f，取较松的一个）", ref, prevDay.prevAvgPrice);
        }

        if (quote.price < ref) {
            result.note = String.format(Locale.CHINA,
                    "【低开路径】今开¥%.2f<昨收¥%.2f，现价¥%.2f仍低于参照价%s，尚未站上，暂不介入",
                    quote.open, waterLine, quote.price, refLabel);
            return result;
        }

        // 【2026-09-13改造，用户已明确要求】取消放量确认这道门槛——用户判断这条限制过于保守，
        // 明确要求所有买入路径都不再以放量作为触发条件。vol.detail(量比数据)仍然保留
        // 在提示文案里，只是从必须达标才能买降级为仅供参考的背景信息，不再影响是否触发。

        result.action = Action.BUY_STARTER;
        result.actionLabel = "建议底仓";
        result.triggerPrice = quote.price;
        result.note = String.format(Locale.CHINA,
                "【%s·低开路径】今开¥%.2f<昨收¥%.2f(低开示弱)，现价¥%.2f已站上参照价%s(解套确认)，%s",
                result.actionLabel, quote.open, waterLine, quote.price, refLabel, vol.detail);
        try {
            DecisionLogger.get().logBuyLogicTrace("", quote.code, String.format(Locale.CHINA,
                    "低开路径命中底仓：今开¥%.2f 昨收¥%.2f 参照价%s 现价¥%.2f",
                    quote.open, waterLine, refLabel, quote.price));
        } catch (Exception ignored) {}
        return result;
    }

    /**
     * 高开/平开路径：今开大于等于昨收，不要求现价低于任何东西。
     * 【2026-09-10改造·R3，用户已在开发计划文档中确认按此方案实现】原先是瞬时判定（检查最近
     * 10分钟低点是否守住VWAP附近、当前价是否大于等于VWAP，命中即直接给底仓）。现改为"重点监听+
     * 计时确认"状态机，对应用户原文"该股票进行着重监听...大盘交易时间的1小时后（中午休盘不算
     * 在1小时内）如果还是能够稳定在分时均价，可以购入底仓，在大盘收盘前15分钟，如果不跌破分时
     * 均价，将进行加仓"：
     *   NONE → 每轮检查是否回踩当日VWAP不破，满足则转入WATCHING，记录开始时间戳，本轮不产生
     *          买卖信号，只推送一条不占用PENDING状态位的轻量通知（见RealtimeMonitorService.
     *          fireFocusWatchNotification，由result.focusWatchJustEntered触发）。
     *   WATCHING → 每轮重新检查"回踩不破"是否仍成立：不成立（现价跌破VWAP，对应用户原文"完全
     *          跌破是不可以购买的"）则重置回NONE，取消监听，不推送新通知；仍成立则计算剔除
     *          11:30-13:00午休后的累计交易分钟数，满focusWatchConfirmMinutes(默认60)分钟后检查放量
     *          确认（沿用本方法改造前就有的vol.confirmed门槛，非本次新增）后触发BUY_STARTER，
     *          focusWatchStatus转为CONFIRMED。
     * 注意：用户已拍板选择"先底仓后加仓"读法（而非D原实现"同一次信号两次确认机会"）：本方法只
     * 负责"1小时确认底仓"这一步，原来"或已到收盘前15分钟"那条备选路径已挑到evaluateAddHalf里，
     * 改为对已经是STARTER状态、且底仓正是今天靠这里1小时确认拿到的股票，检查是否要在收盘前触发
     * 加仓（ADD_HALF）——这是用户需求原文里两个先后独立的动作，而不是同一次买入信号的两次
     * 触发机会。如果一支股票进入重点监听时已经太晚、当天根本凑不满1小时实际交易时间就收盘了，
     * 这里不会有兵底触发——次日会经上面的跨日安全重置回NONE，从头开始判断，不强行在当天收盘前
     * 就给它一个"迟到的底仓"。
     */
    private RuleResult evaluateStarterGapUpOrFlat(RealtimeQuoteManager.Quote quote,
                                                   List<RealtimeQuoteManager.MinutePoint> minutePoints,
                                                   VolumeCheck vol, double vwap, double waterLine, String metrics,
                                                   DivergenceState state) {
        RuleResult result = new RuleResult();
        result.metrics = metrics;

        if (vwap <= 0) {
            result.note = "【高开/平开路径】VWAP数据无效，继续观察";
            return result;
        }

        // 跨日安全重置：focusWatchStartTime不是今天，说明是隔夜遗留状态（比如那天没到收盘前
        // 就没能确认、次日字段还没来得及清），先重置，不能带着昨天的计时误判今天
        if (!"NONE".equals(state.focusWatchStatus) && state.focusWatchStartTime > 0
                && !isSameDay(state.focusWatchStartTime, System.currentTimeMillis())) {
            state.focusWatchStatus = "NONE";
            state.focusWatchStartTime = 0;
        }

        // 【R8】minRecent/look提到if外面，即使不满足回踩条件也能在note里打印出来，
        // 不再只给"尚未回踩确认"这种看不出具体差多少的结论句
        double minRecent = Double.NaN;
        int look = 0;
        boolean pullbackHold = false;
        if (minutePoints != null && !minutePoints.isEmpty()) {
            int n = minutePoints.size();
            look = Math.min(10, n);
            minRecent = Double.MAX_VALUE;
            for (int i = n - look; i < n; i++) {
                if (minutePoints.get(i).price < minRecent) minRecent = minutePoints.get(i).price;
            }
            pullbackHold = minRecent >= vwap * 0.999 && quote.price >= vwap;
        }
        String minRecentText = (look > 0 && minRecent != Double.MAX_VALUE)
                ? String.format(Locale.CHINA, "¥%.2f", minRecent) : "无分时数据";

        if (!pullbackHold) {
            if ("WATCHING".equals(state.focusWatchStatus)) {
                // 【2026-09-13改造，用户已明确要求】取消这里原来"跌破就取消监听、清零计时"的做法——
                // 不再重置focusWatchStartTime，等下次又重新站稳VWAP时，之前累积的监听时长依然算数，
                // 不会因为某一tick的短暂回踩跌破而被清零重来。
                result.note = String.format(Locale.CHINA,
                        "【高开/平开路径】重点监听中，现价¥%.2f暂时低于VWAP¥%.2f，继续监听等待回到位（已累积的监听时长不受影响）",
                        quote.price, vwap);
            } else {
                result.note = String.format(Locale.CHINA,
                        "【高开/平开路径】今开大于等于昨收¥%.2f，现价¥%.2f VWAP¥%.2f(确认阈值¥%.2f)，最近%d个分时点最低价%s，尚未回踩确认到分时均价附近，继续观察",
                        waterLine, quote.price, vwap, vwap * 0.999, look, minRecentText);
            }
            return result;
        }

        // pullbackHold=true：已回踩不破
        if ("NONE".equals(state.focusWatchStatus)) {
            // 首次回踩确认，进入重点监听，开始计时，本轮不产生买卖信号
            state.focusWatchStatus = "WATCHING";
            state.focusWatchStartTime = System.currentTimeMillis();
            result.focusWatchJustEntered = true;
            result.note = String.format(Locale.CHINA,
                    "【高开/平开路径】今开大于等于昨收¥%.2f，现价¥%.2f回踩分时均价¥%.2f不破，进入重点监听并开始计时"
                            + "（需实际交易%d分钟确认底仓，确认后若持续到收盘前%d分钟仍未跌破将进一步触发加仓）",
                    waterLine, quote.price, vwap, mCfg.focusWatchConfirmMinutes, mCfg.gapUpAddConfirmMinutesBeforeClose);
            try {
                DecisionLogger.get().logBuyLogicTrace("", quote.code, String.format(Locale.CHINA,
                        "高开/平开路径进入重点监听：昨收¥%.2f 当日VWAP¥%.2f 现价¥%.2f", waterLine, vwap, quote.price));
            } catch (Exception ignored) {}
            return result;
        }

        // focusWatchStatus == WATCHING，已在计时中，只检查是否满至1小时实际交易时间确认条件。
        // 【R3改，用户已拍板"先底仓后加仓"读法】这里只保留"满 1小时"一个确认条件触发BUY_STARTER；
        // 原D实现里"或已到收盘前N分钟"那条路径已挑到evaluateAddHalf里，改为对已经是STARTER状态、
        // 且底仓正是今天靠这里的1小时确认拿到的股票，检查是否要在收盘前触发加仓（ADD_HALF）——这是
        // 用户需求原文"1小时后可以购入底仓，收盘前15分钟不跌破将进行加仓"里两个先后独立的动作，而不是
        // 同一次买入信号的两次触发机会。如果一支股票进入重点监听时已经太晚、当天根本凑不满1小时实际
        // 交易时间就收盘了，这里不会有兵底触发——次日会经上面的跨日安全重置回NONE，从头开始判断，
        // 不强行在当天收盘前就给它一个"迟到的底仓"。
        int elapsedMinutes = computeTradingMinutesExcludingLunch(state.focusWatchStartTime, System.currentTimeMillis());
        if (elapsedMinutes < mCfg.focusWatchConfirmMinutes) {
            result.note = String.format(Locale.CHINA,
                    "【高开/平开路径】重点监听中，已持续实际交易%d分钟（需%d分钟确认底仓），"
                            + "现价¥%.2f仍守住VWAP¥%.2f(最近%d个分时点最低价%s)，继续观察",
                    elapsedMinutes, mCfg.focusWatchConfirmMinutes,
                    quote.price, vwap, look, minRecentText);
            return result;
        }

        // 【2026-09-13改造，用户已明确要求】取消放量确认这道门槛。

        result.action = Action.BUY_STARTER;
        result.actionLabel = "建议底仓";
        result.triggerPrice = quote.price;
        state.focusWatchStatus = "CONFIRMED";
        result.note = String.format(Locale.CHINA,
                "【%s·高开/平开路径】今开大于等于昨收¥%.2f，重点监听已满%d分钟实际交易时间仍未跌破，现价¥%.2f VWAP¥%.2f，%s。"
                        + "若持续到收盘前%d分钟仍未跌破，将追加触发加仓",
                result.actionLabel, waterLine, mCfg.focusWatchConfirmMinutes, quote.price, vwap, vol.detail,
                mCfg.gapUpAddConfirmMinutesBeforeClose);
        try {
            DecisionLogger.get().logBuyLogicTrace("", quote.code, String.format(Locale.CHINA,
                    "高开/平开路径重点监听满%d分钟确认底仓：昨收¥%.2f 当日VWAP¥%.2f 现价¥%.2f",
                    mCfg.focusWatchConfirmMinutes, waterLine, vwap, quote.price));
        } catch (Exception ignored) {}
        return result;
    }

    // ══════════════════════════════════════════
    // 加仓50%
    // ══════════════════════════════════════════

    /**
     * 【2026-09-10改造·R2，用户已在开发计划文档中确认按此方案实现】"底仓后回踩VWAP不破"这条
     * 加仓路径的参照价原先只用当日vwap一个基准；现统一改为 max(昨日VWAP, 当日VWAP)——两者都要
     * 站上，取较高(更严格)的一个，对应A文档R2(b)"加仓统一要求价格≥max(昨日VWAP,当日VWAP)"。
     * 昨日VWAP若还没抓到(prevDay.prevAvgPrice<=0)，退化为只看当日VWAP，与改造前行为一致，
     * 不阻塞加仓判断。"突破水线(昨收)"这条独立路径完全不受影响。
     *
     * 【2026-09-10改造·R3，用户已拍板选择"先底仓后加仓"读法（而非D原实现"同一次信号两次
     * 确认机会"）】新增第三条独立触发路径：如果这支股票的底仓正是通过"重点监听1小时确认"拿到的
     * （state.focusWatchStatus=="CONFIRMED"，且确认发生在今天），到了收盘前
     * gapUpAddConfirmMinutesBeforeClose分钟仍未跌破VWAP，直接触发加仓——对应用户需求原文
     * "在大盘收盘前15分钟，如果不跌破分时均价，将进行加仓"里"加仓"二字的字面含义：这是紧接着
     * 1小时确认底仓之后的第二个独立动作，不是同一次买入信号的另一次触发机会。这条新路径与上面
     * 已有的breakWater/pullbackHold路径是"任一满足即可"的关系——不冲突：一旦本方法返回ADD_HALF，
     * 外层status就会变成ADDED，本方法不会再被调用，不存在同一支股票被重复触发的风险。
     */
    private RuleResult evaluateAddHalf(RealtimeQuoteManager.Quote quote, PrevDayRef prevDay,
                                        List<RealtimeQuoteManager.MinutePoint> minutePoints,
                                        VolumeCheck vol, double vwap, double waterLine,
                                        DivergenceState state) {
        RuleResult result = new RuleResult();
        boolean breakWater = quote.price > waterLine;
        boolean pullbackHold = false;
        // 【R8】跟evaluateStarterGapUpOrFlat同款处理：minRecent/look提到条件判断外面，
        // 不满足回踩条件时也能打印出来，不再只留一个空note
        double minRecent = Double.NaN;
        int look = 0;

        double addRef = vwap;
        String addRefLabel = String.format(Locale.CHINA, "当日VWAP¥%.2f", vwap);
        if (prevDay.prevAvgPrice > 0 && vwap > 0) {
            addRef = Math.max(prevDay.prevAvgPrice, vwap);
            addRefLabel = prevDay.prevAvgPrice >= vwap
                    ? String.format(Locale.CHINA, "昨日均价¥%.2f（高于当日VWAP¥%.2f，取较严格的一个）", addRef, vwap)
                    : String.format(Locale.CHINA, "当日VWAP¥%.2f（高于昨日均价¥%.2f，取较严格的一个）", addRef, prevDay.prevAvgPrice);
        }

        if (!breakWater && minutePoints != null && addRef > 0) {
            // 底仓后回踩参照价不破：最近若干分钟低点≥参照价*(1-0.001)
            int n = minutePoints.size();
            look = Math.min(10, n);
            minRecent = Double.MAX_VALUE;
            for (int i = n - look; i < n; i++) {
                if (minutePoints.get(i).price < minRecent) minRecent = minutePoints.get(i).price;
            }
            pullbackHold = minRecent >= addRef * 0.999 && quote.price >= addRef;
        }

        // 【R3】重点监听毕业加仓：底仓是今天靠"1小时确认"拿到的，到了收盘前窗口仍未跌破VWAP，
        // 独立触发一次加仓——即使上面的breakWater/pullbackHold都不成立也照样触发，这是用户原文
        // 明确要求的第二个动作，不依赖R2那套通用加仓判断。isSameDay守卫防止"隔了好几天的旧
        // CONFIRMED状态"在未来某天的收盘前被误判成刚毕业。
        boolean focusWatchGraduation = "CONFIRMED".equals(state.focusWatchStatus)
                && state.focusWatchStartTime > 0
                && isSameDay(state.focusWatchStartTime, System.currentTimeMillis())
                && isStopNotifyWindowMinutes(mCfg.gapUpAddConfirmMinutesBeforeClose)
                && vwap > 0 && quote.price >= vwap;

        if (!breakWater && !pullbackHold && !focusWatchGraduation) {
            // 【R8】之前这里直接return空note，外层evaluate()只能拼出"未满足加仓/满仓条件，
            // 继续观察"这种看不出具体差多少的话。现在把水线/参照价/minRecent这几个中间值打出来，
            // 外层evaluate()会把这条note拼进最终提示里
            String minRecentText = (look > 0 && minRecent != Double.MAX_VALUE)
                    ? String.format(Locale.CHINA, "¥%.2f", minRecent) : "无分时数据";
            result.note = String.format(Locale.CHINA,
                    "未突破水线¥%.2f(现价¥%.2f)，且未回踩参照价%s不破(最近%d个分时点最低价%s)",
                    waterLine, quote.price, addRefLabel, look, minRecentText);
            return result;
        }

        // 【2026-09-13改造，用户已明确要求】取消放量确认这道门槛。

        result.action = Action.ADD_HALF;
        result.actionLabel = "建议增加50%仓位";
        result.triggerPrice = quote.price;
        String triggerDesc = breakWater ? "突破水线(昨收)"
                : (pullbackHold ? "底仓后回踩双VWAP参照不破"
                : "重点监听确认底仓后，已到收盘前" + mCfg.gapUpAddConfirmMinutesBeforeClose + "分钟仍未跌破VWAP");
        result.note = String.format(Locale.CHINA,
                "【%s】%s，现价¥%.2f 水线¥%.2f 参照价%s，%s",
                result.actionLabel, triggerDesc, quote.price, waterLine, addRefLabel, vol.detail);
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
     *
     * 【R8】以下6个提前return分支原来全部是空note——满仓一共6道关卡（形态日数据/影线长度/吃影线
     * 比例/水线/VWAP/放量/站稳时长），之前哪一道没过都看不出来，外层evaluate()也只能拼一句笼统的
     * "未满足加仓/满仓条件"。现在每道关卡没过都给出具体差距，纯粹增加日志明细，6道关卡的判断顺序、
     * 阈值、触发条件本身一律不变。
     */
    private RuleResult evaluateFullPosition(RealtimeQuoteManager.Quote quote,
                                             PatternRef pattern,
                                             List<RealtimeQuoteManager.MinutePoint> minutePoints,
                                             VolumeCheck vol, double vwap, double waterLine,
                                             int hour, int minute) {
        RuleResult result = new RuleResult();
        if (pattern == null || !pattern.hasData) {
            result.note = "无形态日参考（非选股器来源，如手动同步进来的持仓），不参与满仓判断，只按止损位监控";
            return result;
        }

        double bodyTop = Math.max(pattern.open, pattern.close);
        double shadowTop = pattern.high;
        double shadowLen = shadowTop - bodyTop;
        if (shadowLen <= 0) {
            result.note = String.format(Locale.CHINA,
                    "形态日(%s)开%.2f/收%.2f/高%.2f没有上影线(shadowLen=%.2f≤0)，不参与满仓判断",
                    pattern.date, pattern.open, pattern.close, pattern.high, shadowLen);
            return result;
        }

        double eaten = (quote.price - bodyTop) / shadowLen;
        if (eaten < mCfg.shadowEatRatio) {
            result.note = String.format(Locale.CHINA,
                    "现价¥%.2f仅吃掉形态日(%s)上影线%.0f%%，未达满仓阈值%.0f%%（形态日开%.2f/收%.2f/高%.2f）",
                    quote.price, pattern.date, eaten * 100, mCfg.shadowEatRatio * 100, pattern.open, pattern.close, pattern.high);
            return result;
        }

        if (quote.price <= waterLine) {
            result.note = String.format(Locale.CHINA,
                    "已吃掉上影线%.0f%%达标，但现价¥%.2f未突破水线¥%.2f，不参与满仓判断", eaten * 100, quote.price, waterLine);
            return result;
        }
        if (vwap > 0 && quote.price < vwap) {
            result.note = String.format(Locale.CHINA,
                    "已吃掉上影线%.0f%%达标且突破水线¥%.2f，但现价¥%.2f未站上VWAP¥%.2f，不参与满仓判断",
                    eaten * 100, waterLine, quote.price, vwap);
            return result;
        }
        // 【2026-09-13改造，用户已明确要求】取消放量确认这道门槛。

        // 【修复】之前是 Math.min(vwapConfirmMinutes, fullConfirmMinutes)，默认配置下恒等于 min(5,45)=5，
        // 导致 fullConfirmMinutes(45分钟) 从未真正生效——满仓和底仓用的是同一条 5分钟确认线。
        // 现改为 Math.max(...)，让确认时长真正拉到45分钟，符合文档里
        // “满仓渐进确认”的精神。注意：这是一处行为变化——满仓信号会比修复前更难触发（需持续站稳
        // VWAP上默认45分钟而非5分钟），但更符合文档“资金安全第一”的原则。
        int aboveMinutes = countConsecutiveAboveVwap(minutePoints);
        int requiredMinutes = Math.max(mCfg.vwapConfirmMinutes, mCfg.fullConfirmMinutes);
        if (aboveMinutes < requiredMinutes) {
            result.note = String.format(Locale.CHINA,
                    "技术条件（吃影线%.0f%%/水线/VWAP/放量）均已达标，正在累计站稳VWAP时长：已持续%d分钟，需满%d分钟才确认满仓",
                    eaten * 100, aboveMinutes, requiredMinutes);
            return result;
        }

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
                                         PatternRef pattern, int hour, int minute) {
        RuleResult result = new RuleResult();
        double mid = computeStopMid(prevDay, state);
        double divLow = state.divKLow;
        // 【2026-09-08改造·R4方案C，用户已在开发计划文档中确认】独立破位止损参照价改成
        // max(形态日最低价, 前一交易日最低价)——取两者中较高(更严格)的一个。之前(2026-08-20
        // 方案B)只用形态日固定最低价，能避免长期观察/持仓导致止损线漂移，但用户反馈的原始诉求
        // 一直是"跌破昨天最低价就该有反应"，单用形态日最低价在持仓多日、大盘同期走低的场景下
        // 可能迟迟不触发。改成取两者较高值：形态日最低价继续提供"锚点不漂移"的下限保护，
        // 前一交易日最低价(prevDay.prevLow，逐日滚动更新，与prevClose同源、不需要额外请求)
        // 确保只要跌破最近一个交易日的低点就会被捕捉到，两者谁更严格就以谁为准。没有形态日数据
        // (比如手动同步进来的持仓)时，直接用prevDay.prevLow，比旧的动态"前一根阳线最低价"兜底
        // 更简单也更贴合本次用户描述的口径。注意：state.prevYangLow这个字段本身不受此次改动
        // 影响，继续保留给候选股清理逻辑用（见RealtimeMonitorService.checkStaleCandidate），
        // 两处只是历史上共享了同一个状态字段命名，用途完全独立，不要一并删掉。
        // 【2026-09-13改造，用户已明确要求】独立破位止损参照价从之前的max(形态日最低价,
        // 前一交易日最低价)简化为单一参照：直接用前一交易日最低价(prevDay.prevLow)，不再参考形态日
        // 最低价。注意：这会重新引入 2026-08-20 那次修复曾经想避开的"止损线随长期持仓/
        // 大盘同期走低而不断下移漂移"风险，但这是用户明确知情后选择的口径——严格按"跌破前一交
        // 易日最低价就该有反应"执行，不再叠加形态日这个额外下限保护。state.prevYangLow这个字段
        // 仍然保留给候选股清理逻辑用（见RealtimeMonitorService.checkStaleCandidate），不受此次改动影响。
        double stopRefPrice = prevDay.prevLow;
        String stopRefLabel = String.format(Locale.CHINA, "前一交易日(%s)最低价", prevDay.prevDate != null ? prevDay.prevDate : "?");

        // 独立破位：前一交易日最低价
        if (stopRefPrice > 0 && quote.price < stopRefPrice) {
            if (vol.shrinkBreak && !vol.confirmed) {
                return buildWarnPressure(quote, prevDay, state,
                        String.format(Locale.CHINA, "缩量跌破%s¥%.2f，降级为抛压观察", stopRefLabel, stopRefPrice));
            }
            // 【2026-08-20改造】盘中瞬间跌破不算数，要等到收盘前patternLowStopNotifyMinutes分钟
            // 仍未收复才真正确认离场——过滤掉日内插针/主力洗盘造成的误判。这个窗口跟"二级：
            // 分歧K线中点"用的是两个独立配置(patternLowStopNotifyMinutes vs stopNotifyMinutesBeforeClose)。
            boolean inWindow = isStopNotifyWindowMinutes(mCfg.patternLowStopNotifyMinutes);
            RuleResult r = buildStopLoss(quote, StopLevel.YANG_BREAK, stopRefPrice,
                    String.format(Locale.CHINA,
                            "【建议立即清仓止损】跌破%s¥%.2f（独立破位规则），现价¥%.2f，%s",
                            stopRefLabel, stopRefPrice, quote.price, vol.detail),
                    inWindow, state);
            if (!inWindow) {
                r.note += String.format(Locale.CHINA, "（盘中瞬间跌破不算，将持续观察到收盘前%d分钟仍未收复才确认离场并推送提醒）",
                        mCfg.patternLowStopNotifyMinutes);
            }
            try {
                DecisionLogger.get().logBuyLogicTrace("", quote.code, String.format(Locale.CHINA,
                        "独立破位止损判定：参照=%s(¥%.2f) 现价¥%.2f 是否已进入收盘前%d分钟确认窗口=%s",
                        stopRefLabel, stopRefPrice, quote.price, mCfg.patternLowStopNotifyMinutes, inWindow));
            } catch (Exception ignored) {}
            return r;
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
     *
     * 【R8】这个方法之前是纯副作用、完全没有日志痕迹——分歧K线一旦被识别，会直接决定后续止损位
     * （二级中点/三级最低点），但识别这一刻本身悄无声息，事后回看决策日志完全看不出止损位是
     * 什么时候、因为什么被设定的。现在只在"新识别到/K线本身发生变化"时记一条trace（同一根K线
     * 反复满足条件不会重复刷屏），把判断用到的中间值都打出来；不影响识别逻辑本身。
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

        boolean isNewOrChanged = !todayStr().equals(state.divKDate)
                || state.divKHigh != quote.high || state.divKLow != quote.low;

        state.divKHigh = quote.high;
        state.divKLow = quote.low;
        state.divMidKline = (state.divKHigh + state.divKLow) / 2.0;
        state.divMidRetrace = prevDay.prevClose * (1 + state.peakGainPct * mCfg.peakRetraceRatio / 100.0);
        state.divKDate = todayStr();

        if (isNewOrChanged) {
            try {
                DecisionLogger.get().logBuyLogicTrace("", code, String.format(Locale.CHINA,
                        "识别到分歧K线：现价¥%.2f 开%.2f 高%.2f 低%.2f，实体/振幅=%.2f(滞涨阈值%.2f) 上影线占比=%.2f(阈值0.35)，"
                                + "命中%s → 分歧K线中点¥%.2f 最低点¥%.2f",
                        quote.price, quote.open, quote.high, quote.low,
                        body / range, mCfg.divergenceBodyMaxRatio,
                        (quote.high - Math.max(quote.open, quote.price)) / range,
                        stall && upperShadow ? "滞涨+长上影" : (stall ? "滞涨(十字星)" : "长上影线"),
                        state.divMidKline, state.divKLow));
            } catch (Exception ignored) {}
        }
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

    /** 跟 isStopNotifyWindow(hour, minute) 逻辑一样，只是窗口分钟数可以单独指定——独立破位
     *  止损用自己单独配置的 patternLowStopNotifyMinutes，跟分歧K线中点用的
     *  stopNotifyMinutesBeforeClose 是两个独立的配置项，互不影响，可以分别调整。 */
    boolean isStopNotifyWindowMinutes(int minutesBeforeClose) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, mCfg.marketCloseHour);
        cal.set(Calendar.MINUTE, mCfg.marketCloseMinute);
        cal.set(Calendar.SECOND, 0);
        long closeMs = cal.getTimeInMillis();
        long nowMs = System.currentTimeMillis();
        long windowMs = minutesBeforeClose * 60_000L;
        return closeMs - nowMs <= windowMs && nowMs <= closeMs;
    }

    /** 【R3新增】计算两个时间戳之间的"实际交易分钟数"，自动扣除11:30-13:00午休时段。
     *  假定两个时间戳都在同一个交易日内（重点监听状态跨日会在evaluateStarterGapUpOrFlat里
     *  先经isSameDay()安全重置，不会带着跨日的时间戳调用到这里）。 */
    private int computeTradingMinutesExcludingLunch(long startMs, long endMs) {
        if (endMs <= startMs || startMs <= 0) return 0;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(startMs);
        cal.set(Calendar.HOUR_OF_DAY, 11);
        cal.set(Calendar.MINUTE, 30);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long lunchStart = cal.getTimeInMillis();
        cal.set(Calendar.HOUR_OF_DAY, 13);
        cal.set(Calendar.MINUTE, 0);
        long lunchEnd = cal.getTimeInMillis();
        long overlapStart = Math.max(startMs, lunchStart);
        long overlapEnd = Math.min(endMs, lunchEnd);
        long overlapMs = Math.max(0, overlapEnd - overlapStart);
        long tradingMs = (endMs - startMs) - overlapMs;
        return (int) (tradingMs / 60_000L);
    }

    /** 【R3新增】两个时间戳是否落在同一个日历日，用于重点监听状态的跨日安全重置。 */
    private boolean isSameDay(long t1, long t2) {
        Calendar c1 = Calendar.getInstance();
        c1.setTimeInMillis(t1);
        Calendar c2 = Calendar.getInstance();
        c2.setTimeInMillis(t2);
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
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
        c.focusWatchStartTime = s.focusWatchStartTime;
        c.focusWatchStatus = s.focusWatchStatus != null ? s.focusWatchStatus : "NONE";
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
