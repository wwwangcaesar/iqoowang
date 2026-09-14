package com.monsieurmahjong.iqoowang.util;

import android.util.Log;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志系统 —— 按日期分文件夹、按股票分文件。
 *
 * 【2026-09-03 重构】之前是按"监控/决策/AI分析/操作/其他"5个类别分文件，同一支股票的
 * 完整脉络被拆得到处都是，看一支股票当天发生了什么要在5个文件里来回翻。现在改成：
 *
 *   decision_logs/
 *     2026-09-03/                     ← 每天一个文件夹
 *       600798_宁波海运.txt            ← 每支股票一个文件
 *       603587_地素时尚.txt
 *       ...
 *     2026-09-02/
 *       ...
 *
 * 每支股票的文件里，监控快照/决策记录/AI分析/手动操作按时间顺序混排在一起，每行用
 * 【类型】标出来是哪一类，看完整时间线不用再切文件。文件名在当天第一次写入时确定
 * （code_name，拿不到name就只用code），当天固定不变，不会因为中途拿到了name就改名，
 * 也绝不回头修改前一天已经写好的文件——每天都是全新写入。
 * 保留最近7天，超过自动删除整个日期文件夹。
 */
public class DecisionLogger {

    private static final String TAG = "DecisionLogger";
    private static DecisionLogger sInstance;
    private final android.content.Context mContext;
    private final java.text.SimpleDateFormat mDayFmt = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
    private final java.text.SimpleDateFormat mTimeFmt = new java.text.SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    /** code -> 最近一次见到的name，供只有code没有name的调用方（比如VWAP抓取）兜底解析文件名 */
    private final Map<String, String> mCodeNameCache = new ConcurrentHashMap<>();
    /** "day|code" -> 当天已经确定下来的文件基础名（不含扩展名），当天内固定，避免中途改名 */
    private final Map<String, String> mDayFileBaseCache = new ConcurrentHashMap<>();

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

