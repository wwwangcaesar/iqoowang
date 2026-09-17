package com.monsieurmahjong.iqoowang.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.monsieurmahjong.iqoowang.QuickLogActivity;

/**
 * AlarmManager.setAlarmClock() 的投递目标：只做一件事——立刻 startActivity() 拉起
 * QuickLogActivity。这个类刻意保持单薄，是因为它的全部价值就在于"运行在AlarmManager
 * 投递闹钟这个系统豁免后台限制的窗口内"这一件事上——不应该在这里塞其他逻辑（哪怕看起来
 * 无害），避免这个关键的 startActivity() 调用被其他代码路径耽误、错过豁免窗口。
 *
 * 只由 ShakeDetectService 用本App自己创建的 PendingIntent 触发（Manifest 里没有挂
 * 任何 intent-filter），exported=false，外部App没有触发它的途径。
 */
public class ShakeTrampolineReceiver extends BroadcastReceiver {

    private static final String TAG = "ShakeTrampolineReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "落在AlarmManager豁免窗口内，拉起记账页");

        Intent target = new Intent(context, QuickLogActivity.class);
        target.setAction(QuickLogActivity.ACTION_SHAKE_LOG);
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        try {
            context.startActivity(target);
        } catch (Exception e) {
            // 理论上豁免窗口内不应该失败；真出现这种情况就是没有更好的兜底了，
            // 摇一摇这次触发放弃，等用户手动打开App
            Log.e(TAG, "豁免窗口内 startActivity 仍然失败，本次摇晃触发放弃", e);
        }
    }
}
