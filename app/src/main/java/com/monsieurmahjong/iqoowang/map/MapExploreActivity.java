package com.monsieurmahjong.iqoowang.map;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 消费足迹地图的宿主 Activity。
 * 挂载 MapBridge JSBridge，支持在 H5 探索地图中点击具体地点直接调起原生高德地图页面 LocationMapActivity，
 * 并支持向 H5 提供 Android 端配置的高德地图 Key。
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
