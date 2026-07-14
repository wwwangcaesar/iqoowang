package com.monsieurmahjong.iqoowang.piggy;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

import com.monsieurmahjong.iqoowang.dao.AppDatabase;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * JavaScript ↔ Android 数据桥接层（piggy-bank.html 专用）
 * piggy-bank.html 中通过 window.PiggyBridge.xxx() 调用此类的方法。
 *
 * 心愿目标数据（名称/目标金额/期限/已存金额/皮肤）用独立 SharedPreferences 持久化，
 * 不涉及 Room 数据库表结构变更，不影响任何已记录的历史消费数据。
 *
 * "每日结余自动入罐 / 超支生气" 的日均预算基准，
 * 复用 HistoryFragment 首页展示的同一份预算配置（SereneLedgerConfig / monthly_budget_cents），
 * 保证用户在首页看到的预算数字和储蓄罐里的计算逻辑是一致的。
 */
public class PiggyBridge {

    private static final String PREFS_PIGGY = "piggy_bank_prefs";
    private static final String KEY_NAME = "goal_name";
    private static final String KEY_TARGET_CENTS = "goal_target_cents";
    private static final String KEY_SAVED_CENTS = "goal_saved_cents";
    private static final String KEY_TOTAL_DAYS = "goal_total_days";
    private static final String KEY_CREATED_AT = "goal_created_at";
    private static final String KEY_STYLE = "goal_style";
    private static final String KEY_LAST_PROCESSED_DATE = "last_daily_result_date";

    // 与 HistoryFragment / SettingsFragment 共用的首页预算配置
    private static final String PREFS_BUDGET = "SereneLedgerConfig";
    private static final String KEY_MONTHLY_BUDGET_CENTS = "monthly_budget_cents";

