package com.monsieurmahjong.iqoowang.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

public class CoolBudgetSeekBar extends View {

    private int minBudget = 1000;
    private int maxBudget = 20000;
    private int currentBudget = 5000;

    private Paint trackPaint;
    private Paint progressPaint;
    private Paint thumbPaint;
    private Paint emojiPaint;
    private Paint flamePaint;

    private int trackHeight = 24;
    private int thumbRadius = 48; // 滑块整体放大，适配更大的表情

    private OnBudgetChangeListener listener;
    private boolean isDragging = false;

    // 震动+闪烁+火焰动画参数
    private long lastAnimTime;
    private float shakeOffsetX;
    private float shakeOffsetY;
    private int flashAlpha;
    private float flamePulse; // 火焰脉动系数

    public CoolBudgetSeekBar(Context context) {
        super(context);
        init();
    }

    public CoolBudgetSeekBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setColor(Color.parseColor("#F0F3FF"));

        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setShadowLayer(16, 0, 8, Color.parseColor("#40000000")); // 阴影增强
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        emojiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        emojiPaint.setStyle(Paint.Style.STROKE);
        emojiPaint.setStrokeCap(Paint.Cap.ROUND);
        emojiPaint.setStrokeWidth(5f); // 表情线条加粗，适配放大后的尺寸

        flamePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        flamePaint.setStyle(Paint.Style.FILL);

