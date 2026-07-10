package com.monsieurmahjong.iqoowang.agent;

import android.content.Context;
import android.util.Log;

import com.monsieurmahjong.iqoowang.dao.TradeRecord;
import com.monsieurmahjong.iqoowang.util.DatabaseManager;
import com.monsieurmahjong.iqoowang.util.MarketDataManager;
import com.monsieurmahjong.iqoowang.util.MarketIndexManager;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * AIContextBuilder — 把"AI需要知道的事实"从各个数据源里聚合出来，
 * 拼成结构化的文本块塞进 Prompt。
 *
 * 设计原则：所有会被AI用来做判断的"事实类"数字（大盘涨跌、涨跌家数、
 * 历史胜率、某股历史操作记录）全部在 Java 端算好、算准，AI 只负责
 * 基于这些既定事实做"解读和表达"，不负责自己编数字 —— 1.5B级别的本地小模型
 * 编数字的可靠性很差，让它编就是在制造幻觉。
 */
public class AIContextBuilder {

    private static final String TAG = "AIContextBuilder";
    private final Context mContext;

    public AIContextBuilder(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * 大盘环境摘要（指数趋势 + 涨跌家数）。
     * 若指数数据过期会尝试联网刷新 —— 【必须在后台线程调用】。
     */
    public String buildMarketContext() {
        StringBuilder sb = new StringBuilder();
        try {
            MarketIndexManager idx = MarketIndexManager.get();
            idx.ensureFreshBlocking(6000); // 最长等6秒，超时就用旧缓存，不卡死选股流程
            sb.append("【大盘指数】").append(idx.getMarketSummaryText()).append("\n");
        } catch (Exception e) {
            Log.w(TAG, "buildMarketContext index part failed", e);
            sb.append("【大盘指数】暂无数据\n");
        }

        try {
            String breadthJson = MarketDataManager.get().computeMarketBreadth();
            JSONObject b = new JSONObject(breadthJson);
            if (b.optBoolean("hasData", false)) {
                int total = b.optInt("total");
                int up = b.optInt("up");
                int down = b.optInt("down");
                int limitUp = b.optInt("limitUp");
                int limitDown = b.optInt("limitDown");
                double avgPct = b.optDouble("avgChangePct", 0);
                sb.append(String.format(Locale.CHINA,
                        "【市场宽度】%s：全市场%d支，上涨%d支/下跌%d支，涨停%d家/跌停%d家，平均涨幅%.2f%%。\n",
                        b.optString("tradeDate", ""), total, up, down, limitUp, limitDown, avgPct));
            } else {
                sb.append("【市场宽度】暂无缓存数据。\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "buildMarketContext breadth part failed", e);
        }
        return sb.toString();
    }

    /**
     * 用户历史操作摘要：总体胜率、总交易次数、当前持仓敞口。
     * 让AI的建议能考虑"这个人过往操作风格/成绩"，而不是脱离实际的空谈。
     */
    public String buildTradeHistoryContext() {
        try {
            DatabaseManager db = DatabaseManager.get();
            List<TradeRecord> allTrades = db.queryAllTrades();
            double winRate = db.getWinRate();
            double totalPnl = db.getTotalRealizedPnl();
            int positionCount = db.getAllPositions().size();

            if (allTrades.isEmpty()) {
                return "【历史操作】暂无历史交易记录，本次为首批建议，可适当保守。\n";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.CHINA,
                    "【历史操作】累计交易%d笔，历史胜率%.0f%%，累计已实现盈亏%.2f元，当前持仓%d支。\n",
                    allTrades.size(), winRate * 100, totalPnl, positionCount));

            if (winRate < 0.4 && allTrades.size() >= 5) {
                sb.append("注意：近期胜率偏低，本次分析需更严格把关信号质量，倾向保守。\n");
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "buildTradeHistoryContext failed", e);
            return "";
        }
    }

    /**
     * 某只候选股票是否有历史操作记录（买过/卖过、赚过/亏过），
     * 用于在分析这支股票时给出"旧相识"式的针对性提醒。
     */
    public String buildStockHistoryNote(String code) {
        try {
            List<TradeRecord> trades = DatabaseManager.get().queryTradesByCode(code);
            if (trades.isEmpty()) return "";
            int wins = 0, sells = 0;
            for (TradeRecord t : trades) {
                if ("SELL".equals(t.getDirection())) {
                    sells++;
                    if (t.getRealizedPnl() > 0) wins++;
                }
            }
            if (sells == 0) return "（历史：曾买入未平仓）";
            return String.format(Locale.CHINA, "（历史：交易过%d次，%d赢%d亏）",
                    sells, wins, sells - wins);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 个股近5日走势简述（涨跌幅序列），让AI"看到"最近的量价形态，
     * 而不是只有截面上的单一评分数字。
     */
    public String buildRecentTrend(String code) {
        try {
            List<MarketDataManager.KlineBar> bars = MarketDataManager.get().getCachedKline(code, 260);
            if (bars.size() < 2) return "";
            int n = bars.size();
            int start = Math.max(0, n - 5);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < n; i++) {
                MarketDataManager.KlineBar bar = bars.get(i);
                sb.append(String.format(Locale.CHINA, "%+.1f%% ", bar.changePct));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
}
