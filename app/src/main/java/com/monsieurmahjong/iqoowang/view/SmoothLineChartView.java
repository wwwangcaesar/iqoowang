package com.monsieurmahjong.iqoowang.view;


import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

public class SmoothLineChartView extends View {

    private Paint linePaint;
    private Paint shadowPaint;
    private Paint dotPaint;
    private Paint textPaint;

    private float[] dataPoints = {0.1f, 0.35f, 0.2f, 0.55f, 0.25f, 0.85f, 0.6f}; // 模拟数据 (归一化0~1)
    private String[] xLabels = {"Mon", "Wed", "Fri", "Sun"};
    private float animProgress = 0f; // 动效进度

    public SmoothLineChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // 1. 主线条画笔 - 经典祖母绿 #064E3B
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(8f);
        linePaint.setColor(Color.parseColor("#064E3B"));
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        // 2. 投影层画笔
        shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setStyle(Paint.Style.FILL);

        // 3. 数据高亮小圆点画笔
        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setStyle(Paint.Style.FILL);

        // 4. 底部文字坐标轴
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#C4C7C9")); // tertiary-fixed-dim
        textPaint.setTextSize(30f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        startRevealAnimation();
    }

    public void setData(float[] newData) {
        this.dataPoints = newData;
        startRevealAnimation();
    }

    private void startRevealAnimation() {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(800); // 舒缓的渐入动效
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            animProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (dataPoints == null || dataPoints.length == 0) return;

        int paddingBottom = 60;
        int paddingSide = 40;
        float width = getWidth() - (paddingSide * 2);
        float height = getHeight() - paddingBottom - 40;

        float stepX = width / (dataPoints.length - 1);

        Path linePath = new Path();
        Path shadowPath = new Path();

        float firstX = paddingSide;
        float firstY = paddingSide + height * (1 - dataPoints[0] * animProgress);
        linePath.moveTo(firstX, firstY);
        shadowPath.moveTo(firstX, paddingSide + height);
        shadowPath.lineTo(firstX, firstY);

        // 使用贝塞尔曲线算法计算平滑点
        for (int i = 0; i < dataPoints.length - 1; i++) {
            float x1 = paddingSide + i * stepX;
            float y1 = paddingSide + height * (1 - dataPoints[i] * animProgress);
            float x2 = paddingSide + (i + 1) * stepX;
            float y2 = paddingSide + height * (1 - dataPoints[i + 1] * animProgress);

            float controlX1 = x1 + (x2 - x1) / 2;
            float controlY1 = y1;
            float controlX2 = x1 + (x2 - x1) / 2;
            float controlY2 = y2;

            linePath.cubicTo(controlX1, controlY1, controlX2, controlY2, x2, y2);
            shadowPath.cubicTo(controlX1, controlY1, controlX2, controlY2, x2, y2);
        }

        shadowPath.lineTo(paddingSide + width, paddingSide + height);
        shadowPath.close();

        // 注入渐变色渲染器 (Emerald 渐变到透明)
        LinearGradient gradient = new LinearGradient(0, 0, 0, getHeight(),
                Color.parseColor("#22064E3B"), Color.parseColor("#00064E3B"), Shader.TileMode.CLAMP);
        shadowPaint.setShader(gradient);

        // 绘制阴影和曲线
        canvas.drawPath(shadowPath, shadowPaint);
        canvas.drawPath(linePath, linePaint);

        // 绘制高亮锚点（对应原型图中的趋势焦点）
        if (dataPoints.length > 5) {
            float targetX = paddingSide + 5 * stepX;
            float targetY = paddingSide + height * (1 - dataPoints[5] * animProgress);
            dotPaint.setColor(Color.parseColor("#44064E3B"));
            canvas.drawCircle(targetX, targetY, 22f, dotPaint); // 外呼吸圈
            dotPaint.setColor(Color.parseColor("#064E3B"));
            canvas.drawCircle(targetX, targetY, 12f, dotPaint); // 内实心圆
        }

        // 绘制底部横坐标文字
        float labelStep = width / (xLabels.length - 1);
        for (int i = 0; i < xLabels.length; i++) {
            canvas.drawText(xLabels[i], paddingSide + (i * labelStep), getHeight() - 10, textPaint);
        }
    }
}

