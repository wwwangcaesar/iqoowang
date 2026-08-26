package com.monsieurmahjong.iqoowang.util;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * RealtimeQuoteManager — 高频实时行情采集器
 *
 * 设计背景：之前的实时行情直接走单一数据源（腾讯 qt.gtimg.cn），一旦这个源抽风
 * （超时/限流/网络抖动），整轮监控就卡在 10~15 秒的超时等待上，30秒一次的轮询
 * 节奏直接被打乱，还没等到结果就该出下一轮了。
 *
 * 这里的解决方案：
 *   1. 双数据源：腾讯(主) + 新浪(备)，两边字段来源、口径独立，不会同时抽风
 *   2. 高频专用短超时：连接2秒/读取3秒（不是历史K线下载那种10+15秒的宽松超时）
 *   3. 熔断：某数据源连续失败达到阈值后，冷却期内直接跳过它走备用源，避免每次
 *      轮询都白白等一次必然超时的请求
 *   4. 批量请求：一次性把所有关注代码拼到一个请求里，而不是每支股票单独请求
 *
 * 用法：
 *   RealtimeQuoteManager.get().fetchBatch(codes, (quotes, failedCodes) -> {...});
 */
public class RealtimeQuoteManager {

    private static final String TAG = "RealtimeQuoteManager";

    private static final String URL_TENCENT = "https://qt.gtimg.cn/q=";
    private static final String URL_SINA = "https://hq.sinajs.cn/list=";
    private static final String URL_TENCENT_MINUTE =
            "https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=%s%s";
    /** 【2026-08-20新增】腾讯"5日线"接口——跟上面分时接口同一个域名，只是路径不同，
     *  返回最近5个交易日、每天完整的分时数据（不只是今天），用来精确算出"昨日全天真实
     *  成交量加权均价"（VWAP），供新的低开底仓路径判断用。已用真实股票数据人工验证过换算逻辑。 */
    private static final String URL_TENCENT_DAY_QUERY =
            "https://web.ifzq.gtimg.cn/appstock/app/day/query?code=%s%s";

    /** 高频轮询专用短超时客户端，绝不能用历史下载那套10+15秒的宽松超时 */
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static RealtimeQuoteManager sInstance;
    public static RealtimeQuoteManager get() {
        if (sInstance == null) {
            synchronized (RealtimeQuoteManager.class) { if (sInstance == null) sInstance = new RealtimeQuoteManager(); }
        }
        return sInstance;
    }
    private RealtimeQuoteManager() {}

    // ══════════════════════════════════════════
    // 熔断器：连续失败N次后冷却一段时间不再尝试该源
    // ══════════════════════════════════════════

    private static final int BREAKER_THRESHOLD = 3;      // 连续失败3次触发熔断
    private static final long BREAKER_COOLDOWN_MS = 60_000; // 冷却60秒后重新尝试

    private final AtomicInteger mTencentFailCount = new AtomicInteger(0);
    private final AtomicInteger mSinaFailCount = new AtomicInteger(0);
    private volatile long mTencentBreakUntil = 0;
    private volatile long mSinaBreakUntil = 0;

    private boolean tencentAvailable() { return System.currentTimeMillis() >= mTencentBreakUntil; }
    private boolean sinaAvailable() { return System.currentTimeMillis() >= mSinaBreakUntil; }

    private void onTencentFail() {
        if (mTencentFailCount.incrementAndGet() >= BREAKER_THRESHOLD) {
            mTencentBreakUntil = System.currentTimeMillis() + BREAKER_COOLDOWN_MS;
            Log.w(TAG, "腾讯源连续失败" + BREAKER_THRESHOLD + "次，熔断" + (BREAKER_COOLDOWN_MS / 1000) + "秒");
        }
    }
    private void onTencentOk() { mTencentFailCount.set(0); mTencentBreakUntil = 0; }

    private void onSinaFail() {
        if (mSinaFailCount.incrementAndGet() >= BREAKER_THRESHOLD) {
            mSinaBreakUntil = System.currentTimeMillis() + BREAKER_COOLDOWN_MS;
            Log.w(TAG, "新浪源连续失败" + BREAKER_THRESHOLD + "次，熔断" + (BREAKER_COOLDOWN_MS / 1000) + "秒");
        }
    }
    private void onSinaOk() { mSinaFailCount.set(0); mSinaBreakUntil = 0; }

