package com.monsieurmahjong.iqoowang.util;

import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * TradeFlowManager —— 分时图"买入 / 卖出"真实拆分
 *
 * 背景【2026-09-28，用户要求"成交量要展示买入和卖出具体的数字，现在只展示了成交量总量"】：
 * 分时图之前只有每分钟成交量的总数（RealtimeQuoteManager.MinutePoint.volume），量能柱的红/绿
 * 也只是"这一分钟价格比上一分钟涨还是跌"的推测，并不是真实的买卖成交量。腾讯"分时"接口
 * （web.ifzq.gtimg.cn/.../minute/query）每分钟只给 时间/价格/累计成交量/累计成交额 四个字段，
 * 本身没有买卖方向，所以这里改用腾讯的"分笔成交明细"接口：
 *   https://stock.gtimg.cn/data/index.php?appn=detail&action=data&c=sz000001&p=页码
 * 返回当天从集合竞价开始的每一笔（约3秒一笔的快照）成交，每页70笔，格式：
 *   v_detail_data_sz000001=[页码,"序号/时间/价格/涨跌/成交量(手)/成交额(元)/方向|..."]
 * 其中方向 B=买盘(主动买入，即"外盘")、S=卖盘(主动卖出，即"内盘")、M=中性盘。这是交易所行情快照
 * 里按成交价与买卖盘口比较得出的真实方向，不是拿涨跌推测的。本类把它按分钟累加成
 * 每分钟的 买入量/卖出量/中性量（单位：手），供分时图画堆叠量能柱、长按显示具体数字。
 *
 * 取数与缓存：
 *   1. 只在前端真正查看某支股票分时图时才拉（StockBridge.getMinuteChartData触发），不给候选池
 *      每支股票都拉，避免请求量暴涨。
 *   2. 一个交易日最多约70页。第一次要把已有的页全部拉下来（3路并发，通常几秒），之后只需要
 *      重拉"最后一页(可能还没满70笔)"以及新增的页——已经满70笔的页不会再变，直接用缓存。
 *   3. 每次刷新都先拉第0页，用它的第一笔（当天集合竞价那一笔，全天不变）做签名，签名变了说明
 *      换了交易日，旧缓存整体作废，不会把昨天的分笔当成今天的。
 *
 * 防止展示错误数据（宁可不显示也不显示错的）：
 *   - 按分钟聚合后，会拿"累计成交量"跟分时曲线（RealtimeQuoteManager缓存的MinutePoint.cumVolume）
 *     核对一遍：差距超出合理范围（漏页/换日/接口异常）就把状态标记为inconsistent，前端不展示拆分，
 *     并写一条_system日志说明具体差了多少。
 *   - 分笔时间是3秒快照戳，分钟怎么归属有两种口径（一笔在09:30:02，算"09:30这一分钟"还是
 *     "09:31这一分钟"），不同数据源习惯不同，这里不拍脑袋定死，两种都算一遍，拿跟分时曲线累计
 *     成交量偏差更小的那种，对齐方式和两种口径的偏差都会记进日志，方便核查。
 *   - 集合竞价那一笔并入09:30（分时曲线第一个点的成交量本来就包含集合竞价），15:01之后的盘后
 *     固定价格交易不并入，午休时间没有成交。
 *
 * 状态（FlowResult.status）：ok 正常可展示；loading 首次拉取中；failed 拉取失败(带原因)；
 * inconsistent 与分时曲线核对不上；pending 数据暂时不足以核对；auction 09:00-09:31集合竞价阶段
 * 暂不展示（分时曲线此时还可能是上一交易日）。
 *
 * 已知限制（如实记录）：腾讯这个接口只提供"最近一个交易日"的分笔，历史日期没法回补，所以
 * FenshiHistoryManager里的历史分时没有买卖拆分，前端会明确提示。
 */
public class TradeFlowManager {

    private static final String TAG = "TradeFlowManager";

    public static final String STATUS_OK = "ok";
    public static final String STATUS_LOADING = "loading";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_INCONSISTENT = "inconsistent";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_AUCTION = "auction";

