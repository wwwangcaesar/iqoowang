package com.monsieurmahjong.iqoowang.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;



import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 盘后行情数据管理器
 *
 * 独立 SQLite 数据库（不走 GreenDAO），缓存沪深主板日K线数据。
 * 模拟通达信"盘后数据下载"功能：
 *   1. 拉取全量股票列表（分页）
 *   2. 基础筛选（价格/市值/板块）
 *   3. 下载前复权日K线（35天）
 *   4. 用真实数据运行通达信选股公式
 */
public class MarketDataManager {

    private static final String TAG = "MarketData";
    private static final String DB_NAME = "market_data.db";
    private static final int DB_VERSION = 2;

    // K线下载天数（MA25 + SAR预热需要至少35天）
    private static final int KLINE_DAYS = 260;
    // 数据库保留旧数据天数 (自然日，365天即一年)
    private static final int KEEP_DAYS = 365;
    // 并发下载线程数
    private static final int DOWNLOAD_THREADS = 8;

    private static MarketDataManager sInstance;
    private final SQLiteDatabase mDb;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mPool = Executors.newFixedThreadPool(DOWNLOAD_THREADS);
    private final AtomicBoolean mDownloading = new AtomicBoolean(false);

    // 腾讯财经 K线接口（前复权 qfq）
    // 参数: 市场+代码, 周期(day/week/month), 条数
    private static final String URL_KLINE =
            "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=%s%s,day,,,%d,qfq";

    // 腾讯实时行情（批量查询）
    private static final String URL_QUOTE_BASE = "https://qt.gtimg.cn/q=";

    // 每批查询的股票代码数
    private static final int BATCH_QUOTE_SIZE = 80;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    // ── 回调接口 ──
    public interface DownloadCallback {
        void onProgress(int current, int total, String phase);
        void onComplete(int stockCount, int barCount);
        void onError(String msg);
    }

    public interface ScreenCallback {
        void onResult(String resultJson);
        void onError(String msg);
    }

    // ── 初始化 ──
    public static void init(Context context) {
        if (sInstance == null) {
            synchronized (MarketDataManager.class) {
                if (sInstance == null) {
                    sInstance = new MarketDataManager(context.getApplicationContext());
                }
            }
        }
    }

    public static MarketDataManager get() {
        if (sInstance == null) throw new IllegalStateException("call init() first");
        return sInstance;
    }

    private MarketDataManager(Context context) {
        DbHelper helper = new DbHelper(context);
        mDb = helper.getWritableDatabase();
        mDb.enableWriteAheadLogging();
        Log.i(TAG, "MarketDataManager initialized");
    }

