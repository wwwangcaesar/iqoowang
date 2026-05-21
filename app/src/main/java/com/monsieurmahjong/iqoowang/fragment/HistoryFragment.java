package com.monsieurmahjong.iqoowang.fragment;

import android.graphics.Color;
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
import androidx.constraintlayout.widget.ConstraintLayout;

import com.google.android.material.card.MaterialCardView;
import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;
import com.monsieurmahjong.iqoowang.utils.AnimationUtils;
import com.monsieurmahjong.iqoowang.view.CircularProgressView;
import com.monsieurmahjong.iqoowang.view.LinearProgressView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class HistoryFragment extends Fragment {

    // 预算看板组件
    private CircularProgressView circularProgress;
    private TextView tvCircularText;
    private TextView tvMonthSpent;
    private TextView tvMonthLeft;

    // 今日支出组件
    private TextView tvTodaySpending;
    private LinearProgressView linearProgress;

    // 动态列表容器
    private LinearLayout transactionListContainer;

    private AppDatabase db;
    private final long TOTAL_MONTH_BUDGET_CENTS = 1200000L; // 本月预算 12,000.00 元
    private final long DAILY_BUDGET_LIMIT_CENTS = 50000L;    // 每日建议预算上限 500.00 元

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_history, container, false);

        // 1. 安全初始化：直接使用 XML 里刚刚添加的 ID，杜绝 ClassCastException 崩溃！
        circularProgress = view.findViewById(R.id.circularProgress);
        tvCircularText = view.findViewById(R.id.tv_circular_percent);
        tvMonthSpent = view.findViewById(R.id.tv_month_spent);
        tvMonthLeft = view.findViewById(R.id.tv_month_left);

        // 初始化今日支出控件
        ConstraintLayout todaySpendingLayout = view.findViewById(R.id.today_spending);
        tvTodaySpending = (TextView) todaySpendingLayout.getChildAt(1);
        linearProgress = view.findViewById(R.id.linearProgress);

        // 初始化流水列表容器
        transactionListContainer = view.findViewById(R.id.transaction_list);

        db = AppDatabase.getDatabase(requireContext());

        buildReactiveDataPipelines();

        return view;
    }

    private void buildReactiveDataPipelines() {
        String todayStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        long startOfMonth = cal.getTimeInMillis();

        cal.add(Calendar.MONTH, 1);
        long endOfMonth = cal.getTimeInMillis() - 1;

        // 获取 SharedPreferences 实例（预留给设置页面）
        android.content.SharedPreferences sp = requireContext().getSharedPreferences("AppConfig", android.content.Context.MODE_PRIVATE);

        // ==========================================
        // 管道一：本月预算与环形图联动
        // ==========================================
        db.expenseDao().getTotalByTimeRange(startOfMonth, endOfMonth).observe(getViewLifecycleOwner(), monthCents -> {
            long totalMonthSpentCents = monthCents != null ? monthCents : 0L;

            // 每次数据刷新时动态读取SP，确保设置页面修改后能实时生效
            long dynamicMonthBudget = sp.getLong("month_budget_cents", 1200000L); // 默认 12000.00 元

            int percent = (int) ((totalMonthSpentCents * 100) / dynamicMonthBudget);
            if (percent > 100) percent = 100;

            circularProgress.setProgress(percent);
            if (tvCircularText != null) {
                tvCircularText.setText(String.format(Locale.getDefault(), "%d%%", percent));
            }

            if (tvMonthSpent != null) {
                tvMonthSpent.setText(String.format(Locale.getDefault(), "¥ %.2f", totalMonthSpentCents / 100.0));
            }

            long remainingCents = dynamicMonthBudget - totalMonthSpentCents;
            if (remainingCents < 0) remainingCents = 0;
            if (tvMonthLeft != null) {
                tvMonthLeft.setText(String.format(Locale.getDefault(), "¥ %.2f", remainingCents / 100.0));
            }
        });

        // ==========================================
        // 管道二：今日支出与动效
        // ==========================================
        db.expenseDao().getDailyTotal(todayStr).observe(getViewLifecycleOwner(), totalCents -> {
            long total = totalCents != null ? totalCents : 0L;
            AnimationUtils.animateAmount(tvTodaySpending, total);

            // 动态读取日预算 SP
            long dynamicDailyBudget = sp.getLong("daily_budget_cents", 50000L); // 默认 500.00 元

            int dailyPercent = (int) ((total * 100) / dynamicDailyBudget);
            if (dailyPercent > 100) dailyPercent = 100;
            linearProgress.setProgress(dailyPercent);

            if (total > dynamicDailyBudget) {
                linearProgress.setProgressColor(Color.parseColor("#BA1A1A"));
            } else {
                linearProgress.setProgressColor(Color.parseColor("#2B6954"));
            }
        });

        // ==========================================
        // 管道三：最近交易明细列表动态渲染
        // ==========================================
        db.expenseDao().getDailyExpenses(todayStr).observe(getViewLifecycleOwner(), list -> {
            transactionListContainer.removeAllViews();

            if (list == null || list.isEmpty()) {
                renderEmptyView();
                return;
            }

            for (Expense expense : list) {
                MaterialCardView cardView = new MaterialCardView(requireContext());
                cardView.setCardBackgroundColor(Color.parseColor("#FFFFFF"));
                cardView.setRadius(dp2px(16));
                cardView.setCardElevation(0);

                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp2px(64));
                cardParams.bottomMargin = dp2px(8);
                cardView.setLayoutParams(cardParams);

                androidx.constraintlayout.widget.ConstraintLayout itemContent = new androidx.constraintlayout.widget.ConstraintLayout(requireContext());
                itemContent.setPadding(dp2px(16), 0, dp2px(16), 0);
                cardView.addView(itemContent);

                ImageView ivIcon = new ImageView(requireContext());
                ivIcon.setId(View.generateViewId());
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams iconParams = new androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(dp2px(48), dp2px(48));
                iconParams.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
                iconParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
                iconParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
                ivIcon.setLayoutParams(iconParams);
                ivIcon.setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12));

                mapIconResource(expense.getCategoryName(), ivIcon);
                itemContent.addView(ivIcon);

                TextView tvAmount = new TextView(requireContext());
                tvAmount.setId(View.generateViewId());
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams amountParams = new androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                amountParams.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
                amountParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
                amountParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
                tvAmount.setLayoutParams(amountParams);

                // 【修复】：使用你现有的 getAmount() 方法
                tvAmount.setText(String.format(Locale.getDefault(), "-¥ %.2f", expense.getAmount() / 100.0));
                tvAmount.setTextColor(Color.parseColor("#BA1A1A"));
                tvAmount.setTextSize(16);
                itemContent.addView(tvAmount);

                LinearLayout textGroup = new LinearLayout(requireContext());
                textGroup.setOrientation(LinearLayout.VERTICAL);
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams groupParams = new androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT);
                groupParams.startToEnd = ivIcon.getId();
                groupParams.endToStart = tvAmount.getId();
                groupParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
                groupParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
                groupParams.leftMargin = dp2px(12);
                textGroup.setLayoutParams(groupParams);

                TextView tvTitle = new TextView(requireContext());
                tvTitle.setText(expense.getCategoryName());
                tvTitle.setTextColor(Color.parseColor("#111C2D"));
                tvTitle.setTextSize(15);

                TextView tvSubtitle = new TextView(requireContext());
                String amPmTimeStr = new SimpleDateFormat("a hh:mm", Locale.CHINA).format(new Date(expense.getTimestamp()));

                // 【修复】：使用你现有的 getSource() 方法作为元数据展示
                String sourceMeta = expense.getSource() != null && !expense.getSource().isEmpty() ? expense.getSource() : "智能记账";
                String meta = amPmTimeStr + " · " + sourceMeta;

                tvSubtitle.setText(meta);
                tvSubtitle.setTextColor(Color.parseColor("#404944"));
                tvSubtitle.setTextSize(12);
                tvSubtitle.setPadding(0, dp2px(2), 0, 0);

                textGroup.addView(tvTitle);
                textGroup.addView(tvSubtitle);
                itemContent.addView(textGroup);

                transactionListContainer.addView(cardView);
            }
        });
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

    private void renderEmptyView() {
        TextView emptyTv = new TextView(getContext());
        emptyTv.setText("今日暂无记账流水\n点击下方 '+' 记录第一笔消费吧");
        emptyTv.setGravity(Gravity.CENTER);
        emptyTv.setLineSpacing(4, 1);
        emptyTv.setPadding(0, dp2px(40), 0, dp2px(40));
        emptyTv.setTextColor(Color.parseColor("#404944"));
        transactionListContainer.addView(emptyTv);
    }

    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
