package com.monsieurmahjong.iqoowang.utils;

import android.animation.ValueAnimator;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

public class AnimationUtils {
    // 私有构造函数，实现单例工具类（对应Kotlin object）
    private AnimationUtils() {}

    /**
     * 为 TextView 添加数字金额滚动动画（默认时长1000毫秒）
     * @param textView 目标 TextView
     * @param targetAmount 最终金额（单位：分）
     */
    public static void animateAmount(TextView textView, long targetAmount) {
        animateAmount(textView, targetAmount, 1000L);
    }

    /**
     * 为 TextView 添加数字金额滚动动画
     * @param textView 目标 TextView
     * @param targetAmount 最终金额（单位：分）
     * @param duration 动画时长（毫秒）
     */
    public static void animateAmount(TextView textView, long targetAmount, long duration) {
        // 从 0 滚动到目标金额（与原Kotlin完全一致的类型转换）
        ValueAnimator animator = ValueAnimator.ofInt(0, (int) targetAmount);
        animator.setDuration(duration);
        // 减速插值器：开始快，后面慢慢停下，视觉效果更好
        animator.setInterpolator(new DecelerateInterpolator());

        animator.addUpdateListener(animation -> {
            int animatedValue = (int) animation.getAnimatedValue();
            // 将分转换为元，并格式化为两位小数（与原Kotlin完全一致的计算逻辑）
            double displayValue = animatedValue / 100.0;
            textView.setText(String.format("¥ %.2f", displayValue));
        });

        animator.start();
    }
}
