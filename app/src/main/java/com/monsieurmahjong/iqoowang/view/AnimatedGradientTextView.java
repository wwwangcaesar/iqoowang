package com.monsieurmahjong.iqoowang.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import com.monsieurmahjong.iqoowang.R;

/**
 * 原生动态渐变 / 鎏金特效 TextView
 * 支持平滑流动的金属质感鎏金、火焰金橙、青绿霓虹等 Shader 动画效果。
 */
public class AnimatedGradientTextView extends AppCompatTextView {

    public static final int STYLE_GOLD = 0;
    public static final int STYLE_CYAN = 1;
    public static final int STYLE_FLAME = 2;
    public static final int STYLE_RAINBOW = 3;

    // 鎏金金属色谱：古金 -> 亮金 -> 象牙金白 -> 亮金 -> 古金
    private static final int[] COLORS_GOLD = {
            Color.parseColor("#996515"),
            Color.parseColor("#D4AF37"),
            Color.parseColor("#FFF8DC"),
            Color.parseColor("#FFD700"),
            Color.parseColor("#D4AF37"),
            Color.parseColor("#996515")
    };

    // 科技青绿霓虹色谱
    private static final int[] COLORS_CYAN = {
            Color.parseColor("#0083B0"),
            Color.parseColor("#00FFC6"),
            Color.parseColor("#E0FFFF"),
            Color.parseColor("#00FFC6"),
            Color.parseColor("#0083B0")
    };

    // 火焰金橙色谱
    private static final int[] COLORS_FLAME = {
            Color.parseColor("#D6291F"),
            Color.parseColor("#FF8A3D"),
            Color.parseColor("#FFC93C"),
            Color.parseColor("#FFF3CE"),
            Color.parseColor("#FF8A3D"),
            Color.parseColor("#D6291F")
    };

    // 彩虹全息色谱
    private static final int[] COLORS_RAINBOW = {
            Color.parseColor("#FF5B5B"),
            Color.parseColor("#FFB25B"),
            Color.parseColor("#FFF35B"),
            Color.parseColor("#5BFFA0"),
            Color.parseColor("#5B9CFF"),
            Color.parseColor("#FF5BD6"),
            Color.parseColor("#FF5B5B")
    };

    private LinearGradient linearGradient;
    private Matrix matrix;
    private ValueAnimator animator;
    private float translate = 0f;
    private int style = STYLE_GOLD;
    private int[] currentColors = COLORS_GOLD;
    private long duration = 3200;

    public AnimatedGradientTextView(@NonNull Context context) {
        this(context, null);
    }

    public AnimatedGradientTextView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AnimatedGradientTextView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        matrix = new Matrix();

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AnimatedGradientTextView);
            style = a.getInt(R.styleable.AnimatedGradientTextView_gradient_style, STYLE_GOLD);
            duration = a.getInt(R.styleable.AnimatedGradientTextView_gradient_speed, 3200);
            a.recycle();
        }

        setGradientStyle(style);
    }

    public void setGradientStyle(int style) {
        this.style = style;
        switch (style) {
            case STYLE_CYAN:
                currentColors = COLORS_CYAN;
                break;
            case STYLE_FLAME:
                currentColors = COLORS_FLAME;
                break;
            case STYLE_RAINBOW:
                currentColors = COLORS_RAINBOW;
                break;
            case STYLE_GOLD:
            default:
                currentColors = COLORS_GOLD;
                break;
        }
        setupGradient();
    }

    public void setCustomColors(int[] colors) {
        if (colors != null && colors.length >= 2) {
            this.currentColors = colors;
            setupGradient();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        setupGradient();
    }

    private void setupGradient() {
        int width = getMeasuredWidth();
        int height = getMeasuredHeight();
        if (width <= 0 || height <= 0) return;

        // 设置2倍宽度的线性渐变 Shader，配合 Matrix 平移产生循环扫光
        linearGradient = new LinearGradient(
                0, 0, width, 0,
                currentColors,
                null,
                Shader.TileMode.REPEAT
        );

        Paint paint = getPaint();
        paint.setShader(linearGradient);

        startAnimation(width);
    }

    private void startAnimation(int width) {
        if (animator != null) {
            animator.cancel();
        }

        animator = ValueAnimator.ofFloat(0f, width * 2f);
        animator.setDuration(duration);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(animation -> {
            translate = (float) animation.getAnimatedValue();
            if (matrix != null && linearGradient != null) {
                matrix.setTranslate(translate, 0);
                linearGradient.setLocalMatrix(matrix);
                invalidate();
            }
        });

        if (isAttachedToWindow() && getVisibility() == VISIBLE) {
            animator.start();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (matrix != null && linearGradient != null) {
            matrix.setTranslate(translate, 0);
            linearGradient.setLocalMatrix(matrix);
        }
        super.onDraw(canvas);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animator != null && !animator.isRunning() && getVisibility() == VISIBLE) {
            animator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
        }
    }

    @Override
    protected void onVisibilityChanged(@NonNull android.view.View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (animator != null) {
            if (visibility == VISIBLE) {
                if (!animator.isRunning()) animator.start();
            } else {
                animator.cancel();
            }
        }
    }
}
