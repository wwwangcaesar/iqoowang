package com.monsieurmahjong.iqoowang.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FenshiHistoryManager —— 分时图多日历史缓存
 *
 * 背景【新增，对应用户要求"对分时图进行数据天数的缓存处理...比如查看20号这只股票的分时图信息，
 * 通过切换日期进行加载对应日期的分时图数据内容"】：RealtimeQuoteManager里已有的分时缓存
 * （内存sMinuteCache + 2026-09-19新增的SharedPreferences磁盘持久化）都明确只保留"今天"一天——
 * 每次成功拉取就整份覆盖，跨天视为失效，这是分时图"今天实时查看"这个场景该有的设计，但满足
 * 不了"回看某支股票N天前某一天的分时走势"这个不同的需求（两者对"过期"的定义正好相反：
 * 前者是"隔天=脏数据"，后者是"隔天=有价值的历史"）。这里单独开一张表持久化，不跟
 * RealtimeQuoteManager的今日缓存混在一起，按(code,date)为主键，每个交易日一行，全天分时点位
 * （时间-价格-均价-成交量-累计成交量）整份存成JSON文本。
 *
 * 写入时机：RealtimeMonitorService每轮tick成功拉到分时数据后顺手落一份（当前候选池/持仓
 * 覆盖到的范围，不含从未监控过的股票），当天多次tick会用最新一份（全天完整累计到目前
 * 为止的点位）反复覆盖同一行，收盘后最后一次tick落的那份就是这一天的完整分时图。
 *
 * 清理时机：一支股票被卖到清仓（Position归零）时，由StockBridge.recordTrade()调用
 * deleteHistoryForCode()删掉这支股票全部历史——用户明确要求"清仓卖出后删除对应分时图数据"，
 * 不做成"过多少天自动清理"，因为持仓中的股票分时历史对应的是这笔交易的完整过程记录，
 * 只要还持有就有回看价值，卖完清仓这份价值才真正结束。
 *
 * 【已知未覆盖场景，如实记录】只在"这支股票发生过卖出且卖完后仓位归零"这条件下触发删除——
 * 一支从未真正买入、只是候选池观察过又被移出/自动清理的股票，它的分时历史目前没有对应的
 * 删除触发点，会一直留着累积。用户描述的场景明确是"清仓卖出"（先持仓后卖光），这里先只
 * 实现这一条，纯观察股不断累积历史数据的问题留作后续按需处理，不在本次擅自扩大清理范围。
 */
public class FenshiHistoryManager {

    private static final String TAG = "FenshiHistoryManager";
    private static final String DB_NAME = "fenshi_history.db";
    private static final int DB_VERSION = 1;

    private static FenshiHistoryManager sInstance;
    private final SQLiteDatabase mDb;

    public static void init(Context context) {
        if (sInstance == null) {
            synchronized (FenshiHistoryManager.class) {
                if (sInstance == null) sInstance = new FenshiHistoryManager(context.getApplicationContext());
            }
        }
    }

    public static FenshiHistoryManager get() {
        if (sInstance == null) {
            throw new IllegalStateException("FenshiHistoryManager未初始化，请先调用init()（StockMasterApp.onCreate()）");
        }
        return sInstance;
    }

    private FenshiHistoryManager(Context context) {
        mDb = new Helper(context).getWritableDatabase();
    }

    private static class Helper extends SQLiteOpenHelper {
        Helper(Context context) { super(context, DB_NAME, null, DB_VERSION); }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS fenshi_history (" +
                    "code TEXT NOT NULL," +
                    "date TEXT NOT NULL," +
                    "name TEXT," +
                    "prev_close REAL," +
                    "points_json TEXT," +
                    "updated_at INTEGER," +
                    "PRIMARY KEY (code, date))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // 目前只有版本1，暂无需处理
        }
    }

