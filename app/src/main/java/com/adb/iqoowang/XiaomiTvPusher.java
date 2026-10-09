package com.adb.iqoowang;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 小米电视/盒子私有投屏与远程安装 API 客户端 (基于 6095 端口)
 */
public class XiaomiTvPusher {

    public interface PushCallback {
        void onSuccess(String message);
        void onFailed(String error);
    }

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 向小米电视发送远程拉取并安装指令
     * @param tvIp 小米电视 IP
     * @param downloadUrl 手机端 APK 下载地址 (例如 http://192.168.1.100:8888/download?idx=0)
     */
    public static void pushInstallCommand(String tvIp, String downloadUrl, PushCallback callback) {
        new Thread(() -> {
            try {
                // 小米投屏神器远程安装协议端点
                String targetUrl = "http://" + tvIp + ":6095/controller?action=install&url=" + java.net.URLEncoder.encode(downloadUrl, "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(3000);

                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String resp = br.readLine();
                    mainHandler.post(() -> callback.onSuccess("✔ 指令发送成功！小米电视屏幕已弹出安装提示，请用遥控器点击【允许/确认】。"));
                } else {
                    // 尝试备用 openurl 指令呼出浏览器
                    boolean opened = tryOpenTvBrowser(tvIp, downloadUrl);
                    if (opened) {
                        mainHandler.post(() -> callback.onSuccess("✔ 已远程唤起小米电视浏览器直接进入下载安装页面！"));
                    } else {
                        mainHandler.post(() -> callback.onFailed("电视返回状态码: " + code + "。建议使用方案一通过电视自带浏览器直接打开短网址。"));
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onFailed("连接小米电视 6095 服务超时或被防火墙拦截，请使用方案一电视浏览器直开短链。"));
            }
        }).start();
    }

    private static boolean tryOpenTvBrowser(String tvIp, String url) {
        try {
            String openBrowserUrl = "http://" + tvIp + ":6095/request?action=openurl&url=" + java.net.URLEncoder.encode(url, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(openBrowserUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            return conn.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        }
    }
}
