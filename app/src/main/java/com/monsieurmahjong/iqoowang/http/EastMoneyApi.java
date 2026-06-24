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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 东方财富行情接口 — OkHttp版
 *
 * 使用东方财富公开接口（无需Key）
 * 全部异步，回调切回主线程
 */
public class EastMoneyApi {

    private static final String TAG = "EastMoneyApi";

    // ── OkHttp 单例客户端 ──
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(chain -> {
                // 统一加请求头，避免被东方财富拦截
                Request req = chain.request().newBuilder()
                        .header("User-Agent",
                                "Mozilla/5.0 (Linux; Android 13; V2324A) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/120.0.0.0 Mobile Safari/537.36")
                        .header("Referer", "https://www.eastmoney.com/")
                        .header("Accept", "application/json, text/plain, */*")
                        .build();
                return chain.proceed(req);
            })
            .build();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static EastMoneyApi sInstance;

    public static EastMoneyApi get() {
        if (sInstance == null) sInstance = new EastMoneyApi();
        return sInstance;
    }

    // ──────────────────────────────────────────
    // URL 模板
    // ──────────────────────────────────────────

    // K线（复权）：klt=101日K 102周K 103月K 1/5/15/30/60分钟
    private static final String URL_KLINE =
            "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
                    "?cb=&fields1=f1,f2,f3,f4,f5,f6" +
                    "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61" +
                    "&klt=%d&fqt=1&secid=%s.%s&beg=0&end=20500101&lmt=%d" +
                    "&ut=fa5fd1943c7b386f172d6893dbfba10b";

    // 单股快照
    private static final String URL_QUOTE =
            "https://push2.eastmoney.com/api/qt/stock/get" +
                    "?ut=fa5fd1943c7b386f172d6893dbfba10b&invt=2&fltt=2" +
                    "&fields=f43,f44,f45,f46,f47,f48,f57,f58,f107,f169,f170,f9" +
                    "&secid=%s.%s";

    // 批量行情（持仓刷新）
    private static final String URL_BATCH =
            "https://push2.eastmoney.com/api/qt/ulist.np/get" +
                    "?fltt=2&invt=2&fields=f2,f3,f4,f12,f14,f5" +
                    "&secids=%s&ut=fa5fd1943c7b386f172d6893dbfba10b";

    // 股票列表（选股扫描）
    // fs: m:1+t:2,m:1+t:23 = 沪市主板；m:0+t:6,m:0+t:80 = 深市主板
    private static final String URL_LIST =
            "https://push2.eastmoney.com/api/qt/clist/get" +
                    "?pn=%d&pz=50&po=1&np=1&ut=bd1d9428105693ce9dcd&fltt=2&invt=2&fid=f3" +
                    "&fs=%s&fields=f12,f14,f2,f3,f15,f16,f17,f18,f20,f9";

    private static final String FS_SH = "m:1+t:2,m:1+t:23";
    private static final String FS_SZ = "m:0+t:6,m:0+t:80";

    // ──────────────────────────────────────────
    // 数据模型
    // ──────────────────────────────────────────

    public static class QuoteData {
        public String code, name, market;
        public double price, open, high, low, preClose, change, changePct, pe;
        public long volume;
        public double turnover;
        // 复用pe字段存储流通市值（亿），避免增加字段
        public double cap; // 亿
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
    // K线数据
    // ──────────────────────────────────────────

