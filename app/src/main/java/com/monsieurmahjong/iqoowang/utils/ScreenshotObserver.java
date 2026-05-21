package com.monsieurmahjong.iqoowang.utils;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

public class ScreenshotObserver extends ContentObserver {
    private final Context context;
    private final OnScreenshotDetectedListener onScreenshotDetected;
    private Uri lastScannedUri;

    // 替代Kotlin函数类型的回调接口
    public interface OnScreenshotDetectedListener {
        void onScreenshotDetected(Uri uri);
    }

    /**
     * 构造函数，与原Kotlin完全一致
     * @param context 上下文
     * @param onScreenshotDetected 截图检测成功回调
     */
    public ScreenshotObserver(Context context, OnScreenshotDetectedListener onScreenshotDetected) {
        super(new Handler(Looper.getMainLooper()));
        this.context = context;
        this.onScreenshotDetected = onScreenshotDetected;
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        super.onChange(selfChange, uri);
        // 完全对应Kotlin的?.let逻辑
        if (uri != null) {
            // 防止重复触发同一Uri
            if (uri.equals(lastScannedUri)) {
                return;
            }
            lastScannedUri = uri;

            // 等效于Kotlin的CoroutineScope(Dispatchers.IO).launch
            // 在后台线程执行数据库查询和验证逻辑
            new Thread(this::verifyAndProcessScreenshot).start();
        }
    }

    private void verifyAndProcessScreenshot() {
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_ADDED
        };

        Cursor cursor = null;
        try {
            // 完全对应原查询参数：查询最新的1张图片
            cursor = context.getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC LIMIT 1"
            );

            // 对应Kotlin的cursor?.use { it.moveToFirst() }
            if (cursor != null && cursor.moveToFirst()) {
                int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                String path = cursor.getString(dataColumn).toLowerCase();

                // 核心判断逻辑完全不变：路径包含screenshot关键词
                if (path.contains("screenshot")) {
                    Uri contentUri = Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            String.valueOf(cursor.getLong(idColumn))
                    );
                    onScreenshotDetected.onScreenshotDetected(contentUri);
                }
            }
        } finally {
            // 等效于Kotlin的use函数：确保Cursor被正确关闭，避免内存泄漏
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
    }
}
