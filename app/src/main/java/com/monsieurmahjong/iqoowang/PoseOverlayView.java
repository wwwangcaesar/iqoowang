package com.monsieurmahjong.iqoowang;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.List;


import java.util.Arrays;
import java.util.List;

public class PoseOverlayView extends View {

    private PoseLandmarkerResult poseResult;
    private float scaleFactor = 1f;
    private int imageWidth = 0;
    private int imageHeight = 0;
    private boolean isFrontCamera = false;

    // 关键点画笔
    private final Paint pointPaint = new Paint() {{
        setColor(Color.RED);
        setStrokeWidth(10f);
        setStyle(Paint.Style.FILL);
        setAntiAlias(true);
    }};

    // 骨骼线画笔
    private final Paint linePaint = new Paint() {{
        setColor(Color.GREEN);
        setStrokeWidth(5f);
        setStyle(Paint.Style.STROKE);
        setAntiAlias(true);
    }};

    // MediaPipe Pose 33个关键点连接关系（Java 8 兼容写法）
    private final List<int[]> poseConnections = Arrays.asList(
            new int[]{0, 1}, new int[]{1, 2}, new int[]{2, 3}, new int[]{3, 7},
            new int[]{0, 4}, new int[]{4, 5}, new int[]{5, 6}, new int[]{6, 8},
            new int[]{9, 10}, new int[]{11, 12}, new int[]{11, 13}, new int[]{13, 15},
            new int[]{15, 17}, new int[]{15, 19}, new int[]{15, 21}, new int[]{17, 19},
            new int[]{12, 14}, new int[]{14, 16}, new int[]{16, 18}, new int[]{16, 20},
            new int[]{16, 22}, new int[]{18, 20}, new int[]{11, 23}, new int[]{12, 24},
            new int[]{23, 24}, new int[]{23, 25}, new int[]{24, 26}, new int[]{25, 27},
            new int[]{26, 28}, new int[]{27, 29}, new int[]{28, 30}, new int[]{29, 31},
            new int[]{30, 32}, new int[]{27, 31}, new int[]{28, 32}
    );

    public PoseOverlayView(Context context) {
        super(context);
    }

    public PoseOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PoseOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setPoseResult(
            PoseLandmarkerResult result,
            int imageWidth,
            int imageHeight,
            RunningMode runningMode,
            boolean isFrontCamera
    ) {
        this.poseResult = result;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.isFrontCamera = isFrontCamera;

        // 计算缩放因子
        this.scaleFactor = runningMode == RunningMode.LIVE_STREAM
                ? getWidth() / (float) imageHeight
                : getWidth() / (float) imageWidth;

        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (poseResult == null || poseResult.landmarks().isEmpty()) return;

        // 前置摄像头镜像处理
        if (isFrontCamera) {
            canvas.save();
            canvas.scale(-1f, 1f, getWidth() / 2f, getHeight() / 2f);
        }

        // 绘制所有检测到的人体
        for (List<NormalizedLandmark> landmarks : poseResult.landmarks()) {
            // 绘制骨骼线
            for (int[] connection : poseConnections) {
                NormalizedLandmark start = landmarks.get(connection[0]);
                NormalizedLandmark end = landmarks.get(connection[1]);

                canvas.drawLine(
                        start.x() * imageWidth * scaleFactor,
                        start.y() * imageHeight * scaleFactor,
                        end.x() * imageWidth * scaleFactor,
                        end.y() * imageHeight * scaleFactor,
                        linePaint
                );
            }

            // 绘制关键点
            for (NormalizedLandmark landmark : landmarks) {
                canvas.drawCircle(
                        landmark.x() * imageWidth * scaleFactor,
                        landmark.y() * imageHeight * scaleFactor,
                        pointPaint.getStrokeWidth() / 2,
                        pointPaint
                );
            }
        }

        // 恢复画布状态
        if (isFrontCamera) {
            canvas.restore();
        }
    }
}