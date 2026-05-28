package com.monsieurmahjong.iqoowang;

import android.os.Bundle;
import android.transition.Transition;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_day_detail);

        db = AppDatabase.getDatabase(this);

        String dateStr = getIntent().getStringExtra("date");
        double totalAmount = getIntent().getDoubleExtra("amount", 0.0);

        TextView tvDate = findViewById(R.id.tv_detail_date);
        TextView tvTotal = findViewById(R.id.tv_detail_total);
        rvTransactions = findViewById(R.id.rv_daily_transactions);

        tvDate.setText(dateStr + " 消费总览");
        tvTotal.setText(String.format("¥%.2f", totalAmount));

        // 配置流水列表
        rvTransactions.setLayoutManager(new LinearLayoutManager(this));
        detailAdapter = new TransactionAdapter();
        rvTransactions.setAdapter(detailAdapter);

        // 🌟 核心打通：绑定观察本地 Room 数据库的真实当日流水明细 LiveData
        if (dateStr != null) {
            db.expenseDao().getDailyExpenses(dateStr).observe(this, expenses -> {
                if (expenses != null) {
                    expenseList.clear();
                    expenseList.addAll(expenses);
                    detailAdapter.notifyDataSetChanged();
                }
            });
        }

        // 入场细节动画过渡
        rvTransactions.setAlpha(0f);
        rvTransactions.setTranslationY(60f);
        getWindow().getSharedElementEnterTransition().addListener(new Transition.TransitionListener() {
            @Override
            public void onTransitionEnd(Transition transition) {
                rvTransactions.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(350)
                        .start();
            }
            @Override public void onTransitionStart(Transition transition) {}
            @Override public void onTransitionCancel(Transition transition) {}
            @Override public void onTransitionPause(Transition transition) {}
            @Override public void onTransitionResume(Transition transition) {}
        });
    }

    // 专属当日明细的 Adapter
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

            // 绑定分类名称（如果分类为空，兜底显示"其他支出"）
            holder.tvCategory.setText(expense.getCategoryName() != null ? expense.getCategoryName() : "其他支出");

            // 格式化具体时间戳
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
            holder.tvTime.setText(timeFormat.format(new Date(expense.getTimestamp())));

            // 格式化金额 (分转为元)
            double yuan = expense.getAmount() / 100.0;
            holder.tvAmount.setText(String.format("-¥%.2f", yuan));
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
