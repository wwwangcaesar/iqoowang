package com.monsieurmahjong.iqoowang.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启摇一摇记账。BOOT_COMPLETED 是 Android 官方明确列出的、允许从后台直接拉起前台Service的
 * 例外场景之一，不需要额外的用户交互豁免（specialUse 类型的前台服务不在 Android 15 新增的
 * "BOOT_COMPLETED 禁止启动的FGS类型" 名单里：那份名单只覆盖 camera/dataSync/mediaPlayback/
 * mediaProjection/microphone/phoneCall 这几种，specialUse 不受影响）。
 *
 * 真正是否拉起交给 ShakeDetectService.startIfEnabled() 统一判断（开关 + 无障碍服务双重检查），
 * 这里只是把"设备重启"这个事件接进来，不会绕过用户手动关闭开关的选择。
 */
public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "BootCompletedReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Log.i(TAG, "收到开机广播，检查是否需要自启摇一摇监听");
        ShakeDetectService.startIfEnabled(context);
    }
}

