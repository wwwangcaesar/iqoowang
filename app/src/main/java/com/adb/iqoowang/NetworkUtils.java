package com.adb.iqoowang;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.List;

public class NetworkUtils {

    /**
     * 获取当前局域网前缀，例如 "192.168.1." 或热点 "192.168.43."
     */
    public static String getSubnetPrefix(Context context) {
        String localIp = getLocalIpAddress();
        if (localIp != null && localIp.contains(".") && !localIp.startsWith("127.")) {
            return localIp.substring(0, localIp.lastIndexOf(".") + 1);
        }

        // 尝试通过网络接口寻找
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : interfaces) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase();
                // 常见 Wi-Fi 或热点 (ap/softap/wlan/rndis)
                if (name.contains("wlan") || name.contains("ap") || name.contains("rndis") || name.contains("eth")) {
                    for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            String ip = addr.getHostAddress();
                            if (ip != null && ip.contains(".")) {
                                return ip.substring(0, ip.lastIndexOf(".") + 1);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return "192.168.1.";
    }

    /**
     * 获取本机当前活动 IPv4 地址
     */
    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : interfaces) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase();
                // 优先选取 wlan 或 ap 网卡
                if (name.contains("wlan") || name.contains("ap") || name.contains("softap") || name.contains("eth")) {
                    for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
            // 兜底找任意可用非环回 IPv4
            for (NetworkInterface ni : interfaces) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    /**
     * 判断当前是否处于便携热点模式 (SoftAP)
     */
    public static boolean isHotspotActive() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : interfaces) {
                if (!ni.isUp()) continue;
                String name = ni.getName().toLowerCase();
                if (name.contains("ap") || name.contains("softap")) {
                    for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 获取当前网络类型描述标签
     */
    public static String getNetworkDescription(Context context) {
        if (isHotspotActive()) {
            return "🔥 本机便携式热点 (AP模式)";
        }
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getSSID() != null) {
                    String ssid = info.getSSID().replace("\"", "");
                    if (!ssid.isEmpty() && !ssid.equals("<unknown ssid>")) {
                        return "📶 Wi-Fi: " + ssid;
                    }
                }
                return "📶 当前已连接 Wi-Fi";
            }
        } catch (Exception ignored) {}
        return "🌐 局域网在线";
    }

    /**
     * 测试单端口连通性及耗时 (ms)，返回 -1 表示超时或不可达
     */
    public static long checkPortLatency(String host, int port, int timeoutMs) {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return -1;
        }
    }
}
