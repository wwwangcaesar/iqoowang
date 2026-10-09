package com.adb.iqoowang;

import java.util.ArrayList;
import java.util.List;

/**
 * 各大品牌电视/设备 ADB 激活秘籍与强连知识库
 */
public class DeviceActivationGuides {

    public static class GuideItem {
        public String brand;
        public String title;
        public String shortcutCode;
        public String steps;
        public String commandHint;

        public GuideItem(String brand, String title, String shortcutCode, String steps, String commandHint) {
            this.brand = brand;
            this.title = title;
            this.shortcutCode = shortcutCode;
            this.steps = steps;
            this.commandHint = commandHint;
        }
    }

    public static List<GuideItem> getAllGuides() {
        List<GuideItem> list = new ArrayList<>();

        // 1. 小米 / Redmi / 小米盒子
        list.add(new GuideItem(
                "小米 / Redmi",
                "小米电视 / 盒子 开启 ADB 调试",
                "连续点击型号 5 次",
                "1. 在电视主页按菜单键，进入【设置】 -> 【关于】。\n" +
                "2. 找到【产品型号】这一行，手持遥控器对着它连续按 OK(确认键) 5 到 7 次，直到屏幕底部弹出提示「你已处于开发者模式」。\n" +
                "3. 返回【设置】 -> 找到【账号与安全】。\n" +
                "4. 将【ADB调试】选项由“关闭”改为【允许】。\n" +
                "5. 此时电视即会在 5555 端口启动 ADB 守护进程，可直接使用本 App 或终端连接！\n" +
                "注意：首次连接时电视屏幕会弹出“允许USB调试吗”，请务必用遥控器勾选“总是允许”并点确认。",
                "adb connect <IP>:5555"
        ));

        // 2. TCL / 雷鸟电视
        list.add(new GuideItem(
                "TCL / 雷鸟",
                "TCL电视 工厂工程模式强制开启 ADB",
                "遥控器按 0 6 2 5 9 6 + 菜单",
                "1. 打开电视，进入【系统设置】 -> 【系统信息】界面。\n" +
                "2. 使用遥控器依次按数字键：0 6 2 5 9 6，紧接着按【菜单键】（或部分型号直接按 0 5 3 2）。\n" +
                "3. 电视界面将弹出隐藏的【Factory Menu (工厂工程模式)】。\n" +
                "4. 在菜单中寻找【Param Setting】或【Other Option】，找到【ADB】或【USB Debug】。\n" +
                "5. 将其状态切换为【ON / 开启】，重启电视后 5555 端口即可自动暴露！",
                "adb connect <IP>:5555"
        ));

        // 3. 海信电视 / VIDAA
        list.add(new GuideItem(
                "海信 / Hisense",
                "海信电视 VIDAA 系统工程菜单开启调试",
                "声音平衡 -> 按 1 9 6 9",
                "1. 遥控器进入电视【系统设置】 -> 【声音设置】。\n" +
                "2. 将光标移动到【声音平衡】选项上。\n" +
                "3. 按遥控器数字键依次输入：1 9 6 9（若无数字键，可在设置界面快速连击主页与左右键）。\n" +
                "4. 屏幕中央会出现工程菜单图标（或字母 M），按遥控器进入工厂模式。\n" +
                "5. 找到【ADB开关】或【系统调试】，选择打开并保存退出即可。",
                "adb connect <IP>:5555"
        ));

        // 4. 创维 / 酷开电视
        list.add(new GuideItem(
                "创维 / 酷开",
                "创维 / 酷开 开启网络调试",
                "本机信息 -> 连按 上下左右",
                "1. 电视进入【设置】 -> 【本机信息】页面。\n" +
                "2. 遥控器方向键按照顺序快速按下：【上】、【下】、【左】、【右】（或【左】、【右】、【上】、【下】）。\n" +
                "3. 页面将自动呼出工程指令菜单或显示“工厂菜单”。\n" +
                "4. 找到通用调试选项【ADB Debugger】设为开启。",
                "adb connect <IP>:5555"
        ));

        // 5. 索尼 Bravia (原生 Android TV)
        list.add(new GuideItem(
                "索尼 Bravia",
                "索尼 Android TV 原生开发者选项",
                "版本号连续狂按 7 次",
                "1. 打开索尼电视【设置】 -> 【设备偏好设置】 -> 【关于】。\n" +
                "2. 下拉找到【Android TV 操作系统版本】或【内部版本号】。\n" +
                "3. 连续狂点 7 次，直到提示已处于开发者选项。\n" +
                "4. 返回【设备偏好设置】 -> 底部进入【开发者选项】。\n" +
                "5. 开启【网络调试】（Network Debugging）及【USB调试】。",
                "adb connect <IP>:5555"
        ));

        // 6. Android 11+ 无线调试 (动态随机端口 + 配对码)
        list.add(new GuideItem(
                "Android 11+ 无线调试",
                "动态端口配对与连接流程 (原生安全机制)",
                "无需5555·使用 mDNS 与配对码",
                "说明：Android 11及以上手机默认不再开放固定的5555端口，改为随机动态端口。\n\n" +
                "【配对步骤】：\n" +
                "1. 目标机进入【开发者选项】 -> 打开【无线调试】开关。\n" +
                "2. 点击进入【无线调试】二级菜单，点击【使用配对码配对设备】。\n" +
                "3. 屏幕会显示 6 位数字配对码和特定配对端口（如 42315）。\n" +
                "4. 电脑终端输入：adb pair <IP>:<配对端口>，并输入这 6 位配对码。\n" +
                "5. 配对成功后，使用本 App 扫描到的当前连接端口执行：\n" +
                "   adb connect <IP>:<当前连接端口>",
                "adb pair <IP>:<配对端口>  ->  adb connect <IP>:<连接端口>"
        ));

        // 7. 通用万能救砖：一次性电脑激活
        list.add(new GuideItem(
                "万能方案 (USB一次性激活)",
                "通过数据线执行 tcpip 开启无限制无线连接",
                "adb tcpip 5555",
                "当设备系统彻底封死了遥控器工程模式时：\n" +
                "1. 使用一条 USB 数据线（或双公头 USB 线）将电视/设备与电脑相连一次。\n" +
                "2. 电脑终端执行：adb tcpip 5555\n" +
                "3. 提示「restarting in TCP mode port: 5555」后，立即拔掉数据线！\n" +
                "4. 只要电视不关机断电，它将在当前 Wi-Fi/热点下持续保持 5555 开放，本 App 就能稳定扫描和直连！",
                "adb tcpip 5555  ->  adb connect <IP>:5555"
        ));

        return list;
    }

    /**
     * 根据设备品牌推荐指南
     */
    public static GuideItem getRecommendedGuide(String vendorHint) {
        List<GuideItem> list = getAllGuides();
        if (vendorHint != null) {
            for (GuideItem item : list) {
                if (vendorHint.contains(item.brand) || item.brand.contains(vendorHint)) {
                    return item;
                }
            }
        }
        return list.get(0); // 默认返回小米
    }
}
