package com.monsieurmahjong.iqoowang.utils;

// CheckInManager.java

import android.content.Context;

public class CheckInManager {
    private static CheckInManager instance;

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

    // ==================== TODO: 以下方法需要你实现数据库逻辑 ====================
    // 1. 判断当天是否已经打卡（有消费记录即打卡）
    public boolean isTodayCheckedIn(Context context) {
        // TODO: 查询本地数据库，判断今天是否有支出记录
        // 示例返回：return false;
        return false;
    }

    // 2. 记录当天打卡（在添加支出记录时调用）
    public void recordTodayCheckIn(Context context) {
        // TODO: 向数据库插入今天的打卡记录（如果不存在）
        // 注意：去重处理，一天只能打卡一次
    }

    // 3. 获取本周打卡天数
    public int getWeekCheckInCount(Context context) {
        // TODO: 查询本周一到今天的打卡记录数量
        // 示例返回：return 5;
        return 5;
    }
    // ==========================================================================

    // 获取本周总天数（固定7天）
    public int getWeekTotalDays() {
        return 7;
    }

    // 获取打卡进度百分比
    public int getCheckInProgress(Context context) {
        int checked = getWeekCheckInCount(context);
        int total = getWeekTotalDays();
        return (int) ((checked * 100.0) / total);
    }
}
