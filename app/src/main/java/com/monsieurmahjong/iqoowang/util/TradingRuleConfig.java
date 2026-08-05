package com.monsieurmahjong.iqoowang.util;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 交易规则外部配置 — 从 assets/trading_rules.json 加载，阈值可独立于代码调整。
 */
public class TradingRuleConfig {

    private static final String TAG = "TradingRuleConfig";
    /** 用户在App内保存过的参数覆盖文件名，存放在内部可写目录（assets只读，不能直接改） */
    private static final String OVERRIDE_FILE = "trading_rules_override.json";
    private static TradingRuleConfig sInstance;

    public double volumeRatioThreshold = 1.8;
    public int volumeMaDays = 5;
    public int vwapConfirmMinutes = 5;
    public double shadowEatRatio = 0.70;
    public int stopNotifyMinutesBeforeClose = 30;
    public int marketCloseHour = 15;
    public int marketCloseMinute = 0;
    public int earlyWindowMinutes = 60;
    public int lateWindowMinutes = 20;
    public double earlyWindowVolumeMultiplier = 1.5;
    public int fullConfirmMinutes = 45;
    public int sellObserveMinutes = 10;
    public double peakRetraceRatio = 0.50;
    /** KLINE_MID 或 RETRACE_MID */
    public String divergenceMidMode = "KLINE_MID";
    public double starterPositionPct = 0.30;
    public double addHalfPositionPct = 0.50;
    public double divergenceBodyMaxRatio = 0.35;
    /** 沪深主板涨跌停幅度（含ST，2026年新规已统一放宽到10%，见操盘手经验终版.md 5.1节） */
    public double mainboardLimitPct = 0.10;
    /** 创业板(300/301)、科创板(688)涨跌停幅度 */
    public double gemStarLimitPct = 0.20;
    /** 北交所(8/4开头)涨跌停幅度，粗略处理 */
    public double bjExchangeLimitPct = 0.30;
    /** 候选股（未买入）跌破水线超过这个比例，视为非正常回踩，自动移出观察池。
     *  建议默认值，文档未给出，需结合实盘/回测校准 */
    public double candidateDeepBreakPct = 0.08;
    /** 候选股观察超过这么多交易日仍未触发底仓买入，视为仙人指路预示的“近期”转强
     *  大概率已经落空，自动移出观察池。建议默认值，需结合实盘/回测校准 */
    public int candidateMaxObserveDays = 10;

    public static void init(Context context) {
        if (sInstance == null) {
            synchronized (TradingRuleConfig.class) {
                if (sInstance == null) sInstance = load(context.getApplicationContext());
            }
        }
    }

    public static TradingRuleConfig get() {
        if (sInstance == null) throw new IllegalStateException("call init() first");
        return sInstance;
    }

