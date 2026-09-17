package com.monsieurmahjong.iqoowang.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.server.ScreenshotService;
import com.monsieurmahjong.iqoowang.utils.AccessibilityStatusUtils;

import java.util.ArrayDeque;

/**
 * 摇一摇记账的后台监听：持续监听加速度传感器，检测到"用力摇晃"这个动作特有的
 * 高频往复模式后，请求 ScreenshotService 立刻截图并接管后续的拉起流程
 * （见 ScreenshotService.ACTION_CAPTURE_FOR_TRAMPOLINE）。
 *
 * 【202609 截图时序修复】这个类本身不再直接拉起 QuickLogActivity，也不再弹任何通知——
 * 早期版本是"检测到摇晃 → 直接拉起QuickLogActivity → 它自己再截图"，问题是第二步一旦
 * 把自家App的界面顶到最前面（QuickLogActivity 是 singleTask，跟 MainActivity 共享默认
 * taskAffinity，只要MainActivity已经有后台任务存在——几乎总是这样——系统会把那个任务连
 * 同MainActivity一起带回前台，QuickLogActivity只是透明地盖在它上面)，第三步截到的就是
 * MainActivity，不是用户真正想记的银行卡/支付软件那一屏。现在改成"先让ScreenshotService
 * 趁用户当前App还在最前面的这一刻截图 → 图存好之后由它自己通过AlarmManager豁免窗口把
 * QuickLogActivity连同图片URI一起拉起来"，QuickLogActivity从此不需要再自己截一次图。
 *
 * 【为什么是前台Service】普通后台 Service 在新版 Android 上活不了多久就被系统弄死，
 * 摇一摇要做到"随时随地"就必须是前台 Service，代价是会有一条常驻通知——
 * 这是跟用户明确确认过的取舍（前台Service+常驻通知 vs 只在App开着时监听，选了前者）。
 * 这条常驻状态通知和"摇晃那一刻要不要弹提示"是两回事：本类现在完全不弹后者，
 * 摇晃触发走 AlarmManager 豁免窗口，全程无通知。
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
    private static final int NOTIF_ID_STATUS = 3001;

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
        createNotificationChannel();
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

    /**
     * 只广播一个"请求截图"意图，不在这里做任何拉起Activity的事——这一刻用户当前App
     * （银行卡/支付软件）还在最前面，正是应该截图的时机；等 ScreenshotService 截完图、
     * 存好之后，由它自己负责拉起 QuickLogActivity（见该类的 ACTION_CAPTURE_FOR_TRAMPOLINE
     * 处理逻辑），不能反过来先拉起我们自己的界面再截图。
     */
    private void onValidShakeDetected() {
        Log.i(TAG, "检测到有效摇晃，请求无障碍服务截图当前屏幕");
        sendBroadcast(new Intent(ScreenshotService.ACTION_CAPTURE_FOR_TRAMPOLINE));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        // 常驻状态通知：低优先级、无声音，只是告诉用户"现在摇一摇是生效的"，这是前台Service
        // 按Android要求必须有的通知，跟"摇晃那一刻要不要弹提示"无关——后者现在完全不弹了
        NotificationChannel statusChannel = new NotificationChannel(
                CHANNEL_STATUS, "摇一摇记账状态", NotificationManager.IMPORTANCE_LOW);
        statusChannel.setDescription("摇一摇记账功能开启期间的常驻状态提示");
        statusChannel.setShowBadge(false);
        nm.createNotificationChannel(statusChannel);
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

    /**
     * 供 MyApplication 冷启动 / 开机自启广播复用：只要开关没被用户手动关掉（未写过 KEY_ENABLED 时
     * 默认视为开启）且无障碍服务已经授权，就拉起前台监听，不需要用户每次都手动进设置页点一下。
     * 两个条件任一不满足都直接跳过，不会产生一个「开着但没用」的前台Service。
     */
    public static void startIfEnabled(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_ENABLED, true);
        boolean accessibilityOn = AccessibilityStatusUtils.isScreenshotServiceEnabled(appContext);
        if (enabled && accessibilityOn) {
            ContextCompat.startForegroundService(appContext, new Intent(appContext, ShakeDetectService.class));
        }
    }
}
