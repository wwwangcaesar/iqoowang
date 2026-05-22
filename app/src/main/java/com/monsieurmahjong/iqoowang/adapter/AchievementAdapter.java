package com.monsieurmahjong.iqoowang.adapter;

// AchievementAdapter.java

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;


import com.monsieurmahjong.iqoowang.R;
import com.monsieurmahjong.iqoowang.connect.Achievement;

import java.util.List;

public class AchievementAdapter extends RecyclerView.Adapter<AchievementAdapter.AchievementViewHolder> {

    private final List<Achievement> achievementList;
    private final Context context;

    public AchievementAdapter(Context context, List<Achievement> achievementList) {
        this.context = context;
        this.achievementList = achievementList;
    }

    @NonNull
    @Override
    public AchievementViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_achievement, parent, false);
        return new AchievementViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AchievementViewHolder holder, int position) {
        Achievement achievement = achievementList.get(position);

        holder.tvName.setText(achievement.getName());
        holder.tvDescription.setText(achievement.getDescription());

        if (achievement.isUnlocked()) {
            holder.itemView.setAlpha(1.0f);
            holder.tvIcon.setImageResource(achievement.getIcon());
            holder.tvStatus.setText("已解锁");
            holder.tvStatus.setTextColor(context.getColor(R.color.primary_dark));
        } else {
            holder.itemView.setAlpha(0.6f);
            holder.tvIcon.setImageResource(achievement.getIcon());
            holder.tvStatus.setText("lock");
            holder.tvStatus.setTextColor(context.getColor(R.color.text_secondary));
        }
    }

    @Override
    public int getItemCount() {
        return achievementList.size();
    }

    public static class AchievementViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvDescription, tvStatus;
        ImageView tvIcon;
        public AchievementViewHolder(@NonNull View itemView) {
            super(itemView);
            tvIcon = itemView.findViewById(R.id.tv_icon);
            tvName = itemView.findViewById(R.id.tv_name);
            tvDescription = itemView.findViewById(R.id.tv_description);
            tvStatus = itemView.findViewById(R.id.tv_status);
        }
    }
}
