package com.monsieurmahjong.iqoowang.view;


import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 赛博朋克颜色同步桥
 *
 * CyberpunkBgView 每帧调用 updateColor() 广播当前混合色；
 * NeonCardView 订阅监听，实时跟随同一颜色，确保卡片光效与背景呼吸灯严格同步。
 */
public class CyberpunkColorSync {

    private static volatile int currentColor = 0xFF00FFFF;

    // 使用线程安全列表（避免并发修改异常）
    private static final CopyOnWriteArrayList<ColorListener> listeners =
            new CopyOnWriteArrayList<>();

    /** CyberpunkBgView 每帧调用此方法广播当前混合色 */
    public static void updateColor(int color) {
        currentColor = color;
        for (ColorListener l : listeners) {
            l.onColorChanged(color);
        }
    }

    /** 获取最新颜色（供首次初始化使用） */
    public static int getCurrentColor() {
        return currentColor;
    }

    public static void addListener(ColorListener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(ColorListener l) {
        listeners.remove(l);
    }

    public interface ColorListener {
        void onColorChanged(int color);
    }
}
