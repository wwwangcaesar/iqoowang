package com.monsieurmahjong.iqoowang.agent;


import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
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
    private static final String MODEL_DIR = "qwen2.5-1.5b-instruct-int4";

    private final Context       mContext;
    private final LlmEngine     mEngine;
    private final StockExpertSystem mExpert;
    private final ExecutorService   mExecutor;
    private final Handler           mMainHandler;
    private final AtomicBoolean     mInferring = new AtomicBoolean(false);

    // Agent 进化状态
    private int mLevel = 1;
    private int mExp   = 0;
    private boolean mEngineReady = false;

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
        mExecutor    = Executors.newSingleThreadExecutor();
        mMainHandler = new Handler(Looper.getMainLooper());

        // 异步加载模型，不阻塞主线程
        mExecutor.execute(this::initEngine);
    }

    // ──────────────────────────────────────────
    // 模型初始化
    // ──────────────────────────────────────────

    private void initEngine() {
        String[] candidates = {
                new File(mContext.getExternalFilesDir(null), MODEL_DIR).getAbsolutePath(),
                "/sdcard/Android/data/com.stockmaster/files/" + MODEL_DIR,
                new File(mContext.getFilesDir(), MODEL_DIR).getAbsolutePath(),
        };

        File modelDir = null;
        for (String path : candidates) {
            File f = new File(path);
            if (!f.exists()) { Log.i(TAG, "不存在: " + path); continue; }
            String[] files = f.list();
            Log.i(TAG, "发现目录: " + path + " (" + (files!=null?files.length:0) + "项)");
            if (files != null) for (String fn : files)
                Log.i(TAG, "  " + fn + " (" + new File(f,fn).length()/1024 + "KB)");
            if (new File(f, "config.json").exists()) { modelDir = f; break; }
        }

        if (modelDir == null) {
            Log.e(TAG, "❌ 未找到含config.json的模型目录");
            mEngineReady = false; return;
        }

        // ── 自动检测旧版格式（tokenizer.txt），自动修复config.json ──
        boolean hasNewTokenizer = new File(modelDir, "tokenizer.mtok").exists();
        boolean hasOldTokenizer = new File(modelDir, "tokenizer.txt").exists();
        boolean hasWeight       = new File(modelDir, "llm.mnn.weight").exists();
        boolean hasLlmConfig    = new File(modelDir, "llm_config.json").exists();

        Log.i(TAG, "格式: tokenizer.mtok=" + hasNewTokenizer
                + " tokenizer.txt=" + hasOldTokenizer + " weight=" + hasWeight);

        if (!hasNewTokenizer && hasOldTokenizer) {
            Log.w(TAG, "旧版格式，自动修复 config.json");
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
                Log.i(TAG, "✅ config.json 已更新（旧版兼容）");
            } catch (Exception e) {
                Log.e(TAG, "config.json写入失败: " + e.getMessage());
            }
        }

        Log.i(TAG, "加载模型: " + modelDir.getAbsolutePath());
        try {
            boolean ok = mEngine.init(modelDir.getAbsolutePath());
            mEngineReady = ok;
            Log.i(TAG, ok ? "✅ 模型加载成功" : "❌ 失败");

            // 加载完成后通知 WebView 更新状态
            mMainHandler.postDelayed(() -> {
                // 这里只记录，实际通知由 StockBridge 的 setAutoRefresh 触发
                Log.i(TAG, "模型加载结束，mEngineReady=" + mEngineReady
                        + " isReady=" + mEngine.isReady());
            }, 500);

        } catch (Throwable t) {
            Log.e(TAG, "❌ 异常: " + t.getMessage(), t);
            mEngineReady = false;
        }
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

    // ──────────────────────────────────────────
    // 选股结果分析
    // ──────────────────────────────────────────

    public void analyzeScreenResult(String screenResultJson, AICallback cb) {
        if (mInferring.getAndSet(true)) {
            cb.onError("AI正在思考中，请稍后");
            return;
        }
        mExecutor.execute(() -> {
            try {
                JSONArray results = new JSONArray(screenResultJson);

                // 专家规则预分析（快速）
                String expertPre = mExpert.analyzeResults(results);

                // 构造提示词
                String prompt = buildScreenPrompt(results, expertPre);

                if (mEngine.isReady()) {
                    // 重置上轮对话历史，避免上下文污染
                    mEngine.reset();
                    runStream(prompt, cb);
                } else {
                    // 降级：专家规则流式输出
                    String result = mExpert.generate(prompt);
                    streamToCallback(result, cb);
                }
            } catch (Exception e) {
                Log.e(TAG, "analyzeScreenResult", e);
                mInferring.set(false);
                mMainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    // ──────────────────────────────────────────
    // 对话
    // ──────────────────────────────────────────

    public void chat(String message, String historyJson, AICallback cb) {
        if (mInferring.getAndSet(true)) {
            cb.onError("AI正在思考中，请稍后");
            return;
        }
        mExecutor.execute(() -> {
            try {
                String prompt = buildChatPrompt(message, historyJson);

                if (mEngine.isReady()) {
                    runStream(prompt, cb);
                } else {
                    String fallback = mExpert.answerQuestion(message);
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
                    // 从 prompt 里提取用户问题
                    String question = prompt.length() > 200
                            ? prompt.substring(prompt.length() - 200) : prompt;
                    String fallback = mExpert.answerQuestion(question)
                            + "\n（本地AI推理暂时不可用，使用专家规则回答）";
                    // 重新流式输出
                    mExecutor.execute(() -> {
                        mInferring.set(true);
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

    private String buildScreenPrompt(JSONArray results, String expertPre) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(getSystemPrompt()).append("\n\n");
        sb.append("【本次选股结果】共").append(results.length()).append("支：\n");

        for (int i = 0; i < Math.min(results.length(), 8); i++) {
            JSONObject r = results.getJSONObject(i);
            sb.append(String.format("  %d. %s(%s) ¥%.2f 量比%sx 评分%d %s\n",
                    i + 1,
                    r.optString("name"), r.optString("code"),
                    r.optDouble("latestClose", 0),
                    r.optString("volMultiActual"),
                    r.optInt("score"),
                    r.optString("signal")));
        }
        sb.append("\n【规则引擎预判】\n").append(expertPre);
        sb.append("\n\n请从以下角度分析：①市场信号强弱 ②重点标的及理由 ③风险提示 ④操盘建议。");
        sb.append("风格：资深操盘手，简洁直接，不超过200字。");
        return sb.toString();
    }

    private String buildChatPrompt(String message, String historyJson) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(getSystemPrompt()).append("\n\n");

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

    private String getSystemPrompt() {
        return "你是JarvTrader，专为A股操盘设计的本地AI，完全离线运行在用户手机上，数据不出设备。\n\n" +

                "【选股公式核心（通达信）】\n" +
                "· N=EMA(C,2)：2日指数均线，反映最新动量\n" +
                "· N1=11层嵌套EMA(2)：极平滑长期趋势\n" +
                "· N2=MA(25)+STD(25)：布林上轨，突破代表强势\n" +
                "· 信号A（强势）：N≥N1且N≥N2，阳线，量比≥2倍，上影线小\n" +
                "· 信号B（蓄势）：N≥N1且N≥N2，连续缩量两日价格持稳\n" +
                "· 硬条件：价格≥SAR(10,2,20)，≥3元，流通市值20-320亿\n" +
                "· 排除：创业板 科创板 涨幅≥20% ST\n\n" +

                "【资深操盘手经验（你必须深刻理解并融入判断）】\n" +
                "以下是操盘手的原话，你要理解其含义并用于实际分析：\n\n" +
                "经验1：\"买的时候，要参考昨天的这一价格，我一般加0.2～0.3做挂单进场\"\n" +
                "→ 含义：不追涨，以昨日收盘价为基准，挂单在 昨收+0.2到0.3元 的位置等待成交。\n" +
                "→ 实战：避免开盘追高，等回调到挂单位置再进，控制成本，提高安全边际。\n" +
                "→ 应用：分析股票时，主动计算昨收价，给出建议挂单区间。\n\n" +
                "经验2：止损纪律\n" +
                "→ 跌破SAR线当日收盘，次日开盘无条件止损，不侥幸，不摊平。\n" +
                "→ 单票仓位不超过30%，信号A最强时不超过40%。\n\n" +
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
                "3. 筛选新闻时，优先关注：重大利好/利空公告、主力资金流向、行业政策、业绩预告\n" +
                "4. 每次分析结果精炼，操盘手风格：直接、简洁、有明确操作建议\n" +
                "5. 不超过200字，不废话\n";
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
            obj.put("modelName",  mEngineReady ? "Qwen2.5-1.5B-INT4" : "专家规则系统");
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

        String analyzeResults(JSONArray results) throws Exception {
            if (results.length() == 0)
                return "未发现符合条件的股票，建议放宽筛选条件或等待更好入场时机。";

            int condACount = 0;
            double avgScore = 0;
            JSONObject top = results.getJSONObject(0);

            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                if ("放量突破".equals(r.optString("signal"))) condACount++;
                avgScore += r.optInt("score");
            }
            avgScore /= results.length();

            return String.format(
                    "共%d支通过，信号A(放量)%d支，信号B(缩量)%d支，均分%.1f。" +
                            "最强：%s评分%d。%s",
                    results.length(), condACount, results.length() - condACount, avgScore,
                    top.optString("name"), top.optInt("score"),
                    condACount > results.length() / 2
                            ? "放量信号为主，市场做多氛围较浓。"
                            : "缩量整理为主，谨慎观望为宜。"
            );
        }

        String answerQuestion(String q) {
            q = q.toLowerCase();
            if (q.contains("挂单") || q.contains("进场") || q.contains("买入") || q.contains("买点")) {
                return "操盘手经验：以昨日收盘价为基准，挂单在昨收+0.2~0.3元位置等待成交，避免追高。" +
                        "开盘不追涨，等价格回落到挂单区间再进，控制成本，提高安全边际。";
            }
            if (q.contains("sar") || q.contains("止损")) {
                return "SAR是红线不能破：跌破SAR当日收盘，次日开盘无条件出局，不侥幸不摊平。" +
                        "SAR(10,2,20)：初始加速因子2%，最大20%。止损是保命的，不是可选项。";
            }
            if (q.contains("量") || q.contains("放量") || q.contains("缩量")) {
                return "量价核心：缩量到前期一半以下说明筹码锁定充分，随时可能爆发。" +
                        "第一个放量突破日（量比≥2）是最佳进场时机，次日挂单昨收+0.25元进场。";
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
                return "卖出三条：①跌破SAR无条件止损；②涨10-15%分批减仓；③N<N1动量衰减减到半仓。" +
                        "不要贪，操盘手的核心是管好风险，留着子弹打下一只。";
            }
            if (q.contains("市值") || q.contains("盘子")) {
                return "市值偏好：50-150亿最佳，弹性好主力容易控；200亿以上启动慢但稳；" +
                        "20亿以下流动性差风险大。公式硬限：20-320亿。";
            }
            return "基于操盘手经验：挂单在昨收+0.2~0.3元进场，跌破SAR止损，量比≥2放量才进，" +
                    "单票仓位≤30%。四维共振（EMA趋势+布林突破+量比放量+SAR支撑）才是最强信号。";
        }

        String generate(String prompt) {
            if (prompt.contains("选股结果"))
                return "根据通达信公式分析，当前筛选结果显示市场存在多头信号。" +
                        "建议重点关注量比最高且满足信号A的标的，次日观察高开企稳后介入。" +
                        "止损设SAR下方，仓位控制在30%以内，风控优先。";
            return answerQuestion(prompt);
        }
    }
}
