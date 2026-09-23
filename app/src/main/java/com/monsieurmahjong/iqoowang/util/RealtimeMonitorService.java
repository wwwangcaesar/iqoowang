package com.monsieurmahjong.iqoowang.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.monsieurmahjong.iqoowang.MainActivity;
import com.monsieurmahjong.iqoowang.agent.LocalAIAgent;
import com.monsieurmahjong.iqoowang.dao.Position;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RealtimeMonitorService — 实时监控前台服务
 *
 * 流程（2026-08重设：规则引擎独立决定是否推送，AI不再阻塞通知）：
 * 规则引擎(Layer1)命中 → 立即markPending+推送通知+写决策日志 → 同时入队交给
 * AI(Layer2)异步补充定性分析 → AI跑完后回填支持/存疑结论+补一条决策日志，不影响
 * 是否推送这个已经做完的决定。因为本地AI推理慢（单次可能长达几十秒），让它卡在
 * 推送前面会让用户错过规则引擎已经确认成立的买卖机会—基本操作规则必须优先于AI的
 * 分析速度。
 */
public class RealtimeMonitorService extends Service {

    private static final String TAG = "RealtimeMonitorService";
    private static final String CHANNEL_ID = "realtime_monitor";
    private static final String CHANNEL_ID_QUIET = "realtime_monitor_quiet"; // 【R7】T+1锁仓止损/预警的静默降噪通道
    private static final String CHANNEL_ID_FOCUS_WATCH = "realtime_monitor_focus_watch"; // 【R3】进入重点监听的轻量提示，不是买卖决策，用比普通信号更低的优先级
    private static final int NOTIFICATION_ID = 1001;
    private static final int RANKING_NOTIFICATION_ID = 1002; // 【R10】排行榜就绪提醒，固定ID与个股信号的code.hashCode()区开

    public static final String EXTRA_INTERVAL_MS = "interval_ms";
    public static final long DEFAULT_INTERVAL_MS = 120_000; // 2分钟：之前30/60秒轮询过密，与AI复核真实耗时(90-180秒)错开，也降低行情接口频率降低双熔断风险。前端默认选项同步改为2分钟
    public static final long FAST_INTERVAL_MS = 30_000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mIntervalMs = DEFAULT_INTERVAL_MS;
    private final TradingRuleEngine mEngine = new TradingRuleEngine();
    private int mTickCount = 0;
    private final Set<String> mStaleWarnedCodes = new HashSet<>();
    /** 【2026-09-17新增】分时形态"判断不准确"时喊本地AI校验一次的节流——同一支股票哪怕一直处于
     *  ambiguous状态，也不应该每2分钟tick都去抢一次本来只有一份的推理资源，跟真正的买卖
     *  信号复核/水下反转解读挤占同一把锁。 */
    private final java.util.Map<String, Long> mLastIntradayAiCheckAt = new java.util.HashMap<>();
    private static final long INTRADAY_AI_CHECK_COOLDOWN_MS = 15 * 60_000;

    // 断档检测：把每次tick的时间戳存进SharedPreferences，App/服务重启时用来判断
    // 中间是不是隔了太久没监控（被系统杀了、网络断了、或者根本没打开App）
    private static final String PREFS_NAME = "monitor_prefs";
    private static final String KEY_LAST_TICK_AT = "last_tick_at";

    // 并发保护：如果短时间内onStartCommand被重复触发两次（实测确实会发生，决策日志里
    // 每次"监控服务启动"都是成对出现的，且两份"监控快照"股票顺序不一致，说明真的有两轮tick
    // 并发跑了），单靠mHandler.removeCallbacksAndMessages预防不住——它只能取消还没开始执行的回调，
    // 如果第一次的mTick.run()已经开始执行（异步行情请求已经发出），就取消不了了。
    // 现加一层显式的"上一轮tick是否还在跑"旗位，从根上避免两轮tick并发评估同一批股票，
    // 避免产生重复/错乱的日志和潜在的数据竞争。
    private volatile boolean mTickInFlight = false;
    private volatile long mTickStartedAt = 0;
    private static final long TICK_STUCK_THRESHOLD_MS = 90_000; // tick本身只是几个网络请求，不该超过这个时长，超过说明卡住了，强制重置

    // 同样的并发保护也加在onStartCommand自身上——短时间内重复调用就跳过重复初始化，
    // 不再重复重置handler、不再重复写"监控服务启动"日志。
    private volatile long mLastStartCommandAt = 0;
    private static final long START_COMMAND_DEBOUNCE_MS = 2000;

    // 周期性监控快照：不管有没有信号，定期把所有股票的现价/参考价/判断记一笔，
    // 证明监控确实在跑，不能靠“通知栏还在”来判断
    private static final long SNAPSHOT_INTERVAL_MS = 10 * 60_000; // 10分钟一次（2026-08-30从15分钟调整而来）
    private volatile long mLastSnapshotAt = 0;
    // 【R10】收盘前排行榜一天只触发一次——用日期字符串去重，跨天自然重置，与R1年底提醒同一模式
    private volatile String mLastRankDate = "";

    // 行情双源全部失败时的日志节流：断网期间避免每个tick都刷一条，最多10分钟记一次
    private static final long FETCH_FAIL_LOG_THROTTLE_MS = 10 * 60_000;
    private volatile long mLastFetchFailLoggedAt = 0;
    /** AI复核外层超时——实测本地模型单次正常推理需约90秒，与LocalAIAgent内部看门狗(200秒)错开，
     *  外层先放弃、内部看门狗不会提前介入搅乱正常推理。下面的单流水线队列已与tick周期
     *  解耦（见processQueueIfIdle注释），拉长这个阈值不会导致多轮tick堆叠。即使本地模型JNI层面
     *  彻底卡死（既不触发onToken也不触发onFinish），这次信号评估也会有明确的“超时不推送”
     *  结论写进决策日志，而不是静默消失。 */
    private static final long AI_VERIFY_TIMEOUT_MS = 180_000; // 3分钟

    /** 待AI复核队列的一条记录——规则引擎命中后不直接抢锁，而是先进这个队列，
     *  由下面的单流水线处理器按实际推理速度持续消费，不再跟tick的60秒周期绑定。 */
    private static class PendingVerify {
        WatchlistManager.WatchlistItem item;
        String actionKey;
        TradingRuleEngine.RuleResult result;
        RealtimeQuoteManager.Quote quote;
        Position pos;
        long queuedAt;
    }
    /** 按股票代码去重的待复核队列：同一支股票有新数据时直接覆盖旧条目，保持原排队位置不变（
     *  先排队的先处理，不插队）。LinkedHashMap保证插入顺序。
     *  排队多久都不丢弃——哪怕等了很久才轮到，真正发起AI复核前会先用最新行情重新跑一遍规则引擎，
     *  确认信号依然成立才继续——宁可慢一点，也要保证AI看到的是当下真实有效的行情，
     *  而不是排队那一刻的旧快照。 */
    private final java.util.LinkedHashMap<String, PendingVerify> mVerifyQueue = new java.util.LinkedHashMap<>();
    private volatile boolean mVerifyProcessing = false;

    public interface Listener {
        void onSignalTriggered(String code, String name, String action, String note, double price);
        void onTick(int watchCount, int posCount, String timeStr);
    }
    private static volatile Listener sListener;
    public static void setListener(Listener l) { sListener = l; }

