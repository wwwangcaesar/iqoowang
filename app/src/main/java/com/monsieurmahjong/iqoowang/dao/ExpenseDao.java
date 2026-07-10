package com.monsieurmahjong.iqoowang.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.monsieurmahjong.iqoowang.pet.CategoryTotal;

import kotlinx.coroutines.flow.Flow;
import java.util.List;

@Dao
public interface ExpenseDao {

    // 插入新花费（必须在后台线程调用）
    @Insert
    void insertExpense(Expense expense);

    // 获取特定日期的总花费（返回分，前端再除以100）
    // IFNULL(SUM(amount), 0)：无数据时返回0，彻底避免null指针
    @Query("SELECT IFNULL(SUM(amount), 0) FROM expense_table WHERE date_str = :dateStr")
    LiveData<Long> getDailyTotal(String dateStr);

    // 获取特定日期的总花费（同步版本，供后台线程计算使用，如储蓄罐每日结余结算）
    @Query("SELECT IFNULL(SUM(amount), 0) FROM expense_table WHERE date_str = :dateStr")
    long getDailyTotalSync(String dateStr);

    // 获取特定日期的所有花费明细列表
    @Query("SELECT * FROM expense_table WHERE date_str = :dateStr ORDER BY timestamp DESC")
    LiveData<List<Expense>> getDailyExpenses(String dateStr);

    // ===============================================
    // 【修正后】：支持周、月、年任意时间区间的响应式统计
    // ===============================================
    // 修正1：表名从expenses改为expense_table
    // 修正2：字段名从amountInCents改为amount
    // 修正3：添加IFNULL处理无数据情况
    @Query("SELECT IFNULL(SUM(amount), 0) FROM expense_table WHERE timestamp >= :startTime AND timestamp <= :endTime")
    LiveData<Long> getTotalByTimeRange(long startTime, long endTime);

    // 修正：表名从expenses改为expense_table
    @Query("SELECT * FROM expense_table WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    LiveData<List<Expense>> getExpensesByTimeRange(long startTime, long endTime);

    // ===============================================
    // 高阶同步查询（用于后台线程的复杂数据分析）
    // ===============================================

    // 获取指定时间段内的所有消费记录
    @Query("SELECT * FROM expense_table WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    List<Expense> getExpensesInRangeSync(long startTime, long endTime);

    // 根据分类名称模糊查询指定时间段内的总消费
    @Query("SELECT SUM(amount) FROM expense_table WHERE categoryName LIKE :category AND timestamp >= :startTime AND timestamp <= :endTime")
    long getCategoryTotalSync(String category, long startTime, long endTime);
    @Query("SELECT COUNT(*) FROM expense_table")
    int getExpenseCountSync();

    @Query("SELECT SUM(amount) FROM expense_table WHERE date_str LIKE :month || '%'")
    long getMonthTotalCentsSync(String month);

    // 日历视图专用：获取指定月份全部消费记录
// month 传入格式 "yyyy-MM"，例如 "2026-05"
    @Query("SELECT * FROM expense_table WHERE date_str LIKE :month || '%' ORDER BY date_str ASC")
    List<Expense> getAllExpensesByMonthSync(String month);

    @Update
    void updateExpense(Expense expense);

    @Delete
    void deleteExpense(Expense expense);

    // ════════════════════════════════════════════════════
    //  宠物系统专用查询
    // ════════════════════════════════════════════════════
    @Query("SELECT COALESCE(SUM(amount), 0) FROM expense_table WHERE categoryName = :categoryName")
    long getCategoryTotalSync(String categoryName);

    @Query("SELECT COALESCE(SUM(amount), 0) FROM expense_table")
    long getAllTimeTotalSync();

    @Query("SELECT CAST((julianday('now') - julianday(MIN(date_str))) AS INTEGER) FROM expense_table")
    long getDaysSinceFirstExpense();

    @Query("SELECT categoryName as categoryName, COALESCE(SUM(amount),0) as total FROM expense_table GROUP BY categoryName ORDER BY total DESC")
    List<CategoryTotal> getCategoryTotalsSync();

    // ════════════════════════════════════════════════════
    //  全局搜索专用查询（聚合搜索：关键字 + 分类 + 日期区间 + 金额区间）
    //  注：所有筛选条件均为可空，为 null 时该条件不生效
    // ════════════════════════════════════════════════════
    @Query("SELECT * FROM expense_table WHERE " +
            "(:keyword IS NULL OR categoryName LIKE '%' || :keyword || '%' OR remark LIKE '%' || :keyword || '%') " +
            "AND (:category IS NULL OR categoryName = :category) " +
            "AND (:startTime IS NULL OR timestamp >= :startTime) " +
            "AND (:endTime IS NULL OR timestamp <= :endTime) " +
            "AND (:minAmount IS NULL OR amount >= :minAmount) " +
            "AND (:maxAmount IS NULL OR amount <= :maxAmount) " +
            "ORDER BY timestamp DESC")
    List<Expense> searchExpensesSync(String keyword, String category, Long startTime, Long endTime, Long minAmount, Long maxAmount);

    // 全局搜索分类筛选项：取库内已出现过的全部分类名
    @Query("SELECT DISTINCT categoryName FROM expense_table WHERE categoryName IS NOT NULL ORDER BY categoryName ASC")
    List<String> getAllCategoryNamesSync();

}