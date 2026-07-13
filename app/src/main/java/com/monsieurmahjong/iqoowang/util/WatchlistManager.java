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
 * 第一天选股公式筛选出的股票（哪怕当天0支，规则也要照常跑），存进这里持续跟踪。
 *
 * 状态机（人工确认版——规则引擎和AI都只是"建议"，最终动作必须用户点确认）：
 *   WATCHING（观察中，day1入池）
 *     → 规则引擎命中候选买入信号，AI二次验证后 → PENDING_STARTER（待确认买入，等你点头）
 *     → 用户确认 → STARTER（已建底仓）；用户忽略 → 打回 WATCHING 继续观察
 *   STARTER
 *     → 规则命中候选加仓，AI验证后 → PENDING_ADD（待确认加仓）→ 确认→ADDED / 忽略→打回STARTER
 *     → 规则命中候选止损，AI验证后 → PENDING_STOP（待确认止损）→ 确认→STOPPED / 忽略→打回STARTER
 *   ADDED
 *     → 规则命中候选止损，AI验证后 → PENDING_STOP → 确认→STOPPED / 忽略→打回ADDED
 *
 * 独立 SQLite 数据库，不使用现有的 GreenDAO(stockmaster.db)，避免任何 schema
 * 变更触发 DevOpenHelper 的整库重建、清空真实交易记录。
 */
public class WatchlistManager {

    private static final String TAG = "WatchlistManager";
    private static final String DB_NAME = "watchlist.db";
    private static final int DB_VERSION = 2;

    public static final String STATUS_WATCHING = "WATCHING";
    public static final String STATUS_STARTER = "STARTER";
    public static final String STATUS_ADDED = "ADDED";
    public static final String STATUS_STOPPED = "STOPPED";
    public static final String STATUS_REMOVED = "MANUAL_REMOVED";
    // 待人工确认状态——规则引擎+AI都只是"建议"，不会自动变成上面这些终态
    public static final String STATUS_PENDING_STARTER = "PENDING_STARTER";
    public static final String STATUS_PENDING_ADD = "PENDING_ADD";
    public static final String STATUS_PENDING_STOP = "PENDING_STOP";

