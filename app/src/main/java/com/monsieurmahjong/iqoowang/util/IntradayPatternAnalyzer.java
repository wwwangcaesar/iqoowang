package com.monsieurmahjong.iqoowang.util;

import java.util.List;

/**
 * IntradayPatternAnalyzer —— 分时图经验规则·Layer1（纯客观计算，不做买卖判断）
 *
 * 【2026-09-14新增·D】对应 B-分时图经验规则研究与实施方案.md 第3节Layer1、
 * D-分时图经验规则实现方案.md 第1节。输入分时点序列，输出结构化的客观数字，
 * "买不买/卖不卖"完全由调用方（TradingRuleEngine/RealtimeMonitorService）决定，
 * 这里不产生任何Action。
 */
public class IntradayPatternAnalyzer {

    private static IntradayPatternAnalyzer sInstance;
    public static IntradayPatternAnalyzer get() {
        if (sInstance == null) sInstance = new IntradayPatternAnalyzer();
        return sInstance;
    }

    // ═══════════════════════════════════════════════
    // 1.1 尖角反转（V型反转 / 顶部冲高回落）
    // ═══════════════════════════════════════════════

    public static class VReversal {
        public boolean detected;
        /** "BOTTOM"=尖角向下反转向上（对应买入方向）；"TOP"=冲高回落（对应卖出/预警方向） */
        public String direction = "";
        public int extremeIndex = -1;
        public double extremePrice;
        /** 反转点量能 / 触发点前5-10分钟均量 */
        public double volRatio;
        /** 反转后价格没有再破极值（顶部则是没有再创新高）已经维持的分钟数 */
        public int sustainMinutes;
        /** 是否已放量突破当日高点（强确认，仅BOTTOM方向有意义） */
        public boolean strongConfirm;
        /** 中确认：反转后价格是否已站稳分时均价线不再回落（BOTTOM方向才有意义，TOP方向恒为false） */
        public boolean midConfirm;
    }

