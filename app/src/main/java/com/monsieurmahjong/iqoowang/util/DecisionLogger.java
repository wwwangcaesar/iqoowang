package com.monsieurmahjong.iqoowang.util;

import android.util.Log;

import java.util.Locale;

/**
 * 增强版决策日志 — 记录规则引擎+AI完整复核过程，供事后跟踪验证。
 */
public class DecisionLogger {

    private static final String TAG = "DecisionLogger";
    private static DecisionLogger sInstance;
    private final android.content.Context mContext;
    private final java.text.SimpleDateFormat mDayFmt = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
    private final java.text.SimpleDateFormat mTimeFmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA);

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

    private java.io.File getTodayLogFile() {
        return new java.io.File(getLogDir(), "decisions_" + mDayFmt.format(new java.util.Date()) + ".txt");
    }

    /**
     * 规则引擎命中且已立即推送通知时记录（AI 尚未完成，标记为分析进行中）。
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
        sb.append("\nAI复核：分析进行中…");
        sb.append("\n").append(repeat('-', 72)).append("\n");
        appendToFile(sb.toString());
    }

    /**
     * AI 异步定性分析完成后补充记录（不改变已推送的通知）。
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
        appendToFile(sb.toString());
    }

    /**
     * 记录一次完整的信号评估（含规则未命中、AI驳回、双通过等所有情况）。
     */
    public void logSignalEvaluation(String name, String code, boolean holding, double holdCost,
                                     double currentPrice, String watchStatus,
                                     TradingRuleEngine.RuleResult ruleResult,
                                     boolean aiConfirmed, String aiReason, String aiFullText,
                                     boolean notified) {
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

        if (ruleResult == null || ruleResult.action == TradingRuleEngine.Action.NONE) {
            sb.append("规则引擎：本轮无信号");
            if (ruleResult != null && ruleResult.note != null) sb.append("（").append(ruleResult.note).append("）");
            sb.append("\n");
            if (ruleResult != null && ruleResult.metrics != null && !ruleResult.metrics.isEmpty()) {
                sb.append("指标快照：").append(ruleResult.metrics).append("\n");
            }
        } else {
            sb.append("规则引擎：").append(ruleResult.actionLabel);
            sb.append(" | action=").append(TradingRuleEngine.actionToKey(ruleResult.action));
            sb.append("\n规则依据：").append(ruleResult.note);
            if (ruleResult.metrics != null && !ruleResult.metrics.isEmpty()) {
                sb.append("\n指标快照：").append(ruleResult.metrics);
            }
            if (ruleResult.stopLevel != TradingRuleEngine.StopLevel.NONE) {
                sb.append("\n止损级别：").append(ruleResult.stopLevel.name());
                sb.append(" | 即时推送=").append(ruleResult.notifyImmediate);
            }
            sb.append("\n");
            sb.append("AI复核：").append(aiConfirmed ? "✓ 确认通过" : "✗ 未通过");
            sb.append("\nAI结论：").append(aiReason != null ? aiReason : "（无）");
            if (aiFullText != null && !aiFullText.trim().isEmpty()) {
                sb.append("\nAI完整输出：\n").append(aiFullText.trim());
            }
            sb.append("\n用户通知：").append(notified ? "已推送" : "未推送（规则或AI未双通过，或不在推送窗口）");
        }

        sb.append("\n").append(repeat('-', 72)).append("\n");
        appendToFile(sb.toString());
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

    public void logNote(String text) {
        appendToFile("[" + mTimeFmt.format(new java.util.Date()) + "] " + text + "\n"
                + repeat('-', 72) + "\n");
    }

    private String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private synchronized void appendToFile(String content) {
        try (java.io.FileWriter fw = new java.io.FileWriter(getTodayLogFile(), true)) {
            fw.write(content);
        } catch (java.io.IOException e) {
            Log.e(TAG, "写日志失败", e);
        }
    }

    public String getTodayLogContent() { return readLogFile(getTodayLogFile()); }

    public String getLogContent(String dayStr) {
        return readLogFile(new java.io.File(getLogDir(), "decisions_" + dayStr + ".txt"));
    }

    private String readLogFile(java.io.File f) {
        if (!f.exists()) return "";
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "读日志失败", e);
            return "";
        }
    }

    public String[] listLogDates() {
        java.io.File dir = getLogDir();
        java.io.File[] files = dir.listFiles((d, name) -> name.startsWith("decisions_") && name.endsWith(".txt"));
        if (files == null) return new String[0];
        String[] dates = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            String n = files[i].getName();
            dates[i] = n.substring("decisions_".length(), n.length() - ".txt".length());
        }
        java.util.Arrays.sort(dates, java.util.Collections.reverseOrder());
        return dates;
    }

    public String getLogDirPath() {
        return getLogDir().getAbsolutePath();
    }
}
