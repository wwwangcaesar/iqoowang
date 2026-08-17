package com.monsieurmahjong.iqoowang.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;

/**
 * 高德定位封装：发起一次单次定位，回调里同时拿到经纬度和地点名称，
 * 不需要额外再调一次逆地理编码接口——AMapLocationClientOption 开启
 * needAddress（默认就是 true，这里显式写出来便于阅读）之后，AMapLocation
 * 回调本身就带了 POI名/AOI名/完整地址等字段，一次定位两件事一起拿到。
 *
 * 定位是"尽力而为"：拿不到（权限没给、没信号、SDK超时、隐私合规没通过等）时
 * Callback 会收到 null，调用方必须能正常处理"没有定位也能正常记账"这条兜底路径，
 * 不能让定位失败卡住记账主流程。
 */
public class LocationHelper {

    private static final String TAG = "LocationHelper";
    /** 单次定位等待上限：不能无限等下去，超时就当作这次拿不到定位 */
    private static final long TIMEOUT_MS = 8000;

    public interface Callback {
        /** result 为 null 表示没拿到定位，调用方需要能正常处理这种情况 */
        void onResult(LocationResult result);
    }

    public static class LocationResult {
        public final double latitude;
        public final double longitude;
        /** 可能为 null（定位成功但地址反查失败时） */
        public final String locationName;

        public LocationResult(double latitude, double longitude, String locationName) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.locationName = locationName;
        }
    }

    /**
     * 发起一次单次定位（异步、非阻塞，可以直接在主线程调用）。
     * MyApplication.onCreate() 里已经做过隐私合规声明，这里不用重复调用。
     */
    public static void requestOnce(Context context, Callback callback) {
        AMapLocationClient client;
        try {
            client = new AMapLocationClient(context.getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "创建高德定位客户端失败", e);
            callback.onResult(null);
            return;
        }

        AMapLocationClientOption option = new AMapLocationClientOption();
        option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
        option.setOnceLocation(true);   // 只定位一次，拿到结果后 SDK 自动停止，不需要手动维护连续定位状态
        option.setNeedAddress(true);    // 回调里直接带地址信息（SDK 默认就是 true，这里显式声明便于阅读）
        option.setHttpTimeOut(TIMEOUT_MS);
        client.setLocationOption(option);

        // done 只在主线程里被读写（onLocationChanged 回调和下面的超时 Runnable 都发生在主线程），
        // 不需要额外加锁，纯粹用来防止"正常回调"和"兜底超时"重复触发 callback
        final boolean[] done = {false};
        final AMapLocationClient finalClient = client;

        Runnable timeoutFallback = () -> {
            if (done[0]) return;
            done[0] = true;
            safeStopAndDestroy(finalClient);
            callback.onResult(null);
        };
        Handler timeoutHandler = new Handler(Looper.getMainLooper());
        timeoutHandler.postDelayed(timeoutFallback, TIMEOUT_MS + 2000);

        client.setLocationListener(new AMapLocationListener() {
            @Override
            public void onLocationChanged(AMapLocation location) {
                if (done[0]) return;
                done[0] = true;
                timeoutHandler.removeCallbacks(timeoutFallback);
                safeStopAndDestroy(finalClient);

                if (location == null || location.getErrorCode() != 0) {
                    Log.w(TAG, "定位失败：" + (location != null
                            ? location.getErrorCode() + " " + location.getErrorInfo() : "null"));
                    callback.onResult(null);
                    return;
                }
                callback.onResult(new LocationResult(
                        location.getLatitude(), location.getLongitude(), pickDisplayName(location)));
            }
        });
        client.startLocation();
    }

    private static void safeStopAndDestroy(AMapLocationClient client) {
        try {
            client.stopLocation();
            client.onDestroy();
        } catch (Exception e) {
            Log.w(TAG, "释放定位客户端时出错（不影响本次结果）", e);
        }
    }

    /** 地点名称兜底链：POI名（比如"星巴克(万达店)"）> AOI名（比如"万达广场"）> 完整地址 */
    private static String pickDisplayName(AMapLocation location) {
        if (notEmpty(location.getPoiName())) return location.getPoiName();
        if (notEmpty(location.getAoiName())) return location.getAoiName();
        if (notEmpty(location.getAddress())) return location.getAddress();
        return null;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
