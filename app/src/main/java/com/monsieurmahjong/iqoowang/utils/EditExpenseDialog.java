package com.monsieurmahjong.iqoowang.utils;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.textfield.TextInputEditText;
import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;

import java.util.ArrayList;
import java.util.List;

/**
 * 复用的"修改账单明细" BottomSheet（原本只在 DayDetailActivity 里，
 * 现在抽成共用组件，SearchActivity 等其他入口点击条目也能弹出同一个编辑弹窗）。
 *
 * 分类字段改为单选下拉菜单：点击弹出已有分类列表供选择，
 * 列表最后一项是"新增分类"，选中后弹出小输入框让用户新增一个分类名称。
 */
public class EditExpenseDialog {

    private static final String ADD_NEW_SENTINEL = "＋ 新增分类…";

    public interface OnSavedListener {
        void onSaved();
    }

    public static void show(Activity activity, AppDatabase db, Expense expense, OnSavedListener listener) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity,
                com.google.android.material.R.style.Theme_MaterialComponents_Light_BottomSheetDialog);

        // 用 ContextThemeWrapper 包装，确保 TextInputLayout / AutoCompleteTextView 能正确识别 Material 样式
        ContextThemeWrapper themeWrapper = new ContextThemeWrapper(activity,
                com.google.android.material.R.style.Theme_MaterialComponents_Light);

        View view = LayoutInflater.from(themeWrapper).inflate(R.layout.dialog_edit_transaction, null);
        dialog.setContentView(view);

        TextInputEditText etAmount = view.findViewById(R.id.et_edit_amount);
        AutoCompleteTextView etCategory = view.findViewById(R.id.et_edit_category);
        TextInputEditText etRemark = view.findViewById(R.id.et_edit_remark);
        Button btnSave = view.findViewById(R.id.btn_save_changes);

        // 回显数据
        etAmount.setText(String.valueOf(expense.getAmount() / 100.0));
        String currentCategory = expense.getCategoryName();
        etCategory.setText(currentCategory);
        if (expense.getRemark() != null) {
            etRemark.setText(expense.getRemark());
        }

        setupCategoryDropdown(activity, db, etCategory, currentCategory);

        btnSave.setOnClickListener(v -> {
            try {
                double newAmountDouble = Double.parseDouble(etAmount.getText().toString());
                long newAmountCents = (long) (newAmountDouble * 100);
                String newCategory = etCategory.getText().toString().trim();
                String newRemark = etRemark.getText() != null ? etRemark.getText().toString().trim() : "";

                if (ADD_NEW_SENTINEL.equals(newCategory)) {
                    Toast.makeText(activity, "请先在下拉菜单里输入新分类名称", Toast.LENGTH_SHORT).show();
                    return;
                }

                expense.setAmount(newAmountCents);
                expense.setCategoryName(newCategory.isEmpty() ? "其他支出" : newCategory);
                expense.setRemark(newRemark);

                new Thread(() -> {
                    db.expenseDao().updateExpense(expense);
                    activity.runOnUiThread(() -> {
                        dialog.dismiss();
                        Toast.makeText(activity, "修改成功", Toast.LENGTH_SHORT).show();
                        if (listener != null) listener.onSaved();
                    });
                }).start();
            } catch (NumberFormatException e) {
                Toast.makeText(activity, "请输入合法的金额", Toast.LENGTH_SHORT).show();
            }
        });

        // 让 BottomSheet 背景透明以展示自绘的圆角背景
        if (view.getParent() instanceof View) {
            ((View) view.getParent()).setBackgroundColor(Color.TRANSPARENT);
        }
        dialog.show();
    }

    /** 异步加载库内已有分类，填充下拉菜单；末尾追加"新增分类"入口 */
    private static void setupCategoryDropdown(Activity activity, AppDatabase db,
                                               AutoCompleteTextView etCategory, String currentCategory) {
        new Thread(() -> {
            List<String> categories = new ArrayList<>(db.expenseDao().getAllCategoryNamesSync());
            if (currentCategory != null && !currentCategory.trim().isEmpty() && !categories.contains(currentCategory)) {
                categories.add(0, currentCategory);
            }
            categories.add(ADD_NEW_SENTINEL);

            activity.runOnUiThread(() -> {
                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                        activity, android.R.layout.simple_list_item_1, categories);
                etCategory.setAdapter(adapter);
                etCategory.setText(currentCategory, false);

                // 阈值设为 0，点击即弹出完整列表，不需要先输入字符触发过滤
                etCategory.setThreshold(0);
                etCategory.setOnClickListener(v -> etCategory.showDropDown());

                etCategory.setOnItemClickListener((parent, itemView, position, id) -> {
                    String selected = adapter.getItem(position);
                    if (ADD_NEW_SENTINEL.equals(selected)) {
                        showAddCategoryDialog(activity, etCategory, adapter, categories);
                    }
                });
            });
        }).start();
    }

    /** "新增分类"输入弹窗：确认后插入到下拉列表中（"新增分类"选项之前），并立即选中 */
    private static void showAddCategoryDialog(Context ctx, AutoCompleteTextView etCategory,
                                               ArrayAdapter<String> adapter, List<String> categories) {
        EditText input = new EditText(ctx);
        input.setHint("输入新分类名称");

        new AlertDialog.Builder(ctx)
                .setTitle("新增分类")
                .setView(input)
                .setPositiveButton("确定", (d, which) -> {
                    String newCat = input.getText().toString().trim();
                    if (!newCat.isEmpty() && !categories.contains(newCat)) {
                        categories.add(categories.size() - 1, newCat); // 插到"新增分类"这一项之前
                        adapter.notifyDataSetChanged();
                    }
                    if (!newCat.isEmpty()) {
                        etCategory.setText(newCat, false);
                    }
                })
                .setNegativeButton("取消", (d, which) -> etCategory.setText("", false))
                .show();
    }
}
