package com.monsieurmahjong.iqoowang.service;

import android.app.AlarmManager;
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
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

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
        fireTrampolineLaunch();
    }

    /**
     * 【202609修复】上一版这里直接调用 setAlarmClock()，线上崩了：
     * java.lang.SecurityException: Caller ... needs to hold android.permission.SCHEDULE_EXACT_ALARM
     * or android.permission.USE_EXACT_ALARM to set exact alarms.
     * 之前注释里写"setAlarmClock()在Android 12+上豁免SCHEDULE_EXACT_ALARM权限检查"是错的——
     * 查了官方文档（Schedule exact alarms are denied by default），setExact() /
     * setExactAndAllowWhileIdle() / setAlarmClock() 三个API都受这个权限约束，没有例外。
     * 现在按权限是否已授予分两条路：授予了走AlarmManager豁免窗口（无感、不弹通知）；
     * 没授予就退化回弹通知兜底，保证摇一摇"不会没反应"，只是体验退回到需要点一下。
     * 引导用户去系统设置页授予这个权限的入口在 SettingsFragment 打开开关那一刻
     * （requestExactAlarmPermissionIfNeeded()），正常使用下很快就会转向前一条路径。
     */
    private void fireTrampolineLaunch() {
        AlarmManager alarmManager = getSystemService(AlarmManager.class);
        boolean canUseExactAlarm = alarmManager != null
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms());

        if (canUseExactAlarm) {
            fireViaAlarmClock(alarmManager);
        } else {
            Log.w(TAG, "没有精确闹钟权限，退化为通知兜底方案");
            fireViaNotificationFallback();
        }
    }

    /**
     * 用 AlarmManager.setAlarmClock() 触发一个"立刻到点"的系统闹钟，闹钟到点后系统投递
     * PendingIntent 给 ShakeTrampolineReceiver 的这次广播处理过程，天然落在 Android 官方
     * 认可的后台拉起Activity豁免窗口内——跟真正的闹钟App在锁屏时能直接弹出响铃界面是
     * 同一套机制，不受"前台Service在拉Activity这件事上仍算后台"这条限制约束，也不需要
     * 用户当前屏幕状态配合、不需要点任何通知。
     */
    private void fireViaAlarmClock(AlarmManager alarmManager) {
        Intent trampolineIntent = new Intent(this, ShakeTrampolineReceiver.class);
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

    /**
     * 精确闹钟权限还没到手时的兜底：退回到弹通知、用户点一下才进去，至少保证"摇了有反应"，
     * 不做全屏意图那么复杂（那需要额外的 USE_FULL_SCREEN_INTENT 权限），这条路径本身
     * 只是过渡状态，没必要做到跟主路径一样"无感"。
     */
    private void fireViaNotificationFallback() {
        Intent target = new Intent(this, QuickLogActivity.class);
        target.setAction(QuickLogActivity.ACTION_SHAKE_LOG);
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, target,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_TRIGGER)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("摇一摇记账")
                .setContentText("检测到摇晃，点击打开记账页")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(contentIntent);

        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID_TRIGGER, builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "没有通知权限，这次摇晃触发没有更多兜底了", e);
        }
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        // 常驻状态通知：低优先级、无声音，只是告诉用户"现在摇一摇是生效的"
        // （原来这里还有一条"触发通知"channel，摇晃那一刻用来弹全屏意图/普通通知；
        // 现在改回带权限判断：有精确闹钟权限就不弹，没权限才走下面这条 CHANNEL_TRIGGER 兜底）
        NotificationChannel statusChannel = new NotificationChannel(
                CHANNEL_STATUS, "摇一摇记账状态", NotificationManager.IMPORTANCE_LOW);
        statusChannel.setDescription("摇一摇记账功能开启期间的常驻状态提示");
        statusChannel.setShowBadge(false);
        nm.createNotificationChannel(statusChannel);

        // 触发通知：仅在精确闹钟权限还没被授予时的兜底路径会用到（见 fireViaNotificationFallback），
        // 正常情况下（已授权）摇一摇走 AlarmManager 直接拉起，不会弹这条
        NotificationChannel triggerChannel = new NotificationChannel(
                CHANNEL_TRIGGER, "摇一摇触发提醒（备用）", NotificationManager.IMPORTANCE_HIGH);
        triggerChannel.setDescription("精确闹钟权限未授予时的备用提醒，用于唤起记账页面");
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
