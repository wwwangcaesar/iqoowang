package com.monsieurmahjong.iqoowang.utils;

// SpBudgetUtils.java

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

public class SpBudgetUtils {
    private static final String SP_NAME = "budget_settings";
    private static final String KEY_MONTHLY_BUDGET = "monthly_budget";
    private static final String KEY_CURRENT_MONTH = "current_month";

    private static SpBudgetUtils instance;
    private final SharedPreferences sp;

    private SpBudgetUtils(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public static SpBudgetUtils getInstance(Context context) {
        if (instance == null) {
            synchronized (SpBudgetUtils.class) {
                if (instance == null) {
                    instance = new SpBudgetUtils(context);
                }
            }
        }
        return instance;
    }

    // 保存月度预算（自动记录当前月份）
    public void saveMonthlyBudget(int budget) {
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt(KEY_MONTHLY_BUDGET, budget);
        editor.putString(KEY_CURRENT_MONTH, getCurrentMonthStr());
        editor.apply();
    }

    // 获取月度预算（默认8500）
    public int getMonthlyBudget() {
        // 如果是新月份，自动重置预算（可选逻辑，不需要可删除）
        String savedMonth = sp.getString(KEY_CURRENT_MONTH, "");
        if (!savedMonth.equals(getCurrentMonthStr())) {
            return 8500; // 新月份默认值
        }
        return sp.getInt(KEY_MONTHLY_BUDGET, 8500);
    }

    // 计算每日支出限额（保留两位小数）
    public String getDailyLimit() {
        int monthlyBudget = getMonthlyBudget();
        int daysInMonth = getDaysInCurrentMonth();
        double dailyLimit = (double) monthlyBudget / daysInMonth;
        return String.format("¥%.2f", dailyLimit);
    }

    // 获取当前月份字符串（格式：yyyy-MM）
    private String getCurrentMonthStr() {
        Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.YEAR) + "-" + (calendar.get(Calendar.MONTH) + 1);
    }

    // 获取当月天数
    public int getDaysInCurrentMonth() {
        Calendar calendar = Calendar.getInstance();
        return calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
    }
}
