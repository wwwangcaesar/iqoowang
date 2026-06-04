package com.monsieurmahjong.iqoowang.pet;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.pet.PetBridge;

/**
 * 电子宠物 Activity — WebView 兼容性修复版
 *
 * 白屏根因：
 *  旧版代码设置了 setLayerType(LAYER_TYPE_SOFTWARE, null)
 *  这会关闭硬件加速，导致 Canvas 2D 无法正常渲染（软件渲染模式下
 *  canvas.getContext('2d') 的绘制操作会被忽略或极慢）。
 *  普通浏览器默认硬件加速所以正常，WebView 受此设置影响。
 *
 * 修复：
 *  1. 改为 LAYER_TYPE_HARDWARE（开启硬件加速）
 *  2. 页面加载完成后注入 resize 触发，确保 W/H 正确
 *  3. AndroidManifest.xml 同步加 android:hardwareAccelerated="true"
 */
public class PetActivity extends AppCompatActivity {

    private WebView webView;
    private PetBridge petBridge;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        super.onCreate(savedInstanceState);

        // ── WebView ──────────────────────────────────────────
        webView = new WebView(this);

        // ✅ 关键修复：必须使用硬件加速，Canvas 2D 才能正常渲染
        // 旧的 LAYER_TYPE_SOFTWARE 是白屏的直接原因
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        setContentView(webView);

        // ── WebSettings ───────────────────────────────────────
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);          // localStorage 必须
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDefaultTextEncodingName("UTF-8");
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);

        // ── JavaScript Bridge ─────────────────────────────────
        AppDatabase db = AppDatabase.getDatabase(this);
        petBridge = new PetBridge(this, db);
        webView.addJavascriptInterface(petBridge, "AndroidBridge");

        // ── WebViewClient ─────────────────────────────────────
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                // ✅ 页面加载完成后触发 resize，确保 canvas 尺寸正确
                // 有些 WebView 在 onPageFinished 时 innerWidth 仍为 0
                // 延迟 150ms 等 layout 真正完成
                view.postDelayed(() ->
                                view.evaluateJavascript(
                                        "(function(){ if(typeof resize==='function'){ resize(); } })();",
                                        null),
                        150);

                // 沉浸式全屏
                view.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return url != null && !url.startsWith("file://");
            }
        });

        // ── 加载本地 HTML ─────────────────────────────────────
        webView.loadUrl("file:///android_asset/pet/pet.html");
        overridePendingTransition(android.R.anim.fade_in, 0);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else { super.onBackPressed(); overridePendingTransition(0, android.R.anim.fade_out); }
    }

    @Override protected void onResume() { super.onResume(); if (webView != null) webView.onResume(); }
    @Override protected void onPause()  { super.onPause();  if (webView != null) webView.onPause();  }

    @Override
    protected void onDestroy() {
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        super.onDestroy();
    }
}
