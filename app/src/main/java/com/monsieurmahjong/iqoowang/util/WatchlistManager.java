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
 * 第一天选股公式筛选出的股票（哪怕当天0支，规则也要照常跑），存进这里持续跟踪，
 * 直到规则引擎判断满足买入条件、或者用户手动移除。
 *
 * 状态机：
 *   WATCHING（观察中，day1入池）
 *     → 满足买入规则(站稳分时低点+低开) → STARTER（已建底仓）
 *   STARTER
 *     → 突破前一交易日收盘价 → ADDED（已加仓）
 *     → 跌破前一交易日最低价 → STOPPED（止损离场）
 *   ADDED
 *     → 跌破前一交易日最低价 → STOPPED（止损离场）
 *
 * 独立 SQLite 数据库，不使用现有的 GreenDAO(stockmaster.db)，避免任何 schema
 * 变更触发 DevOpenHelper 的整库重建、清空真实交易记录。
 */
public class WatchlistManager {

    private static final String TAG = "WatchlistManager";
    private static final String DB_NAME = "watchlist.db";
    private static final int DB_VERSION = 1;

    public static final String STATUS_WATCHING = "WATCHING";
    public static final String STATUS_STARTER = "STARTER";
    public static final String STATUS_ADDED = "ADDED";
    public static final String STATUS_STOPPED = "STOPPED";
    public static final String STATUS_REMOVED = "MANUAL_REMOVED";

    private static WatchlistManager sInstance;
    private final SQLiteDatabase mDb;
    private final SimpleDateFormat mDateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

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
                    "status TEXT," +               // WATCHING/STARTER/ADDED/STOPPED/MANUAL_REMOVED
                    "score INTEGER," +             // day1 规则引擎评分
                    "signal TEXT," +                // day1 信号类型
                    "starter_price REAL," +         // 建底仓成交参考价
                    "starter_date TEXT," +          // 建底仓日期
                    "added_price REAL," +           // 加仓参考价
                    "added_date2 TEXT," +           // 加仓日期
                    "last_note TEXT," +              // 规则引擎最近一次判断说明
                    "updated_at INTEGER)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int o, int n) {
            db.execSQL("DROP TABLE IF EXISTS watchlist");
            onCreate(db);
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
    }

    /** 仍需要规则引擎持续跟踪的（观察中/已建底仓/已加仓），止损和手动移除的排除在外 */
    public List<WatchlistItem> getActiveWatchlist() {
        List<WatchlistItem> list = new ArrayList<>();
        Cursor c = mDb.rawQuery(
                "SELECT * FROM watchlist WHERE status IN (?,?,?) ORDER BY updated_at DESC",
                new String[]{STATUS_WATCHING, STATUS_STARTER, STATUS_ADDED});
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
        } catch (Exception ignored) {}
        return o;
    }
}
