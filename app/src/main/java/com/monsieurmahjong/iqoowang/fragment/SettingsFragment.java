package com.monsieurmahjong.iqoowang.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.connect.Achievement;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;
import com.monsieurmahjong.iqoowang.utils.AchievementManager;
import com.monsieurmahjong.iqoowang.utils.SpBudgetUtils;
import com.monsieurmahjong.iqoowang.view.CoolBudgetSeekBar;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SettingsFragment extends Fragment {

    private TextView tvBudgetValue;
    private TextView tvDailyLimit;
    private CoolBudgetSeekBar budgetSlider;
    private LinearLayout llTicks;

    private TextView tvCheckInCount;
    private ProgressBar checkInProgress;

    private LinearLayout llAchievementSection;

    private SharedPreferences sharedPreferences;
    private AppDatabase db;
    private SpBudgetUtils spBudgetUtils;

    private static final String PREFS_NAME = "SereneLedgerConfig";
    private static final String KEY_MONTHLY_BUDGET = "monthly_budget_cents";

    private static final int MIN_BUDGET = 1000;
    private static final int MAX_BUDGET = 20000;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 1. 精确绑定你新版 XML 里的真实 ID
        tvBudgetValue = view.findViewById(R.id.budget_value);
        tvDailyLimit = view.findViewById(R.id.tv_daily_limit);
        budgetSlider = view.findViewById(R.id.budget_slider);
        llTicks = view.findViewById(R.id.ll_ticks);

        tvCheckInCount = view.findViewById(R.id.tv_check_in_count);
        checkInProgress = view.findViewById(R.id.check_in_progress);
        llAchievementSection = view.findViewById(R.id.ll_achievement_section);

        sharedPreferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        db = AppDatabase.getDatabase(requireContext());
        spBudgetUtils = new SpBudgetUtils(getActivity());

        setupBudgetSlider();
        generateSliderTicks();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 瞬间同步数据
        syncBudgetDisplay();
        calculateRealTimeCheckIn();
        updateAchievementsState();
    }

    private void setupBudgetSlider() {
        budgetSlider.setOnBudgetChangeListener(new CoolBudgetSeekBar.OnBudgetChangeListener() {
            @Override
            public void getBudgetChanged(int budget) {
                // 拖动中：实时更新数字
                if (tvBudgetValue != null) {
                    tvBudgetValue.setText(String.format(Locale.getDefault(), "¥%,d", budget));
                }
                if (tvDailyLimit != null) {
                    double dailyLimit = (double) budget / getDaysInCurrentMonth();
                    tvDailyLimit.setText(String.format(Locale.getDefault(), "¥%.2f", dailyLimit));
                }
            }

            @Override
            public void onBudgetChanged(int budget) {
                // 抬手结束：写入 SP，并重新评估成就系统（因为预算额度变了，可能触发或丢失成就）
                sharedPreferences.edit().putLong(KEY_MONTHLY_BUDGET, budget * 100L).apply();
                updateAchievementsState();
            }
        });
    }

    private void generateSliderTicks() {
        if (llTicks == null) return;
        llTicks.removeAllViews();

        TextView tvMin = new TextView(requireContext());
        tvMin.setText("¥1,000");
        tvMin.setTextColor(Color.parseColor("#707974"));
        tvMin.setTextSize(12);
        LinearLayout.LayoutParams minParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvMin.setLayoutParams(minParams);

        TextView tvMax = new TextView(requireContext());
        tvMax.setText("¥20,000");
        tvMax.setTextColor(Color.parseColor("#707974"));
        tvMax.setTextSize(12);
        tvMax.setGravity(Gravity.END);
        LinearLayout.LayoutParams maxParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvMax.setLayoutParams(maxParams);

        llTicks.addView(tvMin);
        llTicks.addView(tvMax);
    }

    private void syncBudgetDisplay() {
        long currentBudgetCents = sharedPreferences.getLong(KEY_MONTHLY_BUDGET, 850000L); // 默认 ¥8,500
        int currentBudgetYuan = (int) (currentBudgetCents / 100);

        if (budgetSlider != null) {
            budgetSlider.setConfig(MIN_BUDGET, MAX_BUDGET, currentBudgetYuan);
        }
        if (tvBudgetValue != null) {
            tvBudgetValue.setText(String.format(Locale.getDefault(), "¥%,d", currentBudgetYuan));
        }
        if (tvDailyLimit != null) {
            double dailyLimit = (double) currentBudgetYuan / getDaysInCurrentMonth();
            tvDailyLimit.setText(String.format(Locale.getDefault(), "¥%.2f", dailyLimit));
        }
    }

    private void calculateRealTimeCheckIn() {
        new Thread(() -> {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            long startOfWeek = cal.getTimeInMillis();
            long endOfWeek = System.currentTimeMillis();

            List<Expense> weekExpenses = db.expenseDao().getExpensesInRangeSync(startOfWeek, endOfWeek);
            Set<String> activeDays = new HashSet<>();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

            if (weekExpenses != null) {
                for (Expense e : weekExpenses) {
                    activeDays.add(sdf.format(new Date(e.getTimestamp())));
                }
            }

            int checkedCount = activeDays.size();
            int progressPercent = (int) ((checkedCount * 100f) / 7f);

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (tvCheckInCount != null) {
                        tvCheckInCount.setText(String.format(Locale.getDefault(), "%d / 7 天", checkedCount));
                    }
                    if (checkInProgress != null) {
                        checkInProgress.setProgress(progressPercent);
                    }
                });
            }
        }).start();
    }

    private void updateAchievementsState() {
        long currentBudgetCents = sharedPreferences.getLong(KEY_MONTHLY_BUDGET, 850000L);

        AchievementManager.computeRealAchievements(requireContext(), currentBudgetCents, (achievements, unlockedCount) -> {
            if (llAchievementSection == null || getActivity() == null) return;

            // 1. 保留标题区域（Header是第0个子View），清空下面的死数据
            int childCount = llAchievementSection.getChildCount();
            if (childCount > 1) {
                llAchievementSection.removeViews(1, childCount - 1);
            }

            // 2. 动态更新 Header 上的 "X / 8 解锁"
            try {
                LinearLayout header = (LinearLayout) llAchievementSection.getChildAt(0);
                TextView tvSummary = (TextView) header.getChildAt(1);
                tvSummary.setText(String.format(Locale.getDefault(), "%d / %d 解锁", unlockedCount, achievements.size()));
            } catch (Exception e) { e.printStackTrace(); }

            // 3. 高度还原你的精美 XML 结构并动态注入你的成就
            for (Achievement ach : achievements) {
                LinearLayout itemRow = new LinearLayout(requireContext());
                itemRow.setOrientation(LinearLayout.HORIZONTAL);
                itemRow.setGravity(Gravity.START);
                itemRow.setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12));

                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowParams.topMargin = dp2px(12);
                itemRow.setLayoutParams(rowParams);

                // 根据解锁状态调节透明度
                if (!ach.isUnlocked()) itemRow.setAlpha(0.6f);

                // --- A. 左侧图标区域 ---
                LinearLayout iconContainer = new LinearLayout(requireContext());
                iconContainer.setLayoutParams(new LinearLayout.LayoutParams(dp2px(48), dp2px(48)));
                iconContainer.setGravity(Gravity.CENTER);
                if (ach.isUnlocked()) iconContainer.setElevation(dp2px(2)); // 已解锁加微弱阴影

                ImageView ivIcon = new ImageView(requireContext());
                // 这里接入了你的 R.drawable.pigmoney 等原始资源！
                ivIcon.setImageResource(ach.getIcon());
                int iconPadding = dp2px(8);
                ivIcon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);

                // 为了避免没有背景，给个浅色底衬
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                bg.setColor(ach.isUnlocked() ? Color.parseColor("#E7EEFF") : Color.parseColor("#F2F2F7"));
                iconContainer.setBackground(bg);

                iconContainer.addView(ivIcon);
                itemRow.addView(iconContainer);

                // --- B. 右侧文字区域 ---
                LinearLayout textGroup = new LinearLayout(requireContext());
                textGroup.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                textParams.leftMargin = dp2px(16);
                textGroup.setLayoutParams(textParams);

                // 标题行：成就名 + 状态徽章
                LinearLayout titleRow = new LinearLayout(requireContext());
                titleRow.setOrientation(LinearLayout.HORIZONTAL);
                titleRow.setGravity(Gravity.CENTER_VERTICAL);

                TextView tvTitle = new TextView(requireContext());
                tvTitle.setText(ach.getName());
                tvTitle.setTextColor(Color.parseColor("#111c2d"));
                tvTitle.setTextSize(16);
                titleRow.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView tvBadge = new TextView(requireContext());
                if (ach.isUnlocked()) {
                    tvBadge.setText("已解锁");
                    tvBadge.setTextColor(Color.parseColor("#003527"));
                    tvBadge.setTextSize(12);
                    tvBadge.setPadding(dp2px(8), dp2px(2), dp2px(8), dp2px(2));

                    android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
                    badgeBg.setCornerRadius(dp2px(8));
                    badgeBg.setColor(Color.parseColor("#B0F0D6")); // primary-fixed 浅绿背景
                    tvBadge.setBackground(badgeBg);
                } else {
                    tvBadge.setText("未解锁"); // 你 XML 里的 lock
                    tvBadge.setTextColor(Color.parseColor("#505f76"));
                    tvBadge.setTextSize(12);
                }
                titleRow.addView(tvBadge);

                textGroup.addView(titleRow);

                // 描述说明
                TextView tvDesc = new TextView(requireContext());
                tvDesc.setText(ach.getDescription());
                tvDesc.setTextColor(Color.parseColor("#505f76"));
                tvDesc.setTextSize(12);
                LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                descParams.topMargin = dp2px(4);
                tvDesc.setLayoutParams(descParams);
                textGroup.addView(tvDesc);

                itemRow.addView(textGroup);

                // 将动态拼装的卡片挂载进页面
                llAchievementSection.addView(itemRow);
            }
        });
    }

    private int getDaysInCurrentMonth() {
        Calendar calendar = Calendar.getInstance();
        return calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
