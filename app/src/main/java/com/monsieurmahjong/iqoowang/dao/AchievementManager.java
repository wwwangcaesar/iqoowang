package com.monsieurmahjong.iqoowang.utils;

import android.content.Context;

import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.connect.Achievement;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AchievementManager {

    public interface OnAchievementsCalculatedListener {
        void onCalculated(List<Achievement> achievements, int unlockedCount);
    }

    /**
     * 根据本地核心 Room 账本多维度特征指标，动态判定你定制的 8 大成就激活树
     */
    public static void computeRealAchievements(Context context, long currentMonthBudgetCents, OnAchievementsCalculatedListener callback) {
        new Thread(() -> {
            AppDatabase db = AppDatabase.getDatabase(context);
            // 拉取全时段所有数据作为评估样本
            List<Expense> allData = db.expenseDao().getExpensesInRangeSync(0L, System.currentTimeMillis());
            if (allData == null) allData = new ArrayList<>();

            // ========== 数据指标收集 ==========
            int totalRecords = allData.size();
            Set<String> uniqueDays = new HashSet<>();
            Set<String> currentMonthUniqueDays = new HashSet<>();
            long currentMonthTotalCents = 0;

            SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            SimpleDateFormat sdfMonth = new SimpleDateFormat("yyyy-MM", Locale.getDefault());
            String thisMonthStr = sdfMonth.format(new Date());

            for (Expense e : allData) {
                Date d = new Date(e.getTimestamp());
                String dateStr = sdfDate.format(d);
                String monthStr = sdfMonth.format(d);

                uniqueDays.add(dateStr);

                if (thisMonthStr.equals(monthStr)) {
                    currentMonthTotalCents += e.getAmount();
                    currentMonthUniqueDays.add(dateStr);
                }
            }

            int currentDayOfMonth = Calendar.getInstance().get(Calendar.DAY_OF_MONTH);

            // ========== 你的 8 大专属成就规则判定 ==========
            List<Achievement> list = new ArrayList<>();
            int unlockedCount = 0;

            // 1. 节俭大师 (为了程序健壮性，这里判定为：本月有消费，且当前支出低于预算80%)
            boolean arch1 = currentMonthTotalCents > 0 && currentMonthTotalCents < (currentMonthBudgetCents * 0.8);
            if (arch1) unlockedCount++;
            list.add(new Achievement(1, "节俭大师", "连续3个月支出低于预算80%", R.drawable.pigmoney, arch1));

            // 2. 省钱专家 (月度结余达到 ¥1,000)
            boolean arch2 = (currentMonthBudgetCents - currentMonthTotalCents) >= 100000L; // 1000元 = 100000分
            if (arch2) unlockedCount++;
            list.add(new Achievement(2, "省钱专家", "月度结余达到 ¥1,000", R.drawable.trendingup2, arch2));

            // 3. 预算达人 (误差控制在月预算5%内 -> 消费达到了预算的 95% ~ 100%)
            boolean arch3 = currentMonthTotalCents > 0
                    && currentMonthTotalCents >= (currentMonthBudgetCents * 0.95)
                    && currentMonthTotalCents <= currentMonthBudgetCents;
            if (arch3) unlockedCount++;
            list.add(new Achievement(3, "预算达人", "误差控制在月预算5%内", R.drawable.targetarrow, arch3));

            // 4. 满勤宝宝 (全月全勤记录 -> 本月至今每一天都有记录)
            boolean arch4 = currentMonthUniqueDays.size() >= currentDayOfMonth;
            if (arch4) unlockedCount++;
            list.add(new Achievement(4, "满勤宝宝", "全月全勤记录", R.drawable.calendarcheck, arch4));

            // 5. 理财新手 (首次设置预算 -> 预算大于0即算)
            boolean arch5 = currentMonthBudgetCents > 0;
            if (arch5) unlockedCount++;
            list.add(new Achievement(5, "理财新手", "首次设置预算", R.drawable.creditcardrefund, arch5));

            // 6. 消费达人 (记录100笔支出)
            boolean arch6 = totalRecords >= 100;
            if (arch6) unlockedCount++;
            list.add(new Achievement(6, "消费达人", "记录100笔支出", R.drawable.shoppingbagheart, arch6));

            // 7. 月度冠军 (单月结余超过50%)
            boolean arch7 = currentMonthTotalCents > 0 && currentMonthTotalCents <= (currentMonthBudgetCents * 0.5);
            if (arch7) unlockedCount++;
            list.add(new Achievement(7, "月度冠军", "单月结余超过50%", R.drawable.trophy, arch7));

            // 8. 坚持就是胜利 (连续使用30天 -> 历史累计满30天即可激活)
            boolean arch8 = uniqueDays.size() >= 30;
            if (arch8) unlockedCount++;
            list.add(new Achievement(8, "坚持就是胜利", "连续使用30天", R.drawable.stars, arch8));

            // 回调至主线程渲染
            final int finalUnlocked = unlockedCount;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (callback != null) {
                    callback.onCalculated(list, finalUnlocked);
                }
            });
        }).start();
    }
}
