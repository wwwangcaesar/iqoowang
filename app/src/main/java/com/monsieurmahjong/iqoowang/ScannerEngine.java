package com.monsieurmahjong.iqoowang;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高性能网络与 ADB 探测引擎
 * 支持：全网段高并发探测 + Android 11 mDNS 发现 + 电视特征指纹提取 + 单机深度端口扫描
 */
public class ScannerEngine {

    public interface ScanListener {
        void onDeviceDiscovered(DeviceItem device);
        void onDeviceUpdated(DeviceItem device);
        void onProgress(int current, int total);
        void onScanFinished(int totalFound);
    }

    public interface DeepScanListener {
        void onPortFound(int port, String portDescription);
        void onDeepScanProgress(int current, int total);
        void onDeepScanFinished(List<Integer> openPorts);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, DeviceItem> deviceMap = Collections.synchronizedMap(new LinkedHashMap<>());

    private WifiManager.MulticastLock multicastLock;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener adbDiscoveryListener;
    private NsdManager.DiscoveryListener castDiscoveryListener;
    private ExecutorService threadPool;
    private volatile boolean isScanning = false;

    // 辅助特征探测端口 (电视投屏、小米服务、Airplay、Web)
    private static final int[] TV_PROBE_PORTS = new int[]{8008, 8009, 6095, 9000, 7000, 8080};

    public ScannerEngine(Context context) {
        this.context = context.getApplicationContext();
        this.nsdManager = (NsdManager) this.context.getSystemService(Context.NSD_SERVICE);
    }

    public synchronized void startScan(ScanListener listener) {
        if (isScanning) {
            stopScan();
        }
        isScanning = true;
        deviceMap.clear();

        acquireMulticastLock();
        startNsdDiscovery(listener);
        startSubnetFastScan(listener);
    }

    public synchronized void stopScan() {
        isScanning = false;
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdownNow();
        }
        stopNsdDiscovery();
        releaseMulticastLock();
    }

    public boolean isScanning() {
        return isScanning;
    }

