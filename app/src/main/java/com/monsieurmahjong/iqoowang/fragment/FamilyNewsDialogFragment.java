package com.monsieurmahjong.iqoowang.fragment;


import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.fragment.SettingsFragment.FamilyNewsItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 家庭档案资讯全量弹窗
 * 与 AchievementDialogFragment 保持一致的弹出缩放动画风格。
 * 布局完全由代码构建，无需额外 XML 文件。
 */
public class FamilyNewsDialogFragment extends DialogFragment {

    private static final String ARG_NEWS_LIST = "arg_news_list";

    private List<FamilyNewsItem> newsList;

    // ── 工厂方法 ─────────────────────────────────────────

    public static FamilyNewsDialogFragment newInstance(ArrayList<FamilyNewsItem> list) {
        FamilyNewsDialogFragment fragment = new FamilyNewsDialogFragment();
        // 注意：FamilyNewsItem 是 static class，直接赋值引用即可
        fragment.newsList = list;
        return fragment;
    }

    // ── 视图构建 ─────────────────────────────────────────

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
                // ✅ 与 AchievementDialogFragment 相同的弹出缩放动画
                window.setWindowAnimations(R.style.DialogScaleAnimation);
            }
        }

        // 根容器
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(buildRoundedBackground("#FFFFFF", dp2px(20)));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int pad = dp2px(20);

        // ── 标题栏 ────────────────────────────────────────
        LinearLayout headerRow = new LinearLayout(requireContext());
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(pad, pad, pad, dp2px(12));
        headerRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 图标
        TextView tvIcon = new TextView(requireContext());
        tvIcon.setText("article");          // Material Symbols 字体图标
        tvIcon.setTextColor(Color.parseColor("#003527"));
        tvIcon.setTextSize(20);
        LinearLayout.LayoutParams iconP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iconP.setMarginEnd(dp2px(8));
        tvIcon.setLayoutParams(iconP);

        // 标题
        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText("家庭资讯");
        tvTitle.setTextSize(18);
        tvTitle.setTextColor(Color.parseColor("#111c2d"));
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 条数角标
        TextView tvCount = new TextView(requireContext());
        String countText = (newsList != null ? newsList.size() : 0) + " 条";
        tvCount.setText(countText);
        tvCount.setTextSize(12);
        tvCount.setTextColor(Color.parseColor("#707974"));
        LinearLayout.LayoutParams countP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countP.setMarginEnd(dp2px(12));
        tvCount.setLayoutParams(countP);

        // 关闭按钮
        TextView tvClose = new TextView(requireContext());
        tvClose.setText("close");
        tvClose.setTextSize(20);
        tvClose.setTextColor(Color.parseColor("#b0b8c1"));
        tvClose.setPadding(dp2px(4), dp2px(4), dp2px(4), dp2px(4));
        tvClose.setOnClickListener(v -> dismiss());

        headerRow.addView(tvIcon);
        headerRow.addView(tvTitle);
        headerRow.addView(tvCount);
        headerRow.addView(tvClose);
        root.addView(headerRow);

        // 分割线
        View divider = new View(requireContext());
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(Color.parseColor("#f0f0f0"));
        root.addView(divider);

        // ── 新闻列表（可滚动）────────────────────────────
        ScrollView scrollView = new ScrollView(requireContext());
        LinearLayout.LayoutParams svParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        // 最大高度通过 ScrollView 内部的 maxHeight 控制
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout listContainer = new LinearLayout(requireContext());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(pad, dp2px(8), pad, dp2px(16));
        listContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (newsList != null && !newsList.isEmpty()) {
            for (int i = 0; i < newsList.size(); i++) {
                if (i > 0) {
                    View itemDivider = new View(requireContext());
                    LinearLayout.LayoutParams dvP = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 1);
                    dvP.topMargin    = dp2px(12);
                    dvP.bottomMargin = dp2px(12);
                    itemDivider.setLayoutParams(dvP);
                    itemDivider.setBackgroundColor(Color.parseColor("#f5f5f5"));
                    listContainer.addView(itemDivider);
                }
                listContainer.addView(buildNewsItemView(newsList.get(i)));
            }
        } else {
            TextView tvEmpty = new TextView(requireContext());
            tvEmpty.setText("暂无资讯数据");
            tvEmpty.setTextSize(14);
            tvEmpty.setTextColor(Color.parseColor("#b0b8c1"));
            tvEmpty.setGravity(Gravity.CENTER);
            tvEmpty.setPadding(0, dp2px(32), 0, dp2px(32));
            tvEmpty.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            listContainer.addView(tvEmpty);
        }

        scrollView.addView(listContainer);
        root.addView(scrollView);

        return root;
    }

    /** 构建单条新闻视图 */
    private View buildNewsItemView(FamilyNewsItem item) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(buildRoundedBackground("#f9faff", dp2px(12)));
        card.setPadding(dp2px(14), dp2px(12), dp2px(14), dp2px(12));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 顶行：分类标签 + 时间 ──────────────────────
        LinearLayout topRow = new LinearLayout(requireContext());
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvCategory = new TextView(requireContext());
        tvCategory.setText(item.category);
        tvCategory.setTextSize(10);
        tvCategory.setTextColor(Color.parseColor("#003527"));
        tvCategory.setBackground(buildTagBackground("#e7eeff", dp2px(4)));
        tvCategory.setPadding(dp2px(6), dp2px(2), dp2px(6), dp2px(2));
        LinearLayout.LayoutParams catP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        catP.setMarginEnd(dp2px(8));
        tvCategory.setLayoutParams(catP);

        TextView tvTime = new TextView(requireContext());
        tvTime.setText(item.publishTime);
        tvTime.setTextSize(11);
        tvTime.setTextColor(Color.parseColor("#b0b8c1"));
        tvTime.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // spacer
        View spacer = new View(requireContext());
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        topRow.addView(tvCategory);
        topRow.addView(spacer);
        topRow.addView(tvTime);
        card.addView(topRow);

        // ── 标题 ──────────────────────────────────────
        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText(item.title);
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(Color.parseColor("#111c2d"));
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setMaxLines(2);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleP.topMargin = dp2px(6);
        tvTitle.setLayoutParams(titleP);
        card.addView(tvTitle);

        // ── 内容摘要 ──────────────────────────────────
        TextView tvContent = new TextView(requireContext());
        tvContent.setText(item.content);
        tvContent.setTextSize(12);
        tvContent.setTextColor(Color.parseColor("#505f76"));
        tvContent.setMaxLines(3);
        tvContent.setEllipsize(TextUtils.TruncateAt.END);
        tvContent.setLineSpacing(dp2px(2), 1.0f);
        LinearLayout.LayoutParams contentP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentP.topMargin = dp2px(4);
        tvContent.setLayoutParams(contentP);
        card.addView(tvContent);

        // ── 来源行 ────────────────────────────────────
        LinearLayout sourceRow = new LinearLayout(requireContext());
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        sourceRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srcRowP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srcRowP.topMargin = dp2px(8);
        sourceRow.setLayoutParams(srcRowP);

        // 来源图标点
        View dot = new View(requireContext());
        android.graphics.drawable.GradientDrawable dotBg =
                new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(Color.parseColor("#003527"));
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotP = new LinearLayout.LayoutParams(dp2px(6), dp2px(6));
        dotP.setMarginEnd(dp2px(6));
        dot.setLayoutParams(dotP);

        TextView tvSource = new TextView(requireContext());
        tvSource.setText(item.source);
        tvSource.setTextSize(11);
        tvSource.setTextColor(Color.parseColor("#707974"));

        sourceRow.addView(dot);
        sourceRow.addView(tvSource);
        card.addView(sourceRow);

        return card;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }
    }

    // ── 工具方法 ─────────────────────────────────────────

    private android.graphics.drawable.GradientDrawable buildRoundedBackground(
            String colorHex, int cornerRadius) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(cornerRadius);
        d.setColor(Color.parseColor(colorHex));
        return d;
    }

    private android.graphics.drawable.GradientDrawable buildTagBackground(
            String colorHex, int cornerRadius) {
        return buildRoundedBackground(colorHex, cornerRadius);
    }

    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
