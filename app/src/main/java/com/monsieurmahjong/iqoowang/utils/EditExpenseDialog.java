package com.monsieurmahjong.iqoowang.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.textfield.TextInputEditText;
import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;
import com.monsieurmahjong.iqoowang.map.LocationMapActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 复用的"账单明细"编辑/新增 BottomSheet（原本只在 DayDetailActivity 里做"编辑"，
 * 现在抽成共用组件：SearchActivity 等入口点击条目走编辑模式；
 * DayDetailActivity 右下角悬浮加号走新增模式，用于给过去某一天补录忘记记的消费）。
 *
 * 分类字段是单选下拉菜单：点击弹出已有分类列表供选择，
 * 列表最后一项是"新增分类"，选中后弹出小输入框让用户新增一个分类名称。
 *
 * 2026-08 新增位置信息展示（仅编辑模式）：只有摇一摇/NFC记账时自动定位到的记录才会
 * 显示这一行，长按可改名，点击跳转独立地图页面。新增模式（补录旧日期）不涉及定位，
 * 不显示这一行——补录的是过去某一天的账，"现在的定位"跟那天没有意义上的关联。
 */
public class EditExpenseDialog {

    private static final String ADD_NEW_SENTINEL = "＋ 新增分类…";

    public interface OnSavedListener {
        void onSaved();
    }

    /** 编辑模式：修改一条已有消费记录 */
    public static void show(Activity activity, AppDatabase db, Expense expense, OnSavedListener listener) {
        DialogViews dv = buildDialogShell(activity);
        dv.title.setText("修改账单明细");
        dv.btnSave.setText("保存修改");

        // 回显数据
        dv.etAmount.setText(String.valueOf(expense.getAmount() / 100.0));
        String currentCategory = expense.getCategoryName();
        dv.etCategory.setText(currentCategory);
        if (expense.getRemark() != null) {
            dv.etRemark.setText(expense.getRemark());
        }

        setupCategoryDropdown(activity, db, dv.etCategory, currentCategory);

        // 位置信息：只有已经有经纬度的记录才显示这一行。currentLocationName 用单元素数组包一层，
        // 是因为长按改名要在内部的 AlertDialog 回调里改这个值，保存按钮的回调里还要读到改名后的
        // 结果——lambda 里能捕获的局部变量必须是"事实上的 final"，包一层数组绕开这个限制。
        Double lat = expense.getLatitude();
        Double lon = expense.getLongitude();
        final String[] currentLocationName = {expense.getLocationName()};
        if (lat != null && lon != null) {
            dv.tvLocation.setVisibility(View.VISIBLE);
            refreshLocationText(dv.tvLocation, currentLocationName[0]);

            dv.tvLocation.setOnLongClickListener(v -> {
                showRenameLocationDialog(activity, dv.tvLocation, currentLocationName);
                return true;
            });
            dv.tvLocation.setOnClickListener(v ->
                    openLocationMap(activity, lat, lon, currentLocationName[0]));
        }

        dv.btnSave.setOnClickListener(v -> {
            try {
                double newAmountDouble = Double.parseDouble(dv.etAmount.getText().toString());
                long newAmountCents = (long) (newAmountDouble * 100);
                String newCategory = dv.etCategory.getText().toString().trim();
                String newRemark = dv.etRemark.getText() != null ? dv.etRemark.getText().toString().trim() : "";

                if (ADD_NEW_SENTINEL.equals(newCategory)) {
                    Toast.makeText(activity, "请先在下拉菜单里输入新分类名称", Toast.LENGTH_SHORT).show();
                    return;
                }

                expense.setAmount(newAmountCents);
                expense.setCategoryName(newCategory.isEmpty() ? "其他支出" : newCategory);
                expense.setRemark(newRemark);
                if (lat != null && lon != null) {
                    expense.setLocationName(currentLocationName[0]);
                }

                new Thread(() -> {
                    db.expenseDao().updateExpense(expense);
                    activity.runOnUiThread(() -> {
                        dv.dialog.dismiss();
                        Toast.makeText(activity, "修改成功", Toast.LENGTH_SHORT).show();
                        if (listener != null) listener.onSaved();
                    });
                }).start();
            } catch (NumberFormatException e) {
                Toast.makeText(activity, "请输入合法的金额", Toast.LENGTH_SHORT).show();
            }
        });

        dv.dialog.show();
    }

