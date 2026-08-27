package com.monsieurmahjong.iqoowang.util;


import android.content.Context;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;


import com.monsieurmahjong.iqoowang.agent.LocalAIAgent;
import com.monsieurmahjong.iqoowang.http.EastMoneyApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WebView ↔ Java 双向通信桥
 *
 * JS调用Java：  window.Android.methodName(args)
 * Java调用JS：  webView.evaluateJavascript("jsFunc()", null)
 *
 * 注册方式（MainActivity中）：
 *   webView.addJavascriptInterface(new StockBridge(this, webView), "Android");
 */
public class StockBridge implements RealtimeMonitorService.Listener {

    private static final String TAG = "StockBridge";

    private final Context mContext;
    private final WebView mWebView;
    private final EastMoneyApi mApi;
    private final DatabaseManager mDb;
    private final LocalAIAgent mAgent;

    // 行情刷新定时任务
    private boolean mAutoRefresh = false;
    private final android.os.Handler mRefreshHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private static final int REFRESH_INTERVAL_MS = 3000; // 3秒刷新一次（交易时段）

    /** AI分析审核队列是否正在跑——串行执行，一支跑完才跑下一支，避免和自动补充分析/
     *  选股分析抢同一把AI推理锁（这正是后台自动补充分析经常打回"AI正在思考中"的原因） */
    private volatile boolean mAiReviewRunning = false;

    /** 单支AI审核外层超时——如果本地模型这次推理真的原生层卡死（onToken/onComplete都不再
     *  触发，这是你反馈的"卡住"的真实成因），不能让整条审核队列跟着永远卡在这一支上。
     *  实测本地模型单次推理正常情况下需要约 90 秒，因此超时阈值设成 3 分钟，留够充裕度，
     *  避免把“还在正常推理”误判成“卡死”。超时后强制把这一支标记为TIMEOUT并继续下一支，
     *  不影响其余股票的审核进度。 */
    private static final long AI_REVIEW_TIMEOUT_MS = 180_000; // 3分钟
    private final android.os.Handler mAiReviewHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 待确认信号pendingAction → 中文动作说明，口径对齐TradingRuleEngine.RuleResult.actionLabel */
    private static final Map<String, String> AI_REVIEW_ACTION_LABELS = new java.util.HashMap<>();
    static {
        AI_REVIEW_ACTION_LABELS.put("BUY_STARTER", "建议底仓");
        AI_REVIEW_ACTION_LABELS.put("ADD_HALF", "建议增加50%仓位");
        AI_REVIEW_ACTION_LABELS.put("ADD_POSITION", "建议增加50%仓位");
        AI_REVIEW_ACTION_LABELS.put("BUY_FULL", "建议满仓");
        AI_REVIEW_ACTION_LABELS.put("WARN_PRESSURE", "建议抛压");
        AI_REVIEW_ACTION_LABELS.put("STOP_LOSS", "建议立即清仓止损");
    }

    public StockBridge(Context context, WebView webView) {
        mContext = context;
        mWebView = webView;
        mApi = EastMoneyApi.get();
        mDb = DatabaseManager.get();
        mAgent = LocalAIAgent.get(context);
        RealtimeMonitorService.setListener(this);
    }

    // ══ RealtimeMonitorService.Listener ══ App在前台时，监控服务的事件实时推送到WebView

    @Override
    public void onSignalTriggered(String code, String name, String action, String note, double price) {
        try {
            JSONObject o = new JSONObject();
            o.put("code", code);
            o.put("name", name);
            o.put("action", action);
            o.put("note", note);
            o.put("price", price);
            String escaped = o.toString().replace("\\", "\\\\").replace("'", "\\'");
            evalJs("window.onSignalTriggered && window.onSignalTriggered('" + escaped + "')");
        } catch (Exception e) {
            Log.e(TAG, "onSignalTriggered push", e);
        }
    }

    @Override
    public void onTick(int watchCount, int posCount, String timeStr) {
        evalJs("window.onMonitorTick && window.onMonitorTick(" + watchCount + "," + posCount + ",'" + timeStr + "')");
    }

    // ══════════════════════════════════════════════
    // 行情数据接口
    // ══════════════════════════════════════════════

    /**
     * 获取K线数据
     * JS调用：Android.fetchKline("600519", "日K", 120)
     * 回调：window.onKlineData(jsonStr, name, code)
     */
    @JavascriptInterface
    public void fetchKline(String code, String period, int limit) {
        Log.d(TAG, "fetchKline: " + code + " " + period);
        mApi.fetchKline(code, period, limit, new EastMoneyApi.KlineCallback() {
            @Override
            public void onSuccess(List<EastMoneyApi.KlineBar> bars, String name) {
                String json = EastMoneyApi.klineBarsToJson(bars);
                String escaped = json.replace("\\", "\\\\").replace("'", "\\'");
                String nameEsc = name.replace("'", "\\'");
                evalJs("window.onKlineData && window.onKlineData('" +
                        escaped + "','" + nameEsc + "','" + code + "')");
            }
            @Override
            public void onError(String msg) {
                evalJs("window.onKlineError && window.onKlineError('" + code + "','" + msg + "')");
            }
        });
    }

