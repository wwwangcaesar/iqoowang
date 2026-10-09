package com.adb.iqoowang;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.adb.iqoowang.R;

import java.util.ArrayList;
import java.util.List;

public class DeviceAdapter extends BaseAdapter {

    public interface OnItemActionListener {
        void onTroubleshoot(DeviceItem device);
        void onDeviceSelected(DeviceItem device);
    }

    private final Context context;
    private final List<DeviceItem> originalList = new ArrayList<>();
    private final List<DeviceItem> displayList = new ArrayList<>();
    private final OnItemActionListener actionListener;
    private boolean adbOnly = false;

    public DeviceAdapter(Context context, OnItemActionListener listener) {
        this.context = context;
        this.actionListener = listener;
    }

    public void setDevices(List<DeviceItem> list) {
        originalList.clear();
        if (list != null) {
            originalList.addAll(list);
        }
        applyFilter();
    }

    public void addOrUpdateDevice(DeviceItem item) {
        int index = -1;
        for (int i = 0; i < originalList.size(); i++) {
            if (originalList.get(i).getIp().equals(item.getIp())) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            originalList.set(index, item);
        } else {
            // 将 ADB 就绪的设备优先排在前面
            if (item.isAdbReady()) {
                originalList.add(0, item);
            } else {
                originalList.add(item);
            }
        }
        applyFilter();
    }

    public void setAdbOnlyFilter(boolean adbOnly) {
        this.adbOnly = adbOnly;
        applyFilter();
    }

    public boolean isAdbOnly() {
        return adbOnly;
    }

    private void applyFilter() {
        displayList.clear();
        for (DeviceItem item : originalList) {
            if (!adbOnly || item.isAdbReady()) {
                displayList.add(item);
            }
        }
        notifyDataSetChanged();
    }

    public int getAdbReadyCount() {
        int count = 0;
        for (DeviceItem item : originalList) {
            if (item.isAdbReady()) count++;
        }
        return count;
    }

    public int getTvCount() {
        int count = 0;
        for (DeviceItem item : originalList) {
            if (item.getStatus() == DeviceItem.STATUS_TV_DETECTED) count++;
        }
        return count;
    }

    public int getTotalCount() {
        return originalList.size();
    }

    @Override
    public int getCount() {
        return displayList.size();
    }

    @Override
    public DeviceItem getItem(int position) {
        return displayList.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_device, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        DeviceItem item = getItem(position);
        bindData(holder, item);
        return convertView;
    }

    private void bindData(ViewHolder holder, DeviceItem item) {
        holder.tvDeviceName.setText(item.getDeviceName());
        holder.tvVendorBadge.setText(item.getVendorHint());

        String ipPortStr = item.getIp() + ":" + item.getPort();
        holder.tvIpAddress.setText(ipPortStr);

        if (item.getPingMs() > 0) {
            holder.tvLatency.setText("⚡ " + item.getPingMs() + " ms");
            holder.tvLatency.setVisibility(View.VISIBLE);
        } else {
            holder.tvLatency.setVisibility(View.GONE);
        }

        // 详细信息拼接
        StringBuilder detail = new StringBuilder();
        if (item.getExtraDetail() != null && !item.getExtraDetail().isEmpty()) {
            detail.append(item.getExtraDetail());
        }
        if (!item.getOpenPorts().isEmpty()) {
            if (detail.length() > 0) detail.append(" | ");
            detail.append("开放端口: ").append(item.getOpenPorts());
        }
        holder.tvDetailInfo.setText(detail.length() > 0 ? detail.toString() : "局域网活跃设备");

        // 状态徽标与呼吸灯设置
        switch (item.getStatus()) {
            case DeviceItem.STATUS_ADB_READY:
                holder.ivStatusDot.setImageResource(R.drawable.ic_dot_green);
                holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_cyber_badge_green);
                holder.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.cyber_neon_green));
                holder.tvStatusBadge.setText("ADB 5555 就绪");
                holder.cardContainer.setBackgroundResource(R.drawable.bg_cyber_card_highlight);
                break;
            case DeviceItem.STATUS_MDNS_ADB:
                holder.ivStatusDot.setImageResource(R.drawable.ic_dot_cyan);
                holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_cyber_badge_cyan);
                holder.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.cyber_neon_cyan));
                holder.tvStatusBadge.setText("无线调试 (" + item.getPort() + ")");
                holder.cardContainer.setBackgroundResource(R.drawable.bg_cyber_card_highlight);
                break;
            case DeviceItem.STATUS_TV_DETECTED:
                holder.ivStatusDot.setImageResource(R.drawable.ic_dot_amber);
                holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_cyber_badge_amber);
                holder.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.cyber_amber));
                holder.tvStatusBadge.setText("智能电视 (未开5555)");
                holder.cardContainer.setBackgroundResource(R.drawable.bg_cyber_card);
                break;
            default:
                holder.ivStatusDot.setImageResource(R.drawable.ic_dot_amber);
                holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_cyber_badge_gray);
                holder.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.cyber_text_secondary));
                holder.tvStatusBadge.setText("在线·未开放ADB");
                holder.cardContainer.setBackgroundResource(R.drawable.bg_cyber_card);
                break;
        }

        // 按钮点击事件
        holder.btnCopyAdb.setOnClickListener(v -> {
            copyToClipboard(item.getAdbCommand());
            Toast.makeText(context, "已复制: " + item.getAdbCommand(), Toast.LENGTH_SHORT).show();
        });

        holder.btnTroubleshoot.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onTroubleshoot(item);
            }
        });

        holder.cardContainer.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onDeviceSelected(item);
            }
        });
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            ClipData clip = ClipData.newPlainText("ADB Command", text);
            cm.setPrimaryClip(clip);
        }
    }

    static class ViewHolder {
        View cardContainer;
        ImageView ivStatusDot;
        TextView tvDeviceName;
        TextView tvVendorBadge;
        TextView tvStatusBadge;
        TextView tvIpAddress;
        TextView tvLatency;
        TextView tvDetailInfo;
        TextView btnTroubleshoot;
        TextView btnCopyAdb;

        ViewHolder(View root) {
            cardContainer = root.findViewById(R.id.card_container);
            ivStatusDot = root.findViewById(R.id.iv_status_dot);
            tvDeviceName = root.findViewById(R.id.tv_device_name);
            tvVendorBadge = root.findViewById(R.id.tv_vendor_badge);
            tvStatusBadge = root.findViewById(R.id.tv_status_badge);
            tvIpAddress = root.findViewById(R.id.tv_ip_address);
            tvLatency = root.findViewById(R.id.tv_latency);
            tvDetailInfo = root.findViewById(R.id.tv_detail_info);
            btnTroubleshoot = root.findViewById(R.id.btn_troubleshoot);
            btnCopyAdb = root.findViewById(R.id.btn_copy_adb);
        }
    }
}
