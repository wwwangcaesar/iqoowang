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
