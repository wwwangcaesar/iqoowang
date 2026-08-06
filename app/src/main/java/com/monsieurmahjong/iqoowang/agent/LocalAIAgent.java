package com.monsieurmahjong.iqoowang.agent;


import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LocalAIAgent
 *
 * 端侧AI大脑，完全本地推理，数据不出设备。
 *
 * 架构：
 *   LlmEngine (JNI) → MNN C++ → libMNNLLM.so
 *   ↑ 失败时降级
 *   StockExpertSystem（纯Java专家规则，零依赖）
 *
 * iQOO 11s 骁龙8 Gen2：
 *   · 优先走 OpenCL (Adreno 740 GPU)
 *   · 其次 CPU ARMv8.2 (Hexagon DSP 需额外 QNN.so)
 *   · 推理速度约 20-40 tokens/s
 */
public class LocalAIAgent {

    private static final String TAG = "LocalAIAgent";
    private static final String MODEL_DIR = "qwen3.5-4b-instruct-int4";   

    private final Context       mContext;
    private final LlmEngine     mEngine;
    private final StockExpertSystem mExpert;
    private final AIContextBuilder  mCtxBuilder;
    private final ExecutorService   mExecutor;
    private final Handler           mMainHandler;
    private final AtomicBoolean     mInferring = new AtomicBoolean(false);
    private volatile long mInferringSince = 0L;
    /** 推理锁看门狗超时——超过这个时长还没释放锁，视为上一次推理异常挂起（比如原生库
     *  崩溃导致onFinish回调根本没触发），强制解锁重试，避免一次卡死导致之后所有AI调用
     *  永久性地弹"AI正在思考中" */
    private static final long INFER_WATCHDOG_MS = 200_000; // 3分钟，实测本地模型单次推理需约90秒，这个阈值必须明显高于它，否则会把还在正常推理的误判为卡死而提前强制解锁，让新请求与旧推理并发争用MNN资源

    // Agent 进化状态
    private int mLevel = 1;
    private int mExp   = 0;
    private boolean mEngineReady = false;
    private String mInitDebugLog = ""; // 收集初始化诊断日志

    private static LocalAIAgent sInstance;

    public static LocalAIAgent get(Context ctx) {
        if (sInstance == null) {
            synchronized (LocalAIAgent.class) {
                if (sInstance == null)
                    sInstance = new LocalAIAgent(ctx.getApplicationContext());
            }
        }
        return sInstance;
    }

    private LocalAIAgent(Context context) {
        mContext     = context;
        mEngine      = LlmEngine.get();
        mExpert      = new StockExpertSystem();
        mCtxBuilder  = new AIContextBuilder(context);
        mExecutor    = Executors.newSingleThreadExecutor();
        mMainHandler = new Handler(Looper.getMainLooper());

        // 异步加载模型，不阻塞主线程
        mExecutor.execute(this::initEngine);
    }

    // ──────────────────────────────────────────
    // 模型初始化
    // ──────────────────────────────────────────

    private void initEngine() {
        mInitDebugLog = "【初始化诊断日志】\n";
        String[] candidates = {
                new File(mContext.getExternalFilesDir(null), MODEL_DIR).getAbsolutePath(),
                "/sdcard/Android/data/com.stockmaster/files/" + MODEL_DIR,
                new File(mContext.getFilesDir(), MODEL_DIR).getAbsolutePath(),
        };

        File modelDir = null;
        for (String path : candidates) {
            File f = new File(path);
            if (!f.exists()) { 
                mInitDebugLog += "目录不存在: " + path + "\n";
                Log.i(TAG, "不存在: " + path); 
                continue; 
            }
            String[] files = f.list();
            mInitDebugLog += "发现目录: " + path + " (" + (files!=null?files.length:0) + "项)\n";
            Log.i(TAG, "发现目录: " + path + " (" + (files!=null?files.length:0) + "项)");
            if (files != null) for (String fn : files) {
                mInitDebugLog += "  - " + fn + " (" + new File(f,fn).length()/1024 + "KB)\n";
                Log.i(TAG, "  " + fn + " (" + new File(f,fn).length()/1024 + "KB)");
            }
            if (new File(f, "config.json").exists() || new File(f, "llm.mnn").exists()) { modelDir = f; break; }
        }

        if (modelDir == null) {
            mInitDebugLog += "❌ 未找到含config.json或llm.mnn的模型，放弃加载。\n";
            Log.e(TAG, "❌ 未找到含config.json的模型目录");
            mEngineReady = false; return;
        }

        // ── 自动检测旧版格式（tokenizer.txt），自动修复config.json ──
        boolean hasNewTokenizer = new File(modelDir, "tokenizer.mtok").exists();
        boolean hasOldTokenizer = new File(modelDir, "tokenizer.txt").exists();
        boolean hasWeight       = new File(modelDir, "llm.mnn.weight").exists();
        boolean hasLlmConfig    = new File(modelDir, "llm_config.json").exists();

        mInitDebugLog += "检查模型文件: mtok=" + hasNewTokenizer + " txt=" + hasOldTokenizer + " weight=" + hasWeight + "\n";
        Log.i(TAG, "格式: tokenizer.mtok=" + hasNewTokenizer
                + " tokenizer.txt=" + hasOldTokenizer + " weight=" + hasWeight);

        if (!hasNewTokenizer && hasOldTokenizer) {
            Log.w(TAG, "旧版格式，自动修复 config.json");
            mInitDebugLog += "检测到旧版格式，自动生成 config.json...\n";
            try {
                String cfg = "{\n" +
                        "  \"llm_model\": \"llm.mnn\",\n" +
                        "  \"llm_weight\": \"" + (hasWeight ? "llm.mnn.weight" : "llm.mnn") + "\",\n" +
                        (hasLlmConfig ? "  \"llm_config\": \"llm_config.json\",\n" : "") +
                        "  \"tokenizer_model\": \"tokenizer.txt\",\n" +
                        "  \"is_single_token\": false,\n" +
                        "  \"max_new_tokens\": 512,\n" +
                        "  \"reuse_kv\": false,\n" +
                        "  \"quant_bit\": 4,\n" +
                        "  \"quant_block\": 0\n}";
                java.io.FileWriter fw = new java.io.FileWriter(new File(modelDir, "config.json"));
                fw.write(cfg); fw.close();
                mInitDebugLog += "✅ config.json 自动补全成功\n";
                Log.i(TAG, "✅ config.json 已更新（旧版兼容）");
            } catch (Exception e) {
                mInitDebugLog += "❌ config.json 写入失败: " + e.getMessage() + "\n";
                Log.e(TAG, "config.json写入失败: " + e.getMessage());
            }
        }

        Log.i(TAG, "加载模型: " + modelDir.getAbsolutePath());
        mInitDebugLog += "正在调用底层JNI加载模型 (这可能需要几秒钟)...\n";
        try {
            boolean ok = mEngine.init(modelDir.getAbsolutePath());
            mEngineReady = ok;
            if (ok) {
                mInitDebugLog += "✅ MNN模型底层加载成功！\n";
            } else {
                mInitDebugLog += "❌ MNN模型底层加载失败。详情信息：\n" + LlmEngine.getLoadError() + "\n";
            }
            Log.i(TAG, ok ? "✅ 模型加载成功" : "❌ 失败");

            // 加载完成后通知 WebView 更新状态
            mMainHandler.postDelayed(() -> {
                // 这里只记录，实际通知由 StockBridge 的 setAutoRefresh 触发
                Log.i(TAG, "模型加载结束，mEngineReady=" + mEngineReady
                        + " isReady=" + mEngine.isReady());
            }, 500);

        } catch (Throwable t) {
            mInitDebugLog += "❌ 加载异常: " + t.getMessage() + "\n";
            Log.e(TAG, "❌ 异常: " + t.getMessage(), t);
            mEngineReady = false;
        }
    }
    