    /**
   * 启动子网 50 线程并发嗅探
     */
    private void startSubnetFastScan(ScanListener listener) {
        threadPool = Executors.newFixedThreadPool(50);
        String subnetPrefix = NetworkUtils.getSubnetPrefix(context);
        String localIp = NetworkUtils.getLocalIpAddress();

        new Thread(() -> {
            int total = 254;
            AtomicInteger progressCounter = new AtomicInteger(0);

            for (int i = 1; i <= total; i++) {
                final String targetIp = subnetPrefix + i;
                final boolean isSelf = targetIp.equals(localIp);

                threadPool.execute(() -> {
                    if (!isScanning) return;

                    try {
                        probeHost(targetIp, isSelf, listener);
                    } catch (Exception ignored) {
                    } finally {
                        int cur = progressCounter.incrementAndGet();
                        mainHandler.post(() -> listener.onProgress(cur, total));
                    }
                });
            }

            threadPool.shutdown();
            try {
                threadPool.awaitTermination(8, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}

            isScanning = false;
            stopNsdDiscovery();
            releaseMulticastLock();

            mainHandler.post(() -> listener.onScanFinished(deviceMap.size()));
        }).start();
    }

    /**
     * 单机探测逻辑：先测 5555，再测电视指纹，最后测在线状态
     */
    private void probeHost(String ip, boolean isSelf, ScanListener listener) {
        // 1. 优先探测 ADB 5555 端口
        long adbLatency = NetworkUtils.checkPortLatency(ip, 5555, 260);
        if (adbLatency >= 0) {
            handleAdbReadyDevice(ip, 5555, adbLatency, isSelf, listener);
            return;
        }

        // 2. 探测常见智能电视 / 盒子服务端口
        int foundTvPort = -1;
        long tvLatency = -1;
        for (int p : TV_PROBE_PORTS) {
            long lat = NetworkUtils.checkPortLatency(ip, p, 180);
            if (lat >= 0) {
                foundTvPort = p;
                tvLatency = lat;
                break;
            }
        }

        if (foundTvPort > 0) {
            handleTvDetectedDevice(ip, foundTvPort, tvLatency, isSelf, listener);
            return;
        }

        // 3. 通用在线探测（快速测试 80 或 443 或 135 或 ICMP）
        long onlineLatency = NetworkUtils.checkPortLatency(ip, 80, 160);
        if (onlineLatency < 0) {
            onlineLatency = NetworkUtils.checkPortLatency(ip, 443, 160);
        }
        if (onlineLatency < 0) {
            try {
                InetAddress addr = InetAddress.getByName(ip);
                if (addr.isReachable(150)) {
                    onlineLatency = 50;
                }
            } catch (Exception ignored) {}
        }

        if (onlineLatency >= 0) {
            handleOnlineDevice(ip, onlineLatency, isSelf, listener);
        }
    }

    private void handleAdbReadyDevice(String ip, int port, long latency, boolean isSelf, ScanListener listener) {
        DeviceItem item = deviceMap.get(ip);
        boolean isNew = (item == null);
        if (isNew) {
            String name = resolveHostName(ip);
            if (isSelf) name += " (本机)";
            item = new DeviceItem(ip, port, name, DeviceItem.STATUS_ADB_READY);
            deviceMap.put(ip, item);
        } else {
            item.setStatus(DeviceItem.STATUS_ADB_READY);
            item.setPort(port);
        }
        item.setPingMs(latency);
        item.addOpenPort(port);
        item.setExtraDetail("ADB 5555 握手成功");

        final DeviceItem finalItem = item;
        mainHandler.post(() -> {
            if (isNew) listener.onDeviceDiscovered(finalItem);
            else listener.onDeviceUpdated(finalItem);
        });
    }

    private void handleTvDetectedDevice(String ip, int port, long latency, boolean isSelf, ScanListener listener) {
        DeviceItem item = deviceMap.get(ip);
        boolean isNew = (item == null);
        if (isNew) {
            String name = resolveHostName(ip);
            if (isSelf) name += " (本机)";
            item = new DeviceItem(ip, 5555, name, DeviceItem.STATUS_TV_DETECTED);
            deviceMap.put(ip, item);
        } else if (item.getStatus() != DeviceItem.STATUS_ADB_READY && item.getStatus() != DeviceItem.STATUS_MDNS_ADB) {
            item.setStatus(DeviceItem.STATUS_TV_DETECTED);
        }
        item.setPingMs(latency);
        item.addOpenPort(port);
        item.setExtraDetail("探测到服务端口: " + port + " (疑似电视/盒子)");

        final DeviceItem finalItem = item;
        mainHandler.post(() -> {
            if (isNew) listener.onDeviceDiscovered(finalItem);
            else listener.onDeviceUpdated(finalItem);
        });
    }

    private void handleOnlineDevice(String ip, long latency, boolean isSelf, ScanListener listener) {
        DeviceItem item = deviceMap.get(ip);
        boolean isNew = (item == null);
        if (isNew) {
            String name = resolveHostName(ip);
            if (isSelf) name += " (本机)";
            item = new DeviceItem(ip, 5555, name, DeviceItem.STATUS_ONLINE_UNKNOWN);
            deviceMap.put(ip, item);
        }
        item.setPingMs(latency);
        item.setExtraDetail("设备在线，未开放 5555");

        final DeviceItem finalItem = item;
        mainHandler.post(() -> {
            if (isNew) listener.onDeviceDiscovered(finalItem);
            else listener.onDeviceUpdated(finalItem);
        });
    }

    private String resolveHostName(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            String name = addr.getCanonicalHostName();
            if (name != null && !name.isEmpty() && !name.equals(ip)) {
                return name;
            }
        } catch (Exception ignored) {}
        return "设备 (" + ip + ")";
    }

    // ----------------- mDNS (NSD) 发现 -----------------

    private void startNsdDiscovery(ScanListener listener) {
        if (nsdManager == null) return;

        // 1. 监听 Android 11+ 原生无线调试广播
        try {
            adbDiscoveryListener = createNsdListener("_adb-tls-connect._tcp", listener, true);
            nsdManager.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, adbDiscoveryListener);
        } catch (Exception ignored) {}

