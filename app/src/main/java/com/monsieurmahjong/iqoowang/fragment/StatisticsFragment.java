package com.monsieurmahjong.iqoowang.fragment;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;
import com.monsieurmahjong.iqoowang.utils.AnimationUtils;
import com.monsieurmahjong.iqoowang.view.PercentagePieChartView;
import com.monsieurmahjong.iqoowang.view.SegmentedControlView;
import com.monsieurmahjong.iqoowang.view.SmoothLineChartView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import android.widget.ScrollView;
import androidx.appcompat.app.AlertDialog;

public class StatisticsFragment extends Fragment {

    private SegmentedControlView segmentedTab;
    private SmoothLineChartView smoothLineChart;
    private PercentagePieChartView percentagePieChart;

    private TextView tvTotalAmount;
    private TextView tvTrendPercent;
    private ImageView ivTrendArrow;
    private LinearLayout containerCategoryList;
    private TextView tvSmartTip;
    private TextView tvDailyAvg;
    private TextView tvActiveDays;

    private AppDatabase db;

    private final String[] CHART_HEX_COLORS = {"#003527", "#505F76", "#B7C8E1", "#E7EEFF"};
    private final String TRACK_COLOR = "#F0F3FF";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_statistics, container, false);

        segmentedTab = view.findViewById(R.id.segmented_tab);
        smoothLineChart = view.findViewById(R.id.smooth_line_chart);
        percentagePieChart = view.findViewById(R.id.percentage_pie_chart);

        tvTotalAmount = view.findViewById(R.id.tv_total_amount);
        tvTrendPercent = view.findViewById(R.id.tv_trend_percent);
        ivTrendArrow = view.findViewById(R.id.iv_trend_arrow);
        containerCategoryList = view.findViewById(R.id.container_category_list);
        tvSmartTip = view.findViewById(R.id.tv_smart_tip);
        tvDailyAvg = view.findViewById(R.id.tv_daily_avg);
        tvActiveDays = view.findViewById(R.id.tv_active_days);
        ImageView tvCalendarMonth = view.findViewById(R.id.iv_calendar);
        tvCalendarMonth.setOnClickListener(v -> openCalendarDialog());
        db = AppDatabase.getDatabase(requireContext());

        setupChartInteractions();
        return view;
    }

    private void setupChartInteractions() {
        segmentedTab.setOnTabSelectedListener((index, text) -> {
            loadAnalyticsData(index);
        });
        loadAnalyticsData(0); // 默认加载周数据
    }

    private void loadAnalyticsData(int periodIndex) {
        new Thread(() -> {
            long currentStart, currentEnd, prevStart, prevEnd;
            Calendar cal = Calendar.getInstance();
            currentEnd = cal.getTimeInMillis();

            int pointCount;
            String[] xAxisLabels;
            int daysInPeriod;
            String periodName;

            // =====================================
            // 1. 标准自然日切片算法（修复时间轴错位）
            // =====================================
            if (periodIndex == 0) {
                // 【周】：往前推6天，包含今天共7个自然日
                daysInPeriod = 7;
                periodName = "本周";
                pointCount = 7;

                cal.add(Calendar.DAY_OF_YEAR, -6);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                currentStart = cal.getTimeInMillis();

                prevEnd = currentStart - 1;
                prevStart = prevEnd - (7L * 24 * 60 * 60 * 1000) + 1;

                xAxisLabels = new String[7];
                Calendar labelCal = Calendar.getInstance();
                labelCal.setTimeInMillis(currentStart);
                SimpleDateFormat sdf = new SimpleDateFormat("MM/dd", Locale.getDefault());
                for(int i = 0; i < 7; i++) {
                    xAxisLabels[i] = sdf.format(labelCal.getTime());
                    labelCal.add(Calendar.DAY_OF_YEAR, 1);
                }
            } else if (periodIndex == 1) {
                // 【月】：标准自然月切分（按当月日期划分为5个周档位）
                periodName = "本月";
                pointCount = 5;

                cal.set(Calendar.DAY_OF_MONTH, 1);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                currentStart = cal.getTimeInMillis();
                daysInPeriod = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

                cal.add(Calendar.MILLISECOND, -1);
                prevEnd = cal.getTimeInMillis();
                cal.set(Calendar.DAY_OF_MONTH, 1);
                prevStart = cal.getTimeInMillis();

                xAxisLabels = new String[]{"W1", "W2", "W3", "W4", "W5"};
            } else {
                // 【年】：标准自然年切分（12个月）
                periodName = "今年";
                pointCount = 12;

                cal.set(Calendar.DAY_OF_YEAR, 1);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                currentStart = cal.getTimeInMillis();
                daysInPeriod = cal.getActualMaximum(Calendar.DAY_OF_YEAR);

                cal.add(Calendar.MILLISECOND, -1);
                prevEnd = cal.getTimeInMillis();
                cal.set(Calendar.DAY_OF_YEAR, 1);
                prevStart = cal.getTimeInMillis();

                xAxisLabels = new String[]{"1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月", "12月"};
            }

            // =====================================
            // 2. 真实数据库投递与精准坐标映射
            // =====================================
            List<Expense> currentExpenses = db.expenseDao().getExpensesInRangeSync(currentStart, currentEnd);
            List<Expense> prevExpenses = db.expenseDao().getExpensesInRangeSync(prevStart, prevEnd);

            long currentTotalCents = 0;
            Map<String, Long> categoryTotals = new HashMap<>();
            float[] trendData = new float[pointCount];
            Calendar extractCal = Calendar.getInstance();

            for (Expense e : currentExpenses) {
                long amount = e.getAmount();
                currentTotalCents += amount;

                String catName = e.getCategoryName() != null ? e.getCategoryName() : "其他";
                categoryTotals.put(catName, categoryTotals.getOrDefault(catName, 0L) + amount);

                extractCal.setTimeInMillis(e.getTimestamp());
                int bucketIndex = 0;

                // 绝对日历时间轴对齐，彻底解决相对偏差溢出
                if (periodIndex == 0) {
                    long diffMillis = e.getTimestamp() - currentStart;
                    bucketIndex = (int) (diffMillis / (24 * 60 * 60 * 1000L));
                } else if (periodIndex == 1) {
                    int day = extractCal.get(Calendar.DAY_OF_MONTH);
                    bucketIndex = (day - 1) / 7;
                } else {
                    bucketIndex = extractCal.get(Calendar.MONTH);
                }

                if (bucketIndex >= pointCount) bucketIndex = pointCount - 1;
                if (bucketIndex < 0) bucketIndex = 0;

                trendData[bucketIndex] += amount;
            }

            long prevTotalCents = 0;
            Map<String, Long> prevCategoryTotals = new HashMap<>();
            for (Expense e : prevExpenses) {
                prevTotalCents += e.getAmount();
                String catName = e.getCategoryName() != null ? e.getCategoryName() : "其他";
                prevCategoryTotals.put(catName, prevCategoryTotals.getOrDefault(catName, 0L) + e.getAmount());
            }

            // =====================================
            // 3. 趋势比对与异常态（零基准）修复
            // =====================================
            String trendText;
            boolean isTrendUp;

            if (prevTotalCents > 0) {
                long diff = currentTotalCents - prevTotalCents;
                int pct = (int) ((diff * 100) / prevTotalCents);
                isTrendUp = diff > 0;
                trendText = (isTrendUp ? "+" : "") + pct + "%";
            } else if (currentTotalCents > 0) {
                isTrendUp = true;
                trendText = "+100%"; // 修复上周期为0，当前突增的极端态
            } else {
                isTrendUp = false;
                trendText = "0%";
            }

            // 图表纵轴归一化与防塌陷处理
            float maxBucket = 0;
            for (float val : trendData) if (val > maxBucket) maxBucket = val;
            float[] normalizedTrend = new float[pointCount];
            for (int i = 0; i < pointCount; i++) {
                normalizedTrend[i] = (maxBucket == 0) ? 0.0f : (trendData[i] / maxBucket);
            }

            // 分类提取 Top
            List<Map.Entry<String, Long>> sortedCategories = new ArrayList<>(categoryTotals.entrySet());
            Collections.sort(sortedCategories, (o1, o2) -> o2.getValue().compareTo(o1.getValue()));

            String smartTipText;
            if (!sortedCategories.isEmpty()) {
                String topCategory = sortedCategories.get(0).getKey();
                long currentTopAmt = sortedCategories.get(0).getValue();
                long prevTopAmt = prevCategoryTotals.getOrDefault(topCategory, 0L);

                if (prevTopAmt > 0) {
                    long diff = currentTopAmt - prevTopAmt;
                    int pct = (int) Math.abs((diff * 100) / prevTopAmt);
                    if (diff > 0) {
                        smartTipText = "您" + periodName + "的「" + topCategory + "」支出比上一周期增加了 " + pct + "%，请注意合理规划！";
                    } else {
                        smartTipText = "您" + periodName + "的「" + topCategory + "」支出比上一周期降低了 " + pct + "%，继续保持！";
                    }
                } else {
                    smartTipText = "「" + topCategory + "」是您" + periodName + "的主要开销，记账习惯非常棒！";
                }
            } else {
                smartTipText = "暂无消费数据，良好的财务规划从第一笔记账开始。";
            }

            long dailyAvgCents = currentTotalCents / daysInPeriod;

            // =====================================
            // 4. 主线程 UI 同步挂载
            // =====================================
            final long finalCurrentTotalCents = currentTotalCents;
            final String finalTrendText = trendText;
            final boolean finalIsTrendUp = isTrendUp;

            long finalPrevTotalCents = prevTotalCents;
            requireActivity().runOnUiThread(() -> {
                AnimationUtils.animateAmount(tvTotalAmount, finalCurrentTotalCents);

                // 正确接管状态展示
                tvTrendPercent.setText(finalTrendText);
                if (finalCurrentTotalCents == 0 && finalPrevTotalCents == 0) {
                    // 无数据时的中立灰态
                    tvTrendPercent.setTextColor(Color.parseColor("#707974"));
                    ivTrendArrow.setImageResource(R.drawable.ic_trending_up);
                } else if (finalIsTrendUp) {
                    // 支出增加，警告红
                    tvTrendPercent.setTextColor(Color.parseColor("#BA1A1A"));
                    ivTrendArrow.setImageResource(R.drawable.ic_trending_up);
                } else {
                    // 支出减少，安全绿
                    tvTrendPercent.setTextColor(Color.parseColor("#2B6954"));
                    ivTrendArrow.setImageResource(R.drawable.ic_down);
                }

                // 更新折线图
                if (periodIndex == 0) {
                    // 周图展示头、中、尾标签
                    smoothLineChart.setDynamicLabels(new String[]{xAxisLabels[0], xAxisLabels[3], xAxisLabels[6]});
                } else if (periodIndex == 2) {
                    // 年图抽取季节点
                    smoothLineChart.setDynamicLabels(new String[]{xAxisLabels[0], xAxisLabels[3], xAxisLabels[6], xAxisLabels[9]});
                } else {
                    smoothLineChart.setDynamicLabels(xAxisLabels);
                }
                smoothLineChart.setData(normalizedTrend);

                tvDailyAvg.setText(String.format(Locale.getDefault(), "¥ %.2f", dailyAvgCents / 100.0));
                tvActiveDays.setText(currentExpenses.size() + " 笔记录");
                tvSmartTip.setText(smartTipText);

                renderCategoryBreakdown(sortedCategories, finalCurrentTotalCents, currentExpenses);
            });
        }).start();
    }

    private void renderCategoryBreakdown(List<Map.Entry<String, Long>> sortedCategories, long totalCents, List<Expense> periodExpenses) {
        containerCategoryList.removeAllViews();
        List<PercentagePieChartView.PieEntry> pieEntries = new ArrayList<>();

        if (totalCents <= 0 || sortedCategories.isEmpty()) {
            pieEntries.add(new PercentagePieChartView.PieEntry(1.0f, Color.parseColor(CHART_HEX_COLORS[3])));
            percentagePieChart.setEntries(pieEntries);
            return;
        }

        long top3Total = 0;
        int listLimit = Math.min(sortedCategories.size(), 4);

        // 记录前三大分类名称，供“其他”行点击时做反向筛选
        Set<String> top3CategoryKeys = new HashSet<>();
        for (int k = 0; k < Math.min(sortedCategories.size(), 3); k++) {
            top3CategoryKeys.add(sortedCategories.get(k).getKey());
        }

        for (int i = 0; i < listLimit; i++) {
            Map.Entry<String, Long> entry = sortedCategories.get(i);
            long amountCents = entry.getValue();
            float percentage = (float) amountCents / totalCents;
            boolean isOthersRow = (i == 3);

            String hexColor = CHART_HEX_COLORS[Math.min(i, 3)];
            if (i < 3) {
                pieEntries.add(new PercentagePieChartView.PieEntry(percentage, Color.parseColor(hexColor)));
                top3Total += amountCents;
            } else if (i == 3) {
                float otherPercent = (float) (totalCents - top3Total) / totalCents;
                pieEntries.add(new PercentagePieChartView.PieEntry(otherPercent, Color.parseColor(hexColor)));
            }

            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(64));
            if (i > 0) rowParams.topMargin = dp2px(8);
            row.setLayoutParams(rowParams);

            ImageView iv = new ImageView(requireContext());
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp2px(48), dp2px(48)));
            iv.setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12));
            mapIconResource(entry.getKey(), iv);
            row.addView(iv);

            LinearLayout middleGroup = new LinearLayout(requireContext());
            middleGroup.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams middleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            middleParams.leftMargin = dp2px(8);
            middleGroup.setLayoutParams(middleParams);

            TextView tvName = new TextView(requireContext());
            tvName.setText(entry.getKey());
            tvName.setTextColor(Color.parseColor("#111C2D"));
            tvName.setTextSize(16);

            View barContainer = new View(requireContext());
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(8));
            barParams.topMargin = dp2px(8);
            barContainer.setLayoutParams(barParams);
            barContainer.setBackground(createProgressBarDrawable(hexColor, percentage));

            middleGroup.addView(tvName);
            middleGroup.addView(barContainer);
            row.addView(middleGroup);

            LinearLayout rightGroup = new LinearLayout(requireContext());
            rightGroup.setOrientation(LinearLayout.VERTICAL);
            rightGroup.setGravity(Gravity.END);

            TextView tvPercent = new TextView(requireContext());
            tvPercent.setText(String.format(Locale.getDefault(), "%d%%", (int)(percentage * 100)));
            tvPercent.setTextColor(Color.parseColor("#111C2D"));
            tvPercent.setTextSize(16);

            TextView tvAmt = new TextView(requireContext());
            tvAmt.setText(String.format(Locale.getDefault(), "¥ %.2f", amountCents / 100.0));
            tvAmt.setTextColor(Color.parseColor("#404944"));
            tvAmt.setTextSize(12);
            tvAmt.setPadding(0, dp2px(2), 0, 0);

            rightGroup.addView(tvPercent);
            rightGroup.addView(tvAmt);
            row.addView(rightGroup);

            row.setClickable(true);
            row.setFocusable(true);
            android.util.TypedValue outValue = new android.util.TypedValue();
            requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            row.setBackgroundResource(outValue.resourceId);
            row.setOnClickListener(v -> {
                List<Expense> filtered = filterExpensesByCategory(periodExpenses, entry.getKey(), isOthersRow, top3CategoryKeys);
                showCategoryDetailDialog(entry.getKey(), filtered, amountCents);
            });

            containerCategoryList.addView(row);
        }

        percentagePieChart.setEntries(pieEntries);
    }

    /**
     * 按分类（或“其他”聚合分类）从当前周期消费明细中筛选出对应记录
     */
    private List<Expense> filterExpensesByCategory(List<Expense> source, String categoryKey, boolean isOthersRow, Set<String> top3CategoryKeys) {
        List<Expense> result = new ArrayList<>();
        if (source == null) return result;
        for (Expense e : source) {
            String cat = e.getCategoryName() != null ? e.getCategoryName() : "其他";
            if (isOthersRow) {
                if (!top3CategoryKeys.contains(cat)) result.add(e);
            } else {
                if (cat.equals(categoryKey)) result.add(e);
            }
        }
        Collections.sort(result, (a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
        return result;
    }

    /**
     * 分类消费明细弹窗：展示该分类（或“其他”聚合）在当前统计周期内的所有记录
     * 每条记录展示：时间 + 备注（选填） + 金额
     */
    private void showCategoryDetailDialog(String categoryName, List<Expense> expenses, long totalCents) {
        if (getContext() == null) return;

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp2px(24), dp2px(20), dp2px(24), dp2px(4));

        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText(categoryName + " 消费明细");
        tvTitle.setTextSize(18);
        tvTitle.setTextColor(Color.parseColor("#111C2D"));
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(tvTitle);

        TextView tvSubtitle = new TextView(requireContext());
        tvSubtitle.setText(String.format(Locale.getDefault(), "共 %d 笔 · 合计 ¥ %.2f", expenses.size(), totalCents / 100.0));
        tvSubtitle.setTextColor(Color.parseColor("#707974"));
        tvSubtitle.setTextSize(13);
        LinearLayout.LayoutParams subP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subP.topMargin = dp2px(4);
        subP.bottomMargin = dp2px(12);
        tvSubtitle.setLayoutParams(subP);
        root.addView(tvSubtitle);

        ScrollView scrollView = new ScrollView(requireContext());
        LinearLayout listContainer = new LinearLayout(requireContext());
        listContainer.setOrientation(LinearLayout.VERTICAL);

        if (expenses.isEmpty()) {
            TextView tvEmpty = new TextView(requireContext());
            tvEmpty.setText("暂无相关消费记录");
            tvEmpty.setTextColor(Color.parseColor("#707974"));
            tvEmpty.setGravity(Gravity.CENTER);
            tvEmpty.setPadding(0, dp2px(24), 0, dp2px(24));
            listContainer.addView(tvEmpty);
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault());
            for (Expense e : expenses) {
                LinearLayout row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp2px(10), 0, dp2px(10));

                LinearLayout left = new LinearLayout(requireContext());
                left.setOrientation(LinearLayout.VERTICAL);
                left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView tvTime = new TextView(requireContext());
                tvTime.setText(sdf.format(new Date(e.getTimestamp())));
                tvTime.setTextColor(Color.parseColor("#111C2D"));
                tvTime.setTextSize(14);
                left.addView(tvTime);

                String remark = e.getRemark();
                if (remark != null && !remark.trim().isEmpty()) {
                    TextView tvRemark = new TextView(requireContext());
                    tvRemark.setText(remark);
                    tvRemark.setTextColor(Color.parseColor("#707974"));
                    tvRemark.setTextSize(12);
                    tvRemark.setPadding(0, dp2px(2), 0, 0);
                    left.addView(tvRemark);
                }

                row.addView(left);

                TextView tvAmount = new TextView(requireContext());
                tvAmount.setText(String.format(Locale.getDefault(), "¥ %.2f", e.getAmount() / 100.0));
                tvAmount.setTextColor(Color.parseColor("#003527"));
                tvAmount.setTextSize(15);
                tvAmount.setTypeface(null, android.graphics.Typeface.BOLD);
                row.addView(tvAmount);

                listContainer.addView(row);

                View divider = new View(requireContext());
                divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(1)));
                divider.setBackgroundColor(Color.parseColor("#F0F3FF"));
                listContainer.addView(divider);
            }
        }

        scrollView.addView(listContainer);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2px(360));
        scrollView.setLayoutParams(scrollParams);
        root.addView(scrollView);

        new AlertDialog.Builder(requireContext())
                .setView(root)
                .setPositiveButton("关闭", null)
                .show();
    }
    private void openCalendarDialog() {
        // 异步查询本月支出，再弹出日历
        new Thread(() -> {
            // 修正后（使用新增方法，只查当月）
            String monthKey = new SimpleDateFormat("yyyy-MM", Locale.getDefault())
                    .format(Calendar.getInstance().getTime());
            List<Expense> monthExpenses = db.expenseDao().getAllExpensesByMonthSync(monthKey);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                CalendarDialogFragment dialog =
                        CalendarDialogFragment.newInstance(new ArrayList<>(monthExpenses));
                dialog.show(getChildFragmentManager(), "CalendarDialog");
            });
        }).start();
    }
    private android.graphics.drawable.LayerDrawable createProgressBarDrawable(String hexActiveColor, float percentage) {
        GradientDrawable track = new GradientDrawable();
        track.setShape(GradientDrawable.RECTANGLE);
        track.setCornerRadius(dp2px(4));
        track.setColor(Color.parseColor(TRACK_COLOR));

        GradientDrawable progress = new GradientDrawable();
        progress.setShape(GradientDrawable.RECTANGLE);
        progress.setCornerRadius(dp2px(4));
        progress.setColor(Color.parseColor(hexActiveColor));

        android.graphics.drawable.ClipDrawable clipProgress = new android.graphics.drawable.ClipDrawable(
                progress, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        clipProgress.setLevel((int) (percentage * 10000));

        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{track, clipProgress});
    }

    private void mapIconResource(String category, ImageView iv) {
        if (category == null) category = "";
        if (category.contains("餐饮") || category.toLowerCase().contains("food")) {
            iv.setImageResource(R.mipmap.ic_food);
        } else if (category.contains("交通") || category.toLowerCase().contains("transport")) {
            iv.setImageResource(R.mipmap.ic_transport);
        } else if (category.contains("购物") || category.toLowerCase().contains("shop")) {
            iv.setImageResource(R.mipmap.ic_shopping);
        } else {
            iv.setImageResource(R.mipmap.ic_food);
        }
    }

    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
