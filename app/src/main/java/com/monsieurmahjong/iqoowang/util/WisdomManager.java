package com.monsieurmahjong.iqoowang.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * WisdomManager — 话术知识库
 *
 * 【重要说明，必须诚实】这里不是真正的模型权重级"学习"（本地4B模型不可能实时
 * 做微调，太重也没必要）。实际机制是：把你教的话术原文持久化存起来，之后每次
 * 分析/对话涉及交易判断时，都会把这些话术重新塞进 Prompt 喂给模型——本质是
 * "知识库注入"，模拟"记住"的效果。诚实地说，这跟人类"学习"不是一回事，但效果
 * 上能做到"你教过的东西，之后分析时AI都会参考"。
 */
public class WisdomManager {

    private static final String TAG = "WisdomManager";
    private static final String DB_NAME = "wisdom.db";
    private static final int DB_VERSION = 1;

    /** 注入prompt时最多带几条最近的话术，避免话术越攒越多把prompt拖得很长、拖慢推理 */
    private static final int MAX_INJECT_ENTRIES = 15;
    /** 注入prompt的字符预算上限，双重保险 */
    private static final int MAX_INJECT_CHARS = 1200;

    private static WisdomManager sInstance;
    private final SQLiteDatabase mDb;
    private final SimpleDateFormat mDateFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    public static void init(Context context) {
        if (sInstance == null) {
            synchronized (WisdomManager.class) {
                if (sInstance == null) sInstance = new WisdomManager(context.getApplicationContext());
            }
        }
    }

    public static WisdomManager get() {
        if (sInstance == null) throw new IllegalStateException("call init() first");
        return sInstance;
    }

    private WisdomManager(Context context) {
        mDb = new DbHelper(context).getWritableDatabase();
        Log.i(TAG, "WisdomManager initialized");
    }

    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context ctx) { super(ctx, DB_NAME, null, DB_VERSION); }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS wisdom_entries (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "raw_text TEXT," +      // 用户教的原始话术
                    "summary TEXT," +        // AI/规则生成的简明摘要（用于进化记录展示）
                    "added_at INTEGER)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int o, int n) {
            db.execSQL("DROP TABLE IF EXISTS wisdom_entries");
            onCreate(db);
        }
    }

    public long addEntry(String rawText, String summary) {
        ContentValues cv = new ContentValues();
        cv.put("raw_text", rawText);
        cv.put("summary", summary);
        cv.put("added_at", System.currentTimeMillis());
        long id = mDb.insert("wisdom_entries", null, cv);
        Log.i(TAG, "新增话术#" + id + ": " + summary);
        return id;
    }

    public static class WisdomEntry {
        public long id;
        public String rawText, summary;
        public long addedAt;
    }

    public List<WisdomEntry> getAll() {
        List<WisdomEntry> list = new ArrayList<>();
        Cursor c = mDb.rawQuery("SELECT * FROM wisdom_entries ORDER BY added_at DESC", null);
        try {
            while (c.moveToNext()) {
                WisdomEntry e = new WisdomEntry();
                e.id = c.getLong(c.getColumnIndexOrThrow("id"));
                e.rawText = c.getString(c.getColumnIndexOrThrow("raw_text"));
                e.summary = c.getString(c.getColumnIndexOrThrow("summary"));
                e.addedAt = c.getLong(c.getColumnIndexOrThrow("added_at"));
                list.add(e);
            }
        } finally { c.close(); }
        return list;
    }

    public String getAllJson() {
        JSONArray arr = new JSONArray();
        for (WisdomEntry e : getAll()) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("summary", e.summary);
                o.put("rawText", e.rawText);
                o.put("addedAt", mDateFmt.format(new Date(e.addedAt)));
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    /**
     * 生成供 Prompt 注入的知识块——把最近学到的话术原文拼进去，让AI每次分析
     * 都能"看到"你教过的东西。按最近优先截取，控制总长度。
     */
    public String buildInjectBlock() {
        List<WisdomEntry> all = getAll(); // 已按时间倒序
        if (all.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n【你之前学过的操盘手补充话术（务必参考）】\n");
        int used = sb.length();
        int count = 0;
        for (WisdomEntry e : all) {
            if (count >= MAX_INJECT_ENTRIES) break;
            String line = "· " + e.rawText + "\n";
            if (used + line.length() > MAX_INJECT_CHARS) break;
            sb.append(line);
            used += line.length();
            count++;
        }
        sb.append("\n");
        return sb.toString();
    }

    public boolean hasAny() {
        Cursor c = mDb.rawQuery("SELECT COUNT(*) FROM wisdom_entries", null);
        try { return c.moveToFirst() && c.getInt(0) > 0; } finally { c.close(); }
    }
}
