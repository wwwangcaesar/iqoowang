package com.monsieurmahjong.iqoowang.agent;


import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;


import com.monsieurmahjong.iqoowang.util.DatabaseManager;

import java.util.Calendar;

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
}
