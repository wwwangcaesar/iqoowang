package com.monsieurmahjong.iqoowang;

import android.app.Application;

import com.amap.api.location.AMapLocationClient;

public class MyApplication extends Application {
    private static final String TAG = "MyApplication";

    @Override
    public void onCreate() {
        super.onCreate();

        // 高德定位 SDK 隐私合规：必须在任何 AMapLocationClient 实例化之前调用，
        // 否则 SDK 会直接报隐私合规校验失败（errorCode 555570），定位拿不到结果。
        // 放在 Application.onCreate() 里保证全局只调一次、且一定比后面任何定位请求都早。
        AMapLocationClient.updatePrivacyShow(this, true, true);
        AMapLocationClient.updatePrivacyAgree(this, true);
    }
}
