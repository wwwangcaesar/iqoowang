package com.monsieurmahjong.iqoowang.util;


import android.content.Context;
import android.util.Log;

import com.monsieurmahjong.iqoowang.dao.DailyAsset;
import com.monsieurmahjong.iqoowang.dao.Position;
import com.monsieurmahjong.iqoowang.dao.TradeRecord;
import com.stockmaster.db.DailyAssetDao;
import com.stockmaster.db.DaoMaster;
import com.stockmaster.db.DaoSession;
import com.stockmaster.db.PositionDao;
import com.stockmaster.db.TradeRecordDao;

import org.greenrobot.greendao.database.Database;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * GreenDAO 数据库管理器（单例）
 *
 * 集成三张表：
 *   TradeRecord  — 每笔买卖记录（含真实手续费）
 *   Position     — 当前持仓（唯一索引 stockCode）
 *   DailyAsset   — 每日资产快照
 *
 * 【重要架构说明】现金余额/总资产/总盈亏，现在完全由 Java 端根据持久化的交易记录
 * 自行推算，不再接受 WebView 传入的数值——之前的实现是 WebView 端在内存里维护一个
 * cash 变量，每次App重启都会重置回初始10万，且从未真正写回本地存储，导致"上周五买
 * 了5万股票，今天看可用资金还是10万"这种问题。现在 Java 端才是唯一的真相来源，
 * WebView 只负责展示，不再自己计算/缓存这些数字。
 *
 * 用法：
 *   DatabaseManager.init(context);
 *   DatabaseManager.get().insertTrade(...);
 */
public class DatabaseManager {

    private static final String TAG = "DatabaseManager";
    private static final String DB_NAME = "stockmaster.db";
    private static final int    DB_VERSION = 1;

    /** 模拟账户初始资金 */
    public static final double INITIAL_CAPITAL = 100000.0;

    private static DatabaseManager sInstance;

    private DaoSession mDaoSession;
    private final SimpleDateFormat mDateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    // ──────────────────────────────────────────
    // 初始化 & 单例
    // ──────────────────────────────────────────

    public static void init(Context context) {
        if (sInstance == null) {
            synchronized (DatabaseManager.class) {
                if (sInstance == null) {
                    sInstance = new DatabaseManager(context.getApplicationContext());
                }
            }
        }
    }

    public static DatabaseManager get() {
        if (sInstance == null) throw new IllegalStateException("call init() first");
        return sInstance;
    }

    private DatabaseManager(Context context) {
        DaoMaster.DevOpenHelper helper = new DaoMaster.DevOpenHelper(context, DB_NAME) {
            @Override
            public void onUpgrade(Database db, int oldVersion, int newVersion) {
                Log.i(TAG, "DB upgrade " + oldVersion + " -> " + newVersion);
                // 生产环境替换为迁移脚本，此处 Dev 版直接 drop recreate
                super.onUpgrade(db, oldVersion, newVersion);
            }
        };
        Database db = helper.getWritableDb();
        mDaoSession = new DaoMaster(db).newSession();
        Log.i(TAG, "GreenDAO initialized: " + DB_NAME);
    }

    // ──────────────────────────────────────────
    // 手续费计算（模拟A股真实费率）
    // ──────────────────────────────────────────

    /** 佣金费率：万分之三（多数券商目前的普遍水平），双边收取，最低5元 */
    private static final double COMMISSION_RATE = 0.0003;
    private static final double COMMISSION_MIN = 5.0;
    /** 印花税：卖出单边千分之0.5（2023年8月28日起下调后的现行税率） */
    private static final double STAMP_TAX_RATE = 0.0005;
    /** 过户费：沪市（6开头）双边收取万分之0.1，深市不收 */
    private static final double TRANSFER_FEE_RATE = 0.00001;

    private double calcCommission(double amount) {
        return Math.max(amount * COMMISSION_RATE, COMMISSION_MIN);
    }

    private double calcStampTax(double amount, String direction) {
        return "SELL".equals(direction) ? amount * STAMP_TAX_RATE : 0;
    }

    private double calcTransferFee(double amount, String code) {
        return (code != null && code.startsWith("6")) ? amount * TRANSFER_FEE_RATE : 0;
    }

    /** 计算某笔交易的总手续费（佣金+印花税+过户费） */
    public double calcTotalFee(double amount, String direction, String code) {
        return calcCommission(amount) + calcStampTax(amount, direction) + calcTransferFee(amount, code);
    }

    // ──────────────────────────────────────────
    // 交易记录
    // ──────────────────────────────────────────

