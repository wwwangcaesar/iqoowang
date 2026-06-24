package com.monsieurmahjong.iqoowang.dao;


import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Id;
import org.greenrobot.greendao.annotation.Index;
import org.greenrobot.greendao.annotation.Generated;

/**
 * 每日资产快照 — 用于绘制资产曲线
 * 每个交易日收盘后记录一次
 */
@Entity(indexes = {
        @Index(value = "tradeDate", unique = true, name = "IDX_ASSET_DATE")
})
public class DailyAsset {

    @Id(autoincrement = true)
    private Long id;

    /** 交易日期 yyyy-MM-dd */
    private String tradeDate;

    /** 总资产 = 现金 + 持仓市值 */
    private double totalAsset;

    /** 现金余额 */
    private double cash;

    /** 持仓总市值 */
    private double positionValue;

    /** 当日盈亏 */
    private double dailyPnl;

    /** 当日盈亏百分比 */
    private double dailyPnlPct;

    /** 累计盈亏（相对初始10万） */
    private double totalPnl;

    /** 累计盈亏百分比 */
    private double totalPnlPct;

    /** 记录时间戳 */
    private long recordTime;

@Generated(hash = 290858366)
public DailyAsset(Long id, String tradeDate, double totalAsset, double cash,
        double positionValue, double dailyPnl, double dailyPnlPct,
        double totalPnl, double totalPnlPct, long recordTime) {
    this.id = id;
    this.tradeDate = tradeDate;
    this.totalAsset = totalAsset;
    this.cash = cash;
    this.positionValue = positionValue;
    this.dailyPnl = dailyPnl;
    this.dailyPnlPct = dailyPnlPct;
    this.totalPnl = totalPnl;
    this.totalPnlPct = totalPnlPct;
    this.recordTime = recordTime;
}

@Generated(hash = 938005396)
public DailyAsset() {
}

public Long getId() {
    return this.id;
}

public void setId(Long id) {
    this.id = id;
}

public String getTradeDate() {
    return this.tradeDate;
}

public void setTradeDate(String tradeDate) {
    this.tradeDate = tradeDate;
}

public double getTotalAsset() {
    return this.totalAsset;
}

public void setTotalAsset(double totalAsset) {
    this.totalAsset = totalAsset;
}

public double getCash() {
    return this.cash;
}

public void setCash(double cash) {
    this.cash = cash;
}

public double getPositionValue() {
    return this.positionValue;
}

public void setPositionValue(double positionValue) {
    this.positionValue = positionValue;
}

public double getDailyPnl() {
    return this.dailyPnl;
}

public void setDailyPnl(double dailyPnl) {
    this.dailyPnl = dailyPnl;
}

public double getDailyPnlPct() {
    return this.dailyPnlPct;
}

public void setDailyPnlPct(double dailyPnlPct) {
    this.dailyPnlPct = dailyPnlPct;
}

public double getTotalPnl() {
    return this.totalPnl;
}

public void setTotalPnl(double totalPnl) {
    this.totalPnl = totalPnl;
}

public double getTotalPnlPct() {
    return this.totalPnlPct;
}

public void setTotalPnlPct(double totalPnlPct) {
    this.totalPnlPct = totalPnlPct;
}

public long getRecordTime() {
    return this.recordTime;
}

public void setRecordTime(long recordTime) {
    this.recordTime = recordTime;
}

}

