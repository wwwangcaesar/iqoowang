package com.monsieurmahjong.iqoowang.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import androidx.core.content.ContextCompat;

import com.monsieurmahjong.iqoowang.R;

public class CircularProgressView extends View {

    // 默认值（匹配 DESIGN.md）
    private int progress = 0;
    private float strokeWidth = dp2px(8);
    private int progressColor;
    private int trackColor;
    private int textColor;
    private float textSize = sp2px(20);

    // 画笔
    private final Paint trackPaint = new Paint();
    private final Paint progressPaint = new Paint();
    private final Paint textPaint = new Paint();

    public CircularProgressView(Context context) {
        super(context);
        init(null);
    }

    public CircularProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public CircularProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        // 绑定默认色值（DESIGN.md）
        progressColor = ContextCompat.getColor(getContext(), R.color.primary_container);
        trackColor = ContextCompat.getColor(getContext(), R.color.surface_container_low);
        textColor = ContextCompat.getColor(getContext(), R.color.primary);

        // 读取自定义属性
        if (attrs != null) {
            TypedArray ta = getContext().obtainStyledAttributes(attrs, R.styleable.CircularProgressView);
            progress = ta.getInt(R.styleable.CircularProgressView_cpv_progress, 0);
            strokeWidth = ta.getDimension(R.styleable.CircularProgressView_cpv_stroke_width, dp2px(8));
            progressColor = ta.getColor(R.styleable.CircularProgressView_cpv_progress_color, progressColor);
            trackColor = ta.getColor(R.styleable.CircularProgressView_cpv_track_color, trackColor);
            textColor = ta.getColor(R.styleable.CircularProgressView_cpv_text_color, textColor);
            textSize = ta.getDimension(R.styleable.CircularProgressView_cpv_text_size, sp2px(20));
            ta.recycle();
        }

        // 初始化画笔
        trackPaint.setAntiAlias(true);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setStrokeWidth(strokeWidth);
        trackPaint.setColor(trackColor);

        progressPaint.setAntiAlias(true);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setStrokeWidth(strokeWidth);
        progressPaint.setColor(progressColor);

        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("Inter", Typeface.BOLD));
        textPaint.setColor(textColor);
        textPaint.setTextSize(textSize);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 宽高一致，默认 96dp
        int size = resolveSize(dp2px(96), widthMeasureSpec);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float radius = (getWidth() - strokeWidth) / 2f;

        // 1. 绘制背景轨道
        canvas.drawCircle(centerX, centerY, radius, trackPaint);

        // 2. 绘制进度弧（从顶部 -90° 开始）
        float sweepAngle = progress * 3.6f;
        RectF rectF = new RectF(centerX - radius, centerY - radius,
                centerX + radius, centerY + radius);
        canvas.drawArc(rectF, -90f, sweepAngle, false, progressPaint);

        // 3. 绘制居中百分比文字
        canvas.drawText(progress + "%", centerX, centerY + textSize / 3, textPaint);
    }

    // 对外设置进度（0~100）
    public void setProgress(int value) {
        this.progress = Math.max(0, Math.min(100, value));
        invalidate();
    }

    // 动态设置进度弧颜色（用于超预算等警示状态）
    public void setProgressColor(int color) {
        this.progressColor = color;
        progressPaint.setColor(color);
        invalidate();
    }

    // 动态设置轨道颜色
    public void setTrackColor(int color) {
        this.trackColor = color;
        trackPaint.setColor(color);
        invalidate();
    }

    // dp 转 px
    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    // sp 转 px
    private float sp2px(int sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }
}
