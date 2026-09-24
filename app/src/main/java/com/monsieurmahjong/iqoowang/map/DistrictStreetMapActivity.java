package com.monsieurmahjong.iqoowang.map;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.Circle;
import com.amap.api.maps.model.CircleOptions;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.utils.AmapNavigationHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 【202609 新增：原生高清街区图层】用高德原生 Android SDK 重做的区县级消费点详情页，
 * 替代原来 map_explore.html 里靠 WebView 加载高德 JS API 那一套。动机见跟用户的讨论：
 * 自定义 Marker + 逐点脉冲动画这个视觉效果，在 WebView 里用 DOM+CSS 做，点一多就卡；
 * 挪到原生实现后，Marker 图标只在创建时生成一次静态 Bitmap（不逐帧重绘），脉冲光圈用
 * AMap 原生 Circle 叠加同一坐标、半径/透明度交给 ValueAnimator 驱动，是原生矢量图层的
 * 属性动画，比"每个点各自一个DOM元素+独立CSS动画"便宜得多——两边视觉效果基本一致，
 * 性能不是一个量级。
 *
 * 【当前阶段：先接 Mock 数据，不接生产数据】按用户要求，这一步只把原生页面本身做完整、
 * 做正确，用一批写死的示例消费点验证视觉效果和交互；MapExploreActivity/map_explore.html
 * 里"市级点击进区县"的入口还没有改成跳这个页面，正常使用流程完全不受影响。等这个页面
 * 本身验证没问题了，再单独做一轮：1) 从 Room 数据库查真实的某区县消费点数据传进来，
 * 2) 把 map_explore.html 里省→市→区县那一步的跳转目标换成这个原生页面。
 *
 * 【202609 二次修复：缩放时光圈闪烁】AMap 原生 Circle 是矢量图形类，半径单位是"米"，不是
 * "像素"——地图放大时同样的米数在屏幕上占的像素会跟着变大，脉冲光圈就会从"细小的呼吸感"
 * 迅速膨胀成一个硕大的圆不停放大缩小，视觉上就是用户说的"闪烁"。（查了官方文档确认过，
 * Android SDK 目前只有 Circle 这一个圆形覆盖物类型，没有 JS API 里那个"固定屏幕像素大小、
 * 不随缩放改变"的 CircleMarker，得自己按当前缩放级别反算。）
 * 现在的做法：不再用固定的"米"作为光圈半径，而是记住"想要多少像素"，每次相机缩放变化时
 * （setOnCameraChangeListener）用标准 Web 墨卡托投影公式把"像素"换算成对应纬度、当前
 * 缩放级别下应该用多少"米"，再喂给 Circle.setRadius()——这样光圈在屏幕上看起来的大小
 * 无论怎么缩放都基本不变，回到用户满意的"第一种动画效果"那种观感。
 */
public class DistrictStreetMapActivity extends AppCompatActivity {

    public static final String EXTRA_DISTRICT_NAME = "extra_district_name";
    public static final String EXTRA_DISTRICT_ADCODE = "extra_district_adcode";
    /** 市区名称，没传区县名只传城市名时用作查询粒度退化（例如未来市级直接跳这个页面、不再途经区县选择那步的话） */
    public static final String EXTRA_CITY_NAME = "extra_city_name";
    public static final String EXTRA_POINTS = "extra_points";

    /** 原生渲染扛得住比 WebView 多得多的点，但还是留一个上限，避免极端数据量下 Marker 多到影响可读性 */
    private static final int MAX_POINTS = 500;

    /** 标准 Web 墨卡托投影公式里 zoom=0、赤道上的"米/像素"基准值，高德/百度/Google地图共用同一套瓦片体系 */
    private static final double BASE_METERS_PER_PIXEL = 156543.03392;

    private MapView mapView;
    private AMap aMap;
    private final List<Marker> markers = new ArrayList<>();
    private final List<Animator> pulseAnimators = new ArrayList<>();
    private final List<PulseEntry> pulseEntries = new ArrayList<>();

