package com.monsieurmahjong.iqoowang.map;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.LatLng;
import com.amap.api.services.core.AMapException;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.core.PoiItem;
import com.amap.api.services.geocoder.AoiItem;
import com.amap.api.services.geocoder.GeocodeResult;
import com.amap.api.services.geocoder.GeocodeSearch;
import com.amap.api.services.geocoder.RegeocodeAddress;
import com.amap.api.services.geocoder.RegeocodeQuery;
import com.amap.api.services.geocoder.RegeocodeResult;
import com.monsieurmahjong.iqoowang.R;

import java.util.List;
import java.util.Locale;

/**
 * 手动位置校准页面：
 * 屏幕中心固定图钉，用户拖动地图以更换目标位置。
 * 停止拖动后自动执行逆地理编码获取最新地点名称与省市区行政区划信息。
 */
public class LocationPickerActivity extends AppCompatActivity
        implements AMap.OnCameraChangeListener, GeocodeSearch.OnGeocodeSearchListener {

    public static final String EXTRA_LAT = "extra_lat";
    public static final String EXTRA_LON = "extra_lon";
    public static final String EXTRA_NAME = "extra_name";
    public static final String EXTRA_PROVINCE = "extra_province";
    public static final String EXTRA_CITY = "extra_city";
    public static final String EXTRA_DISTRICT = "extra_district";
    public static final String EXTRA_ADCODE = "extra_adcode";

    private static final String TAG = "LocationPickerActivity";

    public interface OnLocationPickedListener {
        void onLocationPicked(double latitude, double longitude, String locationName,
                              String province, String city, String district, String adCode);
    }

    private static OnLocationPickedListener sListener;

    public static void setOnLocationPickedListener(OnLocationPickedListener listener) {
        sListener = listener;
    }

    private MapView mapView;
    private AMap aMap;
    private GeocodeSearch geocodeSearch;

    private double currentLat;
    private double currentLon;
    private String currentName;
    private String currentProvince;
    private String currentCity;
    private String currentDistrict;
    private String currentAdCode;

    private TextView tvAddress;
    private TextView tvCoords;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_location_picker);

        currentLat = getIntent().getDoubleExtra(EXTRA_LAT, 39.9042);
        currentLon = getIntent().getDoubleExtra(EXTRA_LON, 116.4074);
        currentName = getIntent().getStringExtra(EXTRA_NAME);
        currentProvince = getIntent().getStringExtra(EXTRA_PROVINCE);
        currentCity = getIntent().getStringExtra(EXTRA_CITY);
        currentDistrict = getIntent().getStringExtra(EXTRA_DISTRICT);
        currentAdCode = getIntent().getStringExtra(EXTRA_ADCODE);

        if (currentName == null || currentName.trim().isEmpty()) {
            currentName = "未命名地点";
        }

        tvAddress = findViewById(R.id.lp_tv_address);
        tvCoords = findViewById(R.id.lp_tv_coords);
        TextView btnCancel = findViewById(R.id.lp_btn_cancel);
        Button btnConfirm = findViewById(R.id.lp_btn_confirm);

        tvAddress.setText(currentName);
        tvCoords.setText(String.format(Locale.getDefault(), "%.6f, %.6f", currentLat, currentLon));

        btnCancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        btnConfirm.setOnClickListener(v -> {
            if (sListener != null) {
                sListener.onLocationPicked(currentLat, currentLon, currentName,
                        currentProvince, currentCity, currentDistrict, currentAdCode);
            }
            Intent result = new Intent();
            result.putExtra(EXTRA_LAT, currentLat);
            result.putExtra(EXTRA_LON, currentLon);
            result.putExtra(EXTRA_NAME, currentName);
            result.putExtra(EXTRA_PROVINCE, currentProvince);
            result.putExtra(EXTRA_CITY, currentCity);
            result.putExtra(EXTRA_DISTRICT, currentDistrict);
            result.putExtra(EXTRA_ADCODE, currentAdCode);
            setResult(RESULT_OK, result);
            finish();
        });

        mapView = findViewById(R.id.lp_map_view);
        mapView.onCreate(savedInstanceState);

        initMap();

        try {
            geocodeSearch = new GeocodeSearch(this);
            geocodeSearch.setOnGeocodeSearchListener(this);
        } catch (AMapException e) {
            Log.e(TAG, "初始化 GeocodeSearch 异常", e);
        }
    }

    private void initMap() {
        aMap = mapView.getMap();
        LatLng targetLatLng = new LatLng(currentLat, currentLon);
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(targetLatLng, 16.5f));
        aMap.setOnCameraChangeListener(this);
    }

    @Override
    public void onCameraChange(CameraPosition cameraPosition) {
        if (cameraPosition != null && cameraPosition.target != null) {
            tvCoords.setText(String.format(Locale.getDefault(), "%.6f, %.6f",
                    cameraPosition.target.latitude, cameraPosition.target.longitude));
        }
    }

    @Override
    public void onCameraChangeFinish(CameraPosition cameraPosition) {
        if (cameraPosition == null || cameraPosition.target == null) return;

        currentLat = cameraPosition.target.latitude;
        currentLon = cameraPosition.target.longitude;

        tvCoords.setText(String.format(Locale.getDefault(), "%.6f, %.6f", currentLat, currentLon));
        tvAddress.setText("正在解析位置…");

        if (geocodeSearch != null) {
            LatLonPoint point = new LatLonPoint(currentLat, currentLon);
            RegeocodeQuery query = new RegeocodeQuery(point, 200, GeocodeSearch.AMAP);
            geocodeSearch.getFromLocationAsyn(query);
        }
    }

    @Override
    public void onRegeocodeSearched(RegeocodeResult result, int rCode) {
        if (rCode == AMapException.CODE_AMAP_SUCCESS && result != null && result.getRegeocodeAddress() != null) {
            RegeocodeAddress addr = result.getRegeocodeAddress();
            String resolvedName = pickBestName(addr);
            if (resolvedName != null && !resolvedName.trim().isEmpty()) {
                currentName = resolvedName;
            }

            if (addr.getProvince() != null && !addr.getProvince().isEmpty()) {
                currentProvince = addr.getProvince();
            }
            if (addr.getCity() != null && !addr.getCity().isEmpty()) {
                currentCity = addr.getCity();
            } else if (addr.getProvince() != null) {
                // 直辖市城市可能为空，用省份填充
                currentCity = addr.getProvince();
            }
            if (addr.getDistrict() != null && !addr.getDistrict().isEmpty()) {
                currentDistrict = addr.getDistrict();
            }
            if (addr.getAdCode() != null && !addr.getAdCode().isEmpty()) {
                currentAdCode = addr.getAdCode();
            }

            tvAddress.setText(currentName);
        } else {
            Log.w(TAG, "逆地理编码失败，rCode=" + rCode);
            tvAddress.setText(currentName != null ? currentName : "未知位置");
        }
    }

    @Override
    public void onGeocodeSearched(GeocodeResult geocodeResult, int i) {
        // 未使用
    }

    /** 逆地理编码名称优先级：附近最靠前POI > AOI区域 > 格式化地址 */
    private String pickBestName(RegeocodeAddress addr) {
        List<PoiItem> pois = addr.getPois();
        if (pois != null && !pois.isEmpty()) {
            for (PoiItem poi : pois) {
                if (poi != null && poi.getTitle() != null && !poi.getTitle().trim().isEmpty()) {
                    return poi.getTitle().trim();
                }
            }
        }

        List<AoiItem> aois = addr.getAois();
        if (aois != null && !aois.isEmpty()) {
            for (AoiItem aoi : aois) {
                if (aoi != null && aoi.getAoiName() != null && !aoi.getAoiName().trim().isEmpty()) {
                    return aoi.getAoiName().trim();
                }
            }
        }

        if (addr.getFormatAddress() != null && !addr.getFormatAddress().trim().isEmpty()) {
            return addr.getFormatAddress().trim();
        }

        return null;
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
        sListener = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }
}