    /**
     * 获取单股实时行情
     * JS调用：Android.fetchQuote("600519")
     * 回调：window.onQuoteData(jsonStr)
     */
    @JavascriptInterface
    public void fetchQuote(String code) {
        mApi.fetchQuote(code, new EastMoneyApi.QuoteCallback() {
            @Override
            public void onSuccess(EastMoneyApi.QuoteData data) {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("code", data.code);
                    obj.put("name", data.name);
                    obj.put("price", data.price);
                    obj.put("open", data.open);
                    obj.put("high", data.high);
                    obj.put("low", data.low);
                    obj.put("preClose", data.preClose);
                    obj.put("change", data.change);
                    obj.put("changePct", data.changePct);
                    obj.put("volume", data.volume);
                    obj.put("turnover", data.turnover);
                    String escaped = obj.toString().replace("'", "\\'");
                    evalJs("window.onQuoteData && window.onQuoteData('" + escaped + "')");
                } catch (Exception e) {
                    Log.e(TAG, "fetchQuote serialize", e);
                }
            }
            @Override
            public void onError(String msg) {
                evalJs("window.onQuoteError && window.onQuoteError('" + code + "')");
            }
        });
    }

    /**
     * 启动/停止持仓行情自动刷新
     * JS调用：Android.setAutoRefresh(true)
     */
    @JavascriptInterface
    public void setAutoRefresh(boolean enable) {
        mAutoRefresh = enable;
        if (enable) startAutoRefresh();
        else mRefreshHandler.removeCallbacksAndMessages(null);
    }

    /**
     * 拉取主板股票列表（选股扫描用）
     * JS调用：Android.fetchStockList("sh", 1)
     * 回调：window.onStockList(jsonStr, market, page)
     */
    @JavascriptInterface
    public void fetchStockList(String market, int page) {
        mApi.fetchStockList(market, page, new EastMoneyApi.StockListCallback() {
            @Override
            public void onSuccess(List<EastMoneyApi.QuoteData> stocks) {
                JSONArray arr = new JSONArray();
                for (EastMoneyApi.QuoteData q : stocks) {
                    try {
                        JSONObject obj = new JSONObject();
                        obj.put("code", q.code);
                        obj.put("name", q.name);
                        obj.put("price", q.price);
                        obj.put("changePct", q.changePct);
                        obj.put("high", q.high);
                        obj.put("low", q.low);
                        obj.put("open", q.open);
                        obj.put("preClose", q.preClose);
                        obj.put("cap", q.pe); // 已在API层转换为亿
                        obj.put("market", q.market);
                        arr.put(obj);
                    } catch (Exception ignored) {}
                }
                String escaped = arr.toString().replace("'", "\\'");
                evalJs("window.onStockList && window.onStockList('" +
                        escaped + "','" + market + "'," + page + ")");
            }
            @Override
            public void onError(String msg) {
                String esc = msg.replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
                evalJs("window.onStockListError && window.onStockListError('" + esc + "')");
            }
        });
    }

    // ══════════════════════════════════════════════
    // 交易 & 数据库接口
    // ══════════════════════════════════════════════

    /**
     * JS调用：Android.recordTrade(code,name,dir,price,qty,signal,score)
     * 不再需要WebView传cash/total——Java端insertTrade内部会根据完整交易流水自己算，
     * 这样才不会出现"重启App现金又变回10万"的问题。
     * 返回交易记录id；资金不足或出错返回 -1。
     */
    @JavascriptInterface
    public long recordTrade(String code, String name, String direction,
                            double price, int quantity, String signalType, int aiScore) {
        int sellableBefore = "SELL".equals(direction) ? mDb.getSellableQuantity(code) : 0;

        // 【2026-08-27修复】只有这一步（真正写数据库）失败才允许返回-1。
        // 之前整个方法体共用一个try/catch，导致下面"锦上添花"的辅助记录（AI经验值、
        // 待复盘登记、日志）里任何一步抛异常，都会被这个外层catch吞掉、让已经成功的
        // 交易被误判成失败——真实发生过的例子：TradeLessonManager忘了init()，
        // 导致每次整仓清仓都在这里炸出IllegalStateException，进而让前端弹出
        // "卖出失败，请检查持仓"，但数据库其实早已正确清空持仓，只有重启App才能看到真相。
        long id;
        try {
            id = mDb.insertTrade(code, name, direction, price, quantity, signalType, aiScore);
        } catch (Exception e) {
            Log.e(TAG, "recordTrade error", e);
            return -1;
        }

        try {
            DecisionLogger.get().logManualTrade(name, code, direction, price, quantity, id, sellableBefore);
        } catch (Exception e) {
            Log.e(TAG, "写手动交易日志失败（不影响本笔交易结果）", e);
        }

        if (id > 0 && "SELL".equals(direction)) {
            // 卖出成交后：按真实盈亏给本地AI加经验。单独try/catch，
            // 这一步出问题不该连累下面"标记待复盘"也执行不到。
            try {
                com.monsieurmahjong.iqoowang.dao.TradeRecord justInserted = findTradeById(id);
                double realizedPnl = justInserted != null ? justInserted.getRealizedPnl() : 0;
                mAgent.gainExpFromTrade(realizedPnl);
            } catch (Exception e) {
                Log.e(TAG, "交易后AI经验值更新失败（不影响本笔交易结果）", e);
            }

            // 检查这次卖出是否让持仓彻底清零——清零说明"买入到卖出"一个完整周期结束了，
            // 记一条待复盘，不自动跑AI。同样单独try/catch，绝不能让这里的失败
            // 反过来让上面已经成功的insertTrade被报告为失败。
            try {
                com.monsieurmahjong.iqoowang.dao.Position posAfter = mDb.getPositionByCode(code);
                if (posAfter == null || posAfter.getQuantity() <= 0) {
                    TradeLessonManager.get().markCycleClosed(code, name, id, System.currentTimeMillis());
                }
            } catch (Exception e) {
                Log.e(TAG, "标记待复盘周期失败（不影响本笔交易结果）", e);
            }
        }
        return id;
    }
    /** 账户核心数据（现金/总资产/总盈亏/今日盈亏），WebView启动和每次交易后都要刷新这个 */
    @JavascriptInterface
    public String getAccountSummary() {
        return mDb.getAccountSummaryJson();
    }

    /**
     * 预估手续费（买卖弹窗展示用），跟Java端insertTrade用的是同一套公式(mDb.calcTotalFee)，
     * 保证展示的预估值和实际扣款完全一致。
     */
    @JavascriptInterface
    public double estimateFee(double price, int quantity, String direction, String code) {
        double amount = price * quantity;
        return mDb.calcTotalFee(amount, direction, code);
    }

    /**
     * 获取所有持仓（JSON）
     * JS调用：Android.getPositions()
     * 返回：JSON字符串
     */
    @JavascriptInterface
    public String getPositions() {
        return mDb.getPositionsJson();
    }

    /**
     * 某只股票当前可T+1卖出的数量（持仓总量减去今日买入尚未过户部分）。
     * 卖出弹窗用来限制满仓/最大可卖股数，避免提交一笔实际会被T+1拒绝的卖单。
     * JS调用：Android.getSellableQuantity("600519")
     */
    @JavascriptInterface
    public int getSellableQuantity(String code) {
        return mDb.getSellableQuantity(code);
    }

    /**
     * 获取近N天资产曲线数据
     * JS调用：Android.getDailyAsset(30)
     */
    @JavascriptInterface
    public String getDailyAsset(int days) {
        return mDb.getDailyAssetJson(days);
    }

    /**
     * 获取交易历史
     * JS调用：Android.getTradeHistory(50)
     */
    @JavascriptInterface
    public String getTradeHistory(int limit) {
        return mDb.getTradeHistoryJson(limit);
    }

    /**
     * 获取统计数据
     * JS调用：Android.getStats()
     * 返回：{"totalPnl":2000,"winRate":0.65,"decisions":20}
     */
    @JavascriptInterface
    public String getStats() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("totalPnl", mDb.getTotalRealizedPnl());
            obj.put("winRate", mDb.getWinRate());
            obj.put("wins", mDb.getWinCount());
            obj.put("decisions", mDb.queryAllTrades().size());
            return obj.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // ══════════════════════════════════════════════
    // 交易规则参数配置接口
    // ══════════════════════════════════════════════

    /**
     * 当前生效的完整交易规则参数（内置默认值叠加用户覆盖后的最终值），
     * 供参数配置面板回显当前值。
     * JS调用：Android.getTradingConfig()
     */
    @JavascriptInterface
    public String getTradingConfig() {
        return TradingRuleConfig.get().toJson().toString();
    }

    /**
     * 保存用户在参数配置面板里调整过的参数——只需传有改动的字段，其余保持不变。
     * 立即对运行中的监控生效（TradingRuleEngine用的是同一个单例），并持久化到
     * App内部可写目录，下次冷启动也会加载。assets目录只读，无法直接写回，
     * 所以持久化走的是单独的覆盖文件，见TradingRuleConfig.saveOverrides()。
     * JS调用：Android.saveTradingConfig(jsonStr)，返回是否保存成功
     */
    @JavascriptInterface
    public boolean saveTradingConfig(String updatesJson) {
        try {
            return TradingRuleConfig.saveOverrides(mContext, new JSONObject(updatesJson));
        } catch (Exception e) {
            Log.e(TAG, "saveTradingConfig error", e);
            return false;
        }
    }

    /**
     * 恢复全部参数为内置默认值（删除用户覆盖文件）。
     * JS调用：Android.resetTradingConfig()
     */
    @JavascriptInterface
    public boolean resetTradingConfig() {
        return TradingRuleConfig.resetToDefault(mContext);
    }

    // ══════════════════════════════════════════════
    // 本地AI Agent接口
    // ══════════════════════════════════════════════

    /**
     * AI分析选股结果（本地Qwen推理）
     * JS调用：Android.aiAnalyzeScreenResult(jsonStr)
     * 回调：window.onAiAnalysis(resultText)
     */
    @JavascriptInterface
    public void aiAnalyzeScreenResult(String screenResultJson) {
        Log.d(TAG, "aiAnalyzeScreenResult len=" + screenResultJson.length());
        mAgent.analyzeScreenResult(screenResultJson, new LocalAIAgent.AICallback() {
            @Override
            public void onToken(String token) {
                // 流式输出token，实时推送到WebView
                String escaped = token.replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n");
                evalJs("window.onAiToken && window.onAiToken('" + escaped + "')");
            }
            @Override
            public void onComplete(String fullText) {
                String escaped = fullText.replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n");
                evalJs("window.onAiAnalysis && window.onAiAnalysis('" + escaped + "')");
                // 进化经验值
                evalJs("window.evolveAgent && window.evolveAgent(6)");
            }
            @Override
            public void onError(String msg) {
                evalJs("window.onAiAnalysis && window.onAiAnalysis('本地分析引擎繁忙，请稍后重试')");
            }
        });
    }

    /**
     * AI对话（本地推理）
     * JS调用：Android.aiChat(message, historyJson)
     * 回调：window.onAiChatToken(token) / window.onAiChatReply(text)
     */
    @JavascriptInterface
    public void aiChat(String message, String historyJson) {
        mAgent.chat(message, historyJson, new LocalAIAgent.AICallback() {
            @Override
            public void onToken(String token) {
                String escaped = token.replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n");
                evalJs("window.onAiChatToken && window.onAiChatToken('" + escaped + "')");
            }
            @Override
            public void onComplete(String fullText) {
                String escaped = fullText.replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n");
                evalJs("window.onAiChatReply && window.onAiChatReply('" + escaped + "')");
                evalJs("window.evolveAgent && window.evolveAgent(3)");
            }
            @Override
            public void onError(String msg) {
                evalJs("window.onAiChatReply && window.onAiChatReply('思考中断，请重试...')");
            }
        });
    }

    /**
     * 获取AI Agent状态
     * JS调用：Android.getAgentStatus()
     */
    @JavascriptInterface
    public String getAgentStatus() {
        return mAgent.getStatusJson();
    }

    /**
     * 启动AI状态轮询（模型加载是异步的，JS需要轮询才能知道何时完成）
     * JS调用：Android.startAIStatusPolling()
     * 当模型加载完成时回调：window.onAIModelReady(statusJson)
     */
    @JavascriptInterface
    public void startAIStatusPolling() {
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final int[] attempts = {0};
        final Runnable[] poll = {null};
        poll[0] = () -> {
            attempts[0]++;
            String statusJson = mAgent.getStatusJson();
            try {
                JSONObject status = new JSONObject(statusJson);
                boolean llmReady = status.optBoolean("llmReady", false);
                Log.d(TAG, "AI状态轮询 #" + attempts[0] + " llmReady=" + llmReady);

                if (llmReady) {
                    // 模型加载完成，通知JS
                    String escaped = statusJson.replace("'", "\\'");
                    evalJs("window.onAIModelReady && window.onAIModelReady('" + escaped + "')");
                    Log.i(TAG, "✅ AI模型就绪，停止轮询");
                } else if (attempts[0] < 60) {
                    // 未就绪，5秒后再查
                    h.postDelayed(poll[0], 5000);
                } else {
                    // 5分钟后放弃
                    Log.w(TAG, "AI加载超时（5分钟），停止轮询");
                    evalJs("window.onAIModelTimeout && window.onAIModelTimeout()");
                }
            } catch (Exception e) {
                Log.e(TAG, "AI轮询解析错误", e);
            }
        };
        // 延迟3秒开始轮询（给模型加载时间）
        h.postDelayed(poll[0], 3000);
    }

    /**
     * 预热AI模型
     * JS调用：Android.warmupAI()
     */
    @JavascriptInterface
    public void warmupAI() {
        mAgent.warmup();
    }

    /**
     * 教AI一条新话术（知识库注入式学习，非模型权重级微调，见LocalAIAgent.learnWisdom注释）
     * JS调用：Android.learnWisdom("你教的话术原文")
     * 回调：window.onWisdomToken(token) / window.onWisdomLearned(summary) / window.onWisdomError(msg)
     */
    @JavascriptInterface
    public void learnWisdom(String rawText) {
        mAgent.learnWisdom(rawText, new LocalAIAgent.AICallback() {
            @Override
            public void onToken(String token) {
                String escaped = token.replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n");
                evalJs("window.onWisdomToken && window.onWisdomToken('" + escaped + "')");
            }
            @Override
            public void onComplete(String summary) {
                String escaped = summary.replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n");
                evalJs("window.onWisdomLearned && window.onWisdomLearned('" + escaped + "')");
            }
            @Override
            public void onError(String msg) {
                String esc = msg.replace("'", "\\'");
                evalJs("window.onWisdomError && window.onWisdomError('" + esc + "')");
            }
        });
    }

    /** 话术学习历史（进化记录列表） */
    @JavascriptInterface
    public String getWisdomLog() {
        return WisdomManager.get().getAllJson();
    }

    // ══ 交易周期复盘（买入到清仓完整走完后手动触发AI总结）══

    /**
     * 【2026-08-20新增】候选池最近一次tick缓存的水线/VWAP/量比快照，供买卖弹窗算
     * "现价偏离VWAP多少"用（四格原则提醒）。没缓存过（比如App刚重启还没tick过）就返回"{}"，
     * 前端要自行判断兜底，不要假设一定有数据。
     * JS调用：Android.getLiveMetrics(code) → {"waterLine":x,"vwap":y,"volRatio":z} 或 "{}"
     */
    @JavascriptInterface
    public String getLiveMetrics(String code) {
        double[] m = WatchlistManager.get().getLiveMetrics(code);
        if (m == null) return "{}";
        try {
            JSONObject o = new JSONObject();
            o.put("waterLine", m[0]);
            o.put("vwap", m[1]);
            o.put("volRatio", m[2]);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 待复盘的交易周期列表（买入到清仓已经完整走完，但用户还没点"生成复盘"的） */
    @JavascriptInterface
    public String getPendingTradeReviews() {
        return TradeLessonManager.get().getPendingJson();
    }

    /** 已复盘的交易周期列表，供"AI大脑"页展示历史复盘记录 */
    @JavascriptInterface
    public String getReviewedTradeLessons() {
        return TradeLessonManager.get().getReviewedJson();
    }

    /**
     * 用户在"AI大脑"页手动点击某条待复盘记录，触发本地AI生成复盘总结。
     * 不自动执行，需要人主动点击，避免卡顿。
     * JS调用：Android.startTradeReview(lessonId)
     * 回调：window.onTradeReviewToken(token) / window.onTradeReviewDone(lessonId, resultText) / window.onTradeReviewError(msg)
     */
    @JavascriptInterface
    public void startTradeReview(long lessonId) {
        mAgent.summarizeTradeCycle(lessonId, new LocalAIAgent.AICallback() {
            @Override
            public void onToken(String token) {
                String escaped = token.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
                evalJs("window.onTradeReviewToken && window.onTradeReviewToken('" + escaped + "')");
            }
            @Override
            public void onComplete(String fullText) {
                String escaped = fullText.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
                evalJs("window.onTradeReviewDone && window.onTradeReviewDone(" + lessonId + ",'" + escaped + "')");
            }
            @Override
            public void onError(String msg) {
                String esc = msg.replace("'", "\\'");
                evalJs("window.onTradeReviewError && window.onTradeReviewError('" + esc + "')");
            }
        });
    }

    // ══════════════════════════════════════════════
    // 行情数据下载与选股接口
    // ══════════════════════════════════════════════

    @JavascriptInterface
    public String estimateDownloadSize(String paramsJson) {
        try {
            JSONObject p = new JSONObject(paramsJson);
            return MarketDataManager.get().estimateDownloadSize(
                    p.optDouble("minPrice", 3.0),
                    p.optDouble("maxPrice", 50.0),
                    p.optDouble("minCap", 20.0),
                    p.optDouble("maxCap", 320.0),
                    p.optBoolean("exCY", true),
                    p.optBoolean("exKC", true),
                    p.optBoolean("exST", true)
            );
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void startMarketDataDownload(String paramsJson) {
        try {
            JSONObject p = new JSONObject(paramsJson);
            MarketDataManager.get().startDownload(
                    p.optDouble("minPrice", 3.0),
                    p.optDouble("maxPrice", 50.0),
                    p.optDouble("minCap", 20.0),
                    p.optDouble("maxCap", 320.0),
                    p.optBoolean("exCY", true),
                    p.optBoolean("exKC", true),
                    p.optBoolean("exST", true),
                    p.optString("boardFilter", "all"),
                    new MarketDataManager.DownloadCallback() {
                        @Override
                        public void onProgress(int current, int total, String phase) {
                            String esc = phase.replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
                            evalJs("window.onDownloadProgress && window.onDownloadProgress(" + current + "," + total + ",'" + esc + "')");
                        }
                        @Override
                        public void onComplete(int stockCount, int barCount) {
                            evalJs("window.onDownloadComplete && window.onDownloadComplete(" + stockCount + "," + barCount + ")");
                        }
                        @Override
                        public void onError(String msg) {
                            String esc = msg.replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
                            evalJs("window.onDownloadError && window.onDownloadError('" + esc + "')");
                        }
                    }
            );
        } catch (Exception e) {
            evalJs("window.onDownloadError && window.onDownloadError('参数错误')");
        }
    }

    @JavascriptInterface
    public void runRealScreener(String paramsJson) {
        try {
            JSONObject p = new JSONObject(paramsJson);
            MarketDataManager.get().runRealScreener(
                    p.optDouble("minPrice", 3.0),
                    p.optDouble("maxPrice", 50.0),
                    p.optDouble("minCap", 20.0),
                    p.optDouble("maxCap", 320.0),
                    p.optDouble("volMulti", 2.0),
                    p.optBoolean("exCY", true),
                    p.optBoolean("exKC", true),
                    p.optBoolean("exST", true),
                    p.optBoolean("exLT", true),
                    p.optBoolean("requireXianRenZhiLu", false),
                    p.optString("boardFilter", "all"),
                    new MarketDataManager.ScreenCallback() {
                        @Override
                        public void onResult(String resultJson) {
                            // 无论筛选出多少支（哪怕为0支），都把结果写入候选池持久化跟踪（day1入池）
                            try {
                                JSONArray arr = new JSONArray(resultJson);
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject r = arr.getJSONObject(i);
                                    WatchlistManager.get().addIfAbsent(
                                            r.optString("code"), r.optString("name"),
                                            r.optInt("score"), r.optString("signal"),
                                            r.optDouble("patternOpen", 0), r.optDouble("patternHigh", 0),
                                            r.optDouble("patternClose", 0), r.optDouble("patternLow", 0),
                                            r.optString("patternDate", null));
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "候选池入池失败", e);
                            }
                            String esc = resultJson.replace("\\", "\\\\").replace("'", "\\'");
                            evalJs("window.onScreenerResult && window.onScreenerResult('" + esc + "')");
                        }
                        @Override
                        public void onError(String msg) {
                            String esc = msg.replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
                            evalJs("window.onScreenerError && window.onScreenerError('" + esc + "')");
                        }
                    }
            );
        } catch (Exception e) {
            evalJs("window.onScreenerError && window.onScreenerError('参数错误')");
        }
    }

    @JavascriptInterface
    public String getDownloadStatus() {
        return MarketDataManager.get().getDownloadStatus();
    }

    // ══ 实时监控 & 候选池 ══

    /**
     * 启动实时监控（前台服务，锁屏/切后台也不中断）
     * JS调用：Android.startMonitor(60000) 或 Android.startMonitor(30000)
     */
    @JavascriptInterface
    public void startMonitor(long intervalMs) {
        RealtimeMonitorService.start(mContext, intervalMs > 0 ? intervalMs : RealtimeMonitorService.DEFAULT_INTERVAL_MS);
    }

    @JavascriptInterface
    public void stopMonitor() {
        RealtimeMonitorService.stop(mContext);
    }

    /**
     * 延迟N秒后发一条测试通知——点下去后你有时间锁屏/切到后台，等着看能不能正常收到。
     * JS调用：Android.testNotificationDelayed(10)
     */
    @JavascriptInterface
    public void testNotificationDelayed(int delaySeconds) {
        long delayMs = Math.max(0, delaySeconds) * 1000L;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> RealtimeMonitorService.sendTestNotification(mContext), delayMs);
    }

    /** 当前跟踪中的候选池（观察中/已建底仓/已加仓，不含止损/已移除的） */
    @JavascriptInterface
    public String getActiveWatchlist() {
        return WatchlistManager.get().getActiveWatchlistJson();
    }

    /** 全部候选池记录（含止损/已移除的历史） */
    @JavascriptInterface
    public String getAllWatchlist() {
        return WatchlistManager.get().getAllJson();
    }

    /** 手动从候选池移除（不硬删，只标记为已移除） */
    @JavascriptInterface
    public void removeFromWatchlist(String code) {
        WatchlistManager.get().removeManual(code);
    }

    /**
     * 用户确认一条待确认信号——真正把规则引擎+AI验证过的候选信号落地成终态
     * （建底仓/加仓/止损）。注意：这只是更新候选池状态方便你跟踪，
     * 实际买卖仍需你在持仓页自己录入交易记录。
     * JS调用：Android.confirmSignal(code)
     */
    @JavascriptInterface
    public void confirmSignal(String code) {
        WatchlistManager.get().confirmPending(code);
    }

    /** 用户忽略一条待确认信号——打回确认前的状态，继续观察，不采取任何行动 */
    @JavascriptInterface
    public void dismissSignal(String code) {
        WatchlistManager.get().dismissPending(code);
    }

    /** 当前所有待确认信号（PENDING_*状态） */
    @JavascriptInterface
    public String getPendingSignals() {
        JSONArray arr = new JSONArray();
        for (WatchlistManager.WatchlistItem it : WatchlistManager.get().getPendingSignals()) {
            try {
                JSONObject o = new JSONObject();
                o.put("code", it.code);
                o.put("name", it.name);
                o.put("status", it.status);
                o.put("pendingAction", it.pendingAction);
                o.put("pendingPrice", it.pendingPrice);
                o.put("pendingAiConfirmed", it.pendingAiConfirmed);
                o.put("pendingReason", it.pendingReason);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    // ══ AI分析审核（手动串行重新分析所有待确认信号，按推送时间排队）══

    /**
     * 【AI分析审核】手动触发：把当前所有待确认信号（含此前后台自动补充分析因为
     * 并发推理锁冲突/模型刚冷启动等原因失败、显示"存疑"或"AI正在思考中"的）
     * 按推送时间从早到晚排队，串行调用本地AI重新分析——一支跑完拿到结论再跑下一支，绝不并发。
     * 之所以设计成"点开App手动审核"而不是让后台自动补充分析更努力重试：本地AI(LocalAIAgent)
     * 全局只有一把推理锁，用户点这个按钮时人已经在看 App、模型大概率已加载/预热完成，
     * 比后台监控服务刚推送那一刻（可能还在冷启动/被其它AI调用占用）成功率高得多。
     * JS调用：Android.startAiReviewPendingSignals()
     * 回调：window.onAiReviewQueued(jsonArrayStr) 一次性给出初始队列（含排队顺序）
     *       window.onAiReviewProgress(jsonStr) 每支状态变化时推送一次（QUEUED/ANALYZING/CONFIRMED/DOUBTED/SKIPPED）
     *       window.onAiReviewFinished() 全部跑完
     *       window.onAiReviewError(msg) 无法启动时的提示（队列为空/正在审核中）
     */
    @JavascriptInterface
    public void startAiReviewPendingSignals() {
        if (mAiReviewRunning) {
            evalJs("window.onAiReviewError && window.onAiReviewError('AI审核正在进行中，请稍候')");
            return;
        }
        List<WatchlistManager.WatchlistItem> pending = new ArrayList<>(WatchlistManager.get().getPendingSignals());
        if (pending.isEmpty()) {
            evalJs("window.onAiReviewError && window.onAiReviewError('当前没有待确认信号')");
            return;
        }
        // getPendingSignals()内部按pending_at DESC排序，这里改成从早到晚——先推送的先分析
        java.util.Collections.sort(pending, (a, b) -> Long.compare(a.pendingAt, b.pendingAt));

        mAiReviewRunning = true;
        JSONArray initArr = new JSONArray();
        for (WatchlistManager.WatchlistItem it : pending) {
            try {
                JSONObject o = new JSONObject();
                o.put("code", it.code);
                o.put("name", it.name);
                o.put("pendingAction", it.pendingAction);
                o.put("pendingPrice", it.pendingPrice);
                o.put("reviewStatus", "QUEUED");
                initArr.put(o);
            } catch (Exception ignored) {}
        }
        String escInit = initArr.toString().replace("\\", "\\\\").replace("'", "\\'");
        evalJs("window.onAiReviewQueued && window.onAiReviewQueued('" + escInit + "')");

        processAiReviewQueue(pending, 0);
    }

    /** 串行处理AI审核队列：上一支彻底跑完（成功/失败/超时/被跳过）才会处理下一支。
     *  带外层超时兼底：即使本次推理真的卡死不返回，队列也不会永远卡住，保证"不会卡住"。 */
    private void processAiReviewQueue(List<WatchlistManager.WatchlistItem> queue, int index) {
        if (index >= queue.size()) {
            mAiReviewRunning = false;
            evalJs("window.onAiReviewFinished && window.onAiReviewFinished()");
            return;
        }
        WatchlistManager.WatchlistItem queued = queue.get(index);
        // 分析前用最新数据库状态重新确认——用户可能已经在这支股票上手动确认/忽略过了
        WatchlistManager.WatchlistItem fresh = WatchlistManager.get().getByCode(queued.code);
        if (fresh == null || fresh.pendingAction == null) {
            pushAiReviewProgress(queued.code, queued.name, "SKIPPED", false, "该信号已被处理，跳过", "");
            processAiReviewQueue(queue, index + 1);
            return;
        }

        pushAiReviewProgress(fresh.code, fresh.name, "ANALYZING", false, "本地AI分析中…", "");

        final boolean[] handled = {false};
        Runnable timeoutRunnable = () -> {
            if (handled[0]) return;
            handled[0] = true;
            Log.w(TAG, "AI审核超时(" + (AI_REVIEW_TIMEOUT_MS / 1000) + "秒无响应)，跳过继续下一支: " + fresh.code);
            LocalAIAgent.VerifyResult vr = new LocalAIAgent.VerifyResult();
            vr.confirmed = false;
            vr.reason = "AI分析超时无响应（可能本次推理卡住），请结合规则依据自行判断，或稍后再次点击AI分析审核重试";
            finishOneReview(queue, index, fresh, vr, "AI_TIMEOUT");
        };
        mAiReviewHandler.postDelayed(timeoutRunnable, AI_REVIEW_TIMEOUT_MS);

        List<String> codes = new ArrayList<>();
        codes.add(fresh.code);
        RealtimeQuoteManager.get().fetchBatch(codes, (quotes, failedCodes) -> {
            if (handled[0]) return; // 行情还没拿到就已经超时了，不再继续发起AI调用
            RealtimeQuoteManager.Quote quote = quotes.get(fresh.code);
            String actionLabel = AI_REVIEW_ACTION_LABELS.containsKey(fresh.pendingAction)
                    ? AI_REVIEW_ACTION_LABELS.get(fresh.pendingAction) : fresh.pendingAction;
            // 【修复】之前这里 metrics 传的是空字符串，持仓也从未传——AI只能凭规则文字描述泛泛而谈，
            // 缺少具体数字支撑，这也是列表页分析比AI大脑聊天浅的主因之一。现补上两个：
            String metrics = buildAiReviewMetricsString(fresh.code);
            com.monsieurmahjong.iqoowang.dao.Position position = DatabaseManager.get().getPositionByCode(fresh.code);
            mAgent.verifySignal(fresh.code, fresh.name, fresh.pendingAction, actionLabel,
                    fresh.pendingReason, metrics, quote, position,
                    new LocalAIAgent.AICallback() {
                        @Override public void onToken(String token) {}

                        @Override
                        public void onComplete(String fullText) {
                            if (handled[0]) return;
                            handled[0] = true;
                            mAiReviewHandler.removeCallbacks(timeoutRunnable);
                            LocalAIAgent.VerifyResult vr = LocalAIAgent.parseVerifyResult(fullText);
                            finishOneReview(queue, index, fresh, vr, fullText);
                        }

                        @Override
                        public void onError(String msg) {
                            if (handled[0]) return;
                            handled[0] = true;
                            mAiReviewHandler.removeCallbacks(timeoutRunnable);
                            LocalAIAgent.VerifyResult vr = new LocalAIAgent.VerifyResult();
                            vr.confirmed = false;
                            vr.reason = "AI分析失败：" + msg;
                            finishOneReview(queue, index, fresh, vr, "AI_ERROR: " + msg);
                        }
                    });
        });
    }

    /** 一支处理完成（无论成功/失败/超时）都走这里：回填AI结论+写日志+推进到下一支，
     *  确保无论哪种结局都能让队列继续前进。AI明确支持时补一条确认通知（跟后台自动复核
     *  同样的缺口：之前手动AI审核确认支持后也从不推通知，用户只能靠自己打开App才发现）。 */
    private void finishOneReview(List<WatchlistManager.WatchlistItem> queue, int index,
                                  WatchlistManager.WatchlistItem fresh,
                                  LocalAIAgent.VerifyResult vr, String fullText) {
        WatchlistManager.get().updatePendingAiResult(fresh.code, vr.confirmed, vr.reason, fullText);
        String actionLabel = AI_REVIEW_ACTION_LABELS.containsKey(fresh.pendingAction)
                ? AI_REVIEW_ACTION_LABELS.get(fresh.pendingAction) : fresh.pendingAction;
        try {
            DecisionLogger.get().logAiSupplement(fresh.name, fresh.code,
                    actionLabel + "（AI分析审核·手动触发）",
                    vr.confirmed, vr.reason, fullText);
        } catch (Exception e) {
            Log.e(TAG, "写AI审核日志失败", e);
        }
        if (vr.confirmed) {
            try {
                RealtimeMonitorService.fireConfirmedAlert(mContext, fresh.code, fresh.name,
                        actionLabel, vr.reason, fresh.pendingPrice);
            } catch (Exception e) {
                Log.e(TAG, "发送AI确认通知失败", e);
            }
        }
        String status = "AI_TIMEOUT".equals(fullText) ? "TIMEOUT" : (vr.confirmed ? "CONFIRMED" : "DOUBTED");
        pushAiReviewProgress(fresh.code, fresh.name, status, vr.confirmed, vr.reason, fullText);
        processAiReviewQueue(queue, index + 1);
    }

    private void pushAiReviewProgress(String code, String name, String reviewStatus,
                                       boolean confirmed, String reason, String fullText) {
        try {
            JSONObject o = new JSONObject();
            o.put("code", code);
            o.put("name", name);
            o.put("reviewStatus", reviewStatus);
            o.put("confirmed", confirmed);
            o.put("reason", reason);
            o.put("fullText", fullText);
            String esc = o.toString().replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
            evalJs("window.onAiReviewProgress && window.onAiReviewProgress('" + esc + "')");
        } catch (Exception e) {
            Log.e(TAG, "pushAiReviewProgress", e);
        }
    }

    /** 从候选池最近一次tick缓存的水线/VWAP/量比快照拼出跟候选池卡片同一口径的指标文本，
     *  没有缓存（比如App刚重启还没tick过）就返回空字符串，交给AI凭规则依据本身判断，不会报错。 */
    private String buildAiReviewMetricsString(String code) {
        double[] m = WatchlistManager.get().getLiveMetrics(code);
        if (m == null) return "";
        return String.format(java.util.Locale.CHINA, "水线¥%.2f VWAP¥%.2f 量比%.2fx", m[0], m[1], m[2]);
    }

    /** 今天的决策日志内容（无需切到文件管理器，App内直接看） */
    @JavascriptInterface
    public String getTodayDecisionLog() {
        return DecisionLogger.get().getTodayLogContent();
    }

    /** 指定日期（yyyy-MM-dd）的决策日志内容 */
    @JavascriptInterface
    public String getDecisionLog(String dayStr) {
        return DecisionLogger.get().getLogContent(dayStr);
    }

    /** 有日志的日期列表（新→旧），供前端做日期选择 */
    @JavascriptInterface
    public String getDecisionLogDates() {
        return new JSONArray(java.util.Arrays.asList(DecisionLogger.get().listLogDates())).toString();
    }

    /** 日志文件实际存放路径，也可以用文件管理器/USB直接去导出 */
    @JavascriptInterface
    public String getDecisionLogDirPath() {
        return DecisionLogger.get().getLogDirPath();
    }

    // ══════════════════════════════════════════════
    // 工具接口
    // ══════════════════════════════════════════════

    /**
     * 震动反馈
     * JS调用：Android.vibrate(50)
     */
    @JavascriptInterface
    public void vibrate(int ms) {
        try {
            android.os.Vibrator v = (android.os.Vibrator)
                    mContext.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(ms);
        } catch (Exception ignored) {}
    }

    /**
     * 保存设置到SharedPreferences
     */
    @JavascriptInterface
    public void savePrefs(String key, String value) {
        mContext.getSharedPreferences("sm_prefs", Context.MODE_PRIVATE)
                .edit().putString(key, value).apply();
    }

    @JavascriptInterface
    public String loadPrefs(String key, String defaultVal) {
        return mContext.getSharedPreferences("sm_prefs", Context.MODE_PRIVATE)
                .getString(key, defaultVal);
    }

    /**
     * 【危险操作】清空全部交易/持仓/资产历史数据，重新开始记录真实操盘营收。
     * 不清WisdomManager手动教过的话术（那是你教的操盘经验，跟"营收/持仓"是两回事），
     * 也不清决策日志（那个有自己独立的14天保留机制）。
     * 会一并清空交易周期复盘记录，并重置本地AI的等级/经验——以后经验完全由真实盈亏
     * 驱动，交易记录都清空了，旧经验留着对不上号。前端必须先做二次确认再调这个接口。
     * JS调用：Android.clearAllTradingData()，返回是否成功
     */
    @JavascriptInterface
    public boolean clearAllTradingData() {
        try {
            mDb.clearAllTradingData();
            TradeLessonManager.get().clearAll();
            mAgent.resetLevelAndExp();
            Log.i(TAG, "用户触发：已清空全部交易数据（交易/持仓/资产历史/复盘记录/AI等级经验）");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "clearAllTradingData失败", e);
            return false;
        }
    }

    // ══════════════════════════════════════════════
    // 内部工具
    // ══════════════════════════════════════════════

    /** 在主线程执行JS */
    private void evalJs(final String js) {
        mWebView.post(() -> mWebView.evaluateJavascript(js, null));
    }

    /** 按id查单笔交易记录——insertTrade()只返回id，不返回完整记录，
     *  recordTrade()需要拿到刚刚那笔卖出的realizedPnl才能给本地AI加经验。
     *  只在卖出成交时调用，频率低，线性查找足够快。 */
    private com.monsieurmahjong.iqoowang.dao.TradeRecord findTradeById(long id) {
        for (com.monsieurmahjong.iqoowang.dao.TradeRecord t : mDb.queryAllTrades()) {
            if (t.getId() == id) return t;
        }
        return null;
    }

    /** 持仓行情自动刷新 */
    private void startAutoRefresh() {
        mRefreshHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!mAutoRefresh) return;
                refreshPositionPrices();
                mRefreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        }, REFRESH_INTERVAL_MS);
    }

    private void refreshPositionPrices() {
        String posJson = mDb.getPositionsJson();
        try {
            JSONArray arr = new JSONArray(posJson);
            List<String> codes = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                codes.add(arr.getJSONObject(i).getString("code"));
            }
            if (codes.isEmpty()) return;

            RealtimeQuoteManager.get().fetchBatch(codes,
                    (quotes, failedCodes) -> {
                        if (!failedCodes.isEmpty()) {
                            Log.w(TAG, "refreshPositionPrices: " + failedCodes.size() + "支两个数据源都未拿到: " + failedCodes);
                        }
                        if (quotes.isEmpty()) return;
                        JSONObject priceMap = new JSONObject();
                        for (Map.Entry<String, RealtimeQuoteManager.Quote> e : quotes.entrySet()) {
                            try { priceMap.put(e.getKey(), e.getValue().price); }
                            catch (Exception ignored) {}
                        }
                        // 更新DB
                        mDb.batchUpdatePrices(priceMap);
                        // 推送到WebView
                        String escaped = priceMap.toString().replace("\\", "\\\\").replace("'", "\\'");
                        evalJs("window.onPriceUpdate && window.onPriceUpdate('" + escaped + "')");
                    });
        } catch (Exception e) {
            Log.e(TAG, "refreshPositionPrices parse", e);
        }
    }
}
