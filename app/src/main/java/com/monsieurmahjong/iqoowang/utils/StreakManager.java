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
 *
 * 【2026-08 新增】断签补签机制：
 * 过去断签一天，连续天数就直接清零重来。现在改为：断签后旧的连续天数被
 * "冻结"记为 pendingRestore，用户接下来连续签到 N 天（N 根据断签前的天数
 * 分档递增，见 computeRecoveryTarget）就能把 pendingRestore 加回来，相当于
 * "补签"找回之前的天数，而不是清零重来。如果补签过程中又断签一次，
 * pendingRestore 不会被这次更小的断签覆盖——永远盯着"最早那次"断签前的天数，
 * 只是重新开始数这一轮的补签天数，等于给了无限次补签机会。
 */
public class StreakManager {

    private static final String PREFS = "streak_prefs";
    private static final String KEY_COUNT = "streak_count";
    private static final String KEY_LAST_DATE = "streak_last_date";
    private static final String KEY_DATES = "streak_dates_set";
    private static final String KEY_PENDING_RESTORE = "streak_pending_restore";
    private static final String KEY_RECOVERY_TARGET = "streak_recovery_target";
    private static final String KEY_JUST_RESTORED = "streak_just_restored";
    private static final String KEY_JUST_RESTORED_TOTAL = "streak_just_restored_total";

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

    /**
     * 断签检测与状态归一化。每次读取或写入打卡状态前都会先调用，确保"断签已经
     * 发生"这个事实第一时间反映到可展示的状态里——不需要等用户下一次点开
     * "点亮打卡"才发现自己断签了，只是打开页面看看也能看到补签提示。
     *
     * 只在"上次打卡日期"既不是今天、也不是昨天时才判定为断签
     * （即中间至少空了一个完整的自然日）。
     */
    private void reconcile(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        String lastDate = sp.getString(KEY_LAST_DATE, "");
        if (lastDate.isEmpty()) return; // 从未打过卡，无需处理

        String today = today();
        if (today.equals(lastDate) || yesterday().equals(lastDate)) return; // 今天已打卡 / 昨天打卡，正常延续中

        int brokenStreak = sp.getInt(KEY_COUNT, 0);
        int pending = sp.getInt(KEY_PENDING_RESTORE, 0);
        SharedPreferences.Editor editor = sp.edit();

        if (brokenStreak > 0 && pending == 0) {
            // 第一次发现这次断签：把断掉的天数冻结下来，按这一档算出需要补签几天
            editor.putInt(KEY_PENDING_RESTORE, brokenStreak);
            editor.putInt(KEY_RECOVERY_TARGET, computeRecoveryTarget(brokenStreak));
        }
        // 已经有 pending 说明是"补签过程中又断了一次"——不覆盖，只重新清零这一轮的计数，
        // pendingRestore/recoveryTarget 保持不变，永远对应最早那次断签
        editor.putInt(KEY_COUNT, 0);
        editor.apply();
    }

    /**
     * 断签后需要连续补签几天才能恢复原有连续天数——按断签前的连续天数分档递增，
     * 天数越长、之前投入的坚持越多，补签门槛也越高，但封顶 7 天，避免补签本身
     * 变成新的挫败感。分档节点和 streak-flame.html 里 FLAME_TIERS 的里程碑对齐。
     */
    private int computeRecoveryTarget(int brokenStreak) {
        if (brokenStreak < 3) return 1;
        if (brokenStreak < 7) return 2;
        if (brokenStreak < 14) return 3;
        if (brokenStreak < 30) return 4;
        if (brokenStreak < 50) return 5;
        if (brokenStreak < 100) return 6;
        return 7;
    }

    /** 当前连续打卡天数（补签期间，这里是"这一轮已经补签了几天"，不是被冻结的历史值） */
    public int getCurrentStreak(Context ctx) {
        reconcile(ctx);
        return prefs(ctx).getInt(KEY_COUNT, 0);
    }

    /** 今天是否已经打过卡 */
    public boolean isCheckedToday(Context ctx) {
        return today().equals(prefs(ctx).getString(KEY_LAST_DATE, ""));
    }

    /** 最近 7 天（含今天，纯按自然日往前推 6 天的滚动窗口）的打卡情况 */
    public boolean[] getLast7Days(Context ctx) {
        reconcile(ctx);
        return computeLast7Days(prefs(ctx));
    }

