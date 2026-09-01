package com.monsieurmahjong.iqoowang;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityOptionsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.monsieurmahjong.iqoowang.connect.GalleryItem;
import com.monsieurmahjong.iqoowang.dao.AppDatabase;
import com.monsieurmahjong.iqoowang.dao.Expense;
import com.monsieurmahjong.iqoowang.view.ZoomableRecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HistoryGalleryActivity extends AppCompatActivity {

    public enum ViewLevel { YEAR, MONTH, WEEK }
    private ViewLevel currentLevel = ViewLevel.YEAR;

    private ZoomableRecyclerView rvGallery;
    private GridLayoutManager layoutManager;
    private GalleryAdapter adapter;
    private AppDatabase db;

    private List<GalleryItem> displayList = new ArrayList<>();

    private int selectedYear;
    private int selectedMonth;

    // 真实“当前时间”基准，用于自动定位到当前年/月/日
    private final Calendar realNowCal = Calendar.getInstance();
    private final int REAL_CURRENT_YEAR = realNowCal.get(Calendar.YEAR);
    private final int REAL_CURRENT_MONTH = realNowCal.get(Calendar.MONTH) + 1;
    private final int REAL_CURRENT_DAY = realNowCal.get(Calendar.DAY_OF_MONTH);

    // 从日历弹窗等入口直接跳转过来时，需要定位并自动打开的目标日期
    private int pendingJumpDay = -1;

    // 缓存不同层级的滑动浏览位置
    private Map<ViewLevel, Parcelable> scrollStateMap = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history_gallery);

        db = AppDatabase.getDatabase(this);
        rvGallery = findViewById(R.id.rv_history_gallery);

        layoutManager = new GridLayoutManager(this, 28);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (displayList.get(position).viewType == GalleryItem.TYPE_HEADER) {
                    return 28;
                }
                switch (currentLevel) {
                    case YEAR:  return 7;  // 一行4个月
                    case MONTH: return 7;  // 一行4个周
                    case WEEK:  return 4;  // 一行7天 (28/4=7)
                    default:    return 14;
                }
            }
        });

        rvGallery.setLayoutManager(layoutManager);
        adapter = new GalleryAdapter();
        rvGallery.setAdapter(adapter);

        // 双指手势监听
        rvGallery.setOnZoomGestureListener(new ZoomableRecyclerView.OnZoomGestureListener() {
            @Override
            public void onZoomOut() {
                saveCurrentScrollState();
                if (currentLevel == ViewLevel.WEEK) {
                    loadMonthData(selectedYear);
                } else if (currentLevel == ViewLevel.MONTH) {
                    loadYearData();
                }
            }

            @Override
            public void onZoomIn(int focusedPosition) {
                if (focusedPosition < 0 || focusedPosition >= displayList.size()) return;
                GalleryItem focusedItem = displayList.get(focusedPosition);

                if (focusedItem.viewType == GalleryItem.TYPE_GRID_ITEM) {
                    saveCurrentScrollState();

                    if (currentLevel == ViewLevel.YEAR) {
                        selectedYear = focusedItem.year;
                        loadMonthData(selectedYear);
                    } else if (currentLevel == ViewLevel.MONTH) {
                        selectedMonth = focusedItem.month;
                        loadWeekData(selectedYear, selectedMonth);
                    } else if (currentLevel == ViewLevel.WEEK) {
                        // 🌟 修复：双指张开放大单天时，同样触发共享元素数字飞入动画
                        RecyclerView.ViewHolder holder = rvGallery.findViewHolderForAdapterPosition(focusedPosition);
                        View transitionView = (holder != null) ? holder.itemView.findViewById(R.id.tv_grid_amount) : null;

                        Intent intent = new Intent(HistoryGalleryActivity.this, DayDetailActivity.class);
                        intent.putExtra("date", focusedItem.dateStr);
                        intent.putExtra("amount", focusedItem.amount);

                        if (transitionView != null && focusedItem.hasConsumed) {
                            ActivityOptionsCompat options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                                    HistoryGalleryActivity.this, transitionView, "transition_amount"
                            );
                            startActivity(intent, options.toBundle());
                        } else {
                            startActivity(intent);
                        }
                    }
                }
            }
        });

        selectedYear = REAL_CURRENT_YEAR;
        selectedMonth = REAL_CURRENT_MONTH;

        handleIncomingIntent(getIntent(), false);
    }

    /**
     * 提取 Intent 中的 jump_year/jump_month/jump_day 并处理跳转。
     *
     * 重要：本 Activity 声明了 android:launchMode="singleTask"，
     * 当实例已存在于任务栈中时，再次 startActivity() 会走 onNewIntent()，
     * 而不会重新调用 onCreate()。之前跳转逻辑只写在 onCreate() 里，
     * 导致从日历弹窗点击时，若 Activity 已经存在过，新的跳转参数会被静默丢弃，
     * 必须退出重进（触发真正的 onCreate）才能看到正确结果。
     * 现在 onCreate 和 onNewIntent 都调用同一套逻辑，修复这个问题。
     */
    private void handleIncomingIntent(Intent intent, boolean isNewIntent) {
        if (intent == null) {
            if (!isNewIntent) loadYearData();
            return;
        }

        int jumpYear = intent.getIntExtra("jump_year", -1);
        int jumpMonth = intent.getIntExtra("jump_month", -1);
        int jumpDay = intent.getIntExtra("jump_day", -1);

        if (jumpYear > 0 && jumpMonth > 0 && jumpDay > 0) {
            // 来自日历弹窗的直接跳转：定位到对应年/月的周视图，并自动打开当天详情
            if (isNewIntent) {
                saveCurrentScrollState();
            }
            selectedYear = jumpYear;
            selectedMonth = jumpMonth;
            pendingJumpDay = jumpDay;
            loadWeekData(selectedYear, selectedMonth);
        } else if (!isNewIntent) {
            // 默认进入（仅 onCreate 首次启动时）：自动定位到当前年份
            loadYearData();
        }
        // isNewIntent 且无跳转参数时（比如从其他入口普通重新打开），保持当前页面状态不变，不做额外处理
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent, true);
    }

    private void saveCurrentScrollState() {
        if (layoutManager != null) {
            scrollStateMap.put(currentLevel, layoutManager.onSaveInstanceState());
        }
    }

    private void restoreScrollState() {
        if (scrollStateMap.containsKey(currentLevel)) {
            layoutManager.onRestoreInstanceState(scrollStateMap.get(currentLevel));
        } else {
            rvGallery.scrollToPosition(0);
        }
    }

    /**
     * 【1. 年视图数据加载】
     */
    private void loadYearData() {
        currentLevel = ViewLevel.YEAR;
        displayList.clear();
        adapter.notifyDataSetChanged();

        new Thread(() -> {
            Calendar cal = Calendar.getInstance();
            int currentYear = cal.get(Calendar.YEAR);
            List<GalleryItem> temp = new ArrayList<>();

            for (int y = currentYear; y > currentYear - 3; y--) {
                temp.add(new GalleryItem(GalleryItem.TYPE_HEADER, y + "年 消费回顾"));

                for (int m = 1; m <= 12; m++) {
                    cal.clear();
                    cal.set(Calendar.YEAR, y);
                    cal.set(Calendar.MONTH, m - 1);
                    cal.set(Calendar.DAY_OF_MONTH, 1);
                    long start = cal.getTimeInMillis();

                    cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH));
                    cal.set(Calendar.HOUR_OF_DAY, 23);
                    cal.set(Calendar.MINUTE, 59);
                    cal.set(Calendar.SECOND, 59);
                    cal.set(Calendar.MILLISECOND, 999);
                    long end = cal.getTimeInMillis();

                    List<Expense> expenses = db.expenseDao().getExpensesInRangeSync(start, end);
                    long totalCents = 0;
                    if (expenses != null) {
                        for (Expense e : expenses) totalCents += e.getAmount();
                    }
                    temp.add(new GalleryItem(GalleryItem.TYPE_GRID_ITEM, m + "月", totalCents / 100.0, totalCents > 0, y, m, 1, null));
                }
            }

            runOnUiThread(() -> {
                displayList.addAll(temp);
                adapter.notifyDataSetChanged();
                restoreScrollState();
            });
        }).start();
    }

    /**
     * 【2. 月视图数据加载】
     * 采用线性按天归类算法，确保周级账目的总和跟年/日全对齐
     */
    private void loadMonthData(int year) {
        currentLevel = ViewLevel.MONTH;
        displayList.clear();
        adapter.notifyDataSetChanged();

        new Thread(() -> {
            List<GalleryItem> temp = new ArrayList<>();
            temp.add(new GalleryItem(GalleryItem.TYPE_HEADER, year + "年 · 季度周度账目拆解"));

            int autoScrollIndex = -1;

            Calendar cal = Calendar.getInstance();
            cal.setFirstDayOfWeek(Calendar.MONDAY);

            for (int m = 1; m <= 12; m++) {
                temp.add(new GalleryItem(GalleryItem.TYPE_HEADER, year + "年 " + m + "月"));
                if (year == REAL_CURRENT_YEAR && m == REAL_CURRENT_MONTH) {
                    autoScrollIndex = temp.size() - 1;
                }

                cal.clear();
                cal.set(Calendar.YEAR, year);
                cal.set(Calendar.MONTH, m - 1);
                int maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

                // 利用有序Map存储本月各周的金额累加
                Map<Integer, Long> weekAmountMap = new LinkedHashMap<>();
                int currentWeek = 1;

                for (int d = 1; d <= maxDays; d++) {
                    cal.set(Calendar.DAY_OF_MONTH, d);
                    int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);

                    // 只要遇到周一且不是1号，周数权重自然加1
                    if (dayOfWeek == Calendar.MONDAY && d > 1) {
                        currentWeek++;
                    }

                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    cal.set(Calendar.MILLISECOND, 0);
                    long start = cal.getTimeInMillis();

                    cal.set(Calendar.HOUR_OF_DAY, 23);
                    cal.set(Calendar.MINUTE, 59);
                    cal.set(Calendar.SECOND, 59);
                    cal.set(Calendar.MILLISECOND, 999);
                    long end = cal.getTimeInMillis();

                    List<Expense> expenses = db.expenseDao().getExpensesInRangeSync(start, end);
                    long dayCents = 0;
                    if (expenses != null) {
                        for (Expense e : expenses) dayCents += e.getAmount();
                    }

                    long existCents = weekAmountMap.containsKey(currentWeek) ? weekAmountMap.get(currentWeek) : 0L;
                    weekAmountMap.put(currentWeek, existCents + dayCents);
                }

                // 将该月清洗出的周网格项压入数据集
                for (Map.Entry<Integer, Long> entry : weekAmountMap.entrySet()) {
                    long totalCents = entry.getValue();
                    temp.add(new GalleryItem(GalleryItem.TYPE_GRID_ITEM, "第" + entry.getKey() + "周", totalCents / 100.0, totalCents > 0, year, m, entry.getKey(), null));
                }
            }

            final int finalAutoScrollIndex = autoScrollIndex;
            runOnUiThread(() -> {
                displayList.addAll(temp);
                adapter.notifyDataSetChanged();
                // 若是首次进入该层级（无缓存滑动位置）且当前浏览的是真实当前年份，自动定位到当前月份
                if (!scrollStateMap.containsKey(currentLevel) && finalAutoScrollIndex >= 0) {
                    rvGallery.scrollToPosition(finalAutoScrollIndex);
                } else {
                    restoreScrollState();
                }
            });
        }).start();
    }

    /**
     * 【3. 真实周列表（天网格）核心修复逻辑】
     * 彻底废弃WEEK_OF_MONTH高危API，使用纯线性日历清洗算法，5月26、27日100%完美归仓显示
     */
    private void loadWeekData(int year, int month) {
        currentLevel = ViewLevel.WEEK;
        displayList.clear();
        adapter.notifyDataSetChanged();

        final int jumpDayRequested = pendingJumpDay;
        pendingJumpDay = -1;

        new Thread(() -> {
            List<GalleryItem> temp = new ArrayList<>();
            Calendar cal = Calendar.getInstance();
            cal.clear();
            cal.setFirstDayOfWeek(Calendar.MONDAY);
            cal.set(Calendar.YEAR, year);
            cal.set(Calendar.MONTH, month - 1);

            int maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

            // 临时群组容器，把天按对应的周归入队列
            Map<Integer, List<GalleryItem>> weekToDaysGroup = new LinkedHashMap<>();
            int currentWeek = 1;

            for (int d = 1; d <= maxDays; d++) {
                cal.set(Calendar.DAY_OF_MONTH, d);
                int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);

                if (dayOfWeek == Calendar.MONDAY && d > 1) {
                    currentWeek++;
                }

                if (!weekToDaysGroup.containsKey(currentWeek)) {
                    weekToDaysGroup.put(currentWeek, new ArrayList<>());
                }

                String dateStr = sdf.format(cal.getTime());

                // 24小时绝对闭环区间控制
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                long start = cal.getTimeInMillis();

                cal.set(Calendar.HOUR_OF_DAY, 23);
                cal.set(Calendar.MINUTE, 59);
                cal.set(Calendar.SECOND, 59);
                cal.set(Calendar.MILLISECOND, 999);
                long end = cal.getTimeInMillis();

                List<Expense> expenses = db.expenseDao().getExpensesInRangeSync(start, end);
                long totalCents = 0;
                if (expenses != null) {
                    for (Expense e : expenses) totalCents += e.getAmount();
                }

                double totalYuan = totalCents / 100.0;
                weekToDaysGroup.get(currentWeek).add(new GalleryItem(
                        GalleryItem.TYPE_GRID_ITEM, d + "日", totalYuan, totalCents > 0, year, month, currentWeek, dateStr
                ));
            }

            // 线性拼装扁平列表：周标识Header + 属于该周的7个天卡片
            int autoScrollIndex = -1;
            int jumpIndex = -1;
            for (Map.Entry<Integer, List<GalleryItem>> entry : weekToDaysGroup.entrySet()) {
                temp.add(new GalleryItem(GalleryItem.TYPE_HEADER, year + "年" + month + "月 · 第" + entry.getKey() + "周"));
                for (GalleryItem gi : entry.getValue()) {
                    temp.add(gi);
                    int idx = temp.size() - 1;
                    if (year == REAL_CURRENT_YEAR && month == REAL_CURRENT_MONTH
                            && gi.itemLabel != null && gi.itemLabel.equals(REAL_CURRENT_DAY + "日")) {
                        autoScrollIndex = idx;
                    }
                    if (jumpDayRequested > 0 && gi.itemLabel != null && gi.itemLabel.equals(jumpDayRequested + "日")) {
                        jumpIndex = idx;
                    }
                }
            }

            final int finalAutoScrollIndex = autoScrollIndex;
            final GalleryItem jumpTargetItem = jumpIndex >= 0 ? temp.get(jumpIndex) : null;
            final int finalJumpIndex = jumpIndex;

            runOnUiThread(() -> {
                displayList.addAll(temp);
                adapter.notifyDataSetChanged();

                if (jumpTargetItem != null) {
                    // 来自日历弹窗的目标日期：定位并自动打开当天消费详情
                    rvGallery.scrollToPosition(finalJumpIndex);
                    // 🌟 增强修复：使用双重延迟确保数据和UI都完全就绪
                    // 第一层post确保RecyclerView布局完成
                    rvGallery.post(() -> {
                        // 第二层postDelayed增加200ms缓冲，确保数据库查询也完成
                        rvGallery.postDelayed(() -> {
                            android.util.Log.d("HistoryGallery", "Opening DayDetail for: " + jumpTargetItem.dateStr);
                            Intent intent = new Intent(HistoryGalleryActivity.this, DayDetailActivity.class);
                            intent.putExtra("date", jumpTargetItem.dateStr);
                            intent.putExtra("amount", jumpTargetItem.amount);
                            startActivity(intent);
                        }, 200);
                    });
                } else if (!scrollStateMap.containsKey(currentLevel) && finalAutoScrollIndex >= 0) {
                    // 首次进入该层级且当前浏览的是真实当前月份，自动定位到当前日
                    rvGallery.scrollToPosition(finalAutoScrollIndex);
                } else {
                    restoreScrollState();
                }
            });
        }).start();
    }

    @Override
    public void onBackPressed() {
        saveCurrentScrollState();
        if (currentLevel == ViewLevel.WEEK) {
            loadMonthData(selectedYear);
        } else if (currentLevel == ViewLevel.MONTH) {
            loadYearData();
        } else {
            super.onBackPressed();
        }
    }

    private class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.ViewHolder> {
        @Override
        public int getItemViewType(int position) { return displayList.get(position).viewType; }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history_gallery, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            GalleryItem item = displayList.get(position);

            if (item.viewType == GalleryItem.TYPE_HEADER) {
                holder.llHeader.setVisibility(View.VISIBLE);
                holder.clItem.setVisibility(View.GONE);

                // 🌟 【Header 文字】：改用深邃的深绿色，有质感且清晰
                holder.tvHeaderTitle.setText(item.title);
                holder.tvHeaderTitle.setTextColor(0xFF064E3B);
            } else {
                holder.llHeader.setVisibility(View.GONE);
                holder.clItem.setVisibility(View.VISIBLE);

                holder.tvGridLabel.setText(item.itemLabel);
                holder.tvGridAmount.setText(item.hasConsumed ? String.format("¥%.1f", item.amount) : "—");

                if (item.hasConsumed) {
                    // 🌟 【已消费：轻松明亮风格】
                    // 背景：使用带点透明度的薄荷绿或清爽淡蓝 (这里用 0xFFE6F4EA 或 0xFF80BEA6 的淡化版)
                    holder.clItem.setBackgroundResource(R.drawable.bg_gallery_item_consumed);

                    // 状态图标：使用你风格内的亮色
                    holder.ivStatus.setImageResource(R.drawable.ic_status_consumed);

                    // 文字颜色：标签用深绿，金额用稳重的深绿或黑，看起来非常清爽舒适
                    holder.tvGridLabel.setTextColor(0xFF064E3B);
                    holder.tvGridAmount.setTextColor(0xFF064E3B);
                } else {
                    // 🌟 【未消费：干净空白风格】
                    // 背景：完全明亮的浅灰/接近白色
                    holder.clItem.setBackgroundResource(R.drawable.bg_gallery_item_empty);

                    holder.ivStatus.setImageResource(R.drawable.ic_status_empty);

                    // 文字颜色：轻微的灰色，不抢眼
                    holder.tvGridLabel.setTextColor(0xFF9E9E9E);
                    holder.tvGridAmount.setTextColor(0xFFCCCCCC);
                }

                // 点击下钻逻辑与共享元素飞入效果保持完美兼容
                holder.itemView.setOnClickListener(v -> {
                    saveCurrentScrollState();
                    if (currentLevel == ViewLevel.YEAR) {
                        selectedYear = item.year;
                        loadMonthData(selectedYear);
                    } else if (currentLevel == ViewLevel.MONTH) {
                        selectedMonth = item.month;
                        loadWeekData(selectedYear, selectedMonth);
                    } else if (currentLevel == ViewLevel.WEEK) {
                        Intent intent = new Intent(HistoryGalleryActivity.this, DayDetailActivity.class);
                        intent.putExtra("date", item.dateStr);
                        intent.putExtra("amount", item.amount);

                        if (item.hasConsumed) {
                            ActivityOptionsCompat options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                                    HistoryGalleryActivity.this, holder.tvGridAmount, "transition_amount"
                            );
                            startActivity(intent, options.toBundle());
                        } else {
                            startActivity(intent);
                        }
                    }
                });
            }
        }


        @Override
        public int getItemCount() { return displayList.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            android.widget.LinearLayout llHeader;
            View clItem;
            TextView tvHeaderTitle, tvGridLabel, tvGridAmount;
            ImageView ivStatus;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                llHeader = itemView.findViewById(R.id.ll_header_container);
                clItem = itemView.findViewById(R.id.cl_item_container);
                tvHeaderTitle = itemView.findViewById(R.id.tv_header_title);
                tvGridLabel = itemView.findViewById(R.id.tv_grid_label);
                tvGridAmount = itemView.findViewById(R.id.tv_grid_amount);
                ivStatus = itemView.findViewById(R.id.iv_status_icon);
            }
        }
    }
}
