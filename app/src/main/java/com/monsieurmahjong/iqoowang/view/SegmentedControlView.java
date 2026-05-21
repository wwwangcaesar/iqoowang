package com.monsieurmahjong.iqoowang.view;

import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class SegmentedControlView extends FrameLayout {

    private View indicatorView;
    private LinearLayout textContainer;
    private final List<TextView> tabs = new ArrayList<>();

    private int currentSelectedIndex = 0;
    private int tabWidth = 0;

    private final int COLOR_ACTIVE = 0xFF111C2D;   // 设计稿 on-surface
    private final int COLOR_INACTIVE = 0xFF505F76; // 设计稿 secondary

    public interface OnTabSelectedListener {
        void onTabSelected(int index, String text);
    }

    private OnTabSelectedListener listener;

    public void setOnTabSelectedListener(OnTabSelectedListener listener) {
        this.listener = listener;
    }

    public SegmentedControlView(@NonNull Context context) {
        this(context, null);
    }

    public SegmentedControlView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // 1. 初始化底衬跑道背景
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(Color.parseColor("#F0F3FF")); // surface-container-low
        trackBg.setCornerRadius(dp2px(14));
        setBackground(trackBg);
        int padding = dp2px(4);
        setPadding(padding, padding, padding, padding);

        // 2. 初始化滑块 (Indicator)
        indicatorView = new View(getContext());
        GradientDrawable indicatorBg = new GradientDrawable();
        indicatorBg.setColor(0xFFFFFFFF); // 纯白滑块
        indicatorBg.setCornerRadius(dp2px(12));
        indicatorView.setBackground(indicatorBg);
        indicatorView.setElevation(dp2px(1));
        addView(indicatorView, new FrameLayout.LayoutParams(100, ViewGroup.LayoutParams.MATCH_PARENT));

        // 3. 初始化文本容器
        textContainer = new LinearLayout(getContext());
        textContainer.setOrientation(LinearLayout.HORIZONTAL);
        textContainer.setElevation(dp2px(2));
        addView(textContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 4. 添加默认的周、月、年
        String[] defaultTitles = {"周", "月", "年"};
        for (int i = 0; i < defaultTitles.length; i++) {
            final int index = i;
            TextView tv = new TextView(getContext());
            tv.setText(defaultTitles[i]);
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setTextColor(i == 0 ? COLOR_ACTIVE : COLOR_INACTIVE);
            tv.setTypeface(i == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
            textContainer.addView(tv, lp);
            tabs.add(tv);

            tv.setOnClickListener(v -> setCurrentSelection(index, true));
        }

        // 5. 测量并重置滑块宽度
        getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int totalWidth = getWidth() - getPaddingLeft() - getPaddingRight();
                tabWidth = totalWidth / tabs.size();

                ViewGroup.LayoutParams lp = indicatorView.getLayoutParams();
                lp.width = tabWidth;
                indicatorView.setLayoutParams(lp);
                indicatorView.setTranslationX(currentSelectedIndex * tabWidth);
            }
        });
    }

    public void setCurrentSelection(int index, boolean animate) {
        if (index < 0 || index >= tabs.size() || index == currentSelectedIndex) return;

        if (animate && tabWidth > 0) {
            float targetX = index * tabWidth;
            Interpolator interpolator = AnimationUtils.loadInterpolator(getContext(), android.R.interpolator.fast_out_slow_in);

            ObjectAnimator slide = ObjectAnimator.ofFloat(indicatorView, "translationX", indicatorView.getTranslationX(), targetX);
            slide.setDuration(320);
            slide.setInterpolator(interpolator);

            TextView prevTv = tabs.get(currentSelectedIndex);
            TextView targetTv = tabs.get(index);

            ObjectAnimator fadeOut = ObjectAnimator.ofInt(prevTv, "textColor", prevTv.getCurrentTextColor(), COLOR_INACTIVE);
            ObjectAnimator fadeIn = ObjectAnimator.ofInt(targetTv, "textColor", targetTv.getCurrentTextColor(), COLOR_ACTIVE);
            fadeOut.setEvaluator(new ArgbEvaluator());
            fadeIn.setEvaluator(new ArgbEvaluator());
            fadeOut.setDuration(260);
            fadeIn.setDuration(260);

            AnimatorSet set = new AnimatorSet();
            set.playTogether(slide, fadeOut, fadeIn);
            set.start();

            prevTv.setTypeface(Typeface.DEFAULT);
            targetTv.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            indicatorView.setTranslationX(index * tabWidth);
            for (int i = 0; i < tabs.size(); i++) {
                tabs.get(i).setTextColor(i == index ? COLOR_ACTIVE : COLOR_INACTIVE);
                tabs.get(i).setTypeface(i == index ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
        }

        currentSelectedIndex = index;
        if (listener != null) {
            listener.onTabSelected(index, tabs.get(index).getText().toString());
        }
    }

    private int dp2px(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}

