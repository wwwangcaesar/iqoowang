package com.monsieurmahjong.iqoowang.map;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.PolylineOptions;
import com.amap.api.services.core.AMapException;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.route.BusRouteResult;
import com.amap.api.services.route.DrivePath;
import com.amap.api.services.route.DriveRouteResult;
import com.amap.api.services.route.DriveStep;
import com.amap.api.services.route.RideRouteResult;
import com.amap.api.services.route.RouteSearch;
import com.amap.api.services.route.WalkRouteResult;
import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.utils.LocationHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 消费记录的位置详情页：显示地点名称/坐标，内嵌地图展示目标点，拿到当前定位后
 * 自动查一条驾车路线画出来，"导航"按钮唤起高德地图 App 做真实导航（走 URI Scheme，
 * 不依赖地图/搜索 SDK，不受这两个 SDK 是否配置正确影响，永远可用）。
 *
 * 【关于路线绘制】高德地图 SDK 从 V4.1.3 起不再在 SDK jar 里自带 DrivingRouteOverlay
 * 这个便捷类（官方原话：SDK不再提供 com.amap.api.maps.overlay 包下的 overlay，已在
 * 官方demo中开源）。这里没有去拷贝那个类，而是用最基础、稳定可靠的方式手动把
 * DriveStep 里的坐标点连成一条 Polyline——没有转弯图标、没有按拥堵情况变色这些
 * 进阶效果，但能满足"根据定位显示导航路线"这个核心需求，且不依赖一个已经从官方
 * SDK 移除、需要额外拷贝维护的类。
 */
public class LocationMapActivity extends AppCompatActivity implements RouteSearch.OnRouteSearchListener {

    public static final String EXTRA_LAT = "extra_lat";
    public static final String EXTRA_LON = "extra_lon";
    public static final String EXTRA_NAME = "extra_name";

    private static final String TAG = "LocationMapActivity";
    private static final String AMAP_PACKAGE = "com.autonavi.minimap";
    /** 路线画成蓝绿色，跟 App 整体的荧光青主题色呼应 */
    private static final int ROUTE_COLOR = 0xFF00C6FF;

    private MapView mapView;
    private AMap aMap;
    private RouteSearch routeSearch;

    private double targetLat;
    private double targetLon;
    private String displayName;

