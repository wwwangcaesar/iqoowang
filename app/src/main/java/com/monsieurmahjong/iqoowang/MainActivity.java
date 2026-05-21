package com.monsieurmahjong.iqoowang;

import static androidx.core.content.ContentProviderCompat.requireContext;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;


import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.monsieurmahjong.iqoowang.fragment.HistoryFragment;
import com.monsieurmahjong.iqoowang.fragment.SettingsFragment;
import com.monsieurmahjong.iqoowang.fragment.StatisticsFragment;

public class MainActivity extends AppCompatActivity {

    private Fragment historyFragment;
    private Fragment statisticsFragment;
    private Fragment settingsFragment;
    private final FragmentManager fm = getSupportFragmentManager();
    private Fragment activeFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        android.content.SharedPreferences sp = getSharedPreferences("AppConfig", android.content.Context.MODE_PRIVATE);
        sp.edit().putLong("month_budget_cents", 10000).apply();
        sp.edit().putLong("daily_budget_cents", 500).apply();

        // 初始化三大核心 Fragment 实例
        historyFragment = new HistoryFragment();
        statisticsFragment = new StatisticsFragment();
        settingsFragment = new SettingsFragment();

        // 默认将明细页面（HistoryFragment）呈现给用户
        activeFragment = historyFragment;
        fm.beginTransaction().add(R.id.fragment_container, settingsFragment).hide(settingsFragment).commit();
        fm.beginTransaction().add(R.id.fragment_container, statisticsFragment).hide(statisticsFragment).commit();
        fm.beginTransaction().add(R.id.fragment_container, historyFragment).commit();

        BottomNavigationView navView = findViewById(R.id.bottom_navigation);

        // 拦截点击事件进行单路由转发
        navView.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_detail) { // 点击明细
                fm.beginTransaction().hide(activeFragment).show(historyFragment).commit();
                activeFragment = historyFragment;
                return true;
            } else if (id == R.id.nav_statistics) { // 点击统计
                fm.beginTransaction().hide(activeFragment).show(statisticsFragment).commit();
                activeFragment = statisticsFragment;
                return true;
            } else if (id == R.id.nav_add) { // 点击记账标签 -> 满足要求，直接跳转独立外部Activity
                Intent intent = new Intent(MainActivity.this, QuickLogActivity.class);
                startActivity(intent);
                return false; // 返回 false 确保高亮状态依旧停留在原先的 Tab 节点上
            } else if (id == R.id.nav_settings) { // 点击设置
                fm.beginTransaction().hide(activeFragment).show(settingsFragment).commit();
                activeFragment = settingsFragment;
                return true;
            }
            return false;
        });
    }
}