    private boolean[] computeLast7Days(SharedPreferences sp) {
        Set<String> dates = sp.getStringSet(KEY_DATES, new HashSet<>());
        boolean[] result = new boolean[7];
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -6);
        for (int i = 0; i < 7; i++) {
            result[i] = dates.contains(sdf.format(cal.getTime()));
            cal.add(Calendar.DATE, 1);
        }
        return result;
    }

    /** 一次性打包读取全部展示状态，供 StreakBridge 一次调用拿全量数据，避免重复触发 reconcile */
    public StreakState getState(Context ctx) {
        reconcile(ctx);
        SharedPreferences sp = prefs(ctx);

        StreakState s = new StreakState();
        s.currentStreak = sp.getInt(KEY_COUNT, 0);
        s.checkedToday = today().equals(sp.getString(KEY_LAST_DATE, ""));
        s.last7Days = computeLast7Days(sp);
        s.pendingRestore = sp.getInt(KEY_PENDING_RESTORE, 0);
        s.recoveryTarget = sp.getInt(KEY_RECOVERY_TARGET, 0);
        s.recoveryProgress = s.pendingRestore > 0 ? s.currentStreak : 0;
        s.justRestored = sp.getBoolean(KEY_JUST_RESTORED, false);
        s.justRestoredTotal = sp.getInt(KEY_JUST_RESTORED_TOTAL, 0);

        if (s.justRestored) {
            // 一次性标记：读取后立即消费掉，避免下次打开页面又重复弹一次"补签成功"
            sp.edit().putBoolean(KEY_JUST_RESTORED, false).apply();
        }
        return s;
    }

    /**
     * 执行一次打卡（幂等：同一天重复调用不会重复计数）。
     * 连续性判断已经交给 reconcile() 在读取/写入前统一处理：走到这里时，
     * KEY_COUNT 要么是"延续到昨天"的正确值，要么已经因断签被清零，
     * 两种情况都只需要在原有基础上 +1。
     * 如果 +1 后达到了补签目标天数，触发"恢复"：把冻结的旧天数加回来。
     */
    public void checkIn(Context ctx) {
        reconcile(ctx);
        SharedPreferences sp = prefs(ctx);
        String today = today();
        String lastDate = sp.getString(KEY_LAST_DATE, "");
        if (today.equals(lastDate)) return; // 今天已经打过卡，忽略重复调用

        int newStreak = sp.getInt(KEY_COUNT, 0) + 1;

        int pending = sp.getInt(KEY_PENDING_RESTORE, 0);
        int target = sp.getInt(KEY_RECOVERY_TARGET, 0);
        boolean justRestored = false;
        if (pending > 0 && newStreak >= target) {
            newStreak = pending + newStreak; // 补签达标：冻结的天数 + 这一轮实际补签的天数，一起加回来
            pending = 0;
            target = 0;
            justRestored = true;
        }

        Set<String> dates = new HashSet<>(sp.getStringSet(KEY_DATES, new HashSet<>()));
        dates.add(today);
        trimOldDates(dates);

        SharedPreferences.Editor editor = sp.edit()
                .putInt(KEY_COUNT, newStreak)
                .putString(KEY_LAST_DATE, today)
                .putStringSet(KEY_DATES, dates)
                .putInt(KEY_PENDING_RESTORE, pending)
                .putInt(KEY_RECOVERY_TARGET, target);

        if (justRestored) {
            editor.putBoolean(KEY_JUST_RESTORED, true);
            editor.putInt(KEY_JUST_RESTORED_TOTAL, newStreak);
        }
        editor.apply();
    }

    /** 只保留最近 40 天的打卡日期记录，避免 Set 无限增长 */
    private void trimOldDates(Set<String> dates) {
        if (dates.size() <= 40) return;
        Calendar cutoff = Calendar.getInstance();
        cutoff.add(Calendar.DATE, -40);
        String cutoffStr = sdf.format(cutoff.getTime());
        dates.removeIf(d -> d.compareTo(cutoffStr) < 0);
    }

    /** 打包状态快照，供桥接层一次性序列化成 JSON */
    public static class StreakState {
        public int currentStreak;
        public boolean checkedToday;
        public boolean[] last7Days;
        /** >0 表示有一次断签正在等待补签，值是断签前冻结的天数 */
        public int pendingRestore;
        /** 需要连续补签几天才能恢复（配合 pendingRestore 一起看） */
        public int recoveryTarget;
        /** 这一轮已经连续补签了几天（等于 currentStreak，仅在 pendingRestore>0 时有意义） */
        public int recoveryProgress;
        /** 上一次 checkIn() 是否恰好触发了补签恢复，供一次性庆祝提示用 */
        public boolean justRestored;
        /** 触发恢复那一刻的最终天数，配合 justRestored 展示"恢复到XX天！" */
        public int justRestoredTotal;
    }
}
