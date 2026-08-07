package com.monsieurmahjong.iqoowang.fragment;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 日历消费对话框
 *
 * 功能：
 *  - 以月视图展示日历，每个日期格内显示当日消费金额
 *  - 支持上一月 / 下一月导航
 *  - 今日高亮显示
 *  - 有消费的日期以绿色金额展示，无消费显示为空
 *  - 弹出时使用与 AchievementDialogFragment 一致的缩放动画
 */
public class CalendarDialogFragment extends DialogFragment {

    // 当前显示的年月
    private int displayYear;
    private int displayMonth; // 0-indexed (Calendar.MONTH)

    // 当前 displayYear/displayMonth 这一个月的支出数据：key = "yyyy-MM-dd"，value = 总金额（分）。
    // 【2026-08 修复】以前是构造时一次性传入 List<Expense> 建好整份 map，翻页时不会重新查询——
    // 只有"打开对话框那一刻"所在的月份是对的，翻到其它月份格子全部空白（看起来像消费记录丢了）。
    // 现在改成每次显示的月份变化（初次加载 / 上一月 / 下一月）都重新按月查库，永远只缓存当前这一屏。
    private Map<String, Long> dailyExpenseMap = new HashMap<>();

    // 今日日期字符串 "yyyy-MM-dd"
    private String todayStr;

    // 动态日历格子容器
    private GridLayout calendarGrid;
    private TextView tvMonthTitle;

    private AppDatabase db;

    // ── 工厂方法 ─────────────────────────────────────────

    public static CalendarDialogFragment newInstance() {
        return new CalendarDialogFragment();
    }

    /**
     * 将支出列表按日期聚合为 Map<"yyyy-MM-dd", 总金额(分)>
     *
     * 对应 ExpenseDao 字段：
     *   - date_str 列 → Expense.getDateStr()  (格式 "yyyy-MM-dd")
     *   - amount   列 → Expense.getAmount()   (单位：分)
     */
    private void buildExpenseMap(List<Expense> expenses) {
        dailyExpenseMap.clear();
        if (expenses == null) return;
        for (Expense e : expenses) {
            try {
                String dateKey = e.getDate_str();   // date_str 字段，如 "2026-05-26"
                if (dateKey == null || dateKey.isEmpty()) continue;
                long amount = e.getAmount();        // amount 字段，单位分
                long prev   = dailyExpenseMap.containsKey(dateKey)
                        ? dailyExpenseMap.get(dateKey) : 0L;
                dailyExpenseMap.put(dateKey, prev + amount);
            } catch (Exception ex) {
                // 跳过字段访问异常
            }
        }
    }

    // ── 生命周期 ─────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Calendar today = Calendar.getInstance();
        displayYear  = today.get(Calendar.YEAR);
        displayMonth = today.get(Calendar.MONTH);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        todayStr = sdf.format(today.getTime());
        db = AppDatabase.getDatabase(requireContext().getApplicationContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // 配置 Dialog 窗口
        Dialog dialog = getDialog();
        if (dialog != null) {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setBackgroundDrawableResource(android.R.color.transparent);
                // ✅ 与成就对话框相同的弹出缩放动画
                window.setWindowAnimations(R.style.DialogScaleAnimation);
            }
        }

