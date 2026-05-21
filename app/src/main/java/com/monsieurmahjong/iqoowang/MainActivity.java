package com.monsieurmahjong.iqoowang;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LiveData;

import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;
import com.monsieurmahjong.iqoowang.utils.AnimationUtils;

import java.nio.ByteBuffer;

import android.os.Bundle;
import android.widget.LinearLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import kotlinx.coroutines.flow.FlowKt;

public class MainActivity extends AppCompatActivity {

    private TextView tvTodayTotal;
    private LinearLayout containerHistory;
    private AppDatabase db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化控件和数据库（与原代码完全一致）
        tvTodayTotal = findViewById(R.id.tv_today_total);
        containerHistory = findViewById(R.id.container_history);
        db = AppDatabase.getDatabase(this);

        // 获取今日日期字符串（格式完全一致）
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String todayStr = sdf.format(new Date());

        // 1. 响应式监听今日总花费（替代原Kotlin协程collectLatest）
        LiveData<Long> totalLiveData = db.expenseDao().getDailyTotal(todayStr);
        totalLiveData.observe(this, totalCents -> {
            long total = totalCents != null ? totalCents : 0L;
            // 触发金额滚动动画（调用之前转换的AnimationUtils）
            AnimationUtils.animateAmount(tvTodayTotal, total);
        });

        // 2. 响应式监听消费明细列表
        LiveData<List<Expense>> expensesLiveData = db.expenseDao().getDailyExpenses(todayStr);
        expensesLiveData.observe(this, list -> {
            // 清空历史容器
            containerHistory.removeAllViews();
            // 遍历添加明细项（与原代码完全一致的UI参数）
            for (Expense expense : list) {
                TextView tv = new TextView(MainActivity.this);
                // 文本内容完全对应原字符串模板
                String text = expense.getCategoryName() + " ： ¥ " + (expense.getAmount() / 100.0) + " (" + expense.getSource() + ")";
                tv.setText(text);
                tv.setTextSize(15); // 对应原代码15sp（setTextSize默认单位为sp）
                tv.setPadding(0, 12, 0, 12); // 像素单位与原代码完全一致
                containerHistory.addView(tv);
            }
        });
    }
}