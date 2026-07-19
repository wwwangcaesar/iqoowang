package com.monsieurmahjong.iqoowang.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.monsieurmahjong.iqoowang.http.EastMoneyApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * MarketIndexManager — 大盘指数管理器
 *
 * 独立缓存 上证指数 / 深证成指 / 创业板指 的日K线，
 * 为 AI 分析提供"大盘状态"这一层宏观依据 —— 避免 AI 仅凭个股数据
 * 就给出脱离大盘环境的选股结论（例如大盘系统性下跌时不该无脑追多）。
 *
 * 用法：
 *   MarketIndexManager.init(context);
 *   MarketIndexManager.get().ensureFreshBlocking(8000); // 在后台线程调用，若数据过期则联网刷新
 *   String summary = MarketIndexManager.get().getMarketSummaryText();
 */
public class MarketIndexManager {

    private static final String TAG = "MarketIndexManager";
    private static final String DB_NAME = "market_index.db";
    private static final int DB_VERSION = 1;
    private static final int KLINE_DAYS = 30;

    /** 三大跟踪指数：{市场前缀, 代码, 中文名} */
    private static final String[][] INDICES = {
            {"sh", "000001", "上证指数"},
            {"sz", "399001", "深证成指"},
            {"sz", "399006", "创业板指"},
    };

    private static MarketIndexManager sInstance;
    private final SQLiteDatabase mDb;
    private final SimpleDateFormat mDateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    public static void init(Context context) {
        if (sInstance == null) {
            synchronized (MarketIndexManager.class) {
                if (sInstance == null) sInstance = new MarketIndexManager(context.getApplicationContext());
            }
        }
    }

    public static MarketIndexManager get() {
        if (sInstance == null) throw new IllegalStateException("call init() first");
        return sInstance;
    }