    private String consumeDebugLog() {
        if (mInitDebugLog == null || mInitDebugLog.isEmpty()) return "";
        String log = mInitDebugLog;
        mInitDebugLog = ""; // 消费一次后清空，避免每次聊天都弹出一长串日志
        return log + "\n-----\n本地模型不可用，已自动降级至专家规则系统：\n";
    }

    /** App启动时预热，消除冷启动延迟 */
    public void warmup() {
        mExecutor.execute(() -> {
            // 等待初始化完成
            int wait = 0;
            while (!mEngineReady && !mEngine.isReady() && wait < 15000) {
                try { Thread.sleep(500); } catch (Exception ignored) {}
                wait += 500;
            }
            if (mEngine.isReady()) {
                // 跑一次极短推理预热 KV Cache
                mEngine.chat("你好", new LlmEngine.Callback() {
                    @Override public void onToken(String t) {}
                    @Override public void onFinish(String s) { Log.d(TAG, "warmup完成"); }
                });
            }
        });
    }

    // ──────────────────────────────────────────
    // 回调接口
    // ──────────────────────────────────────────

    public interface AICallback {
        void onToken(String token);
        void onComplete(String fullText);
        void onError(String msg);
    }

    /**
     * 获取推理锁——比直接用 mInferring.getAndSet(true) 多一层看门狗：如果上一次推理
     * 声称"正在进行"但已经卡住超过 INFER_WATCHDOG_MS 还没释放锁，就强制解锁重新开始，
     * 避免一次意外挂起（原生库崩溃/异常导致onFinish回调没被调用）永久卡死后续所有AI调用。
     */
    private boolean tryAcquireInferLock() {
        if (mInferring.compareAndSet(false, true)) {
            mInferringSince = System.currentTimeMillis();
            return true;
        }
        long stuckMs = System.currentTimeMillis() - mInferringSince;
        if (stuckMs > INFER_WATCHDOG_MS) {
            Log.w(TAG, "推理锁已卡死" + (stuckMs / 1000) + "秒（超过看门狗阈值"
                    + (INFER_WATCHDOG_MS / 1000) + "秒），强制解锁重试");
            mInferringSince = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    // ──────────────────────────────────────────
    // 选股结果分析
    // ──────────────────────────────────────────

    public void analyzeScreenResult(String screenResultJson, AICallback cb) {
        if (!tryAcquireInferLock()) {
            cb.onError("AI正在思考中，请稍后");
            return;
        }
        mExecutor.execute(() -> {
            try {
                JSONArray results = new JSONArray(screenResultJson);

                // 聚合真实数据上下文：大盘指数+市场宽度 / 历史战绩 / 每支股票近期走势&个人历史
                // 注意：这里会阻塞等待指数下载(最长6秒)，但发生在后台线程，不影响 UI
                String marketCtx = mCtxBuilder.buildMarketContext();
                String historyCtx = mCtxBuilder.buildTradeHistoryContext();
                String perStockCtx = buildPerStockContext(results);

                // 构造提示词
                String prompt = buildScreenPrompt(results, marketCtx, historyCtx, perStockCtx);

                if (mEngine.isReady()) {
                    // 重置上轮对话历史，避免上下文污染
                    mEngine.reset();
                    runStream(prompt, cb);
                } else {
                    // 降级：专家规则直接产出与 LLM 同样的结构化格式，同样基于真实大盘/历史数据
                    String result = consumeDebugLog()
                            + mExpert.generateStructured(results, marketCtx, historyCtx, mCtxBuilder);
                    streamToCallback(result, cb);
                }
            } catch (Exception e) {
                Log.e(TAG, "analyzeScreenResult", e);
                mInferring.set(false);
                mMainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /** 为每支候选股拼接：基础指标 + 近期走势 + 个人历史操作提醒 */
    private String buildPerStockContext(JSONArray results) throws Exception {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(results.length(), 6); // 控制长度，避免超出本地小模型的 max_new_tokens 限制
        for (int i = 0; i < n; i++) {
            JSONObject r = results.getJSONObject(i);
            String code = r.optString("code");
            String trend = mCtxBuilder.buildRecentTrend(code);
            String histNote = mCtxBuilder.buildStockHistoryNote(code);
            sb.append(String.format("  %d. %s(%s) ¥%.2f 量比%sx 评分%d %s",
                    i + 1,
                    r.optString("name"), code,
                    r.optDouble("latestClose", 0),
                    r.optString("volMultiActual"),
                    r.optInt("score"),
                    r.optString("signal")));
            if (!trend.isEmpty()) sb.append(" 近日:").append(trend);
            if (!histNote.isEmpty()) sb.append(" ").append(histNote);
            sb.append("\n");
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────
    // 对话
    // ──────────────────────────────────────────

    public void chat(String message, String historyJson, AICallback cb) {
        if (!tryAcquireInferLock()) {
            cb.onError("AI正在思考中，请稍后");
            return;
        }
        mExecutor.execute(() -> {
            try {
                String prompt = buildChatPrompt(message, historyJson);

                if (mEngine.isReady()) {
                    mEngine.reset(); // prompt里已经手动拼接了完整历史，reset避免引擎残留状态和这份历史对不上
                    runStream(prompt, cb);
                } else {
                    String fallback = consumeDebugLog() + mExpert.answerQuestion(message);
                    streamToCallback(fallback, cb);
                }
            } catch (Exception e) {
                Log.e(TAG, "chat", e);
                mInferring.set(false);
                mMainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    // ──────────────────────────────────────────
    // 信号二次验证（规则引擎只做候选初筛，AI结合已学话术做二次确认+生成人话解释）
    // ──────────────────────────────────────────

    public static class VerifyResult {
        public boolean confirmed;
        public String reason;
        public String fullText;
    }

    /**
     * 规则引擎命中后 AI 二次复核。仅当 AI 明确「确认」时 confirmed=true。
     */
    public void verifySignal(String code, String name, String action, String actionLabel,
                              String ruleNote, String metrics,
                              com.monsieurmahjong.iqoowang.util.RealtimeQuoteManager.Quote quote,
                              AICallback cb) {
        if (!tryAcquireInferLock()) {
            cb.onError("AI正在思考中，请稍后");
            return;
        }
        mExecutor.execute(() -> {
            try {
                String histNote = mCtxBuilder.buildStockHistoryNote(code);
                String trend = mCtxBuilder.buildRecentTrend(code);
                String prompt = buildVerifyPrompt(code, name, action, actionLabel, ruleNote, metrics, quote, histNote, trend);

                if (mEngine.isReady()) {
                    mEngine.reset();
                    runStream(prompt, cb);
                } else {
                    mInferring.set(false);
                    cb.onError("本地AI模型未就绪");
                }
            } catch (Exception e) {
                Log.e(TAG, "verifySignal", e);
                mInferring.set(false);
                mMainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    private String buildVerifyPrompt(String code, String name, String action, String actionLabel,
                                      String ruleNote, String metrics,
                                      com.monsieurmahjong.iqoowang.util.RealtimeQuoteManager.Quote quote,
                                      String histNote, String trend) {
        StringBuilder sb = new StringBuilder();
        sb.append(getSystemPrompt(action)).append("\n\n");
        sb.append("【补充分析任务】规则引擎已按操盘手方法论触发信号并推送通知，现在需要你结合真实数据和你学过的话术，做定性补充分析。\n");
        sb.append("你的结论不会阻止已发出的通知，但会显示给用户参考。买入类信号偏审慎复核，卖出/止损类偏支持规则执行。\n");
        sb.append("核心原则：右侧交易、放量验证分歧、资金安全第一。你不预测涨跌，只评估眼前证据的可信度。\n\n");
        sb.append("股票：").append(name).append("(").append(code).append(")\n");
        sb.append("候选动作：").append(actionLabel).append("（").append(action).append("）\n");
        sb.append("规则引擎依据：").append(ruleNote).append("\n");
        if (metrics != null && !metrics.isEmpty()) sb.append("量化指标：").append(metrics).append("\n");
        if (quote != null) {
            sb.append(String.format(Locale.CHINA,
                    "实时行情(%s)：现价¥%.2f 今开¥%.2f 最高¥%.2f 最低¥%.2f 涨跌幅%.2f%%\n",
                    quote.time != null && !quote.time.isEmpty() ? quote.time : "时间未知",
                    quote.price, quote.open, quote.high, quote.low, quote.changePct));
        }
        // 大盘环境：文档9.7把"大盘环境定性补充"列为AI该做的判断之一。
        // 【修复】之前这里直接调getMarketSummaryText()读本地缓存，跳过了MarketIndexManager类文档
        // 明确要求的ensureFreshBlocking()刷新步骤——选股用的buildMarketContext()有调用，这里没有，
        // 导致AI复核买卖信号时看到的大盘环境可能是缓存建立时的旧数据，从不主动更新。
        // 现补上，最长等6秒，超时就用已有缓存（不是编造数据），不影响主流程。
        try {
            com.monsieurmahjong.iqoowang.util.MarketIndexManager idx = com.monsieurmahjong.iqoowang.util.MarketIndexManager.get();
            idx.ensureFreshBlocking(6000);
            sb.append("大盘环境：").append(idx.getMarketSummaryText()).append("\n");
        } catch (Exception ignored) {}
        if (!trend.isEmpty()) sb.append("近期走势：").append(trend).append("\n");
        if (!histNote.isEmpty()) sb.append("历史交易：").append(histNote).append("\n");

        sb.append("\n【输出要求】严格按下面三行输出：\n");
        sb.append("推理：简要说明你的判断逻辑（量价、VWAP、水线、止损位等），不超过80字\n");
        sb.append("判断：确认 或 不确认\n");
        sb.append("说明：不超过80字大白话，说清楚为什么、风险提示\n");
        return sb.toString();
    }

    /** 仅当 AI 明确输出「确认」且不含「不确认」时返回 true；否则一律不通过 */
    public static VerifyResult parseVerifyResult(String text) {
        VerifyResult r = new VerifyResult();
        r.fullText = text != null ? text.trim() : "";
        if (text == null || text.trim().isEmpty()) {
            r.confirmed = false;
            r.reason = "AI未返回有效结果，本次不推送";
            return r;
        }
        boolean hasNotConfirm = text.contains("不确认");
        java.util.regex.Matcher jm = java.util.regex.Pattern.compile("判断[:：]\\s*(确认|不确认)").matcher(text);
        if (jm.find()) {
            r.confirmed = "确认".equals(jm.group(1)) && !hasNotConfirm;
        } else {
            r.confirmed = text.contains("确认") && !hasNotConfirm;
        }

        java.util.regex.Matcher rm = java.util.regex.Pattern.compile("说明[:：]\\s*([\\s\\S]+)").matcher(text);
        r.reason = rm.find() ? rm.group(1).trim() : text.trim();
        return r;
    }

    // ──────────────────────────────────────────
    // 话术学习（知识库注入，非模型权重级学习——见 WisdomManager 类注释）
    // ──────────────────────────────────────────

    /**
     * 教AI一条新话术。流程：
     *   1. 让模型用一句话复述它理解到的要点（用于确认理解是否正确 + 生成简明摘要）
     *   2. 无论模型是否可用，原始话术原文都会持久化进 WisdomManager
     *   3. 之后每次涉及交易判断的分析/对话，都会重新把这条话术塞进 Prompt
     *
     * 【诚实说明】这不是真正的模型微调，是"知识库注入"，模拟"记住"的效果。
     */
    public void learnWisdom(String rawText, AICallback cb) {
        if (rawText == null || rawText.trim().isEmpty()) {
            cb.onError("话术内容不能为空");
            return;
        }
        if (!tryAcquireInferLock()) {
            cb.onError("AI正在思考中，请稍后再教学");
            return;
        }
        final String text = rawText.trim();
        mExecutor.execute(() -> {
            try {
                if (mEngine.isReady()) {
                    String prompt = buildLearnPrompt(text);
                    mEngine.reset();
                    runLearnStream(text, prompt, cb);
                } else {
                    // 模型不可用：直接用规则生成一个简明摘要（截断+去空白）+关键词启发式分类，照样持久化
                    String summary = fallbackSummary(text);
                    String category = guessCategoryByKeyword(text);
                    com.monsieurmahjong.iqoowang.util.WisdomManager.get().addEntry(text, summary, category);
                    mInferring.set(false);
                    String msg = consumeDebugLog() + "已记录（专家系统模式，未做AI复述确认，分类由关键词推断）：" + summary;
                    streamToCallback(msg, cb);
                }
            } catch (Exception e) {
                Log.e(TAG, "learnWisdom", e);
                mInferring.set(false);
                mMainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    private String buildLearnPrompt(String rawText) {
        return getCorePersona() +
                "【学习任务】用户要教你一条新的操盘经验，原文如下：\n" +
                "\"" + rawText + "\"\n\n" +
                "请严格按下面两行输出，不要输出其他内容：\n" +
                "分类：从[BUY_STARTER, ADD_HALF, BUY_FULL, WARN_PRESSURE, STOP_LOSS, GENERAL]中选一个" +
                "（分别对应：建底仓/加仓/满仓/抛压预警/止损；如果这条经验是通用的、不专属于某一类判断，选GENERAL）\n" +
                "复述：不超过30字，复述你理解到的核心要点（比如：什么条件下做什么操作）\n";
    }

    private static final java.util.regex.Pattern CATEGORY_PATTERN = java.util.regex.Pattern.compile(
            "分类[:：]\\s*(BUY_STARTER|ADD_HALF|BUY_FULL|WARN_PRESSURE|STOP_LOSS|GENERAL)");
    private static final java.util.regex.Pattern RESTATE_PATTERN = java.util.regex.Pattern.compile(
            "复述[:：]\\s*([\\s\\S]+)");

    /**
     * 从AI的“分类+复述”结构化输出里解析分类标签；解析失败就退化为关键词启发式推测，
     * 比一律不分类要好（不分类意味着永远全量注入，失去了分类的意义）。
     */
    private String parseCategoryOrGuess(String aiOutput, String rawText) {
        if (aiOutput != null) {
            java.util.regex.Matcher m = CATEGORY_PATTERN.matcher(aiOutput);
            if (m.find()) {
                String cat = m.group(1);
                return "GENERAL".equals(cat) ? "" : cat;
            }
        }
        return guessCategoryByKeyword(rawText);
    }

    /** AI分类解析失败或模型不可用时的关键词兜底分类，匹不到就归为通用（宁可多展示也不要漏掉关键信息） */
    private String guessCategoryByKeyword(String text) {
        if (text == null) return "";
        if (text.contains("止损") || text.contains("清仓") || text.contains("离场") || text.contains("跌破")) return "STOP_LOSS";
        if (text.contains("抛压") || text.contains("预警") || text.contains("回撤")) return "WARN_PRESSURE";
        if (text.contains("满仓")) return "BUY_FULL";
        if (text.contains("加仓")) return "ADD_HALF";
        if (text.contains("底仓") || text.contains("建仓") || text.contains("买入") || text.contains("进场")) return "BUY_STARTER";
        return "";
    }

    private void runLearnStream(String rawText, String prompt, AICallback cb) {
        final StringBuilder full = new StringBuilder();
        mEngine.chatStream(prompt, new LlmEngine.Callback() {
            @Override
            public void onToken(String token) {
                full.append(token);
                mMainHandler.post(() -> cb.onToken(token));
            }
            @Override
            public void onFinish(String fullText) {
                mInferring.set(false);
                String result = fullText.isEmpty() ? full.toString() : fullText;
                boolean parseFailed = result.startsWith("[response") || result.startsWith("[推理")
                        || result.startsWith("[签名") || result.trim().isEmpty();
                String category = parseFailed ? guessCategoryByKeyword(rawText) : parseCategoryOrGuess(result, rawText);
                String summary;
                if (parseFailed) {
                    summary = fallbackSummary(rawText);
                } else {
                    java.util.regex.Matcher rm = RESTATE_PATTERN.matcher(result);
                    summary = rm.find() ? rm.group(1).trim() : result.trim();
                }
                com.monsieurmahjong.iqoowang.util.WisdomManager.get().addEntry(rawText, summary, category);
                gainExp(8); // 学习一条新话术给更高经验值，鼓励持续教学
                mMainHandler.post(() -> cb.onComplete(summary));
            }
        });
    }

    private String fallbackSummary(String rawText) {
        String s = rawText.replaceAll("\\s+", "");
        return s.length() > 30 ? s.substring(0, 30) + "..." : s;
    }

    // ──────────────────────────────────────────
    // 核心推理（流式）
    // ──────────────────────────────────────────

    private void runStream(String prompt, AICallback cb) {
        final StringBuilder full = new StringBuilder();

        mEngine.chatStream(prompt, new LlmEngine.Callback() {
            @Override
            public void onToken(String token) {
                full.append(token);
                mMainHandler.post(() -> cb.onToken(token));
            }

            @Override
            public void onFinish(String fullText) {
                mInferring.set(false);
                String result = fullText.isEmpty() ? full.toString() : fullText;

                // 检测推理失败的标记字符串，降级到专家规则
                if (result.startsWith("[response") || result.startsWith("[推理")
                        || result.startsWith("[签名") || result.isEmpty()) {
                    Log.w(TAG, "Qwen推理失败: " + result + "，降级到专家规则");
                    // 提取更详细的底层签名信息
                    String sigInfo = "";
                    try { sigInfo = mEngine.isReady() ? mEngine.getDebugInfo() : ""; } catch (Exception e) {}
                    
                    // 从 prompt 里提取用户问题
                    String question = prompt.length() > 200
                            ? prompt.substring(prompt.length() - 200) : prompt;
                    String fallback = "【模型虽然加载，但推理无返回】\n底层符号状态：" + sigInfo + "\n返回值长度：" + result.length() + "\n\n--- 专家系统已接管 ---\n"
                            + mExpert.answerQuestion(question);
                    // 重新流式输出
                    mExecutor.execute(() -> {
                        mInferring.set(true);
                        mInferringSince = System.currentTimeMillis();
                        streamToCallback(fallback, cb);
                    });
                    return;
                }

                gainExp(5);
                mMainHandler.post(() -> cb.onComplete(result));
            }
        });
    }

    /** 专家规则降级时模拟流式输出（打字机效果） */
    private void streamToCallback(String text, AICallback cb) {
        // 按标点断句分组输出
        String[] segments = text.split("(?<=[。，！？、])");
        StringBuilder full = new StringBuilder();
        try {
            for (String seg : segments) {
                // 每段内按字符输出
                for (int i = 0; i < seg.length(); i++) {
                    String ch = String.valueOf(seg.charAt(i));
                    full.append(ch);
                    final String token = ch;
                    mMainHandler.post(() -> cb.onToken(token));
                    Thread.sleep(35); // 控制打字速度
                }
            }
        } catch (InterruptedException ignored) {}

        mInferring.set(false);
        gainExp(3);
        final String result = full.toString();
        mMainHandler.post(() -> cb.onComplete(result));
    }

    // ──────────────────────────────────────────
    // 提示词构造
    // ──────────────────────────────────────────

    private String buildScreenPrompt(JSONArray results, String marketCtx, String historyCtx, String perStockCtx) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(getSystemPrompt()).append("\n\n");
        sb.append(marketCtx);
        sb.append(historyCtx);
        sb.append("\n【本次选股结果】共").append(results.length()).append("支通过公式筛选（下面每支都已附上近期走势和你自己的历史操作记录）：\n");
        sb.append(perStockCtx);

        sb.append("\n【输出要求】严格按下面格式输出，不要写其他对话或寒暄：\n");
        sb.append("◆大盘研判：一句话，结合上面的大盘指数和市场宽度数据，判断现在适合激进还是保守\n");
        sb.append("对每一支股票，依次输出以下块（六行，不要增减字段）：\n");
        sb.append("▶股票：名称(代码)\n评分：0-100的整数（可参考但不必等于规则引擎评分，结合大盘环境微调）\n");
        sb.append("操作：从[买入/观察/回避]中选一个\n挂单：给一个参考进场位（以昨收/水线为基准，说明大致等回踩到什么位置；这只是选股阶段的粗略参考，实际入场以App后续实时监控的水线+VWAP+放量信号为准）\n");
        sb.append("止损：不给固定价格（实际止损位要等持仓后由分歧K线动态确定，不是固定百分比），这里只需一句话提示这支票的主要风险点\n理由：一句话，必须提及大盘环境、量价信号、历史操作记录中至少一项具体依据，不要编造未提供的数字\n");
        return sb.toString();
    }

    private String buildChatPrompt(String message, String historyJson) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(getCorePersona());

        // 识别消息里有没有提到具体股票（代码或名称），有的话自动去拉实时数据注入，
        // 不然AI压根没数据，只能诚实地说"数据不足"——这不是AI在偷懒，是我们没给它数据
        com.monsieurmahjong.iqoowang.util.MarketDataManager.StockMatch mentioned = null;
        try {
            mentioned = com.monsieurmahjong.iqoowang.util.MarketDataManager.get().findStockMentionedIn(message);
        } catch (Exception ignored) {}

        // 只有看起来真的在问股票/交易相关问题（或者提到了具体股票）时，才把公式+操盘手经验
        // 这一大段塞进去，日常打招呼这类消息跳过，prefill量小很多，回复明显更快
        boolean needKnowledge = looksTradingRelated(message) || mentioned != null;
        if (needKnowledge) {
            sb.append(getTradingKnowledge());
            // 只有涉及交易判断时才接上大盘背景，问候语不需要。【修复】同样补上
            // ensureFreshBlocking，避免聊天时AI看到的大盘环境也是一直不更新的旧缓存
            try {
                com.monsieurmahjong.iqoowang.util.MarketIndexManager idx = com.monsieurmahjong.iqoowang.util.MarketIndexManager.get();
                idx.ensureFreshBlocking(6000);
                sb.append("【当前大盘】").append(idx.getMarketSummaryText()).append("\n\n");
            } catch (Exception ignored) {}
        }

        if (mentioned != null) {
            sb.append(buildStockDataBlock(mentioned.code, mentioned.name));
        }

        if (historyJson != null && !historyJson.isEmpty()) {
            JSONArray history = new JSONArray(historyJson);
            int start = Math.max(0, history.length() - 8); // 最近4轮
            for (int i = start; i < history.length(); i++) {
                JSONObject msg = history.getJSONObject(i);
                String role = msg.optString("role");
                String content = msg.optString("content");
                sb.append("user".equals(role) ? "用户：" : "AI：")
                        .append(content).append("\n");
            }
        }
        sb.append("用户：").append(message).append("\nAI：");
        return sb.toString();
    }

    /**
     * 阻塞式拉取单支股票实时行情——聊天场景下在后台线程调用，短超时（RealtimeQuoteManager
     * 本身连接2秒/读取3秒），不会长时间卡住对话。
     */
    private com.monsieurmahjong.iqoowang.util.RealtimeQuoteManager.Quote fetchQuoteBlocking(String code, long timeoutMs) {
        final com.monsieurmahjong.iqoowang.util.RealtimeQuoteManager.Quote[] result =
                new com.monsieurmahjong.iqoowang.util.RealtimeQuoteManager.Quote[1];
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        com.monsieurmahjong.iqoowang.util.RealtimeQuoteManager.get().fetchBatch(
                java.util.Collections.singletonList(code), (quotes, failed) -> {
                    result[0] = quotes.get(code);
                    latch.countDown();
                });
        try { latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        return result[0];
    }

    /**
     * 聊天时识别到用户提到某支股票后，自动组装的"实时数据块"：现价/持仓状态/历史交易/
     * 候选池状态全部塞进去，AI才有依据回答，而不是干巴巴一句"数据不足"。
     */
    private String buildStockDataBlock(String code, String name) {
        StringBuilder sb = new StringBuilder();
        try {
            com.monsieurmahjong.iqoowang.util.RealtimeQuoteManager.Quote q = fetchQuoteBlocking(code, 4000);
            sb.append("\n【实时数据 - ").append(name).append("(").append(code).append(")】\n");
            if (q != null) {
                sb.append(String.format(Locale.CHINA,
                        "现价¥%.2f 今开¥%.2f 最高¥%.2f 最低¥%.2f 涨跌幅%.2f%%\n",
                        q.price, q.open, q.high, q.low, q.changePct));
            } else {
                sb.append("实时行情获取失败（可能网络问题），下面信息仅供参考\n");
            }

            String trend = mCtxBuilder.buildRecentTrend(code);
            if (!trend.isEmpty()) sb.append("近期走势：").append(trend).append("\n");

            com.monsieurmahjong.iqoowang.dao.Position pos =
                    com.monsieurmahjong.iqoowang.util.DatabaseManager.get().getPositionByCode(code);
            if (pos != null && pos.getQuantity() > 0) {
                double pnlPct = (q != null && pos.getAvgCost() > 0)
                        ? (q.price - pos.getAvgCost()) / pos.getAvgCost() * 100 : 0;
                sb.append(String.format(Locale.CHINA, "持仓状态：持仓中，成本价¥%.2f，%s%.2f%%\n",
                        pos.getAvgCost(), pnlPct >= 0 ? "浮盈" : "浮亏", Math.abs(pnlPct)));
            } else {
                sb.append("持仓状态：未持仓\n");
            }

            String histNote = mCtxBuilder.buildStockHistoryNote(code);
            if (!histNote.isEmpty()) sb.append("历史交易：").append(histNote).append("\n");

            com.monsieurmahjong.iqoowang.util.WatchlistManager.WatchlistItem item =
                    com.monsieurmahjong.iqoowang.util.WatchlistManager.get().getByCode(code);
            if (item != null) {
                sb.append("候选池状态：").append(item.status);
                if (item.lastNote != null && !item.lastNote.isEmpty()) sb.append("（").append(item.lastNote).append("）");
                sb.append("\n");
            }
        } catch (Exception e) {
            sb.append("（获取实时数据时出错：").append(e.getMessage()).append("）\n");
        }
        return sb.toString();
    }

    private String getSystemPrompt(String actionKey) {
        return getCorePersona() + getTradingKnowledge(actionKey);
    }

    private String getSystemPrompt() {
        return getSystemPrompt(null);
    }

    /** 精简人设，每轮对话都会发，保持很短以控制prefill开销 */
    private String getCorePersona() {
        return "你是JarvTrader，专为A股操盘设计的本地AI，完全离线运行在用户手机上，数据不出设备。\n" +
                "操盘手风格：直接、简洁、有明确操作建议，不说废话。自由问答不超过200字。\n" +
                "\"数据不足\"只用于确实缺少具体股票行情数字的情况；如果用户问你自己有什么技能/规则/" +
                "学过什么话术，直接从下面给你的公式、经验、已学话术里如实列出，不要用\"数据不足\"敷衍。\n\n";
    }

    /** 详细交易知识（公式+操盘手经验），只在真正论及股票/交易的场景下注入，避免日常对话也要prefill这一大段 */
    private String getTradingKnowledge(String actionKey) {
        return "【选股公式核心（通达信）】\n" +
                "· N=EMA(C,2)：2日指数均线，反映最新动量\n" +
                "· N1=11层嵌套EMA(2)：极平滑长期趋势\n" +
                "· N2=MA(25)+STD(25)：布林上轨，突破代表强势\n" +
                "· 信号A（强势）：N≥N1且N≥N2，阳线，量比≥2倍，上影线小\n" +
                "· 信号B（蓄势）：N≥N1且N≥N2，连续缩量两日价格持稳\n" +
                "· 硬条件：价格≥SAR(10,2,20)，≥3元，流通市值20-320亿\n" +
                "· 排除：创业板 科创板 涨幅≥20% ST\n\n" +

                "【资深操盘手经验（你必须深刻理解并融入判断）】\n" +
                "以下是操盘手的原话，你要理解其含义并用于实战分析：\n\n" +
                "经验1：买入时机——不是选出来就能买，也不是随便挂个价\n" +
                "→ 以昨收价（水线）为基准：现价跌破水线、又重新站稳分时均价（VWAP）5分钟以上，同时放量，才是底仓的入场信号。\n" +
                "→ 突破水线，或底仓后回踩VWAP不破，+放量，才能加仓；当日吃掉选股当天长上影线的70%以上+突破水线+站上VWAP+放量，才能满仓。\n" +
                "→ 不追涨，也不摸不清方向就抢反弹，等真正的量价确认出现再动手。\n\n" +
                "经验2：止损纪律——参照位是动态的，不是一条固定百分比的死线\n" +
                "→ 持仓后盯着放量但滞涨、或收长上影的那根K线（分歧K线），它的中点、最低点就是止损参照；跌破前一根阳线最低价，无条件离场。\n" +
                "→ 当日涨幅从最高点回撤到峰值的一半，先当预警观察，不是立刻止损。\n" +
                "→ 单票仓位不超过30%。\n\n" +
                "经验3：量价关系\n" +
                "→ 放量突破（信号A）比缩量整理（信号B）信号更强，但操作风险也更高。\n" +
                "→ 缩量整理后的第一个放量日，是最佳进场时机。\n" +
                "→ 成交量萎缩到前期的一半以下，说明筹码锁定充分，随时可能爆发。\n\n" +
                "经验4：市值偏好\n" +
                "→ 50-150亿流通市值标的弹性最佳，主力容易控盘，启动后涨速快。\n" +
                "→ 超过200亿的大盘股启动慢，但稳定性好，适合持有。\n\n" +

                "【你的职责】\n" +
                "1. 分析股票时，主动结合操盘手经验给出具体挂单价区间\n" +
                "2. 评估量价信号时，判断是信号A（放量突破）还是信号B（缩量整理）\n" +
                "3. 必须结合给定的大盘指数、市场宽度、历史交易记录做判断，这些都是真实数据，不是可有可无的参考信息\n" +
                "4. 选股分析时按指定的结构化格式输出，不受字数限制\n"
                + safeWisdomBlock(actionKey);
    }

    /** 兼容旧调用：不按判断类型过滤，注入全部话术（选股/聊天等无具体判断类型场景用） */
    private String getTradingKnowledge() {
        return getTradingKnowledge(null);
    }

    /** 安全获取话术知识库注入块（按判断类型过滤），WisdomManager未初始化或异常时返回空字符串，不影响正常分析 */
    private String safeWisdomBlock(String actionKey) {
        try {
            return com.monsieurmahjong.iqoowang.util.WisdomManager.get().buildInjectBlock(actionKey);
        } catch (Exception e) {
            return "";
        }
    }

    /** 兼容旧调用：不过滤，注入全部话术 */
    private String safeWisdomBlock() {
        return safeWisdomBlock(null);
    }

    /** 粗略判断一句话是不是在问股票/交易相关的问题——只有这种情况才把详细公式/经验塞进去，日常对话保持轻量、回复快 */
    private boolean looksTradingRelated(String message) {
        if (message == null) return false;
        String[] kw = {"股", "买", "卖", "止损", "挂单", "信号", "量比", "均线", "仓位", "SAR", "sar",
                "EMA", "ema", "大盘", "涨停", "跌停", "市值", "K线", "k线", "分时", "收盘", "开盘",
                "规则", "话术", "技能", "学会", "擅长", "进化", "能力", "会什么", "懂什么"};
        for (String k : kw) if (message.contains(k)) return true;
        return message.matches(".*\\d{6}.*"); // 提到了看起来像股票代码的6位数字
    }

    // ──────────────────────────────────────────
    // 进化系统
    // ──────────────────────────────────────────

    public void gainExp(int exp) {
        mExp += exp;
        int[] thresholds = {0, 100, 250, 500, 900, 1500, 2500};
        for (int i = thresholds.length - 1; i >= 0; i--) {
            if (mExp >= thresholds[i]) { mLevel = i + 1; break; }
        }
        mLevel = Math.min(mLevel, 6);
    }

    public String getStatusJson() {
        try {
            String modelPath = new File(
                    mContext.getExternalFilesDir(null), MODEL_DIR).getAbsolutePath();
            // 合并错误信息：.so加载错误 + nativeInit调试信息
            String errInfo = LlmEngine.getLoadError();
            JSONObject obj = new JSONObject();
            obj.put("level",      mLevel);
            obj.put("exp",        mExp);
            obj.put("modelName",  mEngineReady ? "Qwen3.5-4B-Instruct-INT4" : "专家规则系统");
            obj.put("ready",      true);
            obj.put("llmReady",   mEngine.isReady());
            obj.put("jniLoaded",  LlmEngine.isJniLoaded());
            obj.put("inferring",  mInferring.get());
            obj.put("modelPath",  modelPath);
            obj.put("soError",    errInfo.isEmpty() ? "无错误" : errInfo);
            return obj.toString();
        } catch (Exception e) { return "{}"; }
    }

    // ──────────────────────────────────────────
    // 内嵌专家规则系统（降级方案）
    // ──────────────────────────────────────────

    static class StockExpertSystem {

        /**
         * 降级方案：在本地 LLM 不可用时，用确定性规则产出与 LLM 同样的结构化标签格式，
         * 保证前端解析逻辑不用区分数据来源。所有判断都基于传入的真实大盘/历史数据，
         * 不是随便模板充数。
         */
        String generateStructured(JSONArray results, String marketCtx, String historyCtx, AIContextBuilder ctxBuilder) throws Exception {
            if (results.length() == 0)
                return "未发现符合条件的股票，建议放宽筛选条件或等待更好入场时机。";

            int tilt = 0; // 大盘倾向：+1偏多 / -1偏空 / 0中性
            if (marketCtx.contains("大盘整体偏多")) tilt = 1;
            else if (marketCtx.contains("大盘整体偏弱")) tilt = -1;

            StringBuilder sb = new StringBuilder();
            sb.append("◆大盘研判：").append(
                    tilt > 0 ? "当前大盘偏多，可适度提高强势股参与度。"
                            : tilt < 0 ? "当前大盘偏弱，系统性风险较高，建议降低仓位、优先回避追高。"
                            : "大盘涨跌不一，处于震荡阶段，宜精选个股、控制仓位。").append("\n");

            int n = Math.min(results.length(), 6);
            for (int i = 0; i < n; i++) {
                JSONObject r = results.getJSONObject(i);
                String code = r.optString("code");
                String name = r.optString("name");
                int baseScore = r.optInt("score");
                double latestClose = r.optDouble("latestClose", 0);
                String signal = r.optString("signal");

                String histNote = ctxBuilder.buildStockHistoryNote(code);
                boolean historyAllLoss = histNote.contains("0赢") && histNote.contains("亏") && !histNote.contains("亏0");

                int score = Math.max(0, Math.min(100, baseScore + tilt * 5 - (historyAllLoss ? 10 : 0)));
                String action;
                if (tilt < 0 && score < 80) action = "回避";
                else if (historyAllLoss) action = "观察";
                else if (score >= 85) action = "买入";
                else if (score >= 70) action = "观察";
                else action = "回避";

                // 水线 = 选股当天收盘价（latestClose），转入实时监控后就是"昨收"参照线；
                // 这里不再算固定挂单价/固定止损价——那套昨收+0.2~0.3、现价*0.95的老办法和现在
                // 实际监控用的水线+VWAP+分歧K线方法论对不上，容易误导用户，改成文字说明
                double waterLine = latestClose;

                StringBuilder reason = new StringBuilder();
                reason.append(signal).append("，量价信号").append("放量突破".equals(signal) ? "较强" : "尚需观察");
                if (tilt != 0) reason.append("，参考大盘").append(tilt > 0 ? "偏多环境" : "偏弱环境");
                if (!histNote.isEmpty()) reason.append("，").append(histNote);

                sb.append(String.format(Locale.CHINA,
                        "▶股票：%s(%s)\n评分：%d\n操作：%s\n挂单：参考水线¥%.2f附近，实际入场需等站稳VWAP+放量\n止损：待持仓后由分歧K线动态确定，非固定价\n理由：%s\n",
                        name, code, score, action, waterLine, reason.toString()));
            }
            return sb.toString();
        }

        String answerQuestion(String q) {
            q = q.toLowerCase();
            if (q.contains("挂单") || q.contains("进场") || q.contains("买入") || q.contains("买点")) {
                return "买入按三档来：跌破水线（昨收）后重新站稳分时均价（VWAP）5分钟以上+放量，才是底仓信号；" +
                        "突破水线，或底仓后回踩VWAP不破+放量，可以加仓；吃掉选股当天长上影线70%以上+突破水线+" +
                        "站稳VWAP+放量才能满仓。不追涨，等真正的量价确认出现再动手。";
            }
            if (q.contains("sar") || q.contains("止损")) {
                return "止损参照的是分歧K线（放量但滞涨、或收长上影的那根K线）：它的中点是二级止损位，最低点是" +
                        "三级止损位；跌破前一根阳线最低价，无条件离场。当日涨幅从峰值回撤到一半，先当预警观察，" +
                        "不是立刻止损。";
            }
            if (q.contains("量") || q.contains("放量") || q.contains("缩量")) {
                return "量价核心：缩量到前期一半以下说明筹码锁定充分，随时可能爆发。" +
                        "无论底仓、加仓还是满仓，都必须有放量确认才成立，缩量的信号只能降级为观察，不直接下结论。";
            }
            if (q.contains("ema") || q.contains("均线") || q.contains("趋势")) {
                return "N=EMA(2)>N1=11层嵌套EMA：短期动量突破长期趋势，是核心信号。" +
                        "N≥N2（布林上轨）说明突破近期震荡区间。两个条件同时满足信号最强。";
            }
            if (q.contains("仓位") || q.contains("几成仓") || q.contains("多少钱")) {
                return "仓位铁律：单票≤30%，信号A最强时≤40%，不重仓单票。" +
                        "50-150亿流通市值标的弹性最佳，主力容易控盘，优先选这个区间。";
            }
            if (q.contains("卖") || q.contains("止盈") || q.contains("何时出")) {
                return "止损分三级，都参照分歧K线：一级是当日涨幅从峰值回撤过半，先预警观察；二级跌破分歧K线" +
                        "中点；三级跌破分歧K线最低点，建议清仓；跌破前一根阳线最低价，无条件离场。" +
                        "另外注意T+1：当天买入的份额当天不能卖。";
            }
            if (q.contains("市值") || q.contains("盘子")) {
                return "市值偏好：50-150亿最佳，弹性好主力容易控；200亿以上启动慢但稳；" +
                        "20亿以下流动性差风险大。公式硬限：20-320亿。";
            }
            return "买入看三档（底仓/加仓/满仓），都要求先破水线再站稳VWAP+放量确认；止损参照分歧K线的" +
                    "中点和最低点，不是固定百分比；单票仓位不超过30%。水线位置+VWAP+放量+分歧K线止损参照，" +
                    "这几点合在一起才是完整判断。";
        }
    }
}
