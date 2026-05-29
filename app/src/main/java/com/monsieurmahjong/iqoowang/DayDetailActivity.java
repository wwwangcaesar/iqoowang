package com.monsieurmahjong.iqoowang;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.transition.Transition;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.textfield.TextInputEditText;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DayDetailActivity extends AppCompatActivity {

    private RecyclerView rvTransactions;
    private TransactionAdapter detailAdapter;
    private List<Expense> expenseList = new ArrayList<>();
    private AppDatabase db;
    private TextView tvTotal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_day_detail);

        db = AppDatabase.getDatabase(this);

        String dateStr = getIntent().getStringExtra("date");
        double totalAmount = getIntent().getDoubleExtra("amount", 0.0);

        TextView tvDate = findViewById(R.id.tv_detail_date);
        tvTotal = findViewById(R.id.tv_detail_total);
        rvTransactions = findViewById(R.id.rv_daily_transactions);

        tvDate.setText(dateStr + " 消费明细");
        tvTotal.setText(String.format("¥%.2f", totalAmount));

        rvTransactions.setLayoutManager(new LinearLayoutManager(this));
        detailAdapter = new TransactionAdapter();
        rvTransactions.setAdapter(detailAdapter);

        // 绑定 LiveData，数据库任何增删改都会自动触发此处刷新
        if (dateStr != null) {
            db.expenseDao().getDailyExpenses(dateStr).observe(this, expenses -> {
                if (expenses != null) {
                    expenseList.clear();
                    expenseList.addAll(expenses);
                    detailAdapter.notifyDataSetChanged();

                    // 重新计算当日总额并更新头部UI
                    long totalCents = 0;
                    for (Expense e : expenses) totalCents += e.getAmount();
                    tvTotal.setText(String.format("¥%.2f", totalCents / 100.0));
                }
            });
        }

        // 🌟 核心：挂载右滑删除交互
        setupSwipeToDelete();

        // 入场动画
        rvTransactions.setAlpha(0f);
        rvTransactions.setTranslationY(60f);
        getWindow().getSharedElementEnterTransition().addListener(new Transition.TransitionListener() {
            @Override
            public void onTransitionEnd(Transition transition) {
                rvTransactions.animate().alpha(1f).translationY(0f).setDuration(350).start();
            }
            @Override public void onTransitionStart(Transition transition) {}
            @Override public void onTransitionCancel(Transition transition) {}
            @Override public void onTransitionPause(Transition transition) {}
            @Override public void onTransitionResume(Transition transition) {}
        });
    }

    /**
     * 配置优雅的右滑删除机制
     */
    private void setupSwipeToDelete() {
        ItemTouchHelper.SimpleCallback swipeCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            private final ColorDrawable background = new ColorDrawable(Color.parseColor("#FF5252")); // 警告红

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                Expense expenseToDelete = expenseList.get(position);

                // 在后台线程执行删除，LiveData 会自动刷新列表
                new Thread(() -> {
                    db.expenseDao().deleteExpense(expenseToDelete);
                    runOnUiThread(() -> Toast.makeText(DayDetailActivity.this, "记录已删除", Toast.LENGTH_SHORT).show());
                }).start();
            }

            // 绘制滑动时的红色背景提示
            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
                View itemView = viewHolder.itemView;
                if (dX > 0) { // 向右滑动
                    background.setBounds(itemView.getLeft(), itemView.getTop(), itemView.getLeft() + ((int) dX), itemView.getBottom());
                    background.draw(c);
                } else {
                    background.setBounds(0, 0, 0, 0);
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }
        };

        new ItemTouchHelper(swipeCallback).attachToRecyclerView(rvTransactions);
    }

    /**
     * 弹出修改记录的 BottomSheet
     */
    /**
     * 弹出修改记录的 BottomSheet
     */
    private void showEditDialog(Expense expense) {
        // 🌟 核心修复点 1：显式指定带有 Material 属性的底部弹窗主题
        BottomSheetDialog dialog = new BottomSheetDialog(this, com.google.android.material.R.style.Theme_MaterialComponents_Light_BottomSheetDialog);

        // 🌟 核心修复点 2：使用 ContextThemeWrapper 强行将 LayoutInflater 的上下文包装为 MaterialComponents 主题
        // 这样可以确保布局内所有的 TextInputLayout 和 TextInputEditText 都能完美识别并渲染其样式
        androidx.appcompat.view.ContextThemeWrapper contextThemeWrapper =
                new androidx.appcompat.view.ContextThemeWrapper(this, com.google.android.material.R.style.Theme_MaterialComponents_Light);

        // 🌟 注意：这里必须传入包装后的 contextThemeWrapper
        View view = LayoutInflater.from(contextThemeWrapper).inflate(R.layout.dialog_edit_transaction, null);
        dialog.setContentView(view);

        TextInputEditText etAmount = view.findViewById(R.id.et_edit_amount);
        TextInputEditText etCategory = view.findViewById(R.id.et_edit_category);
        TextInputEditText etRemark = view.findViewById(R.id.et_edit_remark);
        Button btnSave = view.findViewById(R.id.btn_save_changes);

        // 回显数据
        etAmount.setText(String.valueOf(expense.getAmount() / 100.0));
        etCategory.setText(expense.getCategoryName());
        if (expense.getRemark() != null) {
            etRemark.setText(expense.getRemark());
        }

        btnSave.setOnClickListener(v -> {
            try {
                double newAmountDouble = Double.parseDouble(etAmount.getText().toString());
                long newAmountCents = (long) (newAmountDouble * 100);
                String newCategory = etCategory.getText().toString().trim();
                String newRemark = etRemark.getText().toString().trim();

                expense.setAmount(newAmountCents);
                expense.setCategoryName(newCategory.isEmpty() ? "其他支出" : newCategory);
                expense.setRemark(newRemark);

                new Thread(() -> {
                    db.expenseDao().updateExpense(expense);
                    runOnUiThread(() -> {
                        dialog.dismiss();
                        Toast.makeText(this, "修改成功", Toast.LENGTH_SHORT).show();
                    });
                }).start();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入合法的金额", Toast.LENGTH_SHORT).show();
            }
        });

        // 让 BottomSheet 背景透明以展示我们自绘的圆角背景
        ((View) view.getParent()).setBackgroundColor(Color.TRANSPARENT);
        dialog.show();
    }


    // -----------------------------------------------------
    // 内部 Adapter 实现
    // -----------------------------------------------------
    private class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_daily_transaction, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Expense expense = expenseList.get(position);

            holder.tvCategory.setText(expense.getCategoryName() != null ? expense.getCategoryName() : "其他支出");

            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
            String timeStr = timeFormat.format(new Date(expense.getTimestamp()));

            // 如果有备注，拼接到时间后面展示
            if (expense.getRemark() != null && !expense.getRemark().trim().isEmpty()) {
                holder.tvTime.setText(timeStr + " | " + expense.getRemark());
            } else {
                holder.tvTime.setText(timeStr);
            }

            double yuan = expense.getAmount() / 100.0;
            holder.tvAmount.setText(String.format("-¥%.2f", yuan));

            // 🌟 核心：点击 Item 触发修改弹窗
            holder.itemView.setOnClickListener(v -> showEditDialog(expense));
        }

        @Override
        public int getItemCount() {
            return expenseList.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvCategory, tvTime, tvAmount;
            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvCategory = itemView.findViewById(R.id.tv_item_category);
                tvTime = itemView.findViewById(R.id.tv_item_time);
                tvAmount = itemView.findViewById(R.id.tv_item_amount);
            }
        }
    }
}
