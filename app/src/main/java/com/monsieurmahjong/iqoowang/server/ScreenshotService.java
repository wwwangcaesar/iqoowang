package com.monsieurmahjong.iqoowang.server;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
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

import com.monsieurmahjong.iqoowang.QuickLogActivity;
import com.monsieurmahjong.iqoowang.service.ShakeTrampolineReceiver;

import java.io.OutputStream;

public class ScreenshotService extends AccessibilityService {

    private static final String TAG = "NFC_Screenshot_Service";

    /** NFC路径用：QuickLogActivity 这时候已经在最前面（哪怕是透明的），截图完只需要广播回去，
     * 它自己 registerReceiver 在监听 ACTION_SCREENSHOT_DONE。 */
    public static final String ACTION_REQUEST_SCREENSHOT = "com.iqoowang.REQUEST_SCREENSHOT";
    public static final String ACTION_SCREENSHOT_DONE = "com.iqoowang.SCREENSHOT_DONE";
    public static final String EXTRA_IMAGE_URI = "image_uri";

    /**
     * 【202609 截图时序修复】摇一摇专用：这时候 QuickLogActivity 根本还没打开，银行App/支付软件
     * 还在最前面——这正是这个action存在的意义，必须先在"我们自己的界面还没出现"这个时间点
     * 截图，截到的才是用户真正想记的那张支付凭证，而不是自家App任务栈里躺着的 MainActivity。
     * 截图存好之后，由这里（而不是ShakeDetectService）通过 AlarmManager 豁免窗口把
     * QuickLogActivity 连同图片URI一起拉起来，QuickLogActivity 不用再自己截一次图。
     */
    public static final String ACTION_CAPTURE_FOR_TRAMPOLINE = "com.iqoowang.CAPTURE_FOR_TRAMPOLINE";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @RequiresApi(api = Build.VERSION_CODES.R)
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "🟢 收到截屏请求广播，action=" + action);
            if (ACTION_REQUEST_SCREENSHOT.equals(action)) {
                performGlobalScreenshot(false);
            } else if (ACTION_CAPTURE_FOR_TRAMPOLINE.equals(action)) {
                performGlobalScreenshot(true);
            }
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "🚀 无障碍服务已成功连接！");
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_REQUEST_SCREENSHOT);
        filter.addAction(ACTION_CAPTURE_FOR_TRAMPOLINE);
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    /**
     * @param launchAfterward true=摇一摇路径，截图存好后自己负责拉起QuickLogActivity；
     *                        false=NFC路径，截图存好后只广播，由已经打开的QuickLogActivity接住
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void performGlobalScreenshot(boolean launchAfterward) {
        Log.d(TAG, "📸 开始调用系统 takeScreenshot API..."
                + (launchAfterward ? "（摇一摇：截图后自己拉起记账页）" : "（NFC：截图后广播给已打开的记账页）"));

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
                        saveToGalleryAndNotify(softwareBitmap, launchAfterward);
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

    private void saveToGalleryAndNotify(Bitmap bitmap, boolean launchAfterward) {
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

                    if (launchAfterward) {
                        Log.d(TAG, "📢 摇一摇路径：图已存好，通过豁免窗口拉起记账页并带上URI");
                        launchQuickLogViaTrampoline(uri);
                    } else {
                        Log.d(TAG, "📢 NFC路径：图已存好，广播给已经打开的记账页...");
                        Intent intent = new Intent(ACTION_SCREENSHOT_DONE);
                        intent.putExtra(EXTRA_IMAGE_URI, uri.toString());
                        sendBroadcast(intent);
                    }
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

    /**
     * 复用跟摇一摇同一套 AlarmManager.setAlarmClock() 豁免窗口机制来 startActivity()——
     * 这里跟 ShakeDetectService 处于同一个"后台Service想拉Activity"的处境，一样受
     * Android 10+ 后台启动限制约束（前台Service/无障碍服务都不天然豁免这条限制），不能因为
     * "我是从截图这个流程过来的"就绕开。复用 ShakeTrampolineReceiver 作为落地目标，把图片
     * URI 通过 Intent extra 带过去，这样 QuickLogActivity 拿到的是刚截的这一张，不用再
     * 重新查一次相册最新图。
     */
    private void launchQuickLogViaTrampoline(Uri imageUri) {
        AlarmManager alarmManager = getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            Log.e(TAG, "❌ 拿不到 AlarmManager，摇一摇这次触发放弃");
            return;
        }

        Intent trampolineIntent = new Intent(this, ShakeTrampolineReceiver.class);
        trampolineIntent.putExtra(QuickLogActivity.EXTRA_CAPTURED_IMAGE_URI, imageUri.toString());
        PendingIntent operationPendingIntent = PendingIntent.getBroadcast(this, 0, trampolineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // AlarmClockInfo 的第二个参数(showIntent)只在用户点状态栏小闹钟图标时才用得到，
        // 跟触发记账页本身无关，给个能安全跳回App的Intent即可，拿不到就传null（合法）
        Intent showIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent showPendingIntent = showIntent != null
                ? PendingIntent.getActivity(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE)
                : null;

        AlarmManager.AlarmClockInfo alarmClockInfo =
                new AlarmManager.AlarmClockInfo(System.currentTimeMillis(), showPendingIntent);
        alarmManager.setAlarmClock(alarmClockInfo, operationPendingIntent);
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