    /**
     * 新增模式：给 dateStrForNew（"yyyy-MM-dd"）这一天补录一笔消费。
     * 和"编辑"共用同一套弹窗外壳与分类下拉逻辑，区别只在标题/按钮文案、
     * 初始为空、保存时是插入一条新记录而不是更新已有记录。
     *
     * 【日期一致性】新记录的 timestamp 不能直接用 System.currentTimeMillis()——
     * 那样会导致 date_str（比如"2026-07-15"）和 timestamp（实际是今天）对不上：
     * 日历、按 date_str 查询的地方能正确归到 7 月 15 号，但统计报表、
     * CheckInManager 周打卡这些按 timestamp 时间范围查询的地方，会把这笔
     * 补录的支出误算到"今天"头上。这里用 dateStrForNew 对应的那一天 + 当前的
     * 时分秒拼出 timestamp，保证两个字段的"日期部分"严格一致。
     */
    public static void show(Activity activity, AppDatabase db, String dateStrForNew, OnSavedListener listener) {
        DialogViews dv = buildDialogShell(activity);
        dv.title.setText("补录消费记录");
        dv.btnSave.setText("确认添加");

        setupCategoryDropdown(activity, db, dv.etCategory, "");
        // 补录的是过去某一天，不涉及"现在的定位"，位置信息这一行保持默认的 gone

        dv.btnSave.setOnClickListener(v -> {
            try {
                double amountDouble = Double.parseDouble(dv.etAmount.getText().toString());
                long amountCents = (long) (amountDouble * 100);
                String category = dv.etCategory.getText().toString().trim();
                String remark = dv.etRemark.getText() != null ? dv.etRemark.getText().toString().trim() : "";

                if (ADD_NEW_SENTINEL.equals(category)) {
                    Toast.makeText(activity, "请先在下拉菜单里输入新分类名称", Toast.LENGTH_SHORT).show();
                    return;
                }

                long timestamp = timestampForDateKeepingTimeOfDay(dateStrForNew);
                Expense newExpense = new Expense(0, amountCents,
                        category.isEmpty() ? "其他支出" : category,
                        timestamp, dateStrForNew, "MANUAL");
                if (!remark.isEmpty()) newExpense.setRemark(remark);

                new Thread(() -> {
                    db.expenseDao().insertExpense(newExpense);
                    activity.runOnUiThread(() -> {
                        dv.dialog.dismiss();
                        Toast.makeText(activity, "补录成功", Toast.LENGTH_SHORT).show();
                        if (listener != null) listener.onSaved();
                    });
                }).start();
            } catch (NumberFormatException e) {
                Toast.makeText(activity, "请输入合法的金额", Toast.LENGTH_SHORT).show();
            }
        });

        dv.dialog.show();
    }

