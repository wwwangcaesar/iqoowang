package com.monsieurmahjong.iqoowang.fragment;

import android.content.Context;
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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryFragment extends Fragment {

    private CircularProgressView circularProgress;
    private TextView tvCircularText;
    private TextView tvMonthSpent;
    private TextView tvMonthLeft;
    private TextView tvMonthBudgetTotal;

    private TextView tvTodaySpending;
    private LinearProgressView linearProgress;
    private LinearLayout transactionListContainer;

    private AppDatabase db;
    private static final String PREFS_NAME = "SereneLedgerConfig";
    private static final String KEY_MONTHLY_BUDGET = "monthly_budget_cents";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_history, container, false);

        circularProgress = view.findViewById(R.id.circularProgress);
        tvCircularText = view.findViewById(R.id.tv_circular_percent);
        tvMonthSpent = view.findViewById(R.id.tv_month_spent);
        tvMonthLeft = view.findViewById(R.id.tv_month_left);

        // 依靠严密的 DOM 视树向下寻解并动态锚定没有给定明确 ID 的 tvMonthBudgetTotal
        try {
            MaterialCardView cardBudget = view.findViewById(R.id.card_budget);
            LinearLayout rootLayout = (LinearLayout) cardBudget.getChildAt(0);
            ConstraintLayout innerConstraint = (ConstraintLayout) rootLayout.getChildAt(0);
            LinearLayout textContainer = (LinearLayout) innerConstraint.getChildAt(0);
            tvMonthBudgetTotal = (TextView) textContainer.getChildAt(1);
        } catch (Exception e) {
            e.printStackTrace();
        }

        ConstraintLayout todaySpendingLayout = view.findViewById(R.id.today_spending);
        tvTodaySpending = (TextView) todaySpendingLayout.getChildAt(1);
        linearProgress = view.findViewById(R.id.linearProgress);
        transactionListContainer = view.findViewById(R.id.transaction_list);

        db = AppDatabase.getDatabase(requireContext());
        ImageView tvCalendarMonth = view.findViewById(R.id.iv_calendar);
        tvCalendarMonth.setOnClickListener(v -> openCalendarDialog());
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 挂载响应式观察者链路
        buildReactiveDataPipelines();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 关键打通：每次用户切换切回本 Fragment 瞬间强制迫使观察者管线重绘刷新，解决不退出不刷新痛点！
        buildReactiveDataPipelines();
    }

    private void buildReactiveDataPipelines() {
        if (!isAdded() || getContext() == null) return;

        String todayStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        long startOfMonth = cal.getTimeInMillis();

        cal.add(Calendar.MONTH, 1);
        long endOfMonth = cal.getTimeInMillis() - 1;

        android.content.SharedPreferences sp = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // ==========================================
        // 管线一：本月预算、已消费、剩余金额动画全量滚动
        // ==========================================
        db.expenseDao().getTotalByTimeRange(startOfMonth, endOfMonth).observe(getViewLifecycleOwner(), monthCents -> {
            long totalMonthSpentCents = monthCents != null ? monthCents : 0L;

            // 完美对齐设置页面同步输出
            long dynamicMonthBudget = sp.getLong(KEY_MONTHLY_BUDGET, 500000L); // 默认5000元

            int percent = (int) (dynamicMonthBudget <= 0 ? 0 : ((totalMonthSpentCents * 100) / dynamicMonthBudget));
            if (percent > 100) percent = 100;

            if (circularProgress != null) {
                circularProgress.setProgress(percent);
            }
            if (tvCircularText != null) {
                tvCircularText.setText(String.format(Locale.getDefault(), "%d%%", percent));
            }

            // 核心动画功能落地：全部交给高阶数值平滑动画
            if (tvMonthBudgetTotal != null) {
                AnimationUtils.animateAmount(tvMonthBudgetTotal, dynamicMonthBudget);
            }
            if (tvMonthSpent != null) {
                AnimationUtils.animateAmount(tvMonthSpent, totalMonthSpentCents);
            }

            long remainingCents = dynamicMonthBudget - totalMonthSpentCents;
            if (remainingCents < 0) remainingCents = 0;
            if (tvMonthLeft != null) {
                AnimationUtils.animateAmount(tvMonthLeft, remainingCents);
            }
        });

        // ==========================================
        // 管线二：今日支出监控与安全色阶提醒
        // ==========================================
        db.expenseDao().getDailyTotal(todayStr).observe(getViewLifecycleOwner(), totalCents -> {
            long total = totalCents != null ? totalCents : 0L;
            AnimationUtils.animateAmount(tvTodaySpending, total);

            long dynamicDailyBudget = 50000L; // 预设 500.00 元建议上线
            int dailyPercent = (int) ((total * 100) / dynamicDailyBudget);
            if (dailyPercent > 100) dailyPercent = 100;

            if (linearProgress != null) {
                linearProgress.setProgress(dailyPercent);
                if (total > dynamicDailyBudget) {
                    linearProgress.setProgressColor(Color.parseColor("#BA1A1A"));
                } else {
                    linearProgress.setProgressColor(Color.parseColor("#003527"));
                }
            }
        });

        // ==========================================
        // 管线三：动态记账流水列表增量渲染
        // ==========================================
        db.expenseDao().getDailyExpenses(todayStr).observe(getViewLifecycleOwner(), list -> {
            if (transactionListContainer == null) return;
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

                ConstraintLayout itemContent = new ConstraintLayout(requireContext());
                itemContent.setPadding(dp2px(16), 0, dp2px(16), 0);
                cardView.addView(itemContent);

                ImageView ivIcon = new ImageView(requireContext());
                ivIcon.setId(View.generateViewId());
                ConstraintLayout.LayoutParams iconParams = new ConstraintLayout.LayoutParams(dp2px(48), dp2px(48));
                iconParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
                iconParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
                iconParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                ivIcon.setLayoutParams(iconParams);
                ivIcon.setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12));
                mapIconResource(expense.getCategoryName(), ivIcon);
                itemContent.addView(ivIcon);

                TextView tvCategory = new TextView(requireContext());
                tvCategory.setId(View.generateViewId());
                tvCategory.setText(expense.getCategoryName());
                tvCategory.setTextColor(Color.parseColor("#111C2D"));
                tvCategory.setTextSize(16);
                ConstraintLayout.LayoutParams catParams = new ConstraintLayout.LayoutParams(
                        ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT);
                catParams.startToEnd = ivIcon.getId();
                catParams.topToTop = ivIcon.getId();
                catParams.topMargin = dp2px(4); // 微调与图标顶部的对齐
                tvCategory.setLayoutParams(catParams);
                itemContent.addView(tvCategory);

                // ========== 核心修改：tvSubtitle位置调整 ==========
                TextView tvSubtitle = new TextView(requireContext());
                tvSubtitle.setId(View.generateViewId()); // 必须设置id才能使用约束
                String amPmTimeStr = new SimpleDateFormat("a hh:mm", Locale.CHINA).format(new Date(expense.getTimestamp()));

                String sourceMeta = expense.getSource() != null && !expense.getSource().isEmpty() ? expense.getSource() : "智能记账";
                String meta = amPmTimeStr + " · " + sourceMeta;
                tvSubtitle.setText(meta);
                tvSubtitle.setTextColor(Color.parseColor("#404944"));
                tvSubtitle.setTextSize(12);
                // 移除原有的paddingTop，改用margin控制间距
                // tvSubtitle.setPadding(0, dp2px(2), 0, 0);

                // 配置tvSubtitle的约束：在ivIcon右侧、tvCategory下方，上边距10dp
                ConstraintLayout.LayoutParams subtitleParams = new ConstraintLayout.LayoutParams(
                        ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT);
                subtitleParams.startToEnd = ivIcon.getId();
                subtitleParams.topToBottom = tvCategory.getId();
                subtitleParams.topMargin = dp2px(4); // 距离上面10dp
                tvSubtitle.setLayoutParams(subtitleParams);

                itemContent.addView(tvSubtitle);
                // ========== 核心修改结束 ==========

                TextView tvMoney = new TextView(requireContext());
                tvMoney.setText(String.format(Locale.getDefault(), "¥ %.2f", expense.getAmount() / 100.0));
                tvMoney.setTextColor(Color.parseColor("#003527"));
                tvMoney.setTextSize(16);
                ConstraintLayout.LayoutParams moneyParams = new ConstraintLayout.LayoutParams(
                        ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT);
                moneyParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                moneyParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
                moneyParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                tvMoney.setLayoutParams(moneyParams);
                itemContent.addView(tvMoney);

                transactionListContainer.addView(cardView);
            }
        });
    }

    private void renderEmptyView() {
        TextView emptyTv = new TextView(getContext());
        emptyTv.setText("今日暂无记账流水");
        emptyTv.setGravity(Gravity.CENTER);
        emptyTv.setPadding(0, dp2px(32), 0, dp2px(32));
        emptyTv.setTextColor(Color.parseColor("#505F76"));
        transactionListContainer.addView(emptyTv);
    }

    private void mapIconResource(String category, ImageView iv) {
        if (category == null) category = "";
        if (category.contains("餐饮") || category.toLowerCase().contains("food")) {
            iv.setImageResource(R.mipmap.ic_food);
        } else if (category.contains("交通") || category.toLowerCase().contains("transport")) {
            iv.setImageResource(R.mipmap.ic_transport);
        } else if (category.contains("购物") || category.toLowerCase().contains("shop")) {
            iv.setImageResource(R.mipmap.ic_shopping);
        } else if (category.contains("零食") || category.toLowerCase().contains("drinks")) {
            iv.setImageResource(R.mipmap.ic_drinks);
        } else if (category.contains("娱乐") || category.toLowerCase().contains("entertainment")) {
            iv.setImageResource(R.mipmap.ic_entertainment);
        } else {
            iv.setImageResource(R.mipmap.ic_other);
        }
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
    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