        // ── 根容器 ─────────────────────────────────────
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(buildRoundedBg("#FFFFFF", dp2px(20)));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp2px(16), dp2px(16), dp2px(16), dp2px(20));
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 标题栏 ─────────────────────────────────────
        content.addView(buildHeader());

        // ── 月份导航栏 ─────────────────────────────────
        content.addView(buildMonthNav());

        // ── 星期标题行 ─────────────────────────────────
        content.addView(buildWeekdayRow());

        // ── 日历格子（动态渲染） ─────────────────────
        calendarGrid = new GridLayout(requireContext());
        calendarGrid.setColumnCount(7);
        calendarGrid.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(calendarGrid);

        // ── 图例说明 ────────────────────────────────────
        content.addView(buildLegend());

        scrollView.addView(content);
        root.addView(scrollView);

        // 先同步渲染一次空壳（日期数字/今日高亮先出来，避免弹窗先白屏一下），
        // 真实的当月消费数据异步查完之后在 loadMonthDataAndRender() 里再刷新一次
        renderCalendar();
        loadMonthDataAndRender();

        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    // ── 构建子视图 ────────────────────────────────────────

    /** 顶部标题栏：图标 + "消费日历" + 关闭按钮 */
    private View buildHeader() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp2px(12);
        row.setLayoutParams(p);

        // 日历图标（Material Symbol 文本图标，与主界面保持一致）
        TextView tvIcon = new TextView(requireContext());
        tvIcon.setText("calendar_month");
        tvIcon.setTextColor(Color.parseColor("#003527"));
        tvIcon.setTextSize(20);
        LinearLayout.LayoutParams iconP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iconP.setMarginEnd( dp2px(8));
        tvIcon.setLayoutParams(iconP);

        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText("消费日历");
        tvTitle.setTextSize(18);
        tvTitle.setTextColor(Color.parseColor("#111c2d"));
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvClose = new TextView(requireContext());
        tvClose.setText("✕");
        tvClose.setTextSize(16);
        tvClose.setTextColor(Color.parseColor("#b0b8c1"));
        tvClose.setPadding(dp2px(8), dp2px(4), dp2px(4), dp2px(4));
        tvClose.setOnClickListener(v -> dismiss());

        row.addView(tvIcon);
        row.addView(tvTitle);
        row.addView(tvClose);
        return row;
    }

    /** 月份导航：< 2026年05月 > */
    private View buildMonthNav() {
        LinearLayout nav = new LinearLayout(requireContext());
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setBackground(buildRoundedBg("#f4f6fa", dp2px(12)));
        nav.setPadding(dp2px(8), dp2px(10), dp2px(8), dp2px(10));
        LinearLayout.LayoutParams navP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navP.bottomMargin = dp2px(12);
        nav.setLayoutParams(navP);

        // 上一月按钮
        TextView btnPrev = new TextView(requireContext());
        btnPrev.setText("‹");
        btnPrev.setTextSize(22);
        btnPrev.setTextColor(Color.parseColor("#003527"));
        btnPrev.setGravity(Gravity.CENTER);
        btnPrev.setPadding(dp2px(12), dp2px(4), dp2px(12), dp2px(4));
        btnPrev.setOnClickListener(v -> {
            displayMonth--;
            if (displayMonth < 0) { displayMonth = 11; displayYear--; }
            updateMonthTitle();
            loadMonthDataAndRender();
        });

        // 月份标题
        tvMonthTitle = new TextView(requireContext());
        tvMonthTitle.setTextSize(15);
        tvMonthTitle.setTextColor(Color.parseColor("#111c2d"));
        tvMonthTitle.setTypeface(null, Typeface.BOLD);
        tvMonthTitle.setGravity(Gravity.CENTER);
        tvMonthTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        updateMonthTitle();

        // 下一月按钮
        TextView btnNext = new TextView(requireContext());
        btnNext.setText("›");
        btnNext.setTextSize(22);
        btnNext.setTextColor(Color.parseColor("#003527"));
        btnNext.setGravity(Gravity.CENTER);
        btnNext.setPadding(dp2px(12), dp2px(4), dp2px(12), dp2px(4));
        btnNext.setOnClickListener(v -> {
            displayMonth++;
            if (displayMonth > 11) { displayMonth = 0; displayYear++; }
            updateMonthTitle();
            loadMonthDataAndRender();
        });

        nav.addView(btnPrev);
        nav.addView(tvMonthTitle);
        nav.addView(btnNext);
        return nav;
    }

    /** 星期标题行：一 二 三 四 五 六 日 */
    private View buildWeekdayRow() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowP.bottomMargin = dp2px(4);
        row.setLayoutParams(rowP);

        String[] weekdays = {"一", "二", "三", "四", "五", "六", "日"};
        for (int i = 0; i < 7; i++) {
            TextView tv = new TextView(requireContext());
            tv.setText(weekdays[i]);
            tv.setTextSize(11);
            tv.setTextColor(i >= 5
                    ? Color.parseColor("#e05252")   // 周末红色
                    : Color.parseColor("#707974"));  // 工作日灰色
            tv.setGravity(Gravity.CENTER);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tv);
        }
        return row;
    }

    /** 底部图例 */
    private View buildLegend() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp2px(14);
        row.setLayoutParams(p);

        // 今日标记示例
        View todayDot = new View(requireContext());
        android.graphics.drawable.GradientDrawable dotBg =
                new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(Color.parseColor("#003527"));
        todayDot.setBackground(dotBg);
        todayDot.setLayoutParams(new LinearLayout.LayoutParams(dp2px(8), dp2px(8)));

        TextView tvTodayLabel = new TextView(requireContext());
        tvTodayLabel.setText("今日");
        tvTodayLabel.setTextSize(11);
        tvTodayLabel.setTextColor(Color.parseColor("#707974"));
        LinearLayout.LayoutParams labelP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelP.leftMargin  = dp2px(6);
        labelP.rightMargin = dp2px(20);
        tvTodayLabel.setLayoutParams(labelP);

        // 有消费标记示例
        TextView tvExpenseDot = new TextView(requireContext());
        tvExpenseDot.setText("¥");
        tvExpenseDot.setTextSize(11);
        tvExpenseDot.setTextColor(Color.parseColor("#1a9e6e"));
        tvExpenseDot.setTypeface(null, Typeface.BOLD);

        TextView tvExpenseLabel = new TextView(requireContext());
        tvExpenseLabel.setText("当日消费");
        tvExpenseLabel.setTextSize(11);
        tvExpenseLabel.setTextColor(Color.parseColor("#707974"));
        LinearLayout.LayoutParams elP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elP.leftMargin = dp2px(4);
        tvExpenseLabel.setLayoutParams(elP);

        row.addView(todayDot);
        row.addView(tvTodayLabel);
        row.addView(tvExpenseDot);
        row.addView(tvExpenseLabel);
        return row;
    }

    // ── 日历渲染核心 ─────────────────────────────────────

    /**
     * 按 displayYear/displayMonth 重新查询这一个月的消费数据并刷新格子。
     * 初次显示、上一月、下一月都会调用这个方法——每次都是新查询，不复用旧月份的缓存，
     * 这样翻到任何月份都能正确显示该月的消费标记，而不是只有打开对话框那一刻的月份是对的。
     *
     * 用请求发出时的 reqYear/reqMonth 和查询返回时的 displayYear/displayMonth 做比对，
     * 丢弃"用户已经又翻了别的月份"之后才妧妧来迟的过期结果，避免快速连续翻页时乱序覆盖。
     */
    private void loadMonthDataAndRender() {
        if (getContext() == null || db == null) return;
        final int reqYear = displayYear;
        final int reqMonth = displayMonth;
        String monthKey = String.format(Locale.getDefault(), "%d-%02d", reqYear, reqMonth + 1);

        new Thread(() -> {
            List<Expense> monthExpenses = db.expenseDao().getAllExpensesByMonthSync(monthKey);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (!isAdded() || reqYear != displayYear || reqMonth != displayMonth) return;
                buildExpenseMap(monthExpenses);
                renderCalendar();
            });
        }).start();
    }

    /** 更新月份标题文字 */
    private void updateMonthTitle() {
        if (tvMonthTitle != null) {
            tvMonthTitle.setText(String.format(Locale.getDefault(),
                    "%d年%02d月", displayYear, displayMonth + 1));
        }
    }

    /**
     * 完整渲染当前 displayYear/displayMonth 的日历格子
     * 每格：日期数字 + 当日消费金额（无消费则留空）
     */
    private void renderCalendar() {
        if (calendarGrid == null) return;
        calendarGrid.removeAllViews();

        // 构建当月日历
        Calendar cal = Calendar.getInstance();
        cal.set(displayYear, displayMonth, 1);

        int firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK); // SUN=1 MON=2
        // 转换为周一起始：MON=0 ... SUN=6
        int startOffset = (firstDayOfWeek == Calendar.SUNDAY) ? 6 : (firstDayOfWeek - 2);

        int daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        int totalCells  = startOffset + daysInMonth;
        // 补齐为 7 的倍数
        int rows = (int) Math.ceil(totalCells / 7.0);
        int cellCount = rows * 7;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

        for (int i = 0; i < cellCount; i++) {
            int dayNumber = i - startOffset + 1; // 1..daysInMonth
            boolean isValidDay = (dayNumber >= 1 && dayNumber <= daysInMonth);

            // 构建日期字符串
            String dateKey = "";
            if (isValidDay) {
                cal.set(displayYear, displayMonth, dayNumber);
                dateKey = sdf.format(cal.getTime());
            }

            boolean isToday    = dateKey.equals(todayStr);
            boolean isWeekend  = (i % 7 == 5 || i % 7 == 6); // 第6、7列 = 周六、周日
            Long expenseCents  = isValidDay ? dailyExpenseMap.get(dateKey) : null;

            View cell = buildDayCell(dayNumber, isValidDay, isToday, isWeekend, expenseCents, dateKey);

            GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
            gp.width  = 0;
            gp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            gp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            gp.setMargins(dp2px(1), dp2px(2), dp2px(1), dp2px(2));
            cell.setLayoutParams(gp);

            calendarGrid.addView(cell);
        }
    }

    /**
     * 构建单个日期格子
     *
     * 布局：
     *   ┌──────────────┐
     *   │      15      │  ← 日期数字
     *   │    ¥128      │  ← 消费金额（无消费时留空）
     *   └──────────────┘
     */
    private View buildDayCell(int dayNumber, boolean isValidDay,
                              boolean isToday, boolean isWeekend,
                              Long expenseCents, String dateKey) {

        LinearLayout cell = new LinearLayout(requireContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp2px(2), dp2px(6), dp2px(2), dp2px(6));
        cell.setMinimumHeight(dp2px(52));

        if (isToday) {
            // 今日：深绿圆角背景
            cell.setBackground(buildRoundedBg("#003527", dp2px(10)));
        } else if (isValidDay && expenseCents != null && expenseCents > 0) {
            // 有消费：浅绿背景
            cell.setBackground(buildRoundedBg("#e6f7f0", dp2px(10)));
        } else {
            cell.setBackground(null);
        }

        // 日期数字
        TextView tvDay = new TextView(requireContext());
        if (isValidDay) {
            tvDay.setText(String.valueOf(dayNumber));
        } else {
            tvDay.setText("");
        }
        tvDay.setTextSize(14);
        tvDay.setGravity(Gravity.CENTER);
        tvDay.setTypeface(null, isToday ? Typeface.BOLD : Typeface.NORMAL);

        if (!isValidDay) {
            tvDay.setTextColor(Color.TRANSPARENT);
        } else if (isToday) {
            tvDay.setTextColor(Color.WHITE);
        } else if (isWeekend) {
            tvDay.setTextColor(Color.parseColor("#e05252"));
        } else {
            tvDay.setTextColor(Color.parseColor("#111c2d"));
        }
        cell.addView(tvDay);

        // 消费金额
        TextView tvAmount = new TextView(requireContext());
        if (isValidDay && expenseCents != null && expenseCents > 0) {
            double yuan = expenseCents / 100.0;
            // 超过 999 元简化显示
            if (yuan >= 1000) {
                tvAmount.setText(String.format(Locale.getDefault(), "¥%.0fk", yuan / 1000));
            } else {
                tvAmount.setText(String.format(Locale.getDefault(), "¥%.0f", yuan));
            }
            tvAmount.setTextColor(isToday ? Color.parseColor("#a8e6cf") : Color.parseColor("#1a9e6e"));
            tvAmount.setTypeface(null, Typeface.BOLD);
        } else {
            tvAmount.setText("");
        }
        tvAmount.setTextSize(9);
        tvAmount.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams amtP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        amtP.topMargin = dp2px(2);
        tvAmount.setLayoutParams(amtP);
        cell.addView(tvAmount);

        // 点击日期格子跳转到 HistoryGalleryActivity、展示对应天的消费记录
        if (isValidDay) {
            cell.setClickable(true);
            cell.setFocusable(true);
            cell.setOnClickListener(v -> navigateToGalleryForDate(dateKey));
        }

        return cell;
    }

    /** 跳转到 HistoryGalleryActivity 并定位到指定日期（yyyy-MM-dd） */
    private void navigateToGalleryForDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty() || getContext() == null) return;
        try {
            String[] parts = dateStr.split("-");
            int y = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            int d = Integer.parseInt(parts[2]);

            android.content.Intent intent = new android.content.Intent(
                    requireContext(), com.monsieurmahjong.iqoowang.HistoryGalleryActivity.class);
            intent.putExtra("jump_year", y);
            intent.putExtra("jump_month", m);
            intent.putExtra("jump_day", d);
            startActivity(intent);
            dismiss();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    // ── 工具方法 ─────────────────────────────────────────

    private android.graphics.drawable.GradientDrawable buildRoundedBg(
            String colorHex, int cornerRadius) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(cornerRadius);
        d.setColor(Color.parseColor(colorHex));
        return d;
    }

    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
