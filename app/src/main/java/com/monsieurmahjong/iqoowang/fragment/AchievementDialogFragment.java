package com.monsieurmahjong.iqoowang.fragment;

// AchievementDialogFragment.java

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.adapter.AchievementAdapter;
import com.monsieurmahjong.iqoowang.connect.Achievement;

import java.util.List;


public class AchievementDialogFragment extends DialogFragment {

    private List<Achievement> achievementList;

    public static AchievementDialogFragment newInstance(List<Achievement> achievementList) {
        AchievementDialogFragment fragment = new AchievementDialogFragment();
        fragment.achievementList = achievementList;
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_achievement_list, container, false);

        // 设置Dialog样式
        Dialog dialog = getDialog();
        if (dialog != null) {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setBackgroundDrawableResource(android.R.color.transparent);
                // 添加跳脱动画
                window.setWindowAnimations(R.style.DialogScaleAnimation);
            }
        }

        // 初始化关闭按钮
        ImageView ivClose = view.findViewById(R.id.iv_close);
        ivClose.setOnClickListener(v -> dismiss());

        // 计算已解锁数量
        int unlockedCount = 0;
        for (Achievement achievement : achievementList) {
            if (achievement.isUnlocked()) {
                unlockedCount++;
            }
        }

        // 设置统计文本
        TextView tvCount = view.findViewById(R.id.tv_achievement_count);
        tvCount.setText(String.format("已解锁 %d / %d 个成就", unlockedCount, achievementList.size()));

        // 初始化RecyclerView
        RecyclerView rvAchievements = view.findViewById(R.id.rv_achievements);
        rvAchievements.setLayoutManager(new LinearLayoutManager(getContext()));
        AchievementAdapter adapter = new AchievementAdapter(getContext(), achievementList);
        rvAchievements.setAdapter(adapter);

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        // 确保Dialog宽度充满屏幕
        Dialog dialog = getDialog();
        if (dialog != null) {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }
    }
}
