package com.monsieurmahjong.iqoowang.dao;


import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.connect.Achievement;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class AchievementManager {

    private static final String SP_ACHIEVEMENT_NAME = "PermanentAchievementsPrefs";
    private static final String KEY_PREFIX = "perm_unlocked_";

    public interface OnAchievementsCalculatedListener {
        /**
         * @param achievements  全量成就列表
         * @param unlockedCount 总解锁数量
         * @param newlyUnlocked 本次最新解锁的成就（用于触发弹窗动画）
         */
        void onCalculated(List<Achievement> achievements, int unlockedCount, List<Achievement> newlyUnlocked);
    }

    public static void computeRealAchievements(final Context context, final long currentMonthBudgetCents, final OnAchievementsCalculatedListener callback) {
        new Thread(() -> {
            Context appContext = context.getApplicationContext();
            SharedPreferences sp = appContext.getSharedPreferences(SP_ACHIEVEMENT_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();

            AppDatabase db = AppDatabase.getDatabase(appContext);
            List<Expense> allData = db.expenseDao().getExpensesInRangeSync(0L, System.currentTimeMillis());
            if (allData == null) allData = new ArrayList<>();

            // ================== 1. 基础多维数据聚合 ==================
            int totalRecords = allData.size();
            long firstLogTimestamp = Long.MAX_VALUE;

            Map<String, Long> dailySumMap = new HashMap<>();       // yyyy-MM-dd -> 总支出
            Map<String, Long> monthlySumMap = new HashMap<>();     // yyyy-MM -> 总支出
            Map<String, Long> monthlyFoodSumMap = new HashMap<>(); // yyyy-MM -> 餐饮总支出
            Map<String, Long> monthFirstHalfSumMap = new HashMap<>(); // yyyy-MM -> 15号前总支出
            Map<String, Set<String>> dailyCategoryMap = new HashMap<>(); // yyyy-MM-dd -> 分类集合
            Map<String, Set<String>> monthlyActiveDaysMap = new HashMap<>(); // yyyy-MM -> 活跃天数集合

            boolean arch11NightOwl = false;
            boolean arch12EarlyBird = false;

            SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            SimpleDateFormat monthFormat = new SimpleDateFormat("yyyy-MM", Locale.getDefault());
            Calendar cal = Calendar.getInstance();

            String currentMonthStr = monthFormat.format(new Date());

            for (Expense e : allData) {
                long time = e.getTimestamp();
                long cents = e.getAmount(); // 确保你 ExpenseDao 中获取金额的方法名是 getAmount()
                if (time < firstLogTimestamp) firstLogTimestamp = time;

                Date date = new Date(time);
                String dayStr = dayFormat.format(date);
                String monthStr = monthFormat.format(date);

                // 天/月维度总额累加
                dailySumMap.put(dayStr, dailySumMap.getOrDefault(dayStr, 0L) + cents);
                monthlySumMap.put(monthStr, monthlySumMap.getOrDefault(monthStr, 0L) + cents);

                // 活跃天数收集
                if (!monthlyActiveDaysMap.containsKey(monthStr)) monthlyActiveDaysMap.put(monthStr, new HashSet<>());
                monthlyActiveDaysMap.get(monthStr).add(dayStr);

                // 分类收集
                String cat = e.getCategoryName() != null ? e.getCategoryName() : "其他";
                if (cat.contains("餐饮") || cat.toLowerCase().contains("food")) {
                    monthlyFoodSumMap.put(monthStr, monthlyFoodSumMap.getOrDefault(monthStr, 0L) + cents);
                }
                if (!dailyCategoryMap.containsKey(dayStr)) dailyCategoryMap.put(dayStr, new HashSet<>());
                dailyCategoryMap.get(dayStr).add(cat);

                // 提取时间特征
                cal.setTime(date);
                int hour = cal.get(Calendar.HOUR_OF_DAY);
                if (hour >= 0 && hour < 4) arch11NightOwl = true;
                if (hour >= 5 && hour < 7) arch12EarlyBird = true;

                int dayOfMonth = cal.get(Calendar.DAY_OF_MONTH);
                if (dayOfMonth < 15) {
                    monthFirstHalfSumMap.put(monthStr, monthFirstHalfSumMap.getOrDefault(monthStr, 0L) + cents);
                }
            }

            // ================== 2. 核心算法提取与判决 ==================

            // [日结维度] 铁公鸡 & 剁手狂魔 & 连续天数
            boolean arch9IronRooster = false;
            boolean arch10BigSpender = false;

            List<String> sortedDays = new ArrayList<>(dailySumMap.keySet());
            Collections.sort(sortedDays);

            int maxConsecutiveDays = 0;
            int currentStreak = 0;
            long lastDayEpoch = -2;

            for (String dayStr : sortedDays) {
                long dayTotal = dailySumMap.get(dayStr);
                if (dayTotal > 0 && dayTotal <= 1000) arch9IronRooster = true; // <= 10元
                if (dayTotal >= 100000) arch10BigSpender = true;               // >= 1000元

                try {
                    Date d = dayFormat.parse(dayStr);
                    if (d != null) {
                        long epochDays = d.getTime() / (1000 * 60 * 60 * 24);
                        if (epochDays == lastDayEpoch + 1) {
                            currentStreak++;
                        } else if (epochDays > lastDayEpoch + 1) {
                            currentStreak = 1;
                        }
                        if (currentStreak > maxConsecutiveDays) maxConsecutiveDays = currentStreak;
                        lastDayEpoch = epochDays;
                    }
                } catch (Exception ignored) {}
            }

            // [滑窗维度] 7天内跨越5个分类
            boolean arch17AllRounder = false;
            if (sortedDays.size() >= 7) {
                for (int i = 0; i <= sortedDays.size() - 7; i++) {
                    Set<String> windowCategories = new HashSet<>();
                    for (int j = 0; j < 7; j++) {
                        windowCategories.addAll(dailyCategoryMap.get(sortedDays.get(i + j)));
                    }
                    if (windowCategories.size() >= 5) {
                        arch17AllRounder = true;
                        break;
                    }
                }
            }

            // [历史缺口维度] 四大皆空 (开局后有无账日)
            boolean arch18EmptyVoid = false;
            if (firstLogTimestamp != Long.MAX_VALUE) {
                long yesterdayMillis = System.currentTimeMillis() - (24 * 60 * 60 * 1000L);
                if (yesterdayMillis > firstLogTimestamp) {
                    long totalDaysPassed = (yesterdayMillis - firstLogTimestamp) / (24 * 60 * 60 * 1000L) + 1;
                    if (totalDaysPassed > sortedDays.size()) { // 逝去的天数 > 有记录的天数 = 存在断更
                        arch18EmptyVoid = true;
                    }
                }
            }

            // [月结维度] 各种完结月判定
            int consecutiveFrugalMonths = 0;
            int maxConsecutiveFrugal = 0;

            boolean arch2SaverExpert = false;
            boolean arch3BudgetMaster = false;
            boolean arch4FullAttendance = false;
            boolean arch7MonthChamp = false;
            boolean arch13Gourmet = false;
            boolean arch14MidMonthCrash = false;
            boolean arch16TenThousand = false;

            List<String> sortedMonths = new ArrayList<>(monthlySumMap.keySet());
            Collections.sort(sortedMonths);

            for (String monthKey : sortedMonths) {
                long monthSpent = monthlySumMap.get(monthKey);

                // 半路翻车 (任何月份均可)
                if (monthFirstHalfSumMap.getOrDefault(monthKey, 0L) > currentMonthBudgetCents && currentMonthBudgetCents > 0) {
                    arch14MidMonthCrash = true;
                }

                // --- 仅限已完结的历史月份 ---
                if (monthKey.compareTo(currentMonthStr) < 0) {
                    // 节俭大师连击计算 (支出 < 预算80%)
                    if (currentMonthBudgetCents > 0 && monthSpent < (currentMonthBudgetCents * 0.8)) {
                        consecutiveFrugalMonths++;
                        if (consecutiveFrugalMonths > maxConsecutiveFrugal) maxConsecutiveFrugal = consecutiveFrugalMonths;
                    } else {
                        consecutiveFrugalMonths = 0;
                    }

                    long surplus = currentMonthBudgetCents - monthSpent;

                    // 省钱专家 (结余达 1000)
                    if (surplus >= 100000L && currentMonthBudgetCents > 0) arch2SaverExpert = true;
                    // 月结万元户 (结余达 10000，这里假设万元指1万元即 1000000 Cents)
                    if (surplus >= 1000000L && currentMonthBudgetCents > 0) arch16TenThousand = true;

                    // 预算达人 (误差5%内)
                    if (currentMonthBudgetCents > 0 && Math.abs(surplus) <= (currentMonthBudgetCents * 0.05)) {
                        arch3BudgetMaster = true;
                    }

                    // 月度冠军 (结余超50%)
                    if (currentMonthBudgetCents > 0 && surplus >= (currentMonthBudgetCents * 0.5)) {
                        arch7MonthChamp = true;
                    }

                    // 满勤宝宝 (打卡天数 == 该月自然天数)
                    try {
                        Date d = monthFormat.parse(monthKey);
                        if (d != null) {
                            cal.setTime(d);
                            int maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
                            if (monthlyActiveDaysMap.get(monthKey).size() >= maxDays) {
                                arch4FullAttendance = true;
                            }
                        }
                    } catch (Exception ignored) {}

                    // 干饭魂 (餐饮超 60%)
                    long foodSpent = monthlyFoodSumMap.getOrDefault(monthKey, 0L);
                    if (monthSpent > 0 && ((double) foodSpent / monthSpent) > 0.6) {
                        arch13Gourmet = true;
                    }
                }
            }

            boolean arch1FrugalMaster = maxConsecutiveFrugal >= 3;
            boolean arch5Rookie = currentMonthBudgetCents > 0;
            boolean arch6Consumer = totalRecords >= 100;
            boolean arch8Persistence = maxConsecutiveDays >= 30;
            boolean arch15LongHolder = maxConsecutiveDays >= 7;


            // ================== 3. 装配判定结果并持久化 ==================
            List<Achievement> fullList = new ArrayList<>();
            List<Achievement> newlyUnlockedList = new ArrayList<>();
            int totalUnlockedCount = 0;

            // 你指定的原始 1 - 8 成就 (匹配 R.drawable 资源)
            totalUnlockedCount += evaluate(1, "节俭大师", "连续3个月支出低于预算80%", R.drawable.old0, arch1FrugalMaster, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(2, "省钱专家", "完结月度结余达到 ¥1,000", R.drawable.old1, arch2SaverExpert, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(3, "预算达人", "完结月误差控制在月预算5%内", R.drawable.old2, arch3BudgetMaster, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(4, "满勤宝宝", "达成全月全勤记账记录", R.drawable.old3, arch4FullAttendance, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(5, "理财新手", "首次成功设置财务预算", R.drawable.old4, arch5Rookie, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(6, "消费达人", "累计记录达到 100 笔支出", R.drawable.old5, arch6Consumer, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(7, "月度冠军", "完结单月结余超过总预算 50%", R.drawable.old6, arch7MonthChamp, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(8, "坚持就是胜利", "历史最高连续记账达到 30 天", R.drawable.old7, arch8Persistence, sp, editor, fullList, newlyUnlockedList);

            // 新增的 9 - 18 趣味成就
            totalUnlockedCount += evaluate(9, "铁公鸡", "日支出竟控制在 10 元以内", R.drawable.piglock, arch9IronRooster, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(10, "剁手狂魔", "单日痛快挥霍超过 1,000 元", R.drawable.new2, arch10BigSpender, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(11, "夜猫子记账", "在凌晨 0-4 点进行过财务反思", R.drawable.new3, arch11NightOwl, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(12, "早起鸟儿", "清晨 5-7 点伴随朝阳整理账本", R.drawable.new4, arch12EarlyBird, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(13, "干饭魂", "完结月中餐饮开销占比突破 60%", R.drawable.new5, arch13Gourmet, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(14, "半路翻车", "当月还没过完15号，预算已告罄", R.drawable.new6, arch14MidMonthCrash, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(15, "长线持股", "连续 7 天无中断维持记账习惯", R.drawable.new7, arch15LongHolder, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(16, "月结万元户", "完结月结余资金丰厚达 10,000 元", R.drawable.new8, arch16TenThousand, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(17, "全面发展", "在一周内记账门类跨越 5 种领域", R.drawable.new9, arch17AllRounder, sp, editor, fullList, newlyUnlockedList);
            totalUnlockedCount += evaluate(18, "四大皆空", "完美的自控力，存在无任何支出的空白日", R.drawable.new0, arch18EmptyVoid, sp, editor, fullList, newlyUnlockedList);

            editor.apply();

            // 4. 将 final 数据抛给主线程 UI
            final List<Achievement> finalFullList = fullList;
            final int finalCount = totalUnlockedCount;
            final List<Achievement> finalNewly = newlyUnlockedList;

            new Handler(Looper.getMainLooper()).post(() -> {
                if (callback != null) {
                    callback.onCalculated(finalFullList, finalCount, finalNewly);
                }
            });

        }).start();
    }

    private static int evaluate(int id, String name, String desc, int resId, boolean currentEval,
                                SharedPreferences sp, SharedPreferences.Editor editor,
                                List<Achievement> fullList, List<Achievement> newlyList) {
        String key = KEY_PREFIX + id;
        boolean wasUnlockedBefore = sp.getBoolean(key, false);

        // 永久解锁机制：只要以前解开过，或者现在满足条件，就是 true
        boolean finalUnlockState = currentEval || wasUnlockedBefore;

        Achievement achievement = new Achievement(id, name, desc, resId, finalUnlockState);
        fullList.add(achievement);

        // 检测【新鲜出炉】的解锁，派发给弹窗动画
        if (currentEval && !wasUnlockedBefore) {
            newlyList.add(achievement);
            editor.putBoolean(key, true);
        }

        return finalUnlockState ? 1 : 0;
    }
}
