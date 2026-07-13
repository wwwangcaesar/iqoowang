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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RealtimeMonitorService — 实时监控前台服务
 *
 * 按设定间隔（默认60秒，可调30秒）轮询候选池+持仓的实时行情和分时数据，跑：
 *   1. TradingRuleEngine（确定性规则引擎）做候选信号初筛——快、零成本、100%可复现
 *   2. 命中候选信号后，转 LocalAIAgent 做二次验证——结合已学话术+真实数据判断
 *      靠不靠谱，并生成小白能看懂的理由
 *   3. 无论AI是否认可，都落地成"待确认"状态，绝不自动买卖——真正要不要操作，
 *      必须用户在App里手动点确认，AI和规则引擎都只是"建议"，不能替用户下决定
 *
 * 前台服务是为了保证锁屏/切后台时监控不中断。
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

    /** App在前台时，StockBridge 注册自己进来，接收信号事件实时推给WebView */
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
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
        Log.i(TAG, "监控服务停止");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ══════════════════════════════════════════
    // 轮询主循环
    // ══════════════════════════════════════════

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
        List<WatchlistManager.WatchlistItem> watchItems = WatchlistManager.get().getActiveWatchlist();
        List<com.monsieurmahjong.iqoowang.dao.Position> positions = DatabaseManager.get().getAllPositions();

        Set<String> codeSet = new HashSet<>();
        for (WatchlistManager.WatchlistItem it : watchItems) codeSet.add(it.code);
        for (com.monsieurmahjong.iqoowang.dao.Position p : positions) codeSet.add(p.getStockCode());
        List<String> codes = new ArrayList<>(codeSet);

        String timeStr = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date());
        updateNotification(watchItems.size(), positions.size(), timeStr);
        if (sListener != null) sListener.onTick(watchItems.size(), positions.size(), timeStr);

        if (codes.isEmpty()) return;

        RealtimeQuoteManager.get().fetchBatch(codes, (quotes, failed) -> {
            if (!failed.isEmpty()) Log.w(TAG, "本轮" + failed.size() + "支行情获取失败: " + failed);

            for (WatchlistManager.WatchlistItem item : watchItems) {
                // 已经在"待确认"状态的跳过，等用户处理完这一条再评估下一轮，避免同一支股票信号刷屏
                if (isPendingStatus(item.status)) continue;

                RealtimeQuoteManager.Quote q = quotes.get(item.code);
                if (q == null) continue;

                if (WatchlistManager.STATUS_WATCHING.equals(item.status)) {
                    // 买入判断需要分时数据，单独异步取（不阻塞其它股票的判断）
                    RealtimeQuoteManager.get().fetchMinuteLine(item.code, new RealtimeQuoteManager.MinuteCallback() {
                        @Override
                        public void onResult(String code, List<RealtimeQuoteManager.MinutePoint> points) {
                            evaluateAndAct(item, q, points);
                        }
                        @Override
                        public void onError(String code, String msg) {
                            Log.w(TAG, "分时数据获取失败 " + code + ": " + msg);
                            evaluateAndAct(item, q, null);
                        }
                    });
                } else {
                    evaluateAndAct(item, q, null);
                }
            }
        });
    }

    private boolean isPendingStatus(String status) {
        return WatchlistManager.STATUS_PENDING_STARTER.equals(status)
                || WatchlistManager.STATUS_PENDING_ADD.equals(status)
                || WatchlistManager.STATUS_PENDING_STOP.equals(status);
    }

    /**
     * 第一步：规则引擎候选初筛。命中就转AI二次验证，不在这里直接改变持仓状态——
     * 规则引擎的判断只是"看起来符合条件"，靠不靠谱还得AI结合话术和实际数据看一遍。
     */
    private void evaluateAndAct(WatchlistManager.WatchlistItem item, RealtimeQuoteManager.Quote quote,
                                 List<RealtimeQuoteManager.MinutePoint> minutePoints) {
        TradingRuleEngine.PrevDayRef prevDay = mEngine.getPrevDayRef(item.code);
        TradingRuleEngine.RuleResult result = mEngine.evaluate(item.status, quote, minutePoints, prevDay);

        if (result.action == TradingRuleEngine.Action.NONE) {
            WatchlistManager.get().updateNote(item.code, result.note);
            return;
        }

        String actionKey = result.action.name(); // BUY_STARTER / ADD_POSITION / STOP_LOSS
        Log.i(TAG, "【规则候选信号】" + item.name + "(" + item.code + ") " + actionKey
                + " " + result.note + "，转AI二次验证");

        // 第二步：AI结合已学话术+真实数据二次验证，生成小白能看懂的理由
        LocalAIAgent.get(getApplicationContext()).verifySignal(
                item.code, item.name, actionKey, result.note, quote,
                new LocalAIAgent.AICallback() {
                    @Override public void onToken(String token) {}

                    @Override
                    public void onComplete(String fullText) {
                        LocalAIAgent.VerifyResult vr = LocalAIAgent.parseVerifyResult(fullText);
                        handleVerified(item, actionKey, result.triggerPrice, vr);
                    }

                    @Override
                    public void onError(String msg) {
                        Log.w(TAG, "AI验证暂时不可用(" + msg + ")，仍展示规则引擎原始判断供用户参考");
                        LocalAIAgent.VerifyResult vr = new LocalAIAgent.VerifyResult();
                        vr.confirmed = true; // AI打不通不代表信号无效，交给用户自己看规则引擎原始依据判断
                        vr.reason = "本轮AI复核暂时不可用，以下是规则引擎的原始判断：" + result.note;
                        handleVerified(item, actionKey, result.triggerPrice, vr);
                    }
                });
    }

    /**
     * 第三步：无论AI确认与否，都只落地成"待确认"状态，绝不自动买卖。
     * 只有AI也认可时才推送打扰式通知；AI存疑的静默更新到候选池，用户打开App自己看。
     */
    private void handleVerified(WatchlistManager.WatchlistItem item, String actionKey, double price,
                                 LocalAIAgent.VerifyResult vr) {
        WatchlistManager.get().markPending(item.code, actionKey, price, vr.confirmed, vr.reason);

        String actionLabel = "BUY_STARTER".equals(actionKey) ? "建议买入底仓"
                : "ADD_POSITION".equals(actionKey) ? "建议加仓" : "建议止损";

        if (vr.confirmed) {
            fireAlert(item.code, item.name, actionLabel, vr.reason, price);
        } else {
            Log.i(TAG, "AI对该候选信号存疑，不推送通知，仅静默记录: " + item.code + " " + vr.reason);
        }
        if (sListener != null) sListener.onSignalTriggered(item.code, item.name, actionLabel, vr.reason, price);
    }

    // ══════════════════════════════════════════
    // 通知 & 震动
    // ══════════════════════════════════════════

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "实时监控",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("待确认的买入/加仓/止损信号提醒（AI已初步核实，仍需你手动确认）");
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

    private void fireAlert(String code, String name, String actionLabel, String aiReason, double price) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        String title = "🔔 " + name + "(" + code + ") " + actionLabel + "・待确认";
        String content = String.format(java.util.Locale.CHINA, "¥%.2f · %s\n点击App查看详情并确认", price, aiReason);

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

    // ══════════════════════════════════════
    // 测试通知（不依赖服务运行，随时调用，用于验证锁屏/后台能否正常收到推送）
    // ══════════════════════════════════════

    /**
     * 发一条和真实信号样式完全一致的测试通知（标题带[测试]前缀区分）。
     * 不需要监控服务处于运行状态就能直接调，方便你单独验证通知权限/系统设置是否正常，
     * 而不用先把整套监控流程跑起来。
     */
    public static void sendTestNotification(Context ctx) {
        ensureChannel(ctx);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        String title = "🔔 [测试] 平安银行(000001) 建议买入底仓·待确认";
        String content = "¥12.34 · 这是一条测试通知，用来验证锁屏/后台时能否正常收到提醒（不会影响任何真实持仓/候选池数据）";

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

        try {
            Vibrator vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 200, 100, 200, 100, 200}, -1));
                } else {
                    vib.vibrate(new long[]{0, 200, 100, 200, 100, 200}, -1);
                }
            }
        } catch (Exception ignored) {}
        Log.i(TAG, "已发送测试通知");
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "实时监控",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("待确认的买入/加仓/止损信号提醒（AI已初步核实，仍需你手动确认）");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }
}
