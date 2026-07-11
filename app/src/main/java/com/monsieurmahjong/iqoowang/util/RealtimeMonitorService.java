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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RealtimeMonitorService — 实时监控前台服务
 *
 * 按设定间隔（默认60秒，可调30秒）轮询候选池+持仓的实时行情和分时数据，
 * 跑 TradingRuleEngine 判断买入/加仓/止损，触发时：
 *   1. 更新 WatchlistManager 状态
 *   2. 发系统通知（震动）
 *   3. 如果 App 正在前台（StockBridge 已注册监听），同步推送到 WebView
 *
 * 前台服务是为了保证锁屏/切后台时监控不中断——买卖判断这种事不能因为
 * 用户切了个微信就漏掉。
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

    private void evaluateAndAct(WatchlistManager.WatchlistItem item, RealtimeQuoteManager.Quote quote,
                                 List<RealtimeQuoteManager.MinutePoint> minutePoints) {
        TradingRuleEngine.PrevDayRef prevDay = mEngine.getPrevDayRef(item.code);
        TradingRuleEngine.RuleResult result = mEngine.evaluate(item.status, quote, minutePoints, prevDay);

        WatchlistManager.get().updateNote(item.code, result.note);

        if (result.action == TradingRuleEngine.Action.NONE) return;

        String actionLabel;
        switch (result.action) {
            case BUY_STARTER:
                WatchlistManager.get().markStarter(item.code, result.triggerPrice, result.note);
                actionLabel = "买入底仓";
                break;
            case ADD_POSITION:
                WatchlistManager.get().markAdded(item.code, result.triggerPrice, result.note);
                actionLabel = "突破加仓";
                break;
            case STOP_LOSS:
                WatchlistManager.get().markStopped(item.code, result.note);
                actionLabel = "止损清仓";
                break;
            default:
                return;
        }

        Log.i(TAG, "【信号触发】" + item.name + "(" + item.code + ") " + actionLabel + " " + result.note);
        fireAlert(item.code, item.name, actionLabel, result.note, result.triggerPrice);
        if (sListener != null) sListener.onSignalTriggered(item.code, item.name, actionLabel, result.note, result.triggerPrice);
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
            ch.setDescription("买入/加仓/止损信号提醒");
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

    private void fireAlert(String code, String name, String action, String note, double price) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        String title = "🔔 " + name + "(" + code + ") " + action;
        String content = String.format(java.util.Locale.CHINA, "%s ¥%.2f · %s", action, price, note);

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
}
