package com.monsieurmahjong.iqoowang.dao;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "expense_table",
        indices = {@Index(value = "date_str")}
)
public class Expense {
    @PrimaryKey(autoGenerate = true)
    private final long id;

    private long amount;          // 单位：分
    private  String categoryName;  // 分类名称
    private final long timestamp;
    private final String date_str;      // 格式化日期：yyyy-MM-dd
    private final String source;        // 来源：NFC, MANUAL, SCREENSHOT
    @ColumnInfo(name = "remark")
    private String remark;

    // 2026-08 新增：摇一摇/NFC记账时自动定位。用装箱 Double 而不是 primitive double，
    // 是为了让"没有定位数据"能用 null 表示，不用 0.0 当哨兵值——
    // 0.0/0.0 在地图上是几内亚湾外海一个真实存在的坐标点，用它表示"无数据"
    // 是地理类 App 里一个经典的坑，这里直接避开。
    private Double latitude;
    private Double longitude;
    // 位置显示名称：默认取逆地理编码结果，支持用户在详情弹窗长按改名
    private String locationName;

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public String getLocationName() { return locationName; }
    public void setLocationName(String locationName) { this.locationName = locationName; }

    public void setAmount(long amount) { this.amount = amount; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    // ✅ 保留这个作为Room的主构造函数（不要加@Ignore）
    // Room会用这个构造函数从数据库中读取数据并创建对象
    public Expense(long id, long amount, String categoryName, long timestamp, String date_str, String source) {
        this.id = id;
        this.amount = amount;
        this.categoryName = categoryName;
        this.timestamp = timestamp;
        this.date_str = date_str;
        this.source = source;
    }

    // ✅ 给这个简化构造函数添加@Ignore注解
    // 告诉Room：这个是给我们业务代码用的，你不要用
    @Ignore
    public Expense(long amount, String categoryName, String date_str, String source) {
        this(0, amount, categoryName, System.currentTimeMillis(), date_str, source);
    }

    // Getter方法保持不变
    public long getId() { return id; }
    public long getAmount() { return amount; }
    public String getCategoryName() { return categoryName; }
    public long getTimestamp() { return timestamp; }
    public String getDate_str() { return date_str; }
    public String getSource() { return source; }

    // equals/hashCode/toString保持不变
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Expense expense = (Expense) o;
        return id == expense.id &&
                amount == expense.amount &&
                timestamp == expense.timestamp &&
                categoryName.equals(expense.categoryName) &&
                date_str.equals(expense.date_str) &&
                source.equals(expense.source);
    }

    @Override
    public int hashCode() {
        int result = (int) (id ^ (id >>> 32));
        result = 31 * result + (int) (amount ^ (amount >>> 32));
        result = 31 * result + categoryName.hashCode();
        result = 31 * result + (int) (timestamp ^ (timestamp >>> 32));
        result = 31 * result + date_str.hashCode();
        result = 31 * result + source.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "Expense{" +
                "id=" + id +
                ", amount=" + amount +
                ", categoryName='" + categoryName + '\'' +
                ", timestamp=" + timestamp +
                ", date_str='" + date_str + '\'' +
                ", source='" + source + '\'' +
                '}';
    }
}