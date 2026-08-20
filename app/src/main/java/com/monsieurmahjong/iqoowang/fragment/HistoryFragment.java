package com.monsieurmahjong.iqoowang.fragment;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
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

import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.monsieurmahjong.iqoowang.HistoryGalleryActivity;
import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;
import com.monsieurmahjong.iqoowang.pet.PetActivity;
import com.monsieurmahjong.iqoowang.piggy.PiggyBankActivity;
import com.monsieurmahjong.iqoowang.search.SearchActivity;
import com.monsieurmahjong.iqoowang.streak.StreakActivity;
import com.monsieurmahjong.iqoowang.utils.AnimationUtils;
import com.monsieurmahjong.iqoowang.utils.StreakManager;
import com.monsieurmahjong.iqoowang.view.CircularProgressView;
import com.monsieurmahjong.iqoowang.view.LinearProgressView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HistoryFragment extends Fragment {

    private CircularProgressView circularProgress;
    private TextView tvCircularText;
    private TextView tvMonthSpent;
    private TextView tvMonthLeft;
    private TextView tvMonthBudgetTotal;

    private TextView tvTodaySpending;
    private LinearProgressView linearProgress;
    private LinearLayout transactionListContainer;
    private TextView tvSmartTip;

    private MaterialCardView cardBudget;
    private ValueAnimator budgetWarningAnimator;

    // 明智消费提示：缓存本次命中的全部文案，点击卡片可随机切换到另一条
    private final List<String> cachedTips = new ArrayList<>();
    private int currentTipIndex = -1;
    private final java.util.Random tipRandom = new java.util.Random();

    private AppDatabase db;
    private static final String PREFS_NAME = "SereneLedgerConfig";
    private static final String KEY_MONTHLY_BUDGET = "monthly_budget_cents";
    private final StreakManager streakManager = StreakManager.getInstance();

    private TextView tvStreakDays;
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_history, container, false);

        circularProgress = view.findViewById(R.id.circularProgress);
        tvCircularText = view.findViewById(R.id.tv_circular_percent);
        tvMonthSpent = view.findViewById(R.id.tv_month_spent);
        tvMonthLeft = view.findViewById(R.id.tv_month_left);
        tvStreakDays= view.findViewById(R.id.tv_streak_days);
        TextView btnCancel =view.findViewById(R.id.tv_more);
        if (btnCancel != null) btnCancel.setOnClickListener(v -> toAC());

        // 依靠严密的 DOM 视树向下寻解并动态锚定没有给定明确 ID 的 tvMonthBudgetTotal
        try {
            cardBudget = view.findViewById(R.id.card_budget);
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
        tvSmartTip = view.findViewById(R.id.tv_smart_tip);

        // 点击明智消费提示卡片：在本次命中的全部文案中随机切换一条（不会重新查库）
        View cardTip = view.findViewById(R.id.card_tip);
        if (cardTip != null) {
            cardTip.setOnClickListener(v -> showRandomTip());
        }

        db = AppDatabase.getDatabase(requireContext());
        ImageView iv_avatar = view.findViewById(R.id.iv_avatar);
        iv_avatar.setOnClickListener(v -> openActvity());
        ImageView ivSearch = view.findViewById(R.id.iv_search);
        if (ivSearch != null) {
            ivSearch.setOnClickListener(v -> startActivity(new Intent(requireContext(), SearchActivity.class)));
        }

        // 存钱罐入口：点击跳转到心愿储蓄罐，页面加载时播一次晃动动画提醒用户可以点击
        ImageView ivPiggyBank = view.findViewById(R.id.iv_piggy_bank);
        if (ivPiggyBank != null) {
            ivPiggyBank.setOnClickListener(v -> startActivity(new Intent(requireContext(), PiggyBankActivity.class)));
            playPiggyIconShake(ivPiggyBank);
        }
        return view;
    }

    /** 存钱罐图标晃动提醒：HistoryFragment 页面加载时播放一次，不影响任何数据 */
    private void playPiggyIconShake(View iconView) {
        iconView.animate().cancel();
        iconView.setRotation(0f);
        android.animation.ObjectAnimator anim = android.animation.ObjectAnimator.ofFloat(
                iconView, "rotation", 0f, -14f, 11f, -8f, 5f, -2f, 0f);
        anim.setDuration(650);
        anim.setStartDelay(200);
        anim.start();
    }

    private void openActvity() {
        Intent intent = new Intent(getActivity(), StreakActivity.class);
        startActivity(intent);
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
        refreshStreakDisplay();
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

            // 本月已超预算：环形进度条变红 + 卡片呼吸灯光效提醒；未超预算则恢复默认样式
            applyBudgetWarning(dynamicMonthBudget > 0 && totalMonthSpentCents > dynamicMonthBudget);
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

        // ==========================================
        // 管线四：明智消费提示（规则/统计对比引擎，不依赖端侧AI，避免备注脏数据干扰）
        // ==========================================
        refreshSmartTip();
    }

    /**
     * 异步计算“明智消费提示”文案集合，替代原先写死的静态文案。
     * 完全基于金额/分类/时间等结构化字段做规则对比，不解析备注文本，
     * 避免因备注内容不规范导致误判。只读查询，不修改任何历史数据。
     */
    private void refreshSmartTip() {
        if (!isAdded() || getContext() == null || tvSmartTip == null) return;
        new Thread(() -> {
            List<String> tips = computeSmartTips();
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                cachedTips.clear();
                cachedTips.addAll(tips);
                currentTipIndex = cachedTips.isEmpty() ? -1 : tipRandom.nextInt(cachedTips.size());
                if (tvSmartTip != null && currentTipIndex >= 0) {
                    tvSmartTip.setText(cachedTips.get(currentTipIndex));
                }
            });
        }).start();
    }

    /** 点击提示卡片：从本次命中的全部文案中随机换一条（只要有不同选项就不重复上一条），带个小淡入淡出提示已切换 */
    private void showRandomTip() {
        if (tvSmartTip == null || cachedTips.isEmpty()) return;
        if (cachedTips.size() == 1) {
            currentTipIndex = 0;
        } else {
            int next;
            do {
                next = tipRandom.nextInt(cachedTips.size());
            } while (next == currentTipIndex);
            currentTipIndex = next;
        }

        tvSmartTip.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            tvSmartTip.setText(cachedTips.get(currentTipIndex));
            tvSmartTip.animate().alpha(1f).setDuration(180).start();
        }).start();
    }

    /**
     * 计算当前周期内所有命中的提示文案（不再只取第一条），供点击卡片时随机切换。
     * 若一条都没命中，返回包含典底文案的列表。
     */
    private List<String> computeSmartTips() {
        List<String> tips = new ArrayList<>();

        Calendar cal = Calendar.getInstance();
        int todayDay = cal.get(Calendar.DAY_OF_MONTH);
        int daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long startOfMonth = cal.getTimeInMillis();

        cal.add(Calendar.MONTH, 1);
        long endOfMonth = cal.getTimeInMillis() - 1;

        cal.setTimeInMillis(startOfMonth);
        cal.add(Calendar.MILLISECOND, -1);
        long prevMonthEnd = cal.getTimeInMillis();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        long prevMonthStart = cal.getTimeInMillis();

        List<Expense> currentMonthExpenses = db.expenseDao().getExpensesInRangeSync(startOfMonth, endOfMonth);
        List<Expense> prevMonthExpenses = db.expenseDao().getExpensesInRangeSync(prevMonthStart, prevMonthEnd);
        if (currentMonthExpenses == null) currentMonthExpenses = new ArrayList<>();
        if (prevMonthExpenses == null) prevMonthExpenses = new ArrayList<>();

        // 数据量太少，任何对比都没有统计意义，直接返回单条兵底文案
        if (currentMonthExpenses.size() < 3) {
            tips.add("本月记账刚刚开始，多记几笔之后就能看到更准确的消费分析啦～");
            return tips;
        }

        long totalMonthCents = 0;
        Map<String, Long> categoryTotals = new HashMap<>();
        String todayStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        long todayCents = 0;
        long weekendCents = 0;

        Calendar ec = Calendar.getInstance();
        for (Expense e : currentMonthExpenses) {
            long amount = e.getAmount();
            totalMonthCents += amount;

            String cat = e.getCategoryName() != null ? e.getCategoryName() : "其他";
            categoryTotals.put(cat, categoryTotals.getOrDefault(cat, 0L) + amount);

            if (todayStr.equals(e.getDate_str())) {
                todayCents += amount;
            }

            ec.setTimeInMillis(e.getTimestamp());
            int dow = ec.get(Calendar.DAY_OF_WEEK);
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
                weekendCents += amount;
            }
        }

        Map<String, Long> prevCategoryTotals = new HashMap<>();
        for (Expense e : prevMonthExpenses) {
            String cat = e.getCategoryName() != null ? e.getCategoryName() : "其他";
            prevCategoryTotals.put(cat, prevCategoryTotals.getOrDefault(cat, 0L) + e.getAmount());
        }

        android.content.SharedPreferences sp = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long monthlyBudget = sp.getLong(KEY_MONTHLY_BUDGET, 500000L);

        // 规则1：本月已超预算
        if (monthlyBudget > 0 && totalMonthCents > monthlyBudget) {
            long overCents = totalMonthCents - monthlyBudget;
            int remainDays = Math.max(daysInMonth - todayDay, 0);
            tips.add(String.format(Locale.getDefault(), "本月已超出预算 ¥%.2f，接下来 %d 天建议控制一下非必要支出。", overCents / 100.0, remainDays));
        }

        // 规则2/3：消费节奏 vs 时间进度对比（提前预警 / 表扬结余）
        if (monthlyBudget > 0 && todayDay > 0) {
            double timeRatio = (double) todayDay / daysInMonth;
            double spendRatio = (double) totalMonthCents / monthlyBudget;
            if (spendRatio - timeRatio > 0.15) {
                long projectedCents = (long) (totalMonthCents / timeRatio);
                tips.add(String.format(Locale.getDefault(), "按当前节奏，本月预计总支出约 ¥%.0f，可能会超出预算，建议适当控制。", projectedCents / 100.0));
            } else if (timeRatio - spendRatio > 0.15 && totalMonthCents > 0) {
                long remainCents = monthlyBudget - totalMonthCents;
                tips.add(String.format(Locale.getDefault(), "本月消费节奏低于预算进度，预计能结余 ¥%.0f 左右，继续保持！", remainCents / 100.0));
            }
        }

        // 规则4：主要分类环比变化明显（增幅或降幅 ≥ 20%）
        List<Map.Entry<String, Long>> sortedCategories = new ArrayList<>(categoryTotals.entrySet());
        Collections.sort(sortedCategories, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        if (!sortedCategories.isEmpty()) {
            String topCategory = sortedCategories.get(0).getKey();
            long topAmount = sortedCategories.get(0).getValue();
            long prevAmount = prevCategoryTotals.getOrDefault(topCategory, 0L);
            if (prevAmount > 0) {
                long diff = topAmount - prevAmount;
                int pct = (int) Math.abs((diff * 100) / prevAmount);
                if (pct >= 20) {
                    if (diff > 0) {
                        tips.add(String.format(Locale.getDefault(), "「%s」支出比上月增加了 %d%%，是本月涨幅最明显的分类。", topCategory, pct));
                    } else {
                        tips.add(String.format(Locale.getDefault(), "「%s」支出比上月降低了 %d%%，继续保持！", topCategory, pct));
                    }
                }
            }
        }

        // 规则5：今日消费明显高于本月日均
        if (todayDay > 0) {
            long dailyAvgCents = totalMonthCents / todayDay;
            if (dailyAvgCents > 0 && todayCents > dailyAvgCents * 2) {
                tips.add(String.format(Locale.getDefault(), "今天已消费 ¥%.2f，明显高于本月日均 ¥%.2f，留意一下是否有临时大额支出。", todayCents / 100.0, dailyAvgCents / 100.0));
            }
        }

        // 规则6：周末消费占比偏高
        if (totalMonthCents > 0) {
            double weekendShare = (double) weekendCents / totalMonthCents;
            if (weekendShare > 0.45) {
                tips.add("本月接近一半的支出集中在周末，安排聚餐、娱乐时可以适当留意一下预算。");
            }
        }

        if (tips.isEmpty()) {
            tips.add("本月消费记录规律，继续保持良好的记账习惯！");
        }
        return tips;
    }
        // 兜底文案
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
    /**
     * 本月超预算警示效果：环形进度条变红，外层预算卡片呼吸灯光效闪烁提醒
     * 只影响 UI 表现，不会修改任何已记录的数据
     */
    private void applyBudgetWarning(boolean isOverBudget) {
        if (!isAdded() || getContext() == null) return;
        if (isOverBudget) {
            if (circularProgress != null) {
                circularProgress.setProgressColor(Color.parseColor("#BA1A1A"));
            }
            if (tvCircularText != null) {
                tvCircularText.setTextColor(Color.parseColor("#BA1A1A"));
            }
            startBudgetBreathingWarning();
        } else {
            if (circularProgress != null) {
                circularProgress.setProgressColor(ContextCompat.getColor(requireContext(), R.color.primary_container));
            }
            if (tvCircularText != null) {
                tvCircularText.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary));
            }
            stopBudgetBreathingWarning();
        }
    }

    private void startBudgetBreathingWarning() {
        if (cardBudget == null) return;
        if (budgetWarningAnimator != null && budgetWarningAnimator.isRunning()) return;

        cardBudget.setStrokeWidth(dp2px(2));
        budgetWarningAnimator = ValueAnimator.ofInt(40, 220);
        budgetWarningAnimator.setDuration(900);
        budgetWarningAnimator.setRepeatMode(ValueAnimator.REVERSE);
        budgetWarningAnimator.setRepeatCount(ValueAnimator.INFINITE);
        budgetWarningAnimator.addUpdateListener(animation -> {
            if (cardBudget == null) return;
            int alpha = (int) animation.getAnimatedValue();
            cardBudget.setStrokeColor(Color.argb(alpha, 0xBA, 0x1A, 0x1A));
        });
        budgetWarningAnimator.start();
    }

    private void stopBudgetBreathingWarning() {
        if (budgetWarningAnimator != null) {
            budgetWarningAnimator.cancel();
            budgetWarningAnimator = null;
        }
        if (cardBudget != null) {
            cardBudget.setStrokeWidth(0);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopBudgetBreathingWarning();
    }
    private void refreshStreakDisplay() {
        if (tvStreakDays == null || getContext() == null) return;
        int streak = streakManager.getCurrentStreak(requireContext());
        boolean checkedToday = streakManager.isCheckedToday(requireContext());
        if (streak <= 0) {
            tvStreakDays.setText("还没有打卡记录，点击点亮第一天的火苗");
        } else if (checkedToday) {
            tvStreakDays.setText(String.format(Locale.getDefault(), "连续 %d 天，今天已经点亮啦", streak));
        } else {
            tvStreakDays.setText(String.format(Locale.getDefault(), "连续 %d 天，点击点亮今天的火苗", streak));
        }
    }

    private void toAC(){
        Intent intent = new Intent(requireContext(), HistoryGalleryActivity.class);
        startActivity(intent);
    }
    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
