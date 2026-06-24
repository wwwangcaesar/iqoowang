package com.monsieurmahjong.iqoowang.dao;


import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Id;
import org.greenrobot.greendao.annotation.Index;
import org.greenrobot.greendao.annotation.Generated;

/**
 * 交易记录实体
 * GreenDAO @Entity 自动生成 TradeRecordDao
 */
@Entity(indexes = {
        @Index(value = "stockCode, tradeTime DESC", name = "IDX_CODE_TIME"),
        @Index(value = "tradeTime DESC", name = "IDX_TIME")
})
public class TradeRecord {

    @Id(autoincrement = true)
    private Long id;

    /** 股票代码，如 600519 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 交易方向：BUY / SELL */
    private String direction;

    /** 成交价格（元） */
    private double price;

    /** 成交数量（股） */
    private int quantity;

    /** 成交金额 = price * quantity */
    private double amount;

    /** 手续费（模拟：万三） */
    private double commission;

    /** 交易时间戳 */
    private long tradeTime;

    /** 交易日期字符串，便于按日分组，格式 yyyy-MM-dd */
    private String tradeDate;

    /** 该笔交易实现的盈亏（卖出时计算） */
    private double realizedPnl;

    /** 触发信号类型：CONDITION_A / CONDITION_B / MANUAL */
    private String signalType;

    /** AI评分（0-100） */
    private int aiScore;

    /** 备注 */
    private String remark;

@Generated(hash = 146372121)
public TradeRecord(Long id, String stockCode, String stockName,
        String direction, double price, int quantity, double amount,
        double commission, long tradeTime, String tradeDate, double realizedPnl,
        String signalType, int aiScore, String remark) {
    this.id = id;
    this.stockCode = stockCode;
    this.stockName = stockName;
    this.direction = direction;
    this.price = price;
    this.quantity = quantity;
    this.amount = amount;
    this.commission = commission;
    this.tradeTime = tradeTime;
    this.tradeDate = tradeDate;
    this.realizedPnl = realizedPnl;
    this.signalType = signalType;
    this.aiScore = aiScore;
    this.remark = remark;
}

@Generated(hash = 1897900341)
public TradeRecord() {
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

public String getDirection() {
    return this.direction;
}

public void setDirection(String direction) {
    this.direction = direction;
}

public double getPrice() {
    return this.price;
}

public void setPrice(double price) {
    this.price = price;
}

public int getQuantity() {
    return this.quantity;
}

public void setQuantity(int quantity) {
    this.quantity = quantity;
}

public double getAmount() {
    return this.amount;
}

public void setAmount(double amount) {
    this.amount = amount;
}

public double getCommission() {
    return this.commission;
}

public void setCommission(double commission) {
    this.commission = commission;
}

public long getTradeTime() {
    return this.tradeTime;
}

public void setTradeTime(long tradeTime) {
    this.tradeTime = tradeTime;
}

public String getTradeDate() {
    return this.tradeDate;
}

public void setTradeDate(String tradeDate) {
    this.tradeDate = tradeDate;
}

public double getRealizedPnl() {
    return this.realizedPnl;
}

public void setRealizedPnl(double realizedPnl) {
    this.realizedPnl = realizedPnl;
}

public String getSignalType() {
    return this.signalType;
}

public void setSignalType(String signalType) {
    this.signalType = signalType;
}

public int getAiScore() {
    return this.aiScore;
}

public void setAiScore(int aiScore) {
    this.aiScore = aiScore;
}

public String getRemark() {
    return this.remark;
}

public void setRemark(String remark) {
    this.remark = remark;
}

}
