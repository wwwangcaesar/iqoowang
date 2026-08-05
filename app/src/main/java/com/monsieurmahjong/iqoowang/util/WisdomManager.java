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
    private static final int DB_VERSION = 2;

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
        ensureDefaultWisdom();
        Log.i(TAG, "WisdomManager initialized");
    }

    /**
     * 话术库为空时预置默认操盘手心态：买入类偏审慎（右侧交易需验证），
     * 卖出/风控类偏支持规则（资金安全第一）。用户后续教的话术会追加，不会覆盖这些。
     */
    private void ensureDefaultWisdom() {
        if (hasAny()) return;
        Log.i(TAG, "话术库为空，预置默认操盘手复核话术");
        addEntry(
                "买底仓时要特别警惕：水下站上VWAP只是初步转一致，必须确认放量是真实的而不是脉冲式一下。缩量突破只能观察，不能直接当底仓信号。",
                "买底仓：放量要真实，缩量只观察", "BUY_STARTER");
        addEntry(
                "加仓50%要更谨慎：突破水线或回踩VWAP不破都需要放量确认。如果量能只是勉强达标、分时反复穿越VWAP，宁可错过也不要勉强加。",
                "加仓：突破需持续放量", "ADD_HALF");
        addEntry(
                "满仓条件最严格：吃掉上影线+突破水线+站上VWAP+放量必须全部同时满足。任何一项勉强达标都要存疑，因为同日满仓意味着T+1前不可卖。",
                "满仓：四项全满足才支持", "BUY_FULL");
        addEntry(
                "一级抛压预警：当日涨幅从峰值回撤一半时，说明分歧在加大。即使还没触发止损位，也要提高警惕，考虑是否提前减仓。",
                "抛压预警：峰值回撤需重视", "WARN_PRESSURE");
        addEntry(
                "止损信号必须优先尊重：跌破前阳低是独立破位，跌破分歧K线中点/最低点是最后防线。资金安全第一，不要侥幸摊平或死扛。",
                "止损：规则触发应果断执行", "STOP_LOSS");
        addEntry(
                "右侧交易的核心是等分歧转一致，不是猜底。任何买入信号都要问：放量验证了吗？站上VWAP持续了吗？",
                "通用：右侧等验证不猜底", "");
    }

    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context ctx) { super(ctx, DB_NAME, null, DB_VERSION); }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS wisdom_entries (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "raw_text TEXT," +      // 用户教的原始话术
                    "summary TEXT," +        // AI/规则生成的简明摘要（用于进化记录展示）
                    "category TEXT DEFAULT ''" +  // 判断类型：BUY_STARTER/ADD_HALF/BUY_FULL/
                                                    // WARN_PRESSURE/STOP_LOSS，空字符串=通用（不区分类型都注入）
                    ",added_at INTEGER)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // 【重要】这里之前是DROP TABLE重建，升级会把用户已经教过的话术全部清空！
            // 现改为ALTER TABLE增列，保留已有数据。旧记录的category默认为空字符串
            // （即“通用”，不会因为新增分类字段而被过滤掉）。
            if (oldVersion < 2) {
                try {
                    db.execSQL("ALTER TABLE wisdom_entries ADD COLUMN category TEXT DEFAULT ''");
                } catch (Exception e) {
                    Log.w(TAG, "category列可能已存在，跳过: " + e.getMessage());
                }
            }
        }
    }

    public long addEntry(String rawText, String summary, String category) {
        ContentValues cv = new ContentValues();
        cv.put("raw_text", rawText);
        cv.put("summary", summary);
        cv.put("category", category == null ? "" : category);
        cv.put("added_at", System.currentTimeMillis());
        long id = mDb.insert("wisdom_entries", null, cv);
        Log.i(TAG, "新增话术#" + id + "[" + (category == null || category.isEmpty() ? "通用" : category) + "]: " + summary);
        return id;
    }

    /** 兼容旧调用：不指定分类，默认为通用（不区分判断类型都会注入） */
    public long addEntry(String rawText, String summary) {
        return addEntry(rawText, summary, "");
    }

    public static class WisdomEntry {
        public long id;
        public String rawText, summary;
        public String category = "";
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
                int catIdx = c.getColumnIndex("category");
                e.category = catIdx >= 0 ? c.getString(catIdx) : "";
                if (e.category == null) e.category = "";
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
                o.put("category", e.category == null ? "" : e.category);
                o.put("addedAt", mDateFmt.format(new Date(e.addedAt)));
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    /**
     * 生成供 Prompt 注入的知识块——只注入“通用”（category为空）和“与当前判断类型匹配”
     * 的话术，而不是无差别塞全部。actionKey 传 null 或空字符串时退化为不过滤（全部注入），
     * 供选股/聊天等没有具体判断类型的场景使用。
     */
    public String buildInjectBlock(String actionKey) {
        List<WisdomEntry> all = getAll(); // 已按时间倒序
        if (all.isEmpty()) return "";
        boolean filterByType = actionKey != null && !actionKey.isEmpty();

        StringBuilder sb = new StringBuilder("\n【你之前学过的操盘手补充话术（务必参考）】\n");
        int used = sb.length();
        int count = 0;
        for (WisdomEntry e : all) {
            if (count >= MAX_INJECT_ENTRIES) break;
            if (filterByType && e.category != null && !e.category.isEmpty()
                    && !e.category.equalsIgnoreCase(actionKey)) {
                continue; // 分类明确且与当前判断类型不匹配，跳过
            }
            String line = "· " + e.rawText + "\n";
            if (used + line.length() > MAX_INJECT_CHARS) break;
            sb.append(line);
            used += line.length();
            count++;
        }
        sb.append("\n");
        return sb.toString();
    }

    /** 兼容旧调用：不按类型过滤，注入全部（选股/聊天等无具体判断类型场景用） */
    public String buildInjectBlock() {
        return buildInjectBlock(null);
    }

    public boolean hasAny() {
        Cursor c = mDb.rawQuery("SELECT COUNT(*) FROM wisdom_entries", null);
        try { return c.moveToFirst() && c.getInt(0) > 0; } finally { c.close(); }
    }
}
