package com.monsieurmahjong.iqoowang;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import androidx.camera.core.ImageProxy;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.core.OutputHandler;

import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity {

    private PreviewView previewView;
    private PoseOverlayView poseOverlayView;
    private TextView tvFps;
    private TextView tvModelInfo;
    private Button btnSettings;

    private PoseLandmarker poseLandmarker;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;

    // 配置参数
    private String currentModel = "pose_landmarker_heavy.task";
    private Delegate currentDelegate = Delegate.GPU;
    private int numPoses = 1;
    private float detectionConfidence = 0.5f;
    private float trackingConfidence = 0.5f;
    private boolean useFrontCamera = false;
    private Size targetResolution = new Size(1920, 1080);

    // 帧率计算
    private int frameCount = 0;
    private long lastFpsUpdateTime = System.currentTimeMillis();

    // SharedPreferences 保存配置
    private SharedPreferences sharedPreferences;

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;
    private static final String PREFS_NAME = "PoseSettings";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化视图
        previewView = findViewById(R.id.previewView);
        poseOverlayView = findViewById(R.id.poseOverlayView);
        tvFps = findViewById(R.id.tvFps);
        tvModelInfo = findViewById(R.id.tvModelInfo);
        btnSettings = findViewById(R.id.btnSettings);

        // 初始化相机执行器（使用4线程池，充分利用骁龙8 Gen2多核性能）
        cameraExecutor = Executors.newFixedThreadPool(4);

        // 加载保存的配置
        loadSettings();

        // 设置配置按钮点击事件
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });

        // 检查相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            setupPoseLandmarker();
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST_CODE
            );
        }
    }

    // 加载保存的配置
    private void loadSettings() {
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentModel = sharedPreferences.getString("model", "pose_landmarker_heavy.task");
        currentDelegate = Delegate.valueOf(sharedPreferences.getString("delegate", "GPU"));
        numPoses = sharedPreferences.getInt("numPoses", 1);
        detectionConfidence = sharedPreferences.getFloat("detectionConfidence", 0.5f);
        trackingConfidence = sharedPreferences.getFloat("trackingConfidence", 0.5f);
        useFrontCamera = sharedPreferences.getBoolean("useFrontCamera", false);

        int width = sharedPreferences.getInt("resolutionWidth", 1920);
        int height = sharedPreferences.getInt("resolutionHeight", 1080);
        targetResolution = new Size(width, height);

        updateModelInfoText();
    }

    // 保存配置
    private void saveSettings() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("model", currentModel);
        editor.putString("delegate", currentDelegate.name());
        editor.putInt("numPoses", numPoses);
        editor.putFloat("detectionConfidence", detectionConfidence);
        editor.putFloat("trackingConfidence", trackingConfidence);
        editor.putBoolean("useFrontCamera", useFrontCamera);
        editor.putInt("resolutionWidth", targetResolution.getWidth());
        editor.putInt("resolutionHeight", targetResolution.getHeight());
        editor.apply();
    }

    // 更新模型信息显示
    private void updateModelInfoText() {
        String modelName;
        switch (currentModel) {
            case "pose_landmarker_lite.task":
                modelName = "轻量";
                break;
            case "pose_landmarker_full.task":
                modelName = "标准";
                break;
            default:
                modelName = "重型";
                break;
        }
        String delegateName = currentDelegate == Delegate.GPU ? "GPU" : "CPU";
        tvModelInfo.setText(String.format("模型: %s | %s", modelName, delegateName));
    }

    // 显示配置对话框
    private void showSettingsDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_settings, null);

        // 初始化对话框控件
        final Spinner spinnerModel = dialogView.findViewById(R.id.spinnerModel);
        final Spinner spinnerDelegate = dialogView.findViewById(R.id.spinnerDelegate);
        final Spinner spinnerNumPoses = dialogView.findViewById(R.id.spinnerNumPoses);
        final SeekBar seekBarDetection = dialogView.findViewById(R.id.seekBarDetectionConfidence);
        final TextView tvDetection = dialogView.findViewById(R.id.tvDetectionConfidence);
        final SeekBar seekBarTracking = dialogView.findViewById(R.id.seekBarTrackingConfidence);
        final TextView tvTracking = dialogView.findViewById(R.id.tvTrackingConfidence);
        final Switch switchFrontCamera = dialogView.findViewById(R.id.switchFrontCamera);
        final Spinner spinnerResolution = dialogView.findViewById(R.id.spinnerResolution);

        // 设置当前值
        switch (currentModel) {
            case "pose_landmarker_lite.task":
                spinnerModel.setSelection(0);
                break;
            case "pose_landmarker_full.task":
                spinnerModel.setSelection(1);
                break;
            default:
                spinnerModel.setSelection(2);
                break;
        }
        spinnerDelegate.setSelection(currentDelegate == Delegate.GPU ? 0 : 1);
        spinnerNumPoses.setSelection(numPoses - 1);
        seekBarDetection.setProgress((int) (detectionConfidence * 100));
        tvDetection.setText(String.format("检测置信度: %.1f", detectionConfidence));
        seekBarTracking.setProgress((int) (trackingConfidence * 100));
        tvTracking.setText(String.format("跟踪置信度: %.1f", trackingConfidence));
        switchFrontCamera.setChecked(useFrontCamera);

        if (targetResolution.getWidth() == 640) {
            spinnerResolution.setSelection(0);
        } else if (targetResolution.getWidth() == 1280) {
            spinnerResolution.setSelection(1);
        } else {
            spinnerResolution.setSelection(2);
        }

        // 设置SeekBar监听
        seekBarDetection.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                detectionConfidence = progress / 100f;
                tvDetection.setText(String.format("检测置信度: %.1f", detectionConfidence));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekBarTracking.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                trackingConfidence = progress / 100f;
                tvTracking.setText(String.format("跟踪置信度: %.1f", trackingConfidence));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 创建对话框
        new AlertDialog.Builder(this)
                .setTitle("配置参数")
                .setView(dialogView)
                .setPositiveButton("应用", new AlertDialog.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        // 获取新的配置值
                        int modelIndex = spinnerModel.getSelectedItemPosition();
                        switch (modelIndex) {
                            case 0:
                                currentModel = "pose_landmarker_lite.task";
                                break;
                            case 1:
                                currentModel = "pose_landmarker_full.task";
                                break;
                            default:
                                currentModel = "pose_landmarker_heavy.task";
                                break;
                        }

                        int delegateIndex = spinnerDelegate.getSelectedItemPosition();
                        currentDelegate = delegateIndex == 0 ? Delegate.GPU : Delegate.CPU;

                        numPoses = spinnerNumPoses.getSelectedItemPosition() + 1;

                        boolean oldFrontCamera = useFrontCamera;
                        useFrontCamera = switchFrontCamera.isChecked();

                        int resolutionIndex = spinnerResolution.getSelectedItemPosition();
                        Size oldResolution = targetResolution;
                        switch (resolutionIndex) {
                            case 0:
                                targetResolution = new Size(640, 480);
                                break;
                            case 1:
                                targetResolution = new Size(1280, 720);
                                break;
                            default:
                                targetResolution = new Size(1920, 1080);
                                break;
                        }

                        // 保存配置
                        saveSettings();
                        updateModelInfoText();

                        // 重新初始化检测器
                        if (poseLandmarker != null) {
                            poseLandmarker.close();
                        }
                        setupPoseLandmarker();

                        // 如果摄像头或分辨率改变，重启相机
                        if (oldFrontCamera != useFrontCamera || !oldResolution.equals(targetResolution)) {
                            if (cameraProvider != null) {
                                cameraProvider.unbindAll();
                            }
                            startCamera();
                        }

                        Toast.makeText(MainActivity.this, "配置已应用", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 初始化MediaPipe Pose检测器（完全修正版）
    private void setupPoseLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(currentModel)
                    .setDelegate(currentDelegate)
                    .build();

            // 正确的ResultListener导入：com.google.mediapipe.tasks.core.OutputHandler.ResultListener
            OutputHandler.ResultListener<PoseLandmarkerResult, MPImage> resultListener =
                    new OutputHandler.ResultListener<PoseLandmarkerResult, MPImage>() {
                        @Override
                        public void run(PoseLandmarkerResult result, MPImage input) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    poseOverlayView.setPoseResult(
                                            result,
                                            input.getWidth(),
                                            input.getHeight(),
                                            RunningMode.LIVE_STREAM,
                                            useFrontCamera
                                    );

                                    // 更新帧率
                                    frameCount++;
                                    long currentTime = System.currentTimeMillis();
                                    if (currentTime - lastFpsUpdateTime >= 1000) {
                                        int fps = (int) (frameCount * 1000f / (currentTime - lastFpsUpdateTime));
                                        tvFps.setText(String.format("FPS: %d", fps));
                                        frameCount = 0;
                                        lastFpsUpdateTime = currentTime;
                                    }

                                    // 进阶功能：获取关键点数据进行姿态识别
                                    if (!result.landmarks().isEmpty()) {
                                        List<NormalizedLandmark> landmarks = result.landmarks().get(0);

                                        // 示例：检测是否举手（手腕高于肩膀）
                                        boolean leftHandRaised = landmarks.get(15).y() < landmarks.get(11).y();
                                        boolean rightHandRaised = landmarks.get(16).y() < landmarks.get(12).y();

                                        // 可以在这里添加你的姿态识别逻辑
                                        // if (leftHandRaised) { ... }
                                    }
                                }
                            });

                        }
                    };

            PoseLandmarker.PoseLandmarkerOptions options =
                    PoseLandmarker.PoseLandmarkerOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setNumPoses(numPoses)
                            .setMinPoseDetectionConfidence(detectionConfidence)
                            .setMinPosePresenceConfidence(detectionConfidence)
                            .setMinTrackingConfidence(trackingConfidence)
                            // 正确的结果回调设置方式
                            .setResultListener(resultListener)
                            .build();

            poseLandmarker = PoseLandmarker.createFromOptions(this, options);
        } catch (Exception e) {
            Toast.makeText(this, "检测器初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    // 启动相机（完全修正版）
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    cameraProvider = cameraProviderFuture.get();

                    // 预览用例
                    Preview preview = new Preview.Builder()
                            .setTargetResolution(targetResolution)
                            .build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    // 图像分析用例
                    ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                            .setTargetResolution(targetResolution)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build();

                    imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
                        @Override
                        public void analyze(ImageProxy imageProxy) {
                            try {
                                // 方法1：将ImageProxy转换为Bitmap（最稳定的方式）
                                Bitmap bitmap = imageProxyToBitmap(imageProxy);

                                // 旋转Bitmap到正确方向
                                Matrix matrix = new Matrix();
                                matrix.postRotate(imageProxy.getImageInfo().getRotationDegrees());
                                Bitmap rotatedBitmap = Bitmap.createBitmap(
                                        bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true
                                );

                                // 正确的MPImage创建方式：使用BitmapImageBuilder
                                MPImage mpImage = new BitmapImageBuilder(rotatedBitmap).build();

                                // 进行姿态检测
                                if (poseLandmarker != null) {
                                    poseLandmarker.detectAsync(mpImage, System.currentTimeMillis());
                                }

                                // 释放Bitmap资源
                                bitmap.recycle();
                                rotatedBitmap.recycle();

                            } catch (Exception e) {
                                // 忽略帧处理错误
                                e.printStackTrace();
                            } finally {
                                imageProxy.close();
                            }
                        }
                    });

                    // 选择摄像头
                    CameraSelector cameraSelector = useFrontCamera
                            ? CameraSelector.DEFAULT_FRONT_CAMERA
                            : CameraSelector.DEFAULT_BACK_CAMERA;

                    // 解绑之前的用例
                    cameraProvider.unbindAll();

                    // 绑定用例到生命周期
                    cameraProvider.bindToLifecycle(
                            MainActivity.this,
                            cameraSelector,
                            preview,
                            imageAnalysis
                    );

                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "相机启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            e.printStackTrace();
                        }
                    });
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ImageProxy转换为Bitmap的工具方法
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        ImageProxy.PlaneProxy planeProxy = imageProxy.getPlanes()[0];
        ByteBuffer buffer = planeProxy.getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, null);
    }

    // 权限请求结果处理
    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupPoseLandmarker();
                startCamera();
            } else {
                Toast.makeText(this, "需要相机权限才能使用此功能", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (poseLandmarker != null) {
            poseLandmarker.close();
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }
}