    /**
     * 检测尖角反转形态。对应B研究1.1节"底部反转三级确认节奏"：
     *   弱确认：极值点放量≥前5-10分钟均量1.5倍，且连续3-5根量能柱都放量（不是打一下就缩量）
     *   中确认：价格站稳分时均价线，不再回落
     *   强确认：放量突破当日高点
     * 只返回"弱确认"及以上（即detected=true即代表至少弱确认成立），中/强确认程度由
     * sustainMinutes/strongConfirm两个字段体现，调用方自行判断要用到哪一级。
     */
    public VReversal detectVReversal(List<RealtimeQuoteManager.MinutePoint> points) {
        VReversal r = new VReversal();
        if (points == null || points.size() < 6) return r;

        // 找局部极值：连续3个点方向反转才认定为候选，避免单点噪声误判
        int extremeIdx = -1;
        String dir = "";
        for (int i = 2; i < points.size() - 1; i++) {
            double prev2 = points.get(i - 2).price;
            double prev1 = points.get(i - 1).price;
            double cur = points.get(i).price;
            double next = points.get(i + 1).price;
            boolean wasFalling = prev1 < prev2;
            boolean wasRising = prev1 > prev2;
            if (wasFalling && cur <= prev1 && next > cur) {
                // 下跌趋势中突然反转向上——取最后一个满足条件的低点（同一天可能有多次）
                extremeIdx = i; dir = "BOTTOM";
            } else if (wasRising && cur >= prev1 && next < cur) {
                extremeIdx = i; dir = "TOP";
            }
        }
        if (extremeIdx < 0) return r;

        // 极值点量能 vs 前5-10分钟均量（不足10分钟就用实际能取到的分钟数，最少要有3分钟基准）
        int baseWindow = Math.min(10, extremeIdx);
        if (baseWindow < 3) return r;
        long baseSum = 0;
        for (int i = extremeIdx - baseWindow; i < extremeIdx; i++) baseSum += points.get(i).volume;
        double baseAvg = baseSum / (double) baseWindow;
        if (baseAvg <= 0) return r;
        double volRatio = points.get(extremeIdx).volume / baseAvg;
        if (volRatio < 1.5) return r; // 未达弱确认的放量门槛

        // 连续3-5根量能柱都要放量，不能打一下就缩量（B研究1.1节明确指出这种是假反转）
        int sustainVolBars = 0;
        for (int i = extremeIdx; i < Math.min(points.size(), extremeIdx + 5); i++) {
            if (points.get(i).volume >= baseAvg * 1.3) sustainVolBars++; // 放宽到1.3倍，避免逐根都卡1.5倍过严
            else break;
        }
        if (sustainVolBars < 3) return r; // 放量没能持续3根以上，判定为假反转，不予确认

        r.detected = true;
        r.direction = dir;
        r.extremeIndex = extremeIdx;
        r.extremePrice = points.get(extremeIdx).price;
        r.volRatio = volRatio;

        // 维持分钟数：反转后价格是否再破极值（BOTTOM=再创新低，TOP=再创新高）
        int sustain = 0;
        for (int i = extremeIdx + 1; i < points.size(); i++) {
            double p = points.get(i).price;
            boolean broken = "BOTTOM".equals(dir) ? (p < r.extremePrice) : (p > r.extremePrice);
            if (broken) break;
            sustain++;
        }
        r.sustainMinutes = sustain;

        // 强确认：BOTTOM方向且反转后有放量突破当日high
        if ("BOTTOM".equals(dir)) {
            double dayHigh = 0;
            for (int i = 0; i <= extremeIdx; i++) dayHigh = Math.max(dayHigh, points.get(i).price);
            for (int i = extremeIdx + 1; i < points.size(); i++) {
                if (points.get(i).price > dayHigh && points.get(i).volume >= baseAvg * 1.5) {
                    r.strongConfirm = true;
                    break;
                }
            }
            // 中确认：从极值点到现在（共sustainMinutes个点），每个点都要站在当时自己的分时均价线
            // 之上（用每个点自己的avgPrice，不是用最新一个点的VWAP去套早先的点），中途哪怕瞬间
            // 跌破一次都不算站稳。
            boolean stable = r.sustainMinutes >= 1;
            for (int i = extremeIdx; stable && i <= extremeIdx + r.sustainMinutes && i < points.size(); i++) {
                RealtimeQuoteManager.MinutePoint p = points.get(i);
                if (p.avgPrice <= 0 || p.price < p.avgPrice) stable = false;
            }
            r.midConfirm = stable;
        }
        return r;
    }

    public static class VolumeSpikeReversal {
        public boolean detected;
        /** "TOP"=高位放量尖角（卖出参考点）；"BOTTOM"=下跌途中放量尖角（买入参考点） */
        public String direction = "";
        public int spikeIndex = -1;
        public double spikePrice;
        public double spikeVolume;
        /** 尖角量能 / 该点前5-10分钟均量 */
        public double volRatio;
    }

