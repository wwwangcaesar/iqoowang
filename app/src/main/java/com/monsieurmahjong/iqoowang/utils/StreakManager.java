package com.monsieurmahjong.iqoowang.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 独立的"每日打卡连续流"管理器（Duolingo 火苗风格）。
 *
 * 【重要】与 CheckInManager（"有消费记录即打卡"，周维度奖励）完全独立、互不影响：
 * 本管理器只认用户在 StreakActivity 页面里手动点击"点亮打卡"这一个动作，
 * 不读取、不依赖 Expense 消费数据，避免两套打卡语义混淆。
 */
public class StreakManager {

    private static final String PREFS = "streak_prefs";
    private static final String KEY_COUNT = "streak_count";
    private static final String KEY_LAST_DATE = "streak_last_date";
    private static final String KEY_DATES = "streak_dates_set";

    private static StreakManager instance;

    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    private StreakManager() {}

    public static StreakManager getInstance() {
        if (instance == null) {
            synchronized (StreakManager.class) {
                if (instance == null) {
                    instance = new StreakManager();
                }
            }
        }
        return instance;
    }

    private SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String today() {
        return sdf.format(new Date());
    }

    private String yesterday() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -1);
        return sdf.format(cal.getTime());
    }

    /** 当前连续打卡天数（断签后会在下一次真正打卡时才归零，展示的是"最后一次有效值"） */
    public int getCurrentStreak(Context ctx) {
        return prefs(ctx).getInt(KEY_COUNT, 0);
    }

    /** 今天是否已经打过卡 */
    public boolean isCheckedToday(Context ctx) {
        return today().equals(prefs(ctx).getString(KEY_LAST_DATE, ""));
    }

    /** 最近 7 天（含今天，按周一…周日语义无关，纯按自然日往前推 6 天）的打卡情况 */
    public boolean[] getLast7Days(Context ctx) {
        Set<String> dates = prefs(ctx).getStringSet(KEY_DATES, new HashSet<>());
        boolean[] result = new boolean[7];
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -6);
        for (int i = 0; i < 7; i++) {
            result[i] = dates.contains(sdf.format(cal.getTime()));
            cal.add(Calendar.DATE, 1);
        }
        return result;
    }

    /**
     * 执行一次打卡（幂等：同一天重复调用不会重复计数）。
     * 连续性判断：若上次打卡是"昨天" → 连续天数 +1；否则（首次打卡或断签后）→ 重置为 1。
     */
    public void checkIn(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        String today = today();
        String lastDate = sp.getString(KEY_LAST_DATE, "");
        if (today.equals(lastDate)) return; // 今天已经打过卡，忽略重复调用

        int streak = sp.getInt(KEY_COUNT, 0);
        int newStreak = yesterday().equals(lastDate) ? streak + 1 : 1;

        Set<String> dates = new HashSet<>(sp.getStringSet(KEY_DATES, new HashSet<>()));
        dates.add(today);
        trimOldDates(dates);

        sp.edit()
                .putInt(KEY_COUNT, newStreak)
                .putString(KEY_LAST_DATE, today)
                .putStringSet(KEY_DATES, dates)
                .apply();
    }

    /** 只保留最近 40 天的打卡日期记录，避免 Set 无限增长 */
    private void trimOldDates(Set<String> dates) {
        if (dates.size() <= 40) return;
        Calendar cutoff = Calendar.getInstance();
        cutoff.add(Calendar.DATE, -40);
        String cutoffStr = sdf.format(cutoff.getTime());
        dates.removeIf(d -> d.compareTo(cutoffStr) < 0);
    }
}