    public void fetchKline(String code, String period, int limit, KlineCallback cb) {
        int klt = periodToKlt(period);
        String mkt = getMarket(code);
        String url = String.format(URL_KLINE, klt, mkt, code, limit);

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
                    JSONObject data = root.getJSONObject("data");
                    String name = data.optString("name", code);
                    JSONArray klines = data.getJSONArray("klines");

                    List<KlineBar> bars = new ArrayList<>();
                    for (int i = 0; i < klines.length(); i++) {
                        // 字段顺序：日期,开,收,高,低,成交量,成交额,振幅,涨跌幅,涨跌额,换手率
                        String[] p = klines.getString(i).split(",");
                        if (p.length < 11) continue;
                        KlineBar bar = new KlineBar();
                        bar.date      = p[0];
                        bar.open      = d(p[1]);
                        bar.close     = d(p[2]);
                        bar.high      = d(p[3]);
                        bar.low       = d(p[4]);
                        bar.volume    = l(p[5]);
                        bar.amount    = d(p[6]);
                        bar.amplitude = d(p[7]);
                        bar.changePct = d(p[8]);
                        bar.changeAmt = d(p[9]);
                        bar.turnRate  = d(p[10]);
                        bars.add(bar);
                    }
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
    // 单股行情快照
    // ──────────────────────────────────────────

    public void fetchQuote(String code, QuoteCallback cb) {
        String url = String.format(URL_QUOTE, getMarket(code), code);
        enqueue(url, new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                MAIN.post(() -> cb.onError(e.getMessage()));
            }
            @Override public void onResponse(Call call, Response resp) throws IOException {
                try (Response r = resp) {
                    JSONObject data = new JSONObject(r.body().string()).getJSONObject("data");
                    QuoteData q = new QuoteData();
                    q.code      = code;
                    q.market    = getMarket(code).equals("1") ? "sh" : "sz";
                    q.name      = data.optString("f58");
                    // 东方财富价格乘以100存储
                    q.price     = data.optDouble("f43", 0) / 100.0;
                    q.open      = data.optDouble("f46", 0) / 100.0;
                    q.high      = data.optDouble("f44", 0) / 100.0;
                    q.low       = data.optDouble("f45", 0) / 100.0;
                    q.changePct = data.optDouble("f170", 0) / 100.0;
                    q.change    = data.optDouble("f169", 0) / 100.0;
                    q.volume    = data.optLong("f47", 0);
                    q.turnover  = data.optDouble("f48", 0);
                    q.pe        = data.optDouble("f9", 0) / 100.0;
                    MAIN.post(() -> cb.onSuccess(q));
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
            sb.append(getMarket(c)).append('.').append(c);
        }
        String url = String.format(URL_BATCH, sb.toString());
        enqueue(url, new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                MAIN.post(() -> cb.onError(e.getMessage()));
            }
            @Override public void onResponse(Call call, Response resp) throws IOException {
                try (Response r = resp) {
                    JSONArray diff = new JSONObject(r.body().string())
                            .getJSONObject("data").getJSONArray("diff");
                    Map<String, QuoteData> result = new HashMap<>();
                    for (int i = 0; i < diff.length(); i++) {
                        JSONObject item = diff.getJSONObject(i);
                        QuoteData q = new QuoteData();
                        q.code      = item.optString("f12");
                        q.name      = item.optString("f14");
                        q.price     = item.optDouble("f2", 0) / 100.0;
                        q.changePct = item.optDouble("f3", 0) / 100.0;
                        q.change    = item.optDouble("f4", 0) / 100.0;
                        q.volume    = item.optLong("f5", 0);
                        result.put(q.code, q);
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
        String fs  = "sh".equals(market) ? FS_SH : FS_SZ;
        String url = String.format(URL_LIST, page,
                fs.replace(",", "%2C").replace("+", "%2B"));

        enqueue(url, new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "fetchStockList fail", e);
                MAIN.post(() -> cb.onError(e.getMessage()));
            }
            @Override public void onResponse(Call call, Response resp) throws IOException {
                try (Response r = resp) {
                    JSONObject root = new JSONObject(r.body().string());
                    JSONObject dataObj = root.optJSONObject("data");
                    if (dataObj == null) {
                        MAIN.post(() -> cb.onError("data null"));
                        return;
                    }
                    JSONArray diff = dataObj.optJSONArray("diff");
                    if (diff == null) { MAIN.post(() -> cb.onSuccess(new ArrayList<>())); return; }

                    List<QuoteData> stocks = new ArrayList<>();
                    for (int i = 0; i < diff.length(); i++) {
                        JSONObject item = diff.getJSONObject(i);
                        QuoteData q = new QuoteData();
                        q.code      = item.optString("f12");
                        q.name      = item.optString("f14");
                        q.price     = item.optDouble("f2",  0) / 100.0;
                        q.changePct = item.optDouble("f3",  0) / 100.0;
                        q.high      = item.optDouble("f15", 0) / 100.0;
                        q.low       = item.optDouble("f16", 0) / 100.0;
                        q.open      = item.optDouble("f17", 0) / 100.0;
                        q.preClose  = item.optDouble("f18", 0) / 100.0;
                        // f20 = 流通市值（元），转亿
                        q.cap       = item.optDouble("f20", 0) / 1e8;
                        q.market    = market;
                        // 过滤无效数据
                        if (q.code.isEmpty() || q.price <= 0) continue;
                        stocks.add(q);
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
    // 工具
    // ──────────────────────────────────────────

    private void enqueue(String url, Callback cb) {
        Request req = new Request.Builder().url(url).get().build();
        HTTP.newCall(req).enqueue(cb);
    }

    public static String getMarket(String code) {
        if (code == null || code.isEmpty()) return "0";
        return code.startsWith("6") || code.startsWith("5") ? "1" : "0";
    }

    private int periodToKlt(String period) {
        switch (period) {
            case "1分":  return 1;
            case "5分":  return 5;
            case "15分": return 15;
            case "30分": return 30;
            case "60分": return 60;
            case "周K":  return 102;
            case "月K":  return 103;
            default:     return 101; // 日K
        }
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
