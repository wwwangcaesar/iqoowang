package com.monsieurmahjong.iqoowang.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

public class ZoomableRecyclerView extends RecyclerView {

    private ScaleGestureDetector scaleDetector;
    private OnZoomGestureListener zoomListener;
    private boolean isScaling = false;

    // 重新定义清晰的意图接口
    public interface OnZoomGestureListener {
        void onZoomOut(); // 捏合缩小：退回上一级
        void onZoomIn(int focusedPosition); // 张开放大：进入下一级，并告知中心落点在哪
    }

    public ZoomableRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                isScaling = true;
                return true; // 必须返回 true 以持续接收缩放事件
            }

            @Override
            public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                isScaling = false;
                float scaleFactor = detector.getScaleFactor();

                if (scaleFactor < 0.85f) {
                    // 【动作：双指捏合缩小】 -> 触发全局回退
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    if (zoomListener != null) {
                        zoomListener.onZoomOut();
                    }
                } else if (scaleFactor > 1.15f) {
                    // 【动作：双指张开放大】 -> 探测双指中心点对应的格子
                    View child = findChildViewUnder(detector.getFocusX(), detector.getFocusY());
                    if (child != null) {
                        int focusedPosition = getChildAdapterPosition(child);
                        if (focusedPosition != NO_POSITION) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                            if (zoomListener != null) {
                                zoomListener.onZoomIn(focusedPosition);
                            }
                        }
                    }
                }
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        // 如果正在进行双指缩放，就吞掉原生的上下滑动事件，防止画面乱跑
        if (isScaling || e.getPointerCount() > 1) {
            return true;
        }
        return super.onTouchEvent(e);
    }

    public void setOnZoomGestureListener(OnZoomGestureListener listener) {
        this.zoomListener = listener;
    }
}
