package com.monsieurmahjong.iqoowang.server;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.io.OutputStream;

public class ScreenshotService extends AccessibilityService {

    private static final String TAG = "NFC_Screenshot_Service";

    public static final String ACTION_REQUEST_SCREENSHOT = "com.iqoowang.REQUEST_SCREENSHOT";
    public static final String ACTION_SCREENSHOT_DONE = "com.iqoowang.SCREENSHOT_DONE";
    public static final String EXTRA_IMAGE_URI = "image_uri";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @RequiresApi(api = Build.VERSION_CODES.R)
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "🟢 收到来自 Activity 的截屏请求广播");
            if (ACTION_REQUEST_SCREENSHOT.equals(intent.getAction())) {
                performGlobalScreenshot();
            }
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "🚀 无障碍服务已成功连接！");
        ContextCompat.registerReceiver(this, receiver,
                new IntentFilter(ACTION_REQUEST_SCREENSHOT), ContextCompat.RECEIVER_EXPORTED);
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private void performGlobalScreenshot() {
        Log.d(TAG, "📸 开始调用系统 takeScreenshot API...");

        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(@NonNull ScreenshotResult screenshotResult) {
                Log.d(TAG, "✅ 系统原生截图成功，开始解析 HardwareBuffer...");
                try {
                    // 1. 获取硬件位图
                    Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            screenshotResult.getHardwareBuffer(),
                            screenshotResult.getColorSpace()
                    );

                    if (hardwareBitmap != null) {
                        Log.d(TAG, "🔄 硬件位图获取成功，正在拷贝为软件位图 (ARGB_8888)...");
                        Bitmap softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
                        hardwareBitmap.recycle(); // 及时释放硬件显存

                        Log.d(TAG, "💾 软件位图拷贝完成，开始准备存入相册...");
                        saveToGalleryAndNotify(softwareBitmap);
                    } else {
                        Log.e(TAG, "❌ 错误: wrapHardwareBuffer 返回了 null 对象的位图！");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "❌ 崩溃: 解析截图数据时发生异常!", e);
                }
            }

            @Override
            public void onFailure(int errorCode) {
                // 常见的错误码：1 (无权限/未开启服务), 2 (系统忙/间隔太短), 3 (当前页面禁止截图如银行App)
                Log.e(TAG, "❌ 系统原生截图失败！无障碍错误码 (ErrorCode): " + errorCode);
            }
        });
    }

    private void saveToGalleryAndNotify(Bitmap bitmap) {
        new Thread(() -> {
            try {
                Log.d(TAG, "🧵 异步线程启动：正在配置 MediaStore 参数...");
                ContentValues values = new ContentValues();
                String fileName = "Receipt_" + System.currentTimeMillis() + ".png";
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SereneLedger");

                Log.d(TAG, "📝 正在向系统 MediaStore 插入图片记录...");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

                if (uri != null) {
                    Log.d(TAG, "Generated MediaStore URI: " + uri.toString() + "，开始写入流...");
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        boolean success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                        Log.d(TAG, "🗜️ 图片压缩写入结果: " + (success ? "成功" : "失败"));
                    }

                    Log.d(TAG, "📢 核心点：截图流程全链路闭环！正在发送 SCREENSHOT_DONE 广播...");
                    Intent intent = new Intent(ACTION_SCREENSHOT_DONE);
                    intent.putExtra(EXTRA_IMAGE_URI, uri.toString());
                    sendBroadcast(intent);
                } else {
                    Log.e(TAG, "❌ 错误: MediaStore 插入返回的 Uri 为 null，可能没有公共存储写入权限！");
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ 崩溃: 在异步线程保存图片或发广播时发生异常!", e);
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
        }).start();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.w(TAG, "🛑 无障碍服务被销毁");
        try {
            unregisterReceiver(receiver);
        } catch (Exception e) {}
    }
}
