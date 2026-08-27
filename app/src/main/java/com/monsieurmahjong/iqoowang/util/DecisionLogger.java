package com.monsieurmahjong.iqoowang.util;

import android.util.Log;

import java.util.Locale;

/**
 * 分类日志系统 —— 记录规则引擎+AI完整复核过程+手动操作，供事后跟踪验证。
 *
 * 【2026-08-27 改造：日志分类存储】之前所有内容都塞进同一个 decisions_YYYY-MM-DD.txt，
 * 监控快照、规则命中、AI分析、手动买卖混在一起，天数一多就很难在里面找到自己关心的那类信息。
 * 现拆分成5个独立文件（同一天、同一目录下，按类别区分文件名前缀）：
 *
 *   1. monitor_YYYY-MM-DD.txt     监控日志——每支监控中/持仓中股票的定期价格快照
 *   2. decision_YYYY-MM-DD.txt    决策日志——触发操盘手规则校验的记录（含无信号的常规评估）
 *   3. ai_analysis_YYYY-MM-DD.txt AI分析日志——AI对信号/选股/复盘的定性分析全过程
 *   4. operation_YYYY-MM-DD.txt   操作日志——所有手动买入/卖出操作，含成功和被拒绝的
 *   5. other_YYYY-MM-DD.txt       其他日志——数据获取追踪、诊断信息等排查用途
 *
 * 每个公开方法名和参数都保持不变（外部调用方 StockBridge / RealtimeMonitorService 无需改动），
 * 只是内部把内容路由到对应分类文件；同时新增按分类查询的重载方法供前端分类查看。
 */
public class DecisionLogger {

    private static final String TAG = "DecisionLogger";
    private static DecisionLogger sInstance;
    private final android.content.Context mContext;
    private final java.text.SimpleDateFormat mDayFmt = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
    private final java.text.SimpleDateFormat mTimeFmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    // ── 五大分类 ──
    public static final String CAT_MONITOR = "monitor";
    public static final String CAT_DECISION = "decision";
    public static final String CAT_AI = "ai_analysis";
    public static final String CAT_OPERATION = "operation";
    public static final String CAT_OTHER = "other";
    private static final String[] ALL_CATEGORIES = {CAT_MONITOR, CAT_DECISION, CAT_AI, CAT_OPERATION, CAT_OTHER};

    /** 分类的中文展示名，前端下拉框/tab用 */
    public static String categoryLabel(String category) {
        if (CAT_MONITOR.equals(category)) return "监控日志";
        if (CAT_DECISION.equals(category)) return "决策日志";
        if (CAT_AI.equals(category)) return "AI分析日志";
        if (CAT_OPERATION.equals(category)) return "操作日志";
        return "其他日志";
    }