    /**
     * 记录一笔交易，同时更新持仓、扣减/增加现金（通过手续费真实模拟）、保存当日快照。
     * 现金/总资产/盈亏全部由本方法内部根据持久化数据推算，不再依赖调用方传入。
     *
     * @param code      股票代码
     * @param name      股票名称
     * @param direction "BUY" | "SELL"
     * @param price     成交价
     * @param quantity  成交股数
     * @param signalType 信号类型
     * @param aiScore   AI评分
     * @return 交易记录id；资金不足时返回-1，T+1限制（卖出数量超过可卖数量）时返回-2，均不会写入任何数据
     */
    public long insertTrade(String code, String name, String direction,
                            double price, int quantity,
                            String signalType, int aiScore) {
        double amount = price * quantity;
        double fee = calcTotalFee(amount, direction, code);

        if ("BUY".equals(direction)) {
            double cashBefore = getCashBalance();
            if (amount + fee > cashBefore + 0.01) { // 容差0.01元避免浮点误差
                Log.w(TAG, "资金不足，拒绝买入: 需要¥" + (amount + fee) + " 可用¥" + cashBefore);
                return -1;
            }
        }

        double realizedPnl = 0;
        if ("SELL".equals(direction)) {
            // T+1硬性拦截：今日买入尚未过户的部分不允许当日卖出（对应操盘手经验终版.md 5节T+1硬约束）
            int sellable = getSellableQuantity(code);
            if (quantity > sellable) {
                Log.w(TAG, "T+1限制拒绝卖出: " + code + " 请求卖出" + quantity + "股，可卖仅" + sellable
                        + "股（差额为当日买入尚未过户部分，需下一交易日起才可卖出）");
                return -2;
            }
            Position pos = getPositionByCode(code);
            if (pos != null) {
                // 已实现盈亏 = 卖出净所得(已扣手续费) - 按均价计算的成本（均价里已经折算了买入时的手续费）
                realizedPnl = (amount - fee) - pos.getAvgCost() * quantity;
            }
        }

        TradeRecord record = new TradeRecord();
        record.setStockCode(code);
        record.setStockName(name);
        record.setDirection(direction);
        record.setPrice(price);
        record.setQuantity(quantity);
        record.setAmount(amount);
        record.setCommission(fee); // 字段名沿用commission，实际存的是三项手续费合计
        record.setTradeTime(System.currentTimeMillis());
        record.setTradeDate(mDateFmt.format(new Date()));
        record.setRealizedPnl(realizedPnl);
        record.setSignalType(signalType != null ? signalType : "MANUAL");
        record.setAiScore(aiScore);

        long id = mDaoSession.getTradeRecordDao().insert(record);
        Log.d(TAG, "Trade inserted id=" + id + " " + direction + " " + code + " x" + quantity
                + " @" + price + " fee=" + fee);

        // 同步更新持仓（均价折算买入手续费）
        updatePositionAfterTrade(code, name, direction, price, quantity, fee);

        // 保存日快照（用Java端自己推算出的现金/总资产，不再依赖外部传入）
        saveDailySnapshot();

        return id;
    }

    /** 查询全部交易记录（时间倒序） */
    public List<TradeRecord> queryAllTrades() {
        return mDaoSession.getTradeRecordDao()
                .queryBuilder()
                .orderDesc(TradeRecordDao.Properties.TradeTime)
                .list();
    }

    /** 查询某只股票的交易历史 */
    public List<TradeRecord> queryTradesByCode(String code) {
        return mDaoSession.getTradeRecordDao()
                .queryBuilder()
                .where(TradeRecordDao.Properties.StockCode.eq(code))
                .orderDesc(TradeRecordDao.Properties.TradeTime)
                .list();
    }

    /** 查询某日的交易记录 */
    public List<TradeRecord> queryTradesByDate(String date) {
        return mDaoSession.getTradeRecordDao()
                .queryBuilder()
                .where(TradeRecordDao.Properties.TradeDate.eq(date))
                .orderDesc(TradeRecordDao.Properties.TradeTime)
                .list();
    }

    /** 统计总已实现盈亏（只算卖出平仓部分，不含当前持仓的浮动盈亏） */
    public double getTotalRealizedPnl() {
        List<TradeRecord> sells = mDaoSession.getTradeRecordDao()
                .queryBuilder()
                .where(TradeRecordDao.Properties.Direction.eq("SELL"))
                .list();
        double total = 0;
        for (TradeRecord t : sells) total += t.getRealizedPnl();
        return total;
    }

