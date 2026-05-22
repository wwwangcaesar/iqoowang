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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StatisticsFragment extends Fragment {

    private SegmentedControlView segmentedTab;
    private SmoothLineChartView smoothLineChart;
    private PercentagePieChartView percentagePieChart;

    // 动态绑定的 UI 控件
    private TextView tvTotalAmount;
    private TextView tvTrendPercent;
    private ImageView ivTrendArrow;
    private LinearLayout containerCategoryList;
    private TextView tvSmartTip;
    private TextView tvDailyAvg;
    private TextView tvActiveDays;

    private AppDatabase db;

    // 高级莫兰迪品牌色盘 (对应你饼图的切片颜色)
    private final String[] CHART_HEX_COLORS = {"#003527", "#505F76", "#B7C8E1", "#E7EEFF"};
    // 进度条背景色
    private final String TRACK_COLOR = "#F0F3FF";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_statistics, container, false);

        // 1. 初始化所有核心组件
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

        db = AppDatabase.getDatabase(requireContext());

        setupChartInteractions();
        return view;
    }
    private int currentTab = 0;
    private void setupChartInteractions() {
        segmentedTab.setOnTabSelectedListener((index, text) -> {
            currentTab = index;
            loadAnalyticsData(index);
        });
        loadAnalyticsData(0);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 瞬间重加载最新账本分析
        loadAnalyticsData(currentTab);
    }
    /**
     * 核心数据分析引擎 (运行在子线程防卡顿)
     */
    private void loadAnalyticsData(int periodIndex) {
        new Thread(() -> {
            long currentStart, currentEnd;
            long prevStart, prevEnd; // 用于计算环比

            Calendar cal = Calendar.getInstance();
            currentEnd = cal.getTimeInMillis();

            int pointCount;
            String[] xAxisLabels;
            int daysInPeriod;
            String periodName;

            // =====================================
            // 1. 时间切片算法
            // =====================================
            if (periodIndex == 0) {
                // 【本周】：最近 7 天
                daysInPeriod = 7;
                periodName = "本周";
                cal.add(Calendar.DAY_OF_YEAR, -6);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                currentStart = cal.getTimeInMillis();

                prevEnd = currentStart - 1;
                prevStart = prevEnd - (7L * 24 * 60 * 60 * 1000) + 1;

                pointCount = 7;
                xAxisLabels = new String[7];
                Calendar labelCal = Calendar.getInstance();
                labelCal.setTimeInMillis(currentStart);
                SimpleDateFormat sdf = new SimpleDateFormat("MM/dd", Locale.getDefault());
                for(int i=0; i<7; i++) {
                    xAxisLabels[i] = sdf.format(labelCal.getTime());
                    labelCal.add(Calendar.DAY_OF_YEAR, 1);
                }
            } else if (periodIndex == 1) {
                // 【本月】：自然月
                periodName = "本月";
                cal.set(Calendar.DAY_OF_MONTH, 1);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                currentStart = cal.getTimeInMillis();
                daysInPeriod = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

                cal.add(Calendar.MILLISECOND, -1);
                prevEnd = cal.getTimeInMillis();
                cal.set(Calendar.DAY_OF_MONTH, 1);
                prevStart = cal.getTimeInMillis();

                pointCount = 5;
                xAxisLabels = new String[]{"第1周", "第2周", "第3周", "第4周", "第5周"};
            } else {
                // 【本年】：自然年
                periodName = "今年";
                cal.set(Calendar.DAY_OF_YEAR, 1);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                currentStart = cal.getTimeInMillis();
                daysInPeriod = cal.getActualMaximum(Calendar.DAY_OF_YEAR);

                cal.add(Calendar.MILLISECOND, -1);
                prevEnd = cal.getTimeInMillis();
                cal.set(Calendar.DAY_OF_YEAR, 1);
                prevStart = cal.getTimeInMillis();

                pointCount = 12;
                xAxisLabels = new String[]{"1月", "3月", "6月", "9月", "12月"};
            }

            // =====================================
            // 2. 数据库拉取聚合
            // =====================================
            List<Expense> currentExpenses = db.expenseDao().getExpensesInRangeSync(currentStart, currentEnd);
            List<Expense> prevExpenses = db.expenseDao().getExpensesInRangeSync(prevStart, prevEnd);

            long currentTotalCents = 0;
            Map<String, Long> categoryTotals = new HashMap<>();
            float[] trendData = new float[pointCount];
            long intervalStep = (currentEnd - currentStart) / pointCount;

            // 循环拆解当前周期数据
            for (Expense e : currentExpenses) {
                currentTotalCents += e.getAmount();

                // 饼图/列表 分类聚合
                long currentCat = categoryTotals.containsKey(e.getCategoryName()) ? categoryTotals.get(e.getCategoryName()) : 0L;
                categoryTotals.put(e.getCategoryName(), currentCat + e.getAmount());

                // 折线图分桶计算
                int bucketIndex = (int) ((e.getTimestamp() - currentStart) / intervalStep);
                if (bucketIndex >= pointCount) bucketIndex = pointCount - 1;
                trendData[bucketIndex] += e.getAmount();
            }

            long prevTotalCents = 0;
            Map<String, Long> prevCategoryTotals = new HashMap<>();
            for (Expense e : prevExpenses) {
                prevTotalCents += e.getAmount();
                long prevCat = prevCategoryTotals.containsKey(e.getCategoryName()) ? prevCategoryTotals.get(e.getCategoryName()) : 0L;
                prevCategoryTotals.put(e.getCategoryName(), prevCat + e.getAmount());
            }

            // =====================================
            // 3. 算法处理 (环比、归一化、排序)
            // =====================================
            // 环比增长率
            String trendText = "0%";
            boolean isTrendUp = false;
            if (prevTotalCents > 0) {
                long diff = currentTotalCents - prevTotalCents;
                int pct = (int) ((diff * 100) / prevTotalCents);
                isTrendUp = pct > 0;
                trendText = (isTrendUp ? "+" : "") + pct + "%";
            }

            // 归一化折线图 (0.0f - 1.0f)
            float maxBucket = 0;
            for (float val : trendData) if (val > maxBucket) maxBucket = val;
            float[] normalizedTrend = new float[pointCount];
            if (maxBucket > 0) {
                for (int i = 0; i < pointCount; i++) normalizedTrend[i] = trendData[i] / maxBucket;
            } else {
                for (int i = 0; i < pointCount; i++) normalizedTrend[i] = 0.05f;
            }

            // 排序分类找出 Top
            List<Map.Entry<String, Long>> sortedCategories = new ArrayList<>(categoryTotals.entrySet());
            Collections.sort(sortedCategories, (o1, o2) -> o2.getValue().compareTo(o1.getValue()));

            // 智能提示文本生成 (找消费最高的类别比对)
            String smartTipText;
            if (!sortedCategories.isEmpty()) {
                String topCategory = sortedCategories.get(0).getKey();
                long currentTopAmt = sortedCategories.get(0).getValue();
                long prevTopAmt = prevCategoryTotals.containsKey(topCategory) ? prevCategoryTotals.get(topCategory) : 0L;

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

            // 算出每日平均
            long dailyAvgCents = currentTotalCents / daysInPeriod;

            // =====================================
            // 4. UI 渲染 (切回主线程)
            // =====================================
            final long finalCurrentTotalCents = currentTotalCents;
            final String finalTrendText = trendText;
            final boolean finalIsTrendUp = isTrendUp;
            requireActivity().runOnUiThread(() -> {
                // A. 更新总支出与数字滚动动画
                AnimationUtils.animateAmount(tvTotalAmount, finalCurrentTotalCents);
                tvTrendPercent.setText(finalTrendText);
                if (finalIsTrendUp) {
                    tvTrendPercent.setTextColor(Color.parseColor("#BA1A1A")); // 支出增加标红
                    ivTrendArrow.setImageResource(R.drawable.ic_trending_up); // 请确保您有这个图标
                } else {
                    tvTrendPercent.setTextColor(Color.parseColor("#2B6954")); // 支出减少标绿 (surface-tint)
                    ivTrendArrow.setImageResource(R.drawable.ic_down); // 对应向下图标
                }

                // B. 更新折线图
                smoothLineChart.setDynamicLabels(periodIndex == 0 ? new String[]{xAxisLabels[0], xAxisLabels[3], xAxisLabels[6]} : xAxisLabels);
                smoothLineChart.setData(normalizedTrend);

                // C. 更新右侧信息小卡片
                tvDailyAvg.setText(String.format(Locale.getDefault(), "¥ %.2f", dailyAvgCents / 100.0));
                tvActiveDays.setText(currentExpenses.size() + " 笔记录");

                // D. 更新底部明智提示
                tvSmartTip.setText(smartTipText);

                // E. 动态渲染饼图与精美分类列表
                renderCategoryBreakdown(sortedCategories, finalCurrentTotalCents);
            });

        }).start();
    }

    /**
     * 动态生成带有独立颜色 Bar 的类别列表
     */
    private void renderCategoryBreakdown(List<Map.Entry<String, Long>> sortedCategories, long totalCents) {
        containerCategoryList.removeAllViews();
        List<PercentagePieChartView.PieEntry> pieEntries = new ArrayList<>();

        if (totalCents <= 0 || sortedCategories.isEmpty()) {
            pieEntries.add(new PercentagePieChartView.PieEntry(1.0f, Color.parseColor(CHART_HEX_COLORS[3])));
            percentagePieChart.setEntries(pieEntries);
            return;
        }

        long top3Total = 0;
        int listLimit = Math.min(sortedCategories.size(), 4); // 列表最多展示4项

        for (int i = 0; i < listLimit; i++) {
            Map.Entry<String, Long> entry = sortedCategories.get(i);
            long amountCents = entry.getValue();
            float percentage = (float) amountCents / totalCents;

            // 饼图数据收集 (最多处理前3+其他)
            String hexColor = CHART_HEX_COLORS[Math.min(i, 3)];
            if (i < 3) {
                pieEntries.add(new PercentagePieChartView.PieEntry(percentage, Color.parseColor(hexColor)));
                top3Total += amountCents;
            } else if (i == 3) {
                // 第四项代表所有“其他”
                float otherPercent = (float) (totalCents - top3Total) / totalCents;
                pieEntries.add(new PercentagePieChartView.PieEntry(otherPercent, Color.parseColor(hexColor)));
            }

            // === 动态构建完美还原设计的列表项 ===
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(64));
            if (i > 0) rowParams.topMargin = dp2px(8);
            row.setLayoutParams(rowParams);

            // 1. 图标
            ImageView iv = new ImageView(requireContext());
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp2px(48), dp2px(48)));
            iv.setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12));
            mapIconResource(entry.getKey(), iv);
            row.addView(iv);

            // 2. 中间：类别名 + 水平进度条
            LinearLayout middleGroup = new LinearLayout(requireContext());
            middleGroup.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams middleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            middleParams.leftMargin = dp2px(8);
            middleGroup.setLayoutParams(middleParams);

            TextView tvName = new TextView(requireContext());
            tvName.setText(entry.getKey());
            tvName.setTextColor(Color.parseColor("#111C2D"));
            tvName.setTextSize(16);

            // 绘制横向圆角进度条
            View barContainer = new View(requireContext());
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(8));
            barParams.topMargin = dp2px(8);
            barContainer.setLayoutParams(barParams);
            barContainer.setBackground(createProgressBarDrawable(hexColor, percentage));

            middleGroup.addView(tvName);
            middleGroup.addView(barContainer);
            row.addView(middleGroup);

            // 3. 右侧：百分比 + 金额
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

            containerCategoryList.addView(row);
        }

        // 推送给饼图重新绘制和动画
        percentagePieChart.setEntries(pieEntries);
    }

    /**
     * 绘制双层叠加的水平进度条（左侧实体色，右侧底色）
     */
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
        clipProgress.setLevel((int) (percentage * 10000)); // Level 是 0-10000

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
