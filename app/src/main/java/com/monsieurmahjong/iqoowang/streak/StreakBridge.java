package com.monsieurmahjong.iqoowang.streak;

import android.app.Activity;
import android.content.Context;
import android.webkit.JavascriptInterface;

import com.monsieurmahjong.iqoowang.utils.StreakManager;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JavaScript ↔ Android 数据桥接层（streak-flame.html 专用）
 * streak-flame.html 中通过 window.StreakBridge.xxx() 调用此类的方法。
 */
public class StreakBridge {

    private final Context ctx;
    private final StreakManager manager = StreakManager.getInstance();

    public StreakBridge(Context ctx) {
        // 注意：这里故意保留 Activity Context（而非 applicationContext），
        // 因为 close() 需要对它 finish()；WebView 销毁时 bridge 也会被回收，不会长期持有导致泄漏。
        this.ctx = ctx;
    }

    /**
     * 返回当前连续打卡状态：
     * { currentStreak, checkedToday, last7Days:[bool*7] }
     */
    @JavascriptInterface
    public String getStreakData() {
        try {
            JSONObject json = new JSONObject();
            json.put("currentStreak", manager.getCurrentStreak(ctx));
            json.put("checkedToday", manager.isCheckedToday(ctx));

            boolean[] last7 = manager.getLast7Days(ctx);
            JSONArray arr = new JSONArray();
            for (boolean b : last7) arr.put(b);
            json.put("last7Days", arr);

            return json.toString();
        } catch (Exception e) {
            return "{\"currentStreak\":0,\"checkedToday\":false,\"last7Days\":[false,false,false,false,false,false,false]}";
        }
    }

    /** 执行一次打卡（幂等，同一天重复调用不会重复计数） */
    @JavascriptInterface
    public void checkIn() {
        manager.checkIn(ctx);
    }

    /** 关闭页面（页面内的返回按钮调用） */
    @JavascriptInterface
    public void close() {
        if (ctx instanceof Activity) {
            ((Activity) ctx).runOnUiThread(((Activity) ctx)::finish);
        }
    }
}