    // ══════════════════════════════════════════
    // 数据结构
    // ══════════════════════════════════════════

    public static class Quote {
        public String code, name, market, source;
        public double price, open, high, low, prevClose, changePct, changeAmt;
        public long volume;
        public double amount;
        public String time; // HH:mm:ss，若数据源未提供则为空
    }

    public interface QuoteCallback {
        /** quotes: 拿到的行情；failedCodes: 两个源都没拿到数据的代码（网络彻底不通或代码有误） */
        void onResult(Map<String, Quote> quotes, List<String> failedCodes);
    }

    public static class MinutePoint {
        public String time;   // "09:30"
        public double price, avgPrice;
        public long volume;
    }

    public interface MinuteCallback {
        void onResult(String code, List<MinutePoint> points);
        void onError(String code, String msg);
    }

    /** 【2026-08-20新增】拿"最近一个已收盘交易日"全天真实VWAP的回调。
     *  vwap<=0 表示没拿到（网络失败/解析失败），此时date也是null，调用方要能优雅跳过本轮判断。 */
    public interface PrevDayVwapCallback {
        void onResult(String code, double vwap, String date);
    }

    private String market(String code) {
        return (code.startsWith("6") || code.startsWith("5")) ? "sh" : "sz";
    }

    // ══════════════════════════════════════════
    // 批量实时行情：主源失败的代码自动转备用源重试
    // ══════════════════════════════════════════

    public void fetchBatch(List<String> codes, QuoteCallback cb) {
        if (codes == null || codes.isEmpty()) {
            cb.onResult(new LinkedHashMap<>(), new ArrayList<>());
            return;
        }
        if (tencentAvailable()) {
            fetchTencentBatch(codes, (okMap, failed) -> {
                if (failed.isEmpty()) { cb.onResult(okMap, failed); return; }
                Log.i(TAG, "腾讯源" + failed.size() + "支未拿到，转新浪源重试");
                fetchSinaFallback(codes, failed, okMap, cb);
            });
        } else {
            Log.i(TAG, "腾讯源熔断中，直接走新浪源");
            fetchSinaFallback(codes, codes, new LinkedHashMap<>(), cb);
        }
    }

    private void fetchSinaFallback(List<String> allCodes, List<String> needCodes,
                                    Map<String, Quote> okMap, QuoteCallback cb) {
        if (!sinaAvailable()) {
            Log.w(TAG, "新浪源也在熔断中，本轮" + needCodes.size() + "支行情缺失");
            cb.onResult(okMap, needCodes);
            return;
        }
        fetchSinaBatch(needCodes, (sinaMap, stillFailed) -> {
            okMap.putAll(sinaMap);
            cb.onResult(okMap, stillFailed);
        });
    }

    // ── 腾讯源 ──