    /**
     * 【对应用户明确描述的"分时放量尖角"经验】跟上面detectVReversal是两套不同判据，不是同一个
     * 东西的变体：detectVReversal要求反转点之后连续3根以上都放量，专门用来过滤"冲一下就缩量"
     * 这种情况，判定为假反转不予确认；这里用户描述的恰恰就是"冲一下就缩量"本身——当天最大量、
     * 尖角形状（这一分钟量能极大，前后都明显回落），在相对高位出现是见顶卖出参考，在下跌途中
     * 的相对低位出现是触底买入参考，且触底这支不要求任何水线/VWAP前提——用户原话"并不一定要
     * 站上分时均价，即使在水下也可以购买"。单点不要求持续性，阀值需要比detectVReversal
     * 的1.5倍更严（现定2.5倍）防噪声。
     */
    public VolumeSpikeReversal detectVolumeSpikeReversal(List<RealtimeQuoteManager.MinutePoint> points) {
        VolumeSpikeReversal r2 = new VolumeSpikeReversal();
        if (points == null || points.size() < 8) return r2;
        int n = points.size();

        int maxIdx = 0;
        for (int i = 1; i < n; i++) {
            if (points.get(i).volume > points.get(maxIdx).volume) maxIdx = i;
        }
        if (maxIdx >= n - 1) return r2;

        int baseWindow = Math.min(10, maxIdx);
        if (baseWindow < 3) return r2;
        long baseWindowSum = 0;
        for (int i = maxIdx - baseWindow; i < maxIdx; i++) baseWindowSum += points.get(i).volume;
        double baseAvg = baseWindowSum / (double) baseWindow;
        if (baseAvg <= 0) return r2;
        double volRatio = points.get(maxIdx).volume / baseAvg;
        if (volRatio < 2.5) return r2;

        int fadeWindow = Math.min(2, n - maxIdx - 1);
        boolean faded = true;
        for (int i = 1; i <= fadeWindow; i++) {
            if (points.get(maxIdx + i).volume > points.get(maxIdx).volume * 0.6) { faded = false; break; }
        }
        if (!faded) return r2;

        double curPrice = points.get(maxIdx).price;
        double prevPrice = points.get(maxIdx - 1).price;
        double nextPrice = points.get(maxIdx + 1).price;
        String dir;
        if (curPrice >= prevPrice && nextPrice < curPrice) {
            dir = "TOP";
        } else if (curPrice <= prevPrice && nextPrice > curPrice) {
            dir = "BOTTOM";
        } else {
            return r2;
        }

        int trendWindow = Math.min(15, maxIdx);
        double trendStart = points.get(maxIdx - trendWindow).price;
        boolean priorUptrend = curPrice > trendStart;
        boolean priorDowntrend = curPrice < trendStart;
        if ("TOP".equals(dir) && !priorUptrend) return r2;
        if ("BOTTOM".equals(dir) && !priorDowntrend) return r2;

        r2.detected = true;
        r2.direction = dir;
        r2.spikeIndex = maxIdx;
        r2.spikePrice = curPrice;
        r2.spikeVolume = points.get(maxIdx).volume;
        r2.volRatio = volRatio;
        return r2;
    }


    // ═══════════════════════════════════════════════
    // 【2026-09-17新增】分时图技术指标综合摘要——不管候选池观察中还是已持仓，也不管现价在
    // 水线上方还是下方，每一轮TradingRuleEngine.evaluate()都会调用一次，产出趋势方向、
    // 量能特征、反转形态三块结构化信息，拼进决策日志的每一行。对应用户反馈“决策日志中并没有
    // 对应分时图技术指标的分析”——之前反转检测只在checkUnderwaterReversal（必须现价<水线）
    // 里才会触发，趋势方向压根没有独立的判断逻辑，持仓和候选池都存在这个缺口，不是只有
    // 候选池缺。
    // ═══════════════════════════════════════════════

    public static class IntradayTechnicalSummary {
        /** "上升"/"下降"/"震荡"/"数据不足" —— 最近窗口内前后两段均价对比判断的分时趋势方向 */
        public String trend = "数据不足";
        /** 后半段均价相对前半段均价的变化百分比，正=走高，负=走低 */
        public double trendChangePct;
        /** "放量"/"缩量"/"正常"/"数据不足" —— 最近5分钟量能相对再往前5分钟基准的特征 */
        public String volumeState = "数据不足";
        public double recentVolRatio;
        /** 复用尖角反转检测，不限定必须处于水下——跟checkUnderwaterReversal（专供买入方向的
         *  水下场景）是两回事，这里任何时候、任何持仓状态都会检测 */
        public VReversal reversal;
        /** trend/volumeState/reversal这几个独立算出来的信号互相矛盾、或反转仅达弱确认这类
         *  “简单代码判断不准确”的客观情况，标记为true，交由调用方（RealtimeMonitorService）
         *  决定要不要额外喊一次本地AI校验——这里只负责判断“值不值得喊”，不负责真的去调AI，
         *  纯计算的Layer1不该管本地推理资源怎么调度。 */
        public boolean ambiguous;
        /** ambiguous=true时具体是哪几点矛盾/不确定，供AI校验的prompt直接引用 */
        public String ambiguousReason = "";
        /** 复用尖角反转检测（与reversal字段用的detectVReversal是两套独立判据），跟reversal
         *  一样任何时候都会检测，不限定方向/持仓状态。 */
        public VolumeSpikeReversal spikeReversal;
        /** 拼好的一行人话摘要，直接可以塞进决策日志/result.metrics */
        public String summaryText = "";
    }