    /**
     * 把 dateStr（yyyy-MM-dd）和"现在的时分秒"拼成一个 timestamp：
     * 日期部分固定成补录目标那一天，时间部分用当前时刻，
     * 这样同一天里补录多笔时相互之间仍能按录入先后正确排序。
     * 解析失败时兜底退化成 System.currentTimeMillis()（此时 date_str 仍然是对的，
     * 只是 timestamp 会指向今天——比完全崩溃或写入非法数据更安全）。
     */
    private static long timestampForDateKeepingTimeOfDay(String dateStr) {
        try {
            SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
            Date parsed = dateFmt.parse(dateStr);
            if (parsed == null) return System.currentTimeMillis();
            Calendar target = Calendar.getInstance();
            target.setTime(parsed);
            Calendar now = Calendar.getInstance();
            target.set(Calendar.HOUR_OF_DAY, now.get(Calendar.HOUR_OF_DAY));
            target.set(Calendar.MINUTE, now.get(Calendar.MINUTE));
            target.set(Calendar.SECOND, now.get(Calendar.SECOND));
            target.set(Calendar.MILLISECOND, now.get(Calendar.MILLISECOND));
            return target.getTimeInMillis();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    /** 弹窗外壳：inflate 布局、找控件、处理 BottomSheet 透明背景，编辑/新增两种模式共用 */
    private static DialogViews buildDialogShell(Activity activity) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity,
                com.google.android.material.R.style.Theme_MaterialComponents_Light_BottomSheetDialog);

        // 用 ContextThemeWrapper 包装，确保 TextInputLayout / AutoCompleteTextView 能正确识别 Material 样式
        ContextThemeWrapper themeWrapper = new ContextThemeWrapper(activity,
                com.google.android.material.R.style.Theme_MaterialComponents_Light);

        View view = LayoutInflater.from(themeWrapper).inflate(R.layout.dialog_edit_transaction, null);
        dialog.setContentView(view);

        DialogViews dv = new DialogViews();
        dv.dialog = dialog;
        dv.title = view.findViewById(R.id.tv_edit_dialog_title);
        dv.etAmount = view.findViewById(R.id.et_edit_amount);
        dv.etCategory = view.findViewById(R.id.et_edit_category);
        dv.etRemark = view.findViewById(R.id.et_edit_remark);
        dv.tvLocation = view.findViewById(R.id.tv_edit_location);
        dv.btnSave = view.findViewById(R.id.btn_save_changes);

        // 让 BottomSheet 背景透明以展示自绘的圆角背景
        if (view.getParent() instanceof View) {
            ((View) view.getParent()).setBackgroundColor(Color.TRANSPARENT);
        }
        return dv;
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
                etCategory.setText(currentCategory == null ? "" : currentCategory, false);

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

    /** 位置文字的统一渲染：加个📍前缀，空名字兜底显示"未命名地点" */
    private static void refreshLocationText(TextView tvLocation, String name) {
        tvLocation.setText("📍 " + (name != null && !name.trim().isEmpty() ? name : "未命名地点"));
    }

    /** 长按位置信息弹出的重命名输入框，和"新增分类"用的是同一套 AlertDialog+EditText 模式 */
    private static void showRenameLocationDialog(Context ctx, TextView tvLocation, String[] currentLocationName) {
        EditText input = new EditText(ctx);
        input.setHint("位置名称");
        if (currentLocationName[0] != null) input.setText(currentLocationName[0]);

        new AlertDialog.Builder(ctx)
                .setTitle("修改位置名称")
                .setView(input)
                .setPositiveButton("确定", (d, which) -> {
                    String newName = input.getText().toString().trim();
                    currentLocationName[0] = newName.isEmpty() ? null : newName;
                    refreshLocationText(tvLocation, currentLocationName[0]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 跳转到独立的地图页面，展示这笔账单记录时的位置 */
    private static void openLocationMap(Activity activity, double lat, double lon, String name) {
        Intent intent = new Intent(activity, LocationMapActivity.class);
        intent.putExtra(LocationMapActivity.EXTRA_LAT, lat);
        intent.putExtra(LocationMapActivity.EXTRA_LON, lon);
        intent.putExtra(LocationMapActivity.EXTRA_NAME, name);
        activity.startActivity(intent);
    }

    /** 弹窗内控件的简单集合，供 buildDialogShell() 返回，编辑/新增两种模式各自在其上继续操作 */
    private static class DialogViews {
        BottomSheetDialog dialog;
        TextView title;
        TextInputEditText etAmount;
        AutoCompleteTextView etCategory;
        TextInputEditText etRemark;
        TextView tvLocation;
        Button btnSave;
    }
}
