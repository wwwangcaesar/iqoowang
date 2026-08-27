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

        // 交易规则配置（assets/trading_rules.json）
        com.monsieurmahjong.iqoowang.util.TradingRuleConfig.init(this);
        Log.i(TAG, "TradingRuleConfig initialized");

        // 初始化候选池管理器（第一天筛选结果持久化跟踪）
        com.monsieurmahjong.iqoowang.util.WatchlistManager.init(this);
        Log.i(TAG, "WatchlistManager initialized");

        // 初始化话术知识库（用户教给AI的操盘手经验，持久化并注入后续分析）
        com.monsieurmahjong.iqoowang.util.WisdomManager.init(this);
        Log.i(TAG, "WisdomManager initialized");

        // 初始化交易周期复盘知识库（买入到清仓的完整周期记录）。
        // 【2026-08-27修复】这一行之前遗漏了——TradeLessonManager.get()在没有init()的情况下
        // 会抛IllegalStateException。这个异常发生在StockBridge.recordTrade()清仓卖出分支里，
        // 导致本来已经成功写库的整仓卖出被外层catch吞掉、误判成交易失败返回-1，
        // 前端因此弹"卖出失败，请检查持仓"，而实际上数据库已经正确清空了这笔持仓——
        // 只有重启App重新读库才会发现其实卖成功了。同时因为markCycleClosed()从未真正执行成功，
        // "AI大脑"页的待复盘交易列表也永远不会出现任何完全卖出的股票。
        com.monsieurmahjong.iqoowang.util.TradeLessonManager.init(this);
        Log.i(TAG, "TradeLessonManager initialized");

        // 初始化决策日志（每次规则+AI判断都记录到本地文件，供事后复盘）
        com.monsieurmahjong.iqoowang.util.DecisionLogger.init(this);
        Log.i(TAG, "DecisionLogger initialized");

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