    /** 全部分类的{key,label}列表（JSON），供前端渲染分类选择器 */
    public String getCategoriesJson() {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (String c : ALL_CATEGORIES) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("key", c);
                o.put("label", categoryLabel(c));
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    private static String normalizeCategory(String category) {
        if (category == null) return CAT_OTHER;
        for (String c : ALL_CATEGORIES) {
            if (c.equals(category)) return c;
        }
        return CAT_OTHER;
    }

    public static void init(android.content.Context context) {
        if (sInstance == null) {
            synchronized (DecisionLogger.class) {
                if (sInstance == null) sInstance = new DecisionLogger(context.getApplicationContext());
            }
        }
    }

    public static DecisionLogger get() {
        if (sInstance == null) throw new IllegalStateException("call init() first");
        return sInstance;
    }

    private DecisionLogger(android.content.Context context) {
        mContext = context;
    }

    private java.io.File getLogDir() {
        java.io.File dir = new java.io.File(mContext.getExternalFilesDir(null), "decision_logs");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private java.io.File getLogFile(String category, String dayStr) {
        return new java.io.File(getLogDir(), normalizeCategory(category) + "_" + dayStr + ".txt");
    }

    private java.io.File getTodayLogFile(String category) {
        return getLogFile(category, mDayFmt.format(new java.util.Date()));
    }

    /**
     * 规则引擎命中且已立即推送通知时记录（AI 尚未完成，标记为分析进行中）。→ 决策日志
     */
    public void logRulePush(String name, String code, boolean holding, double holdCost,
                            double currentPrice, String watchStatus,
                            TradingRuleEngine.RuleResult ruleResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(mTimeFmt.format(new java.util.Date())).append("] ");
        sb.append(name).append("(").append(code).append(") ");
        sb.append("候选池状态=").append(watchStatus).append(" ");
        if (holding) {
            sb.append(String.format(Locale.CHINA, "持仓中 成本¥%.2f 现价¥%.2f", holdCost, currentPrice));
        } else {
            sb.append(String.format(Locale.CHINA, "未买入 现价¥%.2f", currentPrice));
        }
        sb.append("\n");
        sb.append("【规则推送】").append(ruleResult.actionLabel);
        sb.append(" | action=").append(TradingRuleEngine.actionToKey(ruleResult.action));
        sb.append("\n规则依据：").append(ruleResult.note);
        if (ruleResult.metrics != null && !ruleResult.metrics.isEmpty()) {
            sb.append("\n指标快照：").append(ruleResult.metrics);
        }
        if (ruleResult.stopLevel != TradingRuleEngine.StopLevel.NONE) {
            sb.append("\n止损级别：").append(ruleResult.stopLevel.name());
            sb.append(" | 即时推送=").append(ruleResult.notifyImmediate);
        }
        sb.append("\n用户通知：已推送（规则引擎独立决定，不等AI）");
        sb.append("\nAI复核：分析进行中…（结果会写入AI分析日志）");
        sb.append("\n").append(repeat('-', 72)).append("\n");
        appendToFile(CAT_DECISION, sb.toString());
    }

    /**
     * AI 异步定性分析完成后补充记录（不改变已推送的通知）。→ AI分析日志
     */
    public void logAiSupplement(String name, String code, String actionLabel,
                               boolean aiConfirmed, String aiReason, String aiFullText) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(mTimeFmt.format(new java.util.Date())).append("] ");
        sb.append(name).append("(").append(code).append(") ");
        sb.append("【AI补充分析】针对已推送信号：").append(actionLabel).append("\n");
        sb.append("AI结论：").append(aiConfirmed ? "支持规则判断" : "存疑，请结合规则依据自行斟酌");
        sb.append("\nAI说明：").append(aiReason != null ? aiReason : "（无）");
        if (aiFullText != null && !aiFullText.trim().isEmpty()) {
            sb.append("\nAI完整输出：\n").append(aiFullText.trim());
        }
        sb.append("\n").append(repeat('-', 72)).append("\n");
        appendToFile(CAT_AI, sb.toString());
    }

    /**
     * 记录一次完整的信号评估（含规则未命中、AI驳回、双通过等所有情况）。
     * 规则相关内容写决策日志；如果这一轮确实产生了AI结论，AI相关内容额外写一份到AI分析日志，
     * 避免想专门看AI分析记录时还要在决策日志里逐条翻找。
     */
    public void logSignalEvaluation(String name, String code, boolean holding, double holdCost,
                                     double currentPrice, String watchStatus,
                                     TradingRuleEngine.RuleResult ruleResult,
                                     boolean aiConfirmed, String aiReason, String aiFullText,
                                     boolean notified) {
        String header = "[" + mTimeFmt.format(new java.util.Date()) + "] " + name + "(" + code + ") "
                + "候选池状态=" + watchStatus + " "
                + (holding
                    ? String.format(Locale.CHINA, "持仓中 成本¥%.2f 现价¥%.2f", holdCost, currentPrice)
                    : String.format(Locale.CHINA, "未买入 现价¥%.2f", currentPrice))
                + "\n";

        StringBuilder ruleSb = new StringBuilder(header);
        if (ruleResult == null || ruleResult.action == TradingRuleEngine.Action.NONE) {
            ruleSb.append("规则引擎：本轮无信号");
            if (ruleResult != null && ruleResult.note != null) ruleSb.append("（").append(ruleResult.note).append("）");
            ruleSb.append("\n");
            if (ruleResult != null && ruleResult.metrics != null && !ruleResult.metrics.isEmpty()) {
                ruleSb.append("指标快照：").append(ruleResult.metrics).append("\n");
            }
        } else {
            ruleSb.append("规则引擎：").append(ruleResult.actionLabel);
            ruleSb.append(" | action=").append(TradingRuleEngine.actionToKey(ruleResult.action));
            ruleSb.append("\n规则依据：").append(ruleResult.note);
            if (ruleResult.metrics != null && !ruleResult.metrics.isEmpty()) {
                ruleSb.append("\n指标快照：").append(ruleResult.metrics);
            }
            if (ruleResult.stopLevel != TradingRuleEngine.StopLevel.NONE) {
                ruleSb.append("\n止损级别：").append(ruleResult.stopLevel.name());
                ruleSb.append(" | 即时推送=").append(ruleResult.notifyImmediate);
            }
            ruleSb.append("\n用户通知：").append(notified ? "已推送" : "未推送（规则或AI未双通过，或不在推送窗口）");
            ruleSb.append("\nAI复核结论见AI分析日志：").append(aiConfirmed ? "✓ 确认通过" : "✗ 未通过");
        }
        ruleSb.append("\n").append(repeat('-', 72)).append("\n");
        appendToFile(CAT_DECISION, ruleSb.toString());

        boolean hasAiContent = ruleResult != null && ruleResult.action != TradingRuleEngine.Action.NONE;
        if (hasAiContent) {
            StringBuilder aiSb = new StringBuilder(header);
            aiSb.append("【信号评估·AI复核】").append(ruleResult.actionLabel).append("\n");
            aiSb.append("AI复核：").append(aiConfirmed ? "✓ 确认通过" : "✗ 未通过");
            aiSb.append("\nAI结论：").append(aiReason != null ? aiReason : "（无）");
            if (aiFullText != null && !aiFullText.trim().isEmpty()) {
                aiSb.append("\nAI完整输出：\n").append(aiFullText.trim());
            }
            aiSb.append("\n").append(repeat('-', 72)).append("\n");
            appendToFile(CAT_AI, aiSb.toString());
        }
    }

    /** @deprecated 保留兼容，内部转调 logSignalEvaluation */
    public void logDecision(String name, String code, boolean holding, double holdCost,
                             double currentPrice, String ruleAction, String ruleNote,
                             boolean aiConfirmed, String aiReason) {
        TradingRuleEngine.RuleResult rr = new TradingRuleEngine.RuleResult();
        rr.actionLabel = ruleAction;
        rr.note = ruleNote;
        logSignalEvaluation(name, code, holding, holdCost, currentPrice, "-",
                rr.action != TradingRuleEngine.Action.NONE ? rr : null,
                aiConfirmed, aiReason, aiReason, aiConfirmed);
    }

    /** 通用备注——排查/诊断用途，去向"其他日志" */
    public void logNote(String text) {
        appendToFile(CAT_OTHER, "[" + mTimeFmt.format(new java.util.Date()) + "] " + text + "\n"
                + repeat('-', 72) + "\n");
    }

    private String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private synchronized void appendToFile(String category, String content) {
        try (java.io.FileWriter fw = new java.io.FileWriter(getTodayLogFile(category), true)) {
            fw.write(content);
        } catch (java.io.IOException e) {
            Log.e(TAG, "写日志失败[" + category + "]", e);
        }
    }

    // ── 按分类读取 ──

    public String getTodayLogContent(String category) { return readLogFile(getTodayLogFile(category)); }

    public String getLogContent(String dayStr, String category) { return readLogFile(getLogFile(category, dayStr)); }

    /** 兼容旧调用（无分类参数）：默认当作决策日志读取，最贴近旧版单一日志文件原本的定位 */
    public String getTodayLogContent() { return getTodayLogContent(CAT_DECISION); }
    public String getLogContent(String dayStr) { return getLogContent(dayStr, CAT_DECISION); }

    private String readLogFile(java.io.File f) {
        if (!f.exists()) return "";
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "读日志失败", e);
            return "";
        }
    }

    public String[] listLogDates(String category) {
        String cat = normalizeCategory(category);
        String prefix = cat + "_";
        java.io.File dir = getLogDir();
        java.io.File[] files = dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(".txt"));
        if (files == null) return new String[0];
        String[] dates = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            String n = files[i].getName();
            dates[i] = n.substring(prefix.length(), n.length() - ".txt".length());
        }
        java.util.Arrays.sort(dates, java.util.Collections.reverseOrder());
        return dates;
    }

    /** 兼容旧调用（无分类参数）：默认按决策日志的日期列表返回 */
    public String[] listLogDates() { return listLogDates(CAT_DECISION); }

    public String getLogDirPath() {
        return getLogDir().getAbsolutePath();
    }

    /**
     * 周期性监控快照——不管本轮有没有触发买卖信号，都定期把所有监控中/持仓中股票的现价、
     * 参考价（持仓成本或水线）和当前规则判断记一笔。→ 监控日志。
     * lines：调用方已经拼好的每支股票一行摘要（含时间、股票信息、实时价格等主要信息）。
     */
    public void logSnapshot(java.util.List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(mTimeFmt.format(new java.util.Date())).append("] ");
        sb.append("【监控快照】共").append(lines.size()).append("支\n");
        for (String line : lines) {
            sb.append("  ").append(line).append("\n");
        }
        sb.append(repeat('-', 72)).append("\n");
        appendToFile(CAT_MONITOR, sb.toString());
    }

    /**
     * 手动买卖弹窗成交/拒绝时记一笔——完整记录时间、具体股票、方向、数量、价格、
     * 下单前可卖数量、成交结果（成交/资金不足/T+1限制/其他拒绝原因）。→ 操作日志。
     */
    public void logManualTrade(String name, String code, String direction, double price, int quantity,
                                long resultId, int sellableBeforeTrade) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(mTimeFmt.format(new java.util.Date())).append("] ");
        sb.append(name).append("(").append(code).append(") ");
        sb.append("【手动").append("BUY".equals(direction) ? "买入" : "卖出").append("】");
        sb.append(String.format(Locale.CHINA, "请求%d股 @¥%.2f", quantity, price));
        if ("SELL".equals(direction)) {
            sb.append(String.format(Locale.CHINA, "，下单前可卖%d股", sellableBeforeTrade));
        }
        if (resultId > 0) {
            sb.append("\n结果：成交，交易记录id=").append(resultId);
        } else if (resultId == -1) {
            sb.append("\n结果：拒绝（资金不足，未写入任何数据）");
        } else if (resultId == -2) {
            sb.append("\n结果：拒绝（T+1限制，未写入任何数据）");
        } else {
            sb.append("\n结果：拒绝（id=").append(resultId).append("，未写入任何数据）");
        }
        sb.append("\n").append(repeat('-', 72)).append("\n");
        appendToFile(CAT_OPERATION, sb.toString());
    }

    /**
     * 【2026-08-20 买入逻辑改造】异步获取"昨日全天真实VWAP"的结果追踪——数据获取诊断信息，
     * 不是交易决策本身。→ 其他日志。
     */
    public void logPrevDayVwapFetch(String code, boolean success, double vwap, String date, String errorMsg) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(mTimeFmt.format(new java.util.Date())).append("] ");
        sb.append("【昨日VWAP获取】").append(code).append(" ");
        if (success) {
            sb.append(String.format(Locale.CHINA, "成功：日期=%s 真实VWAP=¥%.4f", date, vwap));
        } else {
            sb.append("失败：").append(errorMsg != null ? errorMsg : "未知原因")
                    .append("（本轮低开路径底仓判断将跳过，等待下次tick自动重试）");
        }
        sb.append("\n").append(repeat('-', 72)).append("\n");
        appendToFile(CAT_OTHER, sb.toString());
    }

    /**
     * 【2026-08-20 买入逻辑改造】记录一次底仓路径判定的分支选择过程——属于规则判断的一部分。
     * → 决策日志。
     */
    public void logBuyLogicTrace(String name, String code, String traceDetail) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(mTimeFmt.format(new java.util.Date())).append("] ");
        sb.append(name).append("(").append(code).append(") ");
        sb.append("【买入逻辑追踪】").append(traceDetail);
        sb.append("\n").append(repeat('-', 72)).append("\n");
        appendToFile(CAT_DECISION, sb.toString());
    }

    /**
     * 只保留最近keepDays天的决策日志，避免无限堆积。在监控服务每次启动时调用一次即可。
     * 现在需要对5个分类各自独立清理。
     */
    public void cleanupOldLogs() {
        cleanupOldLogs(14);
    }

    public void cleanupOldLogs(int keepDays) {
        java.io.File dir = getLogDir();
        java.util.Calendar cutoff = java.util.Calendar.getInstance();
        cutoff.add(java.util.Calendar.DAY_OF_YEAR, -keepDays);
        int totalDeleted = 0;
        for (String cat : ALL_CATEGORIES) {
            String prefix = cat + "_";
            java.io.File[] files = dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(".txt"));
            if (files == null || files.length == 0) continue;
            for (java.io.File f : files) {
                String n = f.getName();
                String dateStr = n.substring(prefix.length(), n.length() - ".txt".length());
                try {
                    java.util.Date d2 = mDayFmt.parse(dateStr);
                    if (d2 != null && d2.before(cutoff.getTime())) {
                        if (f.delete()) totalDeleted++;
                    }
                } catch (Exception e) {
                    // 文件名不是日期格式，跳过，不误删
                }
            }
        }
        if (totalDeleted > 0) {
            Log.i(TAG, "清理了" + totalDeleted + "个超过" + keepDays + "天的旧日志文件（5个分类合计）");
        }
    }
}
