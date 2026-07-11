package com.monsieurmahjong.iqoowang;


import android.app.Application;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;


import com.monsieurmahjong.iqoowang.agent.DailySnapshotWorker;
import com.monsieurmahjong.iqoowang.util.DatabaseManager;

import java.util.concurrent.TimeUnit;

/**
 * Application 入口
 * AndroidManifest 中 android:name=".StockMasterApp"
 */
public class StockMasterApp extends Application {

    private static final String TAG = "StockMasterApp";
    private static StockMasterApp sInstance;

    public static StockMasterApp get() { return sInstance; }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;

        // 初始化 GreenDAO（必须最先）
        DatabaseManager.init(this);
        Log.i(TAG, "DatabaseManager initialized");

        // 初始化行情数据管理器
        com.monsieurmahjong.iqoowang.util.MarketDataManager.init(this);
        Log.i(TAG, "MarketDataManager initialized");

        // 初始化大盘指数管理器（上证/深证/创业板指，供AI分析大盘环境）
        com.monsieurmahjong.iqoowang.util.MarketIndexManager.init(this);
        Log.i(TAG, "MarketIndexManager initialized");

        // 初始化候选池管理器（第一天筛选结果持久化跟踪）
        com.monsieurmahjong.iqoowang.util.WatchlistManager.init(this);
        Log.i(TAG, "WatchlistManager initialized");

        // 注册每日收盘快照 WorkManager（15:05 后触发）
        scheduleDailySnapshot();

        Log.i(TAG, "StockMasterApp ready");
    }

    /**
     * 注册每日资产快照 Worker
     * 每24小时执行一次，收盘后自动记录当日资产数据
     */
    private void scheduleDailySnapshot() {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build();

            PeriodicWorkRequest snapshotWork =
                    new PeriodicWorkRequest.Builder(
                            DailySnapshotWorker.class,
                            24, TimeUnit.HOURS)
                            .setConstraints(constraints)
                            .build();

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    "daily_snapshot",
                    ExistingPeriodicWorkPolicy.KEEP, // 已存在则保留
                    snapshotWork
            );
            Log.i(TAG, "DailySnapshotWorker scheduled");
        } catch (Exception e) {
            Log.e(TAG, "scheduleDailySnapshot failed", e);
        }
    }
}