    /** 统计胜率 */
    public double getWinRate() {
        List<TradeRecord> sells = mDaoSession.getTradeRecordDao()
                .queryBuilder()
                .where(TradeRecordDao.Properties.Direction.eq("SELL"))
                .list();
        if (sells.isEmpty()) return 0;
        long wins = 0;
        for (TradeRecord t : sells) if (t.getRealizedPnl() > 0) wins++;
        return (double) wins / sells.size();
    }

    // ──────────────────────────────────────────
    // 账户资金（唯一真相来源——完全从交易流水推算，不依赖任何外部传入/缓存）
    // ──────────────────────────────────────────

    /**
     * 当前可用现金余额 = 初始资金 - 全部买入净支出(含手续费) + 全部卖出净所得(扣手续费)。
     * 每次都从完整交易流水重新算一遍，保证不管App重启多少次、SharedPreferences有没有
     * 正常写入，这个数字永远和真实交易历史一致，不会出现"重启就变回10万"的问题。
     */
    public double getCashBalance() {
        double cash = INITIAL_CAPITAL;
        for (TradeRecord t : queryAllTrades()) {
            double amount = t.getAmount();
            double fee = t.getCommission(); // 该笔交易的手续费合计
            if ("BUY".equals(t.getDirection())) {
                cash -= (amount + fee);
            } else if ("SELL".equals(t.getDirection())) {
                cash += (amount - fee);
            }
        }
        return cash;
    }

    /** 当前全部持仓的市值合计（按各持仓最新价） */
    public double getPositionsMarketValue() {
        double value = 0;
        for (Position p : getAllPositions()) {
            value += p.getCurrentPrice() * p.getQuantity();
        }
        return value;
    }

    /** 总资产 = 现金 + 持仓市值 */
    public double getTotalAssetValue() {
        return getCashBalance() + getPositionsMarketValue();
    }

    /** 总盈亏（相对初始资金，含已实现+浮动） */
    public double getTotalPnl() {
        return getTotalAssetValue() - INITIAL_CAPITAL;
    }

    public double getTotalPnlPct() {
        return getTotalPnl() / INITIAL_CAPITAL * 100;
    }

    /**
     * 今日盈亏 = 当前总资产 - 昨天收盘时的总资产快照。
     * 找不到"昨天"的快照（比如刚用没几天）时，跟初始资金比。
     */
    public double getTodayPnl() {
        String today = mDateFmt.format(new Date());
        List<DailyAsset> prevSnaps = mDaoSession.getDailyAssetDao()
                .queryBuilder()
                .where(DailyAssetDao.Properties.TradeDate.notEq(today))
                .orderDesc(DailyAssetDao.Properties.TradeDate)
                .limit(1).list();
        double baseline = prevSnaps.isEmpty() ? INITIAL_CAPITAL : prevSnaps.get(0).getTotalAsset();
        return getTotalAssetValue() - baseline;
    }

    /**
     * 一次性打包账户核心数据，供WebView启动/交易后统一刷新展示，
     * 避免前端自己拼凑造成和Java端不一致。
     */
    public String getAccountSummaryJson() {
        JSONObject obj = new JSONObject();
        try {
            double cash = getCashBalance();
            double posValue = getPositionsMarketValue();
            double total = cash + posValue;
            double totalPnl = total - INITIAL_CAPITAL;
            obj.put("cash", cash);
            obj.put("positionsValue", posValue);
            obj.put("totalAsset", total);
            obj.put("totalPnl", totalPnl);
            obj.put("totalPnlPct", totalPnl / INITIAL_CAPITAL * 100);
            obj.put("todayPnl", getTodayPnl());
            obj.put("initialCapital", INITIAL_CAPITAL);
            obj.put("positionCount", getAllPositions().size());
        } catch (Exception e) {
            Log.e(TAG, "getAccountSummaryJson", e);
        }
        return obj.toString();
    }

    // ──────────────────────────────────────────
    // 持仓管理
    // ──────────────────────────────────────────