    /** @param points 分时点序列，数据不足6个点时直接返回一份“数据不足”的摘要，不强行计算 */
    public IntradayTechnicalSummary summarize(List<RealtimeQuoteManager.MinutePoint> points) {
        IntradayTechnicalSummary s = new IntradayTechnicalSummary();
        s.reversal = new VReversal();
        if (points == null || points.size() < 6) {
            s.summaryText = "分时趋势=数据不足 量能=数据不足 反转=未检测（分时点不足6个）";
            return s;
        }

        // 趋势方向：取最近min(20,全部)个点，前后对半分别算均价，后段相对前段的变化幅度。
        // 阈值0.15%——分时价格本身波动幅度远小于日K，用日K那种1%级别的阈值会把绝大多数正常
        // 波动都判成“震荡”，参考同文件checkBreakoutVolume等方法对分时量级敏感度定的这个数。
        int n = points.size();
        int window = Math.min(20, n);
        int half = window / 2;
        if (half >= 2) {
            double sumEarly = 0, sumLate = 0;
            for (int i = n - window; i < n - half; i++) sumEarly += points.get(i).price;
            for (int i = n - half; i < n; i++) sumLate += points.get(i).price;
            double avgEarly = sumEarly / (window - half);
            double avgLate = sumLate / half;
            s.trendChangePct = avgEarly > 0 ? (avgLate - avgEarly) / avgEarly * 100 : 0;
            if (s.trendChangePct > 0.15) s.trend = "上升";
            else if (s.trendChangePct < -0.15) s.trend = "下降";
            else s.trend = "震荡";
        }

        // 量能特征：最近5分钟量能 vs 再往前5分钟的基准量能，跟TradingRuleEngine.checkVolume的
        // “近5分钟量比”同一思路，这里独立算一份，不依赖调用方是否传了VolumeCheck对象进来，
        // 保持这个方法可以被任何调用方单独使用。
        if (n >= 10) {
            long recentSum = 0, baseSum = 0;
            int recentWindow = Math.min(5, n);
            for (int i = n - recentWindow; i < n; i++) recentSum += points.get(i).volume;
            int baseWindow = Math.min(5, n - recentWindow);
            for (int i = n - recentWindow - baseWindow; i < n - recentWindow; i++) baseSum += points.get(i).volume;
            double baseAvg = baseWindow > 0 ? (double) baseSum / baseWindow : 0;
            double recentAvg = recentWindow > 0 ? (double) recentSum / recentWindow : 0;
            s.recentVolRatio = baseAvg > 0 ? recentAvg / baseAvg : 0;
            if (s.recentVolRatio >= 1.5) s.volumeState = "放量";
            else if (s.recentVolRatio > 0 && s.recentVolRatio <= 0.6) s.volumeState = "缩量";
            else s.volumeState = "正常";
        }

        // 反转形态：复用现有检测逻辑，不再限定“必须水下”——候选池/持仓不管现价在水线上方还是
        // 下方，反转形态本身都是有意义的客观信息。
        s.reversal = detectVReversal(points);
        s.spikeReversal = detectVolumeSpikeReversal(points);

        // ambiguous判定：不引入任何新的主观阈值，纯粹检查这几个独立信号本身是否互相矛盾/
        // 证据不够扎实——这些矛盾本身就是“规则判断不准确”的客观信号，此时才值得喊本地AI
        // 再看一眼，不是每一轮都无脑喊（本地推理资源只有一份，喊多了会跟真正的买卖信号复核
        // 抢锁）。
        StringBuilder reasons = new StringBuilder();
        if (s.reversal.detected && "TOP".equals(s.reversal.direction) && "上升".equals(s.trend)) {
            reasons.append("反转检测显示冲高回落(TOP)，但整体趋势仍判断为上升，两者矛盾；");
        }
        if (s.reversal.detected && "BOTTOM".equals(s.reversal.direction) && "下降".equals(s.trend)) {
            reasons.append("反转检测显示触底反转(BOTTOM)，但整体趋势仍判断为下降，两者矛盾；");
        }
        if ("放量".equals(s.volumeState) && "震荡".equals(s.trend)) {
            reasons.append("量能明显放大但价格方向不明朗，可能正在酝酿变盘；");
        }
        if (s.reversal.detected && !s.reversal.midConfirm && !s.reversal.strongConfirm) {
            reasons.append("检测到反转形态但仅达弱确认（未站稳分时均价/未放量突破），可信度存疑；");
        }
        if (reasons.length() > 0) {
            s.ambiguous = true;
            s.ambiguousReason = reasons.toString();
        }

        s.summaryText = String.format(java.util.Locale.CHINA,
                "分时趋势=%s(%.2f%%) 量能=%s(%.2fx) 反转=%s%s",
                s.trend, s.trendChangePct, s.volumeState, s.recentVolRatio,
                s.reversal.detected
                        ? (s.reversal.direction + (s.reversal.midConfirm ? "·中确认" : s.reversal.strongConfirm ? "·强确认" : "·弱确认"))
                        : "未检测到",
                s.spikeReversal != null && s.spikeReversal.detected
                        ? String.format(java.util.Locale.CHINA, " 尖角=%s@¥%.2f(%.2fx)",
                                s.spikeReversal.direction, s.spikeReversal.spikePrice, s.spikeReversal.volRatio)
                        : "");
        return s;
    }

