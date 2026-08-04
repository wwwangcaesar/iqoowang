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
 * WatchlistManager — 候选池管理器
 *
 * 状态机（人工确认版）：
 *   WATCHING → PENDING_STARTER → STARTER
 *   STARTER  → PENDING_ADD / PENDING_FULL → ADDED / FULL
 *   ADDED    → PENDING_FULL → FULL
 *   任意持仓 → PENDING_WARN（建议抛压）/ PENDING_STOP（建议立即清仓止损）
 */
public class WatchlistManager {

    private static final String TAG = "WatchlistManager";
    private static final String DB_NAME = "watchlist.db";
    private static final int DB_VERSION = 5;

    public static final String STATUS_WATCHING = "WATCHING";
    public static final String STATUS_STARTER = "STARTER";
    public static final String STATUS_ADDED = "ADDED";
    public static final String STATUS_FULL = "FULL";
    public static final String STATUS_STOPPED = "STOPPED";
    public static final String STATUS_REMOVED = "MANUAL_REMOVED";

    public static final String STATUS_PENDING_STARTER = "PENDING_STARTER";
    public static final String STATUS_PENDING_ADD = "PENDING_ADD";
    public static final String STATUS_PENDING_FULL = "PENDING_FULL";
    public static final String STATUS_PENDING_WARN = "PENDING_WARN";
    public static final String STATUS_PENDING_STOP = "PENDING_STOP";

    private static WatchlistManager sInstance;
    private final SQLiteDatabase mDb;
    private final SimpleDateFormat mDateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat mTimeFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    /** 实时计算的水线/VWAP/量比快照——不落库，只是内存缓存，供候选池卡片结构化展示用。
     *  每轮监控tick后由 RealtimeMonitorService 写入，重启App后自然清空，下一轮tick会重新填充 */
    private static final java.util.Map<String, double[]> sLiveMetricsCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** RealtimeMonitorService 每次评估后调用，缓存本次水线/VWAP/量比供前端结构化展示 */
    public void updateLiveMetrics(String code, double waterLine, double vwap, double volRatio) {
        sLiveMetricsCache.put(code, new double[]{waterLine, vwap, volRatio});
    }

    public static void init(Context context) {
        if (sInstance == null) {
            synchronized (WatchlistManager.class) {
                if (sInstance == null) sInstance = new WatchlistManager(context.getApplicationContext());
            }
        }
    }

    public static WatchlistManager get() {
        if (sInstance == null) throw new IllegalStateException("call init() first");
        return sInstance;
    }

