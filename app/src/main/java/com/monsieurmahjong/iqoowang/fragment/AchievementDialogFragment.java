package com.monsieurmahjong.iqoowang.fragment;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.connect.Achievement;

import java.util.ArrayList;
import java.util.List;

/**
 * 成就徽章全览弹窗
 *
 * 功能：
 *  - 展示所有成就（已解锁 + 未解锁）
 *  - 已解锁：彩色图标 + 绿色"已解锁"标签
 *  - 未解锁：灰色图标 + 锁定遮罩 + 灰色"未解锁"标签
 *  - 弹出使用与主界面一致的缩放动画（R.style.DialogScaleAnimation）
 */
public class AchievementDialogFragment extends DialogFragment {

    private static final String ARG_ACHIEVEMENTS = "arg_achievements";

    private List<Achievement> achievements;

    // ── 工厂方法 ─────────────────────────────────────────

    public static AchievementDialogFragment newInstance(ArrayList<Achievement> list) {
        AchievementDialogFragment fragment = new AchievementDialogFragment();
        fragment.achievements = list;
        return fragment;
    }

    // ── 视图构建 ─────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        Dialog dialog = getDialog();
        if (dialog != null) {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setBackgroundDrawableResource(android.R.color.transparent);
                // ✅ 弹出缩放动画：从中心放大弹出
                window.setWindowAnimations(R.style.DialogScaleAnimation);
            }
        }

        // ── 根容器 ─────────────────────────────────────
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(buildRoundedBg("#FFFFFF", dp2px(20)));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int pad = dp2px(20);

        // ── 标题栏 ─────────────────────────────────────
        root.addView(buildHeader(pad));

        // 分割线
        View divider = new View(requireContext());
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(Color.parseColor("#f0f0f0"));
        root.addView(divider);

        // ── 统计摘要 ────────────────────────────────────
        root.addView(buildSummaryRow(pad));

        // ── 成就列表 ────────────────────────────────────
        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout listContainer = new LinearLayout(requireContext());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(pad, dp2px(8), pad, dp2px(20));

        if (achievements != null && !achievements.isEmpty()) {
            for (int i = 0; i < achievements.size(); i++) {
                listContainer.addView(buildAchievementRow(achievements.get(i), i));
            }
        } else {
            TextView tvEmpty = new TextView(requireContext());
            tvEmpty.setText("暂无成就数据");
            tvEmpty.setTextSize(14);
            tvEmpty.setTextColor(Color.parseColor("#b0b8c1"));
            tvEmpty.setGravity(Gravity.CENTER);
            tvEmpty.setPadding(0, dp2px(40), 0, dp2px(40));
            listContainer.addView(tvEmpty);
        }

        scrollView.addView(listContainer);
        root.addView(scrollView);

        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    // ── 子视图构建 ────────────────────────────────────────

    /** 标题栏：图标 + "全部成就" + 关闭按钮 */
    private View buildHeader(int pad) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, pad, pad, dp2px(14));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 奖杯图标
        TextView tvIcon = new TextView(requireContext());
        tvIcon.setText("emoji_events");
        tvIcon.setTextSize(22);
        tvIcon.setTextColor(Color.parseColor("#e0a800"));
        LinearLayout.LayoutParams iconP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iconP.setMarginEnd(dp2px(8));
        tvIcon.setLayoutParams(iconP);

        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText("全部成就");
        tvTitle.setTextSize(18);
        tvTitle.setTextColor(Color.parseColor("#111c2d"));
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvClose = new TextView(requireContext());
        tvClose.setText("✕");
        tvClose.setTextSize(16);
        tvClose.setTextColor(Color.parseColor("#b0b8c1"));
        tvClose.setPadding(dp2px(8), dp2px(4), dp2px(4), dp2px(4));
        tvClose.setOnClickListener(v -> dismiss());

        row.addView(tvIcon);
        row.addView(tvTitle);
        row.addView(tvClose);
        return row;
    }

    /** 解锁统计摘要行 */
    private View buildSummaryRow(int pad) {
        int unlockedCount = 0;
        int totalCount    = achievements != null ? achievements.size() : 0;
        if (achievements != null) {
            for (Achievement a : achievements) {
                if (a.isUnlocked()) unlockedCount++;
            }
        }

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(buildRoundedBg("#f9faff", dp2px(12)));
        row.setPadding(pad, dp2px(12), pad, dp2px(12));
        LinearLayout.LayoutParams rowP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowP.leftMargin   = pad;
        rowP.rightMargin  = pad;
        rowP.topMargin    = dp2px(12);
        rowP.bottomMargin = dp2px(4);
        row.setLayoutParams(rowP);

        // 左：已解锁数字
        LinearLayout leftGroup = buildStatGroup(
                String.valueOf(unlockedCount), "已解锁", "#003527");
        leftGroup.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 竖向分割线
        View vDivider = new View(requireContext());
        vDivider.setLayoutParams(new LinearLayout.LayoutParams(1, dp2px(32)));
        vDivider.setBackgroundColor(Color.parseColor("#e0e0e0"));

        // 右：总数
        LinearLayout rightGroup = buildStatGroup(
                String.valueOf(totalCount), "总成就", "#707974");
        rightGroup.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(leftGroup);
        row.addView(vDivider);
        row.addView(rightGroup);
        return row;
    }

    private LinearLayout buildStatGroup(String number, String label, String numColor) {
        LinearLayout group = new LinearLayout(requireContext());
        group.setOrientation(LinearLayout.VERTICAL);
        group.setGravity(Gravity.CENTER);

        TextView tvNumber = new TextView(requireContext());
        tvNumber.setText(number);
        tvNumber.setTextSize(22);
        tvNumber.setTypeface(null, Typeface.BOLD);
        tvNumber.setTextColor(Color.parseColor(numColor));
        tvNumber.setGravity(Gravity.CENTER);

        TextView tvLabel = new TextView(requireContext());
        tvLabel.setText(label);
        tvLabel.setTextSize(11);
        tvLabel.setTextColor(Color.parseColor("#909aA3"));
        tvLabel.setGravity(Gravity.CENTER);

        group.addView(tvNumber);
        group.addView(tvLabel);
        return group;
    }

    /**
     * 单条成就行：
     *  已解锁 → 彩色图标 + 绿色标签
     *  未解锁 → 灰色图标 + 锁定遮罩 + 灰色标签
     */
    private View buildAchievementRow(Achievement ach, int index) {
        boolean unlocked = ach.isUnlocked();

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp2px(14), dp2px(14), dp2px(14), dp2px(14));
        row.setBackground(buildRoundedBg(
                unlocked ? "#f4fff9" : "#f8f8f8",
                dp2px(14)));
        LinearLayout.LayoutParams rowP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowP.bottomMargin = dp2px(10);
        row.setLayoutParams(rowP);

        // ── 图标区域 ──────────────────────────────────
        // 使用 FrameLayout 叠加锁定遮罩
        android.widget.FrameLayout iconFrame = new android.widget.FrameLayout(requireContext());
        iconFrame.setLayoutParams(
                new LinearLayout.LayoutParams(dp2px(48), dp2px(48)));

        // 圆形背景
        android.graphics.drawable.GradientDrawable circleBg =
                new android.graphics.drawable.GradientDrawable();
        circleBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circleBg.setColor(unlocked
                ? Color.parseColor("#e7eeff")
                : Color.parseColor("#ebebeb"));

        LinearLayout iconContainer = new LinearLayout(requireContext());
        iconContainer.setLayoutParams(new ViewGroup.LayoutParams(dp2px(48), dp2px(48)));
        iconContainer.setBackground(circleBg);
        iconContainer.setGravity(Gravity.CENTER);

        ImageView ivIcon = new ImageView(requireContext());
        ivIcon.setImageResource(ach.getIcon());
        ivIcon.setPadding(dp2px(10), dp2px(10), dp2px(10), dp2px(10));
        if (!unlocked) {
            // 未解锁图标灰度滤镜
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setSaturation(0);
            ivIcon.setColorFilter(
                    new android.graphics.ColorMatrixColorFilter(cm));
            ivIcon.setAlpha(0.4f);
        }
        iconContainer.addView(ivIcon);
        iconFrame.addView(iconContainer);

        // 未解锁时叠加锁图标
        if (!unlocked) {
            TextView tvLock = new TextView(requireContext());
            tvLock.setText("🔒");
            tvLock.setTextSize(10);
            tvLock.setGravity(Gravity.CENTER);
            android.widget.FrameLayout.LayoutParams lockP =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            lockP.gravity = Gravity.BOTTOM | Gravity.END;
            tvLock.setLayoutParams(lockP);
            iconFrame.addView(tvLock);
        }

        row.addView(iconFrame);

        // ── 文字区域 ──────────────────────────────────
        LinearLayout textGroup = new LinearLayout(requireContext());
        textGroup.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textP = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textP.leftMargin = dp2px(14);
        textGroup.setLayoutParams(textP);

        // 标题 + 状态标签行
        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvName = new TextView(requireContext());
        tvName.setText(ach.getName());
        tvName.setTextSize(15);
        tvName.setTextColor(unlocked
                ? Color.parseColor("#111c2d")
                : Color.parseColor("#b0b8c1"));
        tvName.setTypeface(null, unlocked ? Typeface.BOLD : Typeface.NORMAL);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 状态标签徽章
        TextView tvStatus = new TextView(requireContext());
        tvStatus.setText(unlocked ? "已解锁" : "未解锁");
        tvStatus.setTextSize(10);
        tvStatus.setTextColor(unlocked
                ? Color.parseColor("#003527")
                : Color.parseColor("#909aA3"));
        tvStatus.setBackground(buildTagBg(unlocked ? "#d4f7e8" : "#ebebeb", dp2px(6)));
        tvStatus.setPadding(dp2px(7), dp2px(3), dp2px(7), dp2px(3));

        titleRow.addView(tvName);
        titleRow.addView(tvStatus);
        textGroup.addView(titleRow);

        // 描述
        TextView tvDesc = new TextView(requireContext());
        tvDesc.setText(ach.getDescription());
        tvDesc.setTextSize(12);
        tvDesc.setTextColor(unlocked
                ? Color.parseColor("#505f76")
                : Color.parseColor("#c8c8c8"));
        tvDesc.setMaxLines(2);
        tvDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams descP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descP.topMargin = dp2px(4);
        tvDesc.setLayoutParams(descP);
        textGroup.addView(tvDesc);

        // 已解锁时显示进度条（满格）
        if (unlocked) {
            android.widget.ProgressBar pb = new android.widget.ProgressBar(
                    requireContext(), null,
                    android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100);
            pb.setProgress(100);
            LinearLayout.LayoutParams pbP = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp2px(4));
            pbP.topMargin = dp2px(6);
            pb.setLayoutParams(pbP);
            // 绿色进度色
            pb.getProgressDrawable().setColorFilter(
                    Color.parseColor("#003527"),
                    android.graphics.PorterDuff.Mode.SRC_IN);
            textGroup.addView(pb);
        }

        row.addView(textGroup);
        return row;
    }

    // ── 工具方法 ─────────────────────────────────────────

    private android.graphics.drawable.GradientDrawable buildRoundedBg(
            String colorHex, int cornerRadius) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(cornerRadius);
        d.setColor(Color.parseColor(colorHex));
        return d;
    }

    private android.graphics.drawable.GradientDrawable buildTagBg(
            String colorHex, int cornerRadius) {
        return buildRoundedBg(colorHex, cornerRadius);
    }

    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
