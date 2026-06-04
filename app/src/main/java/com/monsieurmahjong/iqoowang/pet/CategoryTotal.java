package com.monsieurmahjong.iqoowang.pet;


/**
 * ExpenseDao.getCategoryTotalsSync() 的查询结果 POJO
 *
 * Room 会自动将 SQL 列名映射到字段名：
 *   category_name AS categoryName → this.categoryName
 *   SUM(amount)   AS total        → this.total
 */
public class CategoryTotal {

    /** 分类名称，如 "餐饮"、"交通"、"购物" */
    public String categoryName;

    /** 该分类全部时间累计消费（单位：分） */
    public long total;

    // 供宠物进化计算使用的便捷方法
    public double getTotalYuan() {
        return total / 100.0;
    }
}
