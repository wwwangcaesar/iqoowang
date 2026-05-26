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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.connect.Achievement;
import com.monsieurmahjong.iqoowang.dao.AchievementManager;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;
import com.monsieurmahjong.iqoowang.utils.CheckInManager;
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
    private TextView tvCheckInCount;
    private ProgressBar checkInProgress;
    private LinearLayout llAchievementSection;
    private TextView tvAchievementSummary;

    private SharedPreferences sharedPreferences;
    private AppDatabase db;
    private SpBudgetUtils spBudgetUtils;
    private CheckInManager checkInManager;

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

        // 绑定 XML 视图组件
        tvBudgetValue = view.findViewById(R.id.budget_value);
        tvDailyLimit = view.findViewById(R.id.tv_daily_limit);
        budgetSlider = view.findViewById(R.id.budget_slider);
        tvCheckInCount = view.findViewById(R.id.tv_check_in_count);
        checkInProgress = view.findViewById(R.id.check_in_progress);
        llAchievementSection = view.findViewById(R.id.ll_achievement_section);
        tvAchievementSummary = view.findViewById(R.id.tv_achievement_summary);

        sharedPreferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        db = AppDatabase.getDatabase(requireContext());
        spBudgetUtils = new SpBudgetUtils(getActivity());
        checkInManager = CheckInManager.getInstance();

        setupBudgetSlider();
    }

    @Override
    public void onResume() {
        super.onResume();
        syncBudgetDisplay();
        calculateRealTimeCheckIn();
        updateAchievementsState(); // 当切换回/进入当前 Fragment 页面时，触发成就精准核对与动画
    }

    private void setupBudgetSlider() {
        if (budgetSlider == null) return;
        budgetSlider.setOnBudgetChangeListener(new CoolBudgetSeekBar.OnBudgetChangeListener() {
            @Override
            public void getBudgetChanged(int budget) {
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
                sharedPreferences.edit().putLong(KEY_MONTHLY_BUDGET, budget * 100L).apply();
                updateAchievementsState();
            }
        });
    }

    private void syncBudgetDisplay() {
        long currentBudgetCents = sharedPreferences.getLong(KEY_MONTHLY_BUDGET, 850000L);
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
            int checkedCount = checkInManager.getWeekCheckInCount(requireContext());
            int progressPercent = checkInManager.getCheckInProgress(requireContext());

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

    /**
     * 核心高动态渲染层：完成高精度判定、注入你原有的图片资源，并拦截发放动画通知
     */
    private void updateAchievementsState() {
        long currentBudgetCents = sharedPreferences.getLong(KEY_MONTHLY_BUDGET, 850000L);

        AchievementManager.computeRealAchievements(requireContext(), currentBudgetCents, (achievements, unlockedCount, newlyUnlocked) -> {
            if (llAchievementSection == null || getActivity() == null) return;

            // 1. 动态刷新顶层头部的摘要汇总文本
            if (tvAchievementSummary != null) {
                tvAchievementSummary.setText(String.format(Locale.getDefault(), "%d / %d 解锁", unlockedCount, achievements.size()));
            }

            // 2. 清理历史动态注入的卡片子View（保留第0个Header）
            int childCount = llAchievementSection.getChildCount();
            if (childCount > 1) {
                llAchievementSection.removeViews(1, childCount - 1);
            }

            // 3. 【动画唤醒核心】：检查是否有在此刻新鲜生成的解锁成就
            if (newlyUnlocked != null && !newlyUnlocked.isEmpty()) {
                for (Achievement newAch : newlyUnlocked) {
                    // 🚀 TODO: 这里就是你的全局解锁弹出动画挂载点！
                    // 你可以在此处构造类似 CustomLottieDialogFragment、波纹弹窗或者飘带提示
                    Toast.makeText(getContext(), "🎉 恭喜达成新成就: " + newAch.getName(), Toast.LENGTH_LONG).show();
                }
            }

            // 4. 动态绘制 UI
            for (Achievement ach : achievements) {
                LinearLayout itemRow = new LinearLayout(requireContext());
                itemRow.setOrientation(LinearLayout.HORIZONTAL);
                itemRow.setGravity(Gravity.CENTER_VERTICAL);
                itemRow.setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12));

                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowParams.topMargin = dp2px(12);
                itemRow.setLayoutParams(rowParams);

                if (!ach.isUnlocked()) itemRow.setAlpha(0.5f); // 未解锁淡化

                // 图标外衬圈
                LinearLayout iconContainer = new LinearLayout(requireContext());
                iconContainer.setLayoutParams(new LinearLayout.LayoutParams(dp2px(44), dp2px(44)));
                iconContainer.setGravity(Gravity.CENTER);

                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                bg.setColor(ach.isUnlocked() ? Color.parseColor("#E7EEFF") : Color.parseColor("#F2F2F7"));
                iconContainer.setBackground(bg);

                ImageView ivIcon = new ImageView(requireContext());
                ivIcon.setImageResource(ach.getIcon()); // 完美回填对应的 R.drawable.* 资源
                ivIcon.setPadding(dp2px(8), dp2px(8), dp2px(8), dp2px(8));
                iconContainer.addView(ivIcon);
                itemRow.addView(iconContainer);

                // 文本描述树
                LinearLayout textGroup = new LinearLayout(requireContext());
                textGroup.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                textParams.leftMargin = dp2px(14);
                textGroup.setLayoutParams(textParams);

                LinearLayout titleRow = new LinearLayout(requireContext());
                titleRow.setOrientation(LinearLayout.HORIZONTAL);

                TextView tvTitle = new TextView(requireContext());
                tvTitle.setText(ach.getName());
                tvTitle.setTextColor(Color.parseColor("#111c2d"));
                tvTitle.setTextSize(15);
                titleRow.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView tvStatus = new TextView(requireContext());
                tvStatus.setText(ach.isUnlocked() ? "已解锁" : "未解锁");
                tvStatus.setTextSize(12);
                tvStatus.setTextColor(ach.isUnlocked() ? Color.parseColor("#003527") : Color.parseColor("#707974"));
                titleRow.addView(tvStatus);
                textGroup.addView(titleRow);

                TextView tvDesc = new TextView(requireContext());
                tvDesc.setText(ach.getDescription());
                tvDesc.setTextColor(Color.parseColor("#505f76"));
                tvDesc.setTextSize(12);
                LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                descParams.topMargin = dp2px(2);
                tvDesc.setLayoutParams(descParams);
                textGroup.addView(tvDesc);

                itemRow.addView(textGroup);
                llAchievementSection.addView(itemRow);
            }
        });
    }

    private int getDaysInCurrentMonth() {
        return Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
