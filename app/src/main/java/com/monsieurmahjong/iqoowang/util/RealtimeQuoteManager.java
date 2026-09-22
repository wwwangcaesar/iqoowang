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
        public long volume;      // 本分钟成交量增量(手)，由相邻两条累计值作差得到
        public long cumVolume;   // 【D修复时新增】开盘至本分钟累计成交量(手)，画分时图/核对用
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

    // ════════════════════════════════════
    // 【D新增】分时图用缓存：候选池/持仓股票本来每轮tick就会拉一次行情和分时（规则引擎评估要用），
    // 这里顺手缓下最新一份，分时图功能按需查看时直接读缓存，不用另发一轮网络请求。
    // 缓存过期时间不需要特地处理——下个2分钟tick自然会覆盖掉，比到底多久算过期更实用。
    // ════════════════════════════════════

    private static final Map<String, Quote> sLatestQuoteCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, List<MinutePoint>> sMinuteCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Long> sMinuteCacheUpdatedAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** 【2026-09-19新增·协作aiD，对应用户"分时图不降级获取实时分时数据"要求】上面三个缓存都是
     *  纯内存Map——Android后台监控服务被系统回收进程、App被手动划掉、或者只是简单重启，都会让
     *  它们清零，跟WatchlistManager.prevDayVwap当初做持久化改造要解决的是完全同一个问题（那边
     *  注释原话："Android后台监控服务被系统回收进程、重启是常态"）。现补一层SharedPreferences
     *  磁盘持久化：每次成功拉到新数据顺手落一份，仅限"今天"这一天有效——跨天的旧快照一律视为
     *  无效，不能把上一个交易日的分时图误当成"今天"展示。init()由StockMasterApp.onCreate()
     *  调用；不调用init()时sAppContext为null，持久化读写全部安全跳过，退化为原来的纯内存行为，
     *  不会报错、不影响任何现有调用方。 */
    private static android.content.Context sAppContext;
    private static final String PREFS_DISK_CACHE = "minute_quote_disk_cache";

    public static void init(android.content.Context context) {
        sAppContext = context.getApplicationContext();
    }

    private static String todayStr() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new java.util.Date());
    }

    private void persistQuote(String code, Quote q) {
        if (sAppContext == null || q == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("date", todayStr());
            o.put("name", q.name); o.put("price", q.price); o.put("open", q.open);
            o.put("high", q.high); o.put("low", q.low); o.put("prevClose", q.prevClose);
            o.put("changeAmt", q.changeAmt); o.put("changePct", q.changePct);
            o.put("volume", q.volume); o.put("amount", q.amount); o.put("time", q.time);
            sAppContext.getSharedPreferences(PREFS_DISK_CACHE, android.content.Context.MODE_PRIVATE)
                    .edit().putString("quote_" + code, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    private Quote loadPersistedQuote(String code) {
        if (sAppContext == null) return null;
        try {
            String raw = sAppContext.getSharedPreferences(PREFS_DISK_CACHE, android.content.Context.MODE_PRIVATE)
                    .getString("quote_" + code, null);
            if (raw == null) return null;
            JSONObject o = new JSONObject(raw);
            if (!todayStr().equals(o.optString("date"))) return null; // 不是今天的快照，一律视为无效
            Quote q = new Quote();
            q.code = code; q.market = market(code); q.source = "disk_cache";
            q.name = o.optString("name"); q.price = o.optDouble("price");
            q.open = o.optDouble("open"); q.high = o.optDouble("high"); q.low = o.optDouble("low");
            q.prevClose = o.optDouble("prevClose"); q.changeAmt = o.optDouble("changeAmt");
            q.changePct = o.optDouble("changePct"); q.volume = o.optLong("volume");
            q.amount = o.optDouble("amount"); q.time = o.optString("time");
            return q;
        } catch (Exception e) { return null; }
    }

    private void persistMinuteLine(String code, List<MinutePoint> points) {
        if (sAppContext == null || points == null || points.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            for (MinutePoint p : points) {
                JSONObject po = new JSONObject();
                po.put("time", p.time); po.put("price", p.price); po.put("avgPrice", p.avgPrice);
                po.put("volume", p.volume); po.put("cumVolume", p.cumVolume);
                arr.put(po);
            }
            JSONObject o = new JSONObject();
            o.put("date", todayStr());
            o.put("updatedAt", System.currentTimeMillis());
            o.put("points", arr);
            sAppContext.getSharedPreferences(PREFS_DISK_CACHE, android.content.Context.MODE_PRIVATE)
                    .edit().putString("minute_" + code, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    private List<MinutePoint> loadPersistedMinuteLine(String code) {
        if (sAppContext == null) return null;
        try {
            String raw = sAppContext.getSharedPreferences(PREFS_DISK_CACHE, android.content.Context.MODE_PRIVATE)
                    .getString("minute_" + code, null);
            if (raw == null) return null;
            JSONObject o = new JSONObject(raw);
            if (!todayStr().equals(o.optString("date"))) return null;
            JSONArray arr = o.optJSONArray("points");
            if (arr == null) return null;
            List<MinutePoint> result = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject po = arr.optJSONObject(i);
                if (po == null) continue;
                MinutePoint p = new MinutePoint();
                p.time = po.optString("time"); p.price = po.optDouble("price");
                p.avgPrice = po.optDouble("avgPrice"); p.volume = po.optLong("volume");
                p.cumVolume = po.optLong("cumVolume");
                result.add(p);
            }
            return result;
        } catch (Exception e) { return null; }
    }

    private long loadPersistedMinuteLineUpdatedAt(String code) {
        if (sAppContext == null) return 0;
        try {
            String raw = sAppContext.getSharedPreferences(PREFS_DISK_CACHE, android.content.Context.MODE_PRIVATE)
                    .getString("minute_" + code, null);
            if (raw == null) return 0;
            JSONObject o = new JSONObject(raw);
            if (!todayStr().equals(o.optString("date"))) return 0;
            return o.optLong("updatedAt");
        } catch (Exception e) { return 0; }
    }

    /** 拿某支股最新一次缓存的行情——内存缓存没有时退一步查磁盘持久化（仅限今天内有效），
     *  两边都没有才真的返回null（刚加进池、还没来得及拉到第一轮，或者是隔天了磁盘缓存
     *  也主动判定失效）。 */
    public Quote getCachedQuote(String code) {
        Quote q = sLatestQuoteCache.get(code);
        return q != null ? q : loadPersistedQuote(code);
    }

    /** 拿某支股最新一次缓存的分时数据——同上，内存没有退一步查磁盘（仅限今天）。 */
    public List<MinutePoint> getCachedMinuteLine(String code) {
        List<MinutePoint> l = sMinuteCache.get(code);
        if (l != null) return l;
        List<MinutePoint> persisted = loadPersistedMinuteLine(code);
        return persisted != null ? persisted : new ArrayList<>();
    }

    /** 这份分时缓存是什么时候拉的，前端用来判断数据多新鲜（内存和磁盘都没有返回0）。 */
    public long getCachedMinuteLineUpdatedAt(String code) {
        Long t = sMinuteCacheUpdatedAt.get(code);
        if (t != null) return t;
        return loadPersistedMinuteLineUpdatedAt(code);
    }

    // ══════════════════════════════════════════
    // 批量实时行情：主源失败的代码自动转备用源重试
    // ══════════════════════════════════════════

    public void fetchBatch(List<String> codes, QuoteCallback cb) {
        if (codes == null || codes.isEmpty()) {
            cb.onResult(new LinkedHashMap<>(), new ArrayList<>());
            return;
        }
        QuoteCallback cachingCb = (quotes, failedCodes) -> {
            if (quotes != null && !quotes.isEmpty()) {
                sLatestQuoteCache.putAll(quotes);
                for (Quote q : quotes.values()) persistQuote(q.code, q); // 【新增·协作aiD】顺手落盘，进程重启也能用
            }
            cb.onResult(quotes, failedCodes);
        };
        if (tencentAvailable()) {
            fetchTencentBatch(codes, (okMap, failed) -> {
                if (failed.isEmpty()) { cachingCb.onResult(okMap, failed); return; }
                Log.i(TAG, "腾讯源" + failed.size() + "支未拿到，转新浪源重试");
                fetchSinaFallback(codes, failed, okMap, cachingCb);
            });
        } else {
            Log.i(TAG, "腾讯源熔断中，直接走新浪源");
            fetchSinaFallback(codes, codes, new LinkedHashMap<>(), cachingCb);
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

    /** 【2026-09-19改造·协作aiD，对应用户"不降级的获取实时股票的分时数据信息"要求】原先失败
     *  一次就直接回调onError，一次瞬时网络抖动就会让这一轮分时数据/分时图彻底拿不到，跟
     *  fetchPrevDayVwap早就有的重试机制不是同一个标准（那边是首次+最多2次重试）。现改成
     *  同款重试节奏：先重试几次（间隔递增800ms/1600ms），排除掉瞬时抖动这种假阴性，重试用尽
     *  仍失败才真正回调onError——此时是真的这一刻确实拿不到（比如非交易时段接口本身没有
     *  "今天"的数据），不是代码或网络的偶发问题，onError的msg里会写清楚已经重试过几次，
     *  供决策日志/_system日志据此明确写出失败原因。 */
    public void fetchMinuteLine(String code, MinuteCallback cb) {
        fetchMinuteLineAttempt(code, cb, 0);
    }

    private static final int MINUTE_LINE_MAX_RETRY = 2; // 首次+最多2次重试，共最多3次尝试
    // 【2026-09-22·Claude 修复】这个常量之前在下面被引用但从未声明过，文件实际编译不过——
    // 这里补回作为兜底文案，真正的具体原因现在由 parseMinuteJson 通过 reasonOut 传回来，
    // 这个常量只在 reasonOut 意外为空时才会被用到。
    private static final String MINUTE_PARSE_FAIL_DETAIL = "分时数据为空或格式解析失败";

    private void fetchMinuteLineAttempt(String code, MinuteCallback cb, int attempt) {
        String url = String.format(Locale.US, URL_TENCENT_MINUTE, market(code), code);
        Request req = new Request.Builder().url(url).build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                retryOrFailMinuteLine(code, cb, attempt, "请求失败: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response resp) {
                try (Response r = resp) {
                    byte[] bytes = r.body().bytes();
                    String body = new String(bytes, Charset.forName("UTF-8"));
                    String[] failReason = new String[1]; // 【2026-09-22·Claude】out参数，避免共享字段在并发拉取多支股票时互相覆盖
                    List<MinutePoint> points = parseMinuteJson(body, code, failReason);
                    if (points.isEmpty()) {
                        retryOrFailMinuteLine(code, cb, attempt, failReason[0] != null ? failReason[0] : MINUTE_PARSE_FAIL_DETAIL);
                    } else {
                        sMinuteCache.put(code, points); // 【D新增】顺手缓下，分时图功能按需读
                        sMinuteCacheUpdatedAt.put(code, System.currentTimeMillis());
                        persistMinuteLine(code, points); // 【新增·协作aiD】顺手落盘，进程重启也能用（仅限今天）
                        MAIN.post(() -> cb.onResult(code, points));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "分时数据解析异常 " + code, e);
                    retryOrFailMinuteLine(code, cb, attempt, "解析异常: " + e.getMessage());
                }
            }
        });
    }

    private void retryOrFailMinuteLine(String code, MinuteCallback cb, int attempt, String reason) {
        if (attempt < MINUTE_LINE_MAX_RETRY) {
            Log.w(TAG, "分时数据获取失败(" + code + " 第" + (attempt + 1) + "次): " + reason + "，800ms后重试");
            MAIN.postDelayed(() -> fetchMinuteLineAttempt(code, cb, attempt + 1), 800L * (attempt + 1));
            return;
        }
        Log.w(TAG, "分时数据重试" + MINUTE_LINE_MAX_RETRY + "次后仍失败(" + code + "): " + reason);
        MAIN.post(() -> cb.onError(code, reason + "（已重试" + MINUTE_LINE_MAX_RETRY + "次仍失败）"));
    }

    /**
     * 【D复查时改正】这里之前的注释写错了：这个接口每条分时数据并不是"时间 价格 均价 成交量"，
     * 实测格式是"时间 价格 累计成交量 累计成交额"（跟下面parsePrevDayVwap用的day/query接口同一套格式），
     * 根本没有单独的"均价"字段，均价要自己用累计成交额÷(累计成交量×100)算。用真实样本验证过：
     * “0930 2.023 37265 7538709.50”，7538709.50÷(37265×100)=2.0233，与价格2.023对得上；之前把第3个字段
     * 直接当avgPrice用，实际存进去的是一个几万起步的累计成交量数字，跟真实股价完全不在一个
     * 量级，导致TradingRuleEngine里所有依赖分时VWAP的判断长期在错误基准上运行，现已修正。
     * {"code":0,"msg":"","data":{"sh600000":{"date":"20260710",
     *   "minute":{"data":["0930 10.00 1234 12340.00", "0931 10.02 2221 22254.67", ...]}}}}
     *
     * 【2026-09-22·Claude 修复】上面这条注释里写的响应形状（"minute":{"data":[...]}）不是这个
     * 接口实测会返回的真实形状，也是下面代码一直在用的取值路径——但用手机浏览器直接访问
     * https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=sz002264 拿到的真实响应是：
     * {"code":0,"msg":"","data":{"sz002264":{"data":{"date":"20260921",
     *   "data":["0930 7.35 24911 18309585.00", "0931 7.37 76244 56064079.00", ...]},
     *   "qt":{...},"mx_price":{...}}}}
     * 数组在 stockObj.data.data，根本没有"minute"这一层——这正是分时数据全天每一轮都失败
     * （"分时数据为空或格式解析失败"）的根因：下面代码找的是stockObj.minute.data，这个接口从
     * 未返回过这个形状，所以逐分钟必然100%命中"无minute.data数组"这个分支，重试3次因为是同一个
     * 结构性问题、不是网络抖动，自然次次都失败。现改为优先按实测的data.data取，"minute"分支保留
     * 作兼容兜底（不确定这个接口是否在其他场景/股票/时间点下会返回旧形状，留着比删掉更稳妥）。
     * 同时把4种失败分支各自的具体原因通过reasonOut带出去，不再全部塌缩成一句通用文案，方便
     * 决策日志/logcat直接看出卡在哪一步、原始响应长什么样。
     */
    private List<MinutePoint> parseMinuteJson(String body, String code, String[] reasonOut) {
        List<MinutePoint> result = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                String msg = "分时响应无data字段: " + truncate(body);
                Log.w(TAG, msg);
                reasonOut[0] = msg;
                return result;
            }
            String fullCode = market(code) + code;
            JSONObject stockObj = data.optJSONObject(fullCode);
            if (stockObj == null) {
                // 有些情况下key不带市场前缀，兜底找第一个
                java.util.Iterator<String> keys = data.keys();
                if (keys.hasNext()) stockObj = data.optJSONObject(keys.next());
            }
            if (stockObj == null) {
                String msg = "分时响应未找到股票数据: " + truncate(body);
                Log.w(TAG, msg);
                reasonOut[0] = msg;
                return result;
            }
            // 实测真实形状是 stockObj.data.data，"minute.data"是之前注释里写错的旧形状，保留作兼容兜底
            JSONObject innerData = stockObj.optJSONObject("data");
            JSONArray arr = innerData != null ? innerData.optJSONArray("data") : null;
            if (arr == null) {
                JSONObject minuteObj = stockObj.optJSONObject("minute");
                arr = minuteObj != null ? minuteObj.optJSONArray("data") : null;
            }
            if (arr == null) {
                String msg = "分时响应无data.data/minute.data数组: " + truncate(body);
                Log.w(TAG, msg);
                reasonOut[0] = msg;
                return result;
            }

            long prevCumVol = 0;
            for (int i = 0; i < arr.length(); i++) {
                String line = arr.optString(i, "");
                String[] parts = line.split("\\s+");
                if (parts.length < 4) continue;
                MinutePoint p = new MinutePoint();
                String t = parts[0]; // "0930"
                p.time = t.length() == 4 ? t.substring(0, 2) + ":" + t.substring(2) : t;
                p.price = d(parts[1]);
                double cumVolLots = d(parts[2]);   // 累计成交量，单位"手"(1手=100股)
                double cumAmount = d(parts[3]);    // 累计成交额，单位"元"
                p.cumVolume = (long) cumVolLots;
                p.avgPrice = cumVolLots > 0 ? cumAmount / (cumVolLots * 100.0) : 0;
                long thisMinuteVol = p.cumVolume - prevCumVol;
                p.volume = thisMinuteVol > 0 ? thisMinuteVol : 0;
                prevCumVol = p.cumVolume;
                result.add(p);
            }
        } catch (Exception e) {
            String msg = "分时JSON解析异常: " + e.getMessage() + "，原始响应: " + truncate(body);
            Log.w(TAG, "分时JSON解析失败，原始响应: " + truncate(body), e);
            reasonOut[0] = msg;
        }
        return result;
    }

    // ══════════════════════════════════════════
    // 【2026-08-20新增】历史分时（最近5个交易日）—— 精确算"昨日全天真实VWAP"用
    // ══════════════════════════════════════════

    /**
     * 拿"最近一个已收盘交易日"（即昨日）的全天真实 VWAP（成交量加权均价）。
     * 【重要修复】之前这里只请求一次腾讯接口，一旦失败就直接返回0，而调用方
     * （TradingRuleEngine低开路径）又没有任何地方缓存“已经尝试过但失败了”这个事实，导致如果这个
     * 接口持续不稳定，会每轮tick都重新发一次新请求、永远拿不到数据，低开路径就会永远卡在
     * “正在异步获取”这一步，观察好几天也不会有任何进展。现在改成：先重试几次，
     * 重试仍失败则退化为用日K缓存估算一个近似值，保证低开路径不会因为这一个接口的问题被无限期卡住。
     * 精确值用的是腾讯"5日线"接口，取最后一条分时记录的累计成交额÷(累计成交量×100)；
     * 已用真实股票数据(sz000001, 2026-08-19)人工验证过换算结果落在当天实际价格区间内。
     */
    public void fetchPrevDayVwap(String code, String expectedDate, PrevDayVwapCallback cb) {
        fetchPrevDayVwapAttempt(code, expectedDate, cb, 0);
    }

    private static final int PREV_VWAP_MAX_RETRY = 2; // 首次+最多2次重试，共最多3次尝试

    private void fetchPrevDayVwapAttempt(String code, String expectedDate, PrevDayVwapCallback cb, int attempt) {
        String url = String.format(Locale.US, URL_TENCENT_DAY_QUERY, market(code), code);
        Request req = new Request.Builder().url(url).build();
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                retryOrFallbackVwap(code, expectedDate, cb, attempt, "请求失败: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response resp) {
                try (Response r = resp) {
                    if (!r.isSuccessful()) {
                        retryOrFallbackVwap(code, expectedDate, cb, attempt, "HTTP " + r.code());
                        return;
                    }
                    byte[] bytes = r.body().bytes();
                    String body = new String(bytes, Charset.forName("UTF-8"));
                    Object[] parsed = parsePrevDayVwap(body, code, expectedDate);
                    double vwap = (Double) parsed[0];
                    String date = (String) parsed[1];
                    if (vwap > 0 && date != null) {
                        MAIN.post(() -> cb.onResult(code, vwap, date));
                    } else {
                        retryOrFallbackVwap(code, expectedDate, cb, attempt, "响应中未找到日期匹配" + expectedDate + "的已收盘交易日分时数据");
                    }
                } catch (Exception e) {
                    retryOrFallbackVwap(code, expectedDate, cb, attempt, "解析异常: " + e.getMessage());
                }
            }
        });
    }

    /** 精确获取失败时先重试，重试用尽后改用日K缓存估算，不再无限期卡在原地重试。 */
    private void retryOrFallbackVwap(String code, String expectedDate, PrevDayVwapCallback cb, int attempt, String reason) {
        if (attempt < PREV_VWAP_MAX_RETRY) {
            Log.w(TAG, "昨日真实VWAP获取失败(" + code + " 第" + (attempt + 1) + "次): " + reason + "，800ms后重试");
            MAIN.postDelayed(() -> fetchPrevDayVwapAttempt(code, expectedDate, cb, attempt + 1), 800L * (attempt + 1));
            return;
        }
        Log.w(TAG, "昨日真实VWAP重试" + PREV_VWAP_MAX_RETRY + "次后仍失败(" + code + "): " + reason + "，改用日K缓存估算兜底");
        fallbackVwapFromDailyKline(code, expectedDate, cb, reason);
    }

    /** 精确的分钟级VWAP多次重试仍失败时的兜底：用已有日K缓存算一个近似值——
     *  (开+收*2+高+低)/5，比单纯用收盘价更贴近全天成交重心。虽不如真实成交量加权精确，
     *  但能保证低开路径不会因为这一个接口的问题被无限期卡住，日K缓存现在也已经是腾讯+新浪
     *  双源，比这个单一无重试的分时接口本身更可靠。 */
    private void fallbackVwapFromDailyKline(String code, String expectedDate, PrevDayVwapCallback cb, String preciseFailReason) {
        try {
            List<MarketDataManager.KlineBar> bars = MarketDataManager.get().getCachedKline(code, 5);
            // 【2026-09-16修复】原先直接取“缓存最后一条”当兜底数据来源，但这条兜底本来就是在精确
            // 接口失败后走的，缓存这时完全可能也混进了“今天”这条不完整的记录（比如交易时段内又
            // 重新点过“更新数据”）——如果不做校验，兜底出来的近似值可能对应的也是今天自己而不是
            // 真正的前一交易日。改成优先按expectedDate（调用方已经校验过陈旧性的“昨日”）去缓存里
            // 精确匹配；找不到就退而求其次取最近一条日期不等于今天的记录，但绝不会用“今天”这条。
            String todayDateStr = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new java.util.Date());
            MarketDataManager.KlineBar match = null;
            for (MarketDataManager.KlineBar b : bars) {
                if (expectedDate != null && expectedDate.equals(b.date)) { match = b; break; }
            }
            if (match == null) {
                for (int i = bars.size() - 1; i >= 0; i--) {
                    if (!todayDateStr.equals(bars.get(i).date)) { match = bars.get(i); break; }
                }
            }
            if (match == null) {
                Log.w(TAG, "日K缓存也没有" + code + "可用的前一交易日数据，本轮彻底放弃昨日VWAP");
                MAIN.post(() -> cb.onResult(code, 0, null));
                return;
            }
            double approx = (match.open + match.close * 2 + match.high + match.low) / 5.0;
            if (approx <= 0) {
                MAIN.post(() -> cb.onResult(code, 0, null));
                return;
            }
            String useDate = match.date;
            Log.i(TAG, "已用日K估算" + code + "昨日(" + useDate + ")VWAP≈" + String.format(Locale.CHINA, "%.4f", approx)
                    + "（精确值失败原因：" + preciseFailReason + "）");
            MAIN.post(() -> cb.onResult(code, approx, useDate));
        } catch (Exception e) {
            Log.w(TAG, "日K估算兜底也失败(" + code + ")", e);
            MAIN.post(() -> cb.onResult(code, 0, null));
        }
    }

    /**
     * 解析"5日线"接口响应，从最新往前找第一个"日期不等于今天"的交易日（今天这条还没收盘，
     * 累计值不完整、也没有prec字段），那就是最近一个已收盘的交易日=昨日。用它最后一条
     * 分时记录的累计成交额/累计成交量算出全天真实VWAP。
     * 返回 Object[]{Double vwap, String date}；vwap<=0或异常时 {0.0, null}。
     * date格式统一成"yyyy-MM-dd"，跟MarketDataManager.KlineBar.date同一口径，方便比对。
     */
    private Object[] parsePrevDayVwap(String body, String code, String expectedDate) {
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
                String formattedDate = rawDate.substring(0, 4) + "-" + rawDate.substring(4, 6) + "-" + rawDate.substring(6, 8);
                // 【修复：昨日VWAP用了过期日期】这个接口自己认定的“最近一个非今天交易日”
                // 不一定可靠——它有自己独立的数据返回窗口，实测出现过它仍停留在好几个交易日
                // 之前、迟迟不往前推进的情况，而这里之前逢到第一个“非今天”且有数据的日子就直接
                // 当“昨日”用了，没有跟调用方已经用K线缓存校验过陈旧性的真实“昨日”核对过。
                // 现在必须严格等于调用方传入的expectedDate才能用，对不上就继续往前找（理论上
                // 不会再找到，因为更早的只会更旧），找不到就整体判失败，交给上层重试/日K兜底，
                // 绝不能把错误日期的VWAP偷偷当成“昨日”塞回去。
                if (expectedDate != null && !expectedDate.equals(formattedDate)) continue;
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
                return new Object[]{vwap, formattedDate};
            }
            Log.w(TAG, "day/query未找到日期=" + expectedDate + "的有效已收盘交易日数据 " + code);
        } catch (Exception e) {
            Log.w(TAG, "day/query解析异常 " + code, e);
        }
        return new Object[]{0.0, null};
    }

    private String truncate(String s) { return s != null && s.length() > 300 ? s.substring(0, 300) + "..." : s; }

    private double d(String s) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; } }
    private long l(String s) { try { return (long) Double.parseDouble(s.trim()); } catch (Exception e) { return 0; } }
}
