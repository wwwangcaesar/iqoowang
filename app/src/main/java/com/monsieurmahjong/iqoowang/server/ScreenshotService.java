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
import com.monsieurmahjong.iqoowang.utils.OcrUtils;

import java.io.OutputStream;
import java.util.Locale;

public class ScreenshotService extends AccessibilityService {

    private static final String TAG = "NFC_Screenshot_Service";

    /** NFC路径用：QuickLogActivity 这时候已经在最前面（哪怕是透明的），截图完只需要广播回去，
     * 它自己 registerReceiver 在监听 ACTION_SCREENSHOT_DONE。 */
    public static final String ACTION_REQUEST_SCREENSHOT = "com.iqoowang.REQUEST_SCREENSHOT";
    public static final String ACTION_SCREENSHOT_DONE = "com.iqoowang.SCREENSHOT_DONE";
    public static final String EXTRA_IMAGE_URI = "image_uri";

    /**
     * 摇一摇专用：这时候 QuickLogActivity 根本还没打开，银行App/支付软件还在最前面——这正是
     * 这个action存在的意义，必须先在"我们自己的界面还没出现"这个时间点截图，截到的才是用户
     * 真正想记的那张支付凭证，而不是自家App任务栈里躺着的 MainActivity。
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
     * @param launchAfterward true=摇一摇路径，截图后直接在内存位图上跑OCR、拉起记账页；
     *                        false=NFC路径，截图存好后广播，由已经打开的QuickLogActivity接住
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void performGlobalScreenshot(boolean launchAfterward) {
        long tRequest = System.currentTimeMillis();
        Log.d(TAG, "📸 开始调用系统 takeScreenshot API..."
                + (launchAfterward ? "（摇一摇：截图后直接在内存位图上跑OCR再拉起记账页）" : "（NFC：截图后广播给已打开的记账页）"));

        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(@NonNull ScreenshotResult screenshotResult) {
                Log.d(TAG, "✅ 系统原生截图成功 [耗时统计 t_capture=" + (System.currentTimeMillis() - tRequest)
                        + "ms]，开始解析 HardwareBuffer...");
                try {
                    // 1. 获取硬件位图
                    Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            screenshotResult.getHardwareBuffer(),
                            screenshotResult.getColorSpace()
                    );

                    if (hardwareBitmap != null) {
                        Bitmap softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
                        hardwareBitmap.recycle(); // 及时释放硬件显存
                        Log.d(TAG, "💾 软件位图拷贝完成 [耗时统计 t_bitmap_ready="
                                + (System.currentTimeMillis() - tRequest) + "ms]");

                        if (launchAfterward) {
                            handleShakePathFast(softwareBitmap, tRequest);
                        } else {
                            saveToGalleryAndNotify(softwareBitmap, false);
                        }
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

    /**
     * 【202609 速度修复】摇一摇路径专用：OCR直接跑在这张刚到手的内存位图上，不等、也不需要
     * 先把图存到磁盘再从Uri重新解码读一遍——那一来一回的PNG编码+解码，加上重新跑一次OCR，
     * 是"弹窗要等5秒多"的主要来源。识别完不管成不成功，立刻带着结果拉起记账页；存相册这件事
     * 挪到识别完成之后再做、且不再回调/广播任何东西——用户什么时候能看到记账页，只取决于
     * OCR多快，不再取决于磁盘I/O。两步严格按顺序（先OCR、OCR回调里才碰存图这一步），bitmap
     * 不会被两边同时读写，不需要额外加锁或等待。
     */
    private void handleShakePathFast(Bitmap bitmap, long tRequest) {
        OcrUtils.parsePaymentScreenshotAsync(bitmap, new OcrUtils.OcrCallback() {
            @Override
            public void onSuccess(OcrUtils.ExpenseData data) {
                String amountYuan = String.format(Locale.CHINA, "%.2f", data.getAmount() / 100.0);
                Log.d(TAG, "🎯 [耗时统计 t_ocr_done=" + (System.currentTimeMillis() - tRequest)
                        + "ms] OCR成功，识别金额=" + amountYuan);
                launchQuickLogViaTrampoline(amountYuan, tRequest);
                saveToGalleryAndNotify(bitmap, true);
            }

            @Override
            public void onFailure(String errorMessage) {
                Log.w(TAG, "⚠️ [耗时统计 t_ocr_done=" + (System.currentTimeMillis() - tRequest)
                        + "ms] OCR失败(" + errorMessage + ")，不带金额拉起记账页，用户手动填");
                launchQuickLogViaTrampoline(null, tRequest);
                saveToGalleryAndNotify(bitmap, true);
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

                    if (!launchAfterward) {
                        Log.d(TAG, "📢 NFC路径：图已存好，广播给已经打开的记账页...");
                        Intent intent = new Intent(ACTION_SCREENSHOT_DONE);
                        intent.putExtra(EXTRA_IMAGE_URI, uri.toString());
                        sendBroadcast(intent);
                    } else {
                        // 摇一摇路径：记账页早在OCR完成那一刻就已经弹出了（见 handleShakePathFast），
                        // 这里纯粹是"顺手存一份到相册留档"，不能广播 ACTION_SCREENSHOT_DONE——
                        // QuickLogActivity 还在监听这个广播，收到会被误当成新一次截图完成事件，
                        // 把已经显示好的UI重新触发一遍
                        Log.d(TAG, "📢 摇一摇路径：图已存好相册留档（不广播/不拉起，记账页已经在前面弹出了）");
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
     * Android 10+ 后台启动限制约束，不能因为"我是从截图这个流程过来的"就绕开。复用
     * ShakeTrampolineReceiver 作为落地目标，把OCR识别到的金额通过 Intent extra 带过去。
     *
     * 【202609 崩溃修复】setAlarmClock() 本身也受 Android 12+ 精确闹钟权限约束（早前误以为
     * 它能豁免，线上因此报过一次 SecurityException 崩溃）。Manifest 里现在同时声明了
     * USE_EXACT_ALARM（个人签名安装不受Play Store的"必须是闹钟类App"限制，正常情况下装上
     * 就自动授予）和 SCHEDULE_EXACT_ALARM 作双重保险，理论上这里不会再命中未授权的情况——
     * 但仍然补上这层防御性检查，真遇到极端情况（比如用户手动去设置里把权限关了）也只是
     * 放弃这次触发、留日志，不会重演那次崩溃。
     *
     * @param recognizedAmount OCR识别到的金额（元，字符串形式，如"88.00"），识别失败传null，
     *                          QuickLogActivity 拿到非null值就直接回填金额框，不用自己再跑一次OCR
     */
    private void launchQuickLogViaTrampoline(String recognizedAmount, long tRequest) {
        AlarmManager alarmManager = getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            Log.e(TAG, "❌ 拿不到 AlarmManager，摇一摇这次触发放弃");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.e(TAG, "❌ 没有精确闹钟权限（理论上 USE_EXACT_ALARM 应该已经自动授予了，"
                    + "真出现这行日志说明这台设备上的授权状态跟预期不一样），摇一摇这次触发放弃");
            return;
        }

        Intent trampolineIntent = new Intent(this, ShakeTrampolineReceiver.class);
        if (recognizedAmount != null) {
            trampolineIntent.putExtra(QuickLogActivity.EXTRA_RECOGNIZED_AMOUNT, recognizedAmount);
        }
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
        Log.d(TAG, "⏰ [耗时统计 t_alarm_set=" + (System.currentTimeMillis() - tRequest) + "ms] 已排入AlarmManager豁免窗口");
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
