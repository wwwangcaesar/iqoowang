package com.monsieurmahjong.iqoowang.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.monsieurmahjong.iqoowang.QuickLogActivity;
import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.utils.AccessibilityStatusUtils;

import java.util.ArrayDeque;

/**
 * 摇一摇记账的后台监听：持续监听加速度传感器，检测到"用力摇晃"这个动作特有的
 * 高频往复模式后，唤起 QuickLogActivity 走截图记账流程（等价于又一种 NFC 触发方式，
 * 详见 QuickLogActivity.handleNfcIntent() 对 ACTION_SHAKE_LOG 的处理）。
 *
 * 【为什么是前台Service】普通后台 Service 在新版 Android 上活不了多久就被系统弄死，
 * 摇一摇要做到"随时随地"就必须是前台 Service，代价是会有一条常驻通知——
 * 这是跟用户明确确认过的取舍（前台Service+常驻通知 vs 只在App开着时监听，选了前者）。
 *
 * 【防误触】不靠系统状态位收窄监听窗口（NFC开关和这个功能已经确认无关），
 * 完全靠算法本身：短时间窗口内要求足够多次高幅度往复摆动，单次磕碰/掏手机
 * 这类平滑的单方向加速度不会达到这个次数，减少误触发。具体阈值大概率需要
 * 上机实测微调，见 SHAKE_THRESHOLD / SHAKE_COUNT_REQUIRED 这两个参数。
 *
 * 【服务是否该跑】由 SettingsFragment 里的开关控制启停，且开关本身受
 * ScreenshotService（无障碍服务）是否已启用这个前提gate——没开无障碍，
 * 摇晃了也截不了图，那样"摇了没反应"体验更差，所以干脆不让开关打开。
 */
public class ShakeDetectService extends Service implements SensorEventListener {

    private static final String TAG = "ShakeDetectService";

    public static final String PREFS = "shake_log_prefs";
    public static final String KEY_ENABLED = "shake_log_enabled";

    private static final String CHANNEL_STATUS = "shake_status_channel";
    private static final String CHANNEL_TRIGGER = "shake_trigger_channel";
    private static final int NOTIF_ID_STATUS = 3001;
    private static final int NOTIF_ID_TRIGGER = 3002;

    // ── 摇动判定参数（经验初始值，需要在实际设备上实测微调）──
    /** 有效摆动的加速度幅值阈值（m/s²）；线性加速度已经去掉重力，静止/走路时的读数远低于这个 */
    private static final float SHAKE_THRESHOLD = 18f;
    /** 判定为"有效摇晃"所需的往复次数 */
    private static final int SHAKE_COUNT_REQUIRED = 4;
    /** 往复次数必须落在这个时间窗口内，窗口外的旧记录会被滚动清掉 */
    private static final long SHAKE_WINDOW_MS = 1200;
    /** 触发一次之后的冷却时间，避免一次摇晃动作被重复算成多次触发 */
    private static final long TRIGGER_COOLDOWN_MS = 3000;

    private SensorManager sensorManager;
    private Sensor accelSensor;
    private int sensorType;

    private final ArrayDeque<Long> recentShakeTimestamps = new ArrayDeque<>();
    private long lastTriggerTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        startForeground(NOTIF_ID_STATUS, buildStatusNotification());
        registerSensor();
        Log.i(TAG, "摇一摇监听已启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 服务被系统杀掉后按需重建（不重新传入原来的 Intent），配合前台Service的存活优先级，
        // 尽量做到"设置里开着就应该一直在运行"这个用户预期
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        Log.i(TAG, "摇一摇监听已停止");
    }