        lastAnimTime = System.currentTimeMillis();
    }

    public void setConfig(int min, int max, int current) {
        this.minBudget = min;
        this.maxBudget = max;
        this.currentBudget = Math.max(min, Math.min(max, current));
        invalidate();
    }

    public int getProgress() {
        return currentBudget;
    }

    public void setOnBudgetChangeListener(OnBudgetChangeListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = thumbRadius * 2 + 100; // 大幅增加顶部空间，容纳更大的火焰
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        int paddingLeft = thumbRadius + 24;
        int paddingRight = thumbRadius + 24;
        int usableWidth = width - paddingLeft - paddingRight;

        float cy = height / 2f + 20;
        float progressRatio = (float) (currentBudget - minBudget) / (maxBudget - minBudget);
        float thumbX = paddingLeft + (progressRatio * usableWidth);

        // 1. 绘制背景轨道
        RectF trackRect = new RectF(paddingLeft, cy - trackHeight / 2f, width - paddingRight, cy + trackHeight / 2f);
        canvas.drawRoundRect(trackRect, trackHeight / 2f, trackHeight / 2f, trackPaint);

        // 2. 渐变色（低绿高红）
        int startColor = Color.parseColor("#2B6954");
        int midColor = Color.parseColor("#E28413");
        int endColor = Color.parseColor("#BA1A1A");

        int currentProgressColor;
        if (progressRatio < 0.5f) {
            currentProgressColor = interpolateColor(startColor, midColor, progressRatio * 2f);
        } else {
            currentProgressColor = interpolateColor(midColor, endColor, (progressRatio - 0.5f) * 2f);
        }

        // 3. 绘制进度条
        RectF progressRect = new RectF(paddingLeft, cy - trackHeight / 2f, thumbX, cy + trackHeight / 2f);
        progressPaint.setColor(currentProgressColor);
        canvas.drawRoundRect(progressRect, trackHeight / 2f, trackHeight / 2f, progressPaint);

        // 4. 暴怒状态：震动+闪烁+火焰三重特效叠加
        boolean isFury = progressRatio > 0.85f;
        float shakeRatio = 0f;
        if (isFury) {
            updateAllAnimations();
            shakeRatio = 1f;
            postInvalidateOnAnimation(); // 持续刷新所有动画
        }

        // 应用震动偏移
        float finalThumbX = thumbX + shakeOffsetX * shakeRatio;
        float finalThumbY = cy + shakeOffsetY * shakeRatio;

        // 5. 绘制火焰（在滑块下方绘制，避免被滑块遮挡）
        if (isFury) {
            drawEnhancedFlame(canvas, finalThumbX, finalThumbY - thumbRadius);
        }

        // 6. 绘制滑块外壳（带闪烁）
        thumbPaint.setColor(Color.WHITE);
        thumbPaint.setAlpha(flashAlpha);
        canvas.drawCircle(finalThumbX, finalThumbY, thumbRadius, thumbPaint);

        // 内圈情绪底色
        thumbPaint.setColor(currentProgressColor);
        thumbPaint.setAlpha(40);
        canvas.drawCircle(finalThumbX, finalThumbY, thumbRadius - 6, thumbPaint);

        // 7. 绘制放大后的情绪表情
        drawEnlargedMoodEmoji(canvas, finalThumbX, finalThumbY, progressRatio);
    }

    /**
     * 更新所有动画参数（震动+闪烁+火焰脉动）
     */
    private void updateAllAnimations() {
        long currentTime = System.currentTimeMillis();
        long deltaTime = currentTime - lastAnimTime;
        lastAnimTime = currentTime;

        // 震动参数（可调整）
        float shakeSpeed = 100f;
        float shakeAmplitude = 10f;
        shakeOffsetX = (float) (Math.sin(currentTime / shakeSpeed) * shakeAmplitude);
        shakeOffsetY = (float) (Math.cos(currentTime / (shakeSpeed * 1.1)) * shakeAmplitude);

        // 滑块闪烁参数
        float flashSpeed = 70f;
        flashAlpha = (int) (170 + Math.abs(Math.sin(currentTime / flashSpeed)) * 85);

        // 火焰脉动参数（独立于滑块闪烁，更有层次感）
        float flamePulseSpeed = 60f;
        flamePulse = (float) (Math.sin(currentTime / flamePulseSpeed) * 8);
    }

    /**
     * 增强版火焰效果（尺寸增大50%+多层闪烁）
     */
    private void drawEnhancedFlame(Canvas canvas, float fx, float fy) {
        // 外层大火（红色，最大最暗）
        flamePaint.setColor(Color.parseColor("#FF3D00"));
        flamePaint.setAlpha((int) (200 + flamePulse * 3));
        canvas.drawCircle(fx, fy - 25, 28 + flamePulse, flamePaint);

        // 中层中火（橙色）
        flamePaint.setColor(Color.parseColor("#FF9100"));
        flamePaint.setAlpha((int) (220 + flamePulse * 2));
        canvas.drawCircle(fx, fy - 18, 20 + flamePulse * 0.8f, flamePaint);

        // 内层小火（黄色，最亮）
        flamePaint.setColor(Color.parseColor("#FFEA00"));
        flamePaint.setAlpha((int) (240 + flamePulse));
        canvas.drawCircle(fx, fy - 12, 12 + flamePulse * 0.5f, flamePaint);

        // 核心火星（白色，快速闪烁）
        flamePaint.setColor(Color.WHITE);
        flamePaint.setAlpha((int) (Math.abs(Math.sin(System.currentTimeMillis() / 40f)) * 255));
        canvas.drawCircle(fx, fy - 8, 4, flamePaint);
    }

    /**
     * 放大1.3倍后的情绪表情绘制
     */
    private void drawEnlargedMoodEmoji(Canvas canvas, float cx, float cy, float ratio) {
        if (ratio <= 0.25f) { // 😊 微笑
            emojiPaint.setColor(Color.parseColor("#2B6954"));
            // 弯弯眼（放大）
            canvas.drawArc(cx - 24, cy - 18, cx - 8, cy - 2, 180, 180, false, emojiPaint);
            canvas.drawArc(cx + 8, cy - 18, cx + 24, cy - 2, 180, 180, false, emojiPaint);
            // 大笑嘴角
            canvas.drawArc(cx - 20, cy - 4, cx + 20, cy + 22, 0, 180, false, emojiPaint);
        } else if (ratio <= 0.5f) { // 😐 平静
            emojiPaint.setColor(Color.parseColor("#505F76"));
            // 豆豆眼
            canvas.drawCircle(cx - 15, cy - 8, 4, emojiPaint);
            canvas.drawCircle(cx + 15, cy - 8, 4, emojiPaint);
            // 平淡线
            canvas.drawLine(cx - 18, cy + 10, cx + 18, cy + 10, emojiPaint);
        } else if (ratio <= 0.7f) { // 😟 急躁
            emojiPaint.setColor(Color.parseColor("#E28413"));
            // 八字眉
            canvas.drawLine(cx - 20, cy - 20, cx - 8, cy - 15, emojiPaint);
            canvas.drawLine(cx + 8, cy - 15, cx + 20, cy - 20, emojiPaint);
            // 眼睛
            canvas.drawCircle(cx - 12, cy - 5, 4, emojiPaint);
            canvas.drawCircle(cx + 12, cy - 5, 4, emojiPaint);
            // 撇嘴
            canvas.drawArc(cx - 15, cy + 12, cx + 15, cy + 28, 180, 180, false, emojiPaint);
        } else if (ratio <= 0.85f) { // 😠 愤怒
            emojiPaint.setColor(Color.parseColor("#BA1A1A"));
            // 倒八字眉
            canvas.drawLine(cx - 20, cy - 15, cx - 8, cy - 20, emojiPaint);
            canvas.drawLine(cx + 8, cy - 20, cx + 20, cy - 15, emojiPaint);
            // 眼睛
            canvas.drawCircle(cx - 12, cy - 5, 4, emojiPaint);
            canvas.drawCircle(cx + 12, cy - 5, 4, emojiPaint);
            // 咬牙嘴
            emojiPaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(cx - 15, cy + 10, cx + 15, cy + 18, 5, 5, emojiPaint);
            emojiPaint.setStyle(Paint.Style.STROKE);
        } else { // 🤬 暴怒
            emojiPaint.setColor(Color.parseColor("#BA1A1A"));
            // 抓狂眉头
            canvas.drawLine(cx - 20, cy - 12, cx - 8, cy - 20, emojiPaint);
            canvas.drawLine(cx + 8, cy - 20, cx + 20, cy - 12, emojiPaint);
            // 叉叉眼
            canvas.drawLine(cx - 18, cy - 8, cx - 8, cy + 2, emojiPaint);
            canvas.drawLine(cx - 8, cy - 8, cx - 18, cy + 2, emojiPaint);
            canvas.drawLine(cx + 8, cy - 8, cx + 18, cy + 2, emojiPaint);
            canvas.drawLine(cx + 18, cy - 8, cx + 8, cy + 2, emojiPaint);
            // 大张呐喊嘴
            emojiPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy + 16, 13, emojiPaint);
            emojiPaint.setStyle(Paint.Style.STROKE);
        }
    }

    private int interpolateColor(int colorStart, int colorEnd, float fraction) {
        float[] startHsv = new float[3];
        float[] endHsv = new float[3];
        Color.colorToHSV(colorStart, startHsv);
        Color.colorToHSV(colorEnd, endHsv);
        for (int i = 0; i < 3; i++) {
            endHsv[i] = startHsv[i] + ((endHsv[i] - startHsv[i]) * fraction);
        }
        return Color.HSVToColor(endHsv);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();
        int width = getWidth();
        int paddingLeft = thumbRadius + 24;
        int paddingRight = thumbRadius + 24;
        int usableWidth = width - paddingLeft - paddingRight;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                isDragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
            case MotionEvent.ACTION_MOVE:
                float x = event.getX();
                float relativeX = Math.max(paddingLeft, Math.min(x, width - paddingRight)) - paddingLeft;
                float ratio = relativeX / usableWidth;
                currentBudget = minBudget + (int) (ratio * (maxBudget - minBudget));
                currentBudget = (currentBudget / 100) * 100;
                invalidate();
                if (listener != null) {
                    listener.getBudgetChanged(currentBudget);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                if (listener != null) {
                    listener.onBudgetChanged(currentBudget);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    public interface OnBudgetChangeListener {
        void getBudgetChanged(int budget);
        void onBudgetChanged(int budget);
    }
}