package com.monsieurmahjong.iqoowang.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

/**
 * 唤起高德地图 App 做真实导航的公共逻辑，原本写在 LocationMapActivity 里，现在
 * DistrictStreetMapActivity（原生街区图层）也要用同一套四级降级链路，抽出来共用一份，
 * 避免同样的逻辑维护两份、改一个忘了改另一个。
 */
public class AmapNavigationHelper {

    private static final String TAG = "AmapNavigationHelper";
    private static final String AMAP_PACKAGE = "com.autonavi.minimap";

    /**
     * 不传起点（slat/slon），高德会自动用设备当前定位当起点。dev=0 表示传入的经纬度已经是
     * 高德自己的 GCJ-02 坐标，不需要服务端二次纠偏——这也是为什么定位那一步坚持用高德自己的
     * 定位 SDK（见 LocationHelper），坐标系从头到尾保持一致。
     */
    public static void openNavigation(Context context, double lat, double lon, String name) {
        try {
            // 1. 优先使用高德地图导航协议 (androidamap://navi)
            // 该协议直接以精确经纬度 (lat, lon) 作为导航终点坐标，dev=0 (高德GCJ02坐标系)，style=2 (驾车导航)，
            // poiname 为目的地展示名称。这样高德地图会严格按照 GPS 坐标导航，绝不会误搜到其他同名地点或旧地点。
            String naviUri = "androidamap://navi?sourceApplication=iqoowang"
                    + "&lat=" + lat
                    + "&lon=" + lon
                    + "&dev=0&style=2"
                    + "&poiname=" + Uri.encode(name);
            Intent naviIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(naviUri));
            naviIntent.setPackage(AMAP_PACKAGE);
            naviIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            if (naviIntent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(naviIntent);
                return;
            }

            // 2. 降级使用路径规划协议 (androidamap://route/plan/)
            String routeUri = "androidamap://route/plan/?sourceApplication=iqoowang"
                    + "&dlat=" + lat
                    + "&dlon=" + lon
                    + "&dname=" + Uri.encode(name)
                    + "&dev=0&t=0";
            Intent routeIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(routeUri));
            routeIntent.setPackage(AMAP_PACKAGE);
            routeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (routeIntent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(routeIntent);
                return;
            }

            // 3. 降级使用通用的 amapuri://
            String fallbackUri = "amapuri://route/plan/?sourceApplication=iqoowang"
                    + "&dlat=" + lat
                    + "&dlon=" + lon
                    + "&dname=" + Uri.encode(name)
                    + "&dev=0&t=0";
            Intent fallbackIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUri));
            fallbackIntent.setPackage(AMAP_PACKAGE);
            fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (fallbackIntent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(fallbackIntent);
                return;
            }

            // 4. 降级使用外部浏览器唤起高德 Web 导航
            String webUri = "https://uri.amap.com/navigation?to=" + lon + "," + lat + "," + Uri.encode(name)
                    + "&mode=car&src=iqoowang&coordinate=gaode&callnative=1";
            Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(webUri));
            webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(webIntent);
        } catch (Exception e) {
            Log.e(TAG, "唤起高德导航异常", e);
            Toast.makeText(context, "未安装高德地图，无法导航", Toast.LENGTH_SHORT).show();
        }
    }
}