    private static WatchlistManager sInstance;
    private final SQLiteDatabase mDb;
    private final SimpleDateFormat mDateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat mTimeFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

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
                    "added_date TEXT," +          // 入池日期（day1）
                    "status TEXT," +               // WATCHING/STARTER/ADDED/STOPPED/MANUAL_REMOVED/PENDING_*
                    "score INTEGER," +             // day1 规则引擎评分
                    "signal TEXT," +                // day1 信号类型
                    "starter_price REAL," +         // 建底仓成交参考价
                    "starter_date TEXT," +          // 建底仓日期
                    "added_price REAL," +           // 加仓参考价
                    "added_date2 TEXT," +           // 加仓日期
                    "last_note TEXT," +              // 规则引擎最近一次判断说明（观察态）
                    "prev_status TEXT," +            // 进入PENDING前的状态，用于"忽略"时打回原状态
                    "pending_action TEXT," +         // BUY_STARTER/ADD_POSITION/STOP_LOSS，确认后要执行的动作
                    "pending_price REAL," +          // 规则引擎检测到信号时的触发价
                    "pending_ai_confirmed INTEGER," + // AI是否认可这个信号：1=认可 0=AI存疑（仍展示给用户，但标注不同）
                    "pending_reason TEXT," +          // AI生成的小白能看懂的理由和建议
                    "pending_at INTEGER," +
                    "updated_at INTEGER)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int o, int n) {
            if (o < 2) {
                // v1→v2：新增待确认相关字段，用ALTER保留原有台账数据，不做整表重建
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN prev_status TEXT"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pending_action TEXT"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pending_price REAL"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pending_ai_confirmed INTEGER"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pending_reason TEXT"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE watchlist ADD COLUMN pending_at INTEGER"); } catch (Exception ignored) {}
            }
        }
    }

    // ══════════════════════════════════════════
    // 入池 / 状态变更
    // ══════════════════════════════════════════

    /** day1 选股公式筛选出的股票，加入候选池。已存在则跳过（不重复入池、不覆盖当前状态） */
    public void addIfAbsent(String code, String name, int score, String signal) {
        if (getByCode(code) != null) return;
        ContentValues cv = new ContentValues();
        cv.put("code", code);
        cv.put("name", name);
        cv.put("added_date", mDateFmt.format(new Date()));
        cv.put("status", STATUS_WATCHING);
        cv.put("score", score);
        cv.put("signal", signal);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.insertWithOnConflict("watchlist", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        Log.i(TAG, "入池: " + name + "(" + code + ") score=" + score + " signal=" + signal);
    }

    /**
     * 规则引擎命中候选信号、AI二次验证完成后调用——只是把信号放进"待确认"状态展示给用户，
     * 不会自动买卖、不会自动改变持仓，用户必须在App里手动点"确认"才会真正生效。
     *
     * @param pendingAction  BUY_STARTER / ADD_POSITION / STOP_LOSS（确认后要执行的动作）
     * @param price          规则引擎检测到时的触发价
     * @param aiConfirmed    AI是否也认为这个信号成立
     * @param aiReason       AI给出的小白能看懂的理由和建议
     */
    public void markPending(String code, String pendingAction, double price,
                             boolean aiConfirmed, String aiReason) {
        WatchlistItem cur = getByCode(code);
        if (cur == null) return;
        ContentValues cv = new ContentValues();
        cv.put("prev_status", cur.status); // 记住确认前的状态，"忽略"时要打回这里
        cv.put("status", pendingStatusFor(pendingAction));
        cv.put("pending_action", pendingAction);
        cv.put("pending_price", price);
        cv.put("pending_ai_confirmed", aiConfirmed ? 1 : 0);
        cv.put("pending_reason", aiReason);
        cv.put("pending_at", System.currentTimeMillis());
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
        Log.i(TAG, "待确认信号: " + code + " " + pendingAction + " AI认可=" + aiConfirmed + " " + aiReason);
    }

    private String pendingStatusFor(String action) {
        switch (action) {
            case "BUY_STARTER": return STATUS_PENDING_STARTER;
            case "ADD_POSITION": return STATUS_PENDING_ADD;
            case "STOP_LOSS": return STATUS_PENDING_STOP;
            default: return STATUS_WATCHING;
        }
    }

    /** 用户在App里点"确认"——真正把待确认信号落地成终态 */
    public void confirmPending(String code) {
        WatchlistItem cur = getByCode(code);
        if (cur == null || cur.pendingAction == null) return;
        switch (cur.pendingAction) {
            case "BUY_STARTER": markStarter(code, cur.pendingPrice, cur.pendingReason); break;
            case "ADD_POSITION": markAdded(code, cur.pendingPrice, cur.pendingReason); break;
            case "STOP_LOSS": markStopped(code, cur.pendingReason); break;
        }
        clearPendingFields(code);
    }

    /** 用户点"忽略"——打回确认前的状态，继续观察，不采取任何行动 */
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
        cv.putNull("pending_at");
        cv.putNull("prev_status");
        mDb.update("watchlist", cv, "code=?", new String[]{code});
    }

    public void markStarter(String code, double price, String note) {
        ContentValues cv = new ContentValues();
        cv.put("status", STATUS_STARTER);
        cv.put("starter_price", price);
        cv.put("starter_date", mDateFmt.format(new Date()));
        cv.put("last_note", note);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
        Log.i(TAG, "建底仓: " + code + " @" + price + " " + note);
    }

    public void markAdded(String code, double price, String note) {
        ContentValues cv = new ContentValues();
        cv.put("status", STATUS_ADDED);
        cv.put("added_price", price);
        cv.put("added_date2", mDateFmt.format(new Date()));
        cv.put("last_note", note);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
        Log.i(TAG, "加仓: " + code + " @" + price + " " + note);
    }

    public void markStopped(String code, String note) {
        ContentValues cv = new ContentValues();
        cv.put("status", STATUS_STOPPED);
        cv.put("last_note", note);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
        Log.i(TAG, "止损离场: " + code + " " + note);
    }

    public void updateNote(String code, String note) {
        ContentValues cv = new ContentValues();
        cv.put("last_note", note);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
    }

    /** 用户手动移除（不硬删，保留历史记录方便回顾） */
    public void removeManual(String code) {
        ContentValues cv = new ContentValues();
        cv.put("status", STATUS_REMOVED);
        cv.put("updated_at", System.currentTimeMillis());
        mDb.update("watchlist", cv, "code=?", new String[]{code});
    }

    // ══════════════════════════════════════════
    // 查询
    // ══════════════════════════════════════════

    public static class WatchlistItem {
        public String code, name, addedDate, status, signal, lastNote;
        public String starterDate, addedDate2;
        public int score;
        public double starterPrice, addedPrice;
        public long updatedAt;
        // 待确认相关
        public String prevStatus, pendingAction, pendingReason;
        public double pendingPrice;
        public boolean pendingAiConfirmed;
        public long pendingAt;
    }

    private static final String[] ACTIVE_STATUSES = {
            STATUS_WATCHING, STATUS_STARTER, STATUS_ADDED,
            STATUS_PENDING_STARTER, STATUS_PENDING_ADD, STATUS_PENDING_STOP
    };

    /** 仍需要跟踪的（观察中/已建底仓/已加仓/待确认），止损和手动移除的排除在外 */
    public List<WatchlistItem> getActiveWatchlist() {
        List<WatchlistItem> list = new ArrayList<>();
        String placeholders = "?,?,?,?,?,?";
        Cursor c = mDb.rawQuery(
                "SELECT * FROM watchlist WHERE status IN (" + placeholders + ") ORDER BY updated_at DESC",
                ACTIVE_STATUSES);
        try { while (c.moveToNext()) list.add(fromCursor(c)); } finally { c.close(); }
        return list;
    }

    /** 待用户确认的信号（三种PENDING状态） */
    public List<WatchlistItem> getPendingSignals() {
        List<WatchlistItem> list = new ArrayList<>();
        Cursor c = mDb.rawQuery(
                "SELECT * FROM watchlist WHERE status IN (?,?,?) ORDER BY pending_at DESC",
                new String[]{STATUS_PENDING_STARTER, STATUS_PENDING_ADD, STATUS_PENDING_STOP});
        try { while (c.moveToNext()) list.add(fromCursor(c)); } finally { c.close(); }
        return list;
    }

    /** 全部记录（含止损/移除的历史），供UI展示完整台账 */
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
        return it;
    }

    /** 供前端展示的JSON数组 */
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
            o.put("lastNote", it.lastNote);
            o.put("updatedAt", it.updatedAt);
            o.put("pendingAction", it.pendingAction);
            o.put("pendingPrice", it.pendingPrice);
            o.put("pendingAiConfirmed", it.pendingAiConfirmed);
            o.put("pendingReason", it.pendingReason);
            o.put("pendingAtText", it.pendingAt > 0 ? mTimeFmt.format(new Date(it.pendingAt)) : "");
        } catch (Exception ignored) {}
        return o;
    }
}
