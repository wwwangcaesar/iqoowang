package com.monsieurmahjong.iqoowang.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * TradeLessonManager —— 真实交易复盘知识库
 *
 * 跟 WisdomManager（用户手动教的通用话术）是两回事：这里存的是本地AI对"一次完整
 * 买入到卖出周期"的复盘总结，天然带着具体股票代码，可以在下次分析同一支股票时
 * 优先参考"这支股票之前踩过的坑/吃到的甜头"，比泛泛的同类经验更有针对性。
 *
 * 触发流程：
 *   1. 某支股票被卖到清仓（持仓归零）时，StockBridge.recordTrade() 调用
 *      markCycleClosed()，往表里插一条 reviewed=0 的待复盘记录（这一步不跑AI，
 *      纯粹是"登记一下这里有个完整周期结束了"）。
 *   2. 用户在"AI大脑"页看到待复盘列表，主动点击某一条，才会真正触发
 *      LocalAIAgent.summarizeTradeCycle() 调用本地AI生成复盘文本，写回本表。
 *   3. 之后 LocalAIAgent 分析/复核同一支股票时，会通过 buildInjectBlock() 把
 *      复盘经验重新塞进 Prompt。
 */
public class TradeLessonManager {

    private static final String TAG = "TradeLessonManager";
    private static final String DB_NAME = "trade_lessons.db";
    private static final int DB_VERSION = 1;

    /** 注入prompt时，本股票专属复盘最多带几条 */
    private static final int MAX_STOCK_ENTRIES = 3;
    /** 注入prompt时，总条目数上限（含本股票专属+同类通用） */
    private static final int MAX_INJECT_ENTRIES = 8;
    /** 注入prompt的字符预算上限，双重保险，避免4B模型输入被喂爆 */
    private static final int MAX_INJECT_CHARS = 900;

    private static TradeLessonManager sInstance;
    private final SQLiteDatabase mDb;
    private final SimpleDateFormat mDateFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    public static void init(Context context) {
        if (sInstance == null) {
            synchronized (TradeLessonManager.class) {
                if (sInstance == null) sInstance = new TradeLessonManager(context.getApplicationContext());
            }
        }
    }

    public static TradeLessonManager get() {
        if (sInstance == null) throw new IllegalStateException("call init() first");
        return sInstance;
    }

