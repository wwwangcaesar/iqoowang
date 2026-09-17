package com.monsieurmahjong.iqoowang.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.util.Locale;

/**
 * vivo/iQOO（FuntouchOS / OriginOS）系统级后台限制适配。这类系统在标准Android权限模型
 * 之外，额外拦截"自启动"和"后台弹出界面"这两项能力——即使 AlarmManager.setAlarmClock()
 * 触发的豁免窗口在AOSP层面完全合法，vivo自己的ROM补丁层仍可能在更底层单独拦掉，表现成
 * "摇一摇服务看起来在跑，但摇了没反应"。
 *
 * 这两个权限没有标准API可以像无障碍服务那样声明+引导用户走系统统一授权流程，vivo也没有
 * 公开文档，只能依赖社区整理出的几个已知管理页组件名逐个尝试直接跳转；覆盖不到的ROM版本
 * 会退化到通用的App详情设置页，让用户自己手动找。做不到100%可靠，是这类没有官方支持的
 * OEM适配的通用局限，随vivo系统版本升级这些组件名也可能失效，需要留意。
 */
public class VivoAutoStartHelper {

    private static final String TAG = "VivoAutoStartHelper";

    /** iQOO 是 vivo 子品牌，Build.MANUFACTURER 通常仍报告为 vivo，但部分机型 BRAND 会是 iqoo，两个都查一下 */
    public static boolean isVivoDevice() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase(Locale.ROOT);
        return manufacturer.contains("vivo") || manufacturer.contains("iqoo")
                || brand.contains("vivo") || brand.contains("iqoo");
    }

    /** 已知的几个vivo/iQOO自启动+后台弹出界面管理页组件名，按常见ROM版本出现频率排序，逐个尝试 */
    private static final ComponentName[] CANDIDATES = new ComponentName[]{
            new ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            new ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            new ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
            new ComponentName("com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity"),
    };

    /**
     * 依次尝试跳转到已知的vivo自启动管理页；全部失败则退化到本App的系统详情设置页
     * （用户自己在"权限"里找"自启动"/"后台弹出界面"手动开，多一步但一定能到）。
     *
     * @return true 表示成功跳到了某个vivo专属管理页；false 表示退化到了通用详情页
     */
    public static boolean openAutoStartSettings(Context context) {
        for (ComponentName cn : CANDIDATES) {
            try {
                Intent intent = new Intent();
                intent.setComponent(cn);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Log.i(TAG, "成功跳转到: " + cn);
                return true;
            } catch (Exception e) {
                // 这个ROM版本没有这个组件/跳不过去，试下一个
            }
        }

        Log.w(TAG, "已知vivo自启动管理页组件全部尝试失败，退化到App详情设置页");
        openAppDetailSettings(context);
        return false;
    }

    private static void openAppDetailSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