    private static TradingRuleConfig load(Context ctx) {
        TradingRuleConfig c = new TradingRuleConfig();
        // 第一阶：先加载内置默认值（assets只读，保证任何情况下都有一份完整基线）
        try (InputStream is = ctx.getAssets().open("trading_rules.json")) {
            byte[] buf = new byte[is.available()];
            int n = is.read(buf);
            applyUpdates(c, new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8)));
            Log.i(TAG, "内置规则配置已加载 divergenceMidMode=" + c.divergenceMidMode);
        } catch (Exception e) {
            Log.w(TAG, "加载 assets/trading_rules.json 失败，使用代码内默认值", e);
        }
        // 第二阶：叠加用户在App内保存过的覆盖值（如果有），实现"设置界面可改参数"
        File overrideFile = new File(ctx.getApplicationContext().getFilesDir(), OVERRIDE_FILE);
        if (overrideFile.exists()) {
            try (FileInputStream fis = new FileInputStream(overrideFile)) {
                byte[] buf = new byte[(int) overrideFile.length()];
                int n = fis.read(buf);
                applyUpdates(c, new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8)));
                Log.i(TAG, "用户自定义参数覆盖已加载");
            } catch (Exception e) {
                Log.w(TAG, "加载用户自定义参数覆盖失败，忽略，仅用内置默认值", e);
            }
        }
        return c;
    }

    /** 把JSON里出现的字段更新到目标配置对象上，缺失的字段保持原值不动 */
    private static void applyUpdates(TradingRuleConfig c, JSONObject o) throws Exception {
        if (o.has("volumeRatioThreshold")) c.volumeRatioThreshold = o.getDouble("volumeRatioThreshold");
        if (o.has("volumeMaDays")) c.volumeMaDays = o.getInt("volumeMaDays");
        if (o.has("vwapConfirmMinutes")) c.vwapConfirmMinutes = o.getInt("vwapConfirmMinutes");
        if (o.has("shadowEatRatio")) c.shadowEatRatio = o.getDouble("shadowEatRatio");
        if (o.has("stopNotifyMinutesBeforeClose")) c.stopNotifyMinutesBeforeClose = o.getInt("stopNotifyMinutesBeforeClose");
        if (o.has("marketCloseHour")) c.marketCloseHour = o.getInt("marketCloseHour");
        if (o.has("marketCloseMinute")) c.marketCloseMinute = o.getInt("marketCloseMinute");
        if (o.has("earlyWindowMinutes")) c.earlyWindowMinutes = o.getInt("earlyWindowMinutes");
        if (o.has("lateWindowMinutes")) c.lateWindowMinutes = o.getInt("lateWindowMinutes");
        if (o.has("earlyWindowVolumeMultiplier")) c.earlyWindowVolumeMultiplier = o.getDouble("earlyWindowVolumeMultiplier");
        if (o.has("fullConfirmMinutes")) c.fullConfirmMinutes = o.getInt("fullConfirmMinutes");
        if (o.has("sellObserveMinutes")) c.sellObserveMinutes = o.getInt("sellObserveMinutes");
        if (o.has("peakRetraceRatio")) c.peakRetraceRatio = o.getDouble("peakRetraceRatio");
        if (o.has("divergenceMidMode")) c.divergenceMidMode = o.getString("divergenceMidMode");
        if (o.has("starterPositionPct")) c.starterPositionPct = o.getDouble("starterPositionPct");
        if (o.has("addHalfPositionPct")) c.addHalfPositionPct = o.getDouble("addHalfPositionPct");
        if (o.has("divergenceBodyMaxRatio")) c.divergenceBodyMaxRatio = o.getDouble("divergenceBodyMaxRatio");
        if (o.has("mainboardLimitPct")) c.mainboardLimitPct = o.getDouble("mainboardLimitPct");
        if (o.has("gemStarLimitPct")) c.gemStarLimitPct = o.getDouble("gemStarLimitPct");
        if (o.has("bjExchangeLimitPct")) c.bjExchangeLimitPct = o.getDouble("bjExchangeLimitPct");
        if (o.has("candidateDeepBreakPct")) c.candidateDeepBreakPct = o.getDouble("candidateDeepBreakPct");
        if (o.has("candidateMaxObserveDays")) c.candidateMaxObserveDays = o.getInt("candidateMaxObserveDays");
    }

    /** 序列化当前配置为JSON，供前端“参数配置”面板展示当前值 */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("volumeRatioThreshold", volumeRatioThreshold);
            o.put("volumeMaDays", volumeMaDays);
            o.put("vwapConfirmMinutes", vwapConfirmMinutes);
            o.put("shadowEatRatio", shadowEatRatio);
            o.put("stopNotifyMinutesBeforeClose", stopNotifyMinutesBeforeClose);
            o.put("marketCloseHour", marketCloseHour);
            o.put("marketCloseMinute", marketCloseMinute);
            o.put("earlyWindowMinutes", earlyWindowMinutes);
            o.put("lateWindowMinutes", lateWindowMinutes);
            o.put("earlyWindowVolumeMultiplier", earlyWindowVolumeMultiplier);
            o.put("fullConfirmMinutes", fullConfirmMinutes);
            o.put("sellObserveMinutes", sellObserveMinutes);
            o.put("peakRetraceRatio", peakRetraceRatio);
            o.put("divergenceMidMode", divergenceMidMode);
            o.put("starterPositionPct", starterPositionPct);
            o.put("addHalfPositionPct", addHalfPositionPct);
            o.put("divergenceBodyMaxRatio", divergenceBodyMaxRatio);
            o.put("mainboardLimitPct", mainboardLimitPct);
            o.put("gemStarLimitPct", gemStarLimitPct);
            o.put("bjExchangeLimitPct", bjExchangeLimitPct);
            o.put("candidateDeepBreakPct", candidateDeepBreakPct);
            o.put("candidateMaxObserveDays", candidateMaxObserveDays);
        } catch (Exception ignored) {}
        return o;
    }

    /**
     * 保存用户在设置面板里修改的参数：原地更新当前单例的字段（已经持有旧引用的
     * TradingRuleEngine等类立刻就能看到新值，不需要重启App），并写入可写目录
     * 供下次冷启动加载。
     */
    public static synchronized boolean saveOverrides(Context ctx, JSONObject updates) {
        if (sInstance == null) return false;
        try {
            applyUpdates(sInstance, updates);
            File overrideFile = new File(ctx.getApplicationContext().getFilesDir(), OVERRIDE_FILE);
            try (FileOutputStream fos = new FileOutputStream(overrideFile)) {
                fos.write(sInstance.toJson().toString(2).getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "用户自定义参数已保存到 " + overrideFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "保存用户自定义参数失败", e);
            return false;
        }
    }

    /**
     * 恢复默认参数：删除用户覆盖文件，并原地把当前单例的字段覆盖为纯内置默认值
     *（同样立即对运行中的监控生效，不需要重启App）。
     * 【注意】这里不能写成 sInstance = load(...)：那样会产生一个新对象，
     * 已经通过 final 字段缓存了旧引用的 TradingRuleEngine 等类感知不到，
     * “恢复默认”会显示成功但实际不生效，必须原地修改现有单例的字段。
     */
    public static synchronized boolean resetToDefault(Context ctx) {
        if (sInstance == null) return false;
        File overrideFile = new File(ctx.getApplicationContext().getFilesDir(), OVERRIDE_FILE);
        boolean deleted = !overrideFile.exists() || overrideFile.delete();
        if (deleted) {
            TradingRuleConfig fresh = new TradingRuleConfig();
            try (InputStream is = ctx.getAssets().open("trading_rules.json")) {
                byte[] buf = new byte[is.available()];
                int n = is.read(buf);
                applyUpdates(fresh, new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8)));
            } catch (Exception e) {
                Log.w(TAG, "恢复默认值时重新读取assets失败，使用代码内默认值", e);
            }
            try {
                applyUpdates(sInstance, fresh.toJson());
            } catch (Exception e) {
                Log.w(TAG, "恢复默认值写回单例失败", e);
            }
            Log.i(TAG, "已恢复内置默认参数，用户覆盖文件已删除");
        }
        return deleted;
    }

    public double effectiveVolumeThreshold(int hour, int minute) {
        double base = volumeRatioThreshold;
        int minsFromOpen = hour * 60 + minute - (9 * 60 + 30);
        int minsToClose = (marketCloseHour * 60 + marketCloseMinute) - (hour * 60 + minute);
        if (minsFromOpen >= 0 && minsFromOpen <= earlyWindowMinutes) return base * earlyWindowVolumeMultiplier;
        if (minsToClose >= 0 && minsToClose <= lateWindowMinutes) return base * earlyWindowVolumeMultiplier;
        return base;
    }
}
