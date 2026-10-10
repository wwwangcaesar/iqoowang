package com.adb.iqoowang;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class TechDialogHelper {

    /**
     * 弹出机械硬核风设备详情与激活诊断弹窗
     */
    public static void showDeviceDetailDialog(Activity activity, DeviceItem device, ScannerEngine engine, ApkHttpServer apkHttpServer) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_device_detail, null);
        dialog.setContentView(view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.94),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView tvName = view.findViewById(R.id.tv_dialog_device_name);
        TextView tvIpPort = view.findViewById(R.id.tv_dialog_ip_port);
        TextView tvStatus = view.findViewById(R.id.tv_dialog_status_desc);
        TextView btnTestPing = view.findViewById(R.id.btn_test_ping);
        TextView btnDeepScan = view.findViewById(R.id.btn_deep_scan);
        TextView tvLog = view.findViewById(R.id.tv_test_result_log);

        Spinner spinnerBrands = view.findViewById(R.id.spinner_brands);
        TextView tvShortcut = view.findViewById(R.id.tv_guide_shortcut);
        TextView tvSteps = view.findViewById(R.id.tv_guide_steps);
        TextView tvCommand = view.findViewById(R.id.tv_guide_command);

        TextView btnDirectInstall = view.findViewById(R.id.btn_direct_install_app);
        TextView btnCopy = view.findViewById(R.id.btn_copy_command_bottom);
        TextView btnClose = view.findViewById(R.id.btn_close_bottom);
        TextView btnXClose = view.findViewById(R.id.btn_dialog_close);

        if (btnDirectInstall != null) {
            btnDirectInstall.setOnClickListener(v -> {
                dialog.dismiss();
                showTvInstallerDialog(activity, device.getIp(), apkHttpServer);
            });
        }

        tvName.setText(device.getDeviceName());
        tvIpPort.setText("目标 IP: " + device.getIp() + "  |  端口: " + device.getPort());

        if (device.isAdbReady()) {
            tvStatus.setText("状态: ADB 端口已就绪，可直接执行 adb connect 连接！");
            tvStatus.setTextColor(activity.getResources().getColor(R.color.cyber_neon_green));
        } else if (device.getStatus() == DeviceItem.STATUS_TV_DETECTED) {
            tvStatus.setText("状态: 探测到智能电视/投屏特征，但 5555 端口尚未开放！");
            tvStatus.setTextColor(activity.getResources().getColor(R.color.cyber_amber));
        } else {
            tvStatus.setText("状态: 设备在线，但 5555 端口未响应，请查阅下方激活指引。");
            tvStatus.setTextColor(activity.getResources().getColor(R.color.cyber_amber));
        }

        btnTestPing.setOnClickListener(v -> {
            tvLog.setText("> 正在向 " + device.getIp() + ":" + device.getPort() + " 发送 TCP 握手包...");
            new Thread(() -> {
                long latency = NetworkUtils.checkPortLatency(device.getIp(), device.getPort(), 1200);
                activity.runOnUiThread(() -> {
                    if (latency >= 0) {
                        tvLog.setText("✔ 握手成功！耗时: " + latency + "ms\n> 端口开放正常，可直接建立 ADB 会话。");
                    } else {
                        tvLog.setText("✖ 握手失败/连接超时 (1200ms)\n> 原因分析: 设备未开放端口 " + device.getPort() + " 或防火墙拦截。\n> 建议：请参考下方秘籍开启开发者模式。");
                    }
                });
            }).start();
        });

        btnDeepScan.setOnClickListener(v -> {
            btnDeepScan.setEnabled(false);
            tvLog.setText("> 正在深度探测 IP [" + device.getIp() + "] 的隐蔽端口 (ADB/投屏/Web/管理服务)...");
            StringBuilder portLog = new StringBuilder();

            engine.startDeepScan(device.getIp(), new ScannerEngine.DeepScanListener() {
                @Override
                public void onPortFound(int port, String portDescription) {
                    portLog.append("✔ 发现开放端口: ").append(port).append(" -> ").append(portDescription).append("\n");
                    tvLog.setText(portLog.toString());
                }

                @Override
                public void onDeepScanProgress(int current, int total) {}

                @Override
                public void onDeepScanFinished(List<Integer> openPorts) {
                    btnDeepScan.setEnabled(true);
                    if (openPorts.isEmpty()) {
                        tvLog.setText("> 深度扫描完成：未发现常见开放服务端口。\n> 建议通过遥控器按下方秘籍手动激活！");
                    } else {
                        portLog.append("> 深度扫描完成，共捕获 ").append(openPorts.size()).append(" 个开放端口。");
                        tvLog.setText(portLog.toString());
                    }
                }
            });
        });

        List<DeviceActivationGuides.GuideItem> guides = DeviceActivationGuides.getAllGuides();
        List<String> brandNames = new ArrayList<>();
        int selectedIndex = 0;
        String hint = device.getVendorHint();

        for (int i = 0; i < guides.size(); i++) {
            DeviceActivationGuides.GuideItem g = guides.get(i);
            brandNames.add(g.brand + " - " + g.title);
            if (hint != null && (hint.contains(g.brand) || g.brand.contains(hint))) {
                selectedIndex = i;
            }
        }

        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, brandNames);
        spinnerBrands.setAdapter(spinnerAdapter);
        spinnerBrands.setSelection(selectedIndex);

        spinnerBrands.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                DeviceActivationGuides.GuideItem item = guides.get(position);
                tvShortcut.setText(item.shortcutCode);
                tvSteps.setText(item.steps);
                String cmd = item.commandHint.replace("<IP>", device.getIp());
                tvCommand.setText(cmd);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnCopy.setOnClickListener(v -> {
            copyText(activity, device.getAdbCommand());
            Toast.makeText(activity, "已复制连接命令:\n" + device.getAdbCommand(), Toast.LENGTH_SHORT).show();
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnXClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * 手动输入 IP 直连弹窗
     */
    public static void showManualConnectDialog(Activity activity, ScannerEngine engine, OnManualConfirmedListener listener) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_manual_connect, null);
        dialog.setContentView(view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        EditText etIp = view.findViewById(R.id.et_manual_ip);
        EditText etPort = view.findViewById(R.id.et_manual_port);
        TextView tvResult = view.findViewById(R.id.tv_manual_result);
        TextView btnTest = view.findViewById(R.id.btn_manual_test);
        TextView btnDiagnose = view.findViewById(R.id.btn_manual_diagnose);
        TextView btnClose = view.findViewById(R.id.btn_manual_close);

        String prefix = NetworkUtils.getSubnetPrefix(activity);
        etIp.setText(prefix);
        etIp.setSelection(prefix.length());

        btnTest.setOnClickListener(v -> {
            String ip = etIp.getText().toString().trim();
            String portStr = etPort.getText().toString().trim();
            if (ip.isEmpty()) {
                Toast.makeText(activity, "请输入有效的 IP 地址", Toast.LENGTH_SHORT).show();
                return;
            }
            int port = 5555;
            try {
                if (!portStr.isEmpty()) port = Integer.parseInt(portStr);
            } catch (Exception ignored) {}

            final int finalPort = port;
            tvResult.setText("> 正在尝试连接 " + ip + ":" + finalPort + " ...");

            new Thread(() -> {
                long latency = NetworkUtils.checkPortLatency(ip, finalPort, 1200);
                activity.runOnUiThread(() -> {
                    String cmd = "adb connect " + ip + ":" + finalPort;
                    copyText(activity, cmd);
                    if (latency >= 0) {
                        tvResult.setText("✔ 握手成功 (耗时: " + latency + "ms)\n> 已复制: " + cmd);
                        Toast.makeText(activity, "连接成功！已复制命令", Toast.LENGTH_SHORT).show();
                        if (listener != null) {
                            listener.onDeviceAdded(new DeviceItem(ip, finalPort, "手动添加设备", DeviceItem.STATUS_ADB_READY));
                        }
                    } else {
                        tvResult.setText("✖ 握手超时！端口未开启。\n> 已自动为您复制命令: " + cmd + "\n> 建议点击右侧「进入诊断」查看激活方法。");
                    }
                });
            }).start();
        });

        btnDiagnose.setOnClickListener(v -> {
            String ip = etIp.getText().toString().trim();
            String portStr = etPort.getText().toString().trim();
            int port = 5555;
            try {
                if (!portStr.isEmpty()) port = Integer.parseInt(portStr);
            } catch (Exception ignored) {}

            DeviceItem temp = new DeviceItem(ip, port, "手动设备 (" + ip + ")", DeviceItem.STATUS_ONLINE_UNKNOWN);
            dialog.dismiss();
            showDeviceDetailDialog(activity, temp, engine, new ApkHttpServer(8888));
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * 弹出手机传装电视 App 弹窗（核心功能）
     */
    public static void showTvInstallerDialog(Activity activity, String prefilledTvIp, ApkHttpServer apkHttpServer) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_tv_installer, null);
        dialog.setContentView(view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.94),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        Spinner spinnerApps = view.findViewById(R.id.spinner_installed_apps);
        TextView tvAppInfo = view.findViewById(R.id.tv_selected_app_info);
        TextView tvServerUrl = view.findViewById(R.id.tv_server_url);
        TextView btnCopyUrl = view.findViewById(R.id.btn_copy_server_url);
        TextView btnBrowseApk = view.findViewById(R.id.btn_browse_local_apk);

        EditText etAdbIp = view.findViewById(R.id.et_adb_tv_ip);
        EditText etAdbPort = view.findViewById(R.id.et_adb_tv_port);
        ProgressBar pbAdb = view.findViewById(R.id.pb_adb_install);
        TextView btnAdbInstall = view.findViewById(R.id.btn_adb_direct_install);
        TextView tvAdbLog = view.findViewById(R.id.tv_adb_terminal_log);

        TextView btnDone = view.findViewById(R.id.btn_installer_done);
        TextView btnClose = view.findViewById(R.id.btn_installer_close);

        String localIp = NetworkUtils.getLocalIpAddress();
        tvServerUrl.setText("http://" + localIp + ":8888/");

        if (prefilledTvIp != null && !prefilledTvIp.isEmpty()) {
            etAdbIp.setText(prefilledTvIp);
        } else {
            String prefix = NetworkUtils.getSubnetPrefix(activity);
            etAdbIp.setText(prefix);
            etAdbIp.setSelection(prefix.length());
        }

        // 启动本地 HTTP 备用服务
        apkHttpServer.start(localIp, new ApkHttpServer.OnServerStatusListener() {
            @Override public void onServerStarted(String serverUrl) { tvServerUrl.setText(serverUrl); }
            @Override public void onDownloadProgress(String clientIp, String appName) {}
            @Override public void onServerStopped() {}
        });

        final List<InstalledAppItem> appListHolder = new ArrayList<>();
        final int[] selectedIndexHolder = {0};

        // 浏览本地 APK 文件
        if (btnBrowseApk != null) {
            btnBrowseApk.setOnClickListener(v -> {
                activeApkPickerCallback = item -> {
                    appListHolder.add(0, item);
                    selectedIndexHolder[0] = 0;
                    List<String> names = new ArrayList<>();
                    for (InstalledAppItem app : appListHolder) {
                        names.add(app.getAppName() + " (" + app.getFormattedSize() + ")");
                    }
                    spinnerApps.setAdapter(new ArrayAdapter<>(activity,
                            android.R.layout.simple_spinner_dropdown_item, names));
                    spinnerApps.setSelection(0);
                    tvAppInfo.setText("已选: " + item.getAppName() + " | 大小: " + item.getFormattedSize()
                            + "\n路径: " + item.getApkPath());
                };
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"application/vnd.android.package-archive", "application/octet-stream"});
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                activity.startActivityForResult(Intent.createChooser(intent, "选择本地 APK 安装包"),
                        MainActivity.REQ_CODE_PICK_APK);
            });
        }

        // 异步加载已安装应用 + 本地 APK
        new Thread(() -> {
            List<InstalledAppItem> localApks = AppExtractHelper.scanLocalDownloadApks(activity);
            List<InstalledAppItem> userApps = AppExtractHelper.getInstalledUserApps(activity);
            List<InstalledAppItem> combined = new ArrayList<>();
            combined.addAll(localApks);
            combined.addAll(userApps);

            activity.runOnUiThread(() -> {
                if (combined.isEmpty()) {
                    tvAppInfo.setText("未发现用户安装应用或本地 APK，可点击右上角浏览文件");
                    return;
                }
                appListHolder.clear();
                appListHolder.addAll(combined);
                apkHttpServer.setSharedApps(combined);

                List<String> names = new ArrayList<>();
                for (InstalledAppItem app : combined) {
                    names.add(app.getAppName() + " (" + app.getFormattedSize() + ")");
                }
                spinnerApps.setAdapter(new ArrayAdapter<>(activity,
                        android.R.layout.simple_spinner_dropdown_item, names));

                spinnerApps.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                        selectedIndexHolder[0] = position;
                        InstalledAppItem selected = appListHolder.get(position);
                        List<InstalledAppItem> single = new ArrayList<>();
                        single.add(selected);
                        apkHttpServer.setSharedApps(single);
                        tvAppInfo.setText("已选: " + selected.getAppName() + " | 大小: " + selected.getFormattedSize()
                                + "\n路径: " + selected.getApkPath());
                    }
                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });
            });
        }).start();

        // ⚡ 核心：手机直接 ADB 远程安装到电视
        btnAdbInstall.setOnClickListener(v -> {
            if (appListHolder.isEmpty()) {
                Toast.makeText(activity, "正在加载应用列表，请稍候", Toast.LENGTH_SHORT).show();
                return;
            }
            String tvIp = etAdbIp.getText().toString().trim();
            String portStr = etAdbPort.getText().toString().trim();
            if (tvIp.isEmpty()) {
                Toast.makeText(activity, "请输入目标电视的 IP 地址", Toast.LENGTH_SHORT).show();
                return;
            }
            int port = 5555;
            try {
                if (!portStr.isEmpty()) port = Integer.parseInt(portStr);
            } catch (Exception ignored) {}

            InstalledAppItem selectedApp = appListHolder.get(selectedIndexHolder[0]);
            File apkFile = new File(selectedApp.getApkPath());
            if (!apkFile.exists()) {
                Toast.makeText(activity, "APK 文件不存在或无法读取", Toast.LENGTH_SHORT).show();
                return;
            }

            btnAdbInstall.setEnabled(false);
            pbAdb.setProgress(0);
            tvAdbLog.setText("> 正在建立 ADB 调试会话 [" + tvIp + ":" + port + "] ...");

            final int finalPort = port;
            AdbClientEngine adbEngine = new AdbClientEngine(activity);
            adbEngine.installApk(tvIp, finalPort, apkFile, new AdbClientEngine.AdbInstallListener() {
                @Override
                public void onLog(String message) {
                    activity.runOnUiThread(() -> tvAdbLog.append("\n" + message));
                }

                @Override
                public void onProgress(long transferredBytes, long totalBytes) {
                    if (totalBytes > 0) {
                        int progress = (int) ((transferredBytes * 100) / totalBytes);
                        activity.runOnUiThread(() -> {
                            pbAdb.setProgress(progress);
                        });
                    }
                }

                @Override
                public void onSuccess(String message) {
                    activity.runOnUiThread(() -> {
                        btnAdbInstall.setEnabled(true);
                        pbAdb.setProgress(100);
                        tvAdbLog.append("\n\n" + message);
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onError(String error) {
                    activity.runOnUiThread(() -> {
                        btnAdbInstall.setEnabled(true);
                        tvAdbLog.append("\n\n✖ " + error);
                        Toast.makeText(activity, "安装未完成: " + error, Toast.LENGTH_LONG).show();
                    });
                }
            });

        });

        // 复制 Web 局域网直装地址（电视有浏览器时备用）
        btnCopyUrl.setOnClickListener(v -> {
            copyText(activity, tvServerUrl.getText().toString());
            Toast.makeText(activity, "已复制！请在电视浏览器中打开该网址", Toast.LENGTH_SHORT).show();
        });

        btnDone.setOnClickListener(v -> dialog.dismiss());
        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private static void copyText(Context context, String text) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            ClipData clip = ClipData.newPlainText("ADB Command", text);
            cm.setPrimaryClip(clip);
        }
    }

    public interface OnManualConfirmedListener {
        void onDeviceAdded(DeviceItem item);
    }

    public interface OnApkSelectedCallback {
        void onApkSelected(InstalledAppItem item);
    }

    public static OnApkSelectedCallback activeApkPickerCallback;

    public static void onApkFilePicked(Activity activity, android.net.Uri uri) {
        new Thread(() -> {
            InstalledAppItem item = AppExtractHelper.parseApkUri(activity, uri);
            if (item != null) {
                activity.runOnUiThread(() -> {
                    if (activeApkPickerCallback != null) {
                        activeApkPickerCallback.onApkSelected(item);
                        Toast.makeText(activity, "已载入本地 APK: " + item.getAppName(), Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                activity.runOnUiThread(() -> Toast.makeText(activity, "未能成功解析该 APK 文件", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
