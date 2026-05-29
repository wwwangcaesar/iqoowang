package com.monsieurmahjong.iqoowang;

import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.monsieurmahjong.iqoowang.connect.CategoryProvider;
import com.monsieurmahjong.iqoowang.connect.ExpenseCategory;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.Manifest; // 必须是这个 Android 系统包
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.monsieurmahjong.iqoowang.server.ScreenshotService;
import com.monsieurmahjong.iqoowang.view.CyberpunkBgView;

public class QuickLogActivity extends AppCompatActivity {

    private EditText etAmount;
    private GridLayout gridCategories;
    private AppDatabase db;
    private String triggerSource = "MANUAL";
    private String detectedCardUid = ""; // 记录当前碰到的电梯卡卡号
    private CyberpunkBgView cyberpunkBg;

    private String payTypeName="";
    private static final String TAG = "NFC_Screenshot_Activity";

    private BroadcastReceiver screenshotReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "📥 Activity 收到广播，Action = " + intent.getAction());
            if (ScreenshotService.ACTION_SCREENSHOT_DONE.equals(intent.getAction())) {
                String imgUriString = intent.getStringExtra(ScreenshotService.EXTRA_IMAGE_URI);
                Log.d(TAG, "🎉 成功拿到相册图片 URI: " + imgUriString + "，准备解除透明并显示真实 UI！");
                showRealUI(imgUriString);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "📱 onCreate 启动");
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);

        ContextCompat.registerReceiver(this, screenshotReceiver,
                new IntentFilter(ScreenshotService.ACTION_SCREENSHOT_DONE), ContextCompat.RECEIVER_EXPORTED);
        Log.d(TAG, "📡 成功注册完成截图的广播监听器");

        handleNfcIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "🔄 onNewIntent 被触发（热启动碰卡）");
        setIntent(intent);
        handleNfcIntent(intent);
    }

    private boolean isNfc=true;
    private void handleNfcIntent(Intent intent) {
        if (intent != null && NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) {
            Log.d(TAG, "⚡ NFC 碰卡触发！当前 Activity 处于【全透明隐身状态】，正在向后台无障碍发截图命令...");
            isNfc=true;
            sendBroadcast(new Intent(ScreenshotService.ACTION_REQUEST_SCREENSHOT));
        } else {
            Log.d(TAG, "👆 正常点击图标或非 NFC 唤醒，无需隐身截图，直接展示 UI");
            isNfc=false;
            showRealUI(null);
        }
    }


    private void showRealUI(String imagePath) {
        // 全屏及透明导航栏/状态栏的配置（必须在 setContentView 之前）
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        // 3. 正式挂载布局
        setContentView(R.layout.activity_quick_log);

        cyberpunkBg = findViewById(R.id.cyberpunk_bg);
        if (cyberpunkBg != null) {
            cyberpunkBg.startAnim();
            cyberpunkBg.setOnClickListener(v -> finish());
        }

        overridePendingTransition(R.anim.quicklog_enter, 0);

        etAmount = findViewById(R.id.et_amount);
        gridCategories = findViewById(R.id.grid_categories);
        db = AppDatabase.getDatabase(this);

        checkStoragePermissionAndProcess();

        // 🚨【已删除】：删除了导致无限死循环的 handleNfcIntent(getIntent());

        setupCategoryGrid();

        // ── 取消/确认按钮 ──
        TextView btnCancel = findViewById(R.id.btn_cancel);
        if (btnCancel != null) btnCancel.setOnClickListener(v -> finish());

        TextView btnConfirm = findViewById(R.id.btn_confirm);
        if (btnConfirm != null) btnConfirm.setOnClickListener(v -> {
            // 在此处理你的记账逻辑，完成后调用 finish()
            // saveAndExit(payTypeName);
            finish();
        });

        if (imagePath != null) {
            // TODO: 将截图路径渲染到你的 ImageView 上
            Toast.makeText(this, "凭证已自动截取！", Toast.LENGTH_SHORT).show();
        }
    }



    // 记录当前选中的分类位置（-1表示未选中）
    private int selectedPosition = -1;
    // 保存所有分类item的引用，方便更新选中状态
    private final List<View> categoryItems = new ArrayList<>();
    private static final int PERMISSION_REQUEST_CODE = 1001;

    private void setupCategoryGrid() {
        // 清空之前的视图（防止重复添加）
        gridCategories.removeAllViews();
        categoryItems.clear();

        // 计算每个item的图标大小（根据屏幕宽度自适应，最大64dp，最小48dp）
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        // 两列，减去左右边距和中间间距
        int itemWidth = (screenWidth - dp2px(12 * 4)) / 2;
        // 图标大小为item宽度的40%，不超过64dp
        int iconSize = Math.min((int) (itemWidth * 0.4f), dp2px(64));

        // 遍历所有分类
        for (int i = 0; i < CategoryProvider.basicCategories.size(); i++) {
            ExpenseCategory category = CategoryProvider.basicCategories.get(i);
            final int position = i;

            // ====================== 每个分类的Item布局 ======================
            // 垂直方向的LinearLayout作为item容器（替代原来的Button）
            LinearLayout itemLayout = new LinearLayout(this);
            itemLayout.setOrientation(LinearLayout.VERTICAL);
            itemLayout.setGravity(Gravity.CENTER);
            itemLayout.setPadding(dp2px(16), dp2px(20), dp2px(16), dp2px(20));

            // 1. 图标ImageView
            ImageView ivIcon = new ImageView(this);
            // 设置固定大小，自动缩放图片
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconParams.bottomMargin = dp2px(12);
            ivIcon.setLayoutParams(iconParams);
            // 保持图片比例，居中显示
            ivIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            // 加载图标（兼容VectorDrawable和mipmap）
            Drawable icon = AppCompatResources.getDrawable(this, category.getIconResId());
            ivIcon.setImageDrawable(icon);

            // 2. 分类名称TextView
            TextView tvName = new TextView(this);
            tvName.setText(category.getName());
            tvName.setTextColor(Color.parseColor("#1C1C1E"));
            tvName.setTextSize(14);
            tvName.setGravity(Gravity.CENTER);
            tvName.setSingleLine(true);

            // 添加到item布局
            itemLayout.addView(ivIcon);
            itemLayout.addView(tvName);

            // ====================== 背景和选中效果 ======================
            // 设置默认背景
            GradientDrawable defaultBg = new GradientDrawable();
            defaultBg.setCornerRadius(dp2px(16));
            defaultBg.setColor(Color.parseColor("#F2F2F7"));
            itemLayout.setBackground(defaultBg);

            // ====================== GridLayout布局参数 ======================
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp2px(12), dp2px(12), dp2px(12), dp2px(12));
            itemLayout.setLayoutParams(params);

            // ====================== 点击事件 ======================
            itemLayout.setOnClickListener(v -> {
                // 更新选中位置
                selectedPosition = position;
                // 刷新所有item的选中状态
                updateAllItemsState();
                // 保存并退出（可以加个延迟，让用户看到选中效果）
                v.postDelayed(() -> saveAndExit(category.getName()), 150);
            });
            applyCyberpunkCategoryStyle(itemLayout);
            // 添加到网格和列表
            gridCategories.addView(itemLayout);
            categoryItems.add(itemLayout);
        }

        // 默认选中第一个分类
        if (!CategoryProvider.basicCategories.isEmpty()) {
            selectedPosition = 0;
            updateAllItemsState();
        }
    }

    private void checkStoragePermissionAndProcess() {
        String permission = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{permission}, PERMISSION_REQUEST_CODE);
        } else {
            // 已有权限，直接开始读取最新截屏并识别
            startScreenshotOcrWorkflow();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScreenshotOcrWorkflow();
        } else {
            Toast.makeText(this, "未授予读取相册权限，无法自动识别金额", Toast.LENGTH_SHORT).show();
        }
    }
    /**
     * 核心算法：从 MediaStore 查询系统相册中最新创建的那张图片
     */
    private Uri getLatestImageUri(Context context) {
        Uri collection = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
        };

        // 按添加时间倒序排列（最新的一张在最前面）
        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";

        try (Cursor cursor = context.getContentResolver().query(collection, projection, null, null, sortOrder)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                long id = cursor.getLong(idColumn);
                // 组合成完整的图片 Uri
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 核心算法：利用正则表达式从复杂的 OCR 文本中精准清洗出微信/支付宝金额
     */
    private String extractPaymentAmount(String fullText) {
        if (fullText == null || fullText.isEmpty()) return null;

        // 匹配常见金额格式（如: 88.00, ￥12.50, ¥ 5.00）
        // 过滤规则：找寻带有两位小数点的数字
        Pattern pattern = Pattern.compile("(?:¥|￥)?\\s*(\\d+\\.\\d{2})");
        Matcher matcher = pattern.matcher(fullText);

        double maxAmount = -1.0;
        String bestMatch = null;

        // 微信和支付宝的账单截屏通常包含时间、各种单号、扣款金额。
        // 策略：遍历所有匹配到的两位小数，通常“最大”的那个数字就是你的实际消费金额（单号一般不带小数点，时间是冒号）。
        while (matcher.find()) {
            String amountStr = matcher.group(1);
            try {
                double parsed = Double.parseDouble(amountStr);
                // 排除常见混淆：如部分商家带有 0.00 元优惠，或者特定单号混淆
                if (parsed > maxAmount) {
                    maxAmount = parsed;
                    bestMatch = amountStr;
                }
            } catch (NumberFormatException e) {
                // 转换失败则跳过
            }
        }

        return bestMatch;
    }
    private void saveAndExit(String categoryName) {
        String amountStr = etAmount.getText().toString();
        if (amountStr.isEmpty()) return;

        final long amountInCents = (long) (Double.parseDouble(amountStr) * 100);
        final String todayStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        // 拼接记录来源，将电梯卡卡号一起存入数据库备查
        new Thread(new Runnable() {
            @Override
            public void run() {
                String recordSource = triggerSource + "[" + detectedCardUid + "]";
                if (isNfc){
                    recordSource="NFC触摸";
                }else {
                    recordSource="手动录入";
                }
                Expense expense = new Expense(
                        0,
                        amountInCents,
                        categoryName,
                        System.currentTimeMillis(),
                        todayStr,
                        recordSource
                );

                db.expenseDao().insertExpense(expense);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                });
            }
        }).start();
    }
    /**
     * 更新所有分类item的选中状态
     */
    private void updateAllItemsState() {
        for (int i = 0; i < categoryItems.size(); i++) {
            View item = categoryItems.get(i);
            GradientDrawable bg = (GradientDrawable) item.getBackground();

            if (i == selectedPosition) {
                // 选中状态：深一点的灰色背景
                bg.setColor(Color.parseColor("#E5E5EA"));
                TextView textView = (TextView) ((LinearLayout) item).getChildAt(1);
                textView.setTextColor(getResources().getColor(R.color.on_error_container));
                // 可选：给选中的图标加个蓝色滤镜
                ImageView ivIcon = (ImageView) ((LinearLayout) item).getChildAt(0);
                ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.teal_700));
            } else {
                // 未选中状态：默认浅灰色背景
                bg.setColor(Color.parseColor("#F2F2F7"));
                TextView textView = (TextView) ((LinearLayout) item).getChildAt(1);
                textView.setTextColor(getResources().getColor(R.color.primary_light));
                // 清除图标滤镜
                ImageView ivIcon = (ImageView) ((LinearLayout) item).getChildAt(0);
                ivIcon.clearColorFilter();
            }

            item.invalidate();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // ── [插入位置 7] 停止动画防止内存泄漏 ──
        if (cyberpunkBg != null) cyberpunkBg.stopAnim();
        try {
            unregisterReceiver(screenshotReceiver);
        } catch (Exception e) {}
    }

    @Override
    public void finish() {
        super.finish();
        // ── [插入位置 8] 退出动画 ──
        overridePendingTransition(0, R.anim.quicklog_exit);
    }

    /**
     * 为单个分类按钮应用赛博朋克样式。
     * 在你的 setupCategoryGrid() 里，每次 addView 之前调用此方法。
     */
    public static void applyCyberpunkCategoryStyle(View itemView) {
        if (itemView == null) return;

        // 背景：深色透明 + 青色细边框
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(itemView.getResources().getDisplayMetrics().density * 10);
        bg.setColor(0x220A0820);
        bg.setStroke(
                (int)(itemView.getResources().getDisplayMetrics().density * 1f),
                0x6600FFFF);
        itemView.setBackground(bg);

        // 如果是 TextView，设置文字颜色
        if (itemView instanceof TextView) {
            ((TextView) itemView).setTextColor(0xFFDDFFFF);
        }
        // 如果是 ViewGroup，找到内部 TextView
        else if (itemView instanceof LinearLayout) {
            android.view.ViewGroup vg = (android.view.ViewGroup) itemView;
            for (int j = 0; j < vg.getChildCount(); j++) {
                View child = vg.getChildAt(j);
                if (child instanceof TextView) {
                    ((TextView) child).setTextColor(0xFFDDFFFF);
                }
            }
        }

        // 点击水波纹（暗色背景下的亮色水波纹）
        itemView.setClickable(true);
        itemView.setFocusable(true);
        int[] attrs = { android.R.attr.selectableItemBackground };
        android.content.res.TypedArray ta =
                itemView.getContext().obtainStyledAttributes(attrs);
        itemView.setForeground(ta.getDrawable(0));
        ta.recycle();
    }

    /**
     * dp转px工具方法
     */
    private int dp2px(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        );
    }
    private void startScreenshotOcrWorkflow() {
        Uri latestImageUri = getLatestImageUri(this);
        if (latestImageUri == null) {
            return;
        }

        try {
            InputImage image = InputImage.fromFilePath(this, latestImageUri);

            // 【核心修改】使用中文识别器的专用配置构造器
            ChineseTextRecognizerOptions options = new ChineseTextRecognizerOptions.Builder().build();
            TextRecognizer recognizer = TextRecognition.getClient(options);

            recognizer.process(image)
                    .addOnSuccessListener(visionText -> {
                        String parsedAmount = extractPaymentAmount(visionText.getText());
                        if (parsedAmount != null && !parsedAmount.isEmpty()) {
                            etAmount.setText(parsedAmount);
                            etAmount.setSelection(parsedAmount.length());
                            Toast.makeText(QuickLogActivity.this, "已自动识别金额: ¥" + parsedAmount, Toast.LENGTH_SHORT).show();
                        }
                    })
                    .addOnFailureListener(e -> {
                        e.printStackTrace();
                        Toast.makeText(QuickLogActivity.this, "图片识别失败", Toast.LENGTH_SHORT).show();
                    });

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