    private java.io.File getLogRootDir() {
        java.io.File dir = new java.io.File(mContext.getExternalFilesDir(null), "decision_logs");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private java.io.File getDayDir(String dayStr) {
        java.io.File dir = new java.io.File(getLogRootDir(), dayStr);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private String sanitizeFileName(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
    }

    /** 解析（并在当天内固定住）某支股票当天日志文件的基础文件名（不含.txt） */
    private String resolveFileBase(String dayStr, String code, String name) {
        String cacheKey = dayStr + "|" + code;
        String cached = mDayFileBaseCache.get(cacheKey);
        if (cached != null) return cached;
        String resolvedName = (name != null && !name.isEmpty()) ? name : mCodeNameCache.get(code);
        String base = sanitizeFileName((resolvedName != null && !resolvedName.isEmpty())
                ? code + "_" + resolvedName : code);
        mDayFileBaseCache.put(cacheKey, base);
        return base;
    }

    private java.io.File getStockLogFile(String dayStr, String code, String name) {
        String base = resolveFileBase(dayStr, code, name);
        return new java.io.File(getDayDir(dayStr), base + ".txt");
    }

    /** 所有分类日志最终都走这里——按股票代码归档到"今天"这个文件夹下这支股票的文件里 */
    private synchronized void appendStockLog(String code, String name, String typeTag, String content) {
        if (code == null || code.isEmpty()) code = "_system"; // 极少数没有具体股票的系统级记录
        if (name != null && !name.isEmpty()) mCodeNameCache.put(code, name);
        String dayStr = mDayFmt.format(new java.util.Date());
        java.io.File f = getStockLogFile(dayStr, code, name);
        String line = "[" + mTimeFmt.format(new java.util.Date()) + "] 【" + typeTag + "】" + content + "\n";
        try (java.io.FileWriter fw = new java.io.FileWriter(f, true)) {
            fw.write(line);
        } catch (java.io.IOException e) {
            Log.e(TAG, "写日志失败[" + code + "]", e);
        }
    }

    private String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    // ══════════════════════════════════════════
    // 写入方法——方法名和参数跟改造前保持一致，调用方（RealtimeMonitorService等）不用改
    // ══════════════════════════════════════════

    /** 规则引擎命中且已立即推送通知时记录（AI 尚未完成，标记为分析进行中）。 */
    public void logRulePush(String name, String code, boolean holding, double holdCost,
                            double currentPrice, String watchStatus,
                            TradingRuleEngine.RuleResult ruleResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("候选池状态=").append(watchStatus).append(" ");
        if (holding) {
            sb.append(String.format(Locale.CHINA, "持仓中 成本¥%.2f 现价¥%.2f", holdCost, currentPrice));
        } else {
            sb.append(String.format(Locale.CHINA, "未买入 现价¥%.2f", currentPrice));
        }
        sb.append(" | ").append(ruleResult.actionLabel);
        sb.append(" | action=").append(TradingRuleEngine.actionToKey(ruleResult.action));
        sb.append("\n  规则依据：").append(ruleResult.note);
        if (ruleResult.metrics != null && !ruleResult.metrics.isEmpty()) {
            sb.append("\n  指标快照：").append(ruleResult.metrics);
        }
        if (ruleResult.stopLevel != TradingRuleEngine.StopLevel.NONE) {
            sb.append("\n  止损级别：").append(ruleResult.stopLevel.name());
            sb.append(" | 即时推送=").append(ruleResult.notifyImmediate);
        }
        sb.append("\n  用户通知：已推送（规则引擎独立决定，不等AI）");
        sb.append("\n  AI复核：分析进行中…");
        appendStockLog(code, name, "决策", sb.toString());
    }

    /** AI 异步定性分析完成后补充记录（不改变已推送的通知）。 */
    public void logAiSupplement(String name, String code, String actionLabel,
                               boolean aiConfirmed, String aiReason, String aiFullText) {
        StringBuilder sb = new StringBuilder();
        sb.append("针对已推送信号「").append(actionLabel).append("」\n");
        sb.append("  AI结论：").append(aiConfirmed ? "支持规则判断" : "存疑，请结合规则依据自行斟酌");
        sb.append("\n  AI说明：").append(aiReason != null ? aiReason : "（无）");
        if (aiFullText != null && !aiFullText.trim().isEmpty()) {
            sb.append("\n  AI完整输出：\n  ").append(aiFullText.trim().replace("\n", "\n  "));
        }
        appendStockLog(code, name, "AI分析", sb.toString());
    }

    /** 记录一次完整的信号评估（含规则未命中、AI驳回、双通过等所有情况）。
     *  规则相关内容记为"决策"，如果这一轮确实产生了AI结论，AI相关内容额外记一条"AI分析"，
     *  两者都写进同一支股票的同一个文件里，靠类型标签区分。 */
    public void logSignalEvaluation(String name, String code, boolean holding, double holdCost,
                                     double currentPrice, String watchStatus,
                                     TradingRuleEngine.RuleResult ruleResult,
                                     boolean aiConfirmed, String aiReason, String aiFullText,
                                     boolean notified) {
        StringBuilder ruleSb = new StringBuilder();
        ruleSb.append("候选池状态=").append(watchStatus).append(" ");
        ruleSb.append(holding
                ? String.format(Locale.CHINA, "持仓中 成本¥%.2f 现价¥%.2f", holdCost, currentPrice)
                : String.format(Locale.CHINA, "未买入 现价¥%.2f", currentPrice));

        if (ruleResult == null || ruleResult.action == TradingRuleEngine.Action.NONE) {
            ruleSb.append("\n  本轮无信号");
            if (ruleResult != null && ruleResult.note != null) ruleSb.append("（").append(ruleResult.note).append("）");
            if (ruleResult != null && ruleResult.metrics != null && !ruleResult.metrics.isEmpty()) {
                ruleSb.append("\n  指标快照：").append(ruleResult.metrics);
            }
        } else {
            ruleSb.append(" | ").append(ruleResult.actionLabel);
            ruleSb.append(" | action=").append(TradingRuleEngine.actionToKey(ruleResult.action));
            ruleSb.append("\n  规则依据：").append(ruleResult.note);
            if (ruleResult.metrics != null && !ruleResult.metrics.isEmpty()) {
                ruleSb.append("\n  指标快照：").append(ruleResult.metrics);
            }
            if (ruleResult.stopLevel != TradingRuleEngine.StopLevel.NONE) {
                ruleSb.append("\n  止损级别：").append(ruleResult.stopLevel.name());
                ruleSb.append(" | 即时推送=").append(ruleResult.notifyImmediate);
            }
            ruleSb.append("\n  用户通知：").append(notified ? "已推送" : "未推送（规则或AI未双通过，或不在推送窗口）");
            ruleSb.append("\n  AI复核结论见AI分析记录：").append(aiConfirmed ? "✓ 确认通过" : "✗ 未通过");
        }
        appendStockLog(code, name, "决策", ruleSb.toString());

        boolean hasAiContent = ruleResult != null && ruleResult.action != TradingRuleEngine.Action.NONE;
        if (hasAiContent) {
            StringBuilder aiSb = new StringBuilder();
            aiSb.append(ruleResult.actionLabel).append("\n");
            aiSb.append("  AI复核：").append(aiConfirmed ? "✓ 确认通过" : "✗ 未通过");
            aiSb.append("\n  AI结论：").append(aiReason != null ? aiReason : "（无）");
            if (aiFullText != null && !aiFullText.trim().isEmpty()) {
                aiSb.append("\n  AI完整输出：\n  ").append(aiFullText.trim().replace("\n", "\n  "));
            }
            appendStockLog(code, name, "AI分析", aiSb.toString());
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

    /** 单支股票的一行监控快照——周期性记录，不管本轮有没有触发信号。 */
    public void logMonitorLine(String code, String name, String line) {
        appendStockLog(code, name, "监控", line);
    }

    /** @deprecated 旧版多股票合并快照接口，保留兼容；新代码请用 logMonitorLine 按股票单独记 */
    public void logSnapshot(java.util.List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        for (String line : lines) {
            appendStockLog(null, null, "监控", line);
        }
    }

    /** 有具体股票归属的备注——排查/诊断用途。 */
    public void logNote(String code, String name, String text) {
        appendStockLog(code, name, "其他", text);
    }

    /** 没有具体股票归属的系统级备注（比如App启动、全局设置变更），归到当天的_system文件。 */
    public void logNote(String text) {
        appendStockLog(null, null, "系统", text);
    }

    /** 手动买卖弹窗成交/拒绝时记一笔——完整记录时间、方向、数量、价格、下单前可卖数量、成交结果。 */
    public void logManualTrade(String name, String code, String direction, double price, int quantity,
                                long resultId, int sellableBeforeTrade) {
        StringBuilder sb = new StringBuilder();
        sb.append("手动").append("BUY".equals(direction) ? "买入" : "卖出");
        sb.append(String.format(Locale.CHINA, " 请求%d股 @¥%.2f", quantity, price));
        if ("SELL".equals(direction)) {
            sb.append(String.format(Locale.CHINA, "，下单前可卖%d股", sellableBeforeTrade));
        }
        if (resultId > 0) {
            sb.append("\n  结果：成交，交易记录id=").append(resultId);
        } else if (resultId == -1) {
            sb.append("\n  结果：拒绝（资金不足，未写入任何数据）");
        } else if (resultId == -2) {
            sb.append("\n  结果：拒绝（T+1限制，未写入任何数据）");
        } else {
            sb.append("\n  结果：拒绝（id=").append(resultId).append("，未写入任何数据）");
        }
        appendStockLog(code, name, "操作", sb.toString());
    }

    /** 【2026-08-20买入逻辑改造】异步获取"昨日全天真实VWAP"的结果追踪。 */
    public void logPrevDayVwapFetch(String code, boolean success, double vwap, String date, String errorMsg) {
        StringBuilder sb = new StringBuilder();
        sb.append("昨日VWAP获取：");
        if (success) {
            sb.append(String.format(Locale.CHINA, "成功 日期=%s 真实VWAP=¥%.4f", date, vwap));
        } else {
            sb.append("失败：").append(errorMsg != null ? errorMsg : "未知原因")
                    .append("（本轮低开路径底仓判断将跳过，等待下次tick自动重试）");
        }
        appendStockLog(code, null, "决策", sb.toString());
    }

    /** 【2026-08-20买入逻辑改造】记录一次底仓路径判定的分支选择过程。 */
    public void logBuyLogicTrace(String name, String code, String traceDetail) {
        appendStockLog(code, name, "决策", "买入逻辑追踪：" + traceDetail);
    }

    // ══════════════════════════════════════════
    // 读取方法——供"决策日志"专属页面浏览
    // ══════════════════════════════════════════

    /** 有日志的日期列表（新→旧），即 decision_logs 下的日期文件夹名 */
    public String[] listLogDates() {
        java.io.File root = getLogRootDir();
        java.io.File[] dirs = root.listFiles(java.io.File::isDirectory);
        if (dirs == null) return new String[0];
        String[] dates = new String[dirs.length];
        for (int i = 0; i < dirs.length; i++) dates[i] = dirs[i].getName();
        java.util.Arrays.sort(dates, java.util.Collections.reverseOrder());
        return dates;
    }

    /** 某一天有日志的股票列表：[{code,name}, ...]（JSON字符串），按文件名排序 */
    public String getStocksForDateJson(String dayStr) {
        org.json.JSONArray arr = new org.json.JSONArray();
        java.io.File dir = new java.io.File(getLogRootDir(), dayStr);
        java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".txt"));
        if (files == null) return arr.toString();
        java.util.Arrays.sort(files, java.util.Comparator.comparing(java.io.File::getName));
        for (java.io.File f : files) {
            String base = f.getName().substring(0, f.getName().length() - 4); // 去掉.txt
            String code, name;
            int us = base.indexOf('_');
            if (us > 0) { code = base.substring(0, us); name = base.substring(us + 1); }
            else { code = base; name = ""; }
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("code", code);
                o.put("name", name.isEmpty() ? code : name);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    /** 指定日期+股票代码的完整日志内容（code需要用文件名前缀匹配，因为文件名是code_name） */
    public String getStockLogContent(String dayStr, String code) {
        java.io.File dir = new java.io.File(getLogRootDir(), dayStr);
        java.io.File[] files = dir.listFiles((d, n) ->
                (n.equals(code + ".txt") || n.startsWith(code + "_")) && n.endsWith(".txt"));
        if (files == null || files.length == 0) return "";
        try {
            return new String(java.nio.file.Files.readAllBytes(files[0].toPath()), "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "读日志失败", e);
            return "";
        }
    }

    public String getLogDirPath() {
        return getLogRootDir().getAbsolutePath();
    }

    /** 只保留最近keepDays天的日志文件夹，超过整个文件夹删除。App启动/监控开始时调用一次即可。 */
    public void cleanupOldLogs() {
        cleanupOldLogs(7);
    }

    public void cleanupOldLogs(int keepDays) {
        java.io.File root = getLogRootDir();
        java.io.File[] dirs = root.listFiles(java.io.File::isDirectory);
        if (dirs == null) return;
        java.util.Calendar cutoff = java.util.Calendar.getInstance();
        cutoff.add(java.util.Calendar.DAY_OF_YEAR, -keepDays);
        int deletedDirs = 0;
        for (java.io.File d : dirs) {
            try {
                java.util.Date parsed = mDayFmt.parse(d.getName());
                if (parsed != null && parsed.before(cutoff.getTime())) {
                    deleteRecursive(d);
                    deletedDirs++;
                }
            } catch (Exception ignored) {
                // 文件夹名不是日期格式，跳过，不误删
            }
        }
        if (deletedDirs > 0) {
            Log.i(TAG, "清理了" + deletedDirs + "个超过" + keepDays + "天的旧日志文件夹");
        }
    }

    private void deleteRecursive(java.io.File f) {
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) for (java.io.File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}