    // ═══════════════════════════════════════════════
    // 【2026-09-25新增·Claude，对应用户"收盘排行榜需要通过一整个交易日的分时数据和整体成交量
    // 评估哪只更适合持有"的要求】上面summarize()只看最近约10个分时点（不到20分钟），是"近期
    // 变化"，不是"全天走势"——收盘前判断一支票今天到底是强势保持还是冲高回落，需要看开盘到
    // 现在的完整轨迹，不能只看最后一小段。这里新增一个独立方法，跟summarize()互不影响、互不
    // 依赖，输出全天最高/最低点出现的位置、现价在全天区间的百分位、全天走势形态、以及尾盘
    // 量能相对全天均值的变化——这几点合起来才是"整体成交量的评估"要的东西。
    // ═══════════════════════════════════════════════

    public static class FullDaySummary {
        public double dayHigh, dayLow;
        public String dayHighTime = "", dayLowTime = "";
        /** 现价在全天[dayLow, dayHigh]区间的位置百分比，0=全天最低，100=全天最高 */
        public double pricePositionPct;
        /** "单边上涨"/"单边下跌"/"先抑后扬"/"先扬后抑"/"高位震荡"/"低位震荡"/"数据不足" */
        public String shape = "数据不足";
        /** 尾盘（最后min(30,全部)个点）平均每分钟量 相对 全天平均每分钟量 的倍数 */
        public double lateVolRatio;
        /** "尾盘放量"/"尾盘缩量"/"正常"/"数据不足" */
        public String lateVolState = "数据不足";
        /** 拼好的一行人话摘要，直接可以塞进候选池排行榜snapshot */
        public String summaryText = "";
    }