    private void updatePositionAfterTrade(String code, String name,
                                          String direction, double price, int qty, double fee) {
        PositionDao dao = mDaoSession.getPositionDao();
        Position pos = getPositionByCode(code);

        if ("BUY".equals(direction)) {
            if (pos == null) {
                pos = new Position();
                pos.setStockCode(code);
                pos.setStockName(name);
                pos.setQuantity(qty);
                // 均价把买入手续费折算进成本，更贴近真实持仓成本
                pos.setAvgCost((price * qty + fee) / qty);
                pos.setCurrentPrice(price);
                pos.setOpenDate(mDateFmt.format(new Date()));
                pos.setBoard(code.startsWith("6") ? "sh" : "sz");
                pos.setUpdateTime(System.currentTimeMillis());
                recalcPnl(pos);
                dao.insert(pos);
            } else {
                // 计算新均价（含本次买入的手续费）
                double newAvg = (pos.getAvgCost() * pos.getQuantity() + price * qty + fee)
                        / (pos.getQuantity() + qty);
                pos.setAvgCost(newAvg);
                pos.setQuantity(pos.getQuantity() + qty);
                pos.setCurrentPrice(price);
                pos.setUpdateTime(System.currentTimeMillis());
                recalcPnl(pos);
                dao.update(pos);
            }
        } else if ("SELL".equals(direction) && pos != null) {
            int newQty = pos.getQuantity() - qty;
            if (newQty <= 0) {
                dao.delete(pos);
                Log.d(TAG, "Position cleared: " + code);
            } else {
                pos.setQuantity(newQty);
                pos.setCurrentPrice(price);
                pos.setUpdateTime(System.currentTimeMillis());
                recalcPnl(pos);
                dao.update(pos);
            }
        }
    }

    private void recalcPnl(Position pos) {
        double pnl = (pos.getCurrentPrice() - pos.getAvgCost()) * pos.getQuantity();
        double pct = pos.getAvgCost() > 0
                ? (pos.getCurrentPrice() - pos.getAvgCost()) / pos.getAvgCost() * 100
                : 0;
        pos.setFloatPnl(pnl);
        pos.setFloatPnlPct(pct);
        pos.setMarketValue(pos.getCurrentPrice() * pos.getQuantity());
    }

