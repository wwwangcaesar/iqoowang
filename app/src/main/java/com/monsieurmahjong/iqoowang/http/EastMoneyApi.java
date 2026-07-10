package com.monsieurmahjong.iqoowang.http;


import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 股票行情接口 — 腾讯财经API版
 *
 * 使用腾讯财经公开接口（无需Key），替代已被封锁的东方财富push2接口。
 * 数据源：web.ifzq.gtimg.cn（K线）+ qt.gtimg.cn（实时行情）
 * 全部异步，回调切回主线程。
 */
public class EastMoneyApi {

    private static final String TAG = "EastMoneyApi";

    // ── OkHttp 单例客户端 ──
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static EastMoneyApi sInstance;

    public static EastMoneyApi get() {
        if (sInstance == null) sInstance = new EastMoneyApi();
        return sInstance;
    }

    // ──────────────────────────────────────────
    // URL 模板（腾讯财经）
    // ──────────────────────────────────────────

    // 日/周/月K线（前复权 qfq）
    // 参数: 市场+代码, 周期(day/week/month), 条数
    private static final String URL_KLINE_DAY =
            "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=%s%s,%s,,,%d,qfq";

    // 分钟K线: m1/m5/m15/m30/m60
    private static final String URL_KLINE_MIN =
            "https://ifzq.gtimg.cn/appstock/app/kline/mkline?param=%s%s,%s,,%d";

    // 实时行情（批量，每次最多约80个代码）
    private static final String URL_QUOTE_BASE = "https://qt.gtimg.cn/q=";

    // 股票列表每页大小
    private static final int LIST_PAGE_SIZE = 80;

    // ──────────────────────────────────────────
    // 数据模型
    // ──────────────────────────────────────────

    public static class QuoteData {
        public String code, name, market;
        public double price, open, high, low, preClose, change, changePct, pe;
        public long volume;
        public double turnover;
        // 流通市值（亿）
        public double cap;
    }

    public static class KlineBar {
        public String date;
        public double open, close, high, low, amount;
        public long volume;
        public double changePct, changeAmt, turnRate, amplitude;
    }

    public interface QuoteCallback {
        void onSuccess(QuoteData data);
        void onError(String msg);
    }

    public interface KlineCallback {
        void onSuccess(List<KlineBar> bars, String name);
        void onError(String msg);
    }

    public interface BatchQuoteCallback {
        void onSuccess(Map<String, QuoteData> quotes);
        void onError(String msg);
    }

    public interface StockListCallback {
        void onSuccess(List<QuoteData> stocks);
        void onError(String msg);
    }

    // ──────────────────────────────────────────
    // K线数据（腾讯财经）
    // ──────────────────────────────────────────

    public void fetchKline(String code, String period, int limit, KlineCallback cb) {
        fetchKlineWithMarket(getTencentMarket(code), code, period, limit, cb);
    }

    /**
     * 指数/大盘K线 — 指数代码（如上证指数"000001"）不能用个股的市场判断规则
     * （"000001"按个股规则会被误判为sz的平安银行），必须显式指定市场前缀。
     *
     * 常用指数：
     *   上证指数 sh 000001    深证成指 sz 399001
     *   创业板指 sz 399006    沪深300  sh 000300
     */
    public void fetchIndexKline(String market, String indexCode, String period, int limit, KlineCallback cb) {
        fetchKlineWithMarket(market, indexCode, period, limit, cb);
    }

