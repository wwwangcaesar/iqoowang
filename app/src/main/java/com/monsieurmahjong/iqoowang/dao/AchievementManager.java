package com.monsieurmahjong.iqoowang.dao;


import android.content.Context;
import android.content.SharedPreferences;

import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.connect.Achievement;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AchievementManager {

    private static final String SP_ACHIEVEMENT_NAME = "PermanentAchievementsPrefs";
    private static final String KEY_PREFIX = "perm_unlocked_";

    public interface OnAchievementsCalculatedListener {
        /**
         * @param achievements     全量成就状态列表（用于填充列表）
         * @param unlockedCount    总解锁数
         * @param newlyUnlocked    本次切换/刷新时【新鲜解锁】的成就（用于触发弹出动画）
         */
        void onCalculated(List<Achievement> achievements, int unlockedCount, List<Achievement> newlyUnlocked);
    }

    public static void computeRealAchievements(Context context, long currentMonthBudgetCents, OnAchievementsCalculatedListener callback) {
        new Thread(() -> {
            Context appContext = context.getApplicationContext();
            SharedPreferences sp = appContext.getSharedPreferences(SP_ACHIEVEMENT_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();

            AppDatabase db = AppDatabase.getDatabase(appContext);
            List<Expense> allData = db.expenseDao().getExpensesInRangeSync(0L, System.currentTimeMillis());
            if (allData == null) allData = new ArrayList<>();

            // ================== 1. 核心多维度基础指标清洗 ==================
            int totalRecords = allData.size();
            long firstTransactionTimestamp = Long.MAX_VALUE;

            Set<String> allHistoryUniqueDays = new HashSet<>();
            Set<String> thisMonthUniqueDays = new HashSet<>();

            long currentMonthTotalCents = 0;

            SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            SimpleDateFormat sdfMonth = new SimpleDateFormat("yyyy-MM", Locale.getDefault());

            String thisMonthStr = sdfMonth.format(new Date());

            for (Expense e : allData) {
                if (e.getTimestamp() < firstTransactionTimestamp) {
                    firstTransactionTimestamp = e.getTimestamp();
                }

                String dateStr = sdfDate.format(new Date(e.getTimestamp()));
                String monthStr = sdfMonth.format(new Date(e.getTimestamp()));

                allHistoryUniqueDays.add(dateStr);

                if (thisMonthStr.equals(monthStr)) {
                    currentMonthTotalCents += e.getAmount();
                    thisMonthUniqueDays.add(dateStr);
                }
            }

            // ================== 2. 高精度成就规则判定算法 ==================

            // --- 成就 1: 节俭大师 (连续3个月支出低于预算80%) ---
            boolean arch1 = false;
            if (totalRecords > 0 && firstTransactionTimestamp != Long.MAX_VALUE) {
                // 必须从第一次计费开始，日期到达3个月（约90天）以后
                long ninetyDaysMillis = 90L * 24 * 60 * 60 * 1000;
                if ((System.currentTimeMillis() - firstTransactionTimestamp) >= ninetyDaysMillis) {
                    // 精准推算本月(M0)、上月(M1)、上上月(M2) 的总支出
                    Calendar cal = Calendar.getInstance();
                    String m0 = sdfMonth.format(cal.getTime());
                    cal.add(Calendar.MONTH, -1);
                    String m1 = sdfMonth.format(cal.getTime());
                    cal.add(Calendar.MONTH, -2);
                    String m2 = sdfMonth.format(cal.getTime());

                    long m0Cents = 0, m1Cents = 0, m2Cents = 0;
                    for (Expense e : allData) {
                        String mStr = sdfMonth.format(new Date(e.getTimestamp()));
                        if (mStr.equals(m0)) m0Cents += e.getAmount();
                        else if (mStr.equals(m1)) m1Cents += e.getAmount();
                        else if (mStr.equals(m2)) m2Cents += e.getAmount();
                    }

                    long maxAllowedCents = (long) (currentMonthBudgetCents * 0.8);
                    // 连续三个月都有正常记账记录，且均低于预算的80%
                    if (m0Cents > 0 && m0Cents < maxAllowedCents &&
                            m1Cents > 0 && m1Cents < maxAllowedCents &&
                            m2Cents > 0 && m2Cents < maxAllowedCents) {
                        arch1 = true;
                    }
                }
            }

            // --- 成就 2: 省钱专家 (月度结余达到 ¥1,000) ---
            boolean arch2 = currentMonthBudgetCents > 0 && (currentMonthBudgetCents - currentMonthTotalCents) >= 100000L;

            // --- 成就 3: 预算达人 (误差控制在月预算5%内) ---
            boolean arch3 = currentMonthBudgetCents > 0 && currentMonthTotalCents > 0
                    && (Math.abs(currentMonthTotalCents - currentMonthBudgetCents) <= (currentMonthBudgetCents * 0.05));

            // --- 成就 4: 满勤宝宝 (全月全勤记录：即本月1号到今天每天不落) ---
            int todayDayOfMonth = Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
            boolean arch4 = !thisMonthUniqueDays.isEmpty() && (thisMonthUniqueDays.size() >= todayDayOfMonth);

            // --- 成就 5: 理财新手 (首次设置预算) ---
            boolean arch5 = currentMonthBudgetCents > 0;

            // --- 成就 6: 消费达人 (记录100笔支出) ---
            boolean arch6 = totalRecords >= 100;

            // --- 成就 7: 月度冠军 (单月结余超过50%) ---
            boolean arch7 = currentMonthBudgetCents > 0 && currentMonthTotalCents > 0
                    && (currentMonthTotalCents <= (currentMonthBudgetCents * 0.5));

            // --- 成就 8: 坚持就是胜利 (连续使用30天：历史最高连续天数算法) ---
            boolean arch8 = false;
            if (allHistoryUniqueDays.size() >= 30) {
                List<Long> epochDays = new ArrayList<>();
                for (String dayStr : allHistoryUniqueDays) {
                    try {
                        Date d = sdfDate.parse(dayStr);
                        if (d != null) epochDays.add(d.getTime() / (1000 * 60 * 60 * 24));
                    } catch (Exception ignored) {}
                }
                Collections.sort(epochDays);

                int maxStreak = 0;
                int currentStreak = 0;
                long lastDay = -2;
                for (long day : epochDays) {
                    if (day == lastDay + 1) {
                        currentStreak++;
                    } else if (day > lastDay + 1) {
                        currentStreak = 1;
                    }
                    if (currentStreak > maxStreak) maxStreak = currentStreak;
                    lastDay = day;
                }
                if (maxStreak >= 30) arch8 = true;
            }

            // ================== 3. 差分对齐与持久化拦截器 ==================
            List<Achievement> finalFullList = new ArrayList<>();
            List<Achievement> newlyUnlockedList = new ArrayList<>();
            int totalUnlockedCount = 0;

            // 快捷打包判定函数
            totalUnlockedCount += evaluateAndPack(1, "节俭大师", "连续3个月支出低于预算80%", R.drawable.pigmoney, arch1, sp, editor, finalFullList, newlyUnlockedList);
            totalUnlockedCount += evaluateAndPack(2, "省钱专家", "月度结余达到 ¥1,000", R.drawable.trendingup2, arch2, sp, editor, finalFullList, newlyUnlockedList);
            totalUnlockedCount += evaluateAndPack(3, "预算达人", "误差控制在月预算5%内", R.drawable.targetarrow, arch3, sp, editor, finalFullList, newlyUnlockedList);
            totalUnlockedCount += evaluateAndPack(4, "满勤宝宝", "全月全勤记录", R.drawable.calendarcheck, arch4, sp, editor, finalFullList, newlyUnlockedList);
            totalUnlockedCount += evaluateAndPack(5, "理财新手", "首次设置预算", R.drawable.creditcardrefund, arch5, sp, editor, finalFullList, newlyUnlockedList);
            totalUnlockedCount += evaluateAndPack(6, "消费达人", "记录100笔支出", R.drawable.shoppingbagheart, arch6, sp, editor, finalFullList, newlyUnlockedList);
            totalUnlockedCount += evaluateAndPack(7, "月度冠军", "单月结余超过50%", R.drawable.trophy, arch7, sp, editor, finalFullList, newlyUnlockedList);
            totalUnlockedCount += evaluateAndPack(8, "坚持就是胜利", "连续使用30天", R.drawable.stars, arch8, sp, editor, finalFullList, newlyUnlockedList);

            editor.apply(); // 提交持久化

            // ========== 关键修改：定义final临时变量，解决lambda引用报错 ==========
            final List<Achievement> finalAchievements = finalFullList;
            final int finalUnlockedCount = totalUnlockedCount;
            final List<Achievement> finalNewlyUnlocked = newlyUnlockedList;

            // 切回 UI 主线程回调
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (callback != null) {
                    // 使用final临时变量调用回调
                    callback.onCalculated(finalAchievements, finalUnlockedCount, finalNewlyUnlocked);
                }
            });

        }).start();
    }

    private static int evaluateAndPack(int id, String name, String desc, int resId, boolean currentEval,
                                       SharedPreferences sp, SharedPreferences.Editor editor,
                                       List<Achievement> fullList, List<Achievement> newlyList) {
        String key = KEY_PREFIX + id;
        boolean wasUnlockedBefore = sp.getBoolean(key, false);

        // 核心逻辑：一旦历史解锁过，或者本次实时计算通过，即为解锁状态
        boolean finalUnlockState = currentEval || wasUnlockedBefore;

        Achievement achievement = new Achievement(id, name, desc, resId, finalUnlockState);
        fullList.add(achievement);

        // 【动画触发锚点】：如果本次实时计算成功，但历史从未保存过解锁，则是新鲜解锁！
        if (currentEval && !wasUnlockedBefore) {
            newlyList.add(achievement);
            editor.putBoolean(key, true); // 升起持久化标记，防止下次重复弹窗动画
        }

        return finalUnlockState ? 1 : 0;
    }
}