    public Position getPositionByCode(String code) {
        List<Position> list = mDaoSession.getPositionDao()
                .queryBuilder()
                .where(PositionDao.Properties.StockCode.eq(code))
                .limit(1).list();
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Position> getAllPositions() {
        return mDaoSession.getPositionDao().loadAll();
    }

    // ────────────────────────────────
    // T+1 可卖状态（操盘手经验终版.md 5.4节：仓位需按可卖状态分层记录）
    // ────────────────────────────────

    /** 某只股票“今日已买入、尚未过T+1”的数量——这部分当日不可卖出 */
    public int getTodayBoughtQuantity(String code) {
        String today = mDateFmt.format(new Date());
        List<TradeRecord> todayBuys = mDaoSession.getTradeRecordDao()
                .queryBuilder()
                .where(TradeRecordDao.Properties.StockCode.eq(code),
                        TradeRecordDao.Properties.Direction.eq("BUY"),
                        TradeRecordDao.Properties.TradeDate.eq(today))
                .list();
        int sum = 0;
        for (TradeRecord t : todayBuys) sum += t.getQuantity();
        return sum;
    }

    /**
     * T+1可执行卖出数量 = 持仓总量 - 今日买入未过户部分（不会为负）。
     * 简化假设：卖出总是先消耗“更早买入、已过户”的部分，与真实券商行为一致。
     */
    public int getSellableQuantity(String code) {
        Position pos = getPositionByCode(code);
        if (pos == null) return 0;
        int lockedToday = getTodayBoughtQuantity(code);
        return Math.max(0, pos.getQuantity() - lockedToday);
    }

    /** 当日买入、尚未过T+1、暂不可卖出的数量 */
    public int getLockedQuantity(String code) {
        Position pos = getPositionByCode(code);
        if (pos == null) return 0;
        return Math.max(0, pos.getQuantity() - getSellableQuantity(code));
    }

    /**
     * 批量更新持仓最新价格（行情推送时调用）
     * @param priceJson {"600519":1680.5,"000001":11.2,...}
     */
    public void batchUpdatePrices(JSONObject priceJson) {
        PositionDao dao = mDaoSession.getPositionDao();
        List<Position> positions = dao.loadAll();
        for (Position pos : positions) {
            try {
                if (priceJson.has(pos.getStockCode())) {
                    double newPrice = priceJson.getDouble(pos.getStockCode());
                    pos.setCurrentPrice(newPrice);
                    pos.setUpdateTime(System.currentTimeMillis());
                    recalcPnl(pos);
                    dao.update(pos);
                }
            } catch (Exception e) {
                Log.w(TAG, "batchUpdatePrices skip " + pos.getStockCode(), e);
            }
        }
    }

    // ──────────────────────────────────────────
    // 每日资产快照
    // ──────────────────────────────────────────

    /**
     * 保存当日资产快照——现金/总资产完全由Java端自己推算（getCashBalance/
     * getTotalAssetValue），不再需要调用方传入，避免SharedPreferences没写对导致
     * 快照记录了错误的现金数字。
     */
    public void saveDailySnapshot() {
        double cash = getCashBalance();
        double totalAsset = getTotalAssetValue();
        String today = mDateFmt.format(new Date());
        DailyAssetDao dao = mDaoSession.getDailyAssetDao();

        // 查今日是否已有记录
        List<DailyAsset> existing = dao.queryBuilder()
                .where(DailyAssetDao.Properties.TradeDate.eq(today))
                .limit(1).list();

        // 取最近一个不是今天的快照算日盈亏
        List<DailyAsset> prev = dao.queryBuilder()
                .where(DailyAssetDao.Properties.TradeDate.notEq(today))
                .orderDesc(DailyAssetDao.Properties.TradeDate)
                .limit(1).list();
        double prevAsset = prev.isEmpty() ? INITIAL_CAPITAL : prev.get(0).getTotalAsset();

        double posValue = totalAsset - cash;
        double dailyPnl = totalAsset - prevAsset;
        double dailyPct = prevAsset > 0 ? dailyPnl / prevAsset * 100 : 0;
        double totalPnl = totalAsset - INITIAL_CAPITAL;
        double totalPct = totalPnl / INITIAL_CAPITAL * 100;

        DailyAsset snap;
        if (!existing.isEmpty()) {
            snap = existing.get(0);
        } else {
            snap = new DailyAsset();
            snap.setTradeDate(today);
        }
        snap.setTotalAsset(totalAsset);
        snap.setCash(cash);
        snap.setPositionValue(posValue);
        snap.setDailyPnl(dailyPnl);
        snap.setDailyPnlPct(dailyPct);
        snap.setTotalPnl(totalPnl);
        snap.setTotalPnlPct(totalPct);
        snap.setRecordTime(System.currentTimeMillis());

        if (!existing.isEmpty()) dao.update(snap);
        else dao.insert(snap);
    }

    /**
     * 获取近 N 天资产快照（JSON 数组，供 WebView 消费）
     * 格式：[{"date":"2024-06-01","total":102000,"pnl":2000,"pnlPct":2.0},...]
     */
    public String getDailyAssetJson(int days) {
        List<DailyAsset> list = mDaoSession.getDailyAssetDao()
                .queryBuilder()
                .orderDesc(DailyAssetDao.Properties.TradeDate)
                .limit(days).list();
        JSONArray arr = new JSONArray();
        for (int i = list.size() - 1; i >= 0; i--) {
            DailyAsset d = list.get(i);
            try {
                JSONObject obj = new JSONObject();
                obj.put("date", d.getTradeDate());
                obj.put("total", d.getTotalAsset());
                obj.put("cash", d.getCash());
                obj.put("posValue", d.getPositionValue());
                obj.put("dailyPnl", d.getDailyPnl());
                obj.put("dailyPnlPct", d.getDailyPnlPct());
                obj.put("totalPnl", d.getTotalPnl());
                obj.put("totalPnlPct", d.getTotalPnlPct());
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    /**
     * 获取交易历史 JSON（供 WebView 展示）
     */
    public String getTradeHistoryJson(int limit) {
        List<TradeRecord> list = mDaoSession.getTradeRecordDao()
                .queryBuilder()
                .orderDesc(TradeRecordDao.Properties.TradeTime)
                .limit(limit).list();
        JSONArray arr = new JSONArray();
        for (TradeRecord t : list) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", t.getId());
                obj.put("code", t.getStockCode());
                obj.put("name", t.getStockName());
                obj.put("direction", t.getDirection());
                obj.put("price", t.getPrice());
                obj.put("qty", t.getQuantity());
                obj.put("amount", t.getAmount());
                obj.put("fee", t.getCommission());
                obj.put("pnl", t.getRealizedPnl());
                obj.put("signal", t.getSignalType());
                obj.put("score", t.getAiScore());
                obj.put("date", t.getTradeDate());
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    /**
     * 获取所有持仓 JSON（供 WebView 初始化）
     */
    public String getPositionsJson() {
        List<Position> list = getAllPositions();
        JSONArray arr = new JSONArray();
        for (Position p : list) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("code", p.getStockCode());
                obj.put("name", p.getStockName());
                obj.put("qty", p.getQuantity());
                obj.put("avgCost", p.getAvgCost());
                obj.put("currentPrice", p.getCurrentPrice());
                obj.put("floatPnl", p.getFloatPnl());
                obj.put("floatPnlPct", p.getFloatPnlPct());
                obj.put("marketValue", p.getMarketValue());
                obj.put("board", p.getBoard());
                obj.put("openDate", p.getOpenDate());
                int sellableQty = getSellableQuantity(p.getStockCode());
                obj.put("sellableQty", sellableQty);
                obj.put("lockedQty", Math.max(0, p.getQuantity() - sellableQty));
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    public DaoSession getSession() { return mDaoSession; }
}
