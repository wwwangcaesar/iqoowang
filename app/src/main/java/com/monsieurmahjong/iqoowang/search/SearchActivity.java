package com.monsieurmahjong.iqoowang.search;

import android.app.DatePickerDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;
import com.monsieurmahjong.iqoowang.utils.EditExpenseDialog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 全局聚合搜索：可搜索当前数据库全部字段（分类名、备注等），
 * 并支持按分类 / 日期区间 / 金额区间进一步筛选。
 * 只读查询数据库，不会修改任何已记录的历史数据。
 */
public class SearchActivity extends AppCompatActivity {

    private AppDatabase db;
    private EditText etSearch;
    private LinearLayout containerFilterChips;
    private RecyclerView rvResults;
    private TextView tvSummary;
    private TextView tvEmpty;
    private ResultAdapter adapter;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    // 筛选状态
    private String selectedCategory = null; // null = 全部
    private Long startTime = null;
    private Long endTime = null;
    private Long minAmountCents = null;
    private Long maxAmountCents = null;

    private final List<TextView> categoryChipViews = new ArrayList<>();
    private TextView chipDateRange;
    private TextView chipAmountRange;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        db = AppDatabase.getDatabase(this);

        findViewById(R.id.tv_back).setOnClickListener(v -> finish());
        etSearch = findViewById(R.id.et_search);
        containerFilterChips = findViewById(R.id.container_filter_chips);
        rvResults = findViewById(R.id.rv_search_results);
        tvSummary = findViewById(R.id.tv_result_summary);
        tvEmpty = findViewById(R.id.tv_empty);

        rvResults.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ResultAdapter();
        rvResults.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { scheduleSearch(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        buildFilterChips();
        performSearch();
    }

    private void scheduleSearch() {
        if (pendingSearch != null) debounceHandler.removeCallbacks(pendingSearch);
        pendingSearch = this::performSearch;
        debounceHandler.postDelayed(pendingSearch, 300);
    }

    /** 构建筛选行：分类 Chips + 日期区间 + 金额区间 */
    private void buildFilterChips() {
        containerFilterChips.removeAllViews();
        categoryChipViews.clear();

        TextView chipAll = buildChip("全部");
        chipAll.setOnClickListener(v -> {
            selectedCategory = null;
            refreshChipStyles();
            performSearch();
        });
        containerFilterChips.addView(chipAll);
        categoryChipViews.add(chipAll);

        new Thread(() -> {
            List<String> categories = db.expenseDao().getAllCategoryNamesSync();
            runOnUiThread(() -> {
                for (String cat : categories) {
                    if (cat == null || cat.trim().isEmpty()) continue;
                    TextView chip = buildChip(cat);
                    chip.setOnClickListener(v -> {
                        selectedCategory = cat;
                        refreshChipStyles();
                        performSearch();
                    });
                    containerFilterChips.addView(chip);
                    categoryChipViews.add(chip);
                }

                chipDateRange = buildChip("日期区间");
                chipDateRange.setOnClickListener(v -> showDateRangeDialog());
                containerFilterChips.addView(chipDateRange);

                chipAmountRange = buildChip("金额区间");
                chipAmountRange.setOnClickListener(v -> showAmountRangeDialog());
                containerFilterChips.addView(chipAmountRange);

                refreshChipStyles();
            });
        }).start();
    }

    private TextView buildChip(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setPadding(dp2px(14), dp2px(8), dp2px(14), dp2px(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp2px(8));
        tv.setLayoutParams(lp);
        tv.setBackground(buildChipBg(false));
        tv.setTextColor(Color.parseColor("#111C2D"));
        return tv;
    }

    private GradientDrawable buildChipBg(boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp2px(20));
        d.setColor(selected ? Color.parseColor("#003527") : Color.parseColor("#F0F3FF"));
        return d;
    }

    private void refreshChipStyles() {
        for (TextView chip : categoryChipViews) {
            String label = chip.getText().toString();
            boolean selected = (selectedCategory == null && label.equals("全部")) || label.equals(selectedCategory);
            chip.setBackground(buildChipBg(selected));
            chip.setTextColor(selected ? Color.WHITE : Color.parseColor("#111C2D"));
        }
        if (chipDateRange != null) {
            boolean active = startTime != null || endTime != null;
            chipDateRange.setBackground(buildChipBg(active));
            chipDateRange.setTextColor(active ? Color.WHITE : Color.parseColor("#111C2D"));
        }
        if (chipAmountRange != null) {
            boolean active = minAmountCents != null || maxAmountCents != null;
            chipAmountRange.setBackground(buildChipBg(active));
            chipAmountRange.setTextColor(active ? Color.WHITE : Color.parseColor("#111C2D"));
        }
    }

    private void showDateRangeDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp2px(24), dp2px(16), dp2px(24), dp2px(8));

        TextView tvStart = new TextView(this);
        tvStart.setText(startTime != null ? formatDate(startTime) : "选择开始日期");
        tvStart.setTextSize(15);
        tvStart.setPadding(0, dp2px(12), 0, dp2px(12));
        tvStart.setOnClickListener(v -> pickDate(true, tvStart));

        TextView tvEnd = new TextView(this);
        tvEnd.setText(endTime != null ? formatDate(endTime) : "选择结束日期");
        tvEnd.setTextSize(15);
        tvEnd.setPadding(0, dp2px(12), 0, dp2px(12));
        tvEnd.setOnClickListener(v -> pickDate(false, tvEnd));

        root.addView(tvStart);
        root.addView(tvEnd);

