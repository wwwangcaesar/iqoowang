package com.monsieurmahjong.iqoowang;

import android.app.Application;

import com.amap.api.location.AMapLocationClient;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.services.core.ServiceSettings;

public class MyApplication extends Application {
    private static final String TAG = "MyApplication";

    @Override
    public void onCreate() {
        super.onCreate();

        // 高德 SDK 隐私合规：定位/地图/搜索三个模块各自都要求在对应类第一次被用到之前
        // 调用 updatePrivacyShow + updatePrivacyAgree，不调用会直接报隐私合规校验失败
        // （定位是 errorCode 555570，地图/搜索是白屏或类似报错）。放在 Application.onCreate()
        // 里一次性把三个都声明掉，保证一定比后面任何实际调用都早，不用在每个用到的地方分别操心。
        AMapLocationClient.updatePrivacyShow(this, true, true);
        AMapLocationClient.updatePrivacyAgree(this, true);
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        ServiceSettings.updatePrivacyShow(this, true, true);
        ServiceSettings.updatePrivacyAgree(this, true);
    }
}
