package com.monsieurmahjong.iqoowang.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * DecisionLogger — 本地决策日志
 *
 * 每次规则引擎命中候选信号、AI二次验证完成后，无论最终是"确认"还是"存疑"，
 * 都会记一条日志，格式类似：
 *
 *   [2026-07-13 10:10:23] 太极集团(600129) 持仓中 买入价¥13.20 现价¥12.85
 *   规则引擎：跌破前一日最低价12.90，建议止损清仓
 *   AI判断：确认 | 理由：现价已跌破止损线且成交量放大，继续持有风险较高，建议按纪律止损
 *   ------------------------------------------------------------
 *
 * 日志按天分文件，存在App专属外部存储目录，方便你用文件管理器/USB直接导出，
 * 不需要连电脑看logcat。
 */
public class DecisionLogger {

    private static final String TAG = "DecisionLogger";
    private static DecisionLogger sInstance;
    private final Context mContext;
    private final SimpleDateFormat mDayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
    private final SimpleDateFormat mTimeFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    public static void init(Context context) {
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

    private DecisionLogger(Context context) {
        mContext = context;
    }

    private File getLogDir() {
        File dir = new File(mContext.getExternalFilesDir(null), "decision_logs");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File getTodayLogFile() {
        String dayStr = mDayFmt.format(new Date());
        return new File(getLogDir(), "decisions_" + dayStr + ".txt");
    }

    /**
     * 记一条判断日志。
     *
     * @param name          股票名称
     * @param code          股票代码
     * @param holding       是否持仓中（true=持仓，用来决定日志里写"买入价..."还是"未买入"）
     * @param holdCost      持仓成本价（未持仓时传0，日志里不显示）
     * @param currentPrice  当前实时价格
     * @param ruleAction    规则引擎候选动作的中文描述（如"建议止损清仓"/"建议买入底仓"）
     * @param ruleNote      规则引擎判断依据
     * @param aiConfirmed   AI是否认可
     * @param aiReason      AI给出的理由
     */
    public void logDecision(String name, String code, boolean holding, double holdCost,
                             double currentPrice, String ruleAction, String ruleNote,
                             boolean aiConfirmed, String aiReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(mTimeFmt.format(new Date())).append("] ");
        sb.append(name).append("(").append(code).append(") ");
        if (holding) {
            sb.append(String.format(Locale.CHINA, "持仓中 买入价¥%.2f 现价¥%.2f", holdCost, currentPrice));
        } else {
            sb.append(String.format(Locale.CHINA, "未买入 现价¥%.2f", currentPrice));
        }
        sb.append("\n规则引擎：").append(ruleAction).append("（依据：").append(ruleNote).append("）");
        sb.append("\nAI判断：").append(aiConfirmed ? "确认" : "存疑").append(" | 理由：").append(aiReason);
        sb.append("\n").append(repeat('-', 60)).append("\n");

        appendToFile(sb.toString());
    }

    /** 简单文本消息也可以记（比如"数据异常跳过本轮判断"这类），保持日志连续可读 */
    public void logNote(String text) {
        String line = "[" + mTimeFmt.format(new Date()) + "] " + text + "\n"
                + repeat('-', 60) + "\n";
        appendToFile(line);
    }

    private String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private synchronized void appendToFile(String content) {
        try (FileWriter fw = new FileWriter(getTodayLogFile(), true)) {
            fw.write(content);
        } catch (IOException e) {
            Log.e(TAG, "写日志失败", e);
        }
    }

    /** 读取今天的日志内容，供App内直接查看（不用切到文件管理器） */
    public String getTodayLogContent() {
        return readLogFile(getTodayLogFile());
    }

    /** 读取指定日期（yyyy-MM-dd）的日志内容 */
    public String getLogContent(String dayStr) {
        return readLogFile(new File(getLogDir(), "decisions_" + dayStr + ".txt"));
    }

    private String readLogFile(File f) {
        if (!f.exists()) return "";
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(bytes, "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "读日志失败", e);
            return "";
        }
    }

    /** 列出所有有日志的日期（供前端做一个日期选择） */
    public String[] listLogDates() {
        File dir = getLogDir();
        File[] files = dir.listFiles((d, name) -> name.startsWith("decisions_") && name.endsWith(".txt"));
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
