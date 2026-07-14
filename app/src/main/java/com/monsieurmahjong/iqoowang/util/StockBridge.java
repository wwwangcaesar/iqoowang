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
     * 记录交易（买入/卖出）
     * JS调用：Android.recordTrade(code,name,dir,price,qty,signal,score,cash,total)
     * 返回：交易ID（long）
     */
    @JavascriptInterface
    public long recordTrade(String code, String name, String direction,
                            double price, int quantity,
                            String signalType, int aiScore,
                            double cash, double totalAsset) {
        try {
            long id = mDb.insertTrade(code, name, direction, price, quantity,
                    signalType, aiScore, cash, totalAsset);
            // 持久化现金余额供 DailySnapshotWorker 读取
            mContext.getSharedPreferences("sm_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("cash", String.valueOf(cash))
                    .putString("total_asset", String.valueOf(totalAsset))
                    .apply();
            return id;
        } catch (Exception e) {
            Log.e(TAG, "recordTrade error", e);
            return -1;
        }
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
            obj.put("decisions", mDb.queryAllTrades().size());
            return obj.toString();
        } catch (Exception e) {
            return "{}";
        }
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
                org.json.JSONObject status = new org.json.JSONObject(statusJson);
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
        return com.monsieurmahjong.iqoowang.util.WisdomManager.get().getAllJson();
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
                                            r.optInt("score"), r.optString("signal"));
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
        org.json.JSONArray arr = new org.json.JSONArray();
        for (WatchlistManager.WatchlistItem it : WatchlistManager.get().getPendingSignals()) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
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
        return new org.json.JSONArray(java.util.Arrays.asList(DecisionLogger.get().listLogDates())).toString();
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

    // ══════════════════════════════════════════════
    // 内部工具
    // ══════════════════════════════════════════════

    /** 在主线程执行JS */
    private void evalJs(final String js) {
        mWebView.post(() -> mWebView.evaluateJavascript(js, null));
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

            com.monsieurmahjong.iqoowang.util.RealtimeQuoteManager.get().fetchBatch(codes,
                    (quotes, failedCodes) -> {
                        if (!failedCodes.isEmpty()) {
                            Log.w(TAG, "refreshPositionPrices: " + failedCodes.size() + "支两个数据源都未拿到: " + failedCodes);
                        }
                        if (quotes.isEmpty()) return;
                        JSONObject priceMap = new JSONObject();
                        for (Map.Entry<String, com.monsieurmahjong.iqoowang.util.RealtimeQuoteManager.Quote> e : quotes.entrySet()) {
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
