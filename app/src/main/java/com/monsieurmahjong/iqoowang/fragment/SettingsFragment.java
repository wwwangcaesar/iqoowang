package com.monsieurmahjong.iqoowang.fragment;



import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.connect.Achievement;
import com.monsieurmahjong.iqoowang.utils.CheckInManager;
import com.monsieurmahjong.iqoowang.utils.SpBudgetUtils;

import java.util.ArrayList;
import java.util.List;

public class SettingsFragment extends Fragment {

    private TextView tvBudgetValue;
    private TextView tvDailyLimit;
    private SeekBar budgetSlider;
    private View llTicks;
    private TextView tvCheckInCount;
    private android.widget.ProgressBar checkInProgress;
    private View llAchievementSection;

    private SpBudgetUtils spBudgetUtils;
    private CheckInManager checkInManager;
    private List<Achievement> achievementList;

    // 预算刻度配置
    private static final int MIN_BUDGET = 1000;
    private static final int MAX_BUDGET = 20000;
    private static final int TICK_INTERVAL = 5000; // 每2000元一个刻度

    public SettingsFragment() {
        // Required empty public constructor
    }

    public static SettingsFragment newInstance() {
        return new SettingsFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.activity_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 初始化工具类
        spBudgetUtils = SpBudgetUtils.getInstance(requireContext());
        checkInManager = CheckInManager.getInstance();

        // 初始化视图
        initViews(view);

        // 初始化预算刻度
        initBudgetTicks();

        // 初始化成就数据
        initAchievementData();

        // 设置预算滑块逻辑
        setupBudgetSlider();

        // 设置每日打卡显示
        setupCheckInDisplay();

        // 设置成就点击事件
        setupAchievementClick();
    }

    private void initViews(View view) {
        tvBudgetValue = view.findViewById(R.id.budget_value);
        tvDailyLimit = view.findViewById(R.id.tv_daily_limit);
        budgetSlider = view.findViewById(R.id.budget_slider);
        llTicks = view.findViewById(R.id.ll_ticks);
        tvCheckInCount = view.findViewById(R.id.tv_check_in_count);
        checkInProgress = view.findViewById(R.id.check_in_progress);
        llAchievementSection = view.findViewById(R.id.ll_achievement_section);
    }

    private void initBudgetTicks() {
        // 动态生成刻度线和数值
        ViewGroup tickContainer = (ViewGroup) llTicks;
        tickContainer.removeAllViews();

        // 计算刻度数量
        int tickCount = (MAX_BUDGET - MIN_BUDGET) / TICK_INTERVAL + 1;

        for (int i = 0; i < tickCount; i++) {
            int budget = MIN_BUDGET + i * TICK_INTERVAL;

            // 创建刻度容器
            View tickView = LayoutInflater.from(requireContext()).inflate(R.layout.item_budget_tick, tickContainer, false);

            // 设置刻度数值
            TextView tvTickValue = tickView.findViewById(R.id.tv_tick_value);
            tvTickValue.setText(String.format("¥%,d", budget));

            // 添加到容器
            tickContainer.addView(tickView);
        }
    }

    private void initAchievementData() {
        achievementList = new ArrayList<>();
        // 添加所有成就（可根据需要扩展）
        achievementList.add(new Achievement(1, "节俭大师", "连续3个月支出低于预算80%", R.drawable.pigmoney, true));
        achievementList.add(new Achievement(2, "省钱专家", "月度结余达到 ¥1,000", R.drawable.trendingup2, true));
        achievementList.add(new Achievement(3, "预算达人", "误差控制在月预算5%内", R.drawable.targetarrow, false));
        achievementList.add(new Achievement(4, "满勤宝宝", "全月全勤记录", R.drawable.calendarcheck, false));
        achievementList.add(new Achievement(5, "理财新手", "首次设置预算", R.drawable.creditcardrefund, true));
        achievementList.add(new Achievement(6, "消费达人", "记录100笔支出", R.drawable.shoppingbagheart, false));
        achievementList.add(new Achievement(7, "月度冠军", "单月结余超过50%", R.drawable.trophy, false));
        achievementList.add(new Achievement(8, "坚持就是胜利", "连续使用30天", R.drawable.stars, false));
    }

    private void setupBudgetSlider() {
        // 从SP读取预算并初始化
        int savedBudget = spBudgetUtils.getMonthlyBudget();
        budgetSlider.setProgress(savedBudget);
        tvBudgetValue.setText(String.format("¥%,d", savedBudget));
        tvDailyLimit.setText(spBudgetUtils.getDailyLimit());

        // 设置滑块拖动监听
        budgetSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // 实时更新显示
                tvBudgetValue.setText(String.format("¥%,d", progress));
                // 实时计算每日限额
                double dailyLimit = (double) progress / spBudgetUtils.getDaysInCurrentMonth();
                tvDailyLimit.setText(String.format("¥%.2f", dailyLimit));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 拖动开始时不需要操作
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 拖动结束时保存到SP
                int finalBudget = seekBar.getProgress();
                spBudgetUtils.saveMonthlyBudget(finalBudget);
            }
        });
    }

    private void setupCheckInDisplay() {
        // 获取本周打卡数据
        int checkedDays = checkInManager.getWeekCheckInCount(requireContext());
        int totalDays = checkInManager.getWeekTotalDays();
        int progress = checkInManager.getCheckInProgress(requireContext());

        // 更新UI
        tvCheckInCount.setText(String.format("%d / %d 天", checkedDays, totalDays));
        checkInProgress.setProgress(progress);
    }

    private void setupAchievementClick() {
        llAchievementSection.setOnClickListener(v -> {
            // 弹出成就列表Dialog（Fragment中使用getChildFragmentManager）
            AchievementDialogFragment dialog = AchievementDialogFragment.newInstance(achievementList);
            dialog.show(getChildFragmentManager(), "AchievementDialog");
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // 页面恢复时刷新打卡数据
        setupCheckInDisplay();
    }
}