    private View infoCard;
    private TextView tvSpotName;
    private TextView tvSpotMeta;
    private TextView tvSpotAmount;
    private StreetPoint selectedPoint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_district_street_map);

        String districtName = getIntent().getStringExtra(EXTRA_DISTRICT_NAME);
        if (districtName == null || districtName.trim().isEmpty()) districtName = "示例区县（Mock数据）";

        List<StreetPoint> points = getIntent().getParcelableArrayListExtra(EXTRA_POINTS);
        if (points == null || points.isEmpty()) {
            points = generateMockPoints();
        }
        if (points.size() > MAX_POINTS) {
            points = points.subList(0, MAX_POINTS);
        }

        TextView tvBack = findViewById(R.id.tv_street_back);
        TextView tvDistrictName = findViewById(R.id.tv_street_district_name);
        TextView tvPointCount = findViewById(R.id.tv_street_point_count);
        infoCard = findViewById(R.id.layout_street_info_card);
        tvSpotName = findViewById(R.id.tv_street_spot_name);
        tvSpotMeta = findViewById(R.id.tv_street_spot_meta);
        tvSpotAmount = findViewById(R.id.tv_street_spot_amount);
        Button btnNavigate = findViewById(R.id.btn_street_navigate);

        tvBack.setOnClickListener(v -> finish());
        tvDistrictName.setText(districtName);
        tvPointCount.setText("共 " + points.size() + " 笔消费足迹");
        btnNavigate.setOnClickListener(v -> {
            if (selectedPoint != null) {
                AmapNavigationHelper.openNavigation(this, selectedPoint.lat, selectedPoint.lng, selectedPoint.name);
            }
        });

        mapView = findViewById(R.id.map_view);
        mapView.onCreate(savedInstanceState);
        initMap(points);
    }

    private void initMap(List<StreetPoint> points) {
        aMap = mapView.getMap();
        aMap.setMapType(AMap.MAP_TYPE_NIGHT); // 暗夜底图，呼应 App 整体的赛博朋克配色，不需要额外申请自定义地图样式ID
        aMap.getUiSettings().setZoomControlsEnabled(false);

        aMap.setOnMarkerClickListener(marker -> {
            Object obj = marker.getObject();
            if (obj instanceof StreetPoint) {
                showInfoCard((StreetPoint) obj);
                return true;
            }
            return false;
        });
        aMap.setOnMapClickListener(latLng -> hideInfoCard());

        // 缩放/拖动过程中持续回调（不是只在手势结束后才回调一次），光圈像素大小才能跟手，
        // 不会出现"缩放到一半时光圈还是旧尺寸"的过渡期违和感
        aMap.setOnCameraChangeListener(new AMap.OnCameraChangeListener() {
            @Override
            public void onCameraChange(CameraPosition cameraPosition) {
                updatePulseScaleForZoom(cameraPosition.zoom);
            }

            @Override
            public void onCameraChangeFinish(CameraPosition cameraPosition) {
                updatePulseScaleForZoom(cameraPosition.zoom);
            }
        });

        renderMarkers(points);
    }

    /**
     * 逐点创建 Marker + 脉冲光圈。Marker 图标（自定义 View 转 Bitmap）只在这里生成一次，
     * 之后就是静态图片，不会每帧重新画；真正"动"起来的是叠加在同一坐标上的 Circle，
     * 半径和透明度交给 ValueAnimator 驱动，这是原生矢量图层的属性动画，多几十上百个
     * 同时跑也不会像 WebView 里逐个 DOM 元素跑 CSS 动画那样卡。
     */
    private void renderMarkers(List<StreetPoint> points) {
        if (points.isEmpty()) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder();

        for (StreetPoint pt : points) {
            LatLng latLng = new LatLng(pt.lat, pt.lng);
            boundsBuilder.include(latLng);

            View markerView = inflater.inflate(R.layout.marker_street_point, null);
            TextView label = markerView.findViewById(R.id.marker_label);
            View pin = markerView.findViewById(R.id.marker_pin);
            label.setText(pt.name);
            markerView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            markerView.layout(0, 0, markerView.getMeasuredWidth(), markerView.getMeasuredHeight());

            // 锚点Y坐标不硬编码，而是根据实际测量出来的图钉高度占整个 View 高度的比例现算，
            // 这样图钉底部才能准确对齐到经纬度坐标点，不会因为标签文字长短不同而偏移
            float anchorV = markerView.getMeasuredHeight() > 0
                    ? (float) pin.getMeasuredHeight() / markerView.getMeasuredHeight()
                    : 0.5f;

            Marker marker = aMap.addMarker(new MarkerOptions()
                    .position(latLng)
                    .icon(BitmapDescriptorFactory.fromView(markerView))
                    .anchor(0.5f, anchorV)
                    .zIndex(2f)); // 固定比光圈(zIndex 1)高一层，避免缩放重绘瞬间两者叠放顺序不稳定
            marker.setObject(pt);
            markers.add(marker);

            addPulseCircle(latLng);
        }

        try {
            aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 140));
        } catch (Exception e) {
            // 只有一个点时 bounds 可能退化，忽略即可，Marker 本身还是会正常显示在默认视野里
        }
    }

    /**
     * 脉冲光圈半径按"期望的屏幕像素范围"设计（10~32px），不是固定米数：addCircle 时先按
     * 当前缩放级别换算一次，之后每次缩放变化由 updatePulseScaleForZoom() 统一刷新
     * entry.metersPerPixel，动画的 update 回调里始终用最新值，光圈在屏幕上的大小就能
     * 基本不受缩放影响，只由动画进度 t 决定。
     */
    private void addPulseCircle(LatLng center) {
        PulseEntry entry = new PulseEntry();
        entry.center = center;
        entry.metersPerPixel = metersPerPixel(center.latitude,
                aMap.getCameraPosition() != null ? aMap.getCameraPosition().zoom : 16f);

        Circle circle = aMap.addCircle(new CircleOptions()
                .center(center)
                .radius(10 * entry.metersPerPixel)
                .fillColor(withAlpha(0x00FFC6, 70))
                .strokeColor(withAlpha(0x00FFC6, 160))
                .strokeWidth(2f)
                .zIndex(1f));
        entry.circle = circle;
        pulseEntries.add(entry);

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1800);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.setStartDelay((long) (Math.random() * 1200)); // 错开起始相位，别让所有光圈同步呼吸显得呆板
        animator.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            double pixelRadius = 10 + t * 22; // 期望的屏幕像素半径范围，不是米
            circle.setRadius(pixelRadius * entry.metersPerPixel);
            int alpha = (int) (140 * (1 - t));
            circle.setFillColor(withAlpha(0x00FFC6, alpha));
            circle.setStrokeColor(withAlpha(0x00FFC6, Math.min(255, (int) (alpha * 1.6))));
        });
        animator.start();
        pulseAnimators.add(animator);
    }

    /** 缩放变化时统一刷新每个光圈"当前1像素对应多少米"，动画下一帧就会用上新值 */
    private void updatePulseScaleForZoom(float zoom) {
        for (PulseEntry entry : pulseEntries) {
            entry.metersPerPixel = metersPerPixel(entry.center.latitude, zoom);
        }
    }

    /** 标准 Web 墨卡托投影公式：纬度越高，同样像素数对应的实际距离越短（南北两极附近趋近于0） */
    private double metersPerPixel(double latitude, float zoom) {
        return BASE_METERS_PER_PIXEL * Math.cos(Math.toRadians(latitude)) / Math.pow(2, zoom);
    }

    private int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
    }

    private void showInfoCard(StreetPoint pt) {
        selectedPoint = pt;
        tvSpotName.setText(pt.name);
        tvSpotMeta.setText(pt.category + " · " + pt.time);
        tvSpotAmount.setText("¥" + pt.amountFormatted);
        infoCard.setVisibility(View.VISIBLE);
    }

    private void hideInfoCard() {
        infoCard.setVisibility(View.GONE);
        selectedPoint = null;
    }

    /**
     * 【仅测试用】还没接生产数据时，生成一批南京新街口附近的示例消费点，方便直接在真机上
     * 看到原生 Marker + 脉冲动画 + 信息卡片的实际效果。接入真实数据后这个方法就不会再被调用。
     */
    private List<StreetPoint> generateMockPoints() {
        double baseLat = 32.0415, baseLng = 118.7864;
        String[] names = {
                "瑞幸咖啡（新街口店）", "老门东烤鸭店", "先锋书店", "德基广场",
                "水游城", "大众书局", "南京大牌档", "1912街区",
        };
        String[] cats = {
                "餐饮美食", "餐饮美食", "文化教育", "购物消费",
                "购物消费", "文化教育", "餐饮美食", "休闲娱乐",
        };

        List<StreetPoint> list = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            StreetPoint pt = new StreetPoint();
            pt.id = i + 1;
            pt.name = names[i];
            pt.lat = baseLat + (Math.random() - 0.5) * 0.012;
            pt.lng = baseLng + (Math.random() - 0.5) * 0.012;
            pt.amountFormatted = String.format(Locale.CHINA, "%.2f", 15 + Math.random() * 200);
            pt.category = cats[i];
            pt.time = String.format(Locale.CHINA, "09-%02d %02d:%02d", 10 + i, 9 + i % 12, (i * 7) % 60);
            list.add(pt);
        }
        return list;
    }

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
        // ValueAnimator 是 INFINITE 循环，Activity 销毁时必须手动 cancel，不然会一直跑造成内存泄漏
        for (Animator a : pulseAnimators) a.cancel();
        pulseAnimators.clear();
        pulseEntries.clear();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    /** 单个脉冲光圈需要跟踪的状态：圆本身、经纬度中心（算米/像素比要用纬度）、当前米/像素比 */
    private static class PulseEntry {
        Circle circle;
        LatLng center;
        double metersPerPixel = 1;
    }

    /** 单个消费点的数据模型；实现 Parcelable 是为了能通过 Intent extra 从别的页面传点位列表进来 */
    public static class StreetPoint implements Parcelable {
        public long id;
        public String name;
        public double lat;
        public double lng;
        public String amountFormatted;
        public String category;
        public String time;

        public StreetPoint() {}

        protected StreetPoint(Parcel in) {
            id = in.readLong();
            name = in.readString();
            lat = in.readDouble();
            lng = in.readDouble();
            amountFormatted = in.readString();
            category = in.readString();
            time = in.readString();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeLong(id);
            dest.writeString(name);
            dest.writeDouble(lat);
            dest.writeDouble(lng);
            dest.writeString(amountFormatted);
            dest.writeString(category);
            dest.writeString(time);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<StreetPoint> CREATOR = new Creator<StreetPoint>() {
            @Override
            public StreetPoint createFromParcel(Parcel in) {
                return new StreetPoint(in);
            }

            @Override
            public StreetPoint[] newArray(int size) {
                return new StreetPoint[size];
            }
        };
    }
}
