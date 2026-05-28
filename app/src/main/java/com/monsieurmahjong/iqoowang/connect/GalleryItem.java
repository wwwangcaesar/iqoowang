package com.monsieurmahjong.iqoowang.connect;


public class GalleryItem {
    public static final int TYPE_HEADER = 0;
    public static final int TYPE_GRID_ITEM = 1;

    public int viewType;
    public String title;       // 标识栏显示的文字（如：2026年、2026年5月）
    public String itemLabel;   // 格子显示的微标签（如：5月、第2周、28日）
    public double amount;      // 对应层级的消费金额
    public boolean hasConsumed;// 是否消费过

    // 携带的原始业务标识，供下钻定位使用
    public int year;
    public int month;
    public int week;
    public String dateStr;

    // 构建标识栏的构造方法
    public GalleryItem(int viewType, String title) {
        this.viewType = viewType;
        this.title = title;
    }

    // 构建网格项的构造方法
    public GalleryItem(int viewType, String itemLabel, double amount, boolean hasConsumed, int year, int month, int week, String dateStr) {
        this.viewType = viewType;
        this.itemLabel = itemLabel;
        this.amount = amount;
        this.hasConsumed = hasConsumed;
        this.year = year;
        this.month = month;
        this.week = week;
        this.dateStr = dateStr;
    }
}

