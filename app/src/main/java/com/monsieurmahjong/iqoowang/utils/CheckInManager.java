package com.monsieurmahjong.iqoowang.utils;

import android.content.Context;

import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class CheckInManager {
    private static CheckInManager instance;

    // 严谨的单例模式，私有化构造函数
    private CheckInManager() {}

    public static CheckInManager getInstance() {
        if (instance == null) {
            synchronized (CheckInManager.class) {
                if (instance == null) {
                    instance = new CheckInManager();
                }
            }
        }
        return instance;
    }

    /**
     * 1. 判断当天是否已经打卡（有消费记录即打卡）
     * [需在子线程调用]
     */
    public boolean isTodayCheckedIn(Context context) {
        Calendar cal = Calendar.getInstance();

        // 锁定今天 00:00:00
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        long startOfToday = cal.getTimeInMillis();

        // 锁定今天 23:59:59
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        long endOfToday = cal.getTimeInMillis();

        AppDatabase db = AppDatabase.getDatabase(context);
        List<Expense> todayExpenses = db.expenseDao().getExpensesInRangeSync(startOfToday, endOfToday);

        return todayExpenses != null && !todayExpenses.isEmpty();
    }

    /**
     * 2. 记录当天打卡
     */
    public void recordTodayCheckIn(Context context) {
        // 【架构说明】：
        // 因为业务逻辑定为“有消费记录即打卡”，所以应用的核心流程中，
        // 只要用户录入了一笔 Expense，就等同于完成了今日打卡。
        // 所以我们不需要单独维护一个打卡表，此方法可保持为空，或用于未来扩展额外奖励发放逻辑。
    }

    /**
     * 3. 获取本周打卡天数（去除同一天的重复记录）
     * [需在子线程调用]
     */
    public int getWeekCheckInCount(Context context) {
        Calendar cal = Calendar.getInstance();

        // 设定周一为一周的第一天，并回退到本周一清晨
        cal.setFirstDayOfWeek(Calendar.MONDAY);
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        long startOfWeek = cal.getTimeInMillis();
        long endOfWeek = System.currentTimeMillis();

        AppDatabase db = AppDatabase.getDatabase(context);
        List<Expense> weekExpenses = db.expenseDao().getExpensesInRangeSync(startOfWeek, endOfWeek);

        if (weekExpenses == null || weekExpenses.isEmpty()) {
            return 0;
        }

        // 核心去重逻辑：将时间戳转化为 yyyy-MM-dd 字符串塞入 HashSet
        // 这样即使同一天有 10 笔账单，HashSet 最终也只算 1 天
        Set<String> activeDays = new HashSet<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        for (Expense e : weekExpenses) {
            activeDays.add(sdf.format(new Date(e.getTimestamp())));
        }

        return activeDays.size();
    }

    /**
     * 获取本周总天数（固定7天）
     */
    public int getWeekTotalDays() {
        return 7;
    }

    /**
     * 获取打卡进度百分比 (0 ~ 100)
     * [需在子线程调用]
     */
    public int getCheckInProgress(Context context) {
        int checked = getWeekCheckInCount(context);
        int total = getWeekTotalDays();

        // 进度最高锁定在 100%
        int percent = (int) ((checked * 100.0) / total);
        return Math.min(percent, 100);
    }
}
