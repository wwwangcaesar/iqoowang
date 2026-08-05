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
    private static final int NOTIFICATION_ID = 1001;

    public static final String EXTRA_INTERVAL_MS = "interval_ms";
    public static final long DEFAULT_INTERVAL_MS = 60_000;
    public static final long FAST_INTERVAL_MS = 30_000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mIntervalMs = DEFAULT_INTERVAL_MS;
    private final TradingRuleEngine mEngine = new TradingRuleEngine();
    private int mTickCount = 0;
    private final Set<String> mStaleWarnedCodes = new HashSet<>();
    /** AI复核外层超时——比LocalAIAgent内部看门狗(45秒)稍长一点，给内部机制先机会处理；
     *  但一定得比默认tick间隔(60秒)短，避免多轮tick堆叠。即使本地模型JNI层面彻底卡死
     *  （既不触发onToken也不触发onFinish），这次信号评估也会有明确的“超时不推送”结论
     *  写进决策日志，而不是静默消失。 */
    private static final long AI_VERIFY_TIMEOUT_MS = 50_000;

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
        mHandler.removeCallbacksAndMessages(null);
        mHandler.post(mTick);
        Log.i(TAG, "监控服务启动，间隔=" + mIntervalMs + "ms");
        DecisionLogger.get().logNote("监控服务启动，间隔=" + (mIntervalMs / 1000) + "秒");
        return START_STICKY;
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
        mTickCount++;

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
            return;
        }

        List<Position> positions = DatabaseManager.get().getAllPositions();
        syncPositionsIntoWatchlist(positions);

        List<WatchlistManager.WatchlistItem> watchItems = WatchlistManager.get().getActiveWatchlist();

        Set<String> codeSet = new HashSet<>();
        for (WatchlistManager.WatchlistItem it : watchItems) codeSet.add(it.code);
        for (Position p : positions) codeSet.add(p.getStockCode());
        List<String> codes = new ArrayList<>(codeSet);

        String timeStr = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date());
        updateNotification(watchItems.size(), positions.size(), timeStr);
        if (sListener != null) sListener.onTick(watchItems.size(), positions.size(), timeStr);

        if (codes.isEmpty()) return;

        RealtimeQuoteManager.get().fetchBatch(codes, (quotes, failed) -> {
            if (!failed.isEmpty()) Log.w(TAG, "本轮" + failed.size() + "支行情获取失败: " + failed);

            AtomicInteger pending = new AtomicInteger(watchItems.size());
            for (WatchlistManager.WatchlistItem item : watchItems) {
                if (isPendingStatus(item.status)) {
                    pending.decrementAndGet();
                    continue;
                }
                RealtimeQuoteManager.Quote q = quotes.get(item.code);
                if (q == null) {
                    pending.decrementAndGet();
                    continue;
                }
                // 所有状态均需分时数据（VWAP/量比/止损追踪）
                RealtimeQuoteManager.get().fetchMinuteLine(item.code, new RealtimeQuoteManager.MinuteCallback() {
                    @Override
                    public void onResult(String code, List<RealtimeQuoteManager.MinutePoint> points) {
                        evaluateAndAct(item, q, points);
                        pending.decrementAndGet();
                    }
                    @Override
                    public void onError(String code, String msg) {
                        Log.w(TAG, "分时数据获取失败 " + code + ": " + msg);
                        evaluateAndAct(item, q, null);
                        pending.decrementAndGet();
                    }
                });
            }
        });
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

    /**
     * 沪深A股常规交易时段：9:30-11:30、13:00-15:00（午休时段自然排除在外），周一到周五。
     * 不考虑法定节假日——节假日当天本来也不会有新行情变化，顶多白白拉几次和昨天一样的快照，
     * 不会误报，但如果后续想更严谨可以接入交易日历。
     */
    private boolean isWithinTradingHours() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int dow = cal.get(java.util.Calendar.DAY_OF_WEEK);
        if (dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY) return false;
        int minutesNow = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
        boolean morning = minutesNow >= 9 * 60 + 30 && minutesNow <= 11 * 60 + 30;
        boolean afternoon = minutesNow >= 13 * 60 && minutesNow <= 15 * 60;
        return morning || afternoon;
    }

    private void evaluateAndAct(WatchlistManager.WatchlistItem item, RealtimeQuoteManager.Quote quote,
                                 List<RealtimeQuoteManager.MinutePoint> minutePoints) {
        TradingRuleEngine.PrevDayRef prevDay = mEngine.getPrevDayRef(item.code);
        TradingRuleEngine.DivergenceState trackState = WatchlistManager.get().loadTrackState(item);
        TradingRuleEngine.PatternRef pattern = WatchlistManager.get().loadPatternRef(item);
        TradingRuleEngine.RuleResult result = mEngine.evaluate(
                item.code, item.status, quote, minutePoints, prevDay, trackState, pattern);

        if (result.stateUpdate != null) {
            WatchlistManager.get().saveTrackState(item.code, result.stateUpdate);
        }
        if (result.waterLine > 0) {
            WatchlistManager.get().updateLiveMetrics(item.code, result.waterLine, result.vwap, result.volRatio);
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
            // 观察中的候选股：本轮无买卖信号时，检查是否该自动移出观察池
            if (WatchlistManager.STATUS_WATCHING.equals(item.status) && !holding) {
                String staleReason = checkStaleCandidate(item, quote, prevDay, result);
                if (staleReason != null) {
                    WatchlistManager.get().autoRemoveStale(item.code, staleReason);
                    DecisionLogger.get().logNote(item.name + "(" + item.code + ") 自动移出观察池：" + staleReason);
                }
            }
            return;
        }

        applyT1Note(result, pos, holding);

        // 二级止损中点：未到收盘前推送窗口 → 只更新备注，不推送、不入AI队列
        if (!shouldNotifyNow(result)) {
            WatchlistManager.get().updateNote(item.code, result.note);
            Log.i(TAG, "规则命中但不在推送窗口: " + item.code + " " + result.actionLabel);
            return;
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

        fireAlert(item.code, item.name, result.actionLabel, result.note, result.triggerPrice);
        if (sListener != null) {
            sListener.onSignalTriggered(item.code, item.name, result.actionLabel, result.note, result.triggerPrice);
        }

        enqueueVerification(item, actionKey, result, quote, pos);
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
     * 被判定超时移出观察池。改为按周一到周五估算交易日，不接入法定节假日日历，
     * 与本类isWithinTradingHours()、MarketDataManager.computeExpectedTradeDate()是同一套
     * 简化口径（节假日当天本来也不会有行情变化，顶多把移出判断稍微推迟，不会误报）。
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
                int dow = cursor.get(java.util.Calendar.DAY_OF_WEEK);
                if (dow != java.util.Calendar.SATURDAY && dow != java.util.Calendar.SUNDAY) tradeDays++;
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
                item.code, item.name, actionKey, result.actionLabel, result.note, result.metrics, quote,
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
     * AI 异步分析完成：只回填 pending 记录的 AI 结论 + 写补充日志，不再决定要不要通知
     * （通知已在规则命中那一刻发出）。用户若在 AI 完成前已确认/忽略，updatePendingAiResult 会跳过。
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