    /** @param points 分时点序列，应传入当天从开盘到现在的完整缓存（不是截断窗口），
     *  数据不足6个点时返回"数据不足"摘要，不强行计算 */
    public FullDaySummary summarizeFullDay(List<RealtimeQuoteManager.MinutePoint> points) {
        FullDaySummary s = new FullDaySummary();
        if (points == null || points.size() < 6) {
            s.summaryText = "全天走势=数据不足";
            return s;
        }
        int n = points.size();

        // 全天最高/最低点及其出现的时间——不是最近窗口，是从开盘到现在的完整序列
        int highIdx = 0, lowIdx = 0;
        double high = points.get(0).price, low = points.get(0).price;
        for (int i = 1; i < n; i++) {
            double p = points.get(i).price;
            if (p > high) { high = p; highIdx = i; }
            if (p < low) { low = p; lowIdx = i; }
        }
        s.dayHigh = high; s.dayLow = low;
        s.dayHighTime = points.get(highIdx).time;
        s.dayLowTime = points.get(lowIdx).time;

        double curPrice = points.get(n - 1).price;
        s.pricePositionPct = high > low ? (curPrice - low) / (high - low) * 100 : 50;

        // 走势形态：最高/最低点出现在前40%还是后40%的时间段，配合现价当前所处百分位综合判断——
        // 不引入新的主观阈值体系，跟summarize()的0.15%趋势阈值是两回事，这里判断的是"全天
        // 形状"，不是"最近变化率"。
        boolean highEarly = highIdx <= n * 0.4;
        boolean highLate = highIdx >= n * 0.6;
        boolean lowEarly = lowIdx <= n * 0.4;
        boolean lowLate = lowIdx >= n * 0.6;
        if (s.pricePositionPct >= 80 && highLate) {
            s.shape = "单边上涨";
        } else if (s.pricePositionPct <= 20 && lowLate) {
            s.shape = "单边下跌";
        } else if (lowEarly && s.pricePositionPct >= 60) {
            s.shape = "先抑后扬";
        } else if (highEarly && s.pricePositionPct <= 40) {
            s.shape = "先扬后抑";
        } else if (s.pricePositionPct >= 50) {
            s.shape = "高位震荡";
        } else {
            s.shape = "低位震荡";
        }

        // 尾盘量能：最后min(30,全部)个点的平均每分钟量 vs 全天平均每分钟量——收盘前用来判断
        // "尾盘是不是有资金在加速进场/出逃"，跟summarize()的"近5分钟vs再往前5分钟"是不同量级
        // 的观察窗口，互相补充不重复。
        int lateWindow = Math.min(30, n);
        long lateSum = 0;
        for (int i = n - lateWindow; i < n; i++) lateSum += points.get(i).volume;
        double lateAvg = lateSum / (double) lateWindow;
        long totalSum = 0;
        for (int i = 0; i < n; i++) totalSum += points.get(i).volume;
        double wholeDayAvg = totalSum / (double) n;
        if (wholeDayAvg > 0) {
            s.lateVolRatio = lateAvg / wholeDayAvg;
            if (s.lateVolRatio >= 1.5) s.lateVolState = "尾盘放量";
            else if (s.lateVolRatio <= 0.6) s.lateVolState = "尾盘缩量";
            else s.lateVolState = "正常";
        }

        s.summaryText = String.format(java.util.Locale.CHINA,
                "全天走势=%s(高¥%.2f@%s/低¥%.2f@%s，现价处于区间%.0f%%分位) 尾盘量能=%s(%.2fx全天均值)",
                s.shape, s.dayHigh, s.dayHighTime, s.dayLow, s.dayLowTime, s.pricePositionPct,
                s.lateVolState, s.lateVolRatio);
        return s;
    }

    // ═══════════════════════════════════════════════
    // 1.2 真假突破/破位的瞬时量能判据
    // ═══════════════════════════════════════════════

    public static class BreakoutCheck {
        public boolean crossed;       // 是否发生了穿越（价格确实碰到/跌破了keyLevel）
        public boolean isRealBreak;   // true=真突破/真破位（瞬时量能达标）；false=缩量完成，视为假突破
        public double instantVolRatio;
        public int crossIndex = -1;
        public int confirmWindowMinutes;
    }

