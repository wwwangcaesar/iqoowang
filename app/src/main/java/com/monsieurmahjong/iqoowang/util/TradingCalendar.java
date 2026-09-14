package com.monsieurmahjong.iqoowang.util;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * TradingCalendar — A股法定节假日日历（R1）
 *
 * 【背景】此前 MarketDataManager.computeExpectedTradeDate()、RealtimeMonitorService.
 * isWithinTradingHours()/computeObserveDays() 等处都只按"周几"判断交易日，完全不知道法定
 * 节假日的存在（代码注释里都承认了这个简化）。连续多日的法定假期（春节10天、国庆7天）跨度
 * 超过一个普通周末时，仅按周末做单步/逐周判断算不对"最近一个真实交易日"——最直接的影响是
 * TradingRuleEngine.getPrevDayRef() 里的陈旧数据判断：假期最后一天或复市当天，会把明明有效、
 * 只是"还没到下一个交易日"的缓存数据误判成陈旧，触发"参考数据已陈旧，拒绝产生任何买卖信号"
 * 的安全拦截——恰好是复市当天最需要系统正常工作的时候。
 *
 * 数据来源：assets/trading_calendar.json，按上交所/深交所/北交所官方发布的休市公告整理，
 * 每年12月交易所公告发布后手动更新一次即可（App会在年底自动提醒，见 needsYearEndReminder()）。
 */
public class TradingCalendar {

    private static final String TAG = "TradingCalendar";
    private static final String ASSET_PATH = "trading_calendar.json";

    private static TradingCalendar sInstance;
    /** 每个元素是 [起始日0点的毫秒, 结束日0点的毫秒]，含首尾 */
    private final List<long[]> mHolidayRanges = new ArrayList<>();
    private int mMaxCoveredYear = 0;
    private final SimpleDateFormat mFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);

    public static void init(Context context) {
        if (sInstance == null) {
            synchronized (TradingCalendar.class) {
                if (sInstance == null) sInstance = new TradingCalendar(context.getApplicationContext());
            }
        }
    }

    public static TradingCalendar get() {
        if (sInstance == null) throw new IllegalStateException("call init() first");
        return sInstance;
    }

    private TradingCalendar(Context context) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open(ASSET_PATH), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            JSONObject root = new JSONObject(sb.toString());
            JSONArray years = root.optJSONArray("years");
            if (years != null) {
                for (int y = 0; y < years.length(); y++) {
                    JSONObject yearObj = years.getJSONObject(y);
                    int year = yearObj.optInt("year", 0);
                    if (year > mMaxCoveredYear) mMaxCoveredYear = year;
                    JSONArray holidays = yearObj.optJSONArray("holidays");
                    if (holidays == null) continue;
                    for (int i = 0; i < holidays.length(); i++) {
                        JSONObject h = holidays.getJSONObject(i);
                        long start = parseDateMs(h.optString("start", null));
                        long end = parseDateMs(h.optString("end", null));
                        if (start > 0 && end > 0) mHolidayRanges.add(new long[]{start, end});
                    }
                }
            }
            Log.i(TAG, "已加载节假日日历，覆盖至" + mMaxCoveredYear + "年，共" + mHolidayRanges.size() + "个假期区间");
        } catch (Exception e) {
            Log.e(TAG, "加载trading_calendar.json失败，将退化为仅按周末判断（不识别法定节假日）", e);
        }
    }

    private long parseDateMs(String dateStr) {
        if (dateStr == null) return 0;
        try {
            Calendar cal = Calendar.getInstance();
            cal.setTime(mFmt.parse(dateStr));
            clearTime(cal);
            return cal.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    private void clearTime(Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
    }

    private boolean isHoliday(Calendar cal) {
        Calendar c = (Calendar) cal.clone();
        clearTime(c);
        long ms = c.getTimeInMillis();
        for (long[] range : mHolidayRanges) {
            if (ms >= range[0] && ms <= range[1]) return true;
        }
        return false;
    }

    /** 是否为交易日：非周末 且 不在法定节假日区间内。日历数据没覆盖到的年份只退化判断周末
     *  （不会抛异常/崩溃，但也无法识别那一年的节假日，对应"年底忘记更新"的兜底行为）。 */
    public boolean isTradingDay(Calendar cal) {
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false;
        return !isHoliday(cal);
    }

    public boolean isTradingDay(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return isTradingDay(cal);
    }

    /**
     * 年底提醒：当前是12月，且日历数据还没覆盖到明年，就需要提醒更新。
     * 交易所一般当月中下旬就会发布次年的休市安排（比如2026年安排是2025-12-22发布的），
     * 届时把新一年的假期区间加进 trading_calendar.json 的 years 数组即可。
     */
    public boolean needsYearEndReminder() {
        Calendar cal = Calendar.getInstance();
        if (cal.get(Calendar.MONTH) != Calendar.DECEMBER) return false;
        int nextYear = cal.get(Calendar.YEAR) + 1;
        return mMaxCoveredYear < nextYear;
    }

    public int getMaxCoveredYear() {
        return mMaxCoveredYear;
    }
}
