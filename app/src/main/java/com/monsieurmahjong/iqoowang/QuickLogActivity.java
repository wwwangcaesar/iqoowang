package com.monsieurmahjong.iqoowang;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import androidx.appcompat.app.AppCompatActivity;

import com.monsieurmahjong.iqoowang.connect.CategoryProvider;
import com.monsieurmahjong.iqoowang.connect.ExpenseCategory;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;

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
import androidx.core.content.ContextCompat;

import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class QuickLogActivity extends AppCompatActivity {

    private EditText etAmount;
    private GridLayout gridCategories;
    private AppDatabase db;
    private String triggerSource = "MANUAL";
    private String detectedCardUid = ""; // 记录当前碰到的电梯卡卡号

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quick_log);

        etAmount = findViewById(R.id.et_amount);
        gridCategories = findViewById(R.id.grid_categories);
        db = AppDatabase.getDatabase(this);

        // 处理第一次唤醒
        handleNfcIntent(getIntent());
        setupCategoryGrid();
    }

    // 当 Activity 已经在后台，再次物理碰卡时触发此方法（因为设置了 singleTask）
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNfcIntent(intent);
    }

    // 核心：解析电梯卡 UID 的方法
    private void handleNfcIntent(android.content.Intent intent) {
        if (intent != null && NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            triggerSource = "ELEVATOR_CARD"; // 标记来源为电梯卡

            // 从 Intent 中提取 Tag 对象
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                byte[] uidBytes = tag.getId();
                detectedCardUid = bytesToHexString(uidBytes); // 转换为16进制字符串

                // 提示用户读卡成功（测试用，实际使用时可删掉）
                Toast.makeText(this, "检测到电梯卡，卡号: " + detectedCardUid, Toast.LENGTH_LONG).show();
            }
        }
    }

    // 实用工具：将字节数组转换为16进制可见文本 (JDK 1.8 传统写法)
    private String bytesToHexString(byte[] src) {
        StringBuilder stringBuilder = new StringBuilder();
        if (src == null || src.length <= 0) {
            return "";
        }
        for (byte b : src) {
            int v = b & 0xFF;
            String hv = Integer.toHexString(v);
            if (hv.length() < 2) {
                stringBuilder.append(0);
            }
            stringBuilder.append(hv);
        }
        return stringBuilder.toString().toUpperCase();
    }
    // 记录当前选中的分类位置（-1表示未选中）
    private int selectedPosition = -1;
    // 保存所有分类item的引用，方便更新选中状态
    private final List<View> categoryItems = new ArrayList<>();
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

    private void saveAndExit(String categoryName) {
        String amountStr = etAmount.getText().toString();
        if (amountStr.isEmpty()) return;

        final long amountInCents = (long) (Double.parseDouble(amountStr) * 100);
        final String todayStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        // 拼接记录来源，将电梯卡卡号一起存入数据库备查
        final String recordSource = triggerSource + "[" + detectedCardUid + "]";

        new Thread(new Runnable() {
            @Override
            public void run() {
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
                // 可选：给选中的图标加个蓝色滤镜
                ImageView ivIcon = (ImageView) ((LinearLayout) item).getChildAt(0);
                ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.teal_700));
            } else {
                // 未选中状态：默认浅灰色背景
                bg.setColor(Color.parseColor("#F2F2F7"));
                // 清除图标滤镜
                ImageView ivIcon = (ImageView) ((LinearLayout) item).getChildAt(0);
                ivIcon.clearColorFilter();
            }

            item.invalidate();
        }
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
}
