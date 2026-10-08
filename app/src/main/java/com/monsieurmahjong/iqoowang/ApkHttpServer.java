package com.monsieurmahjong.iqoowang;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 局域网极简微型 HTTP 服务器（专为电视大屏下载安装设计，纯 Java 实现，零第三方库）
 */
public class ApkHttpServer {

    public interface OnServerStatusListener {
        void onServerStarted(String serverUrl);
        void onDownloadProgress(String clientIp, String appName);
        void onServerStopped();
    }

    private final int port;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean isRunning = false;
    private final List<InstalledAppItem> sharedApps = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OnServerStatusListener listener;

    public ApkHttpServer(int port) {
        this.port = port;
    }

    public void setSharedApps(List<InstalledAppItem> apps) {
        synchronized (sharedApps) {
            sharedApps.clear();
            if (apps != null) {
                sharedApps.addAll(apps);
            }
        }
    }

    public synchronized void start(String localIp, OnServerStatusListener listener) {
        if (isRunning) return;
        this.listener = listener;

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                isRunning = true;
                threadPool = Executors.newCachedThreadPool();

                String url = "http://" + localIp + ":" + port + "/";
                mainHandler.post(() -> {
                    if (this.listener != null) this.listener.onServerStarted(url);
                });

                while (isRunning && !serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    threadPool.execute(() -> handleClientSocket(socket));
                }
            } catch (Exception e) {
                stop();
            }
        }).start();
    }

    public synchronized void stop() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        mainHandler.post(() -> {
            if (listener != null) listener.onServerStopped();
        });
    }

    public boolean isRunning() {
        return isRunning;
    }

    private void handleClientSocket(Socket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             OutputStream out = socket.getOutputStream()) {

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            String path = parts[1];
            String clientIp = socket.getInetAddress().getHostAddress();

            if (path.startsWith("/download")) {
                handleDownload(path, out, clientIp);
            } else {
                handleIndexHtml(out);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 输出专为电视大屏优化的 HTML 页面 (遥控器方向键自动高亮、字体超大)
     */
    private void handleIndexHtml(OutputStream out) throws Exception {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
            .append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
            .append("<title>电视应用一键安装</title>")
            .append("<style>")
            .append("body { background: #0A0E17; color: #F0F6FC; font-family: sans-serif; text-align: center; padding: 40px; margin: 0; }")
            .append(".title { font-size: 42px; font-weight: bold; color: #00E5FF; margin-bottom: 10px; }")
            .append(".sub { font-size: 24px; color: #8B949E; margin-bottom: 30px; }")
            .append(".card { background: #161B24; border: 2px solid #28344A; border-radius: 20px; max-width: 800px; margin: 0 auto; padding: 30px; box-shadow: 0 10px 30px rgba(0,0,0,0.5); }")
            .append(".app-item { background: #1C2433; border: 2px solid #303E54; border-radius: 16px; padding: 24px; margin-bottom: 20px; display: flex; align-items: center; justify-content: space-between; text-align: left; }")
            .append(".app-name { font-size: 32px; font-weight: bold; color: #FFFFFF; }")
            .append(".app-meta { font-size: 20px; color: #8B949E; margin-top: 6px; }")
            .append(".btn-install { background: #00E5FF; color: #090D14; font-size: 28px; font-weight: bold; padding: 18px 40px; border-radius: 14px; text-decoration: none; display: inline-block; transition: all 0.2s; outline: none; }")
            .append(".btn-install:focus, .btn-install:hover { background: #00E676; color: #000; transform: scale(1.08); box-shadow: 0 0 25px #00E676; }")
            .append(".tip { margin-top: 30px; font-size: 20px; color: #FFAB00; line-height: 1.6; }")
            .append("</style></head><body>");

        html.append("<div class='title'>⚡ 电视应用极速安装</div>");
        html.append("<div class='sub'>使用电视遥控器按【确认键】即可直接下载并自动安装</div>");
        html.append("<div class='card'>");

        synchronized (sharedApps) {
            if (sharedApps.isEmpty()) {
                html.append("<p style='font-size:26px; color:#8B949E;'>手机端暂未添加分享应用，请在手机上勾选应用！</p>");
            } else {
                for (int i = 0; i < sharedApps.size(); i++) {
                    InstalledAppItem app = sharedApps.get(i);
                    html.append("<div class='app-item'>");
                    html.append("  <div>");
                    html.append("    <div class='app-name'>").append(app.getAppName()).append("</div>");
                    html.append("    <div class='app-meta'>版本: ").append(app.getVersionName())
                        .append(" | 大小: ").append(app.getFormattedSize()).append("</div>");
                    html.append("  </div>");
                    html.append("  <a class='btn-install' autofocus href='/download?idx=").append(i).append("'>立即下载安装</a>");
                    html.append("</div>");
                }
            }
        }

        html.append("<div class='tip'>💡 下载完成后，电视系统会自动弹出「应用安装器」，按遥控器点击【安装】即可完成！</div>");
        html.append("</div></body></html>");

        byte[] bytes = html.toString().getBytes("UTF-8");
        out.write("HTTP/1.1 200 OK\r\n".getBytes());
        out.write("Content-Type: text/html; charset=utf-8\r\n".getBytes());
        out.write(("Content-Length: " + bytes.length + "\r\n").getBytes());
        out.write("Connection: close\r\n\r\n".getBytes());
        out.write(bytes);
        out.flush();
    }

    /**
     * 处理 APK 文件二进制流下载
     */
    private void handleDownload(String path, OutputStream out, String clientIp) throws Exception {
        int index = 0;
        if (path.contains("idx=")) {
            String idxStr = path.substring(path.indexOf("idx=") + 4);
            if (idxStr.contains("&")) idxStr = idxStr.substring(0, idxStr.indexOf("&"));
            try {
                index = Integer.parseInt(idxStr);
            } catch (Exception ignored) {}
        }

        InstalledAppItem targetApp = null;
        synchronized (sharedApps) {
            if (index >= 0 && index < sharedApps.size()) {
                targetApp = sharedApps.get(index);
            }
        }

        if (targetApp == null) {
            out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
            return;
        }

        File apkFile = new File(targetApp.getApkPath());
        if (!apkFile.exists()) {
            out.write("HTTP/1.1 404 File Not Found\r\n\r\n".getBytes());
            return;
        }

        final String appName = targetApp.getAppName();
        mainHandler.post(() -> {
            if (listener != null) listener.onDownloadProgress(clientIp, appName);
        });

        long length = apkFile.length();
        out.write("HTTP/1.1 200 OK\r\n".getBytes());
        out.write("Content-Type: application/vnd.android.package-archive\r\n".getBytes());
        out.write(("Content-Disposition: attachment; filename=\"app_install.apk\"\r\n").getBytes());
        out.write(("Content-Length: " + length + "\r\n").getBytes());
        out.write("Connection: close\r\n\r\n".getBytes());

        try (FileInputStream fis = new FileInputStream(apkFile)) {
            byte[] buf = new byte[65536]; // 64KB 高性能缓冲
            int read;
            while ((read = fis.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            out.flush();
        }
    }
}
