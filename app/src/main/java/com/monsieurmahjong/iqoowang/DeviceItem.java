package com.monsieurmahjong.iqoowang;

import java.util.ArrayList;
import java.util.List;

/**
 * 局域网探测设备模型实体
 */
public class DeviceItem {

    public static final int STATUS_ADB_READY = 1;       // 5555 开放，可直接 adb connect
    public static final int STATUS_MDNS_ADB = 2;        // Android 11+ 原生无线调试 (mDNS 动态端口)
    public static final int STATUS_TV_DETECTED = 3;     // 疑似 Android 电视/盒子 (Cast/投屏开放，但5555未开)
    public static final int STATUS_ONLINE_UNKNOWN = 4;  // 在线设备，5555未开放

    private String ip;
    private int port;
    private String deviceName;
    private String vendorHint;
    private int status;
    private long pingMs;
    private final List<Integer> openPorts = new ArrayList<>();
    private String extraDetail;

    public DeviceItem(String ip, int port, String deviceName, int status) {
        this.ip = ip;
        this.port = port;
        this.deviceName = deviceName != null && !deviceName.isEmpty() ? deviceName : "Android 设备 (" + ip + ")";
        this.status = status;
        this.vendorHint = detectVendor(this.deviceName);
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port > 0 ? port : 5555;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
        this.vendorHint = detectVendor(deviceName);
    }

    public String getVendorHint() {
        return vendorHint != null ? vendorHint : "通用 Android";
    }

    public void setVendorHint(String vendorHint) {
        this.vendorHint = vendorHint;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public long getPingMs() {
        return pingMs;
    }

    public void setPingMs(long pingMs) {
        this.pingMs = pingMs;
    }

    public List<Integer> getOpenPorts() {
        return openPorts;
    }

    public void addOpenPort(int port) {
        if (!openPorts.contains(port)) {
            openPorts.add(port);
        }
    }

    public String getExtraDetail() {
        return extraDetail;
    }

    public void setExtraDetail(String extraDetail) {
        this.extraDetail = extraDetail;
    }

    public boolean isAdbReady() {
        return status == STATUS_ADB_READY || status == STATUS_MDNS_ADB;
    }

    public String getAdbCommand() {
        int targetPort = (port > 0) ? port : 5555;
        return "adb connect " + ip + ":" + targetPort;
    }

    public String getStatusBadgeText() {
        switch (status) {
            case STATUS_ADB_READY:
                return "ADB 5555 就绪";
            case STATUS_MDNS_ADB:
                return "无线调试 (动态:" + port + ")";
            case STATUS_TV_DETECTED:
                return "智能电视 (未开5555)";
            default:
                return "在线·未开放ADB";
        }
    }

    /**
     * 根据设备名称或特征判断品牌
     */
    public static String detectVendor(String name) {
        if (name == null) return "通用设备";
        String lower = name.toLowerCase();
        if (lower.contains("xiaomi") || lower.contains("mi") || lower.contains("redmi") || lower.contains("mitv")) {
            return "小米 / Redmi";
        } else if (lower.contains("tcl")) {
            return "TCL 电视";
        } else if (lower.contains("hisense") || lower.contains("vidaa")) {
            return "海信 / Hisense";
        } else if (lower.contains("skyworth") || lower.contains("coocaa")) {
            return "创维 / 酷开";
        } else if (lower.contains("sony") || lower.contains("bravia")) {
            return "索尼 Bravia";
        } else if (lower.contains("huawei") || lower.contains("honor")) {
            return "华为 / 荣耀智慧屏";
        } else if (lower.contains("oppo") || lower.contains("realme") || lower.contains("oneplus")) {
            return "OPPO / 一加";
        } else if (lower.contains("vivo") || lower.contains("iqoo")) {
            return "vivo / iQOO";
        } else if (lower.contains("google") || lower.contains("pixel") || lower.contains("cast")) {
            return "Google / Android TV";
        }
        return "通用 Android";
    }
}
