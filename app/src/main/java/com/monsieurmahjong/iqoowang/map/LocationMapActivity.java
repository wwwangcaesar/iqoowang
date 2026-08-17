package com.monsieurmahjong.iqoowang.map;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.monsieurmahjong.iqoowang.R;

import java.util.Locale;

/**
 * 消费记录的位置详情页。当前先做一个能跑的基础版：显示地点名称/坐标 + 一键唤起
 * 高德地图 App 导航——导航这部分走的是 URI Scheme，不依赖高德地图/搜索 SDK，
 * 不需要 API Key，现在就能用。页面内嵌地图预览 + 路线绘制需要接入高德 3D 地图/搜索 SDK，
 * 两个都要 Android 平台 Key，Key 申请下来之后再升级这个页面，对外的 Intent extra 不用变。
 */
public class LocationMapActivity extends AppCompatActivity {

    public static final String EXTRA_LAT = "extra_lat";
    public static final String EXTRA_LON = "extra_lon";
    public static final String EXTRA_NAME = "extra_name";

    private static final String AMAP_PACKAGE = "com.autonavi.minimap";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_location_map);

        double lat = getIntent().getDoubleExtra(EXTRA_LAT, 0);
        double lon = getIntent().getDoubleExtra(EXTRA_LON, 0);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        String displayName = (name != null && !name.trim().isEmpty()) ? name : "未命名地点";

        TextView tvName = findViewById(R.id.tv_map_location_name);
        TextView tvCoords = findViewById(R.id.tv_map_coords);
        TextView tvBack = findViewById(R.id.tv_map_back);
        View btnNavigate = findViewById(R.id.btn_map_navigate);

        tvName.setText(displayName);
        tvCoords.setText(String.format(Locale.getDefault(), "%.6f, %.6f", lat, lon));

        tvBack.setOnClickListener(v -> finish());
        btnNavigate.setOnClickListener(v -> openAmapNavigation(lat, lon, displayName));
    }

    /**
     * 唤起高德地图 App 做导航：不传起点（slat/slon），高德会自动用设备当前定位当起点，
     * 正好是我们要的效果。dev=0 表示传入的经纬度已经是高德自己的 GCJ-02 坐标，不需要
     * 服务端二次纠偏——这也是为什么定位那一步坚持用高德自己的定位 SDK（见 LocationHelper），
     * 而不是系统原生定位：坐标系从头到尾保持一致，这里不用再做转换。
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
}