    private static String todayStr() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new java.util.Date());
    }

    /**
     * 保存"今天"这一天的完整分时快照——固定存今天的日期，不需要调用方传日期；同一天内
     * 反复调用会互相覆盖（INSERT OR REPLACE，主键code+date），保留最新一份（也就是全天走到
     * 当前时刻的完整累计点位）。points为空时静默跳过，不写入无意义的空行——这跟"分时数据
     * 本轮获取失败"是同一个信号，调用方（RealtimeMonitorService）本身在获取失败时points就是
     * null/空，不需要这里额外判断失败原因。
     */
    public void saveDaySnapshot(String code, String name, double prevClose, List<RealtimeQuoteManager.MinutePoint> points) {
        if (points == null || points.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            for (RealtimeQuoteManager.MinutePoint p : points) {
                JSONObject po = new JSONObject();
                po.put("time", p.time);
                po.put("price", p.price);
                po.put("avgPrice", p.avgPrice);
                po.put("volume", p.volume);
                po.put("cumVolume", p.cumVolume);
                arr.put(po);
            }
            ContentValues cv = new ContentValues();
            cv.put("code", code);
            cv.put("date", todayStr());
            cv.put("name", name);
            cv.put("prev_close", prevClose);
            cv.put("points_json", arr.toString());
            cv.put("updated_at", System.currentTimeMillis());
            mDb.insertWithOnConflict("fenshi_history", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            Log.e(TAG, "saveDaySnapshot失败 " + code, e);
        }
    }

    /** 某支股票有历史分时数据的日期列表，新→旧排序，供前端日期切换控件展示可选范围——
     *  只应该让用户选这里返回的日期，不是任意日期都点得动。 */
    public List<String> getDatesForCode(String code) {
        List<String> dates = new ArrayList<>();
        try (Cursor c = mDb.rawQuery(
                "SELECT date FROM fenshi_history WHERE code=? ORDER BY date DESC", new String[]{code})) {
            while (c.moveToNext()) dates.add(c.getString(0));
        } catch (Exception e) {
            Log.e(TAG, "getDatesForCode失败 " + code, e);
        }
        return dates;
    }

    /**
     * 某支股票某一天的完整分时数据，拼成跟StockBridge.getMinuteChartData()同一个JSON形状
     * （code/name/prevClose/points），前端drawFenshiChart()可以原样复用，不用额外区分
     * "今天实时"还是"历史某天"两套解析/绘图逻辑。没有这一天的记录时返回null，调用方自行
     * 处理"这天没有数据"的展示（比如这天停牌、还没轮到这支股票开始被监控）。
     */
    public String getDaySnapshotJson(String code, String date) {
        try (Cursor c = mDb.rawQuery(
                "SELECT name, prev_close, points_json, updated_at FROM fenshi_history WHERE code=? AND date=?",
                new String[]{code, date})) {
            if (!c.moveToFirst()) return null;
            String name = c.getString(0);
            double prevClose = c.getDouble(1);
            String pointsJson = c.getString(2);
            long updatedAt = c.getLong(3);
            JSONObject o = new JSONObject();
            o.put("code", code);
            o.put("date", date);
            o.put("name", name);
            o.put("prevClose", prevClose);
            o.put("updatedAt", updatedAt);
            o.put("points", pointsJson != null ? new JSONArray(pointsJson) : new JSONArray());
            return o.toString();
        } catch (Exception e) {
            Log.e(TAG, "getDaySnapshotJson失败 " + code + " " + date, e);
            return null;
        }
    }

    /** 【用户明确要求"当这只股票被清仓卖出，再去删除对应的分时图数据内容"】删除这支股票
     *  全部的分时历史记录。 */
    public void deleteHistoryForCode(String code) {
        try {
            int n = mDb.delete("fenshi_history", "code=?", new String[]{code});
            Log.i(TAG, "清仓触发分时历史清理：" + code + "，删除" + n + "天记录");
        } catch (Exception e) {
            Log.e(TAG, "deleteHistoryForCode失败 " + code, e);
        }
    }
}