    /**
     * 判断价格穿越keyLevel（比如前一交易日最低价）那一刻的量能是否达标。
     * 对应B研究1.2节：真突破/真破位瞬时量能应达前5-10分钟均量1.5-2倍以上；不足则视为
     * 假突破/假破位，缩量完成的破位应该给confirmWindowMinutes（用户确认为3分钟）的
     * 确认窗口——这个窗口本身在这里只是把用户确认的分钟数带出去，是否真的等待、
     * 等待期间怎么处理，由调用方（TradingRuleEngine.evaluateStopLoss）决定，这里只负责
     * 判断"这一刻穿越时量能够不够"，不涉及等待逻辑本身。
     * keyLevel语义由调用方决定（跌破传"前低"，向上突破传"前高"），穿越方向由direction
     * 参数指定（"DOWN"=向下穿越/破位，"UP"=向上穿越/突破）。
     */
    public BreakoutCheck checkBreakoutVolume(List<RealtimeQuoteManager.MinutePoint> points,
                                              double keyLevel, String direction,
                                              int confirmWindowMinutes) {
        BreakoutCheck r = new BreakoutCheck();
        r.confirmWindowMinutes = confirmWindowMinutes;
        if (points == null || points.size() < 6) return r;
        boolean down = "DOWN".equals(direction);

        int crossIdx = -1;
        for (int i = 1; i < points.size(); i++) {
            double prev = points.get(i - 1).price;
            double cur = points.get(i).price;
            boolean crossedNow = down ? (prev >= keyLevel && cur < keyLevel)
                                       : (prev <= keyLevel && cur > keyLevel);
            if (crossedNow) crossIdx = i; // 取最后一次穿越（同一天可能反复穿越，以最近一次为准）
        }
        if (crossIdx < 0) return r;
        r.crossed = true;
        r.crossIndex = crossIdx;

        int baseWindow = Math.min(10, crossIdx);
        // 数据不够判断基准量时不做过度推断，按"真"处理（不额外拦截原有止损判断）
        if (baseWindow < 3) { r.isRealBreak = true; return r; }
        long baseSum = 0;
        for (int i = crossIdx - baseWindow; i < crossIdx; i++) baseSum += points.get(i).volume;
        double baseAvg = baseSum / (double) baseWindow;
        if (baseAvg <= 0) { r.isRealBreak = true; return r; }
        r.instantVolRatio = points.get(crossIdx).volume / baseAvg;
        r.isRealBreak = r.instantVolRatio >= 1.5;
        return r;
    }

    // ═══════════════════════════════════════════════
    // 1.3 涨停放巨量（高位）判据——只算成交量倍数，不算换手率（数据源限制，见D方案第1.3/5节）
    // ═══════════════════════════════════════════════

    public static class VolumeSurgeCheck {
        public boolean hasBaseline;          // 是否拿到了近5-10日历史均量做基准（没跑过盘后数据下载时为false）
        public double multipleVsHistoryAvg;
        /** "DISTRIBUTION"=落在出货区间(≥3倍)；"ACCUMULATION_OR_WASHOUT"=落在洗盘区间(2-3倍)；"NORMAL"=都不是 */
        public String zone = "NORMAL";
    }

    /**
     * @param todayCumVolume 今日累计成交量(手)，取分时数据最后一个点的cumVolume
     * @param recentDailyVolumes 近5-10日每日成交量(手)，来自MarketDataManager.getCachedKline()，
     *                           调用方需自行排除"今天"这一条（如果盘后数据当天已经下载过一次）
     */
    public VolumeSurgeCheck checkVolumeSurgeAtLimit(long todayCumVolume, List<Long> recentDailyVolumes) {
        VolumeSurgeCheck r = new VolumeSurgeCheck();
        if (recentDailyVolumes == null || recentDailyVolumes.isEmpty() || todayCumVolume <= 0) return r;
        long sum = 0;
        for (long v : recentDailyVolumes) sum += v;
        double avg = sum / (double) recentDailyVolumes.size();
        if (avg <= 0) return r;
        r.hasBaseline = true;
        r.multipleVsHistoryAvg = todayCumVolume / avg;
        // B研究1.3节：出货≥3-5倍均量，洗盘2-3倍
        if (r.multipleVsHistoryAvg >= 3.0) r.zone = "DISTRIBUTION";
        else if (r.multipleVsHistoryAvg >= 2.0) r.zone = "ACCUMULATION_OR_WASHOUT";
        return r;
    }
}