    private final Context ctx;
    private final AppDatabase db;
    private final SharedPreferences prefs;
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    public PiggyBridge(Context ctx, AppDatabase db) {
        // 注意：这里故意保留 Activity Context（而非 applicationContext），
        // 因为 close() 需要对它 finish()；WebView 销毁时 bridge 也会被回收，不会长期持有导致泄漏。
        this.ctx = ctx;
        this.db = db;
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS_PIGGY, Context.MODE_PRIVATE);
    }

    // ─────────────────────────────────────────────────
    //  心愿目标：读取 / 保存 / 存入 / 换肤
    // ─────────────────────────────────────────────────

    /**
     * 返回当前心愿目标数据：
     * { name, targetCents, savedCents, totalDays, createdAt, style }
     */
    @JavascriptInterface
    public String getGoalData() {
        try {
            JSONObject json = new JSONObject();
            json.put("name", prefs.getString(KEY_NAME, "我的心愿基金"));
            json.put("targetCents", prefs.getLong(KEY_TARGET_CENTS, 89900L));
            json.put("savedCents", prefs.getLong(KEY_SAVED_CENTS, 0L));
            json.put("totalDays", prefs.getInt(KEY_TOTAL_DAYS, 30));

            long createdAt = prefs.getLong(KEY_CREATED_AT, 0L);
            if (createdAt == 0L) {
                createdAt = System.currentTimeMillis();
                prefs.edit().putLong(KEY_CREATED_AT, createdAt).apply();
            }
            json.put("createdAt", createdAt);
            json.put("style", prefs.getString(KEY_STYLE, "reward"));
            return json.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 保存/修改心愿目标：名称、目标金额（分）、总期限（天，从当前时刻重新起算） */
    @JavascriptInterface
    public void saveGoal(String name, long targetCents, int totalDays, long createdAt) {
        prefs.edit()
                .putString(KEY_NAME, name)
                .putLong(KEY_TARGET_CENTS, targetCents)
                .putInt(KEY_TOTAL_DAYS, totalDays)
                .putLong(KEY_CREATED_AT, createdAt)
                .apply();
    }

    /** 存入一笔金额（分），累加到已存金额 */
    @JavascriptInterface
    public void deposit(long cents) {
        long saved = prefs.getLong(KEY_SAVED_CENTS, 0L) + cents;
        prefs.edit().putLong(KEY_SAVED_CENTS, saved).apply();
    }

    /** 清空已存金额（归零），目标名称/金额/期限不受影响；用于自动结余入罐算错时重置 */
    @JavascriptInterface
    public void resetSaved() {
        prefs.edit().putLong(KEY_SAVED_CENTS, 0L).apply();
    }

    /** 切换储蓄罐皮肤风格："reward"（治愈）或 "cyber"（赛博朋克） */
    @JavascriptInterface
    public void setStyle(String style) {
        prefs.edit().putString(KEY_STYLE, style).apply();
    }

    // ─────────────────────────────────────────────────
    //  每日结余自动入罐 / 超支提醒（真实消费数据驱动）
    // ─────────────────────────────────────────────────

    /**
     * 结算"昨天"的消费情况（每个自然日只会真正结算一次，重复调用返回 status:'none'）：
     *  - 日均预算 = 首页月度预算 / 当月天数
     *  - 昨日花费 < 日均预算 → 差额自动存入储蓄罐，返回 status:'saved'
     *  - 昨日花费 ≥ 日均预算 → 不存钱，返回 status:'overspent'
     *
     * 返回 JSON：
     * { status:'saved'|'overspent'|'none', amount, overAmount, dailyBudget, yesterdaySpend, dateStr }
     */
    @JavascriptInterface
    public String getDailyResult() {
        try {
            String today = sdf.format(new Date());
            String lastProcessed = prefs.getString(KEY_LAST_PROCESSED_DATE, "");
            if (today.equals(lastProcessed)) {
                return "{\"status\":\"none\"}";
            }

            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DATE, -1);
            String yesterdayStr = sdf.format(cal.getTime());
            int daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

            SharedPreferences budgetPrefs = ctx.getApplicationContext()
                    .getSharedPreferences(PREFS_BUDGET, Context.MODE_PRIVATE);
            long monthlyBudgetCents = budgetPrefs.getLong(KEY_MONTHLY_BUDGET_CENTS, 500000L);
            long dailyBudgetCents = daysInMonth > 0 ? monthlyBudgetCents / daysInMonth : 0;

            long yesterdaySpendCents = db.expenseDao().getDailyTotalSync(yesterdayStr);

            JSONObject json = new JSONObject();
            json.put("dailyBudget", dailyBudgetCents / 100.0);
            json.put("yesterdaySpend", yesterdaySpendCents / 100.0);
            json.put("dateStr", yesterdayStr);

            if (dailyBudgetCents <= 0) {
                json.put("status", "none");
            } else if (yesterdaySpendCents < dailyBudgetCents) {
                long surplusCents = dailyBudgetCents - yesterdaySpendCents;
                long newSaved = prefs.getLong(KEY_SAVED_CENTS, 0L) + surplusCents;
                prefs.edit().putLong(KEY_SAVED_CENTS, newSaved).apply();
                json.put("status", "saved");
                json.put("amount", surplusCents / 100.0);
            } else {
                long overCents = yesterdaySpendCents - dailyBudgetCents;
                json.put("status", "overspent");
                json.put("overAmount", overCents / 100.0);
            }

            // 无论哪种结果，立即标记今天已结算过，避免重复触发（幂等保护，不依赖前端回调）
            prefs.edit().putString(KEY_LAST_PROCESSED_DATE, today).apply();

            return json.toString();
        } catch (Exception e) {
            return "{\"status\":\"none\"}";
        }
    }

    /** 兼容前端调用；实际去重已在 getDailyResult() 内部完成，这里保留为空实现即可 */
    @JavascriptInterface
    public void ackDailyResult() {
        // no-op：见 getDailyResult() 内的幂等标记逻辑
    }

    /** 关闭页面（页面内的返回按钮调用） */
    @JavascriptInterface
    public void close() {
        if (ctx instanceof Activity) {
            ((Activity) ctx).runOnUiThread(((Activity) ctx)::finish);
        }
    }
}