    private void fetchTencentBatch(List<String> codes, QuoteCallback cb) {
        StringBuilder sb = new StringBuilder();
        for (String c : codes) {
            if (sb.length() > 0) sb.append(',');
            sb.append(market(c)).append(c);
        }
        Request req = new Request.Builder().url(URL_TENCENT + sb).build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                onTencentFail();
                Log.w(TAG, "腾讯批量行情失败: " + e.getMessage());
                MAIN.post(() -> cb.onResult(new LinkedHashMap<>(), codes));
            }
            @Override public void onResponse(Call call, Response resp) {
                try (Response r = resp) {
                    byte[] bytes = r.body().bytes();
                    String body = new String(bytes, Charset.forName("GBK"));
                    Map<String, Quote> map = new LinkedHashMap<>();
                    List<String> failed = new ArrayList<>();
                    String[] lines = body.split(";\\n?");
                    Map<String, String> lineByCode = new LinkedHashMap<>();
                    for (String line : lines) {
                        int codeStart = line.indexOf("_str_") + 5;
                        if (codeStart < 5 || codeStart >= line.length()) continue;
                        int codeEnd = line.indexOf('=', codeStart);
                        if (codeEnd < 0) continue;
                        String fullCode = line.substring(codeStart, codeEnd).trim(); // 如 sh600000
                        lineByCode.put(fullCode.length() > 2 ? fullCode.substring(2) : fullCode, line);
                    }
                    for (String code : codes) {
                        String line = lineByCode.get(code);
                        Quote q = line != null ? parseTencentLine(line, code) : null;
                        if (q != null) map.put(code, q); else failed.add(code);
                    }
                    if (!map.isEmpty()) onTencentOk();
                    if (!failed.isEmpty()) onTencentFail();
                    MAIN.post(() -> cb.onResult(map, failed));
                } catch (Exception e) {
                    onTencentFail();
                    Log.w(TAG, "腾讯批量行情解析异常", e);
                    MAIN.post(() -> cb.onResult(new LinkedHashMap<>(), codes));
                }
            }
        });
    }

    private Quote parseTencentLine(String line, String code) {
        int q1 = line.indexOf('"');
        int q2 = line.lastIndexOf('"');
        if (q1 < 0 || q2 <= q1) return null;
        String content = line.substring(q1 + 1, q2);
        if (content.isEmpty()) return null;
        String[] f = content.split("~", -1);
        if (f.length < 35) return null;

        Quote q = new Quote();
        q.source = "tencent";
        q.code = code;
        q.market = market(code);
        q.name = f[1];
        q.price = d(f[3]);
        q.prevClose = d(f[4]);
        q.open = d(f[5]);
        q.volume = l(f[6]);
        q.changeAmt = d(f[31]);
        q.changePct = d(f[32]);
        q.high = d(f[33]);
        q.low = d(f[34]);
        if (f.length > 37) q.amount = d(f[37]) * 10000; // 腾讯该字段单位为"万元"
        if (f.length > 30) q.time = f[30];
        return q;
    }

    // ── 新浪源（备用）──

    private void fetchSinaBatch(List<String> codes, QuoteCallback cb) {
        if (codes.isEmpty()) { cb.onResult(new LinkedHashMap<>(), new ArrayList<>()); return; }
        StringBuilder sb = new StringBuilder();
        for (String c : codes) {
            if (sb.length() > 0) sb.append(',');
            sb.append(market(c)).append(c);
        }
        // 新浪要求带Referer，否则会返回空内容
        Request req = new Request.Builder()
                .url(URL_SINA + sb)
                .header("Referer", "https://finance.sina.com.cn")
                .build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                onSinaFail();
                Log.w(TAG, "新浪批量行情失败: " + e.getMessage());
                MAIN.post(() -> cb.onResult(new LinkedHashMap<>(), codes));
            }
            @Override public void onResponse(Call call, Response resp) {
                try (Response r = resp) {
                    byte[] bytes = r.body().bytes();
                    String body = new String(bytes, Charset.forName("GBK"));
                    Map<String, Quote> map = new LinkedHashMap<>();
                    List<String> failed = new ArrayList<>();
                    String[] lines = body.split("\\n");
                    Map<String, String> lineByCode = new LinkedHashMap<>();
                    for (String line : lines) {
                        int codeStart = line.indexOf("hq_str_") + 7;
                        if (codeStart < 7 || codeStart >= line.length()) continue;
                        int codeEnd = line.indexOf('=', codeStart);
                        if (codeEnd < 0) continue;
                        String fullCode = line.substring(codeStart, codeEnd).trim();
                        lineByCode.put(fullCode.length() > 2 ? fullCode.substring(2) : fullCode, line);
                    }
                    for (String code : codes) {
                        String line = lineByCode.get(code);
                        Quote q = line != null ? parseSinaLine(line, code) : null;
                        if (q != null) map.put(code, q); else failed.add(code);
                    }
                    if (!map.isEmpty()) onSinaOk();
                    if (!failed.isEmpty()) onSinaFail();
                    MAIN.post(() -> cb.onResult(map, failed));
                } catch (Exception e) {
                    onSinaFail();
                    Log.w(TAG, "新浪批量行情解析异常", e);
                    MAIN.post(() -> cb.onResult(new LinkedHashMap<>(), codes));
                }
            }
        });
    }

    private Quote parseSinaLine(String line, String code) {
        int q1 = line.indexOf('"');
        int q2 = line.lastIndexOf('"');
        if (q1 < 0 || q2 <= q1) return null;
        String content = line.substring(q1 + 1, q2);
        if (content.isEmpty()) return null;
        String[] f = content.split(",", -1);
        if (f.length < 10) return null;

        // 新浪字段（长期稳定，未变过）：
        // 0名称 1今开 2昨收 3现价 4最高 5最低 6买一价 7卖一价 8成交量(股) 9成交额(元) ... 30日期 31时间
        Quote q = new Quote();
        q.source = "sina";
        q.code = code;
        q.market = market(code);
        q.name = f[0];
        q.open = d(f[1]);
        q.prevClose = d(f[2]);
        q.price = d(f[3]);
        q.high = d(f[4]);
        q.low = d(f[5]);
        q.volume = l(f[8]) / 100; // 股 → 手，与腾讯口径对齐
        q.amount = d(f[9]);
        if (q.prevClose > 0) {
            q.changeAmt = q.price - q.prevClose;
            q.changePct = q.changeAmt / q.prevClose * 100;
        }
        if (f.length > 31) q.time = f[31];
        return q;
    }

    // ══════════════════════════════════════════
    // 分时数据（今日分钟级走势）—— 规则引擎判断"是否已站稳分时低点"要用
    // ══════════════════════════════════════════

    public void fetchMinuteLine(String code, MinuteCallback cb) {
        String url = String.format(Locale.US, URL_TENCENT_MINUTE, market(code), code);
        Request req = new Request.Builder().url(url).build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                MAIN.post(() -> cb.onError(code, e.getMessage()));
            }
            @Override public void onResponse(Call call, Response resp) {
                try (Response r = resp) {
                    byte[] bytes = r.body().bytes();
                    String body = new String(bytes, Charset.forName("UTF-8"));
                    List<MinutePoint> points = parseMinuteJson(body, code);
                    if (points.isEmpty()) {
                        MAIN.post(() -> cb.onError(code, "分时数据为空或格式解析失败"));
                    } else {
                        MAIN.post(() -> cb.onResult(code, points));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "分时数据解析异常 " + code, e);
                    MAIN.post(() -> cb.onError(code, e.getMessage()));
                }
            }
        });
    }

    /**
     * 腾讯分时接口返回形如：
     * {"code":0,"msg":"","data":{"sh600000":{"date":"20260710",
     *   "minute":{"data":["0930 10.00 10.00 1234", "0931 10.02 10.01 987", ...]}}}}
     * 每条数据: "时间 价格 均价 成交量(手)"，空格分隔。
     * 此接口字段格式来自公开文档，若腾讯改版导致解析为空，会记录原始响应方便定位。
     */
    private List<MinutePoint> parseMinuteJson(String body, String code) {
        List<MinutePoint> result = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) { Log.w(TAG, "分时响应无data字段: " + truncate(body)); return result; }
            String fullCode = market(code) + code;
            JSONObject stockObj = data.optJSONObject(fullCode);
            if (stockObj == null) {
                // 有些情况下key不带市场前缀，兜底找第一个
                java.util.Iterator<String> keys = data.keys();
                if (keys.hasNext()) stockObj = data.optJSONObject(keys.next());
            }
            if (stockObj == null) { Log.w(TAG, "分时响应未找到股票数据: " + truncate(body)); return result; }
            JSONObject minuteObj = stockObj.optJSONObject("minute");
            JSONArray arr = minuteObj != null ? minuteObj.optJSONArray("data") : null;
            if (arr == null) { Log.w(TAG, "分时响应无minute.data数组: " + truncate(body)); return result; }

            for (int i = 0; i < arr.length(); i++) {
                String line = arr.optString(i, "");
                String[] parts = line.split("\\s+");
                if (parts.length < 3) continue;
                MinutePoint p = new MinutePoint();
                String t = parts[0]; // "0930"
                p.time = t.length() == 4 ? t.substring(0, 2) + ":" + t.substring(2) : t;
                p.price = d(parts[1]);
                p.avgPrice = d(parts[2]);
                p.volume = parts.length > 3 ? l(parts[3]) : 0;
                result.add(p);
            }
        } catch (Exception e) {
            Log.w(TAG, "分时JSON解析失败，原始响应: " + truncate(body), e);
        }
        return result;
    }

    // ══════════════════════════════════════════
    // 【2026-08-20新增】历史分时（最近5个交易日）—— 精确算"昨日全天真实VWAP"用
    // ══════════════════════════════════════════

    /**
     * 拿"最近一个已收盘交易日"（即昨日）的全天真实VWAP（成交量加权均价），不是近似值。
     * 用的是腾讯"5日线"接口，跟 fetchMinuteLine() 同一个域名，只是路径不同（day/query
     * 而不是 minute/query）。返回过去5个交易日、每天完整的分时数据，每条记录格式是
     * "时间 价格 累计成交量(手) 累计成交额(元)"——注意是从当天开盘到这一刻的累计值，
     * 不是单分钟的增量。取最后一条（即收盘那一刻）的累计成交额÷(累计成交量×100)，
     * 就是这一天真实的、按成交量加权的全天平均价。
     * 已用真实股票数据(sz000001, 2026-08-19)手工验证过换算结果落在当天实际价格区间内。
     */
    public void fetchPrevDayVwap(String code, PrevDayVwapCallback cb) {
        String url = String.format(Locale.US, URL_TENCENT_DAY_QUERY, market(code), code);
        Request req = new Request.Builder().url(url).build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "历史分时(day/query)请求失败 " + code + ": " + e.getMessage());
                MAIN.post(() -> cb.onResult(code, 0, null));
            }
            @Override public void onResponse(Call call, Response resp) {
                try (Response r = resp) {
                    byte[] bytes = r.body().bytes();
                    String body = new String(bytes, Charset.forName("UTF-8"));
                    Object[] parsed = parsePrevDayVwap(body, code);
                    double vwap = (Double) parsed[0];
                    String date = (String) parsed[1];
                    MAIN.post(() -> cb.onResult(code, vwap, date));
                } catch (Exception e) {
                    Log.w(TAG, "历史分时(day/query)解析异常 " + code, e);
                    MAIN.post(() -> cb.onResult(code, 0, null));
                }
            }
        });
    }

    /**
     * 解析"5日线"接口响应，从最新往前找第一个"日期不等于今天"的交易日（今天这条还没收盘，
     * 累计值不完整、也没有prec字段），那就是最近一个已收盘的交易日=昨日。用它最后一条
     * 分时记录的累计成交额/累计成交量算出全天真实VWAP。
     * 返回 Object[]{Double vwap, String date}；vwap<=0或异常时 {0.0, null}。
     * date格式统一成"yyyy-MM-dd"，跟MarketDataManager.KlineBar.date同一口径，方便比对。
     */
    private Object[] parsePrevDayVwap(String body, String code) {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) { Log.w(TAG, "day/query响应无data字段: " + truncate(body)); return new Object[]{0.0, null}; }
            String fullCode = market(code) + code;
            JSONObject stockObj = data.optJSONObject(fullCode);
            if (stockObj == null) {
                java.util.Iterator<String> keys = data.keys();
                if (keys.hasNext()) stockObj = data.optJSONObject(keys.next());
            }
            if (stockObj == null) { Log.w(TAG, "day/query响应未找到股票数据: " + truncate(body)); return new Object[]{0.0, null}; }
            JSONArray days = stockObj.optJSONArray("data");
            if (days == null) { Log.w(TAG, "day/query响应无data数组: " + truncate(body)); return new Object[]{0.0, null}; }

            String today = new java.text.SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new java.util.Date());
            for (int i = days.length() - 1; i >= 0; i--) {
                JSONObject dayObj = days.optJSONObject(i);
                if (dayObj == null) continue;
                String rawDate = dayObj.optString("date", "");
                if (rawDate.length() != 8 || rawDate.equals(today)) continue;
                JSONArray minuteArr = dayObj.optJSONArray("data");
                if (minuteArr == null || minuteArr.length() == 0) continue;
                String lastLine = minuteArr.optString(minuteArr.length() - 1, "");
                String[] parts = lastLine.split("\\s+");
                if (parts.length < 4) continue;
                double cumVolLots = d(parts[2]);   // 累计成交量，单位"手"(1手=100股)
                double cumAmount = d(parts[3]);    // 累计成交额，单位"元"
                if (cumVolLots <= 0) continue;
                double vwap = cumAmount / (cumVolLots * 100.0);
                if (vwap <= 0) continue;
                String formattedDate = rawDate.substring(0, 4) + "-" + rawDate.substring(4, 6) + "-" + rawDate.substring(6, 8);
                return new Object[]{vwap, formattedDate};
            }
            Log.w(TAG, "day/query未找到有效的已收盘交易日 " + code);
        } catch (Exception e) {
            Log.w(TAG, "day/query解析异常 " + code, e);
        }
        return new Object[]{0.0, null};
    }

    private String truncate(String s) { return s != null && s.length() > 300 ? s.substring(0, 300) + "..." : s; }

    private double d(String s) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; } }
    private long l(String s) { try { return (long) Double.parseDouble(s.trim()); } catch (Exception e) { return 0; } }
}