    private void fetchKlineWithMarket(String mkt, String code, String period, int limit, KlineCallback cb) {
        String tPeriod = toTencentPeriod(period);
        boolean isMinute = tPeriod.startsWith("m");

        String url = isMinute
                ? String.format(Locale.US, URL_KLINE_MIN, mkt, code, tPeriod, limit)
                : String.format(Locale.US, URL_KLINE_DAY, mkt, code, tPeriod, limit);

        enqueue(url, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "fetchKline fail: " + code, e);
                MAIN.post(() -> cb.onError(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response resp) throws IOException {
                try (Response r = resp) {
                    if (!r.isSuccessful()) {
                        MAIN.post(() -> cb.onError("HTTP " + r.code()));
                        return;
                    }
                    String body = r.body().string();
                    JSONObject root = new JSONObject(body);

                    int retCode = root.optInt("code", 0);
                    if (retCode != 0) {
                        String msg = root.optString("msg", "API error " + retCode);
                        MAIN.post(() -> cb.onError(msg));
                        return;
                    }

                    JSONObject data = root.optJSONObject("data");
                    if (data == null) {
                        MAIN.post(() -> cb.onError("No data"));
                        return;
                    }

                    String stockKey = mkt + code;
                    JSONObject stockData = data.optJSONObject(stockKey);
                    if (stockData == null) {
                        MAIN.post(() -> cb.onError("No data for " + code));
                        return;
                    }

                    // 从qt段获取股票名称
                    String name = code;
                    JSONObject qt = stockData.optJSONObject("qt");
                    if (qt != null) {
                        JSONArray qtArr = qt.optJSONArray(stockKey);
                        if (qtArr != null && qtArr.length() > 1) {
                            name = qtArr.optString(1, code);
                        }
                    }

                    // 前收盘价（用于计算首根K线涨跌幅）
                    double preClose = d(stockData.optString("prec", "0"));

                    // 查找K线数组：优先用请求的周期名，再尝试常见key
                    JSONArray klines = stockData.optJSONArray(tPeriod);
                    if (klines == null || klines.length() == 0) {
                        String[] fallbackKeys = {"day", "week", "month",
                                "qfqday", "qfqweek", "qfqmonth"};
                        for (String k : fallbackKeys) {
                            klines = stockData.optJSONArray(k);
                            if (klines != null && klines.length() > 0) break;
                        }
                    }

                    if (klines == null || klines.length() == 0) {
                        MAIN.post(() -> cb.onError("No kline data"));
                        return;
                    }

                    List<KlineBar> bars = parseTencentKlines(klines, preClose, isMinute);

                    final String n = name;
                    MAIN.post(() -> cb.onSuccess(bars, n));
                } catch (Exception e) {
                    Log.e(TAG, "fetchKline parse: " + code, e);
                    MAIN.post(() -> cb.onError(e.getMessage()));
                }
            }
        });
    }

    // ──────────────────────────────────────────
    // 单股行情快照（腾讯实时行情）
    // ──────────────────────────────────────────

    public void fetchQuote(String code, QuoteCallback cb) {
        String mkt = getTencentMarket(code);
        String url = URL_QUOTE_BASE + mkt + code;

        enqueue(url, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                MAIN.post(() -> cb.onError(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response resp) throws IOException {
                try (Response r = resp) {
                    String body = r.body().string();
                    QuoteData q = parseQuoteLine(body);
                    if (q != null) {
                        MAIN.post(() -> cb.onSuccess(q));
                    } else {
                        MAIN.post(() -> cb.onError("Quote parse failed for " + code));
                    }
                } catch (Exception e) {
                    MAIN.post(() -> cb.onError(e.getMessage()));
                }
            }
        });
    }

    // ──────────────────────────────────────────
    // 批量行情（持仓价格刷新）
    // ──────────────────────────────────────────

    public void fetchBatchQuotes(List<String> codes, BatchQuoteCallback cb) {
        if (codes == null || codes.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String c : codes) {
            if (sb.length() > 0) sb.append(',');
            sb.append(getTencentMarket(c)).append(c);
        }
        String url = URL_QUOTE_BASE + sb;

        enqueue(url, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                MAIN.post(() -> cb.onError(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response resp) throws IOException {
                try (Response r = resp) {
                    String body = r.body().string();
                    Map<String, QuoteData> result = new HashMap<>();
                    // 每行格式: v_szXXXXXX="字段1~字段2~...";
                    String[] lines = body.split(";\\s*");
                    for (String line : lines) {
                        QuoteData q = parseQuoteLine(line);
                        if (q != null && q.code != null && !q.code.isEmpty()) {
                            result.put(q.code, q);
                        }
                    }
                    MAIN.post(() -> cb.onSuccess(result));
                } catch (Exception e) {
                    MAIN.post(() -> cb.onError(e.getMessage()));
                }
            }
        });
    }

    // ──────────────────────────────────────────
    // 股票列表（选股扫描）
    // ──────────────────────────────────────────

    public void fetchStockList(String market, int page, StockListCallback cb) {
        List<String> allCodes = generateCodes(market);
        int start = page * LIST_PAGE_SIZE;
        if (start >= allCodes.size()) {
            MAIN.post(() -> cb.onSuccess(new ArrayList<>()));
            return;
        }
        int end = Math.min(start + LIST_PAGE_SIZE, allCodes.size());
        List<String> batch = allCodes.subList(start, end);

        String mkt = "sh".equals(market) ? "sh" : "sz";
        StringBuilder sb = new StringBuilder();
        for (String c : batch) {
            if (sb.length() > 0) sb.append(',');
            sb.append(mkt).append(c);
        }
        String url = URL_QUOTE_BASE + sb;

        enqueue(url, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "fetchStockList fail", e);
                MAIN.post(() -> cb.onError(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response resp) throws IOException {
                try (Response r = resp) {
                    String body = r.body().string();
                    List<QuoteData> stocks = new ArrayList<>();
                    String[] lines = body.split(";\\s*");
                    for (String line : lines) {
                        QuoteData q = parseQuoteLine(line);
                        if (q != null && q.price > 0) {
                            q.market = market;
                            stocks.add(q);
                        }
                    }
                    MAIN.post(() -> cb.onSuccess(stocks));
                } catch (Exception e) {
                    Log.e(TAG, "fetchStockList parse", e);
                    MAIN.post(() -> cb.onError(e.getMessage()));
                }
            }
        });
    }

    // ──────────────────────────────────────────
    // 解析工具
    // ──────────────────────────────────────────

    /**
     * 解析腾讯K线数组
     * 日/周/月格式: [日期, 开盘, 收盘, 最高, 最低, 成交量]
     * 分钟格式:     [时间戳, 开盘, 收盘, 最高, 最低, 成交量, {}, 换手率]
     */
    private List<KlineBar> parseTencentKlines(JSONArray klines, double preClose, boolean isMinute) {
        List<KlineBar> bars = new ArrayList<>();
        double prevClose = preClose;

        for (int i = 0; i < klines.length(); i++) {
            JSONArray row = klines.optJSONArray(i);
            if (row == null || row.length() < 6) continue;

            KlineBar bar = new KlineBar();
            String rawDate = row.optString(0, "");
            bar.date = isMinute ? formatMinuteDate(rawDate) : rawDate;
            bar.open = d(row.optString(1, "0"));
            bar.close = d(row.optString(2, "0"));
            bar.high = d(row.optString(3, "0"));
            bar.low = d(row.optString(4, "0"));
            bar.volume = (long) d(row.optString(5, "0"));
            bar.amount = 0;

            // 计算涨跌幅、涨跌额、振幅
            if (prevClose > 0 && bar.close > 0) {
                bar.changePct = (bar.close - prevClose) / prevClose * 100;
                bar.changeAmt = bar.close - prevClose;
                bar.amplitude = (bar.high - bar.low) / prevClose * 100;
            }

            // 分钟K线第8个字段是换手率
            if (isMinute && row.length() >= 8) {
                bar.turnRate = d(row.optString(7, "0"));
            }

            if (bar.close > 0) prevClose = bar.close;
            bars.add(bar);
        }
        return bars;
    }

    /**
     * 解析 qt.gtimg.cn 单行行情
     * 格式: v_sz000001="51~平安银行~000001~10.50~10.29~10.25~1061049~...";
     *
     * 字段索引（以~分隔）:
     *  1=名称, 2=代码, 3=现价, 4=昨收, 5=开盘,
     *  6=成交量(手), 31=涨跌额, 32=涨跌幅(%),
     *  33=最高, 34=最低, 37=成交额(万),
     *  38=换手率(%), 39=市盈率,
     *  44=总市值(亿), 45=流通市值(亿)
     */
    private QuoteData parseQuoteLine(String line) {
        if (line == null || line.isEmpty()) return null;
        int q1 = line.indexOf('"');
        int q2 = line.lastIndexOf('"');
        if (q1 < 0 || q2 <= q1) return null;
        String content = line.substring(q1 + 1, q2);
        if (content.isEmpty()) return null;

        String[] f = content.split("~", -1);
        if (f.length < 35) return null;

        QuoteData q = new QuoteData();
        q.code      = f[2];
        q.name      = f[1];
        q.market    = q.code.startsWith("6") ? "sh" : "sz";
        q.price     = d(f[3]);
        q.preClose  = d(f[4]);
        q.open      = d(f[5]);
        q.volume    = l(f[6]);
        q.change    = d(f[31]);
        q.changePct = d(f[32]);
        q.high      = d(f[33]);
        q.low       = d(f[34]);
        if (f.length > 37) q.turnover = d(f[37]);
        if (f.length > 39) q.pe       = d(f[39]);
        if (f.length > 45) q.cap      = d(f[45]); // 流通市值(亿)
        else if (f.length > 44) q.cap  = d(f[44]); // 总市值(亿)

        return q;
    }

    /**
     * 生成指定市场的全部可能股票代码
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

    // ──────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────

    private void enqueue(String url, Callback cb) {
        Request req = new Request.Builder().url(url).get().build();
        HTTP.newCall(req).enqueue(cb);
    }

    /**
     * 腾讯市场前缀: "sh"=上海, "sz"=深圳
     */
    public static String getTencentMarket(String code) {
        if (code == null || code.isEmpty()) return "sz";
        return code.startsWith("6") || code.startsWith("5") ? "sh" : "sz";
    }

    /**
     * 保持向后兼容: "1"=上海, "0"=深圳
     */
    public static String getMarket(String code) {
        if (code == null || code.isEmpty()) return "0";
        return code.startsWith("6") || code.startsWith("5") ? "1" : "0";
    }

    private String toTencentPeriod(String period) {
        switch (period) {
            case "1分":  return "m1";
            case "5分":  return "m5";
            case "15分": return "m15";
            case "30分": return "m30";
            case "60分": return "m60";
            case "周K":  return "week";
            case "月K":  return "month";
            default:     return "day"; // 日K
        }
    }

    /** 分钟时间戳格式化: "202607060935" → "2026-07-06 09:35" */
    private String formatMinuteDate(String compact) {
        if (compact != null && compact.length() >= 12) {
            return compact.substring(0, 4) + "-" + compact.substring(4, 6) + "-" +
                    compact.substring(6, 8) + " " + compact.substring(8, 10) + ":" +
                    compact.substring(10, 12);
        }
        return compact;
    }

    private double d(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }
    private long l(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    /** K线List → JSON字符串（前端 onKlineData 消费格式）*/
    public static String klineBarsToJson(List<KlineBar> bars) {
        JSONArray arr = new JSONArray();
        for (KlineBar bar : bars) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("date", bar.date);
                obj.put("o",    bar.open);
                obj.put("c",    bar.close);
                obj.put("h",    bar.high);
                obj.put("l",    bar.low);
                obj.put("v",    bar.volume);
                obj.put("pct",  bar.changePct);
                obj.put("turn", bar.turnRate);
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }
}