    private TradeLessonManager(Context context) {
        mDb = new DbHelper(context).getWritableDatabase();
        Log.i(TAG, "TradeLessonManager initialized");
    }

    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context ctx) { super(ctx, DB_NAME, null, DB_VERSION); }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS trade_lessons (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "stock_code TEXT," +
                    "stock_name TEXT," +
                    "close_trade_id INTEGER," +     // 触发这条复盘记录的那一笔清仓卖出的TradeRecord.id
                    "opened_at INTEGER," +           // 本轮周期第一笔买入的时间
                    "closed_at INTEGER," +           // 清仓那一笔卖出的时间
                    "total_pnl REAL DEFAULT 0," +    // 本轮周期累计已实现盈亏（可能含多次分批买卖）
                    "category TEXT DEFAULT ''," +    // 复盘完成后，AI结合本次周期主要触发的信号类型打的标签
                    "reviewed INTEGER DEFAULT 0," +  // 0=待复盘，1=已复盘
                    "review_summary TEXT," +         // AI复盘的简短摘要（列表展示用）
                    "review_text TEXT" +             // AI复盘的完整文本（注入Prompt用）
                    ")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // 目前只有版本1，暂无需处理
        }
    }

    public static class LessonEntry {
        public long id;
        public String stockCode, stockName;
        public long closeTradeId;
        public long openedAt, closedAt;
        public double totalPnl;
        public String category = "";
        public boolean reviewed;
        public String reviewSummary, reviewText;
    }

    /**
     * 某支股票被卖到清仓时调用——把这一轮"买入到卖出"的完整周期记一笔待复盘。
     * 内部会往回查这支股票的交易记录，自己找出本轮周期的起点（上一次从0股开始买入
     * 的那一刻），把中间所有买卖的已实现盈亏加总，而不是只看最后这一笔卖出的盈亏
     * ——分批建仓分批减仓的情况下，只看最后一笔会严重低估/高估真实的本轮盈亏。
     */
    public void markCycleClosed(String code, String name, long closeTradeId, long closedAt) {
        try {
            List<com.monsieurmahjong.iqoowang.dao.TradeRecord> all =
                    DatabaseManager.get().queryTradesByCode(code);
            // queryTradesByCode 是按时间倒序返回的，这里翻成正序方便顺着时间线走
            List<com.monsieurmahjong.iqoowang.dao.TradeRecord> asc = new ArrayList<>(all);
            java.util.Collections.reverse(asc);

            long openedAt = closedAt;
            double totalPnl = 0;
            int qty = 0;
            // 从最早的一笔开始模拟持仓数量变化，找到"最近一次从0股变成大于0股"的那个时间点，
            // 作为本轮周期的起点；从起点到现在，所有SELL的已实现盈亏加总就是本轮真实盈亏
            int cycleStartIdx = 0;
            for (int i = 0; i < asc.size(); i++) {
                com.monsieurmahjong.iqoowang.dao.TradeRecord t = asc.get(i);
                boolean wasFlat = qty <= 0;
                if ("BUY".equals(t.getDirection())) {
                    if (wasFlat) cycleStartIdx = i;
                    qty += t.getQuantity();
                } else {
                    qty -= t.getQuantity();
                }
            }
            qty = 0;
            for (int i = cycleStartIdx; i < asc.size(); i++) {
                com.monsieurmahjong.iqoowang.dao.TradeRecord t = asc.get(i);
                if ("BUY".equals(t.getDirection())) {
                    qty += t.getQuantity();
                } else {
                    qty -= t.getQuantity();
                    totalPnl += t.getRealizedPnl();
                }
            }
            if (cycleStartIdx < asc.size()) {
                openedAt = asc.get(cycleStartIdx).getTradeTime();
            }

            ContentValues cv = new ContentValues();
            cv.put("stock_code", code);
            cv.put("stock_name", name);
            cv.put("close_trade_id", closeTradeId);
            cv.put("opened_at", openedAt);
            cv.put("closed_at", closedAt);
            cv.put("total_pnl", totalPnl);
            cv.put("reviewed", 0);
            long id = mDb.insert("trade_lessons", null, cv);
            Log.i(TAG, "新增待复盘周期#" + id + " " + name + "(" + code + ") 累计盈亏" + totalPnl);
        } catch (Exception e) {
            Log.e(TAG, "markCycleClosed失败", e);
        }
    }

    /** 待复盘列表（reviewed=0），按平仓时间倒序，最近清仓的排最前面 */
    public List<LessonEntry> getPending() {
        return query("reviewed = 0");
    }

    /** 已复盘列表（reviewed=1），按平仓时间倒序 */
    public List<LessonEntry> getReviewed() {
        return query("reviewed = 1");
    }

    public LessonEntry getById(long id) {
        List<LessonEntry> list = query("id = " + id);
        return list.isEmpty() ? null : list.get(0);
    }

    private List<LessonEntry> query(String where) {
        List<LessonEntry> list = new ArrayList<>();
        Cursor c = mDb.rawQuery(
                "SELECT * FROM trade_lessons WHERE " + where + " ORDER BY closed_at DESC", null);
        try {
            while (c.moveToNext()) {
                list.add(fromCursor(c));
            }
        } finally { c.close(); }
        return list;
    }

    private LessonEntry fromCursor(Cursor c) {
        LessonEntry e = new LessonEntry();
        e.id = c.getLong(c.getColumnIndexOrThrow("id"));
        e.stockCode = c.getString(c.getColumnIndexOrThrow("stock_code"));
        e.stockName = c.getString(c.getColumnIndexOrThrow("stock_name"));
        e.closeTradeId = c.getLong(c.getColumnIndexOrThrow("close_trade_id"));
        e.openedAt = c.getLong(c.getColumnIndexOrThrow("opened_at"));
        e.closedAt = c.getLong(c.getColumnIndexOrThrow("closed_at"));
        e.totalPnl = c.getDouble(c.getColumnIndexOrThrow("total_pnl"));
        String cat = c.getString(c.getColumnIndexOrThrow("category"));
        e.category = cat != null ? cat : "";
        e.reviewed = c.getInt(c.getColumnIndexOrThrow("reviewed")) != 0;
        e.reviewSummary = c.getString(c.getColumnIndexOrThrow("review_summary"));
        e.reviewText = c.getString(c.getColumnIndexOrThrow("review_text"));
        return e;
    }

    /** AI生成复盘完成后回填 */
    public void markReviewed(long id, String category, String summary, String fullText) {
        ContentValues cv = new ContentValues();
        cv.put("reviewed", 1);
        cv.put("category", category == null ? "" : category);
        cv.put("review_summary", summary);
        cv.put("review_text", fullText);
        mDb.update("trade_lessons", cv, "id = ?", new String[]{String.valueOf(id)});
    }

    public String getPendingJson() {
        JSONArray arr = new JSONArray();
        for (LessonEntry e : getPending()) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("code", e.stockCode);
                o.put("name", e.stockName);
                o.put("openedAt", mDateFmt.format(new Date(e.openedAt)));
                o.put("closedAt", mDateFmt.format(new Date(e.closedAt)));
                o.put("holdDays", Math.max(1, Math.round((e.closedAt - e.openedAt) / 86400000.0)));
                o.put("totalPnl", e.totalPnl);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    public String getReviewedJson() {
        JSONArray arr = new JSONArray();
        for (LessonEntry e : getReviewed()) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("code", e.stockCode);
                o.put("name", e.stockName);
                o.put("totalPnl", e.totalPnl);
                o.put("category", e.category);
                o.put("summary", e.reviewSummary);
                o.put("closedAt", mDateFmt.format(new Date(e.closedAt)));
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    /**
     * 生成供 Prompt 注入的复盘知识块——分两层：这支股票自己的复盘优先，
     * 同类操作在其它股票上的复盘案例补充。code为空或actionKey为空时对应层级跳过。
     * 预算控制沿用 WisdomManager 同款套路（条目数+字符数双上限）。
     */
    public String buildInjectBlock(String code, String actionKey) {
        List<LessonEntry> stockSpecific = new ArrayList<>();
        List<LessonEntry> categoryGeneral = new ArrayList<>();
        for (LessonEntry e : getReviewed()) {
            if (e.reviewText == null || e.reviewText.isEmpty()) continue;
            if (code != null && code.equals(e.stockCode)) {
                stockSpecific.add(e);
            } else if (actionKey != null && !actionKey.isEmpty()
                    && actionKey.equalsIgnoreCase(e.category)) {
                categoryGeneral.add(e);
            }
        }
        if (stockSpecific.isEmpty() && categoryGeneral.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n【你复盘过的真实交易经验（务必参考）】\n");
        int used = sb.length();
        int count = 0;

        if (!stockSpecific.isEmpty()) {
            sb.append("—— 这支股票你之前的复盘 ——\n");
            for (LessonEntry e : stockSpecific) {
                if (count >= MAX_STOCK_ENTRIES || count >= MAX_INJECT_ENTRIES) break;
                String line = "· " + e.reviewSummary + "\n";
                if (used + line.length() > MAX_INJECT_CHARS) { sb.append("\n"); return sb.toString(); }
                sb.append(line);
                used += line.length();
                count++;
            }
        }
        if (!categoryGeneral.isEmpty() && count < MAX_INJECT_ENTRIES) {
            sb.append("—— 同类操作你复盘过的其它案例 ——\n");
            for (LessonEntry e : categoryGeneral) {
                if (count >= MAX_INJECT_ENTRIES) break;
                String line = "· " + e.stockName + "：" + e.reviewSummary + "\n";
                if (used + line.length() > MAX_INJECT_CHARS) break;
                sb.append(line);
                used += line.length();
                count++;
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    /** 【危险操作】清空全部复盘记录（含待复盘和已复盘），随"清除全部交易数据"一起调用 */
    public void clearAll() {
        mDb.delete("trade_lessons", null, null);
        Log.i(TAG, "已清空全部交易复盘记录");
    }
}
