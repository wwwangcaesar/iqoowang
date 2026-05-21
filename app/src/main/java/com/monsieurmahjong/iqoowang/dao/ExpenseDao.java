package com.monsieurmahjong.iqoowang.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
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
}