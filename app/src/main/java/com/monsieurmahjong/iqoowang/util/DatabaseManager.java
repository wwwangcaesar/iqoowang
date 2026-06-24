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
 *   TradeRecord  — 每笔买卖记录
 *   Position     — 当前持仓（唯一索引 stockCode）
 *   DailyAsset   — 每日资产快照
 *
 * 用法：
 *   DatabaseManager.init(context);
 *   DatabaseManager.get().insertTrade(...);
 */
public class DatabaseManager {

    private static final String TAG = "DatabaseManager";
    private static final String DB_NAME = "stockmaster.db";
    private static final int    DB_VERSION = 1;

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
    // 交易记录
    // ──────────────────────────────────────────

    /**
     * 记录一笔交易，同时更新持仓和日资产快照
     *
     * @param code      股票代码
     * @param name      股票名称
     * @param direction "BUY" | "SELL"
     * @param price     成交价
     * @param quantity  成交股数
     * @param signalType 信号类型
     * @param aiScore   AI评分
     * @param cash      交易后现金余额（由WebView传入）
     * @param totalAsset 交易后总资产
     */
    public long insertTrade(String code, String name, String direction,
                            double price, int quantity,
                            String signalType, int aiScore,
                            double cash, double totalAsset) {
        double amount = price * quantity;
        double commission = amount * 0.0003; // 万三
        double realizedPnl = 0;

        // 计算已实现盈亏（卖出时）
        if ("SELL".equals(direction)) {
            Position pos = getPositionByCode(code);
            if (pos != null) {
                realizedPnl = (price - pos.getAvgCost()) * quantity;
            }
        }

        TradeRecord record = new TradeRecord();
        record.setStockCode(code);
        record.setStockName(name);
        record.setDirection(direction);
        record.setPrice(price);
        record.setQuantity(quantity);
        record.setAmount(amount);
        record.setCommission(commission);
        record.setTradeTime(System.currentTimeMillis());
        record.setTradeDate(mDateFmt.format(new Date()));
        record.setRealizedPnl(realizedPnl);
        record.setSignalType(signalType != null ? signalType : "MANUAL");
        record.setAiScore(aiScore);

        long id = mDaoSession.getTradeRecordDao().insert(record);
        Log.d(TAG, "Trade inserted id=" + id + " " + direction + " " + code + " x" + quantity + " @" + price);

        // 同步更新持仓
        updatePositionAfterTrade(code, name, direction, price, quantity);

        // 保存日快照
        saveDailySnapshot(cash, totalAsset);

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

    /** 统计总盈亏 */
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
    // 持仓管理
    // ──────────────────────────────────────────

    private void updatePositionAfterTrade(String code, String name,
                                          String direction, double price, int qty) {
        PositionDao dao = mDaoSession.getPositionDao();
        Position pos = getPositionByCode(code);

        if ("BUY".equals(direction)) {
            if (pos == null) {
                pos = new Position();
                pos.setStockCode(code);
                pos.setStockName(name);
                pos.setQuantity(qty);
                pos.setAvgCost(price);
                pos.setCurrentPrice(price);
                pos.setOpenDate(mDateFmt.format(new Date()));
                pos.setBoard(code.startsWith("6") ? "sh" : "sz");
                pos.setUpdateTime(System.currentTimeMillis());
                recalcPnl(pos);
                dao.insert(pos);
            } else {
                // 计算新均价
                double newAvg = (pos.getAvgCost() * pos.getQuantity() + price * qty)
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

    public void saveDailySnapshot(double cash, double totalAsset) {
        String today = mDateFmt.format(new Date());
        DailyAssetDao dao = mDaoSession.getDailyAssetDao();

        // 查今日是否已有记录
        List<DailyAsset> existing = dao.queryBuilder()
                .where(DailyAssetDao.Properties.TradeDate.eq(today))
                .limit(1).list();

        // 取昨日资产算日盈亏
        double prevAsset = 100000;
        List<DailyAsset> prev = dao.queryBuilder()
                .orderDesc(DailyAssetDao.Properties.TradeDate)
                .limit(2).list();
        if (prev.size() >= 2) prevAsset = prev.get(1).getTotalAsset();
        else if (!prev.isEmpty() && !prev.get(0).getTradeDate().equals(today))
            prevAsset = prev.get(0).getTotalAsset();

        double posValue = totalAsset - cash;
        double dailyPnl = totalAsset - prevAsset;
        double dailyPct = prevAsset > 0 ? dailyPnl / prevAsset * 100 : 0;
        double totalPnl = totalAsset - 100000;
        double totalPct = totalPnl / 100000 * 100;

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
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    public DaoSession getSession() { return mDaoSession; }
}
