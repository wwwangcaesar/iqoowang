package com.monsieurmahjong.iqoowang;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 手机本地已安装 App 提取工具类
 */
public class AppExtractHelper {

    /**
     * 获取手机上已安装的非系统应用列表（开发者安装的调试 App、第三方 App）
     */
    public static List<InstalledAppItem> getInstalledApps(Context context) {
        List<InstalledAppItem> list = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> packages = pm.getInstalledPackages(0);

        for (PackageInfo pkg : packages) {
            // 过滤系统核心自带无界面 App，保留第三方应用与包含启动界面的应用
            boolean isSystemApp = (pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            // 如果不是系统应用，或者是第三方更新过的系统应用，或者本应用自身
            if (!isSystemApp || (pkg.applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                try {
                    String appName = pkg.applicationInfo.loadLabel(pm).toString();
                    String pkgName = pkg.packageName;
                    String versionName = pkg.versionName != null ? pkg.versionName : "1.0";
                    String apkPath = pkg.applicationInfo.sourceDir;
                    Drawable icon = pkg.applicationInfo.loadIcon(pm);

                    File apkFile = new File(apkPath);
                    long size = apkFile.exists() ? apkFile.length() : 0;

                    list.add(new InstalledAppItem(appName, pkgName, versionName, apkPath, size, icon));
                } catch (Exception ignored) {}
            }
        }
        return list;
    }
}
