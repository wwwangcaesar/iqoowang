package com.monsieurmahjong.iqoowang.util;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;


/**
 * 开机自启动接收器
 *
 * 设备重启后，WorkManager 任务会丢失。
 * 通过监听 BOOT_COMPLETED 广播，触发 Application.onCreate()
 * 重新注册 DailySnapshotWorker。
 *
 * 注：WorkManager 2.x 已内置开机恢复机制，
 * 此 Receiver 作为双重保险。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Boot completed, WorkManager will auto-reschedule");
            // WorkManager 会自动恢复 PeriodicWork，无需手动触发
            // 如果需要立即执行一次快照，可以在这里触发 OneTimeWorkRequest
        }
    }
}