    private TextView tvRouteInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_location_map);

        targetLat = getIntent().getDoubleExtra(EXTRA_LAT, 0);
        targetLon = getIntent().getDoubleExtra(EXTRA_LON, 0);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        displayName = (name != null && !name.trim().isEmpty()) ? name : "未命名地点";

        TextView tvName = findViewById(R.id.tv_map_location_name);
        TextView tvCoords = findViewById(R.id.tv_map_coords);
        tvRouteInfo = findViewById(R.id.tv_map_route_info);
        TextView tvBack = findViewById(R.id.tv_map_back);
        View btnNavigate = findViewById(R.id.btn_map_navigate);

        tvName.setText(displayName);
        tvCoords.setText(String.format(Locale.getDefault(), "%.6f, %.6f", targetLat, targetLon));
        tvBack.setOnClickListener(v -> finish());
        btnNavigate.setOnClickListener(v -> openAmapNavigation(targetLat, targetLon, displayName));

        mapView = findViewById(R.id.map_view);
        mapView.onCreate(savedInstanceState); // MapView 生命周期必须和 Activity 同步转发，官方要求
        initMap();

        routeSearch = new RouteSearch(this);
        routeSearch.setRouteSearchListener(this);

        // 当前定位是"尽力而为"：拿不到也不影响目标点正常显示在地图上，只是没有路线可画
        LocationHelper.requestOnce(this, this::onCurrentLocationResult);
    }

    private void initMap() {
        aMap = mapView.getMap();
        LatLng targetLatLng = new LatLng(targetLat, targetLon);
        aMap.addMarker(new MarkerOptions()
                .position(targetLatLng)
                .title(displayName));
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(targetLatLng, 15f));
    }

    private void onCurrentLocationResult(LocationHelper.LocationResult result) {
        if (result == null) {
            runOnUiThread(() -> tvRouteInfo.setText("无法获取当前位置，仅显示目标地点"));
            return;
        }
        LatLonPoint from = new LatLonPoint(result.latitude, result.longitude);
        LatLonPoint to = new LatLonPoint(targetLat, targetLon);
        RouteSearch.FromAndTo fromAndTo = new RouteSearch.FromAndTo(from, to);
        RouteSearch.DriveRouteQuery query =
                new RouteSearch.DriveRouteQuery(fromAndTo, RouteSearch.DrivingDefault, null, null, "");
        routeSearch.calculateDriveRouteAsyn(query);
    }

    @Override
    public void onDriveRouteSearched(DriveRouteResult result, int errorCode) {
        if (errorCode != AMapException.CODE_AMAP_SUCCESS || result == null
                || result.getPaths() == null || result.getPaths().isEmpty()) {
            Log.w(TAG, "驾车路线规划失败，errorCode=" + errorCode);
            runOnUiThread(() -> tvRouteInfo.setText("路线规划失败，仅显示目标地点"));
            return;
        }

        DrivePath path = result.getPaths().get(0);
        drawRoutePolyline(path);

        long meters = (long) path.getDistance();
        long seconds = (long) path.getDuration();
        String distanceText = meters >= 1000
                ? String.format(Locale.getDefault(), "%.1f公里", meters / 1000f)
                : meters + "米";
        String durationText = seconds >= 3600
                ? String.format(Locale.getDefault(), "%d小时%d分钟", seconds / 3600, (seconds % 3600) / 60)
                : Math.max(1, seconds / 60) + "分钟";
        runOnUiThread(() -> tvRouteInfo.setText("驾车约 " + distanceText + " · " + durationText));
    }

    /** 把驾车路径的每个 DriveStep 坐标点连成一条 Polyline，起点额外加个蓝色圆点标记当前位置 */
    private void drawRoutePolyline(DrivePath path) {
        List<LatLng> points = new ArrayList<>();
        for (DriveStep step : path.getSteps()) {
            for (LatLonPoint p : step.getPolyline()) {
                points.add(new LatLng(p.getLatitude(), p.getLongitude()));
            }
        }
        if (points.isEmpty() || aMap == null) return;

        runOnUiThread(() -> {
            aMap.addPolyline(new PolylineOptions()
                    .addAll(points)
                    .width(16f)
                    .color(ROUTE_COLOR));

            aMap.addMarker(new MarkerOptions()
                    .position(points.get(0))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    .title("当前位置"));

            // 把起点+终点+沿途都纳入视野，而不是停留在目标点那个15级缩放
            LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder();
            for (LatLng p : points) boundsBuilder.include(p);
            try {
                aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 120));
            } catch (Exception e) {
                Log.w(TAG, "调整地图视野失败，保持当前缩放级别", e);
            }
        });
    }

    // RouteSearch.OnRouteSearchListener 接口的其余三个回调（公交/步行/骑行）本页面用不到，空实现即可
    @Override public void onBusRouteSearched(BusRouteResult busRouteResult, int i) {}
    @Override public void onWalkRouteSearched(WalkRouteResult walkRouteResult, int i) {}
    @Override public void onRideRouteSearched(RideRouteResult rideRouteResult, int i) {}

    /**
     * 唤起高德地图 App 做真实导航：不传起点（slat/slon），高德会自动用设备当前定位当起点。
     * dev=0 表示传入的经纬度已经是高德自己的 GCJ-02 坐标，不需要服务端二次纠偏——这也是为什么
     * 定位那一步坚持用高德自己的定位 SDK（见 LocationHelper），坐标系从头到尾保持一致。
     */
    private void openAmapNavigation(double lat, double lon, String name) {
        String uri = "amapuri://route/plan/?sourceApplication=iqoowang"
                + "&dlat=" + lat + "&dlon=" + lon
                + "&dname=" + Uri.encode(name)
                + "&dev=0&t=0";
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        intent.setPackage(AMAP_PACKAGE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        } else {
            Toast.makeText(this, "未安装高德地图，无法导航", Toast.LENGTH_SHORT).show();
        }
    }

    // MapView 生命周期转发，官方要求的标准写法，缺一个都可能导致地图状态异常或内存泄漏
    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapView.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }
}