    private MarketIndexManager(Context context) {
        mDb = new DbHelper(context).getWritableDatabase();
        Log.i(TAG, "MarketIndexManager initialized");
    }

    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context ctx) { super(ctx, DB_NAME, null, DB_VERSION); }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS index_kline (" +
                    "index_code TEXT NOT NULL," +
                    "index_name TEXT," +
                    "trade_date TEXT NOT NULL," +
                    "open REAL, close REAL, high REAL, low REAL," +
                    "volume INTEGER, change_pct REAL," +
                    "UNIQUE(index_code, trade_date) ON CONFLICT REPLACE)");
            db.execSQL("CREATE TABLE IF NOT EXISTS index_meta (" +
                    "key TEXT PRIMARY KEY, value TEXT)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int o, int n) {
            db.execSQL("DROP TABLE IF EXISTS index_kline");
            db.execSQL("DROP TABLE IF EXISTS index_meta");
            onCreate(db);
        }
    }

    // ══════════════════════════════════════════
    // 数据新鲜度检查 & 下载
    // ══════════════════════════════════════════

    private String lastUpdateDate() {
        Cursor c = mDb.rawQuery("SELECT value FROM index_meta WHERE key='last_update'", null);
        try {
            if (c.moveToFirst()) return c.getString(0);
        } finally { c.close(); }
        return null;
    }

    private void setLastUpdateDate(String date) {
        ContentValues cv = new ContentValues();
        cv.put("key", "last_update");
        cv.put("value", date);
        mDb.insertWithOnConflict("index_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public boolean isStale() {
        String today = mDateFmt.format(new Date());
        return !today.equals(lastUpdateDate());
    }

    /**
     * 若数据过期（非今日更新过），联网刷新三大指数K线。
     * 【重要】只能在后台线程调用，内部用 CountDownLatch 阻塞等待网络回调完成，
     * 不会阻塞主线程（调用方必须保证自己不在主线程）。
     *
     * @param timeoutMs 最长等待时间，超时则放弃刷新、直接使用已有缓存（哪怕是旧的）
     */
    public void ensureFreshBlocking(long timeoutMs) {
        if (!isStale()) return;

        CountDownLatch latch = new CountDownLatch(INDICES.length);
        for (String[] idx : INDICES) {
            EastMoneyApi.get().fetchIndexKline(idx[0], idx[1], "日K", KLINE_DAYS,
                    new EastMoneyApi.KlineCallback() {
                        @Override
                        public void onSuccess(List<EastMoneyApi.KlineBar> bars, String name) {
                            try { saveIndexBars(idx[1], idx[2], bars); }
                            catch (Exception e) { Log.w(TAG, "save index fail " + idx[1], e); }
                            latch.countDown();
                        }
                        @Override
                        public void onError(String msg) {
                            Log.w(TAG, "指数下载失败 " + idx[2] + ": " + msg);
                            latch.countDown();
                        }
                    });
        }
        try {
            boolean done = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            Log.i(TAG, "指数刷新" + (done ? "完成" : "超时(使用现有缓存)"));
        } catch (InterruptedException ignored) {}

        // 【修复】之前只要表里有任意历史数据就标记为"今天已更新"，如果三大指数当天全部下载失败但之前有旧数据，
        // 会被错误标记为新鲜，之后一整天都不会再重试。现在改为真正核对缓存里最新一条的日期，
        // 只有真的拿到了新数据才算"今天已更新"。
        String latest = getLatestIndexTradeDate();
        if (latest != null && latest.equals(mDateFmt.format(new Date()))) {
            setLastUpdateDate(mDateFmt.format(new Date()));
        } else {
            Log.w(TAG, "指数数据仍未更新到今天（最新：" + latest + "），下次调用会再次尝试重新下载");
        }
    }

    private String getLatestIndexTradeDate() {
        Cursor c = mDb.rawQuery("SELECT MAX(trade_date) FROM index_kline", null);
        try { if (c.moveToFirst() && !c.isNull(0)) return c.getString(0); } finally { c.close(); }
        return null;
    }

    private void saveIndexBars(String code, String name, List<EastMoneyApi.KlineBar> bars) {
        mDb.beginTransaction();
        try {
            for (EastMoneyApi.KlineBar bar : bars) {
                ContentValues cv = new ContentValues();
                cv.put("index_code", code);
                cv.put("index_name", name);
                cv.put("trade_date", bar.date);
                cv.put("open", bar.open);
                cv.put("close", bar.close);
                cv.put("high", bar.high);
                cv.put("low", bar.low);
                cv.put("volume", bar.volume);
                cv.put("change_pct", bar.changePct);
                mDb.insertWithOnConflict("index_kline", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            mDb.setTransactionSuccessful();
        } finally { mDb.endTransaction(); }
    }

    // ══════════════════════════════════════════
    // 大盘状态摘要（供 AI Prompt 使用）
    // ══════════════════════════════════════════

    private static class IndexState {
        String name, code;
        double lastClose, changePct, ma5, ma20;
        boolean aboveMa5, aboveMa20;
        int dataPoints;
    }

    private IndexState loadIndexState(String code, String name) {
        Cursor c = mDb.rawQuery(
                "SELECT close, change_pct FROM index_kline WHERE index_code=? ORDER BY trade_date DESC LIMIT 20",
                new String[]{code});
        List<Double> closes = new ArrayList<>();
        double lastChangePct = 0;
        try {
            boolean first = true;
            while (c.moveToNext()) {
                closes.add(c.getDouble(0));
                if (first) { lastChangePct = c.getDouble(1); first = false; }
            }
        } finally { c.close(); }

        IndexState st = new IndexState();
        st.code = code;
        st.name = name;
        st.dataPoints = closes.size();
        if (closes.isEmpty()) return st;

        st.lastClose = closes.get(0);
        st.changePct = lastChangePct;
        st.ma5 = avg(closes, 5);
        st.ma20 = avg(closes, 20);
        st.aboveMa5 = st.ma5 > 0 && st.lastClose >= st.ma5;
        st.aboveMa20 = st.ma20 > 0 && st.lastClose >= st.ma20;
        return st;
    }

    private double avg(List<Double> closes, int n) {
        int len = Math.min(n, closes.size());
        if (len == 0) return 0;
        double sum = 0;
        for (int i = 0; i < len; i++) sum += closes.get(i);
        return sum / len;
    }

    /**
     * 生成大盘状态的自然语言摘要 + 机读趋势标记，供 LocalAIAgent 拼接进 Prompt。
     * 例：
     *   "上证指数3512.34（+0.82%）站上5/20日均线，短期偏强；
     *    深证成指10234.5（-0.35%）跌破5日线；创业板指...
     *    综合：大盘中性偏多，可适度参与强势股。"
     */
    public String getMarketSummaryText() {
        List<IndexState> states = new ArrayList<>();
        for (String[] idx : INDICES) states.add(loadIndexState(idx[1], idx[2]));

        boolean anyData = false;
        int bullish = 0, bearish = 0;
        StringBuilder sb = new StringBuilder();
        for (IndexState st : states) {
            if (st.dataPoints == 0) continue;
            anyData = true;
            String trend = st.aboveMa5 && st.aboveMa20 ? "站上5/20日均线，偏强"
                    : (!st.aboveMa5 && !st.aboveMa20 ? "跌破5/20日均线，偏弱"
                    : (st.aboveMa5 ? "站上5日线但仍在20日线下方，弱势反弹" : "跌破5日线但仍在20日线上方，强势回调"));
            if (st.aboveMa5 && st.aboveMa20) bullish++;
            else if (!st.aboveMa5 && !st.aboveMa20) bearish++;

            sb.append(String.format(Locale.CHINA, "%s %.2f（%s%.2f%%）%s；",
                    st.name, st.lastClose, st.changePct >= 0 ? "+" : "", st.changePct, trend));
        }

        if (!anyData) {
            return "大盘指数数据暂缺（尚未下载或下载失败），本次分析仅基于个股数据，缺乏大盘环境参考，请谨慎评估系统性风险。";
        }

        String verdict;
        if (bullish >= 2) verdict = "大盘整体偏多，做多氛围较浓，可适度提高强势股参与度。";
        else if (bearish >= 2) verdict = "大盘整体偏弱，系统性风险较高，建议降低仓位、优先规避追高。";
        else verdict = "大盘涨跌不一，处于震荡阶段，宜精选个股、控制仓位。";

        return sb.append("综合研判：").append(verdict).toString();
    }

    /** 供前端/调试展示的结构化JSON版本 */
    public String getMarketSummaryJson() {
        JSONArray arr = new JSONArray();
        for (String[] idx : INDICES) {
            IndexState st = loadIndexState(idx[1], idx[2]);
            try {
                JSONObject obj = new JSONObject();
                obj.put("name", st.name);
                obj.put("code", st.code);
                obj.put("lastClose", st.lastClose);
                obj.put("changePct", st.changePct);
                obj.put("ma5", st.ma5);
                obj.put("ma20", st.ma20);
                obj.put("aboveMa5", st.aboveMa5);
                obj.put("aboveMa20", st.aboveMa20);
                obj.put("hasData", st.dataPoints > 0);
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }
}