        // 2. 监听 Google Cast / Android TV 设备广播
        try {
            castDiscoveryListener = createNsdListener("_googlecast._tcp", listener, false);
            nsdManager.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, castDiscoveryListener);
        } catch (Exception ignored) {}
    }

    private NsdManager.DiscoveryListener createNsdListener(String serviceType, ScanListener listener, boolean isAdb) {
        return new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                try {
                    nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onServiceResolved(NsdServiceInfo resolvedInfo) {
                            if (resolvedInfo.getHost() == null) return;
                            String ip = resolvedInfo.getHost().getHostAddress();
                            if (ip == null || ip.isEmpty()) return;

                            int port = resolvedInfo.getPort();
                            String serviceName = resolvedInfo.getServiceName();

                            DeviceItem item = deviceMap.get(ip);
                            boolean isNew = (item == null);
                            if (isNew) {
                                int status = isAdb ? DeviceItem.STATUS_MDNS_ADB : DeviceItem.STATUS_TV_DETECTED;
                                item = new DeviceItem(ip, port, serviceName, status);
                                deviceMap.put(ip, item);
                            } else {
                                if (isAdb) {
                                    item.setStatus(DeviceItem.STATUS_MDNS_ADB);
                                    item.setPort(port);
                                }
                                if (serviceName != null && !serviceName.isEmpty()) {
                                    item.setDeviceName(serviceName);
                                }
                            }
                            item.addOpenPort(port);
                            item.setExtraDetail(isAdb ? "mDNS 发现无线调试端口: " + port : "mDNS 发现电视投屏服务");

                            final DeviceItem finalItem = item;
                            mainHandler.post(() -> {
                                if (isNew) listener.onDeviceDiscovered(finalItem);
                                else listener.onDeviceUpdated(finalItem);
                            });
                        }

                        @Override
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {}
                    });
                } catch (Exception ignored) {}
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {

            }

            @Override public void onDiscoveryStarted(String serviceType) {}
            @Override public void onDiscoveryStopped(String serviceType) {}
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
        };
    }

    private void stopNsdDiscovery() {
        if (nsdManager != null) {
            try {
                if (adbDiscoveryListener != null) {
                    nsdManager.stopServiceDiscovery(adbDiscoveryListener);
                    adbDiscoveryListener = null;
                }
            } catch (Exception ignored) {}
            try {
                if (castDiscoveryListener != null) {
                    nsdManager.stopServiceDiscovery(castDiscoveryListener);
                    castDiscoveryListener = null;
                }
            } catch (Exception ignored) {}
        }
    }

    // ----------------- 单机深度端口扫描 -----------------

    /**
     * 对特定未暴露 5555 的 IP 执行深度端口探测
     */
    public void startDeepScan(String ip, DeepScanListener listener) {
        new Thread(() -> {
            List<Integer> targetPorts = new ArrayList<>();
            // ADB 标准区间
            for (int p = 5554; p <= 5585; p++) targetPorts.add(p);
            // 常见服务 & 电视服务 & 投屏
            int[] common = new int[]{22, 80, 443, 7000, 7100, 8008, 8009, 8080, 8888, 9000, 6095, 55555};
            for (int p : common) {
                if (!targetPorts.contains(p)) targetPorts.add(p);
            }

            ExecutorService deepPool = Executors.newFixedThreadPool(20);
            List<Integer> foundOpenPorts = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger count = new AtomicInteger(0);
            int total = targetPorts.size();

            for (int p : targetPorts) {
                deepPool.execute(() -> {
                    long latency = NetworkUtils.checkPortLatency(ip, p, 300);
                    if (latency >= 0) {
                        foundOpenPorts.add(p);
                        String desc = describePort(p);
                        mainHandler.post(() -> listener.onPortFound(p, desc));
                    }
                    int cur = count.incrementAndGet();
                    mainHandler.post(() -> listener.onDeepScanProgress(cur, total));
                });
            }

            deepPool.shutdown();
            try {
                deepPool.awaitTermination(6, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}

            mainHandler.post(() -> listener.onDeepScanFinished(foundOpenPorts));
        }).start();
    }

    private String describePort(int port) {
        if (port == 5555) return "ADB 守护进程 (标准)";
        if (port >= 5554 && port <= 5585) return "ADB Console/Daemon 扩展端口";
        if (port == 8008 || port == 8009) return "Google Cast 投屏服务 (Android TV)";
        if (port == 6095 || port == 9000) return "小米电视远程管理服务";
        if (port == 7000 || port == 7100) return "Airplay / Miracast 投屏";
        if (port == 8080 || port == 8888) return "HTTP 调试/管理服务";
        if (port == 22) return "SSH 终端服务";
        if (port == 55555) return "ADB 备用高位端口";
        return "开放端口 (" + port + ")";
    }

    // ----------------- 组播锁 -----------------

    private void acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    multicastLock = wm.createMulticastLock("CyberAdbMulticastLock");
                    multicastLock.setReferenceCounted(true);
                }
            }
            if (multicastLock != null && !multicastLock.isHeld()) {
                multicastLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception ignored) {}
    }
}
