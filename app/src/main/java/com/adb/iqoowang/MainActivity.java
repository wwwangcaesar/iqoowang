package com.adb.iqoowang;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;


import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQ_CODE = 2001;

    private TextView tvSysStatePill;
    private TextView tvNetworkDesc;
    private TextView tvLocalIp;
    private TextView tvSubnetPrefix;
    private ProgressBar pbScanProgress;
    private TextView tvProgressText;
    private TextView tvCountTotal;
    private TextView tvCountAdb;
    private TextView tvCountTv;

    private TextView tabAllDevices;
    private TextView tabAdbOnly;
    private TextView btnManualConnect;

    private ListView lvDevices;
    private View layoutEmptyView;
    private TextView btnScanToggle;

    private DeviceAdapter adapter;
    private ScannerEngine scannerEngine;
    private ApkHttpServer apkHttpServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupCyberFullscreen();
        setContentView(R.layout.activity_main);

        apkHttpServer = new ApkHttpServer(8888);
        initViews();
        initEngineAndAdapter();
        checkPermissions();
        refreshNetworkState();
    }

    private void setupCyberFullscreen() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(ContextCompat.getColor(this, R.color.cyber_bg));
            window.setNavigationBarColor(ContextCompat.getColor(this, R.color.cyber_bg));
        }
    }

    private void initViews() {
        tvSysStatePill = findViewById(R.id.tv_sys_state_pill);
        tvNetworkDesc = findViewById(R.id.tv_network_desc);
        tvLocalIp = findViewById(R.id.tv_local_ip);
        tvSubnetPrefix = findViewById(R.id.tv_subnet_prefix);
        pbScanProgress = findViewById(R.id.pb_scan_progress);
        tvProgressText = findViewById(R.id.tv_progress_text);
        tvCountTotal = findViewById(R.id.tv_count_total);
        tvCountAdb = findViewById(R.id.tv_count_adb);
        tvCountTv = findViewById(R.id.tv_count_tv);

        tabAllDevices = findViewById(R.id.tab_all_devices);
        tabAdbOnly = findViewById(R.id.tab_adb_only);
        btnManualConnect = findViewById(R.id.btn_manual_connect);

        lvDevices = findViewById(R.id.lv_devices);
        layoutEmptyView = findViewById(R.id.layout_empty_view);
        btnScanToggle = findViewById(R.id.btn_scan_toggle);
        TextView btnTvInstaller = findViewById(R.id.btn_tv_installer);

        // 切换全部设备
        tabAllDevices.setOnClickListener(v -> switchTab(false));

        // 切换仅看 ADB 设备
        tabAdbOnly.setOnClickListener(v -> switchTab(true));

        // 手动直连与诊断
        btnManualConnect.setOnClickListener(v -> {
            TechDialogHelper.showManualConnectDialog(this, scannerEngine, item -> {
                adapter.addOrUpdateDevice(item);
                updateStatsUI();
            });
        });

        // 启动免 ADB 传装电视应用弹窗
        if (btnTvInstaller != null) {
            btnTvInstaller.setOnClickListener(v -> {
                TechDialogHelper.showTvInstallerDialog(MainActivity.this, null, apkHttpServer);
            });
        }

        // 启动/停止扫描
        btnScanToggle.setOnClickListener(v -> toggleScan());
    }

    private void initEngineAndAdapter() {
        scannerEngine = new ScannerEngine(this);

        adapter = new DeviceAdapter(this, new DeviceAdapter.OnItemActionListener() {
            @Override
            public void onTroubleshoot(DeviceItem device) {
                TechDialogHelper.showDeviceDetailDialog(MainActivity.this, device, scannerEngine, apkHttpServer);
            }

            @Override
            public void onDeviceSelected(DeviceItem device) {
                TechDialogHelper.showDeviceDetailDialog(MainActivity.this, device, scannerEngine, apkHttpServer);
            }
        });

        lvDevices.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshNetworkState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scannerEngine != null) {
            scannerEngine.stopScan();
        }
        if (apkHttpServer != null) {
            apkHttpServer.stop();
        }
    }

    private void refreshNetworkState() {
        String netDesc = NetworkUtils.getNetworkDescription(this);
        String localIp = NetworkUtils.getLocalIpAddress();
        String subnet = NetworkUtils.getSubnetPrefix(this);

        tvNetworkDesc.setText(netDesc);
        tvLocalIp.setText("本机: " + localIp);
        tvSubnetPrefix.setText("扫描子网: " + subnet + "0/24 (并发 254 IP)");
    }

    private void toggleScan() {
        if (scannerEngine.isScanning()) {
            scannerEngine.stopScan();
            setScanIdleState();
            Toast.makeText(this, "扫描已停止", Toast.LENGTH_SHORT).show();
        } else {
            startScanProcess();
        }
    }

    private void startScanProcess() {
        refreshNetworkState();
        adapter.setDevices(new ArrayList<>());
        updateStatsUI();

        pbScanProgress.setProgress(0);
        tvProgressText.setText("0/254");

        tvSysStatePill.setText("● PROBING...");
        tvSysStatePill.setTextColor(ContextCompat.getColor(this, R.color.cyber_neon_cyan));
        tvSysStatePill.setBackgroundResource(R.drawable.bg_cyber_badge_cyan);

        btnScanToggle.setText("⏹ 停止扫描 (STOP SCAN)");
        btnScanToggle.setBackgroundResource(R.drawable.bg_cyber_btn_outline_amber);
        btnScanToggle.setTextColor(ContextCompat.getColor(this, R.color.cyber_amber));

        scannerEngine.startScan(new ScannerEngine.ScanListener() {
            @Override
            public void onDeviceDiscovered(DeviceItem device) {
                adapter.addOrUpdateDevice(device);
                updateStatsUI();
            }

            @Override
            public void onDeviceUpdated(DeviceItem device) {
                adapter.addOrUpdateDevice(device);
                updateStatsUI();
            }

            @Override
            public void onProgress(int current, int total) {
                pbScanProgress.setProgress(current);
                tvProgressText.setText(current + "/" + total);
            }

            @Override
            public void onScanFinished(int totalFound) {
                setScanIdleState();
                Toast.makeText(MainActivity.this, "扫描完成！共发现 " + totalFound + " 个设备", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setScanIdleState() {
        tvSysStatePill.setText("● RADAR READY");
        tvSysStatePill.setTextColor(ContextCompat.getColor(this, R.color.cyber_neon_green));
        tvSysStatePill.setBackgroundResource(R.drawable.bg_cyber_badge_green);

        btnScanToggle.setText("⚡ 启动雷达并发扫描 (START SCAN)");
        btnScanToggle.setBackgroundResource(R.drawable.bg_cyber_btn_solid_cyan);
        btnScanToggle.setTextColor(ContextCompat.getColor(this, R.color.cyber_bg));
    }

    private void updateStatsUI() {
        int total = adapter.getTotalCount();
        int adbCount = adapter.getAdbReadyCount();
        int tvCount = adapter.getTvCount();

        tvCountTotal.setText("总发现: " + total);
        tvCountAdb.setText("ADB就绪: " + adbCount);
        tvCountTv.setText("疑似电视: " + tvCount);

        if (adapter.getCount() == 0) {
            layoutEmptyView.setVisibility(View.VISIBLE);
            lvDevices.setVisibility(View.GONE);
        } else {
            layoutEmptyView.setVisibility(View.GONE);
            lvDevices.setVisibility(View.VISIBLE);
        }
    }

    private void switchTab(boolean adbOnly) {
        adapter.setAdbOnlyFilter(adbOnly);
        if (adbOnly) {
            tabAdbOnly.setBackgroundResource(R.drawable.bg_cyber_btn_solid_cyan);
            tabAdbOnly.setTextColor(ContextCompat.getColor(this, R.color.cyber_bg));

            tabAllDevices.setBackgroundResource(R.drawable.bg_cyber_btn_dark);
            tabAllDevices.setTextColor(ContextCompat.getColor(this, R.color.cyber_text_secondary));
        } else {
            tabAllDevices.setBackgroundResource(R.drawable.bg_cyber_btn_solid_cyan);
            tabAllDevices.setTextColor(ContextCompat.getColor(this, R.color.cyber_bg));

            tabAdbOnly.setBackgroundResource(R.drawable.bg_cyber_btn_dark);
            tabAdbOnly.setTextColor(ContextCompat.getColor(this, R.color.cyber_text_secondary));
        }
        updateStatsUI();
    }

    private void checkPermissions() {
        String[] perms = new String[]{
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_MULTICAST_STATE
        };

        boolean needReq = false;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needReq = true;
                break;
            }
        }
        if (needReq) {
            ActivityCompat.requestPermissions(this, perms, PERMISSION_REQ_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshNetworkState();
    }
}
