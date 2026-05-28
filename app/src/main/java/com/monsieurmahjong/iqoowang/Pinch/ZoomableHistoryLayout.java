package com.monsieurmahjong.iqoowang.Pinch;


import android.content.Context;
import android.util.AttributeSet;
import android.view.ScaleGestureDetector;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class ZoomableHistoryLayout extends FrameLayout {

    public enum ZoomLevel { DAY, WEEK, MONTH, YEAR }
    private ZoomLevel currentLevel = ZoomLevel.DAY;

    private ScaleGestureDetector scaleDetector;
    private float scaleFactor = 1.0f;
    private OnZoomLevelChangeListener zoomListener;

    public interface OnZoomLevelChangeListener {
        void onZoomLevelChanged(ZoomLevel newLevel, float progress);
    }

    public ZoomableHistoryLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        // 初始化双指缩放手势探测器
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                scaleFactor *= detector.getScaleFactor();
                // 防止缩放因数过大或过小
                scaleFactor = Math.max(0.5f, Math.min(scaleFactor, 2.0f));

                handleScaleProcess(scaleFactor);
                return true;
            }

            @Override
            public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                // 手势结束时，根据当前的缩放比例，平滑吸附到最近的层级
                snapToNearestLevel();
            }
        });
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // 接管多指触摸事件
        return ev.getPointerCount() > 1 || super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        return true;
    }

    private void handleScaleProcess(float factor) {
        // 根据 factor (0.5 - 2.0) 计算缩放进度，并通知子 View 做矩阵缩放和 Alpha 渐变
        if (zoomListener != null) {
            zoomListener.onZoomLevelChanged(currentLevel, factor);
        }

        // 动态对子组件进行整体缩放
        setScaleX(factor);
        setScaleY(factor);
    }

    private void snapToNearestLevel() {
        // 缩放松手后的吸附弹性动画
        float targetScale = 1.0f;
        ZoomLevel nextLevel = currentLevel;

        if (scaleFactor < 0.8f) {
            // 向下缩小一级
            nextLevel = getLowerLevel(currentLevel);
        } else if (scaleFactor > 1.3f) {
            // 向上放大一级
            nextLevel = getHigherLevel(currentLevel);
        }

        currentLevel = nextLevel;
        scaleFactor = 1.0f; // 重置因子

        // 执行平滑弹性动画恢复到 1.0 比例，并切换布局数据
        animate().scaleX(targetScale).scaleY(targetScale)
                .setDuration(300)
                .withEndAction(() -> {
                    if (zoomListener != null) {
                        zoomListener.onZoomLevelChanged(currentLevel, 1.0f);
                    }
                }).start();
    }

    private ZoomLevel getLowerLevel(ZoomLevel current) {
        if (current == ZoomLevel.DAY) return ZoomLevel.WEEK;
        if (current == ZoomLevel.WEEK) return ZoomLevel.MONTH;
        return ZoomLevel.YEAR;
    }

    private ZoomLevel getHigherLevel(ZoomLevel current) {
        if (current == ZoomLevel.YEAR) return ZoomLevel.MONTH;
        if (current == ZoomLevel.MONTH) return ZoomLevel.WEEK;
        return ZoomLevel.DAY;
    }

    public void setOnZoomLevelChangeListener(OnZoomLevelChangeListener listener) {
        this.zoomListener = listener;
    }
}

