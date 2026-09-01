package com.monsieurmahjong.iqoowang;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.monsieurmahjong.iqoowang.connect.CategoryProvider;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批量补录消费记录Activity
 * 
 * 支持的格式:
 * 1. 标准格式: 8.28/8.88/餐饮/早餐
 * 2. 简化格式: 8.28/8.88/餐饮
 * 3. 最简格式: 8.28/8.88
 * 
 * 智能解析特性:
 * - 自动识别日期格式 (8.28, 08.28, 2026.8.28, 2026-08-28等)
 * - 自动识别金额 (支持整数和小数)
 * - 自动匹配分类 (支持模糊匹配和自动补全)
 * - 自动处理备注
 * - 容错处理 (忽略空行和格式错误的行)
 */
public class BatchImportActivity extends AppCompatActivity {

    private EditText etBatchInput;
    private TextView tvParseResult;
    private TextView btnImport;
    private TextView btnClear;
    private AppDatabase db;

    private List<ParsedExpense> parsedList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_import);

        db = AppDatabase.getDatabase(this);

        initViews();
        setupListeners();
    }

    private void initViews() {
        ImageView btnBack = findViewById(R.id.btn_back);
        btnImport = findViewById(R.id.btn_import);
        btnClear = findViewById(R.id.btn_clear);
        etBatchInput = findViewById(R.id.et_batch_input);
        tvParseResult = findViewById(R.id.tv_parse_result);

        btnBack.setOnClickListener(v -> finish());
    }

    private void setupListeners() {
        // 实时解析输入内容
        etBatchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                parseInput(s.toString());
            }
        });

        // 导入按钮
        btnImport.setOnClickListener(v -> {
            if (parsedList.isEmpty()) {
                Toast.makeText(this, "没有可导入的记录", Toast.LENGTH_SHORT).show();
                return;
            }
            showConfirmDialog();
        });

        // 清空按钮
        btnClear.setOnClickListener(v -> {
            etBatchInput.setText("");
            parsedList.clear();
            updateParseResult();
        });
    }

    /**
     * 智能解析输入文本
     */
    private void parseInput(String input) {
        parsedList.clear();

        if (input == null || input.trim().isEmpty()) {
            updateParseResult();
            return;
        }

        String[] lines = input.split("\n");
        int successCount = 0;
        int errorCount = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            ParsedExpense expense = parseOneLine(line);
            if (expense != null) {
                parsedList.add(expense);
                successCount++;
            } else {
                errorCount++;
            }
        }

        updateParseResult();
    }

    /**
     * 解析单行记录
     * 支持格式:
     * - 8.28/8.88/餐饮/早餐
     * - 8.28/8.88/餐饮
     * - 8.28/8.88
     * - 2026.8.28/8.88/餐饮
     * - 2026-08-28/8.88/餐饮
     */
    private ParsedExpense parseOneLine(String line) {
        // 使用正则表达式提取关键信息
        // 支持多种分隔符: / | , 空格等
        String[] parts = line.split("[/|,\\s]+");
        
        if (parts.length < 2) {
            return null; // 至少需要日期和金额
        }

        try {
            // 解析日期
            String dateStr = parseDate(parts[0]);
            if (dateStr == null) return null;

            // 解析金额
            double amount = parseAmount(parts[1]);
            if (amount <= 0) return null;

            // 解析分类 (可选)
            String category = "其他";
            if (parts.length >= 3) {
                category = matchCategory(parts[2]);
            }

            // 解析备注 (可选)
            String remark = "";
            if (parts.length >= 4) {
                // 拼接剩余所有部分作为备注
                StringBuilder remarkBuilder = new StringBuilder();
                for (int i = 3; i < parts.length; i++) {
                    remarkBuilder.append(parts[i]);
                    if (i < parts.length - 1) remarkBuilder.append(" ");
                }
                remark = remarkBuilder.toString();
            }

            return new ParsedExpense(dateStr, amount, category, remark);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 智能解析日期
     * 支持格式:
     * - 8.28 -> 2026-08-28
     * - 08.28 -> 2026-08-28
     * - 2026.8.28 -> 2026-08-28
     * - 2026-08-28 -> 2026-08-28
     * - 12/25 -> 2026-12-25
     */
    private String parseDate(String input) {
        if (input == null || input.isEmpty()) return null;

        // 移除空格
        input = input.trim();

        Calendar now = Calendar.getInstance();
        int currentYear = now.get(Calendar.YEAR);
        int currentMonth = now.get(Calendar.MONTH) + 1;

        // 模式1: 完整日期 2026-08-28 或 2026.08.28
        Pattern pattern1 = Pattern.compile("(\\d{4})[-.](\\d{1,2})[-.](\\d{1,2})");
        Matcher matcher1 = pattern1.matcher(input);
        if (matcher1.find()) {
            int year = Integer.parseInt(matcher1.group(1));
            int month = Integer.parseInt(matcher1.group(2));
            int day = Integer.parseInt(matcher1.group(3));
            return String.format(Locale.getDefault(), "%d-%02d-%02d", year, month, day);
        }

        // 模式2: 月日格式 8.28 或 08.28 或 8/28
        Pattern pattern2 = Pattern.compile("(\\d{1,2})[./](\\d{1,2})");
        Matcher matcher2 = pattern2.matcher(input);
        if (matcher2.find()) {
            int month = Integer.parseInt(matcher2.group(1));
            int day = Integer.parseInt(matcher2.group(2));
            
            // 智能判断年份: 如果月份大于当前月份,可能是去年的记录
            int year = currentYear;
            if (month > currentMonth) {
                year = currentYear - 1;
            }
            
            return String.format(Locale.getDefault(), "%d-%02d-%02d", year, month, day);
        }

        return null;
    }

    /**
     * 解析金额
     * 支持: 8.88, 8, 88.00, ¥8.88等
     */
    private double parseAmount(String input) {
        if (input == null || input.isEmpty()) return 0;

        // 移除货币符号和空格
        input = input.replaceAll("[¥￥$€£\\s]", "");

        try {
            return Double.parseDouble(input);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 智能匹配分类
     * 支持模糊匹配和别名
     */
    private String matchCategory(String input) {
        if (input == null || input.isEmpty()) return "其他";

        input = input.trim();

        // 精确匹配
        for (int i = 0; i < CategoryProvider.basicCategories.size(); i++) {
            String categoryName = CategoryProvider.basicCategories.get(i).getName();
            if (categoryName.equals(input)) {
                return categoryName;
            }
        }

        // 模糊匹配 (包含关系)
        for (int i = 0; i < CategoryProvider.basicCategories.size(); i++) {
            String categoryName = CategoryProvider.basicCategories.get(i).getName();
            if (categoryName.contains(input) || input.contains(categoryName)) {
                return categoryName;
            }
        }

        // 别名匹配
        if (input.contains("吃") || input.contains("喝") || input.contains("饭") || input.contains("食")) {
            return "餐饮";
        } else if (input.contains("车") || input.contains("行") || input.contains("路")) {
            return "交通";
        } else if (input.contains("买") || input.contains("购") || input.contains("物")) {
            return "购物";
        } else if (input.contains("玩") || input.contains("乐") || input.contains("游")) {
            return "娱乐";
        } else if (input.contains("零") || input.contains("snack") || input.contains("饮")) {
            return "零食饮料";
        }

        // 如果都不匹配,返回原输入作为自定义分类
        return input;
    }

    /**
     * 更新解析结果显示
     */
    private void updateParseResult() {
        if (parsedList.isEmpty()) {
            tvParseResult.setText("等待输入...");
            btnImport.setEnabled(false);
            btnImport.setAlpha(0.5f);
        } else {
            double totalAmount = 0;
            for (ParsedExpense expense : parsedList) {
                totalAmount += expense.amount;
            }
            tvParseResult.setText(String.format(Locale.getDefault(), 
                "识别到 %d 笔记录，合计 ¥%.2f", parsedList.size(), totalAmount));
            btnImport.setEnabled(true);
            btnImport.setAlpha(1.0f);
        }
    }

    /**
     * 显示确认导入对话框
     */
    private void showConfirmDialog() {
        StringBuilder preview = new StringBuilder();
        preview.append("即将导入以下记录:\n\n");

        int previewCount = Math.min(5, parsedList.size());
        for (int i = 0; i < previewCount; i++) {
            ParsedExpense expense = parsedList.get(i);
            preview.append(String.format(Locale.getDefault(), 
                "%s  ¥%.2f  %s\n", 
                expense.dateStr, expense.amount, expense.category));
        }

        if (parsedList.size() > 5) {
            preview.append(String.format("\n... 还有 %d 笔记录", parsedList.size() - 5));
        }

        new AlertDialog.Builder(this)
            .setTitle("确认批量导入")
            .setMessage(preview.toString())
            .setPositiveButton("确认导入", (dialog, which) -> executeImport())
            .setNegativeButton("取消", null)
            .show();
    }

    /**
     * 执行导入操作
     */
    private void executeImport() {
        new Thread(() -> {
            int successCount = 0;
            int errorCount = 0;

            for (ParsedExpense parsed : parsedList) {
                try {
                    long amountInCents = (long) (parsed.amount * 100);
                    Expense expense = new Expense(
                        0,
                        amountInCents,
                        parsed.category,
                        System.currentTimeMillis(),
                        parsed.dateStr,
                        "批量导入"
                    );
                    
                    if (parsed.remark != null && !parsed.remark.isEmpty()) {
                        expense.setRemark(parsed.remark);
                    }

                    db.expenseDao().insertExpense(expense);
                    successCount++;
                } catch (Exception e) {
                    errorCount++;
                }
            }

            final int finalSuccess = successCount;
            final int finalError = errorCount;

            runOnUiThread(() -> {
                String message = String.format(Locale.getDefault(), 
                    "导入完成！\n成功: %d 笔\n失败: %d 笔", 
                    finalSuccess, finalError);
                
                new AlertDialog.Builder(this)
                    .setTitle("导入结果")
                    .setMessage(message)
                    .setPositiveButton("确定", (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
            });
        }).start();
    }

    /**
     * 解析后的消费记录数据类
     */
    private static class ParsedExpense {
        String dateStr;
        double amount;
        String category;
        String remark;

        ParsedExpense(String dateStr, double amount, String category, String remark) {
            this.dateStr = dateStr;
            this.amount = amount;
            this.category = category;
            this.remark = remark;
        }
    }
}
