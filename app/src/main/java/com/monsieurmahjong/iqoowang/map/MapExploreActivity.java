package com.monsieurmahjong.iqoowang.map;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;

import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 消费足迹地图的宿主 Activity。
 * 挂载 MapBridge JSBridge，支持在 H5 探索地图中点击具体地点直接调起原生高德地图页面 LocationMapActivity，
 * 并支持向 H5 提供 Android 端配置的高德地图 Key 以及本地数据库中记录的真实消费与定位数据。
 */
public class MapExploreActivity extends AppCompatActivity {

    private WebView webView;
    private String amapKey = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);

        try {
            ApplicationInfo appInfo = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            if (appInfo.metaData != null) {
                amapKey = appInfo.metaData.getString("com.amap.api.v2.apikey", "");
            }
        } catch (Exception ignored) {
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.addJavascriptInterface(new MapBridge(), "MapBridge");

        webView.loadUrl("file:///android_asset/mapexplore/map_explore.html");
    }

    public class MapBridge {
        @JavascriptInterface
        public void openLocationMap(String name, double lat, double lon) {
            Intent intent = new Intent(MapExploreActivity.this, LocationMapActivity.class);
            intent.putExtra(LocationMapActivity.EXTRA_NAME, name);
            intent.putExtra(LocationMapActivity.EXTRA_LAT, lat);
            intent.putExtra(LocationMapActivity.EXTRA_LON, lon);
            startActivity(intent);
        }

        @JavascriptInterface
        public String getAmapKey() {
            return amapKey != null ? amapKey : "";
        }

        @JavascriptInterface
        public String getExpenseLocationData() {
            try {
                AppDatabase db = AppDatabase.getDatabase(MapExploreActivity.this);
                List<Expense> list = db.expenseDao().getExpensesWithLocationSync();
                JSONArray array = new JSONArray();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
                for (Expense exp : list) {
                    if (exp.getLatitude() == null || exp.getLongitude() == null) continue;
                    JSONObject obj = new JSONObject();
                    obj.put("id", exp.getId());
                    obj.put("name", exp.getLocationName() != null && !exp.getLocationName().trim().isEmpty() ? exp.getLocationName() : "未命名地点");
                    obj.put("amount", exp.getAmount());
                    obj.put("amountFormatted", String.format(Locale.CHINA, "¥ %.2f", exp.getAmount() / 100.0));
                    obj.put("categoryName", exp.getCategoryName() != null ? exp.getCategoryName() : "日常消费");
                    obj.put("timestamp", exp.getTimestamp());
                    obj.put("dateStr", exp.getDate_str());
                    obj.put("timeFormatted", exp.getTimestamp() > 0 ? sdf.format(new Date(exp.getTimestamp())) : (exp.getDate_str() != null ? exp.getDate_str() : ""));
                    obj.put("lat", exp.getLatitude());
                    obj.put("lng", exp.getLongitude());
                    obj.put("province", exp.getProvince() != null ? exp.getProvince() : "");
                    obj.put("city", exp.getCity() != null ? exp.getCity() : "");
                    obj.put("district", exp.getDistrict() != null ? exp.getDistrict() : "");
                    obj.put("adCode", exp.getAdCode() != null ? exp.getAdCode() : "");
                    obj.put("remark", exp.getRemark() != null ? exp.getRemark() : "");
                    array.put(obj);
                }
                return array.toString();
            } catch (Exception e) {
                Log.e("MapExploreActivity", "Error exporting expense locations to JSON", e);
                return "[]";
            }
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
