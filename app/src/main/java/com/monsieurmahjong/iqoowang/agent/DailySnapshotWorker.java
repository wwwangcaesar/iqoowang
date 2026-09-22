package com.monsieurmahjong.iqoowang.agent;


import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;


import com.monsieurmahjong.iqoowang.dao.Position;
import com.monsieurmahjong.iqoowang.util.DatabaseManager;
import com.monsieurmahjong.iqoowang.util.RealtimeQuoteManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 每日资产快照 Worker
 *
 * 在收盘后（15:00之后）自动执行，将当日总资产写入 DailyAsset 表。
 * 由 StockMasterApp 注册，每24小时触发一次。
 *
 * 说明：WorkManager 不保证精确到分钟，只保证24小时内执行一次。
 * 实际触发时间由系统调度，通常在充电/空闲状态下执行。
 *
 * 【修复说明】之前这里从 SharedPreferences 读 "cash"，但 WebView 端从来没有真正写过
 * 这个key（一直是内存里假算，重启就丢），导致每日快照永远记成初始10万，跟真实交易
 * 完全对不上。现在改为直接调用 DatabaseManager.saveDailySnapshot()（无参版本），
 * 现金/总资产由 Java 端根据完整交易流水自己推算，不再依赖任何外部传入的数值。
 *
 * 【2026-09-22新增】上面这次修复解决了"现金"的准确性，但没解决"持仓市值"的准确性——
 * saveDailySnapshot()算总资产时用的是Position.currentPrice，这个字段只在两个时机更新：
 * 交易发生时、或WebView在前台时每3秒的自动刷新(StockBridge.refreshPositionPrices，依赖
 * Android.setAutoRefresh(true))。WorkManager触发这个Worker的时间不保证精确到分钟，如果
 * 收盘后到实际触发这段时间App一直没在前台开着，currentPrice就会停留在几小时甚至前一天
 * 最后一次看盘时的陈旧价格，直接拿去落当天快照，会让"今日盈亏"（DatabaseManager.getTodayPnl，
 * 定义是"当前总资产-最近一次非今天的快照总资产"）往后好几天的计算基准都是错的。现在落
 * 快照前先同步刷新一次全部持仓的最新行情（doWork本来就跑在WorkManager的后台线程，可以
 * 安全阻塞等待），拿不到最新价的股票会在日志里写清楚代码，快照仍然会用它们各自最后一次
 * 已知价格兜底，不会让整个快照任务失败。
 */
public class DailySnapshotWorker extends Worker {

    private static final String TAG = "DailySnapshotWorker";

    public DailySnapshotWorker(@NonNull Context context,
                               @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Log.i(TAG, "DailySnapshotWorker running");

            // 只在交易日执行（周一到周五）
            int dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
            if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
                Log.i(TAG, "Weekend, skip snapshot");
                return Result.success();
            }

            DatabaseManager db = DatabaseManager.get();

            // 【2026-09-22新增】落快照前先同步刷新一次持仓最新价，避免用陈旧价格污染
            // "今日盈亏"往后几天的计算基准，详见类注释。
            refreshPositionPricesBlocking(db);

            db.saveDailySnapshot();

            Log.i(TAG, String.format("Snapshot saved: total=%.2f cash=%.2f",
                    db.getTotalAssetValue(), db.getCashBalance()));

            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "DailySnapshotWorker failed", e);
            // RETRY 让 WorkManager 稍后重试
            return Result.retry();
        }
    }

    /** 同步（阻塞等待）刷新一次全部持仓的最新行情价并写回数据库。最多等15秒——网络异常/
     *  接口超时也不能让每日快照任务无限期卡住，超时后直接用各自最后一次已知价格继续落快照，
     *  好过让整个Worker失败重试、进一步推迟快照时间。doWork()本身就跑在WorkManager自己的
     *  后台线程池上（不是主线程），这里阻塞等待不会卡UI，也不会跟fetchBatch内部post回主线程
     *  的回调死锁（主线程本来就没被占用）。 */
    private void refreshPositionPricesBlocking(DatabaseManager db) {
        List<Position> positions = db.getAllPositions();
        if (positions.isEmpty()) return;
        List<String> codes = new ArrayList<>();
        for (Position p : positions) codes.add(p.getStockCode());

        CountDownLatch latch = new CountDownLatch(1);
        RealtimeQuoteManager.get().fetchBatch(codes, (quotes, failedCodes) -> {
            try {
                if (!quotes.isEmpty()) {
                    JSONObject priceMap = new JSONObject();
                    for (Map.Entry<String, RealtimeQuoteManager.Quote> e : quotes.entrySet()) {
                        try { priceMap.put(e.getKey(), e.getValue().price); } catch (Exception ignored) {}
                    }
                    db.batchUpdatePrices(priceMap);
                }
                if (!failedCodes.isEmpty()) {
                    Log.w(TAG, "落快照前刷新持仓价：" + failedCodes.size()
                            + "支未拿到最新价，快照会用它们各自最后一次已知价格兜底: " + failedCodes);
                }
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                Log.w(TAG, "落快照前刷新持仓价超时(15秒)，改用各自最后一次已知价格继续落快照");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
