package com.monsieurmahjong.iqoowang.dao;


import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Id;
import org.greenrobot.greendao.annotation.Index;
import org.greenrobot.greendao.annotation.Unique;
import org.greenrobot.greendao.annotation.Generated;

/**
 * 持仓实体 — 每个持仓股票一条记录，更新而非追加
 */
@Entity(indexes = {
        @Index(value = "stockCode", unique = true, name = "IDX_POS_CODE")
})
public class Position {

    @Id(autoincrement = true)
    private Long id;

    @Unique
    private String stockCode;

    private String stockName;

    /** 持仓数量（股） */
    private int quantity;

    /** 持仓均价 */
    private double avgCost;

    /** 最新价格（每次行情更新时刷新） */
    private double currentPrice;

    /** 浮动盈亏 = (currentPrice - avgCost) * quantity */
    private double floatPnl;

    /** 浮动盈亏百分比 */
    private double floatPnlPct;

    /** 市值 */
    private double marketValue;

    /** 建仓日期 */
    private String openDate;

    /** 所属板块：sh / sz */
    private String board;

    /** 最后更新时间戳 */
    private long updateTime;

@Generated(hash = 1167455014)
public Position(Long id, String stockCode, String stockName, int quantity,
        double avgCost, double currentPrice, double floatPnl,
        double floatPnlPct, double marketValue, String openDate, String board,
        long updateTime) {
    this.id = id;
    this.stockCode = stockCode;
    this.stockName = stockName;
    this.quantity = quantity;
    this.avgCost = avgCost;
    this.currentPrice = currentPrice;
    this.floatPnl = floatPnl;
    this.floatPnlPct = floatPnlPct;
    this.marketValue = marketValue;
    this.openDate = openDate;
    this.board = board;
    this.updateTime = updateTime;
}

@Generated(hash = 958937587)
public Position() {
}

public Long getId() {
    return this.id;
}

public void setId(Long id) {
    this.id = id;
}

public String getStockCode() {
    return this.stockCode;
}

public void setStockCode(String stockCode) {
    this.stockCode = stockCode;
}

public String getStockName() {
    return this.stockName;
}

public void setStockName(String stockName) {
    this.stockName = stockName;
}

public int getQuantity() {
    return this.quantity;
}

public void setQuantity(int quantity) {
    this.quantity = quantity;
}

public double getAvgCost() {
    return this.avgCost;
}

public void setAvgCost(double avgCost) {
    this.avgCost = avgCost;
}

public double getCurrentPrice() {
    return this.currentPrice;
}

public void setCurrentPrice(double currentPrice) {
    this.currentPrice = currentPrice;
}

public double getFloatPnl() {
    return this.floatPnl;
}

public void setFloatPnl(double floatPnl) {
    this.floatPnl = floatPnl;
}

public double getFloatPnlPct() {
    return this.floatPnlPct;
}

public void setFloatPnlPct(double floatPnlPct) {
    this.floatPnlPct = floatPnlPct;
}

public double getMarketValue() {
    return this.marketValue;
}

public void setMarketValue(double marketValue) {
    this.marketValue = marketValue;
}

public String getOpenDate() {
    return this.openDate;
}

public void setOpenDate(String openDate) {
    this.openDate = openDate;
}

public String getBoard() {
    return this.board;
}

public void setBoard(String board) {
    this.board = board;
}

public long getUpdateTime() {
    return this.updateTime;
}

public void setUpdateTime(long updateTime) {
    this.updateTime = updateTime;
}

}
