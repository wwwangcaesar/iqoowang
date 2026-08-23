package com.monsieurmahjong.iqoowang.map;

import android.os.Bundle;
import android.view.Window;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 消费足迹地图的宿主 Activity——纯 WebView 容器，目前不挂任何 JS Bridge。
 * 页面本身（map_explore.html）现阶段只用假数据验证省市下钻+缩放动效，
 * 等视觉效果确认下来，需要真实消费地点数据时再补一个类似 StreakBridge 的
 * 桥接类，把 Android 端的 Expense 位置数据序列化传进去。
 */
public class MapExploreActivity extends AppCompatActivity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // 地图边界数据是运行时按需从阿里 DataV 拉取的，页面本身虽然是本地 file:// 资源，
        // 但必须允许联网请求，纯离线环境下这个页面打不开地图（只会停在加载中）
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.loadUrl("file:///android_asset/mapexplore/map_explore.html");
    }

    @Override
    public void onBackPressed() {
        // 地图页面自己有面包屑/返回按钮做层级回退，这里的系统返回键直接关闭整个页面，
        // 不需要在 WebView 历史栈里回退（这个页面没有多个"网页"，只有一份文档在切换状态）
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
