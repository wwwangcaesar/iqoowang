package com.monsieurmahjong.iqoowang.utils;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

import com.monsieurmahjong.iqoowang.server.ScreenshotService;

/**
 * 检测 ScreenshotService（截图用的无障碍服务）是否已经在系统设置里被打开。
 * 摇一摇记账依赖这个服务才能截图识别金额，没开的话摇一摇打开页面也没意义，
 * 所以设置页里开"摇一摇记账"开关之前会先检查这个。
 */
public class AccessibilityStatusUtils {

    private AccessibilityStatusUtils() {}

    public static boolean isScreenshotServiceEnabled(Context context) {
        int enabled = 0;
        try {
            enabled = Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException ignored) {
        }
        if (enabled != 1) return false;

        String enabledServices = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) return false;

        String target = context.getPackageName() + "/" + ScreenshotService.class.getName();
        // 系统里这个列表用 ':' 分隔多个服务，逐个精确比对，避免用 contains() 误命中包名相似的其它服务
        for (String service : enabledServices.split(":")) {
            if (service.equalsIgnoreCase(target)) return true;
        }
        return false;
    }
}