    private void registerSensor() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) return;

        // 优先用线性加速度（已经去掉重力分量，更适合做摇动判定）；
        // 部分老设备/机型没有这个虚拟传感器时，退化用原始加速度计（在onSensorChanged里做近似去重力处理）
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        sensorType = Sensor.TYPE_LINEAR_ACCELERATION;
        if (accelSensor == null) {
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            sensorType = Sensor.TYPE_ACCELEROMETER;
        }

        if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
        } else {
            Log.w(TAG, "设备没有可用的加速度类传感器，摇一摇功能无法工作");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0], y = event.values[1], z = event.values[2];
        float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
        if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            // 原始加速度计静止时本身就有约9.8的读数（重力），线性加速度传感器没有这个问题，
            // 这里只是近似减掉，不是严格的重力分量剔除，作为没有线性加速度传感器时的退化方案
            magnitude -= SensorManager.GRAVITY_EARTH;
        }
        if (Math.abs(magnitude) < SHAKE_THRESHOLD) return;

        long now = System.currentTimeMillis();
        recentShakeTimestamps.addLast(now);
        while (!recentShakeTimestamps.isEmpty() && now - recentShakeTimestamps.peekFirst() > SHAKE_WINDOW_MS) {
            recentShakeTimestamps.pollFirst();
        }

        if (recentShakeTimestamps.size() >= SHAKE_COUNT_REQUIRED && now - lastTriggerTime > TRIGGER_COOLDOWN_MS) {
            lastTriggerTime = now;
            recentShakeTimestamps.clear();
            onValidShakeDetected();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不需要处理
    }

    private void onValidShakeDetected() {
        Log.i(TAG, "检测到有效摇晃，触发记账页");

        Intent target = new Intent(this, QuickLogActivity.class);
        target.setAction(QuickLogActivity.ACTION_SHAKE_LOG);
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, target,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 双保险：全屏通知是Android官方认可的、能在锁屏/后台场景下可靠拉起Activity的机制
        // （前提是有 USE_FULL_SCREEN_INTENT 权限），直接 startActivity() 作为额外尝试——
        // 前台Service本身也有一定机会成功拉起，不同机型表现不一样，两个一起做成功率更高，
        // 就算两个都生效，用户最多看到已经在前台的记账页，不会有明显的重复打扰。
        showTriggerNotification(pendingIntent);
        try {
            startActivity(target);
        } catch (Exception e) {
            Log.w(TAG, "直接startActivity失败，依赖全屏通知兜底", e);
        }
    }

    private void showTriggerNotification(PendingIntent pendingIntent) {
        boolean canUseFullScreenIntent = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            canUseFullScreenIntent = nm != null && nm.canUseFullScreenIntent();
            if (!canUseFullScreenIntent) {
                Log.w(TAG, "没有全屏通知权限，摇一摇触发时只能弹普通通知，不会自动点亮/跳转页面，"
                        + "可以去系统设置搜'特殊应用权限-完全屏幕意图通知'给这个App开一下");
            }
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_TRIGGER)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("摇一摇记账")
                .setContentText("检测到摇晃，正在打开记账页")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        if (canUseFullScreenIntent) {
            builder.setFullScreenIntent(pendingIntent, true);
        }

        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID_TRIGGER, builder.build());
        } catch (SecurityException e) {
            // 没有 POST_NOTIFICATIONS 权限：通知发不出来，但 startActivity() 那次尝试仍然可能成功
            Log.w(TAG, "没有通知权限，摇一摇触发的通知无法显示", e);
        }
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        // 常驻状态通知：低优先级、无声音，只是告诉用户"现在摇一摇是生效的"
        NotificationChannel statusChannel = new NotificationChannel(
                CHANNEL_STATUS, "摇一摇记账状态", NotificationManager.IMPORTANCE_LOW);
        statusChannel.setDescription("摇一摇记账功能开启期间的常驻状态提示");
        statusChannel.setShowBadge(false);
        nm.createNotificationChannel(statusChannel);

        // 触发通知：高优先级，摇晃触发那一刻才会用到，需要能弹全屏意图
        NotificationChannel triggerChannel = new NotificationChannel(
                CHANNEL_TRIGGER, "摇一摇触发提醒", NotificationManager.IMPORTANCE_HIGH);
        triggerChannel.setDescription("检测到摇晃动作时的提醒，用于唤起记账页面");
        nm.createNotificationChannel(triggerChannel);
    }

    private Notification buildStatusNotification() {
        Intent settingsIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = settingsIntent != null
                ? PendingIntent.getActivity(this, 0, settingsIntent, PendingIntent.FLAG_IMMUTABLE)
                : null;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("摇一摇记账已就绪")
                .setContentText("用力摇晃手机可快速打开记账页")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);
        if (contentIntent != null) builder.setContentIntent(contentIntent);
        return builder.build();
    }

    /** 供 SettingsFragment 判断当前是否具备开启条件（无障碍服务是否已启用） */
    public static boolean canEnable(android.content.Context context) {
        return AccessibilityStatusUtils.isScreenshotServiceEnabled(context);
    }
}