        new AlertDialog.Builder(this)
                .setTitle("选择日期区间")
                .setView(root)
                .setPositiveButton("确定", (dialog, which) -> {
                    refreshChipStyles();
                    performSearch();
                })
                .setNegativeButton("清空", (dialog, which) -> {
                    startTime = null;
                    endTime = null;
                    refreshChipStyles();
                    performSearch();
                })
                .show();
    }

    private void pickDate(boolean isStart, TextView label) {
        Calendar cal = Calendar.getInstance();
        Long current = isStart ? startTime : endTime;
        if (current != null) cal.setTimeInMillis(current);
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(year, month, dayOfMonth, isStart ? 0 : 23, isStart ? 0 : 59, isStart ? 0 : 59);
            long millis = picked.getTimeInMillis();
            if (isStart) startTime = millis; else endTime = millis;
            label.setText(formatDate(millis));
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private String formatDate(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(millis));
    }

    private void showAmountRangeDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp2px(24), dp2px(16), dp2px(24), dp2px(8));

        EditText etMin = new EditText(this);
        etMin.setHint("最小金额（元）");
        etMin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (minAmountCents != null) etMin.setText(String.valueOf(minAmountCents / 100.0));

        EditText etMax = new EditText(this);
        etMax.setHint("最大金额（元）");
        etMax.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (maxAmountCents != null) etMax.setText(String.valueOf(maxAmountCents / 100.0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp2px(8);
        etMax.setLayoutParams(lp);

        root.addView(etMin);
        root.addView(etMax);

        new AlertDialog.Builder(this)
                .setTitle("选择金额区间")
                .setView(root)
                .setPositiveButton("确定", (dialog, which) -> {
                    try {
                        String minStr = etMin.getText().toString().trim();
                        String maxStr = etMax.getText().toString().trim();
                        minAmountCents = minStr.isEmpty() ? null : (long) (Double.parseDouble(minStr) * 100);
                        maxAmountCents = maxStr.isEmpty() ? null : (long) (Double.parseDouble(maxStr) * 100);
                    } catch (NumberFormatException e) {
                        minAmountCents = null;
                        maxAmountCents = null;
                    }
                    refreshChipStyles();
                    performSearch();
                })
                .setNegativeButton("清空", (dialog, which) -> {
                    minAmountCents = null;
                    maxAmountCents = null;
                    refreshChipStyles();
                    performSearch();
                })
                .show();
    }

    private void performSearch() {
        String keyword = etSearch.getText() != null ? etSearch.getText().toString().trim() : "";
        String kw = keyword.isEmpty() ? null : keyword;
        String category = selectedCategory;
        Long st = startTime, et = endTime, minA = minAmountCents, maxA = maxAmountCents;

        new Thread(() -> {
            List<Expense> results = db.expenseDao().searchExpensesSync(kw, category, st, et, minA, maxA);
            long totalCents = 0;
            for (Expense e : results) totalCents += e.getAmount();
            long finalTotal = totalCents;

            runOnUiThread(() -> {
                adapter.setData(results);
                tvSummary.setText(String.format(Locale.getDefault(), "共 %d 笔，合计 ¥ %.2f", results.size(), finalTotal / 100.0));
                tvEmpty.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
                rvResults.setVisibility(results.isEmpty() ? View.GONE : View.VISIBLE);
            });
        }).start();
    }

    private void mapIconResource(String category, ImageView iv) {
        if (category == null) category = "";
        if (category.contains("餐饮") || category.toLowerCase().contains("food")) {
            iv.setImageResource(R.mipmap.ic_food);
        } else if (category.contains("交通") || category.toLowerCase().contains("transport")) {
            iv.setImageResource(R.mipmap.ic_transport);
        } else if (category.contains("购物") || category.toLowerCase().contains("shop")) {
            iv.setImageResource(R.mipmap.ic_shopping);
        } else if (category.contains("零食") || category.toLowerCase().contains("drinks")) {
            iv.setImageResource(R.mipmap.ic_drinks);
        } else if (category.contains("娱乐") || category.toLowerCase().contains("entertainment")) {
            iv.setImageResource(R.mipmap.ic_entertainment);
        } else {
            iv.setImageResource(R.mipmap.ic_other);
        }
    }

    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.VH> {
        private List<Expense> data = new ArrayList<>();

        void setData(List<Expense> newData) {
            data = newData;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_search_result, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Expense e = data.get(position);
            String cat = e.getCategoryName() != null ? e.getCategoryName() : "其他";
            holder.tvCategory.setText(cat);
            mapIconResource(cat, holder.ivIcon);

            String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(e.getTimestamp()));
            String remark = e.getRemark();
            holder.tvMeta.setText((remark != null && !remark.trim().isEmpty()) ? (timeStr + " · " + remark) : timeStr);

            holder.tvAmount.setText(String.format(Locale.getDefault(), "¥ %.2f", e.getAmount() / 100.0));

            // 点击条目 → 弹出修改记录 BottomSheet（复用 DayDetailActivity 同款弹窗），保存后刷新搜索结果
            holder.itemView.setOnClickListener(v ->
                    EditExpenseDialog.show(SearchActivity.this, db, e, SearchActivity.this::performSearch));
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView tvCategory, tvMeta, tvAmount;
            VH(@NonNull View itemView) {
                super(itemView);
                ivIcon = itemView.findViewById(R.id.iv_result_icon);
                tvCategory = itemView.findViewById(R.id.tv_result_category);
                tvMeta = itemView.findViewById(R.id.tv_result_meta);
                tvAmount = itemView.findViewById(R.id.tv_result_amount);
            }
        }
    }
}
