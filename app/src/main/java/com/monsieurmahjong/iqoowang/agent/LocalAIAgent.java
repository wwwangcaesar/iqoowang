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
        File modelDir = new File(mContext.getExternalFilesDir(null), MODEL_DIR);
        if (!modelDir.exists()) {
            Log.w(TAG, "模型目录不存在: " + modelDir.getAbsolutePath() + "，使用专家规则模式");
            mEngineReady = false;
            return;
        }
        Log.i(TAG, "开始加载模型: " + modelDir.getAbsolutePath());
        boolean ok = mEngine.init(modelDir.getAbsolutePath());
        mEngineReady = ok;
        Log.i(TAG, "模型加载: " + (ok ? "成功，进入AI模式" : "失败，降级专家规则模式"));
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
                // 切回主线程推送token
                mMainHandler.post(() -> cb.onToken(token));
            }

            @Override
            public void onFinish(String fullText) {
                mInferring.set(false);
                String result = fullText.isEmpty() ? full.toString() : fullText;
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
        return "你是JarvTrader，专为A股操盘设计的本地AI，完全离线运行在用户手机上。\n\n" +
                "选股公式核心：\n" +
                "· N=EMA(C,2)，N1=11层嵌套EMA(2)，N2=布林上轨MA(25)+STD(25)\n" +
                "· 信号A：N≥N1且N≥N2，阳线，量比≥2倍，上影线小（强势放量）\n" +
                "· 信号B：N≥N1且N≥N2，连续缩量两日价稳（蓄势整理）\n" +
                "· 硬条件：价格≥SAR(10,2,20)，≥3元，流通市值20-320亿\n" +
                "· 排除：创业板 科创板 涨幅≥20% ST\n\n" +
                "操盘原则：N≥N1是核心信号，SAR是止损线，量价配合是关键，单票仓位≤30%。\n" +
                "回答：专业简洁，像操盘手对话，≤200字。";
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
            JSONObject obj = new JSONObject();
            obj.put("level",     mLevel);
            obj.put("exp",       mExp);
            obj.put("modelName", mEngineReady ? "Qwen2.5-1.5B-INT4" : "专家规则系统");
            obj.put("ready",     true); // 专家系统始终ready
            obj.put("llmReady",  mEngine.isReady());
            obj.put("inferring", mInferring.get());
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
            if (q.contains("sar") || q.contains("止损"))
                return "SAR(10,2,20)是抛物线止损线。买入后收盘跌破SAR则次日开盘无条件止损，这是本公式最重要的风控机制，不可忽视。";
            if (q.contains("ema") || q.contains("均线"))
                return "N=EMA(2)超短期均线，N1=11层嵌套EMA(2)极平滑长期趋势。N≥N1说明短期动量突破长期趋势，是核心买入信号。";
            if (q.contains("买") || q.contains("入场"))
                return "两种入场：信号A放量突破当日或次日早盘快速建仓；信号B缩量整理等第三日放量确认再进，更稳健。单票仓位≤30%。";
            if (q.contains("卖") || q.contains("止盈"))
                return "止盈止损三原则：①跌破SAR无条件止损；②涨幅10-15%分批减仓；③N<N1动量衰减先减至半仓。";
            if (q.contains("市值") || q.contains("盘子"))
                return "硬条件：流通市值20-320亿。实战偏好50-150亿，弹性最佳，启动后涨速快。";
            return "本公式四维共振：趋势(EMA)+突破(布林带)+放量(量比≥2)+止损(SAR)，缺任一维信号质量下降。有具体问题请继续问。";
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
