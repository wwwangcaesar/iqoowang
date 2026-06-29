package com.monsieurmahjong.iqoowang;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;


import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;


import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_CODE = 1001;
    private WebView mWebView;
    private List<SongBean> localSongList = new ArrayList<>();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 全屏沉浸式
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        mWebView = findViewById(R.id.web_kid_music);
        initWebViewConfig();
        checkStoragePermission();
    }

    // WebView基础配置
    private void initWebViewConfig() {
        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setDomStorageEnabled(true);

        // JS桥接对象
        mWebView.addJavascriptInterface(new JsBridge(), "AndroidMusic");
        // 加载本地assets下的index.html
        mWebView.loadUrl("file:///android_asset/index.html");
    }

    // 权限校验：存储读取音频
    private void checkStoragePermission() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms = new String[]{Manifest.permission.READ_MEDIA_AUDIO};
        } else {
            perms = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        }

        boolean hasAllPerm = true;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                hasAllPerm = false;
                break;
            }
        }
        if (!hasAllPerm) {
            ActivityCompat.requestPermissions(this, perms, PERMISSION_CODE);
        } else {
            scanLocalMusic();
        }
    }

    // 扫描本地儿歌文件夹 /storage/emulated/0/KidMusic/
    private void scanLocalMusic() {
        localSongList.clear();
        File musicDir = new File("/storage/emulated/0/KidMusic/");
        if (!musicDir.exists()) {
            musicDir.mkdirs();
            showToast("请将儿歌放入手机 KidMusic 文件夹");
            return;
        }
        File[] files = musicDir.listFiles();
        if (files == null || files.length == 0) {
            showToast("本地无儿歌音频文件");
            return;
        }
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a")) {
                SongBean bean = new SongBean();
                bean.songName = f.getName();
                bean.songPath = f.getAbsolutePath();
                localSongList.add(bean);
            }
        }
    }

    // JS交互桥接类
    public class JsBridge {
        // H5获取本地歌曲列表 JSON
        @JavascriptInterface
        public String getLocalSongList() {
            return new Gson().toJson(localSongList);
        }

        // H5调用吐司提示
        @JavascriptInterface
        public void showToast(String msg) {
            mMainHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }

    // 简易吐司封装
    private void showToast(String msg) {
        mMainHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    // 权限申请回调
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CODE) {
            boolean ok = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) ok = false;
            }
            if (ok) {
                scanLocalMusic();
            } else {
                showToast("缺少音频读取权限，无法播放本地儿歌");
            }
        }
    }

    // 实体类：歌曲信息
    public static class SongBean {
        public String songName;
        public String songPath;
    }
}
