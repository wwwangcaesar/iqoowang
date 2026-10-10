package com.adb.iqoowang;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 手机本地第三方已安装 App 与本地下载 APK 提取工具类
 */
public class AppExtractHelper {

    /**
     * 获取手机已安装的纯第三方用户应用（严格过滤系统级与定制服务应用）
     */
    public static List<InstalledAppItem> getInstalledUserApps(Context context) {
        List<InstalledAppItem> list = new ArrayList<>();
        PackageManager pm = context.getPackageManager();

        try {
            List<PackageInfo> packages = pm.getInstalledPackages(0);
            for (PackageInfo pkg : packages) {
                if (pkg.applicationInfo == null) continue;

                boolean isSystem = (pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean isUpdatedSystem = (pkg.applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;

                // 1. 严格排除纯系统级底层应用
                if (isSystem && !isUpdatedSystem) {
                    continue;
                }

                String pkgName = pkg.packageName;

                // 2. 必须具备启动入口(桌面图标)，过滤掉没有界面的底层服务/插件
                if (pm.getLaunchIntentForPackage(pkgName) == null) {
                    continue;
                }

                // 3. 排除厂商自带的系统定制包 (这些通常无法在不同厂商的电视上跑)
                if (pkgName.startsWith("com.android.") ||
                    pkgName.startsWith("com.qualcomm.") ||
                    pkgName.startsWith("com.mediatek.") ||
                    pkgName.startsWith("android") ||
                    pkgName.startsWith("com.google.android.overlay") ||
                    pkgName.startsWith("com.miui.system") ||
                    pkgName.startsWith("com.vivo.framework") ||
                    pkgName.startsWith("com.coloros.system")) {
                    continue;
                }

                try {
                    String appName = pkg.applicationInfo.loadLabel(pm).toString();
                    String versionName = pkg.versionName != null ? pkg.versionName : "1.0";
                    String apkPath = pkg.applicationInfo.sourceDir;
                    Drawable icon = pkg.applicationInfo.loadIcon(pm);

                    File apkFile = new File(apkPath);
                    long size = apkFile.exists() ? apkFile.length() : 0;

                    if (size > 0) {
                        list.add(new InstalledAppItem(appName, pkgName, versionName, apkPath, size, icon, false));
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // 4. 按应用名称首字母拼音/名称自然排序
        Collections.sort(list, (a, b) -> a.getAppName().compareToIgnoreCase(b.getAppName()));
        return list;
    }

    /**
     * 自动扫描手机常用下载目录中未安装的 .apk 安装包（如应用宝、浏览器下载目录）
     */
    public static List<InstalledAppItem> scanLocalDownloadApks(Context context) {
        List<InstalledAppItem> list = new ArrayList<>();
        PackageManager pm = context.getPackageManager();

        List<File> scanDirs = new ArrayList<>();
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloads != null && downloads.exists()) {
                scanDirs.add(downloads);
                scanDirs.add(new File(downloads, "Tencent")); // 应用宝默认下载
                scanDirs.add(new File(downloads, "WeiXin"));
                scanDirs.add(new File(downloads, "browser"));
            }
            File internalStorage = Environment.getExternalStorageDirectory();
            if (internalStorage != null && internalStorage.exists()) {
                scanDirs.add(new File(internalStorage, "Download"));
                scanDirs.add(new File(internalStorage, "tencent/QQfile_recv"));
                scanDirs.add(new File(internalStorage, "Android/data/com.tencent.android.qqdownloader/files/apk")); // 应用宝专属缓存
            }
        } catch (Exception ignored) {}

        for (File dir : scanDirs) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;

            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase().endsWith(".apk") && file.length() > 0) {
                    InstalledAppItem item = parseApkFile(pm, file);
                    if (item != null) {
                        // 避免重复路径
                        boolean exists = false;
                        for (InstalledAppItem old : list) {
                            if (old.getApkPath().equals(item.getApkPath())) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) list.add(item);
                    }
                }
            }
        }

        return list;
    }

    /**
     * 解析一个具体的本地 APK 文件
     */
    public static InstalledAppItem parseApkFile(PackageManager pm, File apkFile) {
        try {
            PackageInfo info = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), PackageManager.GET_ACTIVITIES);
            if (info != null && info.applicationInfo != null) {
                info.applicationInfo.sourceDir = apkFile.getAbsolutePath();
                info.applicationInfo.publicSourceDir = apkFile.getAbsolutePath();

                String appName = info.applicationInfo.loadLabel(pm).toString();
                if (appName == null || appName.isEmpty() || appName.equals(info.packageName)) {
                    appName = apkFile.getName().replace(".apk", "");
                }
                String versionName = info.versionName != null ? info.versionName : "1.0";
                Drawable icon = info.applicationInfo.loadIcon(pm);

                return new InstalledAppItem("[本地安装包] " + appName, info.packageName, versionName,
                        apkFile.getAbsolutePath(), apkFile.length(), icon, true);
            } else {
                // 无法解析 manifest 时仍提供兜底文件项
                return new InstalledAppItem("[本地文件] " + apkFile.getName(), "local.file.apk", "1.0",
                        apkFile.getAbsolutePath(), apkFile.length(), null, true);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 从系统文件选择器返回的 Uri 解析并缓存 APK 文件
     */
    public static InstalledAppItem parseApkUri(Context context, Uri uri) {
        try {
            File cacheFile = new File(context.getCacheDir(), "picked_install.apk");
            if (cacheFile.exists()) cacheFile.delete();

            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(cacheFile)) {
                if (in == null) return null;
                byte[] buf = new byte[65536];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
            }

            PackageManager pm = context.getPackageManager();
            return parseApkFile(pm, cacheFile);
        } catch (Exception ignored) {}
        return null;
    }
}
