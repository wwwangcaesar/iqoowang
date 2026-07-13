package com.monsieurmahjong.iqoowang;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;


import com.monsieurmahjong.iqoowang.util.DatabaseManager;
import com.monsieurmahjong.iqoowang.util.StockBridge;

import java.io.File;

/**
 * MainActivity
 *
 * 全屏 WebView 承载 HTML 前端，Java层提供：
 *   · 行情数据（东方财富API）
 *   · GreenDAO 数据库
 *   · 本地AI推理（MNN + Qwen2.5）
 *
 * iQOO 11s 适配要点：
 *   · 刘海/水滴屏：延伸到状态栏下方（edge-to-edge）
 *   · 硬件加速：LAYER_TYPE_HARDWARE
 *   · 高刷：120Hz，动画流畅
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQ_PERMISSIONS = 100;

    private WebView mWebView;
    private StockBridge mBridge;

    // ──────────────────────────────────────────
    // 生命周期
    // ──────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏沉浸式（iQOO 11s 支持边到边显示）
        setupImmersive();

        // 初始化数据库
        DatabaseManager.init(this);

        // WebView 初始化
        setupWebView();

        // 申请权限
        checkPermissions();

        // 加载前端
        mWebView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWebView != null) mWebView.onResume();
        // 恢复行情刷新
        mWebView.evaluateJavascript("window.onAppResume && window.onAppResume()", null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mWebView != null) mWebView.onPause();
        // 暂停行情刷新，节省电量
        mWebView.evaluateJavascript("window.onAppPause && window.onAppPause()", null);
    }

    @Override
    protected void onDestroy() {
        if (mWebView != null) {
            mWebView.stopLoading();
            mWebView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // WebView 历史回退（如有内部路由）
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ──────────────────────────────────────────
    // WebView 配置
    // ──────────────────────────────────────────

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        mWebView = new WebView(this);
        setContentView(mWebView);

        // 硬件加速（iQOO 11s GPU充足，务必开启）
        mWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mWebView.setBackgroundColor(Color.parseColor("#070d1a"));

        WebSettings ws = mWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);          // localStorage
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); // 允许HTTP行情接口
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(false);
        ws.setSupportZoom(false);
        ws.setTextZoom(100);

        // 注册Java桥
        mBridge = new StockBridge(this, mWebView);
        mWebView.addJavascriptInterface(mBridge, "Android");

        // WebViewClient
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "onPageFinished: " + url);
                // 加载完成后：注入持仓数据、资产历史、AI状态
                injectInitialData();
                // 预热AI
                view.evaluateJavascript("Android.warmupAI()", null);
                // 启动行情刷新
                view.evaluateJavascript("Android.setAutoRefresh(true)", null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Log.e(TAG, "WebView error: " + error.getDescription());
                }
            }
        });

        // WebChromeClient（处理console.log）
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                Log.d(TAG + "/JS", "[" + msg.messageLevel() + "] " +
                        msg.message() + " (" + msg.sourceId() + ":" + msg.lineNumber() + ")");
                return true;
            }
        });

        // 开发调试（生产环境注释掉）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }

    /** 页面加载完成后向JS注入初始化数据 */
    private void injectInitialData() {
        // 持仓数据
        String positions = DatabaseManager.get().getPositionsJson();
        String posEsc = positions.replace("\\", "\\\\").replace("'", "\\'");
        mWebView.evaluateJavascript(
                "window.initPositions && window.initPositions('" + posEsc + "')", null);

        // 资产曲线（近60天）
        String assets = DatabaseManager.get().getDailyAssetJson(60);
        String assetEsc = assets.replace("\\", "\\\\").replace("'", "\\'");
        mWebView.evaluateJavascript(
                "window.initAssetHistory && window.initAssetHistory('" + assetEsc + "')", null);

        // 交易历史
        String trades = DatabaseManager.get().getTradeHistoryJson(100);
        String tradeEsc = trades.replace("\\", "\\\\").replace("'", "\\'");
        mWebView.evaluateJavascript(
                "window.initTradeHistory && window.initTradeHistory('" + tradeEsc + "')", null);

        // 统计数据
        String stats = DatabaseManager.get().getTradeHistoryJson(1000);
        mWebView.evaluateJavascript(
                "window.initStats && window.initStats()", null);
    }

    // ──────────────────────────────────────────
    // 沉浸式 / 全屏（iQOO 11s）
    // ──────────────────────────────────────────

    private void setupImmersive() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();

        // 延伸到状态栏 + 导航栏
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.parseColor("#070d1a"));
        }

        // 内容延伸到状态栏下方
        View decorView = window.getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        decorView.setSystemUiVisibility(flags);

        // iQOO 11s 刘海屏适配（Android 9+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }
    }

    // ──────────────────────────────────────────
    // 权限
    // ──────────────────────────────────────────

    private void checkPermissions() {
        java.util.List<String> neededList = new java.util.ArrayList<>(java.util.Arrays.asList(
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,  // 模型文件写入
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.VIBRATE
        ));
        // Android 13+ 通知权限需运行时申请，否则实时监控的买入/止损信号通知发不出来
        if (Build.VERSION.SDK_INT >= 33) {
            neededList.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        String[] needed = neededList.toArray(new String[0]);
        boolean allGranted = true;
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, needed, REQ_PERMISSIONS);
        } else {
            ensureModelDirectory();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQ_PERMISSIONS) {
            ensureModelDirectory();
        }
    }

    /** 确保AI模型目录存在，并在JS中显示提示 */
    private void ensureModelDirectory() {
        File modelDir = new File(getExternalFilesDir(null),
                "qwen3.5-4b-instruct-int4");
        if (!modelDir.exists()) {
            modelDir.mkdirs();
            Log.i(TAG, "模型目录已创建: " + modelDir.getAbsolutePath());
            // 提示用户下载模型
            String path = modelDir.getAbsolutePath().replace("'", "\\'");
            mWebView.post(() -> mWebView.evaluateJavascript(
                    "window.showModelTip && window.showModelTip('" + path + "')", null));
        }
    }
}