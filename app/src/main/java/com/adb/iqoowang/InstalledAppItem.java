package com.adb.iqoowang;

import android.graphics.drawable.Drawable;

/**
 * 手机本地已安装的待传输应用实体
 */
public class InstalledAppItem {
    private String appName;
    private String packageName;
    private String versionName;
    private String apkPath;
    private long fileSize;
    private Drawable icon;

    public InstalledAppItem(String appName, String packageName, String versionName, String apkPath, long fileSize, Drawable icon) {
        this.appName = appName;
        this.packageName = packageName;
        this.versionName = versionName;
        this.apkPath = apkPath;
        this.fileSize = fileSize;
        this.icon = icon;
    }

    public String getAppName() {
        return appName;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getVersionName() {
        return versionName;
    }

    public String getApkPath() {
        return apkPath;
    }

    public long getFileSize() {
        return fileSize;
    }

    public Drawable getIcon() {
        return icon;
    }

    public String getFormattedSize() {
        if (fileSize <= 0) return "0 MB";
        float mb = fileSize / (1024f * 1024f);
        return String.format("%.1f MB", mb);
    }
}
