//package com.monsieurmahjong.iqoowang.utils;
//
//
//import android.os.Handler;
//import android.os.Looper;
//import android.os.Message;
//import android.text.TextUtils;
//
//
///**
// * ForceSettleManager — 强制结算 + NFC护盾 工具类
// *
// * 解决两个问题：
// *   1. 强制结算：用户换卡或插队时，把2秒等待中的上一单立即结算，防止漏单。
// *   2. NFC护盾：IC读卡器因NFC手机靠近产生信道干扰时，阻止误触发离盘结算。
// *
// * 设计原则：
// *   - 工具类只管理自己的内部状态，不持有DataReadUtil的任何引用。
// *   - 每个方法只返回一个决策值，DataReadUtil拿到结果后自己执行动作。
// *   - DataReadUtil现有逻辑零删除，只在6个固定位置插入调用。
// *
// * DataReadUtil中需要插入的位置（共6处）：
// *   [插入点1] 字段声明区：声明 forceSettleManager 字段
// *   [插入点2] initSerial() 末尾：初始化工具类
// *   [插入点3] traySerial → onDataReceived 第一行：调用 notifyRawBytes()
// *   [插入点4] traySerial → 异卡进入时：调用 onNewCardDetected()
// *   [插入点5] weightSerial → 离盘超时判断入口：调用 checkShield()
// *   [插入点6] handler → sendMessageDelayed 之前：调用 registerPending()
// *             quitPlanHandler → 收到消息第一行：调用 onSettleExecuted()
// */
//public class ForceSettleManager {
//
//    private static final int MESSAGE_QUIT_PLAN = 0x1002;
//    // NFC护盾：同一张干扰卡连续出现达到此次数，才认定为真实换卡
//    private static final int INTERFERING_CONFIRM_COUNT = 15;
//    // NFC护盾：包间距超过此值触发异常检测（ms）
//    private static final long GAP_THRESHOLD_MS = 200;
//    // NFC护盾：物理断流超过此时间认定托盘已端走，强制放行结算（ms）
//    private static final long PHYSICAL_SILENCE_MS = 1000;
//    // NFC护盾：护盾最长持续时间，防止极端情况死锁（ms）
//    private static final long SHIELD_MAX_DURATION_MS = 15000;
//
//    // ── 回调 ──────────────────────────────────────────────────────────
//    /** 结算执行回调：当到时自动结算或强制结算触发时，通知DataReadUtil执行实际结算 */
//    public interface OnSettleListener {
//        void onSettle(WeightBean weightBean);
//    }
//
//    // ── 强制结算状态 ──────────────────────────────────────────────────
//    private volatile WeightBean pendingBean = null;
//    private final Handler settleHandler;
//
//    // ── NFC护盾状态 ───────────────────────────────────────────────────
//    /** 护盾是否激活 */
//    private volatile boolean shieldActive = false;
//    /** 串口收到任何字节的最后时间（含垃圾帧，用于物理断流检测） */
//    private volatile long lastPhysicalBytesTime = 0;
//    /** 护盾首次激活时间（用于15秒兜底） */
//    private volatile long shieldStartTime = 0;
//    /** 连续收到正确同卡包的计数（用于护盾自动解除） */
//    private int goodPacketCount = 0;
//    /** 当前疑似干扰的卡号 */
//    private String suspectedCard = "";
//    /** 同一张疑似干扰卡的连续出现次数 */
//    private int suspectedCardCount = 0;
//    /** 连续包间距异常计数（需连续2次才触发护盾，过滤单次CPU抖动） */
//    private int gapAbnormalCount = 0;
//    /** 强制离盘标志：由onNewCardDetected确认真实换卡时置true，由checkShield消费 */
//    private volatile boolean forceDepartFlag = false;
//
//    // ── 构造 ──────────────────────────────────────────────────────────
//
//    /**
//     * @param listener 结算执行回调
//     *                 注意：回调在主线程执行（Handler使用Looper.getMainLooper()）
//     */
//    public ForceSettleManager(OnSettleListener listener) {
//        settleHandler = new Handler(Looper.getMainLooper(), msg -> {
//            if (msg.what == MESSAGE_QUIT_PLAN) {
//                WeightBean bean = (WeightBean) msg.obj;
//                pendingBean = null;
//                if (bean != null && listener != null) {
//                    listener.onSettle(bean);
//                }
//            }
//            return false;
//        });
//    }
//
//    // ════════════════════════════════════════════════════════════════
//    //  强制结算 API
//    // ════════════════════════════════════════════════════════════════
//
//    /**
//     * [插入点6-A] handler → isSettlement==true 分支，sendMessageDelayed之前调用。
//     *
//     * 作用：登记当前待结算订单的引用，供 forceSettle() 使用。
//     * 注意：本方法只登记引用，不发送Handler消息，DataReadUtil自己的
//     *       quitPlanHandler.sendMessageDelayed() 保持不动。
//     *
//     * @param weightData 离盘时的WeightBean，与sendMessageDelayed传入的obj一致
//     */
//    public void registerPending(WeightBean weightData) {
//        pendingBean = weightData;
//    }
//
//    /**
//     * [插入点6-B] quitPlanHandler → MESSAGE_QUIT_PLAN 收到消息的第一行调用。
//     *
//     * 作用：结算执行时同步清空引用，防止 forceSettle() 对已执行的订单重复触发。
//     */
//    public void onSettleExecuted() {
//        pendingBean = null;
//    }
//
//    /**
//     * 强制立即结算上一个待结算订单（线程安全）。
//     *
//     * 调用场景：traySerial检测到换卡/插队时调用。
//     * 有 pendingBean 时才执行，无则直接返回，幂等安全。
//     */
//    public synchronized void forceSettle() {
//        if (pendingBean == null) {
//            XLog.d("ForceSettleManager.forceSettle: 无待结算订单，跳过");
//            return;
//        }
//        settleHandler.removeMessages(MESSAGE_QUIT_PLAN);
//        Message msg = new Message();
//        msg.what = MESSAGE_QUIT_PLAN;
//        msg.obj = pendingBean;
//        settleHandler.sendMessageAtFrontOfQueue(msg);
//        pendingBean = null;
//        XLog.i("ForceSettleManager.forceSettle: 强制结算执行，已插队");
//    }
//
//    /** 查询是否有待结算订单悬挂中（供外部判断用） */
//    public boolean hasPendingSettle() {
//        return pendingBean != null;
//    }
//
//    // ════════════════════════════════════════════════════════════════
//    //  NFC护盾 API
//    // ════════════════════════════════════════════════════════════════
//
//    /**
//     * [插入点3] traySerial → onDataReceived 第一行调用（串口收到任何字节时）。
//     *
//     * 作用：记录物理信道最后活跃时间，并检测包间距异常以激活护盾。
//     * 必须在所有 size/format 过滤之前调用，确保垃圾帧也被计入物理时间。
//     *
//     * @param currentCardNo DataReadUtil中的 cardNo 字段当前值
//     * @param currentFindCardTime DataReadUtil中的 findCardTime 字段当前值
//     */
//    public void notifyRawBytes(String currentCardNo, long currentFindCardTime) {
//        long now = System.currentTimeMillis();
//        // 只有托盘稳定存在时才检测包间距（防止放盘瞬间正常空档被误判）
//        if (currentFindCardTime != 0 && !TextUtils.isEmpty(currentCardNo)) {
//            if (lastPhysicalBytesTime != 0) {
//                long gap = now - lastPhysicalBytesTime;
//                if (gap > GAP_THRESHOLD_MS) {
//                    gapAbnormalCount++;
//                    // 需连续2次异常才激活，过滤单次CPU调度抖动
//                    if (gapAbnormalCount >= 2 && !shieldActive) {
//                        shieldActive = true;
//                        shieldStartTime = now;
//                        goodPacketCount = 0;
//                        XLog.w("ForceSettleManager: NFC护盾激活，gap=" + gap + "ms，连续异常=" + gapAbnormalCount);
//                    }
//                } else {
//                    gapAbnormalCount = 0;
//                }
//            }
//        }
//        lastPhysicalBytesTime = now;
//    }
//
//    /**
//     * [插入点4] traySerial → 检测到新卡号（与cardNo不同）且用户正在取餐时调用。
//     *
//     * 作用：区分NFC干扰与真实换卡。
//     *   - 返回 true：判定为干扰，调用方应 findCardTime=now 续命并 return
//     *   - 返回 false：判定为真实换卡，调用方应继续执行换卡流程
//     *     （工具类内部已置 forceDepartFlag，checkShield下次会直接放行离盘）
//     *
//     * 调用前提：inf != null && inf.isJoinCustomer() && !TextUtils.isEmpty(cardNo)
//     *
//     * @param newCard    本次检测到的新卡号
//     * @param currentCard DataReadUtil中的 cardNo 字段当前值
//     * @return true=拦截（干扰），false=放行（真实换卡）
//     */
//    public boolean onNewCardDetected(String newCard, String currentCard) {
//        long now = System.currentTimeMillis();
//        if (newCard.equals(suspectedCard)) {
//            suspectedCardCount++;
//        } else {
//            suspectedCard = newCard;
//            suspectedCardCount = 1;
//        }
//
//        if (suspectedCardCount >= INTERFERING_CONFIRM_COUNT) {
//            XLog.w("ForceSettleManager: 真实换卡确认，新卡=" + newCard + "，旧卡=" + currentCard
//                    + "，连续次数=" + suspectedCardCount);
//            // 确认真实换卡：清除护盾，置强制离盘标志
//            shieldActive = false;
//            suspectedCardCount = 0;
//            suspectedCard = "";
//            gapAbnormalCount = 0;
//            forceDepartFlag = true; // weightSerial下一帧checkShield()会消费此标志
//            return false; // 放行：调用方继续换卡流程
//        }
//
//        // 未达阈值，视为干扰，维持护盾
//        shieldActive = true;
//        shieldStartTime = (shieldStartTime == 0) ? now : shieldStartTime;
//        goodPacketCount = 0;
//        XLog.d("ForceSettleManager: 干扰包拦截，count=" + suspectedCardCount + "/" + INTERFERING_CONFIRM_COUNT);
//        return true; // 拦截：调用方续命return
//    }
//
//    /**
//     * traySerial → 收到与 cardNo 相同的正确卡号时调用（可选，增强护盾自动解除速度）。
//     *
//     * 作用：持续正确包积累，加速护盾解除。
//     * 不调用也不影响正确性（护盾靠物理断流和15秒兜底也能解除）。
//     */
//    public void onCorrectCardReceived() {
//        long now = System.currentTimeMillis();
//        goodPacketCount++;
//        suspectedCardCount = 0;
//        suspectedCard = "";
//        gapAbnormalCount = 0;
//        if (shieldActive) {
//            if (goodPacketCount >= INTERFERING_CONFIRM_COUNT || (now - shieldStartTime > 1000)) {
//                shieldActive = false;
//                XLog.i("ForceSettleManager: 护盾自动解除，连续好包=" + goodPacketCount);
//            }
//        }
//    }
//
//    /**
//     * [插入点5] weightSerial → 离盘超时判断入口调用。
//     *
//     * 调用时机：findCardTime != 0 && now - findCardTime > 超时阈值
//     * 在该if块的第一行调用，根据返回值决定是否执行离盘结算。
//     *
//     * @return ALLOW      = 放行，继续执行离盘结算逻辑
//     *         BLOCK_RENEW = 护盾阻止，调用方执行 findCardTime=now 续命并 return
//     */
//    public ShieldResult checkShield() {
//        long now = System.currentTimeMillis();
//
//        // 强制离盘标志优先（来自onNewCardDetected确认真实换卡）
//        if (forceDepartFlag) {
//            forceDepartFlag = false;
//            resetShieldInternal();
//            XLog.w("ForceSettleManager: 强制离盘标志放行结算");
//            return ShieldResult.ALLOW;
//        }
//
//        // 护盾未激活，正常放行
//        if (!shieldActive) {
//            resetShieldInternal();
//            return ShieldResult.ALLOW;
//        }
//
//        // 护盾激活中：物理断流超1秒，说明NFC手机和托盘都走了，放行
//        if (now - lastPhysicalBytesTime > PHYSICAL_SILENCE_MS) {
//            XLog.w("ForceSettleManager: 护盾激活中，物理断流>" + PHYSICAL_SILENCE_MS + "ms，放行结算");
//            shieldActive = false;
//            resetShieldInternal();
//            return ShieldResult.ALLOW;
//        }
//
//        // 15秒强制兜底，防死锁
//        if (now - shieldStartTime > SHIELD_MAX_DURATION_MS) {
//            XLog.e("ForceSettleManager: 护盾超时" + SHIELD_MAX_DURATION_MS + "ms，强制放行结算");
//            shieldActive = false;
//            resetShieldInternal();
//            return ShieldResult.ALLOW;
//        }
//
//        // 护盾有效，阻止结算
//        return ShieldResult.BLOCK_RENEW;
//    }
//
//    /** checkShield() 的返回值类型 */
//    public enum ShieldResult {
//        /** 放行：调用方继续执行离盘结算逻辑 */
//        ALLOW,
//        /** 阻止：调用方执行 findCardTime = System.currentTimeMillis() 并 return */
//        BLOCK_RENEW
//    }
//
//    // ── 内部重置 ──────────────────────────────────────────────────────
//    private void resetShieldInternal() {
//        shieldStartTime = 0;
//        lastPhysicalBytesTime = 0;
//        goodPacketCount = 0;
//        gapAbnormalCount = 0;
//    }
//}
