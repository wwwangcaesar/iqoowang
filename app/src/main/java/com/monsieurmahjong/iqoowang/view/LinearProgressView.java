package com.monsieurmahjong.iqoowang.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import androidx.core.content.ContextCompat;

import com.monsieurmahjong.iqoowang.R;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import androidx.core.content.ContextCompat;
import com.monsieurmahjong.iqoowang.R; // 替换为你的实际包名

public class LinearProgressView extends View {

    // 核心参数（默认值匹配 DESIGN.md）
    private int progress = 0;
    private float progressHeight = dp2px(8);
    private int progressColor;
    private int trackColor;

    // 画笔（全圆角）
    private final Paint trackPaint = new Paint();
    private final Paint progressPaint = new Paint();

    public LinearProgressView(Context context) {
        super(context);
        init(null);
    }

    public LinearProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public LinearProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        // 绑定默认色值（DESIGN.md）
        progressColor = ContextCompat.getColor(getContext(), R.color.primary_container); // #064e3b
        trackColor = ContextCompat.getColor(getContext(), R.color.surface_container_low); // #f0f3ff

        // 读取自定义属性
        if (attrs != null) {
            TypedArray ta = getContext().obtainStyledAttributes(attrs, R.styleable.LinearProgressView);
            progress = ta.getInt(R.styleable.LinearProgressView_lpv_progress, 0);
            progressHeight = ta.getDimension(R.styleable.LinearProgressView_lpv_height, dp2px(8));
            progressColor = ta.getColor(R.styleable.LinearProgressView_lpv_progress_color, progressColor);
            trackColor = ta.getColor(R.styleable.LinearProgressView_lpv_track_color, trackColor);
            ta.recycle();
        }

        // 初始化画笔（全圆角）
        trackPaint.setAntiAlias(true);
        trackPaint.setStyle(Paint.Style.FILL);
        trackPaint.setColor(trackColor);

        progressPaint.setAntiAlias(true);
        progressPaint.setStyle(Paint.Style.FILL);
        progressPaint.setColor(progressColor);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 高度固定 8dp，宽度自适应
        int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        int height = resolveSize(dp2px(8), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cornerRadius = getHeight() / 2f;
        float progressWidth = getWidth() * progress / 100f;

        // 1. 绘制背景轨道（全圆角矩形）
        RectF trackRect = new RectF(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint);

        // 2. 绘制进度条（全圆角矩形）
        RectF progressRect = new RectF(0, 0, progressWidth, getHeight());
        canvas.drawRoundRect(progressRect, cornerRadius, cornerRadius, progressPaint);
    }

    // 对外设置进度（0~100）
    public void setProgress(int value) {
        this.progress = Math.max(0, Math.min(100, value));
        invalidate();
    }

    // ✅ 新增：动态设置进度条颜色（你需要的方法）
    public void setProgressColor(int color) {
        this.progressColor = color;
        progressPaint.setColor(color);
        invalidate(); // 立即重绘
    }

    // ✅ 可选：动态设置轨道颜色
    public void setTrackColor(int color) {
        this.trackColor = color;
        trackPaint.setColor(color);
        invalidate();
    }

    // dp 转 px
    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}