    private WatchlistManager(Context context) {
        mDb = new DbHelper(context).getWritableDatabase();
        Log.i(TAG, "WatchlistManager initialized");
    }

    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context ctx) { super(ctx, DB_NAME, null, DB_VERSION); }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS watchlist (" +
                    "code TEXT PRIMARY KEY," +
                    "name TEXT," +
                    "added_date TEXT," +
                    "status TEXT," +
                    "score INTEGER," +
                    "signal TEXT," +
                    "starter_price REAL," +
                    "starter_date TEXT," +
                    "added_price REAL," +
                    "added_date2 TEXT," +
                    "full_price REAL," +
                    "full_date TEXT," +
                    "last_note TEXT," +
                    "prev_status TEXT," +
                    "pending_action TEXT," +
                    "pending_price REAL," +
                    "pending_ai_confirmed INTEGER," +
                    "pending_reason TEXT," +
                    "pending_ai_full TEXT," +
                    "pending_at INTEGER," +
                    "div_k_high REAL," +
                    "div_k_low REAL," +
                    "div_mid_kline REAL," +
                    "div_mid_retrace REAL," +
                    "div_k_date TEXT," +
                    "prev_yang_low REAL," +
                    "peak_gain_pct REAL," +
                    "peak_gain_date TEXT," +
                    "pattern_open REAL," +
                    "pattern_high REAL," +
                    "pattern_close REAL," +
                    "pattern_low REAL," +
                    "pattern_date TEXT," +
                    "ai_status TEXT DEFAULT 'NONE'," +
                    "updated_at INTEGER)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int o, int n) {
            if (o < 2) {
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN prev_status TEXT"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pending_action TEXT"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pending_price REAL"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pending_ai_confirmed INTEGER"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pending_reason TEXT"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pending_at INTEGER"); } catch (Exception ignored) {}
            }
            if (o < 3) {
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN full_price REAL"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN full_date TEXT"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pending_ai_full TEXT"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN div_k_high REAL"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN div_k_low REAL"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN div_mid_kline REAL"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN div_mid_retrace REAL"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN div_k_date TEXT"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN prev_yang_low REAL"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN peak_gain_pct REAL"); } catch (Exception ignored) {}
            }
            if (o < 4) {
                // peak_gain_date：让“当日峰值涨幅”真正按交易日重置（修复此前跨日不清零的问题）
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN peak_gain_date TEXT"); } catch (Exception ignored) {}
                // pattern_*：固化选股当天（仙人指路形态日）的OHLC，满仓“吃掉上影线”判断改为读这份固定值，
                // 不再动态取“最新缓存K线”（否则观察多日后或重新下载过数据，会算错影线）
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pattern_open REAL"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pattern_high REAL"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pattern_close REAL"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pattern_low REAL"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pattern_date TEXT"); } catch (Exception ignored) {}
            }
            if (o < 5) {
                // ai_status：规则命中就立即推送通知不再等AI，AI复核改为异步补充定性分析，
                // 前端需要知道这次推送的信号AI分析是“进行中”还是“已完成”，
                // 取值：NONE(无待处理信号)/PENDING(已推送，AI分析中)/CONFIRMED(AI支持)/DOUBTED(AI存疑)
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN ai_status TEXT DEFAULT 'NONE'"); } catch (Exception ignored) {}
            }
        }
    }

    /** 兼容旧调用（如手动持仓同步进候选池）——没有选股形态日OHLC可存，pattern_*字段留空，
     *  evaluateFullPosition会因此自动跳过满仓判断，不会拿错误数据硬算 */
    public void addIfAbsent(String code, String name, int score, String signal) {
        addIfAbsent(code, name, score, signal, 0, 0, 0, 0, null);
    }

    /**
     * @param patternOpen/patternHigh/patternClose/patternLow/patternDate 选股当天（仙人指路形态日）
     *        的OHLC，用于后续“吃掉上影线”满仓判断。没有则传0/null（比如非选股器来源）。
     */
    public void addIfAbsent(String code, String name, int score, String signal,
                             double patternOpen, double patternHigh, double patternClose, double patternLow,
                             String patternDate) {
        if (getByCode(code) != null) return;
        ContentValues cv = new ContentValues();
        cv.put("code", code);
        cv.put("name", name);
        cv.put("added_date", mDateFmt.format(new Date()));
        cv.put("status", STATUS_WATCHING);
        cv.put("score", score);
        cv.put("signal", signal);
        cv.put("pattern_open", patternOpen);
        cv.put("pattern_high", patternHigh);
        cv.put("pattern_close", patternClose);
        cv.put("pattern_low", patternLow);
        if (patternDate != null) cv.put("pattern_date", patternDate);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.insertWithOnConflict("watchlist", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        Log.i(TAG, "入池: " + name + "(" + code + ") score=" + score + " signal=" + signal
                + (patternHigh > 0 ? String.format(Locale.CHINA, " 形态日OHLC=%.2f/%.2f/%.2f/%.2f(%s)",
                        patternOpen, patternHigh, patternClose, patternLow, patternDate) : " 无形态日OHLC"));
    }

    /**
     * 规则命中时立即调用，不等AI。通知是否推送完全由规则引擎决定，AI慢不会影响这个已经
     * 做完的决定。ai_status先标记为PENDING，等AI后台跑完后由 updatePendingAiResult() 回填真实结论。
     */
    public void markPending(String code, String pendingAction, double price, String ruleNote) {
        WatchlistItem cur = getByCode(code);
        if (cur == null) return;
        ContentValues cv = new ContentValues();
        cv.put("prev_status", cur.status);
        cv.put("status", pendingStatusFor(pendingAction));
        cv.put("pending_action", pendingAction);
        cv.put("pending_price", price);
        cv.put("pending_ai_confirmed", 0);
        cv.put("pending_reason", ruleNote); // 先用规则理由占位，AI跑完会覆盖成它自己的分析
        cv.put("pending_ai_full", "");
        cv.put("ai_status", "PENDING");
        cv.put("pending_at", System.currentTimeMillis());
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
        Log.i(TAG, "已推送待确认信号（规则已通过，AI定性分析进行中）: " + code + " " + pendingAction);
    }

    /**
     * AI完成定性分析后调用，只补充AI的结论，不改变已经发出的通知或pending状态本身。
     * 如果用户在AI跑完之前就已经手动确认/忽略了这条信号（pendingAction已被清空），就不再写入，
     * 避免覆盖掉用户新的操作状态。
     */
    public void updatePendingAiResult(String code, boolean aiConfirmed, String aiReason, String aiFullText) {
        WatchlistItem cur = getByCode(code);
        if (cur == null || cur.pendingAction == null) return;
        ContentValues cv = new ContentValues();
        cv.put("ai_status", aiConfirmed ? "CONFIRMED" : "DOUBTED");
        cv.put("pending_ai_confirmed", aiConfirmed ? 1 : 0);
        cv.put("pending_reason", aiReason);
        cv.put("pending_ai_full", aiFullText);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
        Log.i(TAG, "AI定性分析完成: " + code + " " + (aiConfirmed ? "支持" : "存疑"));
    }

    public void saveTrackState(String code, TradingRuleEngine.DivergenceState state) {
        if (state == null) return;
        ContentValues cv = new ContentValues();
        cv.put("div_k_high", state.divKHigh);
        cv.put("div_k_low", state.divKLow);
        cv.put("div_mid_kline", state.divMidKline);
        cv.put("div_mid_retrace", state.divMidRetrace);
        cv.put("div_k_date", state.divKDate);
        cv.put("prev_yang_low", state.prevYangLow);
        cv.put("peak_gain_pct", state.peakGainPct);
        cv.put("peak_gain_date", state.peakGainDate);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
    }

    public TradingRuleEngine.DivergenceState loadTrackState(WatchlistItem item) {
        TradingRuleEngine.DivergenceState s = new TradingRuleEngine.DivergenceState();
        if (item == null) return s;
        s.divKHigh = item.divKHigh;
        s.divKLow = item.divKLow;
        s.divMidKline = item.divMidKline;
        s.divMidRetrace = item.divMidRetrace;
        s.divKDate = item.divKDate;
        s.prevYangLow = item.prevYangLow;
        s.peakGainPct = item.peakGainPct;
        s.peakGainDate = item.peakGainDate;
        return s;
    }

    /** 从候选池构造今天满仓判断需要的形态日参考价——没存过（patternHigh为0）就返回 hasData=false，
     *  让 evaluateFullPosition 直接跳过满仓判断而不是拿错误数据硬算 */
    public TradingRuleEngine.PatternRef loadPatternRef(WatchlistItem item) {
        TradingRuleEngine.PatternRef p = new TradingRuleEngine.PatternRef();
        if (item == null) return p;
        p.open = item.patternOpen;
        p.high = item.patternHigh;
        p.close = item.patternClose;
        p.low = item.patternLow;
        p.date = item.patternDate;
        p.hasData = item.patternHigh > 0;
        return p;
    }

    private String pendingStatusFor(String action) {
        switch (action) {
            case "BUY_STARTER": return STATUS_PENDING_STARTER;
            case "ADD_HALF":
            case "ADD_POSITION": return STATUS_PENDING_ADD;
            case "BUY_FULL": return STATUS_PENDING_FULL;
            case "WARN_PRESSURE": return STATUS_PENDING_WARN;
            case "STOP_LOSS": return STATUS_PENDING_STOP;
            default: return STATUS_WATCHING;
        }
    }

    public void confirmPending(String code) {
        WatchlistItem cur = getByCode(code);
        if (cur == null || cur.pendingAction == null) return;
        switch (cur.pendingAction) {
            case "BUY_STARTER": markStarter(code, cur.pendingPrice, cur.pendingReason); break;
            case "ADD_HALF":
            case "ADD_POSITION": markAdded(code, cur.pendingPrice, cur.pendingReason); break;
            case "BUY_FULL": markFull(code, cur.pendingPrice, cur.pendingReason); break;
            case "WARN_PRESSURE":
                updateNote(code, "已确认抛压预警：" + cur.pendingReason);
                break;
            case "STOP_LOSS": markStopped(code, cur.pendingReason); break;
        }
        clearPendingFields(code);
    }

    public void dismissPending(String code) {
        WatchlistItem cur = getByCode(code);
        if (cur == null) return;
        ContentValues cv = new ContentValues();
        cv.put("status", cur.prevStatus != null ? cur.prevStatus : STATUS_WATCHING);
        cv.put("last_note", "已忽略该信号（" + (cur.pendingReason != null ? cur.pendingReason : "") + "），继续观察");
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
        clearPendingFields(code);
        Log.i(TAG, "忽略信号: " + code);
    }

    private void clearPendingFields(String code) {
        ContentValues cv = new ContentValues();
        cv.putNull("pending_action");
        cv.putNull("pending_price");
        cv.putNull("pending_ai_confirmed");
        cv.putNull("pending_reason");
        cv.putNull("pending_ai_full");
        cv.putNull("pending_at");
        cv.putNull("prev_status");
        cv.put("ai_status", "NONE");
        mDb.update("watchlist", cv, "code=?", new String[]{code});
    }

    /**
     * 候选股（观察中，尚未买入）形态失效时自动移出观察池。和手动移除用同一个终止状态，
     * 但note里清楚写明自动移除的具体原因，不是静默消失，方便回头复盘“当初为什么选中、
     * 后来为什么被移除”。
     */
    public void autoRemoveStale(String code, String reason) {
        ContentValues cv = new ContentValues();
        cv.put("status", STATUS_REMOVED);
        cv.put("last_note", "【自动移出观察池】" + reason);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
        Log.i(TAG, "自动移除候选股: " + code + " 原因=" + reason);
    }

    public void markStarter(String code, double price, String note) {
        ContentValues cv = new ContentValues();
        cv.put("status", STATUS_STARTER);
        cv.put("starter_price", price);
        cv.put("starter_date", mDateFmt.format(new Date()));
        cv.put("last_note", note);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
    }

    public void markAdded(String code, double price, String note) {
        ContentValues cv = new ContentValues();
        cv.put("status", STATUS_ADDED);
        cv.put("added_price", price);
        cv.put("added_date2", mDateFmt.format(new Date()));
        cv.put("last_note", note);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
    }

    public void markFull(String code, double price, String note) {
        ContentValues cv = new ContentValues();
        cv.put("status", STATUS_FULL);
        cv.put("full_price", price);
        cv.put("full_date", mDateFmt.format(new Date()));
        cv.put("last_note", note);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
    }

    public void markStopped(String code, String note) {
        ContentValues cv = new ContentValues();
        cv.put("status", STATUS_STOPPED);
        cv.put("last_note", note);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
    }

    public void updateNote(String code, String note) {
        ContentValues cv = new ContentValues();
        cv.put("last_note", note);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
    }

    public void removeManual(String code) {
        ContentValues cv = new ContentValues();
        cv.put("status", STATUS_REMOVED);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
    }

    public static class WatchlistItem {
        public String code, name, addedDate, status, signal, lastNote;
        public String starterDate, addedDate2, fullDate, divKDate;
        public int score;
        public double starterPrice, addedPrice, fullPrice;
        public double divKHigh, divKLow, divMidKline, divMidRetrace, prevYangLow, peakGainPct;
        public String peakGainDate;
        public double patternOpen, patternHigh, patternClose, patternLow;
        public String patternDate;
        public long updatedAt;
        public String prevStatus, pendingAction, pendingReason, pendingAiFull;
        public double pendingPrice;
        public boolean pendingAiConfirmed;
        public long pendingAt;
        /** NONE/PENDING/CONFIRMED/DOUBTED，见 markPending()/updatePendingAiResult() 注释 */
        public String aiStatus = "NONE";
    }

    private static final String[] ACTIVE_STATUSES = {
            STATUS_WATCHING, STATUS_STARTER, STATUS_ADDED, STATUS_FULL,
            STATUS_PENDING_STARTER, STATUS_PENDING_ADD, STATUS_PENDING_FULL,
            STATUS_PENDING_WARN, STATUS_PENDING_STOP
    };

    public List<WatchlistItem> getActiveWatchlist() {
        List<WatchlistItem> list = new ArrayList<>();
        String placeholders = "?,?,?,?,?,?,?,?,?";
        Cursor c = mDb.rawQuery(
                "SELECT * FROM watchlist WHERE status IN (" + placeholders + ") ORDER BY updated_at DESC",
                ACTIVE_STATUSES);
        try { while (c.moveToNext()) list.add(fromCursor(c)); } finally { c.close(); }
        return list;
    }

    public List<WatchlistItem> getPendingSignals() {
        List<WatchlistItem> list = new ArrayList<>();
        Cursor c = mDb.rawQuery(
                "SELECT * FROM watchlist WHERE status IN (?,?,?,?,?) ORDER BY pending_at DESC",
                new String[]{STATUS_PENDING_STARTER, STATUS_PENDING_ADD, STATUS_PENDING_FULL,
                        STATUS_PENDING_WARN, STATUS_PENDING_STOP});
        try { while (c.moveToNext()) list.add(fromCursor(c)); } finally { c.close(); }
        return list;
    }

    public List<WatchlistItem> getAll() {
        List<WatchlistItem> list = new ArrayList<>();
        Cursor c = mDb.rawQuery("SELECT * FROM watchlist ORDER BY updated_at DESC", null);
        try { while (c.moveToNext()) list.add(fromCursor(c)); } finally { c.close(); }
        return list;
    }

    public WatchlistItem getByCode(String code) {
        Cursor c = mDb.rawQuery("SELECT * FROM watchlist WHERE code=?", new String[]{code});
        try { return c.moveToFirst() ? fromCursor(c) : null; } finally { c.close(); }
    }

    private WatchlistItem fromCursor(Cursor c) {
        WatchlistItem it = new WatchlistItem();
        it.code = c.getString(c.getColumnIndexOrThrow("code"));
        it.name = c.getString(c.getColumnIndexOrThrow("name"));
        it.addedDate = c.getString(c.getColumnIndexOrThrow("added_date"));
        it.status = c.getString(c.getColumnIndexOrThrow("status"));
        it.score = c.getInt(c.getColumnIndexOrThrow("score"));
        it.signal = c.getString(c.getColumnIndexOrThrow("signal"));
        it.starterPrice = c.getDouble(c.getColumnIndexOrThrow("starter_price"));
        it.starterDate = c.getString(c.getColumnIndexOrThrow("starter_date"));
        it.addedPrice = c.getDouble(c.getColumnIndexOrThrow("added_price"));
        it.addedDate2 = c.getString(c.getColumnIndexOrThrow("added_date2"));
        it.lastNote = c.getString(c.getColumnIndexOrThrow("last_note"));
        it.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        it.prevStatus = c.getString(c.getColumnIndexOrThrow("prev_status"));
        it.pendingAction = c.getString(c.getColumnIndexOrThrow("pending_action"));
        it.pendingPrice = c.getDouble(c.getColumnIndexOrThrow("pending_price"));
        it.pendingAiConfirmed = c.getInt(c.getColumnIndexOrThrow("pending_ai_confirmed")) == 1;
        it.pendingReason = c.getString(c.getColumnIndexOrThrow("pending_reason"));
        it.pendingAt = c.getLong(c.getColumnIndexOrThrow("pending_at"));
        it.fullPrice = getDoubleOrZero(c, "full_price");
        it.fullDate = getStringOrNull(c, "full_date");
        it.pendingAiFull = getStringOrNull(c, "pending_ai_full");
        it.divKHigh = getDoubleOrZero(c, "div_k_high");
        it.divKLow = getDoubleOrZero(c, "div_k_low");
        it.divMidKline = getDoubleOrZero(c, "div_mid_kline");
        it.divMidRetrace = getDoubleOrZero(c, "div_mid_retrace");
        it.divKDate = getStringOrNull(c, "div_k_date");
        it.prevYangLow = getDoubleOrZero(c, "prev_yang_low");
        it.peakGainPct = getDoubleOrZero(c, "peak_gain_pct");
        it.peakGainDate = getStringOrNull(c, "peak_gain_date");
        it.patternOpen = getDoubleOrZero(c, "pattern_open");
        it.patternHigh = getDoubleOrZero(c, "pattern_high");
        it.patternClose = getDoubleOrZero(c, "pattern_close");
        it.patternLow = getDoubleOrZero(c, "pattern_low");
        it.patternDate = getStringOrNull(c, "pattern_date");
        String aiStatus = getStringOrNull(c, "ai_status");
        it.aiStatus = aiStatus != null ? aiStatus : "NONE";
        return it;
    }

    private static double getDoubleOrZero(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        return idx >= 0 ? c.getDouble(idx) : 0;
    }

    private static String getStringOrNull(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        return idx >= 0 ? c.getString(idx) : null;
    }

    public String getActiveWatchlistJson() {
        JSONArray arr = new JSONArray();
        for (WatchlistItem it : getActiveWatchlist()) arr.put(toJson(it));
        return arr.toString();
    }

    public String getAllJson() {
        JSONArray arr = new JSONArray();
        for (WatchlistItem it : getAll()) arr.put(toJson(it));
        return arr.toString();
    }

    private JSONObject toJson(WatchlistItem it) {
        JSONObject o = new JSONObject();
        try {
            o.put("code", it.code);
            o.put("name", it.name);
            o.put("addedDate", it.addedDate);
            o.put("status", it.status);
            o.put("score", it.score);
            o.put("signal", it.signal);
            o.put("starterPrice", it.starterPrice);
            o.put("starterDate", it.starterDate);
            o.put("addedPrice", it.addedPrice);
            o.put("addedDate2", it.addedDate2);
            o.put("fullPrice", it.fullPrice);
            o.put("fullDate", it.fullDate);
            o.put("lastNote", it.lastNote);
            o.put("updatedAt", it.updatedAt);
            o.put("pendingAction", it.pendingAction);
            o.put("pendingPrice", it.pendingPrice);
            o.put("pendingAiConfirmed", it.pendingAiConfirmed);
            o.put("pendingReason", it.pendingReason);
            o.put("pendingAtText", it.pendingAt > 0 ? mTimeFmt.format(new Date(it.pendingAt)) : "");
            o.put("aiStatus", it.aiStatus);
            o.put("divMidKline", it.divMidKline);
            o.put("divKLow", it.divKLow);
            o.put("divMidRetrace", it.divMidRetrace);
            o.put("prevYangLow", it.prevYangLow);
            o.put("peakGainPct", it.peakGainPct);
            o.put("patternHigh", it.patternHigh);
            o.put("patternDate", it.patternDate);
            double[] live = sLiveMetricsCache.get(it.code);
            if (live != null) {
                o.put("waterLine", live[0]);
                o.put("vwap", live[1]);
                o.put("volRatio", live[2]);
            }
        } catch (Exception ignored) {}
        return o;
    }
}
