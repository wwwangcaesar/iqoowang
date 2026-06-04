package com.monsieurmahjong.iqoowang.pet;


import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

import com.monsieurmahjong.iqoowang.dao.AppDatabase;

import org.json.JSONObject;

/**
 * JavaScript ↔ Android 数据桥接层
 *
 * pet.html 中通过 window.AndroidBridge.xxx() 调用此类的方法。
 * 所有方法必须标注 @JavascriptInterface，且在主线程之外调用（WebView 自动在 JS 线程执行）。
 */
public class PetBridge {

    private static final String PREFS_PET  = "pet_state_prefs";
    private static final String KEY_STATE  = "pet_json_state";

    private final Context     ctx;
    private final AppDatabase db;
    private final SharedPreferences prefs;

    public PetBridge(Context ctx, AppDatabase db) {
        this.ctx   = ctx.getApplicationContext();
        this.db    = db;
        this.prefs = this.ctx.getSharedPreferences(PREFS_PET, Context.MODE_PRIVATE);
    }

    // ─────────────────────────────────────────────────
    //  消费数据（Room DB → JSON → pet.html）
    // ─────────────────────────────────────────────────

    /**
     * 返回各分类累计消费（单位：分），供 pet.html 计算 XP 和解锁装备。
     *
     * 返回 JSON 格式：
     * {
     *   "total": 12800,         // 全部消费，单位分
     *   "food": 4200,           // 餐饮
     *   "transport": 1800,      // 交通
     *   "shopping": 3500,       // 购物
     *   "health": 1200,         // 医疗/健康
     *   "entertainment": 900,   // 娱乐
     *   "daily": 2300,          // 日常
     *   "other": 900,           // 其他
     *   "daysSinceFirst": 28    // 使用天数（用于"伙伴天数"统计）
     * }
     */
    @JavascriptInterface
    public String getExpenseData() {
        try {
            // 各分类总额（分）
            long total         = safeQuery(() -> db.expenseDao().getAllTimeTotalSync());
            long food          = safeQuery(() -> db.expenseDao().getCategoryTotalSync("餐饮"));
            long transport     = safeQuery(() -> db.expenseDao().getCategoryTotalSync("交通"));
            long shopping      = safeQuery(() -> db.expenseDao().getCategoryTotalSync("购物"));
            long health        = safeQuery(() -> db.expenseDao().getCategoryTotalSync("医疗"));
            long entertainment = safeQuery(() -> db.expenseDao().getCategoryTotalSync("娱乐"));
            long daily         = safeQuery(() -> db.expenseDao().getCategoryTotalSync("日常"));
            long other         = safeQuery(() -> db.expenseDao().getCategoryTotalSync("其他"));

            // 使用天数（首条记录的日期距今）
            long daysSinceFirst = safeQuery(() -> db.expenseDao().getDaysSinceFirstExpense());

            JSONObject json = new JSONObject();
            json.put("total",         total);
            json.put("food",          food);
            json.put("transport",     transport);
            json.put("shopping",      shopping);
            json.put("health",        health);
            json.put("entertainment", entertainment);
            json.put("daily",         daily);
            json.put("other",         other);
            json.put("daysSinceFirst",daysSinceFirst > 0 ? daysSinceFirst : 1);
            return json.toString();

        } catch (Exception e) {
            // 返回安全默认值，避免 pet.html JS 崩溃
            return "{\"total\":0,\"food\":0,\"transport\":0,\"shopping\":0," +
                    "\"health\":0,\"entertainment\":0,\"daily\":0,\"other\":0," +
                    "\"daysSinceFirst\":1}";
        }
    }

    // ─────────────────────────────────────────────────
    //  宠物状态持久化（SharedPreferences）
    // ─────────────────────────────────────────────────

    /**
     * 获取上次保存的宠物 JSON 状态。
     * 若从未保存过，返回 null（pet.html 会使用默认值）。
     */
    @JavascriptInterface
    public String getSavedState() {
        return prefs.getString(KEY_STATE, null);
    }

    /**
     * 保存宠物 JSON 状态（由 pet.html 在每次互动后调用）。
     *
     * @param stateJson pet.html 中 pet 对象的 JSON 序列化字符串
     */
    @JavascriptInterface
    public void saveState(String stateJson) {
        if (stateJson == null || stateJson.isEmpty()) return;
        prefs.edit().putString(KEY_STATE, stateJson).apply();
    }

    // ─────────────────────────────────────────────────
    //  可选：播放原生音效（如 Android 系统提示音）
    // ─────────────────────────────────────────────────

    /**
     * 可选：pet.html 可以请求 Android 播放振动/音效。
     * 目前预留接口，默认空实现。
     */
    @JavascriptInterface
    public void playHaptic(String type) {
        try {
            android.os.Vibrator vib = (android.os.Vibrator)
                    ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()) {
                if ("light".equals(type)) {
                    vib.vibrate(30);
                } else if ("medium".equals(type)) {
                    vib.vibrate(60);
                } else {
                    vib.vibrate(100);
                }
            }
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────
    //  工具：安全查询（DB 异常时返回 0）
    // ─────────────────────────────────────────────────

    private interface LongSupplier { long get() throws Exception; }

    private long safeQuery(LongSupplier fn) {
        try { return fn.get(); } catch (Exception e) { return 0L; }
    }
}