    private static final String URL_TICK_DETAIL =
            "https://stock.gtimg.cn/data/index.php?appn=detail&action=data&c=%s%s&p=%d";
    /** 跟RealtimeQuoteManager分时/5日线请求用同一套浏览器UA+Referer，避免被腾讯WAF按"非浏览器签名"拦截 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    /** 实测每页70笔（sz000001 p=2 → 序号140..209；sh600103 p=0 → 序号0..69） */
    private static final int PAGE_SIZE = 70;
    /** 保护上限：一天最多约4800笔≈69页，留足余量，防止接口异常时无限翻页 */
    private static final int MAX_PAGES = 120;
    private static final int FETCH_CONCURRENCY = 3;
    private static final int PAGE_MAX_RETRY = 2;
    private static final long REFRESH_GAP_TRADING_MS = 25_000;
    private static final long REFRESH_GAP_IDLE_MS = 5 * 60_000;
    private static final long MIN_ATTEMPT_GAP_MS = 8_000;
    private static final long FAIL_LOG_GAP_MS = 2 * 60_000;
    private static final int MAX_CACHED_CODES = 6;

    private static final int OPEN_SEC = 9 * 3600 + 30 * 60;
    private static final int AM_CLOSE_SEC = 11 * 3600 + 30 * 60;
    private static final int PM_OPEN_SEC = 13 * 3600;
    private static final int PM_CLOSE_SEC = 15 * 3600;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private static TradeFlowManager sInstance;

    public static TradeFlowManager get() {
        if (sInstance == null) {
            synchronized (TradeFlowManager.class) {
                if (sInstance == null) sInstance = new TradeFlowManager();
            }
        }
        return sInstance;
    }

    private TradeFlowManager() {}

    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** 协调线程：一次只跑一支股票的刷新流程，避免多支股票同时翻几十页把请求量打满 */
    private final ExecutorService mOrchestrator =
            Executors.newSingleThreadExecutor(r -> daemon("trade-flow-orch", r));
    /** 分页并发拉取线程池 */
    private final ExecutorService mPagePool =
            Executors.newFixedThreadPool(FETCH_CONCURRENCY, r -> daemon("trade-flow-page", r));

    // ══════════════════════════════════════════
    // 数据结构
    // ══════════════════════════════════════════

    private static class Tick {
        final int sec;        // 当天第几秒（HH*3600+mm*60+ss）
        final long volume;    // 手
        final char dir;       // 'B'买 'S'卖 'M'中性

        Tick(int sec, long volume, char dir) {
            this.sec = sec;
            this.volume = volume;
            this.dir = dir;
        }
    }

    private static class PageResult {
        final List<Tick> ticks = new ArrayList<>();
        /** 网络/HTTP层失败（已重试用尽） */
        boolean failed;
        /** HTTP 200但内容不是预期的分笔格式（比如WAF挑战页、不存在的页） */
        boolean notFound;
        String reason = "";
    }

    private static class CodeState {
        final List<List<Tick>> pages = new ArrayList<>();
        final AtomicBoolean refreshing = new AtomicBoolean(false);
        volatile long lastAttemptAt;
        volatile long lastSuccessAt;
        /** pages最近一次发生变化的时间，用来判断缓存是不是今天的 */
        volatile long lastDataAt;
        volatile String lastError;
        String page0Sig;
        /** 这份分笔缓存所属的交易日（yyyy-MM-dd）。买卖拆分必须跟展示的那一天严格对应：null表示日期不明（集合竞价阶段拉的），不可信 */
        volatile String dataDate;
        volatile String lastLoggedStatus = "";
        volatile long lastFailLogAt;
    }

    private final Map<String, CodeState> mStates = new ConcurrentHashMap<>();

    /** 按分钟聚合后的结果，供前端展示 */
    public static class FlowResult {
        public String status = STATUS_LOADING;
        public String message = "";
        /** key="HH:mm"（跟MinutePoint.time同格式），value={买入,卖出,中性}，单位手 */
        public final Map<String, long[]> byMinute = new HashMap<>();
        public long buyTotal, sellTotal, neutralTotal;
        public int tickCount, pageCount;
        /** 最后一笔分笔的时间 HH:mm:ss */
        public String asOf = "";
        /** 采用的分钟对齐口径及两种口径的偏差，写日志用 */
        public String alignment = "";
        /** 这份买卖拆分所属的交易日（yyyy-MM-dd），前端必须核对跟当前图表的股票+日期完全一致才能用 */
        public String date = "";
    }

    private static class Agg {
        final TreeMap<Integer, long[]> byLabel = new TreeMap<>();
        long buy, sell, neutral, maxTickVol;
        int tickCount, lastLabel = -1, lastSec = -1;
    }