    // ══════════════════════════════════════════
    // SQLite Helper
    // ══════════════════════════════════════════

    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context ctx) { super(ctx, DB_NAME, null, DB_VERSION); }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS kline_cache (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "stock_code TEXT NOT NULL," +
                    "stock_name TEXT," +
                    "market TEXT," +
                    "trade_date TEXT NOT NULL," +
                    "open REAL, close REAL, high REAL, low REAL," +
                    "volume INTEGER, amount REAL," +
                    "change_pct REAL, turn_rate REAL," +
                    "amplitude REAL, change_amt REAL," +
                    "UNIQUE(stock_code, trade_date) ON CONFLICT REPLACE)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kline_code ON kline_cache(stock_code)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kline_date ON kline_cache(trade_date)");

            db.execSQL("CREATE TABLE IF NOT EXISTS stock_list_cache (" +
                    "stock_code TEXT PRIMARY KEY," +
                    "stock_name TEXT," +
                    "market TEXT," +
                    "price REAL, open REAL, high REAL, low REAL, pre_close REAL," +
                    "change_pct REAL, volume INTEGER, amount REAL," +
                    "cap REAL, pe REAL," +
                    "update_time INTEGER)");

            db.execSQL("CREATE TABLE IF NOT EXISTS download_meta (" +
                    "key TEXT PRIMARY KEY," +
                    "value TEXT)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int o, int n) {
            db.execSQL("DROP TABLE IF EXISTS kline_cache");
            db.execSQL("DROP TABLE IF EXISTS stock_list_cache");
            db.execSQL("DROP TABLE IF EXISTS download_meta");
            onCreate(db);
        }
    }

    // ══════════════════════════════════════════
    // 数据大小估算
    // ══════════════════════════════════════════

    /**
     * 估算下载大小
     * @return JSON: {"sizeStr":"约3.2MB","stockCount":832,"barCount":29120}
     */
    public String estimateDownloadSize(double minPrice, double maxPrice,
                                       double minCap, double maxCap,
                                       boolean exCY, boolean exKC, boolean exST) {
        // 沪深主板共约3000支，经基础筛选后约40-60%通过
        // 保守估计：3000 * 0.5 = 1500 支，但有价格/市值限制通常剩 600-900支
        int estimatedStocks;
        if (minPrice >= 3 && maxPrice <= 50 && minCap >= 20 && maxCap <= 320) {
            estimatedStocks = 700; // 典型操盘手参数
        } else if (maxPrice <= 100 && maxCap <= 1000) {
            estimatedStocks = 1200;
        } else {
            estimatedStocks = 2000;
        }

        int bars = estimatedStocks * KLINE_DAYS;
        // 每条K线约120字节（网络传输），存储后约90字节
        double networkMB = (estimatedStocks * 3.0 + bars * 0.12) / 1024.0; // 列表 + K线
        double storageMB = bars * 0.09 / 1024.0;

        try {
            JSONObject obj = new JSONObject();
            obj.put("sizeStr", String.format(Locale.CHINA, "约%.1fMB", networkMB));
            obj.put("storageSizeStr", String.format(Locale.CHINA, "约%.1fMB", storageMB));
            obj.put("stockCount", estimatedStocks);
            obj.put("barCount", bars);
            obj.put("estimatedTime", String.format(Locale.CHINA, "约%d-%d秒",
                    estimatedStocks / 20, estimatedStocks / 8));
            return obj.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 缓存里所有股票中最新一条K线的交易日（yyyy-MM-dd），没数据返回null */
    public String getLatestTradeDate() {
        Cursor c = mDb.rawQuery("SELECT MAX(trade_date) FROM kline_cache", null);
        try {
            if (c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } finally { c.close(); }
        return null;
    }

    /**
     * 推算"预期的最近交易日"——粗略处理：周一到周五且已过盘后(15:00后)算当天，
     * 否则往前回溯到上一个工作日。不考虑法定节假日（节假日那天本来就不会有新数据，
     * 对时比较会先行判定为"陈旧"，前端提醒文案中要写清楚这只是提醒不是报错，避免误导）。
     */
    private String computeExpectedTradeDate() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        boolean afterClose = hour >= 15;
        if (!afterClose) cal.add(Calendar.DAY_OF_MONTH, -1); // 还没收盘，预期数据还停在前一天
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SUNDAY) cal.add(Calendar.DAY_OF_MONTH, -2);
        else if (dow == Calendar.SATURDAY) cal.add(Calendar.DAY_OF_MONTH, -1);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(cal.getTime());
    }

    // ══════════════════════════════════════════
    // 盘后数据下载
    // ══════════════════════════════════════════

    public boolean isDownloading() { return mDownloading.get(); }

    /**
     * 启动盘后数据下载（三阶段）
     */
    public void startDownload(double minPrice, double maxPrice,
                              double minCap, double maxCap,
                              boolean exCY, boolean exKC, boolean exST,
                              String boardFilter,
                              DownloadCallback cb) {
        if (mDownloading.getAndSet(true)) {
            mMain.post(() -> cb.onError("正在下载中，请勿重复操作"));
            return;
        }

        mPool.execute(() -> {
            try {
                // ── Phase 1: 拉取全量股票列表 ──
                mMain.post(() -> cb.onProgress(0, 100, "Phase 1/3 · 拉取沪深主板股票列表..."));

                List<StockSnapshot> allStocks = new ArrayList<>();
                if (!"sz".equals(boardFilter)) {
                    List<StockSnapshot> shStocks = fetchAllStocksSync("sh");
                    allStocks.addAll(shStocks);
                    Log.i(TAG, "沪市主板: " + shStocks.size() + " 支");
                }
                if (!"sh".equals(boardFilter)) {
                    List<StockSnapshot> szStocks = fetchAllStocksSync("sz");
                    allStocks.addAll(szStocks);
                    Log.i(TAG, "深市主板: " + szStocks.size() + " 支");
                }

                mMain.post(() -> cb.onProgress(15, 100,
                        "Phase 1 完成 · 共获取 " + allStocks.size() + " 支股票"));

                // 保存股票列表到缓存
                saveStockListCache(allStocks);

                // ── Phase 2: 基础筛选 ──
                mMain.post(() -> cb.onProgress(20, 100, "Phase 2/3 · 基础条件预筛选..."));

                List<StockSnapshot> candidates = new ArrayList<>();
                for (StockSnapshot s : allStocks) {
                    if (s.price <= 0) continue;
                    if (s.price < minPrice || s.price > maxPrice) continue;
                    if (s.cap < minCap || s.cap > maxCap) continue;
                    if (exCY && (s.code.startsWith("30") || s.code.startsWith("300"))) continue;
                    if (exKC && (s.code.startsWith("68") || s.code.startsWith("688"))) continue;
                    if (exST && s.name != null && s.name.contains("ST")) continue;
                    candidates.add(s);
                }

                Log.i(TAG, "基础筛选后: " + candidates.size() + " 支候选股");
                mMain.post(() -> cb.onProgress(25, 100,
                        "Phase 2 完成 · " + candidates.size() + " 支通过基础筛选"));

                // ── Phase 3: 下载K线数据 ──
                final int total = candidates.size();
                final AtomicInteger completed = new AtomicInteger(0);
                final AtomicInteger barTotal = new AtomicInteger(0);
                final Object lock = new Object();

                // 使用线程池并发下载
                List<Thread> threads = new ArrayList<>();
                int batchSize = Math.max(1, total / DOWNLOAD_THREADS);

                for (int t = 0; t < DOWNLOAD_THREADS; t++) {
                    final int start = t * batchSize;
                    final int end = (t == DOWNLOAD_THREADS - 1) ? total : Math.min(start + batchSize, total);
                    if (start >= total) break;

                    Thread thread = new Thread(() -> {
                        for (int i = start; i < end; i++) {
                            if (!mDownloading.get()) return; // 取消检查
                            StockSnapshot stock = candidates.get(i);
                            try {
                                int bars = downloadKlineForStock(stock.code, stock.name, stock.market);
                                barTotal.addAndGet(bars);
                            } catch (Exception e) {
                                Log.w(TAG, "下载K线失败: " + stock.code + " " + e.getMessage());
                            }
                            int done = completed.incrementAndGet();
                            if (done % 10 == 0 || done == total) {
                                int pct = 25 + (int)(done * 70.0 / total);
                                mMain.post(() -> cb.onProgress(pct, 100,
                                        "Phase 3/3 · 下载K线 " + done + "/" + total));
                            }
                        }
                    });
                    threads.add(thread);
                    thread.start();
                }

                // 等待所有线程完成
                for (Thread thread : threads) {
                    thread.join();
                }

                // 清理旧数据
                cleanOldData(KEEP_DAYS);

                // 保存下载元信息
                saveMeta("last_download_time", String.valueOf(System.currentTimeMillis()));
                saveMeta("stock_count", String.valueOf(total));
                saveMeta("bar_count", String.valueOf(barTotal.get()));

                Log.i(TAG, "✅ 盘后数据下载完成: " + total + " 支, " + barTotal.get() + " 条K线");

                final int ft = total, fb = barTotal.get();
                mMain.post(() -> cb.onComplete(ft, fb));

            } catch (Exception e) {
                Log.e(TAG, "下载失败", e);
                mMain.post(() -> cb.onError(e.getMessage()));
            } finally {
                mDownloading.set(false);
            }
        });
    }

    /**
     * 取消下载
     */
    public void cancelDownload() {
        mDownloading.set(false);
    }

    // ══════════════════════════════════════════
    // 同步网络请求（腾讯财经API）
    // ══════════════════════════════════════════

    /**
     * 生成指定市场的全部可能股票代码范围
     * sh: 600000-605999（沪市主板）, 688000-689999（科创板）
     * sz: 000001-004999（深市主板）, 300000-301999（创业板）
     */
    private static List<String> generateCodes(String market) {
        List<String> codes = new ArrayList<>();
        if ("sh".equals(market)) {
            for (int i = 600000; i <= 605999; i++) codes.add(String.valueOf(i));
            for (int i = 688000; i <= 689999; i++) codes.add(String.valueOf(i));
        } else {
            for (int i = 1; i <= 4999; i++)
                codes.add(String.format(Locale.US, "%06d", i));
            for (int i = 300000; i <= 301999; i++) codes.add(String.valueOf(i));
        }
        return codes;
    }

    /**
     * 同步拉取某市场全部股票列表（通过腾讯批量行情枚举代码）
     */
    private List<StockSnapshot> fetchAllStocksSync(String market) {
        List<StockSnapshot> all = new ArrayList<>();
        String mkt = "sh".equals(market) ? "sh" : "sz";
        List<String> codes = generateCodes(market);

        for (int i = 0; i < codes.size(); i += BATCH_QUOTE_SIZE) {
            int end = Math.min(i + BATCH_QUOTE_SIZE, codes.size());
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < end; j++) {
                if (sb.length() > 0) sb.append(',');
                sb.append(mkt).append(codes.get(j));
            }

            try {
                String url = URL_QUOTE_BASE + sb;
                Request req = new Request.Builder().url(url).get().build();
                Response resp = HTTP.newCall(req).execute();
                if (!resp.isSuccessful()) {
                    resp.close();
                    continue;
                }

                String body = resp.body().string();
                resp.close();

                // 每行格式: v_szXXXXXX="字段1~字段2~..."; 空行=""
                String[] lines = body.split(";\\s*");
                for (String line : lines) {
                    StockSnapshot s = parseQuoteToSnapshot(line, market);
                    if (s != null && s.price > 0) {
                        all.add(s);
                    }
                }

                // 礼貌延迟，避免被封
                Thread.sleep(30);

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.w(TAG, "fetchAllStocksSync batch err: " + e.getMessage());
            }
        }
        Log.i(TAG, "fetchAllStocksSync(" + market + "): 获取 " + all.size() + " 支有效股票");
        return all;
    }

    /**
     * 解析 qt.gtimg.cn 单行行情为 StockSnapshot
     * 字段索引（以~分隔）:
     *  1=名称, 2=代码, 3=现价, 4=昨收, 5=开盘,
     *  6=成交量(手), 31=涨跌额, 32=涨跌幅(%),
     *  33=最高, 34=最低, 37=成交额(万),
     *  38=换手率(%), 39=市盈率,
     *  44=总市值(亿), 45=流通市值(亿)
     */
    private StockSnapshot parseQuoteToSnapshot(String line, String market) {
        if (line == null || line.isEmpty()) return null;
        int q1 = line.indexOf('"');
        int q2 = line.lastIndexOf('"');
        if (q1 < 0 || q2 <= q1) return null;
        String content = line.substring(q1 + 1, q2);
        if (content.isEmpty()) return null;

        String[] f = content.split("~", -1);
        if (f.length < 35) return null;

        StockSnapshot s = new StockSnapshot();
        s.code      = f[2];
        s.name      = f[1];
        s.market    = market;
        s.price     = parseDouble(f[3]);
        s.preClose  = parseDouble(f[4]);
        s.open      = parseDouble(f[5]);
        s.volume    = parseLong(f[6]);
        s.changePct = parseDouble(f[32]);
        s.high      = parseDouble(f[33]);
        s.low       = parseDouble(f[34]);
        if (f.length > 37) s.amount = parseDouble(f[37]); // 万
        if (f.length > 39) s.pe     = parseDouble(f[39]);
        if (f.length > 45) s.cap    = parseDouble(f[45]); // 流通市值(亿)
        else if (f.length > 44) s.cap = parseDouble(f[44]); // 总市值(亿)

        return s;
    }

    /**
     * 同步下载单只股票的K线数据并存入DB（腾讯财经API）
     * @return 实际写入的K线条数
     */
    private int downloadKlineForStock(String code, String name, String market) throws Exception {
        String mkt = code.startsWith("6") || code.startsWith("5") ? "sh" : "sz";
        String url = String.format(Locale.US, URL_KLINE, mkt, code, KLINE_DAYS + 5);

        Request req = new Request.Builder().url(url).get().build();
        Response resp = HTTP.newCall(req).execute();
        if (!resp.isSuccessful()) {
            resp.close();
            return 0;
        }

        String body = resp.body().string();
        resp.close();

        JSONObject root = new JSONObject(body);
        if (root.optInt("code", -1) != 0) return 0;

        JSONObject data = root.optJSONObject("data");
        if (data == null) return 0;

        String stockKey = mkt + code;
        JSONObject stockData = data.optJSONObject(stockKey);
        if (stockData == null) return 0;

        // 查找日K线数组
        JSONArray klines = stockData.optJSONArray("day");
        if (klines == null) klines = stockData.optJSONArray("qfqday");
        if (klines == null || klines.length() == 0) return 0;

        // 前收盘（用于计算首根K线涨跌幅）
        double preClose = parseDouble(stockData.optString("prec", "0"));

        // 批量插入
        mDb.beginTransaction();
        try {
            int count = 0;
            double prevClose = preClose;

            for (int i = 0; i < klines.length(); i++) {
                JSONArray row = klines.optJSONArray(i);
                if (row == null || row.length() < 6) continue;

                // 腾讯K线字段: [日期, 开盘, 收盘, 最高, 最低, 成交量]
                String tradeDate = row.optString(0, "");
                double open      = parseDouble(row.optString(1, "0"));
                double close     = parseDouble(row.optString(2, "0"));
                double high      = parseDouble(row.optString(3, "0"));
                double low       = parseDouble(row.optString(4, "0"));
                long volume      = (long) parseDouble(row.optString(5, "0"));

                // 计算衍生指标
                double changePct = 0, changeAmt = 0, amplitude = 0;
                if (prevClose > 0 && close > 0) {
                    changePct = (close - prevClose) / prevClose * 100;
                    changeAmt = close - prevClose;
                    amplitude = (high - low) / prevClose * 100;
                }

                ContentValues cv = new ContentValues();
                cv.put("stock_code", code);
                cv.put("stock_name", name);
                cv.put("market", market);
                cv.put("trade_date", tradeDate);
                cv.put("open", open);
                cv.put("close", close);
                cv.put("high", high);
                cv.put("low", low);
                cv.put("volume", volume);
                cv.put("amount", 0.0);
                cv.put("amplitude", amplitude);
                cv.put("change_pct", changePct);
                cv.put("change_amt", changeAmt);
                cv.put("turn_rate", 0.0);

                mDb.insertWithOnConflict("kline_cache", null, cv,
                        SQLiteDatabase.CONFLICT_REPLACE);

                if (close > 0) prevClose = close;
                count++;
            }
            mDb.setTransactionSuccessful();
            return count;
        } finally {
            mDb.endTransaction();
        }
    }

    // ══════════════════════════════════════════
    // 通达信选股公式（真实数据版）
    // ══════════════════════════════════════════

    /**
     * 使用缓存的真实K线数据运行通达信公式选股
     */
    public void runRealScreener(double minPrice, double maxPrice,
                                double minCap, double maxCap,
                                double volMulti,
                                boolean exCY, boolean exKC, boolean exST, boolean exLT,
                                String boardFilter,
                                ScreenCallback cb) {
        mPool.execute(() -> {
            try {
                // 获取所有有缓存K线的股票代码
                List<String> codes = getAllCachedCodes();
                Log.i(TAG, "缓存中共 " + codes.size() + " 支股票可供筛选");

                JSONArray results = new JSONArray();
                int scanned = 0;

                for (String code : codes) {
                    // 基础排除
                    if (exCY && (code.startsWith("30") || code.startsWith("300"))) continue;
                    if (exKC && (code.startsWith("68") || code.startsWith("688"))) continue;
                    if ("sh".equals(boardFilter) && !code.startsWith("6")) continue;
                    if ("sz".equals(boardFilter) && code.startsWith("6")) continue;

                    // 获取该股的K线数据
                    List<KlineBar> bars = getCachedKline(code, KLINE_DAYS);
                    if (bars.size() < 26) continue; // 至少需要26天数据

                    KlineBar latest = bars.get(bars.size() - 1);

                    // 价格范围
                    if (latest.close < minPrice || latest.close > maxPrice) continue;

                    // 市值（从stock_list_cache读取）
                    double cap = getStockCap(code);
                    if (cap < minCap || cap > maxCap) continue;

                    // ST排除
                    String name = getStockName(code);
                    if (exST && name != null && name.contains("ST")) continue;

                    // 涨幅排除
                    if (exLT && Math.abs(latest.changePct) >= 19.9) continue;

                    // ── 运行通达信公式 ──
                    JSONObject r = runTDXFormula(code, name, cap, bars, volMulti);
                    if (r != null) {
                        results.put(r);
                    }
                    scanned++;
                }

                Log.i(TAG, "扫描 " + scanned + " 支，通过 " + results.length() + " 支");

                // 按评分排序
                JSONArray sorted = sortByScore(results);

                final String json = sorted.toString();
                mMain.post(() -> cb.onResult(json));

            } catch (Exception e) {
                Log.e(TAG, "选股失败", e);
                mMain.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /**
     * 通达信公式核心逻辑（Java版，使用真实数据）
     */
    private JSONObject runTDXFormula(String code, String name, double cap,
                                     List<KlineBar> bars, double volMulti) {
        int n = bars.size();
        int i = n - 1; // 最新一天

        // 提取数组
        double[] closes = new double[n];
        double[] opens = new double[n];
        double[] highs = new double[n];
        double[] lows = new double[n];
        long[] vols = new long[n];

        for (int j = 0; j < n; j++) {
            closes[j] = bars.get(j).close;
            opens[j] = bars.get(j).open;
            highs[j] = bars.get(j).high;
            lows[j] = bars.get(j).low;
            vols[j] = bars.get(j).volume;
        }

        // N = EMA(CLOSE, 2)
        double[] N = ema(closes, 2);
        // N1 = EMA(EMA(...×11..., 2), 2)
        double[] N1 = deepEma11(closes);
        // N2 = MA(CLOSE, 25) + STD(CLOSE, 25)
        Double[] ma25 = ma(closes, 25);
        Double[] std25 = std(closes, 25);
        Double n2 = null;
        if (ma25[i] != null && std25[i] != null) {
            n2 = ma25[i] + std25[i];
        }
        // SAR(10, 2, 20)
        double[] sarVals = sar(highs, lows, 0.02, 0.2);
        double sarLatest = sarVals.length > 0 ? sarVals[sarVals.length - 1] : 0;

        // 条件A: 放量阳线突破
        boolean condA = (
                N[i] >= N1[i] &&
                n2 != null && N[i] >= n2 &&
                closes[i] > closes[i - 1] &&
                closes[i] > opens[i] &&
                vols[i] >= vols[i - 1] * volMulti &&
                (lows[i] + highs[i]) * 0.5 >= closes[i]
        );

        // 条件B: 缩量持续（至少需要i>=3）
        boolean condB = false;
        if (i >= 3) {
            condB = (
                    N[i] >= N1[i] &&
                    n2 != null && N[i] >= n2 &&
                    closes[i] > closes[i - 1] &&
                    vols[i] * 0.75 >= vols[i - 1] &&
                    closes[i - 2] > closes[i - 1] &&
                    vols[i - 2] * 0.75 >= vols[i - 1] &&
                    closes[i - 2] > closes[i - 3] &&
                    vols[i - 2] * 0.75 >= vols[i - 3]
            );
        }

        // 基础条件
        boolean baseCond = closes[i] >= sarLatest && closes[i] >= 3;

        if (!(condA || condB) || !baseCond) return null;

        // 综合评分
        int score = 60;
        if (condA) score += 25;
        if (condB) score += 15;
        if (N[i] > N1[i] * 1.005) score += 5;
        if (cap < 100) score += 5;
        score = Math.min(99, score);

        // 涨跌幅
        double changePct = closes[i - 1] > 0
                ? (closes[i] - closes[i - 1]) / closes[i - 1] * 100 : 0;
        // 量比
        double volRatio = vols[i - 2] > 0 ? (double) vols[i] / vols[i - 2] : 1.0;

        try {
            JSONObject obj = new JSONObject();
            obj.put("code", code);
            obj.put("name", name != null ? name : code);
            obj.put("latestClose", closes[i]);
            obj.put("change", changePct);
            obj.put("cap", cap);
            obj.put("vol", vols[i]);
            obj.put("volMultiActual", String.format(Locale.US, "%.1f", volRatio));
            obj.put("sarOk", true);
            obj.put("condA", condA);
            obj.put("condB", condB);
            obj.put("score", score);
            obj.put("signal", condA ? "放量突破" : "缩量持续");
            obj.put("ema_n", String.format(Locale.US, "%.3f", N[i]));
            obj.put("ema_n1", String.format(Locale.US, "%.3f", N1[i]));
            obj.put("market", code.startsWith("6") ? "sh" : "sz");
            obj.put("board", code.startsWith("6") ? "sh" : "sz");
            obj.put("price", closes[i]);
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    // ══════════════════════════════════════════
    // 通达信技术指标计算
    // ══════════════════════════════════════════

    private double[] ema(double[] arr, int period) {
        double k = 2.0 / (period + 1);
        double[] result = new double[arr.length];
        result[0] = arr[0];
        for (int i = 1; i < arr.length; i++) {
            result[i] = arr[i] * k + result[i - 1] * (1 - k);
        }
        return result;
    }

    private double[] deepEma11(double[] closes) {
        double[] arr = closes.clone();
        for (int layer = 0; layer < 11; layer++) {
            arr = ema(arr, 2);
        }
        return arr;
    }

    private Double[] ma(double[] arr, int period) {
        Double[] result = new Double[arr.length];
        for (int i = 0; i < arr.length; i++) {
            if (i < period - 1) { result[i] = null; continue; }
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) sum += arr[j];
            result[i] = sum / period;
        }
        return result;
    }

    private Double[] std(double[] arr, int period) {
        Double[] result = new Double[arr.length];
        Double[] maVals = ma(arr, period);
        for (int i = 0; i < arr.length; i++) {
            if (maVals[i] == null) { result[i] = null; continue; }
            double variance = 0;
            for (int j = i - period + 1; j <= i; j++) {
                variance += Math.pow(arr[j] - maVals[i], 2);
            }
            result[i] = Math.sqrt(variance / period);
        }
        return result;
    }

    private double[] sar(double[] highs, double[] lows, double accelStep, double accelMax) {
        if (highs.length < 2) return new double[0];
        double[] result = new double[highs.length - 1];
        boolean isBull = true;
        double ep = lows[0];
        double af = accelStep;
        double sarVal = highs[0];

        for (int i = 1; i < highs.length; i++) {
            double prevSar = sarVal;
            sarVal = prevSar + af * (ep - prevSar);

            if (isBull) {
                sarVal = Math.min(sarVal, lows[i - 1]);
                if (i > 1) sarVal = Math.min(sarVal, lows[i - 2]);
                if (lows[i] < sarVal) {
                    isBull = false; sarVal = ep; ep = lows[i]; af = accelStep;
                } else {
                    if (highs[i] > ep) { ep = highs[i]; af = Math.min(af + accelStep, accelMax); }
                }
            } else {
                sarVal = Math.max(sarVal, highs[i - 1]);
                if (i > 1) sarVal = Math.max(sarVal, highs[i - 2]);
                if (highs[i] > sarVal) {
                    isBull = true; sarVal = ep; ep = highs[i]; af = accelStep;
                } else {
                    if (lows[i] < ep) { ep = lows[i]; af = Math.min(af + accelStep, accelMax); }
                }
            }
            result[i - 1] = sarVal;
        }
        return result;
    }

    // ══════════════════════════════════════════
    // DB 查询方法
    // ══════════════════════════════════════════

    /** 获取全部有缓存的股票代码 */
    public List<String> getAllCachedCodes() {
        List<String> codes = new ArrayList<>();
        Cursor c = mDb.rawQuery("SELECT DISTINCT stock_code FROM kline_cache", null);
        while (c.moveToNext()) codes.add(c.getString(0));
        c.close();
        return codes;
    }

    /** 获取某只股票的缓存K线（按日期升序） */
    public List<KlineBar> getCachedKline(String code, int limit) {
        List<KlineBar> bars = new ArrayList<>();
        Cursor c = mDb.rawQuery(
                "SELECT trade_date, open, close, high, low, volume, amount, " +
                "change_pct, turn_rate, amplitude, change_amt " +
                "FROM kline_cache WHERE stock_code=? ORDER BY trade_date ASC LIMIT ?",
                new String[]{code, String.valueOf(limit)});
        while (c.moveToNext()) {
            KlineBar bar = new KlineBar();
            bar.date = c.getString(0);
            bar.open = c.getDouble(1);
            bar.close = c.getDouble(2);
            bar.high = c.getDouble(3);
            bar.low = c.getDouble(4);
            bar.volume = c.getLong(5);
            bar.amount = c.getDouble(6);
            bar.changePct = c.getDouble(7);
            bar.turnRate = c.getDouble(8);
            bar.amplitude = c.getDouble(9);
            bar.changeAmt = c.getDouble(10);
            bars.add(bar);
        }
        c.close();
        return bars;
    }

    /**
     * 市场宽度统计 — 基于本地已缓存的全市场个股K线（最新交易日一条）直接计算，
     * 不需要额外联网下载。用于向 AI 提供"今天到底是普涨还是普跌"这层大盘环境依据，
     * 避免只看筛选出的少数几支强势股就误判整体行情。
     *
     * @return JSON: {"tradeDate":"2026-07-09","total":2201,"up":1523,"down":612,
     *                "flat":66,"limitUp":18,"limitDown":3,"avgChangePct":0.62}
     */
    public String computeMarketBreadth() {
        JSONObject obj = new JSONObject();
        try {
            String latestDate;
            Cursor dc = mDb.rawQuery("SELECT MAX(trade_date) FROM kline_cache", null);
            try {
                if (!dc.moveToFirst() || dc.isNull(0)) {
                    obj.put("hasData", false);
                    return obj.toString();
                }
                latestDate = dc.getString(0);
            } finally { dc.close(); }

            int total = 0, up = 0, down = 0, flat = 0, limitUp = 0, limitDown = 0;
            double sumPct = 0;
            Cursor c = mDb.rawQuery(
                    "SELECT change_pct FROM kline_cache WHERE trade_date=?",
                    new String[]{latestDate});
            try {
                while (c.moveToNext()) {
                    double pct = c.getDouble(0);
                    total++;
                    sumPct += pct;
                    if (pct > 0.01) up++;
                    else if (pct < -0.01) down++;
                    else flat++;
                    if (pct >= 9.9) limitUp++;
                    if (pct <= -9.9) limitDown++;
                }
            } finally { c.close(); }

            obj.put("hasData", total > 0);
            obj.put("tradeDate", latestDate);
            obj.put("total", total);
            obj.put("up", up);
            obj.put("down", down);
            obj.put("flat", flat);
            obj.put("limitUp", limitUp);
            obj.put("limitDown", limitDown);
            obj.put("avgChangePct", total > 0 ? sumPct / total : 0);
        } catch (Exception e) {
            Log.w(TAG, "computeMarketBreadth failed", e);
        }
        return obj.toString();
    }

    /** 获取股票流通市值 */
    private double getStockCap(String code) {
        Cursor c = mDb.rawQuery("SELECT cap FROM stock_list_cache WHERE stock_code=?",
                new String[]{code});
        double cap = 0;
        if (c.moveToFirst()) cap = c.getDouble(0);
        c.close();
        return cap;
    }

    /** 获取股票名称 */
    private String getStockName(String code) {
        Cursor c = mDb.rawQuery("SELECT stock_name FROM stock_list_cache WHERE stock_code=?",
                new String[]{code});
        String name = null;
        if (c.moveToFirst()) name = c.getString(0);
        c.close();
        return name;
    }

    /** 清理N天前的旧数据 */
    public void cleanOldData(int keepDays) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -keepDays);
        String cutoff = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
        int deleted = mDb.delete("kline_cache", "trade_date < ?", new String[]{cutoff});
        Log.i(TAG, "清理旧数据: 删除 " + deleted + " 条 (< " + cutoff + ")");
    }

    /** 获取下载状态 */
    public String getDownloadStatus() {
        try {
            JSONObject obj = new JSONObject();
            String lastTime = getMeta("last_download_time");
            String stockCount = getMeta("stock_count");
            String barCount = getMeta("bar_count");

            if (lastTime != null) {
                long ts = Long.parseLong(lastTime);
                obj.put("lastUpdate", new SimpleDateFormat("yyyy-MM-dd HH:mm",
                        Locale.CHINA).format(new Date(ts)));
            } else {
                obj.put("lastUpdate", "从未下载");
            }
            obj.put("stockCount", stockCount != null ? Integer.parseInt(stockCount) : 0);
            obj.put("barCount", barCount != null ? Integer.parseInt(barCount) : 0);
            obj.put("downloading", mDownloading.get());

            // 数据陈旧检测：缓存里最新一条K线的交易日，是否就是预期的"最近一个交易日"，
            // 不是就说明用户还在拿好几天前的旧数据在跑选股/实时监控
            String latestTradeDate = getLatestTradeDate();
            String expectedTradeDate = computeExpectedTradeDate();
            obj.put("latestTradeDate", latestTradeDate != null ? latestTradeDate : "");
            obj.put("expectedTradeDate", expectedTradeDate);
            boolean stale = latestTradeDate == null || latestTradeDate.compareTo(expectedTradeDate) < 0;
            obj.put("isStale", stale);

            // 计算数据库文件大小
            Cursor c = mDb.rawQuery("SELECT COUNT(*) FROM kline_cache", null);
            int rows = 0;
            if (c.moveToFirst()) rows = c.getInt(0);
            c.close();
            obj.put("cacheRows", rows);
            obj.put("dataSizeMB", String.format(Locale.US, "%.1f", rows * 0.09 / 1024.0));

            return obj.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // ══════════════════════════════════════════
    // 辅助方法
    // ══════════════════════════════════════════

    private void saveStockListCache(List<StockSnapshot> stocks) {
        mDb.beginTransaction();
        try {
            mDb.delete("stock_list_cache", null, null);
            for (StockSnapshot s : stocks) {
                ContentValues cv = new ContentValues();
                cv.put("stock_code", s.code);
                cv.put("stock_name", s.name);
                cv.put("market", s.market);
                cv.put("price", s.price);
                cv.put("open", s.open);
                cv.put("high", s.high);
                cv.put("low", s.low);
                cv.put("pre_close", s.preClose);
                cv.put("change_pct", s.changePct);
                cv.put("volume", s.volume);
                cv.put("amount", s.amount);
                cv.put("cap", s.cap);
                cv.put("pe", s.pe);
                cv.put("update_time", System.currentTimeMillis());
                mDb.insertWithOnConflict("stock_list_cache", null, cv,
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            mDb.setTransactionSuccessful();
        } finally {
            mDb.endTransaction();
        }
    }

    private void saveMeta(String key, String value) {
        ContentValues cv = new ContentValues();
        cv.put("key", key);
        cv.put("value", value);
        mDb.insertWithOnConflict("download_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private String getMeta(String key) {
        Cursor c = mDb.rawQuery("SELECT value FROM download_meta WHERE key=?", new String[]{key});
        String val = null;
        if (c.moveToFirst()) val = c.getString(0);
        c.close();
        return val;
    }

    private JSONArray sortByScore(JSONArray arr) throws Exception {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) list.add(arr.getJSONObject(i));
        Collections.sort(list, (a, b) -> {
            try { return b.getInt("score") - a.getInt("score"); }
            catch (Exception e) { return 0; }
        });
        JSONArray sorted = new JSONArray();
        for (JSONObject o : list) sorted.put(o);
        return sorted;
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }
    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    // ── 数据模型 ──
    public static class StockSnapshot {
        public String code, name, market;
        public double price, open, high, low, preClose, changePct;
        public long volume;
        public double amount, cap, pe;
    }

    public static class KlineBar {
        public String date;
        public double open, close, high, low, amount;
        public long volume;
        public double changePct, changeAmt, turnRate, amplitude;
    }
}
