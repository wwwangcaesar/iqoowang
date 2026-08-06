package com.monsieurmahjong.iqoowang.streak;

import android.content.Context;
import android.webkit.JavascriptInterface;

import com.monsieurmahjong.iqoowang.utils.StreakManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class StreakBridge {

    private final Context context;
    private final StreakActivity activity;

    public StreakBridge(Context context, StreakActivity activity) {
        this.context = context;
        this.activity = activity;
    }

    @JavascriptInterface
    public String getStreakData() {
        StreakManager manager = StreakManager.getInstance();
        try {
            StreakManager.StreakState state = manager.getState(context);
            JSONObject json = new JSONObject();
            json.put("currentStreak", state.currentStreak);
            json.put("checkedToday", state.checkedToday);
            JSONArray arr = new JSONArray();
            for (boolean b : state.last7Days) arr.put(b);
            json.put("last7Days", arr);
            // 断签补签相关状态：pendingRestore>0 时表示有一次断签正在等待补签
            json.put("pendingRestore", state.pendingRestore);
            json.put("recoveryTarget", state.recoveryTarget);
            json.put("recoveryProgress", state.recoveryProgress);
            json.put("justRestored", state.justRestored);
            json.put("justRestoredTotal", state.justRestoredTotal);
            return json.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void checkIn() {
        StreakManager.getInstance().checkIn(context);
    }

    @JavascriptInterface
    public void close() {
        if (activity != null) {
            activity.finish();
        }
    }
}