    private static class Compare {
        int count;
        long sumErr, refCum, tickCum;
        int refLabel;

        boolean has() { return count > 0; }

        double avgErr() { return count > 0 ? (double) sumErr / count : Double.MAX_VALUE; }
    }

    // ══════════════════════════════════════════
    // 对外接口
    // ══════════════════════════════════════════

    /** 当前是否处于交易时段（考虑法定节假日；TradingCalendar没初始化时退化为只看周末） */
    public static boolean isTradingNow() {
        try {
            Calendar c = Calendar.getInstance();
            if (!isTradingDay(c)) return false;
            int sec = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND);
            return (sec >= 9 * 3600 + 25 * 60 && sec <= 11 * 3600 + 31 * 60)
                    || (sec >= 12 * 3600 + 59 * 60 && sec <= 15 * 3600 + 5 * 60);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isTradingDay(Calendar c) {
        try {
            return TradingCalendar.get().isTradingDay(c);
        } catch (IllegalStateException e) {
            int d = c.get(Calendar.DAY_OF_WEEK);
            return d != Calendar.SATURDAY && d != Calendar.SUNDAY;
        }
    }

    /**
     * 【买卖拆分的日期口径】最近一个"已开盘"的交易日（yyyy-MM-dd），分笔/分时接口此刻返回的就是这一天的数据：
     * 交易日9:31之后=今天；开盘前或非交易日=上一个交易日；交易日9:00-9:31集合竞价阶段接口可能还在
     * 返回上一交易日，日期无法确定，返回null（调用方一律按"不展示买卖拆分"处理）。
     */
    public static String latestSessionDate() {
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
            Calendar c = Calendar.getInstance();
            if (isTradingDay(c)) {
                int sec = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND);
                if (sec >= 9 * 3600 + 31 * 60) return fmt.format(c.getTime());
                if (sec >= 9 * 3600) return null;
            }
            Calendar p = (Calendar) c.clone();
            for (int i = 0; i < 20; i++) {
                p.add(Calendar.DAY_OF_MONTH, -1);
                if (isTradingDay(p)) return fmt.format(p.getTime());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 请求刷新某支股票的分笔买卖数据。异步执行，内部做了节流：交易时段25秒内不重复拉，非交易
     * 时段5分钟内不重复拉（数据不会再变），上一轮还没跑完时直接忽略。刷新流程结束（不论成功失败）
     * 后回调onFinished，调用方据此把最新结果推给前端重新渲染。
     */
    public void requestRefresh(final String code, final Runnable onFinished) {
        if (code == null || code.isEmpty()) return;
        final CodeState st = stateOf(code);
        long now = System.currentTimeMillis();
        long gap = isTradingNow() ? REFRESH_GAP_TRADING_MS : REFRESH_GAP_IDLE_MS;
        if (st.lastSuccessAt > 0 && now - st.lastSuccessAt < gap) return;
        if (now - st.lastAttemptAt < MIN_ATTEMPT_GAP_MS) return;
        if (!st.refreshing.compareAndSet(false, true)) return;
        st.lastAttemptAt = now;
        mOrchestrator.execute(() -> {
            try {
                doRefresh(code, st);
            } catch (Throwable t) {
                st.lastError = "分笔拉取流程异常: " + t;
                Log.e(TAG, "分笔刷新异常 " + code, t);
            } finally {
                st.refreshing.set(false);
            }
            logOutcome(code, st);
            if (onFinished != null) {
                try {
                    onFinished.run();
                } catch (Throwable t) {
                    Log.e(TAG, "分笔刷新完成回调异常", t);
                }
            }
        });
    }

    /**
     * 按分钟聚合并跟分时曲线核对，返回可直接展示的买卖拆分。不发网络请求，只读已缓存的分笔，
     * 开销很小（最多几千笔），可以在每次拼分时图JSON时调用。
     *
     * @param points 分时曲线（RealtimeQuoteManager缓存的MinutePoint，时间升序），用来核对分笔是否可信
     */
    public FlowResult computeFlow(String code, List<RealtimeQuoteManager.MinutePoint> points) {
        FlowResult r = new FlowResult();
        CodeState st = mStates.get(code);
        long now = System.currentTimeMillis();
        if (st == null) {
            r.message = "分笔数据加载中…";
            return r;
        }
        List<List<Tick>> snapshot;
        final String snapDate;
        synchronized (st) {
            snapshot = new ArrayList<>(st.pages);
            snapDate = st.dataDate; // 分笔快照和它所属日期在同一把锁里一起读，避免换日刷新的短暂窗口里旧分笔配上新日期
        }
        if (snapshot.isEmpty()) {
            if (st.lastError != null && !st.refreshing.get()) {
                r.status = STATUS_FAILED;
                r.message = st.lastError;
            } else if (st.lastSuccessAt > 0) {
                r.status = STATUS_PENDING;
                r.message = "暂无分笔成交记录（可能尚未开盘）";
            } else {
                r.message = "分笔数据加载中…";
            }
            return r;
        }
        if (st.lastDataAt <= 0 || !sameDay(st.lastDataAt, now)) {
            // 缓存是以前某天拉的（进程隔夜没退出），等今天的刷新覆盖，先不展示
            r.message = "分笔数据加载中…";
            return r;
        }

        Calendar c = Calendar.getInstance();
        int nowSec = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND);
        if (isTradingDay(c) && nowSec >= 9 * 3600 && nowSec < 9 * 3600 + 31 * 60) {
            r.status = STATUS_AUCTION;
            r.message = "开盘集合竞价阶段（9:31前），买卖拆分暂不展示";
            return r;
        }

        // 买卖拆分必须对应"当前展示的这个交易日"：分笔缓存所属日期未知、或跟最近交易日不一致时一律不展示
        final String sessionDate = latestSessionDate();
        if (sessionDate == null || snapDate == null || !sessionDate.equals(snapDate)) {
            r.message = "分笔数据日期与当前交易日不一致，等待刷新后再显示买卖拆分";
            return r;
        }

        Agg up = aggregate(snapshot, true);
        Agg down = aggregate(snapshot, false);
        r.pageCount = snapshot.size();
        if (up.tickCount == 0) {
            r.status = STATUS_PENDING;
            r.message = "分笔数据里暂无有效成交";
            return r;
        }
        if (points == null || points.isEmpty()) {
            r.status = STATUS_PENDING;
            r.message = "等待分时曲线数据，用于核对买卖拆分";
            return r;
        }

        Compare cu = compare(up, points);
        Compare cd = compare(down, points);
        if (!cu.has() && !cd.has()) {
            r.status = STATUS_PENDING;
            r.message = "暂无可与分时曲线核对的完整分钟，稍后自动显示";
            return r;
        }
        boolean useUp = !cd.has() || (cu.has() && cu.avgErr() <= cd.avgErr());
        Agg chosen = useUp ? up : down;
        Compare cc = useUp ? cu : cd;
        r.alignment = String.format(Locale.CHINA, "%s[平均偏差%s手/分钟点，备选口径%s]",
                useUp ? "按分钟末归属" : "按分钟初归属", fmtErr(cc), fmtErr(useUp ? cd : cu));

        long tol = Math.max(Math.round(cc.refCum * 0.05), Math.max(3 * chosen.maxTickVol, 300L));
        long diff = Math.abs(cc.tickCum - cc.refCum);
        if (diff > tol) {
            r.status = STATUS_INCONSISTENT;
            r.message = String.format(Locale.CHINA,
                    "分笔累计%d手与分时曲线累计%d手（截至%s）相差%d手，超出容差%d手，已隐藏买卖拆分以免展示错误数据",
                    cc.tickCum, cc.refCum, labelToStr(cc.refLabel), diff, tol);
            return r;
        }

        for (Map.Entry<Integer, long[]> e : chosen.byLabel.entrySet()) {
            r.byMinute.put(labelToStr(e.getKey()), e.getValue());
        }
        r.buyTotal = chosen.buy;
        r.sellTotal = chosen.sell;
        r.neutralTotal = chosen.neutral;
        r.tickCount = chosen.tickCount;
        r.asOf = secToStr(chosen.lastSec);
        r.date = sessionDate;
        r.status = STATUS_OK;
        return r;
    }

    // ══════════════════════════════════════════
    // 刷新流程
    // ══════════════════════════════════════════

    private CodeState stateOf(String code) {
        CodeState st = mStates.get(code);
        if (st != null) return st;
        synchronized (mStates) {
            st = mStates.get(code);
            if (st == null) {
                if (mStates.size() >= MAX_CACHED_CODES) evictOne();
                st = new CodeState();
                mStates.put(code, st);
            }
            return st;
        }
    }

    private void evictOne() {
        String oldest = null;
        long oldestAt = Long.MAX_VALUE;
        for (Map.Entry<String, CodeState> e : mStates.entrySet()) {
            CodeState s = e.getValue();
            if (s.refreshing.get()) continue;
            if (s.lastAttemptAt < oldestAt) {
                oldestAt = s.lastAttemptAt;
                oldest = e.getKey();
            }
        }
        if (oldest != null) mStates.remove(oldest);
    }

    private void doRefresh(String code, CodeState st) {
        long t0 = System.currentTimeMillis();
        String error = null;
        int fetched = 0;

        // 第0页：既是取数起点，也用来判断缓存是不是上一个交易日遗留的
        PageResult first = fetchPageWithRetry(code, 0);
        if (first.failed || first.notFound) {
            st.lastError = "第0页" + (first.failed ? "请求失败" : "响应格式异常") + "：" + first.reason;
            return;
        }
        if (first.ticks.isEmpty()) {
            synchronized (st) {
                st.pages.clear();
                st.page0Sig = null;
            }
            st.lastError = null;
            st.lastSuccessAt = System.currentTimeMillis();
            return;
        }
        String sig = first.ticks.get(0).sec + "/" + first.ticks.get(0).volume + "/" + first.ticks.get(0).dir;
        final String sessionDate = latestSessionDate();
        int resume;
        synchronized (st) {
            if (!st.pages.isEmpty() && (sessionDate == null || st.dataDate == null || !st.dataDate.equals(sessionDate))) st.pages.clear(); // 日期未知或已换日：旧缓存不可信，整份重拉
            if (!st.pages.isEmpty() && !sig.equals(st.page0Sig)) st.pages.clear(); // 换了交易日，旧缓存作废
            st.page0Sig = sig;
            st.dataDate = sessionDate;
            if (st.pages.isEmpty()) st.pages.add(first.ticks);
            else st.pages.set(0, first.ticks);
            st.lastDataAt = System.currentTimeMillis();
            // 已缓存的最后一页可能当时还没满70笔，要重拉；更早的页都是满的、不会再变
            resume = Math.max(1, st.pages.size() - 1);
        }
        fetched++;

        boolean tail = first.ticks.size() < PAGE_SIZE;
        int page = resume;
        boolean firstWindow = true;
        while (!tail && page < MAX_PAGES && error == null) {
            // 增量刷新（缓存里已经有很多页）时第一批只拉1页，避免为还不存在的后续页白白并发探测
            int window = (firstWindow && resume > 1) ? 1 : FETCH_CONCURRENCY;
            firstWindow = false;
            window = Math.min(window, MAX_PAGES - page);
            List<Future<PageResult>> futures = new ArrayList<>();
            for (int i = 0; i < window; i++) {
                final int p = page + i;
                futures.add(mPagePool.submit(() -> fetchPageWithRetry(code, p)));
            }
            for (int i = 0; i < window; i++) {
                int p = page + i;
                PageResult r;
                try {
                    r = futures.get(i).get(40, TimeUnit.SECONDS);
                } catch (Exception e) {
                    error = "第" + p + "页等待超时或异常: " + e;
                    break;
                }
                if (r.failed) {
                    error = "第" + p + "页请求失败：" + r.reason;
                    break;
                }
                if (r.notFound || r.ticks.isEmpty()) {
                    // 空页 / 非首页且格式不是分笔内容（不存在的页）：视为已经到末页；万一其实是中途异常，
                    // 累计成交量核对会发现缺数据并把状态标成inconsistent，下次刷新会从最后缓存的页接着补
                    tail = true;
                    break;
                }
                synchronized (st) {
                    if (p < st.pages.size()) st.pages.set(p, r.ticks);
                    else if (p == st.pages.size()) st.pages.add(r.ticks);
                    else error = "分页出现空洞(第" + p + "页)";
                    st.lastDataAt = System.currentTimeMillis();
                }
                if (error != null) break;
                fetched++;
                if (r.ticks.size() < PAGE_SIZE) {
                    tail = true;
                    break;
                }
            }
            page += window;
        }

        if (error == null) {
            st.lastError = null;
            st.lastSuccessAt = System.currentTimeMillis();
        } else {
            st.lastError = error;
        }
        Log.i(TAG, "分笔刷新 " + code + " 拉取" + fetched + "页 耗时" + (System.currentTimeMillis() - t0) + "ms"
                + (error != null ? " 错误: " + error : ""));
    }

    private PageResult fetchPageWithRetry(String code, int page) {
        PageResult last = null;
        for (int attempt = 0; attempt <= PAGE_MAX_RETRY; attempt++) {
            last = fetchPageOnce(code, page);
            if (!last.failed) return last;
            if (attempt < PAGE_MAX_RETRY) {
                try {
                    Thread.sleep(500L * (attempt + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return last;
    }

    private PageResult fetchPageOnce(String code, int page) {
        PageResult r = new PageResult();
        String url = String.format(Locale.US, URL_TICK_DETAIL, market(code), code, page);
        Request req = new Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://gu.qq.com/")
                .build();
        try (Response resp = HTTP.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                r.failed = true;
                r.reason = "HTTP " + resp.code();
                return r;
            }
            byte[] bytes = resp.body() != null ? resp.body().bytes() : new byte[0];
            String body = new String(bytes, StandardCharsets.UTF_8);
            int q1 = body.indexOf('"');
            int q2 = body.lastIndexOf('"');
            if (!body.contains("v_detail_data_") || q1 < 0 || q2 <= q1) {
                r.notFound = true;
                r.reason = "响应不是分笔格式: " + snippet(body);
                return r;
            }
            String content = body.substring(q1 + 1, q2);
            if (content.isEmpty()) return r; // 空页：已经翻过了最后一页
            for (String item : content.split("\\|")) {
                String[] f = item.split("/");
                if (f.length < 7) continue;
                int sec = parseSec(f[1]);
                if (sec < 0) continue;
                long vol = parseLong(f[4]);
                String d = f[6].trim();
                char dir = d.isEmpty() ? 'M' : Character.toUpperCase(d.charAt(0));
                if (dir != 'B' && dir != 'S') dir = 'M';
                r.ticks.add(new Tick(sec, vol, dir));
            }
            if (r.ticks.isEmpty()) {
                r.notFound = true;
                r.reason = "分笔条目解析失败: " + snippet(content);
            }
        } catch (Exception e) {
            r.failed = true;
            r.reason = String.valueOf(e.getMessage());
        }
        return r;
    }

    /** 刷新结束后写_system日志：状态变化时记一条，失败时最多每2分钟记一条，避免刷屏 */
    private void logOutcome(String code, CodeState st) {
        try {
            List<RealtimeQuoteManager.MinutePoint> points = RealtimeQuoteManager.get().getCachedMinuteLine(code);
            FlowResult fr = computeFlow(code, points);
            String statusKey = fr.status + (st.lastError != null ? "|err" : "");
            long now = System.currentTimeMillis();
            boolean changed = !statusKey.equals(st.lastLoggedStatus);
            boolean failLike = st.lastError != null || STATUS_INCONSISTENT.equals(fr.status);
            if (!changed && !(failLike && now - st.lastFailLogAt > FAIL_LOG_GAP_MS)) return;
            st.lastLoggedStatus = statusKey;
            if (failLike) st.lastFailLogAt = now;
            String msg = String.format(Locale.CHINA,
                    "【买卖拆分】%s：状态=%s 页数=%d 笔数=%d 买入%d手 卖出%d手 中性%d手 对齐=%s%s%s",
                    code, fr.status, fr.pageCount, fr.tickCount, fr.buyTotal, fr.sellTotal, fr.neutralTotal,
                    fr.alignment.isEmpty() ? "-" : fr.alignment,
                    fr.message.isEmpty() ? "" : "｜" + fr.message,
                    st.lastError != null ? "｜最近一次拉取错误：" + st.lastError : "");
            DecisionLogger.get().logNote(msg);
        } catch (Exception e) {
            Log.e(TAG, "写买卖拆分日志失败", e);
        }
    }

    // ══════════════════════════════════════════
    // 聚合 / 核对
    // ══════════════════════════════════════════

    private static Agg aggregate(List<List<Tick>> pages, boolean roundUp) {
        Agg a = new Agg();
        for (List<Tick> page : pages) {
            for (Tick t : page) {
                int label = roundUp ? labelUp(t.sec) : labelDown(t.sec);
                if (label < 0) continue;
                long[] v = a.byLabel.get(label);
                if (v == null) {
                    v = new long[3];
                    a.byLabel.put(label, v);
                }
                if (t.dir == 'B') {
                    v[0] += t.volume;
                    a.buy += t.volume;
                } else if (t.dir == 'S') {
                    v[1] += t.volume;
                    a.sell += t.volume;
                } else {
                    v[2] += t.volume;
                    a.neutral += t.volume;
                }
                a.tickCount++;
                if (t.volume > a.maxTickVol) a.maxTickVol = t.volume;
                if (label > a.lastLabel) a.lastLabel = label;
                if (t.sec > a.lastSec) a.lastSec = t.sec;
            }
        }
        return a;
    }

    /** 分钟末归属：一笔算进它所在分钟"结束时刻"那个标签（09:30:02 → 09:31）。集合竞价并入09:30 */
    static int labelUp(int sec) {
        if (sec <= OPEN_SEC) return 570;
        int m = (sec + 59) / 60;
        if (m <= 690) return m;
        if (sec <= AM_CLOSE_SEC + 59) return 690;   // 11:30:xx 收盘确认笔归入11:30
        if (sec < PM_OPEN_SEC) return -1;           // 午休
        if (m <= 781) return 781;                   // 13:00:00~13:01:00 → 13:01
        if (m <= 900) return m;
        if (sec <= PM_CLOSE_SEC + 59) return 900;   // 15:00:xx 收盘集合竞价确认笔
        return -1;                                  // 盘后固定价格交易等不并入
    }

    /** 分钟初归属：一笔算进它所在分钟"开始时刻"那个标签（09:30:02 → 09:30） */
    static int labelDown(int sec) {
        if (sec < OPEN_SEC) return 570;
        int m = sec / 60;
        if (m <= 690) return m;
        if (sec < PM_OPEN_SEC) return -1;
        if (m <= 900) return m;
        return -1;
    }

    /** 逐个分时点核对：把分笔按标签累计到该分钟，跟分时曲线的cumVolume比，累计偏差越小说明对齐口径越对 */
    private static Compare compare(Agg agg, List<RealtimeQuoteManager.MinutePoint> points) {
        Compare c = new Compare();
        Iterator<Map.Entry<Integer, long[]>> it = agg.byLabel.entrySet().iterator();
        Map.Entry<Integer, long[]> cur = it.hasNext() ? it.next() : null;
        long cum = 0;
        for (RealtimeQuoteManager.MinutePoint p : points) {
            int lm = parseLabelMin(p.time);
            if (lm < 0) continue;
            while (cur != null && cur.getKey() <= lm) {
                long[] v = cur.getValue();
                cum += v[0] + v[1] + v[2];
                cur = it.hasNext() ? it.next() : null;
            }
            if (lm >= agg.lastLabel) break;   // 这一分钟的分笔可能还没收齐，不参与核对
            if (p.cumVolume <= 0) continue;
            c.count++;
            c.sumErr += Math.abs(cum - p.cumVolume);
            c.refCum = p.cumVolume;
            c.tickCum = cum;
            c.refLabel = lm;
        }
        return c;
    }

    // ══════════════════════════════════════════
    // 小工具
    // ══════════════════════════════════════════

    private static String market(String code) {
        return (code.startsWith("6") || code.startsWith("5")) ? "sh" : "sz";
    }

    private static int parseSec(String t) {
        try {
            String[] p = t.trim().split(":");
            if (p.length < 2) return -1;
            int h = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            int s = p.length > 2 ? Integer.parseInt(p[2]) : 0;
            return h * 3600 + m * 60 + s;
        } catch (Exception e) {
            return -1;
        }
    }

    private static int parseLabelMin(String t) {
        if (t == null) return -1;
        try {
            int i = t.indexOf(':');
            if (i < 0) return -1;
            return Integer.parseInt(t.substring(0, i).trim()) * 60 + Integer.parseInt(t.substring(i + 1).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String labelToStr(int labelMin) {
        return String.format(Locale.US, "%02d:%02d", labelMin / 60, labelMin % 60);
    }

    private static String secToStr(int sec) {
        if (sec < 0) return "";
        return String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
    }

    private static String fmtErr(Compare c) {
        return c.has() ? String.format(Locale.US, "%.0f", c.avgErr()) : "-";
    }

    private static String snippet(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ');
        return one.length() > 120 ? one.substring(0, 120) + "..." : one;
    }

    private static boolean sameDay(long a, long b) {
        Calendar ca = Calendar.getInstance();
        ca.setTimeInMillis(a);
        Calendar cb = Calendar.getInstance();
        cb.setTimeInMillis(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
                && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }
}