    public static void start(Context ctx, long intervalMs) {
        Intent i = new Intent(ctx, RealtimeMonitorService.class);
        i.putExtra(EXTRA_INTERVAL_MS, intervalMs);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, RealtimeMonitorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            mIntervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, DEFAULT_INTERVAL_MS);
        }
        startForeground(NOTIFICATION_ID, buildNotification("监控已启动", "正在获取行情..."));

        long nowMs = System.currentTimeMillis();
        if (nowMs - mLastStartCommandAt < START_COMMAND_DEBOUNCE_MS) {
            Log.i(TAG, "短时间内重复的onStartCommand，跳过重复初始化");
            return START_STICKY;
        }
        mLastStartCommandAt = nowMs;

        mHandler.removeCallbacksAndMessages(null);
        mHandler.post(mTick);
        Log.i(TAG, "监控服务启动，间隔=" + mIntervalMs + "ms");
        DecisionLogger.get().logNote("监控服务启动，间隔=" + (mIntervalMs / 1000) + "秒");

        // 断档检测：如果上次tick距现在超过预期间隔的3倍以上，说明中间很大概率被系统杀掉、
        // 网络中断或者很久没打开App，明确写进日志，别让人以为“通知还在=一直在正常监控”。
        checkAndLogMonitorGap();
        // 顺手清一次超过14天的旧日志，不需要单独的定时任务。
        try { DecisionLogger.get().cleanupOldLogs(); } catch (Exception e) { Log.e(TAG, "清理旧日志失败", e); }

        return START_STICKY;
    }

    /** 把上次tick时间和现在对比，间隔异常大就明确写一条警示进决策日志。
     *  第一次运行（没有历史记录）不报警，避免新装App误报。 */
    private void checkAndLogMonitorGap() {
        android.content.SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long lastTick = sp.getLong(KEY_LAST_TICK_AT, 0);
        if (lastTick <= 0) return;
        long gapMs = System.currentTimeMillis() - lastTick;
        if (gapMs > mIntervalMs * 3) {
            DecisionLogger.get().logNote(String.format(java.util.Locale.CHINA,
                    "⚠️ 监控中断提示：距上次记录已经过去%s（预期间隔%d秒），期间可能被系统后台限制终止、"
                            + "网络中断，或App一直未打开。如经常出现，请检查系统电池优化/后台自启动权限设置。",
                    formatDuration(gapMs), mIntervalMs / 1000));
        }
    }

    private String formatDuration(long ms) {
        long totalMin = ms / 60_000;
        if (totalMin < 60) return totalMin + "分钟";
        long h = totalMin / 60, m = totalMin % 60;
        return h + "小时" + m + "分钟";
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
        Log.i(TAG, "监控服务停止");
        try { DecisionLogger.get().logNote("监控服务停止"); } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            try { doTick(); }
            catch (Exception e) { Log.e(TAG, "tick error", e); }
            finally { mHandler.postDelayed(mTick, mIntervalMs); }
        }
    };

    private void doTick() {
        long tickNow = System.currentTimeMillis();
        if (mTickInFlight) {
            if (tickNow - mTickStartedAt < TICK_STUCK_THRESHOLD_MS) {
                Log.w(TAG, "上一轮tick还在进行中，跳过本次并发触发");
                return;
            }
            Log.w(TAG, "上一轮tick超过" + (TICK_STUCK_THRESHOLD_MS / 1000) + "秒未结束，强制重置继续");
        }
        mTickInFlight = true;
        mTickStartedAt = tickNow;

        mTickCount++;
        // 心跳戳记：无论交易时间内外都写，证明tick自身还在正常跑。
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putLong(KEY_LAST_TICK_AT, System.currentTimeMillis()).apply();

        if (!isWithinTradingHours()) {
            // 非交易时段（晚上/凌晨/周末）：不拉行情、不跑规则评估、不发通知。行情根本没变，
            // 拉了也只是重复收盘那一刻的旧快照，白白消耗网络和电量。只更新一下监控面板
            // 的状态文案，让用户知道监控是正常待命而不是挂了，等下一个交易时段自然恢复。
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(NOTIFICATION_ID, buildNotification("监控待命中",
                    "非交易时段（9:30-11:30, 13:00-15:00），已暂停拉取行情"));
            String timeStr = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date());
            if (sListener != null) {
                int watchCount = WatchlistManager.get().getActiveWatchlist().size();
                int posCount = DatabaseManager.get().getAllPositions().size();
                sListener.onTick(watchCount, posCount, timeStr + "（非交易时段，已暂停）");
            }
            mTickInFlight = false;
            return;
        }

        List<Position> positions = DatabaseManager.get().getAllPositions();
        syncPositionsIntoWatchlist(positions);

        maybeFireCandidateRanking(); // 【R10】每轮tick都检查一下是否到了4:40窗口，内部自己做日期去重，不会重复触发

        List<WatchlistManager.WatchlistItem> watchItems = WatchlistManager.get().getActiveWatchlist();

        Set<String> codeSet = new HashSet<>();
        for (WatchlistManager.WatchlistItem it : watchItems) codeSet.add(it.code);
        for (Position p : positions) codeSet.add(p.getStockCode());
        List<String> codes = new ArrayList<>(codeSet);

        String timeStr = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date());
        updateNotification(watchItems.size(), positions.size(), timeStr);
        if (sListener != null) sListener.onTick(watchItems.size(), positions.size(), timeStr);

        if (codes.isEmpty()) {
            mTickInFlight = false;
            return;
        }

        RealtimeQuoteManager.get().fetchBatch(codes, (quotes, failed) -> {
            if (!failed.isEmpty()) Log.w(TAG, "本轮" + failed.size() + "支行情获取失败: " + failed);
            if (quotes.isEmpty() && !codes.isEmpty()) {
                // 两个行情源全都没拿到任何一支的数据——很可能是双熔断中或网络彻底不通。
                // 之前这种情况只写Logcat，用户看不到，会误以为监控挂了，现写进决策日志（带节流）。
                maybeLogTotalFetchFailure(codes.size());
            }

            // 本轮是否到了写监控快照的时候——不管有没有信号都定期记一笔，证明监控确实在跑。
            // 【B/C重构】之前这里把每支股票的快照行收集进一个共享列表，最后一次性调旧版
            // logSnapshot(list)，这个方法现已标deprecated，内部会把所有行归到code=null的匿名
            // _system文件里，跟"每支股票当天所有监控日志都写进这支股票自己文件"这个核心诉求没对上，
            // 现改成每行产出时（item就在作用域里）直接调 logMonitorLine(item.code, item.name, line)，
            // 不再收批。mLastSnapshotAt改成在确定"本轮确实要写快照"的那一刻就更新，
            // 不再依赖"列表最终有没有内容"这个间接判断。
            boolean dueForSnapshot = System.currentTimeMillis() - mLastSnapshotAt >= SNAPSHOT_INTERVAL_MS;
            if (dueForSnapshot) mLastSnapshotAt = System.currentTimeMillis();

            AtomicInteger pending = new AtomicInteger(watchItems.size());
            if (watchItems.isEmpty()) {
                finishTickBatch();
                return;
            }
            for (WatchlistManager.WatchlistItem item : watchItems) {
                if (isPendingStatus(item.status)) {
                    // 待确认状态：不重新跑规则引擎（避免对同一个已推送信号重复判定/重复弹卡片），
                    // 但仍然要有实时价格快照——之前这里直接跳过，导致一支股票只要挂着未确认，
                    // 就完全从监控日志里消失，哪怕它还有真实仓位暴露在市场风险里。quotes里已经有它的
                    // 行情（codes列表本来就包含了所有持仓代码），不用额外发请求。
                    RealtimeQuoteManager.Quote pendingQuote = quotes.get(item.code);
                    if (pendingQuote != null && dueForSnapshot) {
                        Position pendingPos = DatabaseManager.get().getPositionByCode(item.code);
                        boolean pendingHolding = pendingPos != null && pendingPos.getQuantity() > 0;
                        double pendingHoldCost = pendingHolding ? pendingPos.getAvgCost() : 0;
                        String judgment = String.format(java.util.Locale.CHINA,
                                "待确认·%s（触发价¥%.2f，等待你确认/忽略）",
                                item.pendingAction != null ? item.pendingAction : "?", item.pendingPrice);
                        // 【2026-09-23修复：待确认状态监控快照缺少VWAP/量比/分时形态】之前这里
                        // metrics硬编码传null——不重新跑完整evaluate()是对的（避免重复判定），但连"算指标"
                        // 这个纯计算、不涉及状态变更/买卖判定的部分也一起被跳过了，导致一支信号只要挂着
                        // 没确认（常见几小时甚至一整天），期间的监控快照就完全没有VWAP/水线/量比/分时
                        // 趋势这些用户明确要求要记录的技术指标，跟正常路径(evaluateAndAct→result.metrics)比
                        // 是个信息缺口。用缓存的分时数据（本轮或上一轮已经拉到的，不额外发请求，也不重新
                        // 走一遍会修改state的完整evaluate）算一份只读指标字符串，见TradingRuleEngine.
                        // computeMetricsOnly()注释。
                        List<RealtimeQuoteManager.MinutePoint> pendingCachedPoints =
                                RealtimeQuoteManager.get().getCachedMinuteLine(item.code);
                        String pendingMetrics = mEngine.computeMetricsOnly(
                                item.code, pendingQuote, pendingCachedPoints, mEngine.getPrevDayRef(item.code));
                        String line = buildSnapshotLine(item, pendingQuote, pendingHolding, pendingHoldCost, judgment, pendingMetrics);
                        if (line != null) DecisionLogger.get().logMonitorLine(item.code, item.name, line);
                    }
                    // 【2026-09-16修复：持仓页待确认信号期间止损价消失、分时图不刷新】待确认状态下
                    // 虽然不重新跑规则引擎，但止损价（prevDay.prevLow）和分时图缓存不应该跟着被冻结——
                    // 持仓页看到的止损价来自WatchlistManager的实时指标缓存，而那份缓存之前只在
                    // evaluateAndAct()里更新，待确认状态整段时间都不会调用它，缓存就停在进入待确认
                    // 状态前最后一次的值，甚至可能从未写入过（比如信号在刚入池评估的第一轮就触发了
                    // 止损/预警类分支，而这类分支之前并不总会设置result.waterLine，见下面
                    // evaluateAndAct()里的另一处修复）。getPrevDayRef()只读本地K线缓存，不发网络
                    // 请求，代价很低，这里顺手每轮都刷新一次；waterLine/vwap/volRatio这三项待确认期间
                    // 不重新计算，保留上一次已知值，只把prevLow换成最新的，不无谓地把它们清零。
                    if (pendingQuote != null) {
                        try {
                            TradingRuleEngine.PrevDayRef pendingPrevDay = mEngine.getPrevDayRef(item.code);
                            if (pendingPrevDay.hasData) {
                                double[] prevMetrics = WatchlistManager.get().getLiveMetrics(item.code);
                                double keepWaterLine = prevMetrics != null && prevMetrics.length > 0 ? prevMetrics[0] : 0;
                                double keepVwap = prevMetrics != null && prevMetrics.length > 1 ? prevMetrics[1] : 0;
                                double keepVolRatio = prevMetrics != null && prevMetrics.length > 2 ? prevMetrics[2] : 0;
                                WatchlistManager.get().updateLiveMetrics(item.code, keepWaterLine, keepVwap, keepVolRatio, pendingPrevDay.prevLow);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "待确认状态刷新止损价失败: " + item.code, e);
                        }
                        // 分时图缓存同理，不能因为待确认状态就一直停在进入待确认前的旧快照——
                        // fetchMinuteLine只是拉数据存缓存，不会碰规则引擎/PENDING状态本身，可以
                        // 安全地在待确认分支里也跑，不影响“待确认状态不重新判定信号”这条原则。
                        RealtimeQuoteManager.get().fetchMinuteLine(item.code, new RealtimeQuoteManager.MinuteCallback() {
                            @Override public void onResult(String code, List<RealtimeQuoteManager.MinutePoint> points) {}
                            @Override public void onError(String code, String msg) {
                                // 【2026-09-22修复：待确认状态期间分时数据失败查不到原因】这里之前只写Log.w，
                                // 跟非待确认路径（下面fetchMinuteLine那一处的onError处理）不一致——后者会额外写
                                // 一条到决策日志，这里没有。待确认信号往往会挂好几个小时甚至一整天（等用户点
                                // 确认/忽略），这段时间里分时数据抳取失败会完全从决策日志里消失，只在Logcat能看到，
                                // 用户在自己手机上根本看不到，会误以为“不点确认就不同步监控了”。改成跟非待
                                // 确认路径同款写法，两条路径保持一致。
                                Log.w(TAG, "待确认状态分时数据获取失败 " + code + ": " + msg);
                                try {
                                    DecisionLogger.get().logNote(item.code, item.name,
                                            "【分时数据获取失败】" + msg + "（待确认状态期间，分时图暂时无法更新，下一轮tick自动重试）");
                                } catch (Exception ignored) {}
                            }
                        });
                    }
                    if (pending.decrementAndGet() == 0) finishTickBatch();
                    continue;
                }
                RealtimeQuoteManager.Quote q = quotes.get(item.code);
                if (q == null) {
                    if (pending.decrementAndGet() == 0) finishTickBatch();
                    continue;
                }
                // 所有状态均需分时数据（VWAP/量比/止损追踪）
                RealtimeQuoteManager.get().fetchMinuteLine(item.code, new RealtimeQuoteManager.MinuteCallback() {
                    @Override
                    public void onResult(String code, List<RealtimeQuoteManager.MinutePoint> points) {
                        String line = evaluateAndAct(item, q, points);
                        if (dueForSnapshot && line != null) DecisionLogger.get().logMonitorLine(item.code, item.name, line);
                        if (pending.decrementAndGet() == 0) finishTickBatch();
                    }
                    @Override
                    public void onError(String code, String msg) {
                        // 【2026-09-16修复：分时图无数据排查不到原因】之前这里只写Log.w，只在连着数据线的
                        // Logcat里能看到，用户在自己手机上装的正式版完全看不到——分时图一直空白，却连“到底
                        // 是请求失败还是解析失败”这种最基本的线索都拿不到。现在额外写一条到决策日志（应用内
                        // “决策日志”页面能直接看到），把失败原因(msg)原样带出去。
                        Log.w(TAG, "分时数据获取失败 " + code + ": " + msg);
                        try {
                            DecisionLogger.get().logNote(item.code, item.name,
                                    "【分时数据获取失败】" + msg + "（分时图和依赖分时数据的判断本轮均无法使用，下一轮tick自动重试）");
                        } catch (Exception ignored) {}
                        String line = evaluateAndAct(item, q, null);
                        if (dueForSnapshot && line != null) DecisionLogger.get().logMonitorLine(item.code, item.name, line);
                        if (pending.decrementAndGet() == 0) finishTickBatch();
                    }
                });
            }
        });
    }

    /** 本轮tick所有股票都评估完了才会走到这里，现在只负责重置并发保护旗位，
     *  实际的快照写入已改为每支股票产出时直接调logMonitorLine，不再需要在这里批量写。 */
    private void finishTickBatch() {
        mTickInFlight = false;
    }

    /** 行情双源全部失败时的节流日志——断网期间避免每个tick都刷屏，最多每10分钟记一次。 */
    private void maybeLogTotalFetchFailure(int codeCount) {
        long now = System.currentTimeMillis();
        if (now - mLastFetchFailLoggedAt < FETCH_FAIL_LOG_THROTTLE_MS) return;
        mLastFetchFailLoggedAt = now;
        DecisionLogger.get().logNote(String.format(java.util.Locale.CHINA,
                "⚠️ 本轮行情获取全部失败（腾讯源+新浪源均不可用或正在熔断冷却），%d支股票本轮未评估。"
                        + "如持续出现，请检查网络连接是否正常。", codeCount));
    }

    private void syncPositionsIntoWatchlist(List<Position> positions) {
        for (Position p : positions) {
            if (p.getQuantity() <= 0) continue;
            WatchlistManager.WatchlistItem item = WatchlistManager.get().getByCode(p.getStockCode());
            boolean needsSync = item == null
                    || WatchlistManager.STATUS_WATCHING.equals(item.status)
                    || WatchlistManager.STATUS_STOPPED.equals(item.status)
                    || WatchlistManager.STATUS_REMOVED.equals(item.status);
            if (needsSync) {
                WatchlistManager.get().addIfAbsent(p.getStockCode(), p.getStockName(), 0, "MANUAL_POSITION");
                WatchlistManager.get().markStarter(p.getStockCode(), p.getAvgCost(), "手动买入，自动纳入实时监控");
            }
        }
    }

    private boolean isPendingStatus(String status) {
        return WatchlistManager.STATUS_PENDING_STARTER.equals(status)
                || WatchlistManager.STATUS_PENDING_ADD.equals(status)
                || WatchlistManager.STATUS_PENDING_FULL.equals(status)
                || WatchlistManager.STATUS_PENDING_WARN.equals(status)
                || WatchlistManager.STATUS_PENDING_STOP.equals(status);
    }

    /** 【R10】每个交易日收盘前20分钟（14:40-14:41窗口）触发一次候选池AI排行榜，一天
     *  只触发一次（用日期字符串去重，跨天自然重置，与R1年底提醒同一模式）。模型未就绪或
     *  候选池为空时LocalAIAgent/buildCandidateSnapshotText会直接报错或返回null，这里只静静跳过，
     *  不影响主流程。 */
    private void maybeFireCandidateRanking() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int minutesNow = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
        int closeMinutes = TradingRuleConfig.get().marketCloseHour * 60 + TradingRuleConfig.get().marketCloseMinute;
        // 【D复查修复】原判断窗口只有14:40-14:41整敆1分钟宽，而tick间隔是2分钟——取决于
        // 当天监控服务具体是几点几分启动的，很可能一整天都凑不到一次tick恰好落在这1分钟内，导致
        // 排行榜整天都不会触发，且完全没有任何报错提示，问题很隐蔽。这里改成从14:40起到收盘前
        // 都算在窗口内，靠下面已有的mLastRankDate日期去重来保证"一天只触发一次"，不再依赖
        // "刚好卡在某一分钟"这种脆弱条件。
        if (minutesNow < 14 * 60 + 40 || minutesNow >= closeMinutes) return;
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(new java.util.Date());
        if (today.equals(mLastRankDate)) return;
        mLastRankDate = today;

        final java.util.Map<String, CandidateIndicators> indicators = new java.util.HashMap<>();
        String snapshot = buildCandidateSnapshotText(indicators);
        if (snapshot == null) {
            Log.i(TAG, "候选池排行榜：候选池为空，今日跳过");
            return;
        }
        final String rankDate = today;
        LocalAIAgent.get(getApplicationContext()).rankCandidatesBeforeClose(snapshot, new LocalAIAgent.AICallback() {
            @Override public void onToken(String token) {}
            @Override
            public void onComplete(String fullText) {
                try {
                    String toSave = buildRankingJson(fullText, indicators);
                    DatabaseManager.get().saveCandidateRanking(rankDate, toSave != null ? toSave : fullText);
                    fireRankingReadyNotification(rankDate);
                    DecisionLogger.get().logNote("候选池排行榜已生成，日期" + rankDate);
                } catch (Exception e) {
                    Log.e(TAG, "候选池排行榜落库/通知失败", e);
                }
            }
            @Override
            public void onError(String msg) {
                Log.w(TAG, "候选池排行榜生成失败: " + msg);
            }
        });
    }

    /** 供候选池排行榜用的每支股票结构化技术指标快照，与AI打完分的评分/理由合并存库，这样前端
     *  才能分三栏展示“股票信息/技术指标/AI分析”，不用再把AI返回的整段文本直接摆给用户看。 */
    private static class CandidateIndicators {
        String name, code, holdingNote, techSummary;
        double price, changePct;
        boolean holding;
    }

    /** 把AI输出的“股票：名称(代码)\n评分：N\n理由：文字”逐块文本解析成结构化数组，并且用code跟
     *  buildCandidateSnapshotText同一轮产出的indicators做一次合并——分数/理由来自AI（唯一真正
     *  做排序判断的一方），价格/涨跌幅/技术指标摘要/持仓状态来自程序实时计算（不依赖AI“记得住”
     *  数字），JSON存库后前端就能分“股票信息/技术指标/AI分析”三栏渲染，不用再整段甩给用户读。
     *  任何一块解析失败不影响其他块，最后解析不出任何一条时返回null，调用方回退成保存AI原始
     *  文本，不会让排行榜彻底显示空白。 */
    private String buildRankingJson(String aiText, java.util.Map<String, CandidateIndicators> indicators) {
        if (aiText == null) return null;
        org.json.JSONArray arr = new org.json.JSONArray();
        java.util.regex.Pattern blockPattern = java.util.regex.Pattern.compile(
                "股票[：:]\\s*(.+?)\\(([0-9A-Za-z]+)\\)\\s*[\\r\\n]+评分[：:]\\s*(\\d+)\\s*[\\r\\n]+理由[：:]\\s*(.+?)(?=股票[：:]|$)",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = blockPattern.matcher(aiText);
        while (m.find()) {
            try {
                String name = m.group(1).trim();
                String code = m.group(2).trim();
                int score = Integer.parseInt(m.group(3).trim());
                String reason = m.group(4).trim();
                org.json.JSONObject row = new org.json.JSONObject();
                row.put("name", name);
                row.put("code", code);
                row.put("score", score);
                row.put("aiReason", reason);
                CandidateIndicators ind = indicators != null ? indicators.get(code) : null;
                if (ind != null) {
                    row.put("holdingNote", ind.holdingNote);
                    row.put("techSummary", ind.techSummary);
                    row.put("price", ind.price);
                    row.put("changePct", ind.changePct);
                    row.put("holding", ind.holding);
                }
                arr.put(row);
            } catch (Exception ignored) {
                // 单块解析失败就跳过这一条，不影响其余股票正常显示
            }
        }
        return arr.length() > 0 ? arr.toString() : null;
    }

    /** 【R10】把当前具备买入条件或持仓中的候选股拼成AI能直接读懂的文本快照，全部取自
     *  WatchlistManager已经落库的最新状态（lastNote字段本身就是上一轮evaluate()算好的人话），
     *  不重新发网络请求、不重新跑一遍规则引擎，与tick节奏解耦。返回null表示候选池为空，
     *  调用方不应该再去调AI。 */
    private String buildCandidateSnapshotText(java.util.Map<String, CandidateIndicators> indicatorsOut) {
        List<WatchlistManager.WatchlistItem> items = WatchlistManager.get().getActiveWatchlist();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (WatchlistManager.WatchlistItem item : items) {
            Position pos = DatabaseManager.get().getPositionByCode(item.code);
            boolean holding = pos != null && pos.getQuantity() > 0;
            boolean relevant = holding || isPendingStatus(item.status)
                    || (WatchlistManager.STATUS_WATCHING.equals(item.status)
                        && item.lastNote != null && !item.lastNote.isEmpty());
            if (!relevant) continue;

            // 【核心修复】持仓/T+1状态：明确写成一句话交给AI，不能让AI从status枚举自己猜——之前
            // “已买入、T+1不可卖”这个事实完全没有出现在给AI的文本里，规则引擎的旧理由文案
            // （比如低开路径的“解套确认”）又是站在“要不要买入”的候选股视角写的，两者拼在
            // 一起，AI只能照单全收，把候选股买入理由当成已持仓股票的排名理由输出，不是AI推理错了，
            // 是喂给它的信息本身就没说清楚“这支已经买完、不能再谈买不买了”。
            String holdingNote;
            if (holding) {
                int sellableQty = DatabaseManager.get().getSellableQuantity(item.code);
                int totalQty = pos.getQuantity();
                holdingNote = sellableQty <= 0
                        ? String.format(java.util.Locale.CHINA,
                            "持仓中，%d股全部为今日买入，T+1前不可卖(可卖0股)，此时只需判断是否加仓/继续持有，不要再讨论是否买入", totalQty)
                        : String.format(java.util.Locale.CHINA,
                            "持仓中，%d股可卖%d股，此时只需判断是否加仓/减仓/继续持有，不要再讨论是否买入", totalQty, sellableQty);
            } else {
                holdingNote = "未持仓，仍是候选观察股，可以讨论是否买入";
            }

            RealtimeQuoteManager.Quote q = RealtimeQuoteManager.get().getCachedQuote(item.code);
            List<RealtimeQuoteManager.MinutePoint> minutePoints = RealtimeQuoteManager.get().getCachedMinuteLine(item.code);
            IntradayPatternAnalyzer.IntradayTechnicalSummary techSummary = IntradayPatternAnalyzer.get().summarize(minutePoints);

            // 【核心修复】优先用pendingReason（当前这一刻真正待确认信号的理由，加仓信号触发时就是它，
            // 必要时还经过AI二次校验），不再无条件用lastNote——lastNote只在markStarter/markAdded这类
            // 状态跃迁那一瞬间被写一次，之后哪怕已经有了新的待确认信号，它也不会跟着更新，候选池
            // 排行榜读到的就是几小时甚至今天开盘时“建议底仓”那句旧话。
            String reason = (item.pendingReason != null && !item.pendingReason.isEmpty()) ? item.pendingReason : item.lastNote;

            sb.append(String.format(java.util.Locale.CHINA,
                    "%d. %s(%s)。%s。%s。%s。原始理由：%s\n",
                    ++n, item.name, item.code, holdingNote,
                    q != null ? String.format(java.util.Locale.CHINA, "现价¥%.2f，涨跌幅%.2f%%", q.price, q.changePct) : "现价数据暂缺",
                    techSummary.summaryText,
                    reason != null ? reason : "暂无"));

            if (indicatorsOut != null) {
                CandidateIndicators ind = new CandidateIndicators();
                ind.name = item.name; ind.code = item.code;
                ind.holding = holding; ind.holdingNote = holdingNote;
                ind.techSummary = techSummary.summaryText;
                if (q != null) { ind.price = q.price; ind.changePct = q.changePct; }
                indicatorsOut.put(item.code, ind);
            }
        }
        return n == 0 ? null : sb.toString();
    }

    /** 【R10，留痕修复】排行榜生成完毕的醒目提示——复用普通高优先级通道，固定通知ID避免跟
     *  个股信号的code.hashCode()碰撞。点击后直接跳转到候选池排行榜页面并定位到这一天
     *  （之前点击只是打开App、停留在原来的页面，找不到任何入口能再看一次，这里带上
     *  MainActivity.EXTRA_OPEN_PAGE/EXTRA_RANKING_DATE两个extra，由MainActivity负责
     *  在onNewIntent/冷启动路径里转发给WebView）。 */
    private void fireRankingReadyNotification(String rankDate) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        String title = "📊 今日候选池排行榜已生成";
        String content = "收盘前20分钟AI横向打分完成，点击查看";
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.putExtra(MainActivity.EXTRA_OPEN_PAGE, "candidate_ranking");
        tapIntent.putExtra(MainActivity.EXTRA_RANKING_DATE, rankDate);
        PendingIntent pi = PendingIntent.getActivity(this, RANKING_NOTIFICATION_ID, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(RANKING_NOTIFICATION_ID, n);
    }

    /**
     * 沪深A股常规交易时段：9:30-11:30、13:00-15:00（午休时段自然排除在外），周一到周五，
     * 且不是法定节假日。
     * 【R1修复】之前只判断周几，不接入节假日日历——节假日当天虽然不会真的误发信号
     * （反正行情源本来也不会有新数据），但会白白发起网络请求耗电，现在直接用
     * TradingCalendar跳过，顺带也让"交易时段"这个概念在全项目里口径一致。
     */
    private boolean isWithinTradingHours() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        if (!TradingCalendar.get().isTradingDay(cal)) return false;
        int minutesNow = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
        boolean morning = minutesNow >= 9 * 60 + 30 && minutesNow <= 11 * 60 + 30;
        boolean afternoon = minutesNow >= 13 * 60 && minutesNow <= 15 * 60;
        return morning || afternoon;
    }

    private String evaluateAndAct(WatchlistManager.WatchlistItem item, RealtimeQuoteManager.Quote quote,
                                 List<RealtimeQuoteManager.MinutePoint> minutePoints) {
        TradingRuleEngine.PrevDayRef prevDay = mEngine.getPrevDayRef(item.code);
        TradingRuleEngine.DivergenceState trackState = WatchlistManager.get().loadTrackState(item);
        TradingRuleEngine.PatternRef pattern = WatchlistManager.get().loadPatternRef(item);
        TradingRuleEngine.RuleResult result = mEngine.evaluate(
                item.code, item.status, quote, minutePoints, prevDay, trackState, pattern);

        // 【2026-09-17新增，对应用户要求"简单代码判断不准确时使用本地ai校验"】规则引擎自己算出的
        // 分时趋势/量能/反转三个信号若互相矛盾，说明规则本身判断不准，此时额外喊一次本地AI
        // 结合具体数字再看一眼，不等往后走到是否命中买卖action那一步——即使本轮没有任何
        // action触发（纯观察中的候选池股票），分时形态本身的可信度也同样值得校验。纯事后定性
        // 补充，不影响已经算出的result本身，不阻塞本轮tick。
        if (result.intradaySummary != null && result.intradaySummary.ambiguous) {
            maybeVerifyIntradayPattern(item, quote, result.intradaySummary);
        }

        if (result.stateUpdate != null) {
            WatchlistManager.get().saveTrackState(item.code, result.stateUpdate);
        }
        // 【2026-09-16修复：持仓页止损价有时不显示】原先只在result.waterLine>0时才缓存这几个结构化
        // 指标（含prevLow止损价），但buildSnapshotLine方法自己的注释就写着“result.vwap/waterLine这两个
        // 结构化double字段在部分fallthrough分支里没被设置”——也就是说有些分支（比如
        // WARN_PRESSURE等止损相关分支）走到这里时waterLine可能确实是0，止损价就跟着一起被漏更新，
        // 即使prevDay.prevLow本身明明是有效数据。止损价是否可用只应该取决于prevDay本身有没有拿到
        // 数据，不该被“这一轮具体走到了哪个判断分支”连带影响。
        if (prevDay.hasData) {
            // 【用户已明确要求】额外传入 prevDay.prevLow（前一交易日最低价），现在它就是独立破位
            // 止损参照价，必须在候选池/持仓列表里醒目展示，不能只藏在note文字里。
            WatchlistManager.get().updateLiveMetrics(item.code, result.waterLine, result.vwap, result.volRatio, prevDay.prevLow);
        }

        Position pos = DatabaseManager.get().getPositionByCode(item.code);
        boolean holding = pos != null && pos.getQuantity() > 0;
        double holdCost = holding ? pos.getAvgCost() : 0;

        if (result.action == TradingRuleEngine.Action.NONE) {
            WatchlistManager.get().updateNote(item.code, result.note);
            if (result.note != null && result.note.contains("安全拦截") && mStaleWarnedCodes.add(item.code)) {
                Log.w(TAG, "【陈旧数据拦截】" + item.name + "(" + item.code + ") " + result.note);
                DecisionLogger.get().logNote(item.name + "(" + item.code + ") " + result.note);
            }
            // 【R3新增】首次进入"重点监听"：推送一条轻量提示，不走markPending（不是买卖决策，
            // 不需要用户确认/忽略），只是告知
            if (result.focusWatchJustEntered) {
                try {
                    fireFocusWatchNotification(item.code, item.name, result.note, quote.price);
                } catch (Exception e) {
                    Log.e(TAG, "发送重点监听提示失败", e);
                }
            }
            // 【分时经验规则步骤4新增】水下反转检测到：先写决策日志+推送轻量通知（客观数字，立即
            // 可用），AI"完整分析"异步跑，不阻塞。不影响本轮tick的其他处理。
            if (result.underwaterReversal != null) {
                try {
                    handleUnderwaterReversal(item, quote, prevDay, result.underwaterReversal);
                } catch (Exception e) {
                    Log.e(TAG, "处理水下反转信号失败", e);
                }
            }
            // 观察中的候选股：本轮无买卖信号时，检查是否该自动移出观察池
            if (WatchlistManager.STATUS_WATCHING.equals(item.status) && !holding) {
                String staleReason = checkStaleCandidate(item, quote, prevDay, result);
                if (staleReason != null) {
                    WatchlistManager.get().autoRemoveStale(item.code, staleReason);
                    DecisionLogger.get().logNote(item.name + "(" + item.code + ") 自动移出观察池：" + staleReason);
                }
            }
            return buildSnapshotLine(item, quote, holding, holdCost, result.note, result.metrics);
        }

        applyT1Note(result, pos, holding);
        applyCostBasisNote(result, pos, holding, holdCost, quote); // 【R6】

        // 二级止损中点：未到收盘前推送窗口 → 只更新备注，不推送、不入AI队列
        if (!shouldNotifyNow(result)) {
            WatchlistManager.get().updateNote(item.code, result.note);
            Log.i(TAG, "规则命中但不在推送窗口: " + item.code + " " + result.actionLabel);
            return buildSnapshotLine(item, quote, holding, holdCost, result.actionLabel + "（未到推送窗口，暂缓）", result.metrics);
        }

        String actionKey = TradingRuleEngine.actionToKey(result.action);
        Log.i(TAG, "【规则命中·立即推送】" + item.name + "(" + item.code + ") " + result.actionLabel);

        WatchlistManager.get().markPending(item.code, actionKey, result.triggerPrice, result.note);

        try {
            DecisionLogger.get().logRulePush(item.name, item.code, holding, holdCost,
                    quote.price, item.status, result);
        } catch (Exception e) {
            Log.e(TAG, "写规则推送日志失败", e);
        }

        // 【R7，用户已确认"静默通知+角标"方案】止损/预警命中，但持仓100%是今日买入、T+1前
        // 完全不能卖时，今天实际上什么都做不了，仍用平时高优先级+三连震动纯属打扰，改走静默低
        // 优先级通道。
        boolean isSellSignal = result.action == TradingRuleEngine.Action.STOP_LOSS
                || result.action == TradingRuleEngine.Action.WARN_PRESSURE;
        boolean fullyT1Locked = isSellSignal && holding
                && DatabaseManager.get().getSellableQuantity(item.code) <= 0;
        if (fullyT1Locked) {
            fireQuietAlert(item.code, item.name, result.actionLabel, result.note, result.triggerPrice);
        } else {
            fireAlert(item.code, item.name, result.actionLabel, result.note, result.triggerPrice);
        }
        if (sListener != null) {
            sListener.onSignalTriggered(item.code, item.name, result.actionLabel, result.note, result.triggerPrice);
        }

        enqueueVerification(item, actionKey, result, quote, pos);
        return buildSnapshotLine(item, quote, holding, holdCost, result.actionLabel + "（已推送，待AI复核）", result.metrics);
    }

    /** 拼一支股票的监控快照摘要行——现价、对比参考价（持仓成本）、当前判断，
     *  给周期性快照日志用，就是用户说的"13:20 太极集团 15.23 对比买入价格15.43"那种格式。
     *  quote为null时返回null（没数据不写这一行）。
     *  【D复查时新增】metrics参数：之前这里只把result.note当做judgment拼进去，而note里
     *  是否提到VWAP/水线完全取决于触发这条note的具体代码分支——绝大多数分支确实会提，
     *  但不是强制保证。现让每行监控快照都额外固定拼上 evaluate() 开头统一算好的
     *  result.metrics结构化字符串（含VWAP/水线/量比，不管走到哪个分支都会被设置，不像
     *  result.vwap/waterLine那两个结构化double字段在部分fallthrough分支里没被设置），
     *  不再依赖每个代码分支的note文本恰好提到了VWAP这件事本身，对应用户“分时均价等主要
     *  信息一定要记录到位”的要求。metrics为空时不加这段（兼容旧调用方不传的情况）。 */
    private String buildSnapshotLine(WatchlistManager.WatchlistItem item, RealtimeQuoteManager.Quote quote,
                                      boolean holding, double holdCost, String judgment, String metrics) {
        if (quote == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(item.name).append("(").append(item.code).append(") 现价¥")
                .append(String.format(java.util.Locale.CHINA, "%.2f", quote.price));
        if (holding && holdCost > 0) {
            double pnlPct = (quote.price - holdCost) / holdCost * 100;
            sb.append(String.format(java.util.Locale.CHINA, "，对比成本¥%.2f（%s%.2f%%）",
                    holdCost, pnlPct >= 0 ? "浮盈" : "浮亏", Math.abs(pnlPct)));
        }
        sb.append(" ｜ ").append(judgment != null && !judgment.isEmpty() ? judgment : "观望");
        if (metrics != null && !metrics.isEmpty()) {
            sb.append(" ｜ 指标：").append(metrics);
        }
        return sb.toString();
    }

    /** 【2026-09-17新增，对应用户要求"分时数据简单代码判断不准确时使用本地ai校验，不要为了应付而
     *  回答"】规则计算出的趋势/反转/量能信号互相矛盾时，喊本地AI结合现价/量能再看一眼，
     *  结果写进这支股票的决策日志（"分时"类型标签）。这是纯粹的事后定性补充，不影响已经
     *  算出的result本身、不阻塞本轮tick、拿不到推理锁就安静跳过（不算错误，只是这一轮没抓到，
     *  下次ambiguous再触发时会重试）。15分钟节流，避免同一支股票持续ambiguous时每2分钟tick
     *  都去抢推理资源。 */
    private void maybeVerifyIntradayPattern(WatchlistManager.WatchlistItem item, RealtimeQuoteManager.Quote quote,
                                             IntradayPatternAnalyzer.IntradayTechnicalSummary summary) {
        long now = System.currentTimeMillis();
        Long lastAt = mLastIntradayAiCheckAt.get(item.code);
        if (lastAt != null && now - lastAt < INTRADAY_AI_CHECK_COOLDOWN_MS) return;
        mLastIntradayAiCheckAt.put(item.code, now);
        LocalAIAgent.get(getApplicationContext()).verifyIntradayPattern(item.code, item.name,
                summary.summaryText, summary.ambiguousReason, quote,
                new LocalAIAgent.AICallback() {
                    @Override public void onToken(String token) {}
                    @Override
                    public void onComplete(String fullText) {
                        try {
                            DecisionLogger.get().logIntradayAnalysis(item.code, item.name,
                                    "【分时形态校验】规则判断存疑：" + summary.ambiguousReason
                                            + "\n  规则摘要：" + summary.summaryText
                                            + "\n  本地AI校验：" + fullText);
                        } catch (Exception e) {
                            Log.e(TAG, "写分时形态AI校验日志失败", e);
                        }
                    }
                    @Override
                    public void onError(String msg) {
                        Log.i(TAG, "分时形态AI校验未执行（" + msg + "），本轮跳过，下次ambiguous再触发时重试");
                    }
                });
    }

    /** T+1 同日保护：卖出类信号标注可卖/锁定股数（操盘手经验终版.md 5.2节） */
    private void applyT1Note(TradingRuleEngine.RuleResult result, Position pos, boolean holding) {
        if (!holding || pos == null) return;
        boolean isSellAction = result.action == TradingRuleEngine.Action.STOP_LOSS
                || result.action == TradingRuleEngine.Action.WARN_PRESSURE;
        if (!isSellAction) return;
        int sellableQty = DatabaseManager.get().getSellableQuantity(pos.getStockCode());
        int lockedQty = Math.max(0, pos.getQuantity() - sellableQty);
        if (lockedQty <= 0) return;
        if (sellableQty <= 0) {
            result.note += String.format(java.util.Locale.CHINA,
                    "；⚠️持仓全部%d股均为今日买入，T+1前不可卖出，若确需止损只能等下一交易日开盘执行", pos.getQuantity());
        } else {
            result.note += String.format(java.util.Locale.CHINA,
                    "；⚠️持仓%d股中有%d股为今日买入尚不可卖，实际可执行卖出%d股",
                    pos.getQuantity(), lockedQty, sellableQty);
        }
    }

    /** 【R6，用户已确认方案A】一级预警(WARN_PRESSURE)是纯粹按"相对昨收的峰值涨幅回撤"算的，
     *  跟持仓人实际的买入成本完全无关——同样一条预警，可能对应你实际浮盈、也可能对应你实际
     *  已经浮亏，光看这条预警本身分不清楚。这里不改变WARN_PRESSURE本身的触发条件（那是
     *  操盘手经验原文3.3节的定义，不该被个人成本干扰），只是在文案里追加一行"你的实际
     *  持仓浮盈亏"，两个口径并排放着，自己判断这次预警是"股票本身走弱"还是"叠加了
     *  买入价偏高的错觉"。 */
    private void applyCostBasisNote(TradingRuleEngine.RuleResult result, Position pos,
                                     boolean holding, double holdCost, RealtimeQuoteManager.Quote quote) {
        if (!holding || pos == null || holdCost <= 0 || quote == null) return;
        if (result.action != TradingRuleEngine.Action.WARN_PRESSURE) return;
        double pnlPct = (quote.price - holdCost) / holdCost * 100;
        result.note += String.format(java.util.Locale.CHINA,
                "；你的实际持仓：成本¥%.2f，现价¥%.2f，%s%.2f%%",
                holdCost, quote.price, pnlPct >= 0 ? "浮盈" : "浮亏", Math.abs(pnlPct));
    }

    /**
     * 候选股形态失效自动清理。prevYangLow 必须读本轮 evaluate 刚算出的 stateUpdate，
     * 不能 loadTrackState() 读旧值——否则会比实际情况慢一拍。
     */
    private String checkStaleCandidate(WatchlistManager.WatchlistItem item, RealtimeQuoteManager.Quote quote,
                                        TradingRuleEngine.PrevDayRef prevDay,
                                        TradingRuleEngine.RuleResult result) {
        if (!prevDay.hasData || quote == null) return null;
        TradingRuleConfig cfg = TradingRuleConfig.get();
        double waterLine = prevDay.prevClose;

        // 条件1：大幅跌破水线（非正常回踩）
        if (waterLine > 0 && quote.price < waterLine * (1.0 - cfg.candidateDeepBreakPct)) {
            return String.format(java.util.Locale.CHINA,
                    "现价¥%.2f大幅跌破水线¥%.2f超过%.0f%%，形态失效",
                    quote.price, waterLine, cfg.candidateDeepBreakPct * 100);
        }

        // 条件2：跌破前一根阳线最低价（用本轮刚追踪到的值）
        double yangLow = result.stateUpdate != null ? result.stateUpdate.prevYangLow : 0;
        if (yangLow > 0 && quote.price < yangLow) {
            return String.format(java.util.Locale.CHINA,
                    "现价¥%.2f跌破前阳低¥%.2f，支撑失效", quote.price, yangLow);
        }

        // 条件3：观察天数超限仍未触发底仓
        int observeDays = computeObserveDays(item.addedDate);
        if (observeDays >= cfg.candidateMaxObserveDays) {
            return String.format(java.util.Locale.CHINA,
                    "观察%d天（上限%d天）仍未出现底仓信号，仙人指路预期落空",
                    observeDays, cfg.candidateMaxObserveDays);
        }
        return null;
    }

    /**
     * 从入池日期到今天的交易日数（含首尾）。
     * 【修复】原实现按日历天数计算（两个日期相减/一天毫秒数），但trading_rules.json的
     * candidateMaxObserveDays注释、TradingRuleConfig.java字段注释都明确写的是"交易日"，
     * 按日历天数算会把周末也计入观察期——跨一个周末的候选股会比文档口径提前1~2天
     * 被判定超时移出观察池。
     * 【R1修复】之前只排除周末，不识别法定节假日，跨春节/国庆这类多日假期时同样会
     * 提前误判超时。现改用TradingCalendar逐日判断是否为真实交易日，与本类
     * isWithinTradingHours()、MarketDataManager.computeExpectedTradeDate()口径统一。
     */
    private int computeObserveDays(String addedDate) {
        if (addedDate == null || addedDate.isEmpty()) return 0;
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
            java.util.Date added = fmt.parse(addedDate);
            if (added == null) return 0;
            java.util.Calendar cursor = java.util.Calendar.getInstance();
            cursor.setTime(added);
            clearTimeFields(cursor);
            java.util.Calendar today = java.util.Calendar.getInstance();
            clearTimeFields(today);
            if (cursor.after(today)) return 0;
            int tradeDays = 0;
            while (!cursor.after(today)) {
                if (TradingCalendar.get().isTradingDay(cursor)) tradeDays++;
                cursor.add(java.util.Calendar.DAY_OF_MONTH, 1);
            }
            return tradeDays;
        } catch (Exception e) {
            return 0;
        }
    }

    private void clearTimeFields(java.util.Calendar cal) {
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
    }

    /** 规则命中后入队，不直接抢锁。同一股票有新数据就覆盖旧条目，排队位置不变。 */
    private synchronized void enqueueVerification(WatchlistManager.WatchlistItem item, String actionKey,
                                                   TradingRuleEngine.RuleResult result,
                                                   RealtimeQuoteManager.Quote quote, Position pos) {
        PendingVerify pv = new PendingVerify();
        pv.item = item; pv.actionKey = actionKey; pv.result = result; pv.quote = quote; pv.pos = pos;
        pv.queuedAt = System.currentTimeMillis();
        mVerifyQueue.put(item.code, pv);
        processQueueIfIdle();
    }

    /**
     * 单流水线队列处理器：上一条处理完（无论支持/存疑/超时）就立刻取下一条，
     * 不再受tick的60秒周期限制，AI多快就处理多快。注意：现在通知已经在规则命中那一刻
     * 立即发出了，这个队列纯粹是让AI事后补一段定性分析，不影响是否推送这个已经做完的
     * 决定，所以不需要再按排队时长丢弃任何条目——哪怕排很久才轮到，AI用当初规则命中
     * 那刻的数据做分析也无妨，只是事后参考。
     */
    private synchronized void processQueueIfIdle() {
        if (mVerifyProcessing || mVerifyQueue.isEmpty()) return;
        java.util.Iterator<java.util.Map.Entry<String, PendingVerify>> it = mVerifyQueue.entrySet().iterator();
        java.util.Map.Entry<String, PendingVerify> entry = it.next();
        PendingVerify next = entry.getValue();
        it.remove();
        mVerifyProcessing = true;
        doVerify(next);
    }

    /** 一条处理完成（无论确认/驳回/超时）都会调这个，紧接着尝试取下一条，形成持续流水线 */
    private void onVerifyDone() {
        synchronized (this) { mVerifyProcessing = false; }
        processQueueIfIdle();
    }

    /** 实际发起一次AI复核调用——逻辑与之前直接内联在evaluateAndAct里的一样，只是改为从队列取数据，
     *  并在所有完成分支都改调onVerifyDone()推进队列，而不是直接return。 */
    private void doVerify(PendingVerify pv) {
        WatchlistManager.WatchlistItem item = pv.item;
        String actionKey = pv.actionKey;
        TradingRuleEngine.RuleResult result = pv.result;
        RealtimeQuoteManager.Quote quote = pv.quote;
        Position pos = pv.pos;

        final boolean[] handled = {false};
        Runnable timeoutRunnable = () -> {
            if (handled[0]) return;
            handled[0] = true;
            Log.w(TAG, "AI复核超时(" + (AI_VERIFY_TIMEOUT_MS / 1000) + "秒无响应): " + item.code);
            LocalAIAgent.VerifyResult vr = new LocalAIAgent.VerifyResult();
            vr.confirmed = false;
            vr.reason = "AI复核超时无响应，不影响已推送的规则信号";
            vr.fullText = "AI_TIMEOUT";
            handleAiAnalysisComplete(item, result, vr, vr.fullText);
            onVerifyDone();
        };
        mHandler.postDelayed(timeoutRunnable, AI_VERIFY_TIMEOUT_MS);

        LocalAIAgent.get(getApplicationContext()).verifySignal(
                item.code, item.name, actionKey, result.actionLabel, result.note, result.metrics, quote, pos,
                new LocalAIAgent.AICallback() {
                    @Override public void onToken(String token) {}

                    @Override
                    public void onComplete(String fullText) {
                        if (handled[0]) return;
                        handled[0] = true;
                        mHandler.removeCallbacks(timeoutRunnable);
                        LocalAIAgent.VerifyResult vr = LocalAIAgent.parseVerifyResult(fullText);
                        handleAiAnalysisComplete(item, result, vr, fullText);
                        onVerifyDone();
                    }

                    @Override
                    public void onError(String msg) {
                        if (handled[0]) return;
                        handled[0] = true;
                        mHandler.removeCallbacks(timeoutRunnable);
                        Log.w(TAG, "AI补充分析不可用: " + msg);
                        LocalAIAgent.VerifyResult vr = new LocalAIAgent.VerifyResult();
                        vr.confirmed = false;
                        vr.reason = "AI未能完成补充分析（" + msg + "），规则推送仍然有效，请自行判断";
                        vr.fullText = "AI_ERROR: " + msg;
                        handleAiAnalysisComplete(item, result, vr, vr.fullText);
                        onVerifyDone();
                    }
                });
    }

    /**
     * AI 异步分析完成：回填 pending 记录的 AI 结论 + 写补充日志，不再决定要不要初始通知
     * （初始通知已在规则命中那一刻发出）。但AI明确支持并给出具体操作建议时，这本身就是
     * 值得推送的新信息，不能只能靠用户自己点开App才发现——补一条确认通知。存疑不补通知，
     * 那本来就是不确定、自己判断，补通知反而是噪声。用户若在 AI 完成前已确认/忽略，updatePendingAiResult 会跳过。
     */
    private void handleAiAnalysisComplete(WatchlistManager.WatchlistItem item,
                                           TradingRuleEngine.RuleResult result,
                                           LocalAIAgent.VerifyResult vr, String aiFullText) {
        WatchlistManager.get().updatePendingAiResult(
                item.code, vr.confirmed, vr.reason, aiFullText);

        try {
            DecisionLogger.get().logAiSupplement(
                    item.name, item.code, result.actionLabel,
                    vr.confirmed, vr.reason, aiFullText);
        } catch (Exception e) {
            Log.e(TAG, "写AI补充日志失败", e);
        }

        if (vr.confirmed) {
            try {
                fireConfirmedAlert(this, item.code, item.name, result.actionLabel, vr.reason, result.triggerPrice);
            } catch (Exception e) {
                Log.e(TAG, "发送AI确认通知失败", e);
            }
        }

        Log.i(TAG, "AI补充分析完成: " + item.code + " "
                + result.actionLabel + " " + (vr.confirmed ? "支持" : "存疑"));
    }

    private boolean shouldNotifyNow(TradingRuleEngine.RuleResult result) {
        if (result.action == TradingRuleEngine.Action.STOP_LOSS) {
            return result.notifyImmediate;
        }
        return true;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "实时监控",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("规则引擎触发的买入/加仓/满仓/止损信号（AI异步补充分析，需手动确认）");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        // 【R7，用户已确认"静默通知+角标"方案】Android 8+的通知优先级主要由Channel的
        // importance决定，单靠Notification.Builder.setPriority()不够，所以单独开一个低重要度、
        // 关闭震动的渠道，给T+1完全锁仓、今天实际上不可能操作的止损/预警用。
        if (nm.getNotificationChannel(CHANNEL_ID_QUIET) == null) {
            NotificationChannel chQuiet = new NotificationChannel(CHANNEL_ID_QUIET, "实时监控·静默提醒",
                    NotificationManager.IMPORTANCE_LOW);
            chQuiet.setDescription("T+1锁仓、今日无法实际操作的止损/预警提示（不震动、不弹出）");
            chQuiet.enableVibration(false);
            nm.createNotificationChannel(chQuiet);
        }
        // 【R3新增】高开/平开候选股进入"重点监听"的轻量提示——不是买卖决策，不需要用户
        // 确认/忽略，用比普通买卖信号更低的优先级、更轻的单次震动区分。
        if (nm.getNotificationChannel(CHANNEL_ID_FOCUS_WATCH) == null) {
            NotificationChannel chFocus = new NotificationChannel(CHANNEL_ID_FOCUS_WATCH, "实时监控·重点监听",
                    NotificationManager.IMPORTANCE_LOW);
            chFocus.setDescription("高开/平开候选股回踩分时均价、进入重点监听计时的提示（非买卖决策）");
            chFocus.enableVibration(true);
            chFocus.setVibrationPattern(new long[]{0, 150});
            nm.createNotificationChannel(chFocus);
        }
    }

    private Notification buildNotification(String title, String content) {
        Intent tapIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(int watchCount, int posCount, String timeStr) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        String content = String.format(java.util.Locale.CHINA,
                "观察%d支 · 持仓%d支 · %s更新 · 第%d轮", watchCount, posCount, timeStr, mTickCount);
        nm.notify(NOTIFICATION_ID, buildNotification("实时监控运行中", content));
    }

    private void fireAlert(String code, String name, String actionLabel, String ruleReason, double price) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        String title = "🔔 " + name + "(" + code + ") " + actionLabel + " · 待确认";
        String content = String.format(java.util.Locale.CHINA, "¥%.2f · %s\n规则引擎已触发，AI分析中，点击App确认", price, ruleReason);

        Intent tapIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, code.hashCode(), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(code.hashCode(), n);

        try {
            Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 200, 100, 200, 100, 200}, -1));
                } else {
                    vib.vibrate(new long[]{0, 200, 100, 200, 100, 200}, -1);
                }
            }
        } catch (Exception ignored) {}
    }

    /** 【R7，用户已确认"静默通知+角标"方案】与 fireAlert 基本一致，只换了两处：投到
     *  CHANNEL_ID_QUIET（低重要度）而不是普通频道，且不调用震动——这是本次降噪的核心diff。
     *  标题/文案里直接说明"今日买入，T+1前不可卖"，避免用户看到提示后误以为现在就能卖。
     *  角标本身依赖前端读候选池PENDING状态自行展示，后端数据已经够用，本次不改前端。 */
    private void fireQuietAlert(String code, String name, String actionLabel, String ruleReason, double price) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        String title = name + "(" + code + ") " + actionLabel + " · 待确认（今日买入，T+1前不可卖）";
        String content = String.format(java.util.Locale.CHINA, "¥%.2f · %s\n今天暂时无法实际卖出，仅供参考，明日开盘再处理", price, ruleReason);

        Intent tapIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, code.hashCode(), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID_QUIET)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        nm.notify(code.hashCode(), n);
        // 故意不震动：与fireAlert最大的区别就在这里——既然今天实际卖不掉，就不用震动打扰。
    }

    /** 【R3新增】高开/平开候选股首次进入"重点监听"时的轻量提示——不是买卖决策，不走
     *  markPending/PENDING状态位，不需要用户确认/忽略，只是告知已进入计时观察。用独立的
     *  低优先级通道+轻震动，跟"🔔待确认"（三连震动）、"✅AI已确认"（单长震动）区分开，
     *  复用同一个通知ID（code.hashCode()），保持同一支股票的通知在通知栏里是同一条、
     *  不随后续tick反复刷屏。 */
    private void fireFocusWatchNotification(String code, String name, String note, double price) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        String title = "👀 " + name + "(" + code + ") 进入重点监听";
        String content = String.format(java.util.Locale.CHINA, "¥%.2f · %s", price, note);

        Intent tapIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, code.hashCode(), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID_FOCUS_WATCH)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        nm.notify(code.hashCode(), n);
    }

    /** 【分时经验规则步骤4新增】水下V型反转：先写决策日志+推送轻量通知（客观数字，立即可用），
     *  AI"完整分析"异步跑，跑完后追加进同一支股票的决策日志（不新开一条，保持"点开一支
     *  股票看完整时间线"的体验），不阻塞初始推送。对应用户原话"将所有关键信息都列出来，比如
     *  昨日最低价，水线价格，现在的分时均价价格，并将本地ai针对当前分时图的分析内容完整展示"，
     *  contextText就是那几个关键数字，通知、日志、AI prompt三处共用同一份，保证口径一致。 */
    private void handleUnderwaterReversal(WatchlistManager.WatchlistItem item, RealtimeQuoteManager.Quote quote,
                                           TradingRuleEngine.PrevDayRef prevDay,
                                           IntradayPatternAnalyzer.VReversal r) {
        String contextText = String.format(java.util.Locale.CHINA,
                "昨日最低价¥%.2f，水线（昨收）¥%.2f，反转点价格¥%.2f，反转点量能比%.2fx（相对前5-10分钟均量），"
                        + "现价¥%.2f，反转后已维持%d分钟未破位。",
                prevDay.prevLow, prevDay.prevClose, r.extremePrice, r.volRatio, quote.price, r.sustainMinutes);

        // 【修复】原本传的是两个参数（拼好的"name(code)"字符串 + 内容），但DecisionLogger.
        // logNote只有(code, name, text)三参数和(text)单参数两个重载，没有两参数版本，会直接
        // 编译失败。改为正确的三参数调用。
        DecisionLogger.get().logNote(item.code, item.name, "【水下反转检测】" + contextText);
        fireUnderwaterReversalNotification(item.code, item.name, contextText, quote.price);

        LocalAIAgent.get(getApplicationContext()).analyzeIntradayReversal(item.code, item.name, contextText,
                new LocalAIAgent.AICallback() {
                    @Override public void onToken(String token) {}
                    @Override
                    public void onComplete(String fullText) {
                        try {
                            DecisionLogger.get().logNote(item.code, item.name, "【水下反转·AI完整分析】" + fullText);
                            updateUnderwaterReversalNotification(item.code, item.name, quote.price, fullText);
                        } catch (Exception e) {
                            Log.e(TAG, "追加水下反转AI分析日志失败", e);
                        }
                    }
                    @Override
                    public void onError(String msg) {
                        Log.w(TAG, "水下反转AI分析失败: " + msg);
                    }
                });
    }

    private int underwaterNotifId(String code) {
        return ("uw_" + code).hashCode();
    }

    /** 复用CHANNEL_ID_FOCUS_WATCH（同样是信息展示、非交易动作性质），但通知ID跟
     *  fireFocusWatchNotification区分开（用"uw_"+code算hash），避免一支股票同时处于
     *  "重点监听"又"水下反转"时两条通知互相覆盖。 */
    private void fireUnderwaterReversalNotification(String code, String name, String contextText, double price) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        String title = "🔍 " + name + "(" + code + ") 水下检测到反转形态";
        String content = String.format(java.util.Locale.CHINA, "¥%.2f · %s（AI分析中，稍后更新）", price, contextText);

        Intent tapIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, underwaterNotifId(code), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID_FOCUS_WATCH)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(underwaterNotifId(code), n);
    }

    /** AI完整分析跑完后更新同一条通知，不新增一条刷屏。 */
    private void updateUnderwaterReversalNotification(String code, String name, double price, String aiFullText) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        String title = "🔍 " + name + "(" + code + ") 水下反转·AI分析已就绪";
        String content = String.format(java.util.Locale.CHINA, "¥%.2f · %s", price, aiFullText);

        Intent tapIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, underwaterNotifId(code), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID_FOCUS_WATCH)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(underwaterNotifId(code), n);
    }

    /**
     * AI明确支持时的补充确认通知——跟fireAlert()共用同一个通知ID（code.hashCode()），
     * 会直接覆盖掉原来那条"待确认，AI分析中"的通知，用更具体的操作建议把它更新掉，
     * 而不是叠加出两条通知。存疑时不调用这个方法——那本来就是"不确定，自己判断"，补一条通知
     * 反而是噪声。同时供后台自动复核（本类内部）和StockBridge手动"AI分析审核"共用，
     * 所以做成静态方法，自己传Context进来。
     */
    public static void fireConfirmedAlert(Context ctx, String code, String name, String actionLabel,
                                           String aiReason, double price) {
        ensureChannel(ctx);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        String title = "✅ " + name + "(" + code + ") " + actionLabel + " · AI已确认";
        String content = String.format(java.util.Locale.CHINA, "¥%.2f · %s", price, aiReason);

        Intent tapIntent = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, code.hashCode(), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(code.hashCode(), n);

        try {
            Vibrator vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()) {
                // 用一次长震动区分于最初那条规则信号的三段震动，让用户不看屏幕光靠手感就能分辨
                // “新信号刚触发”还是“AI刚确认了一个已有的信号”。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vib.vibrate(300);
                }
            }
        } catch (Exception ignored) {}
    }

    public static void sendTestNotification(Context ctx) {
        ensureChannel(ctx);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        String title = "🔔 [测试] 太极集团(600129) 建议底仓 · 待确认";
        String content = "¥12.34 · 规则+AI双通过测试通知（不影响真实数据）";

        Intent tapIntent = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 999999, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(999999, n);
        Log.i(TAG, "已发送测试通知");
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "实时监控",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }
}
