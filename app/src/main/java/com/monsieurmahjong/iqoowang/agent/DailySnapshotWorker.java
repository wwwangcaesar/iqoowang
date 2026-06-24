package com.monsieurmahjong.iqoowang.agent;


import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;


import com.monsieurmahjong.iqoowang.dao.Position;
import com.monsieurmahjong.iqoowang.util.DatabaseManager;

import java.util.Calendar;
import java.util.List;

/**
 * 每日资产快照 Worker
 *
 * 在收盘后（15:00之后）自动执行，将当日总资产写入 DailyAsset 表。
 * 由 StockMasterApp 注册，每24小时触发一次。
 *
 * 说明：WorkManager 不保证精确到分钟，只保证24小时内执行一次。
 * 实际触发时间由系统调度，通常在充电/空闲状态下执行。
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

            // 计算当前总资产
            // 注意：cash 由前端维护，这里从 SharedPreferences 读取
            // WebView 会在交易后通过 Android.savePrefs("cash", value) 保存
            String cashStr = getApplicationContext()
                    .getSharedPreferences("sm_prefs", Context.MODE_PRIVATE)
                    .getString("cash", "100000");

            double cash = Double.parseDouble(cashStr);

            // 累计持仓市值
            List<Position> positions = db.getAllPositions();
            double posValue = 0;
            for (Position p : positions) {
                posValue += p.getCurrentPrice() * p.getQuantity();
            }

            double totalAsset = cash + posValue;

            // 写入 GreenDAO
            db.saveDailySnapshot(cash, totalAsset);

            Log.i(TAG, String.format("Snapshot saved: total=%.2f cash=%.2f pos=%.2f",
                    totalAsset, cash, posValue));

            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "DailySnapshotWorker failed", e);
            // RETRY 让 WorkManager 稍后重试
            return Result.retry();
        }
    }
}
