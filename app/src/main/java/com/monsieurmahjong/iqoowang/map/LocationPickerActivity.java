package com.monsieurmahjong.iqoowang.map;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
import com.amap.api.services.poisearch.PoiResult;
import com.amap.api.services.poisearch.PoiSearch;
import com.monsieurmahjong.iqoowang.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 手动位置校准页面：
 * 屏幕上方为高德底图 + 中心图钉，下方实时回显高德解析出的附近真实 POI 列表。
 * 用户拖动地图或点击附近地点列表即可一键校准为该 POI 的名称及精确坐标。
 */
public class LocationPickerActivity extends AppCompatActivity
        implements AMap.OnCameraChangeListener, GeocodeSearch.OnGeocodeSearchListener, PoiSearch.OnPoiSearchListener {

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
    private RecyclerView rvPois;
    private PoiAdapter poiAdapter;
    private final List<PoiItemModel> poiList = new ArrayList<>();

    private boolean isProgrammaticMove = false;

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
        rvPois = findViewById(R.id.lp_rv_pois);

        tvAddress.setText(currentName);
        tvCoords.setText(String.format(Locale.getDefault(), "%.6f, %.6f", currentLat, currentLon));

        rvPois.setLayoutManager(new LinearLayoutManager(this));
        poiAdapter = new PoiAdapter(poiList, this::onPoiSelected);
        rvPois.setAdapter(poiAdapter);

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

        // 初始自动发起一次周边真实 POI 搜索与逆地理编码
        searchNearbyPois(currentLat, currentLon);
    }

    private void initMap() {
        aMap = mapView.getMap();
        LatLng targetLatLng = new LatLng(currentLat, currentLon);
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(targetLatLng, 17.5f));
        aMap.setOnCameraChangeListener(this);
    }

    private void searchNearbyPois(double lat, double lon) {
        if (geocodeSearch != null) {
            LatLonPoint point = new LatLonPoint(lat, lon);
            RegeocodeQuery query = new RegeocodeQuery(point, 300, GeocodeSearch.AMAP);
            query.setExtensions("all");
            geocodeSearch.getFromLocationAsyn(query);
        }

        // 发起周边真实商户/店铺精准搜索 (PoiSearch)，按距离排序
        try {
            PoiSearch.Query poiQuery = new PoiSearch.Query("", "餐饮服务|购物服务|生活服务|商务住宅|公司企业|住宿服务|科教文化服务|交通设施服务|金融保险服务|医疗保健服务|风景名胜", currentCity != null ? currentCity : "");
            poiQuery.setPageSize(35);
            poiQuery.setPageNum(1);
            PoiSearch poiSearch = new PoiSearch(this, poiQuery);
            poiSearch.setBound(new PoiSearch.SearchBound(new LatLonPoint(lat, lon), 1000, true)); // true = 按距离升序
            poiSearch.setOnPoiSearchListener(this);
            poiSearch.searchPOIAsyn();
        } catch (AMapException e) {
            Log.e(TAG, "PoiSearch 异常", e);
        }
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

        if (isProgrammaticMove) {
            isProgrammaticMove = false;
            return;
        }

        // 严格以地图中心图钉对应的真实坐标为准，不发生漂移
        currentLat = cameraPosition.target.latitude;
        currentLon = cameraPosition.target.longitude;

        tvCoords.setText(String.format(Locale.getDefault(), "%.6f, %.6f", currentLat, currentLon));
        tvAddress.setText("正在解析附近店铺…");

        searchNearbyPois(currentLat, currentLon);
    }

    @Override
    public void onPoiSearched(PoiResult result, int rCode) {
        if (rCode == AMapException.CODE_AMAP_SUCCESS && result != null && result.getPois() != null) {
            ArrayList<PoiItem> pois = result.getPois();
            if (!pois.isEmpty()) {
                poiList.clear();
                for (int i = 0; i < pois.size(); i++) {
                    PoiItem p = pois.get(i);
                    if (p == null || p.getTitle() == null || p.getTitle().trim().isEmpty()) continue;
                    double pLat = p.getLatLonPoint() != null ? p.getLatLonPoint().getLatitude() : currentLat;
                    double pLon = p.getLatLonPoint() != null ? p.getLatLonPoint().getLongitude() : currentLon;
                    String pSnippet = p.getSnippet();
                    if (pSnippet == null || pSnippet.trim().isEmpty()) {
                        pSnippet = (p.getCityName() != null ? p.getCityName() : "") + (p.getAdName() != null ? p.getAdName() : "");
                    }
                    if (p.getDistance() > 0) {
                        pSnippet = pSnippet + " (距约 " + p.getDistance() + " 米)";
                    }
                    String prov = p.getProvinceName() != null && !p.getProvinceName().isEmpty() ? p.getProvinceName() : currentProvince;
                    String city = p.getCityName() != null && !p.getCityName().isEmpty() ? p.getCityName() : currentCity;
                    String dist = p.getAdName() != null && !p.getAdName().isEmpty() ? p.getAdName() : currentDistrict;
                    String adCode = p.getAdCode() != null && !p.getAdCode().isEmpty() ? p.getAdCode() : currentAdCode;

                    poiList.add(new PoiItemModel(p.getTitle().trim(), pSnippet, pLat, pLon, prov, city, dist, adCode, i == 0));
                }

                if (!poiList.isEmpty()) {
                    PoiItemModel first = poiList.get(0);
                    first.isSelected = true;
                    currentName = first.title;
                    if (first.province != null) currentProvince = first.province;
                    if (first.city != null) currentCity = first.city;
                    if (first.district != null) currentDistrict = first.district;
                    if (first.adCode != null) currentAdCode = first.adCode;
                    tvAddress.setText(currentName);
                }
                poiAdapter.notifyDataSetChanged();
            }
        }
    }

    @Override
    public void onPoiItemSearched(PoiItem poiItem, int i) {
        // 未使用
    }

    @Override
    public void onRegeocodeSearched(RegeocodeResult result, int rCode) {
        if (rCode == AMapException.CODE_AMAP_SUCCESS && result != null && result.getRegeocodeAddress() != null) {
            RegeocodeAddress addr = result.getRegeocodeAddress();

            String prov = addr.getProvince();
            String city = addr.getCity();
            if (city == null || city.isEmpty()) city = prov;
            String dist = addr.getDistrict();
            String adCode = addr.getAdCode();

            if (prov != null && !prov.isEmpty()) currentProvince = prov;
            if (city != null && !city.isEmpty()) currentCity = city;
            if (dist != null && !dist.isEmpty()) currentDistrict = dist;
            if (adCode != null && !adCode.isEmpty()) currentAdCode = adCode;

            // 仅在 PoiSearch 尚未返回数据时，作为兜底填入列表
            if (poiList.isEmpty()) {
                List<PoiItem> pois = addr.getPois();
                if (pois != null && !pois.isEmpty()) {
                    for (int i = 0; i < pois.size(); i++) {
                        PoiItem p = pois.get(i);
                        if (p == null || p.getTitle() == null || p.getTitle().trim().isEmpty()) continue;
                        double pLat = p.getLatLonPoint() != null ? p.getLatLonPoint().getLatitude() : currentLat;
                        double pLon = p.getLatLonPoint() != null ? p.getLatLonPoint().getLongitude() : currentLon;
                        String pSnippet = p.getSnippet();
                        if (pSnippet == null || pSnippet.trim().isEmpty()) {
                            pSnippet = addr.getFormatAddress();
                        }
                        if (p.getDistance() > 0) {
                            pSnippet = pSnippet + " (距约 " + p.getDistance() + " 米)";
                        }
                        poiList.add(new PoiItemModel(p.getTitle().trim(), pSnippet, pLat, pLon, prov, city, dist, adCode, i == 0));
                    }
                }

                if (addr.getFormatAddress() != null && !addr.getFormatAddress().trim().isEmpty()) {
                    String formatAddr = addr.getFormatAddress().trim();
                    poiList.add(new PoiItemModel(formatAddr, "详细地址定位", currentLat, currentLon, prov, city, dist, adCode, poiList.isEmpty()));
                }

                if (!poiList.isEmpty()) {
                    PoiItemModel selected = poiList.get(0);
                    selected.isSelected = true;
                    currentName = selected.title;
                    tvAddress.setText(currentName);
                }
                poiAdapter.notifyDataSetChanged();
            }
        }
    }

    @Override
    public void onGeocodeSearched(GeocodeResult geocodeResult, int i) {
        // 未使用
    }

    private void onPoiSelected(PoiItemModel poi) {
        for (PoiItemModel m : poiList) {
            m.isSelected = (m == poi);
        }
        poiAdapter.notifyDataSetChanged();

        currentName = poi.title;
        currentLat = poi.lat;
        currentLon = poi.lon;
        if (poi.province != null) currentProvince = poi.province;
        if (poi.city != null) currentCity = poi.city;
        if (poi.district != null) currentDistrict = poi.district;
        if (poi.adCode != null) currentAdCode = poi.adCode;

        tvAddress.setText(currentName);
        tvCoords.setText(String.format(Locale.getDefault(), "%.6f, %.6f", currentLat, currentLon));

        // 平滑移动地图到该 POI 的中心点
        if (aMap != null) {
            isProgrammaticMove = true;
            aMap.animateCamera(CameraUpdateFactory.newLatLng(new LatLng(currentLat, currentLon)));
        }
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

    // =========================================================================
    // POI 列表模型与 Adapter
    // =========================================================================

    public static class PoiItemModel {
        public final String title;
        public final String snippet;
        public final double lat;
        public final double lon;
        public final String province;
        public final String city;
        public final String district;
        public final String adCode;
        public boolean isSelected;

        public PoiItemModel(String title, String snippet, double lat, double lon,
                            String province, String city, String district, String adCode, boolean isSelected) {
            this.title = title;
            this.snippet = snippet;
            this.lat = lat;
            this.lon = lon;
            this.province = province;
            this.city = city;
            this.district = district;
            this.adCode = adCode;
            this.isSelected = isSelected;
        }
    }

    private static class PoiAdapter extends RecyclerView.Adapter<PoiAdapter.ViewHolder> {
        private final List<PoiItemModel> data;
        private final OnItemClickListener listener;

        interface OnItemClickListener {
            void onClick(PoiItemModel poi);
        }

        PoiAdapter(List<PoiItemModel> data, OnItemClickListener listener) {
            this.data = data;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_location_poi, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            PoiItemModel item = data.get(position);
            holder.tvTitle.setText(item.title);
            holder.tvSnippet.setText(item.snippet);

            if (item.isSelected) {
                holder.tvTitle.setTextColor(0xFF00FFC6);
                holder.tvSelected.setVisibility(View.VISIBLE);
                holder.itemView.setSelected(true);
            } else {
                holder.tvTitle.setTextColor(0xFFFFFFFF);
                holder.tvSelected.setVisibility(View.GONE);
                holder.itemView.setSelected(false);
            }

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(item);
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle, tvSnippet, tvSelected;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvTitle = itemView.findViewById(R.id.tv_poi_title);
                tvSnippet = itemView.findViewById(R.id.tv_poi_snippet);
                tvSelected = itemView.findViewById(R.id.tv_poi_selected);
            }
        }
    }
}
