package com.monsieurmahjong.iqoowang.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.view.PercentagePieChartView;
import com.monsieurmahjong.iqoowang.view.SegmentedControlView;
import com.monsieurmahjong.iqoowang.view.SmoothLineChartView;

import java.util.ArrayList;
import java.util.List;

public class StatisticsFragment extends Fragment {

    private SegmentedControlView segmentedTab;
    private SmoothLineChartView smoothLineChart;
    private PercentagePieChartView percentagePieChart;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_statistics, container, false);

        segmentedTab = view.findViewById(R.id.segmented_tab);
        smoothLineChart = view.findViewById(R.id.smooth_line_chart);
        percentagePieChart = view.findViewById(R.id.percentage_pie_chart);

        setupChartInteractions();
        return view;
    }

    private void setupChartInteractions() {
        // 监听自定义时间周期切换器
        segmentedTab.setOnTabSelectedListener((index, text) -> {
            if (index == 0) { // 周数据
                smoothLineChart.setData(new float[]{0.2f, 0.45f, 0.3f, 0.65f, 0.35f, 0.9f, 0.7f});
            } else if (index == 1) { // 月数据
                smoothLineChart.setData(new float[]{0.5f, 0.2f, 0.8f, 0.4f, 0.6f, 0.3f, 0.85f});
            } else { // 年数据
                smoothLineChart.setData(new float[]{0.1f, 0.2f, 0.3f, 0.5f, 0.7f, 0.8f, 0.95f});
            }
        });

        // 绑定饼状图的多色区块参数数据
        List<PercentagePieChartView.PieEntry> entries = new ArrayList<>();
        entries.add(new PercentagePieChartView.PieEntry(0.40f, Color.parseColor("#003527")));
        entries.add(new PercentagePieChartView.PieEntry(0.25f, Color.parseColor("#505F76")));
        entries.add(new PercentagePieChartView.PieEntry(0.15f, Color.parseColor("#B7C8E1")));
        entries.add(new PercentagePieChartView.PieEntry(0.20f, Color.parseColor("#E7EEFF")));
        percentagePieChart.setEntries(entries);
    }
}

