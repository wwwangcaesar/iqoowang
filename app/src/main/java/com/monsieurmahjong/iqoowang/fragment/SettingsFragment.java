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
import androidx.cardview.widget.CardView;
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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SettingsFragment extends Fragment {

    // ── 原有视图 ──────────────────────────────────────────
    private TextView tvBudgetValue;
    private TextView tvDailyLimit;
    private CoolBudgetSeekBar budgetSlider;
    private TextView tvCheckInCount;
    private ProgressBar checkInProgress;
    private LinearLayout llAchievementSection;
    private TextView tvAchievementSummary;

    // ── 新增视图 ──────────────────────────────────────────
    private TextView tvCalendarMonth;
    private CardView cardFamilyArchive;
    private LinearLayout llFamilyNewsContainer;
    private TextView tvFamilyNewsLoading;
    private LinearLayout llAchievementHeader;

    // ── 数据 ─────────────────────────────────────────────
    /** 完整成就列表（含已解锁/未解锁），用于传递给 DialogFragment */
    private List<Achievement> allAchievements = new ArrayList<>();
    /** 家庭新闻列表（网络返回），用于传递给 DialogFragment */
    private List<FamilyNewsItem> familyNewsList = new ArrayList<>();

    // ── 原有工具 ──────────────────────────────────────────
    private SharedPreferences sharedPreferences;
    private AppDatabase db;
    private SpBudgetUtils spBudgetUtils;
    private CheckInManager checkInManager;

    private static final String PREFS_NAME = "SereneLedgerConfig";
    private static final String KEY_MONTHLY_BUDGET = "monthly_budget_cents";
    private static final int MIN_BUDGET = 1000;
    private static final int MAX_BUDGET = 20000;

    // ─────────────────────────────────────────────────────
    //  家庭新闻数据模型
    // ─────────────────────────────────────────────────────
    public static class FamilyNewsItem {
        public final String category;
        public final String content;
        public final int id;
        public final String publishTime;
        public final String source;
        public final String title;

        public FamilyNewsItem(String category, String content, int id,
                              String publishTime, String source, String title) {
            this.category = category;
            this.content = content;
            this.id = id;
            this.publishTime = publishTime;
            this.source = source;
            this.title = title;
        }
    }

    // ─────────────────────────────────────────────────────
    //  生命周期
    // ─────────────────────────────────────────────────────

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // ── 原有视图绑定 ─────────────────────────────────
        tvBudgetValue        = view.findViewById(R.id.budget_value);
        tvDailyLimit         = view.findViewById(R.id.tv_daily_limit);
        budgetSlider         = view.findViewById(R.id.budget_slider);
        tvCheckInCount       = view.findViewById(R.id.tv_check_in_count);
        checkInProgress      = view.findViewById(R.id.check_in_progress);
        llAchievementSection = view.findViewById(R.id.ll_achievement_section);
        tvAchievementSummary = view.findViewById(R.id.tv_achievement_summary);

        // ── 新增视图绑定 ─────────────────────────────────
        tvCalendarMonth      = view.findViewById(R.id.tv_calendar_month);
        cardFamilyArchive    = view.findViewById(R.id.card_family_archive);
        llFamilyNewsContainer= view.findViewById(R.id.ll_family_news_container);
        tvFamilyNewsLoading  = view.findViewById(R.id.tv_family_news_loading);
        llAchievementHeader  = view.findViewById(R.id.ll_achievement_header);

        // ── 工具初始化 ───────────────────────────────────
        sharedPreferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        db            = AppDatabase.getDatabase(requireContext());
        spBudgetUtils = new SpBudgetUtils(getActivity());
        checkInManager= CheckInManager.getInstance();

        // ── 点击事件 ─────────────────────────────────────
        // 1. 日历按钮 → 弹出日历对话框
        tvCalendarMonth.setOnClickListener(v -> openCalendarDialog());

        // 2. 家庭档案卡片 → 弹出新闻列表对话框
        cardFamilyArchive.setOnClickListener(v -> openFamilyNewsDialog());

        // 3. 成就徽章头部 → 弹出全部成就对话框
        llAchievementHeader.setOnClickListener(v -> openAchievementDialog());

        setupBudgetSlider();

        // 加载家庭新闻（模拟网络请求）
        loadFamilyNews();
    }

    @Override
    public void onResume() {
        super.onResume();
        syncBudgetDisplay();
        calculateRealTimeCheckIn();
        updateAchievementsState();
    }

    // ─────────────────────────────────────────────────────
    //  日历对话框
    // ─────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────
    //  家庭档案 - 网络数据加载 & 显示
    // ─────────────────────────────────────────────────────

    /** 模拟网络请求获取家庭资讯，展示前 3 条到卡片内 */
    private void loadFamilyNews() {
        new Thread(() -> {
            // 模拟网络延迟
            try { Thread.sleep(600); } catch (InterruptedException e) { /* ignore */ }

            // 构造模拟数据（对应接口返回的 JSON 格式）
            List<FamilyNewsItem> items = new ArrayList<>();
            items.add(new FamilyNewsItem(
                    "本地民生",
                    "【硬核事实】因管道维修，朝阳区部分区域将于5月26日停水15小时。【分析应对】受影响居民请提前储备生活用水。",
                    1,
                    "今天",
                    "长春市水务局",
                    "长春市朝阳区5月26日部分区域停水通知"
            ));
            items.add(new FamilyNewsItem(
                    "家庭健康",
                    "【健康提示】夏季气温升高，儿童防暑降温尤为重要。建议户外活动时间选在上午10点前或下午4点后，并及时补充水分。",
                    2,
                    "昨天",
                    "长春市卫生健康委员会",
                    "夏季儿童防暑降温健康指南"
            ));
            items.add(new FamilyNewsItem(
                    "社区公告",
                    "【温馨提示】本社区将于本周六上午9:00举行消防安全演练，请居民积极参与，了解紧急疏散路线和灭火器使用方法。",
                    3,
                    "2天前",
                    "绿园街道办事处",
                    "2026年社区消防安全演练通知"
            ));

            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                familyNewsList = items;
                displayFamilyNewsInCard(items);
            });
        }).start();
    }

    /** 将前 3 条新闻动态渲染到家庭档案 Card 内 */
    private void displayFamilyNewsInCard(List<FamilyNewsItem> items) {
        if (llFamilyNewsContainer == null || getContext() == null) return;

        // 移除加载占位文字
        if (tvFamilyNewsLoading != null) tvFamilyNewsLoading.setVisibility(View.GONE);

        // 移除旧动态 item（保留第 0 个 loading TextView 槽位）
        int childCount = llFamilyNewsContainer.getChildCount();
        if (childCount > 1) {
            llFamilyNewsContainer.removeViews(1, childCount - 1);
        }

        int showCount = Math.min(3, items.size());
        for (int i = 0; i < showCount; i++) {
            FamilyNewsItem item = items.get(i);

            // 条目间分割线（首条不加）
            if (i > 0) {
                View divider = new View(requireContext());
                LinearLayout.LayoutParams dvParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                dvParams.topMargin    = dp2px(10);
                dvParams.bottomMargin = dp2px(10);
                divider.setLayoutParams(dvParams);
                divider.setBackgroundColor(Color.parseColor("#f0f0f0"));
                llFamilyNewsContainer.addView(divider);
            }

            // ── 条目容器 ──────────────────────────────
            LinearLayout itemLayout = new LinearLayout(requireContext());
            itemLayout.setOrientation(LinearLayout.VERTICAL);
            itemLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 标题行：[分类标签]  标题文字
            LinearLayout titleRow = new LinearLayout(requireContext());
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 分类标签
            TextView tvCategory = new TextView(requireContext());
            tvCategory.setText(item.category);
            tvCategory.setTextSize(10);
            tvCategory.setTextColor(Color.parseColor("#003527"));
            tvCategory.setBackground(buildTagBackground("#e7eeff"));
            tvCategory.setPadding(dp2px(6), dp2px(2), dp2px(6), dp2px(2));
            LinearLayout.LayoutParams catParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            catParams.setMarginEnd(dp2px(8));
            tvCategory.setLayoutParams(catParams);

            // 标题
            TextView tvTitle = new TextView(requireContext());
            tvTitle.setText(item.title);
            tvTitle.setTextSize(13);
            tvTitle.setTextColor(Color.parseColor("#111c2d"));
            tvTitle.setMaxLines(2);
            tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            titleRow.addView(tvCategory);
            titleRow.addView(tvTitle);
            itemLayout.addView(titleRow);

            // 来源 + 时间行
            LinearLayout metaRow = new LinearLayout(requireContext());
            metaRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            metaParams.topMargin = dp2px(4);
            metaRow.setLayoutParams(metaParams);

            TextView tvSource = new TextView(requireContext());
            tvSource.setText(item.source);
            tvSource.setTextSize(11);
            tvSource.setTextColor(Color.parseColor("#909aA3"));
            tvSource.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvTime = new TextView(requireContext());
            tvTime.setText(item.publishTime);
            tvTime.setTextSize(11);
            tvTime.setTextColor(Color.parseColor("#b0b8c1"));

            metaRow.addView(tvSource);
            metaRow.addView(tvTime);
            itemLayout.addView(metaRow);

            llFamilyNewsContainer.addView(itemLayout);
        }
    }

    /** 点击家庭档案卡片 → 弹出全部新闻对话框 */
    private void openFamilyNewsDialog() {
        FamilyNewsDialogFragment dialog =
                FamilyNewsDialogFragment.newInstance(new ArrayList<>(familyNewsList));
        dialog.show(getChildFragmentManager(), "FamilyNewsDialog");
    }

    // ─────────────────────────────────────────────────────
    //  成就徽章 - 点击打开全部成就对话框
    // ─────────────────────────────────────────────────────

    private void openAchievementDialog() {
        if (allAchievements.isEmpty()) {
            Toast.makeText(getContext(), "成就数据加载中，请稍后...", Toast.LENGTH_SHORT).show();
            return;
        }
        AchievementDialogFragment dialog =
                AchievementDialogFragment.newInstance(new ArrayList<>(allAchievements));
        dialog.show(getChildFragmentManager(), "AchievementDialog");
    }

    // ─────────────────────────────────────────────────────
    //  预算 Slider
    // ─────────────────────────────────────────────────────

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
        if (budgetSlider != null) budgetSlider.setConfig(MIN_BUDGET, MAX_BUDGET, currentBudgetYuan);
        if (tvBudgetValue != null)
            tvBudgetValue.setText(String.format(Locale.getDefault(), "¥%,d", currentBudgetYuan));
        if (tvDailyLimit != null) {
            double dailyLimit = (double) currentBudgetYuan / getDaysInCurrentMonth();
            tvDailyLimit.setText(String.format(Locale.getDefault(), "¥%.2f", dailyLimit));
        }
    }

    // ─────────────────────────────────────────────────────
    //  打卡
    // ─────────────────────────────────────────────────────

    private void calculateRealTimeCheckIn() {
        new Thread(() -> {
            int checkedCount     = checkInManager.getWeekCheckInCount(requireContext());
            int progressPercent  = checkInManager.getCheckInProgress(requireContext());
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (tvCheckInCount  != null) tvCheckInCount.setText(String.format(Locale.getDefault(), "%d / 7 天", checkedCount));
                    if (checkInProgress != null) checkInProgress.setProgress(progressPercent);
                });
            }
        }).start();
    }

    // ─────────────────────────────────────────────────────
    //  成就徽章 - 核心渲染（Settings 页面只展示已解锁条目）
    // ─────────────────────────────────────────────────────

    /**
     * 核心高动态渲染层：
     *  - 将全量成就保存到 allAchievements 供对话框使用
     *  - Settings 页面仅渲染「已解锁」成就
     */
    private void updateAchievementsState() {
        long currentBudgetCents = sharedPreferences.getLong(KEY_MONTHLY_BUDGET, 850000L);

        AchievementManager.computeRealAchievements(requireContext(), currentBudgetCents,
                (achievements, unlockedCount, newlyUnlocked) -> {
                    if (llAchievementSection == null || getActivity() == null) return;

                    // 1. 保存完整列表（含未解锁），供对话框展示全部
                    allAchievements = new ArrayList<>(achievements);

                    // 2. 刷新摘要文字
                    if (tvAchievementSummary != null) {
                        tvAchievementSummary.setText(String.format(Locale.getDefault(),
                                "%d / %d 解锁", unlockedCount, achievements.size()));
                    }

                    // 3. 清除旧动态 item（保留 index=0 的 header 行）
                    //    ll_achievement_section 的第 0 子 View 是 ll_achievement_header
                    int childCount = llAchievementSection.getChildCount();
                    if (childCount > 1) {
                        llAchievementSection.removeViews(1, childCount - 1);
                    }

                    // 4. 新解锁动画提示
                    if (newlyUnlocked != null && !newlyUnlocked.isEmpty()) {
                        for (Achievement newAch : newlyUnlocked) {
                            Toast.makeText(getContext(),
                                    "🎉 恭喜达成新成就: " + newAch.getName(), Toast.LENGTH_LONG).show();
                        }
                    }

                    // 5. ✅ Settings 页面只渲染「已解锁」成就
                    boolean hasUnlocked = false;
                    for (Achievement ach : achievements) {
                        if (!ach.isUnlocked()) continue; // 跳过未解锁
                        hasUnlocked = true;

                        LinearLayout itemRow = new LinearLayout(requireContext());
                        itemRow.setOrientation(LinearLayout.HORIZONTAL);
                        itemRow.setGravity(Gravity.CENTER_VERTICAL);
                        itemRow.setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12));

                        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        rowParams.topMargin = dp2px(8);
                        itemRow.setLayoutParams(rowParams);

                        // 图标圆形背景
                        LinearLayout iconContainer = new LinearLayout(requireContext());
                        iconContainer.setLayoutParams(new LinearLayout.LayoutParams(dp2px(44), dp2px(44)));
                        iconContainer.setGravity(Gravity.CENTER);

                        android.graphics.drawable.GradientDrawable bg =
                                new android.graphics.drawable.GradientDrawable();
                        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                        bg.setColor(Color.parseColor("#E7EEFF"));
                        iconContainer.setBackground(bg);

                        ImageView ivIcon = new ImageView(requireContext());
                        ivIcon.setImageResource(ach.getIcon());
                        ivIcon.setPadding(dp2px(8), dp2px(8), dp2px(8), dp2px(8));
                        iconContainer.addView(ivIcon);
                        itemRow.addView(iconContainer);

                        // 文字组
                        LinearLayout textGroup = new LinearLayout(requireContext());
                        textGroup.setOrientation(LinearLayout.VERTICAL);
                        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        textParams.leftMargin = dp2px(14);
                        textGroup.setLayoutParams(textParams);

                        LinearLayout titleRow = new LinearLayout(requireContext());
                        titleRow.setOrientation(LinearLayout.HORIZONTAL);

                        TextView tvTitle = new TextView(requireContext());
                        tvTitle.setText(ach.getName());
                        tvTitle.setTextColor(Color.parseColor("#111c2d"));
                        tvTitle.setTextSize(15);
                        titleRow.addView(tvTitle, new LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                        TextView tvStatus = new TextView(requireContext());
                        tvStatus.setText("已解锁");
                        tvStatus.setTextSize(12);
                        tvStatus.setTextColor(Color.parseColor("#003527"));
                        titleRow.addView(tvStatus);
                        textGroup.addView(titleRow);

                        TextView tvDesc = new TextView(requireContext());
                        tvDesc.setText(ach.getDescription());
                        tvDesc.setTextColor(Color.parseColor("#505f76"));
                        tvDesc.setTextSize(12);
                        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        descParams.topMargin = dp2px(2);
                        tvDesc.setLayoutParams(descParams);
                        textGroup.addView(tvDesc);

                        itemRow.addView(textGroup);
                        llAchievementSection.addView(itemRow);
                    }

                    // 若一个已解锁成就都没有，显示提示文字
                    if (!hasUnlocked) {
                        TextView tvEmpty = new TextView(requireContext());
                        tvEmpty.setText("还没有解锁成就，继续加油！");
                        tvEmpty.setTextSize(12);
                        tvEmpty.setTextColor(Color.parseColor("#b0b8c1"));
                        tvEmpty.setGravity(Gravity.CENTER);
                        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        ep.topMargin = dp2px(16);
                        ep.bottomMargin = dp2px(8);
                        tvEmpty.setLayoutParams(ep);
                        llAchievementSection.addView(tvEmpty);
                    }
                });
    }

    // ─────────────────────────────────────────────────────
    //  工具
    // ─────────────────────────────────────────────────────

    /** 构建圆角 Tag 背景 */
    private android.graphics.drawable.GradientDrawable buildTagBackground(String colorHex) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp2px(4));
        d.setColor(Color.parseColor(colorHex));
        return d;
    }

    private int getDaysInCurrentMonth() {
        return Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
