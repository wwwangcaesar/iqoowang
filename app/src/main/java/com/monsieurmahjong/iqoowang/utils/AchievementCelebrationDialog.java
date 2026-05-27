package com.monsieurmahjong.iqoowang.utils;


import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.monsieurmahjong.iqoowang.view.AchievementCelebrationView;

/**
 * 成就庆祝弹窗
 *
 * 入场：整体从 0 缩放到 1，同时淡入，带 Overshoot 弹性
 * 离场：缩回 0 + 淡出
 * 点击空白处关闭
 */
public class AchievementCelebrationDialog extends DialogFragment {

    private static final String ARG_ICON      = "icon";
    private static final String ARG_NAME      = "name";
    private static final String ARG_DESC      = "desc";
    private static final String ARG_UNLOCKED  = "unlocked";

    private AchievementCelebrationView celebView;

    // ── 工厂方法 ─────────────────────────────────────────

    public static AchievementCelebrationDialog newInstance(
            int iconRes, String name, String desc, boolean unlocked) {
        AchievementCelebrationDialog f = new AchievementCelebrationDialog();
        Bundle b = new Bundle();
        b.putInt    (ARG_ICON,     iconRes);
        b.putString (ARG_NAME,     name);
        b.putString (ARG_DESC,     desc);
        b.putBoolean(ARG_UNLOCKED, unlocked);
        f.setArguments(b);
        return f;
    }

    // ── 视图 ─────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // 窗口：全屏透明（自己画背景）
        Dialog dialog = getDialog();
        if (dialog != null) {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
                w.setBackgroundDrawableResource(android.R.color.transparent);
                // 禁用系统窗口动画，由我们自己做
                w.setWindowAnimations(0);

                // 暗化背景
                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                lp.dimAmount = 0.75f;
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setAttributes(lp);
            }
        }

        // ── 容器：全屏 FrameLayout + 点击空白关闭 ─────
        FrameLayout root = new FrameLayout(requireContext());
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnClickListener(v -> dismissWithAnim());

        // ── 庆祝视图：固定尺寸，居中 ─────────────────
        celebView = new AchievementCelebrationView(requireContext());
        int viewW = dp(300);
        int viewH = dp(420);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(viewW, viewH);
        vp.gravity = android.view.Gravity.CENTER;
        celebView.setLayoutParams(vp);

        // 拦截点击，避免点庆祝视图时也关闭
        celebView.setOnClickListener(v -> { /* 消费事件 */ });

        // 圆角背景（半透明深色卡片）
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(24));
        bg.setColor(0xEE170938);  // 深色半透明
        celebView.setBackground(bg);
        celebView.setClipToOutline(true);
        celebView.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(24));
            }
        });

        // 配置动画内容
        Bundle args = getArguments();
        if (args != null) {
            int     iconRes  = args.getInt    (ARG_ICON,     -1);
            String  name     = args.getString (ARG_NAME,     "");
            String  desc     = args.getString (ARG_DESC,     "");
            boolean unlocked = args.getBoolean(ARG_UNLOCKED, true);
            if (unlocked) {
                celebView.setupUnlocked(iconRes, name, desc);
            } else {
                celebView.setupLocked(iconRes, name, desc);
            }
        }

        root.addView(celebView);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 入场动画：延迟一帧等布局完成后播放
        view.post(() -> {
            celebView.startAnim();
            playEnterAnim();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (celebView != null) celebView.stopAnim();
    }

    // ── 动画 ─────────────────────────────────────────────

    /** 入场：从 0 缩放到 1（带弹性）+ 淡入 */
    private void playEnterAnim() {
        celebView.setScaleX(0f);
        celebView.setScaleY(0f);
        celebView.setAlpha(0f);

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(celebView, View.SCALE_X, 0f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(celebView, View.SCALE_Y, 0f, 1f);
        ObjectAnimator alpha  = ObjectAnimator.ofFloat(celebView, View.ALPHA,   0f, 1f);

        scaleX.setDuration(480);
        scaleY.setDuration(480);
        alpha .setDuration(300);

        OvershootInterpolator overshoot = new OvershootInterpolator(1.4f);
        scaleX.setInterpolator(overshoot);
        scaleY.setInterpolator(overshoot);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(scaleX, scaleY, alpha);
        set.start();
    }

    /** 离场：缩回 + 淡出，完成后 dismiss */
    private void dismissWithAnim() {
        if (celebView == null) { dismiss(); return; }

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(celebView, View.SCALE_X, 1f, 0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(celebView, View.SCALE_Y, 1f, 0f);
        ObjectAnimator alpha  = ObjectAnimator.ofFloat(celebView, View.ALPHA,   1f, 0f);

        scaleX.setDuration(280);
        scaleY.setDuration(280);
        alpha .setDuration(280);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(scaleX, scaleY, alpha);
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (isAdded()) dismiss();
            }
        });
        set.start();
    }

    // ─────────────────────────────────────────────────────
    //  工具
    // ─────────────────────────────────────────────────────

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}

