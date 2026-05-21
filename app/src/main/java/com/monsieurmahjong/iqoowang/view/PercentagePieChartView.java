package com.monsieurmahjong.iqoowang.view;


import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AnticipateOvershootInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class PercentagePieChartView extends View {

    private Paint piePaint;
    private Paint centerCirclePaint;
    private Paint textPaint;
    private final RectF rectF = new RectF();

    private List<PieEntry> entries = new ArrayList<>();
    private float animatedSweepAngle = 0f;

    public static class PieEntry {
        float percentage; // 0 ~ 1.0
        int color;

        public PieEntry(float percentage, int color) {
            this.percentage = percentage;
            this.color = color;
        }
    }

    public PercentagePieChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        piePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        piePaint.setStyle(Paint.Style.STROKE);
        piePaint.setStrokeWidth(50f); // 圆环厚度

        centerCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerCirclePaint.setStyle(Paint.Style.FILL);
        centerCirclePaint.setColor(Color.WHITE);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // 赋一组默认演示色（Food: 40% [墨绿], Shopping: 25% [蓝灰], Transport: 15% [浅灰], 剩余为空白）
        entries.add(new PieEntry(0.40f, Color.parseColor("#003527"))); // primary
        entries.add(new PieEntry(0.25f, Color.parseColor("#505F76"))); // secondary
        entries.add(new PieEntry(0.15f, Color.parseColor("#B7C8E1"))); // dim
        entries.add(new PieEntry(0.20f, Color.parseColor("#E7EEFF"))); // surface-container

        startAnimate();
    }

    public void setEntries(List<PieEntry> newEntries) {
        this.entries = newEntries;
        startAnimate();
    }

    private void startAnimate() {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(950);
        animator.setInterpolator(new AnticipateOvershootInterpolator(0.6f)); // 微弱超越回弹，极具动感
        animator.addUpdateListener(animation -> {
            animatedSweepAngle = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float size = Math.min(getWidth(), getHeight());
        float padding = 40f;
        rectF.set(padding, padding, size - padding, size - padding);

        float startAngle = -90f; // 从正上方开始画
        float globalProgress = animatedSweepAngle / 360f;

        for (PieEntry entry : entries) {
            float sweepAngle = entry.percentage * 360f * globalProgress;
            piePaint.setColor(entry.color);
            canvas.drawArc(rectF, startAngle, sweepAngle, false, piePaint);
            startAngle += entry.percentage * 360f;
        }

        // 绘制中央空心文字区域
        float cx = size / 2f;
        float cy = size / 2f;

        textPaint.setColor(Color.parseColor("#505F76"));
        textPaint.setTextSize(32f);
        canvas.drawText("TOP", cx, cy - 15, textPaint);

        textPaint.setColor(Color.parseColor("#111C2D"));
        textPaint.setTextSize(48f);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        canvas.drawText("Food", cx, cy + 30, textPaint);
    }
}

