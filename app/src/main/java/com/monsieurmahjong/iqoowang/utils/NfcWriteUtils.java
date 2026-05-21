package com.monsieurmahjong.iqoowang.utils;

import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;

public class NfcWriteUtils {
    // 私有构造函数，实现单例工具类（对应Kotlin object）
    private NfcWriteUtils() {}

    /**
     * 将唤醒 URL 写入 NFC 标签
     * @param tag Intent 中获取的 Tag 对象
     * @param url 需要写入的 URL，例如 "https://mydomain.com/expense"
     * @return 写入成功返回true，失败返回false
     */
    public static boolean writeUrlToTag(Tag tag, String url) {
        try {
            // 创建 NDEF URI Record
            NdefRecord record = NdefRecord.createUri(url);
            NdefMessage message = new NdefMessage(new NdefRecord[]{record});

            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                ndef.connect();
                // 检查标签是否可写
                if (!ndef.isWritable()) {
                    return false;
                }
                // 检查标签容量是否足够
                if (ndef.getMaxSize() < message.toByteArray().length) {
                    return false;
                }
                // 写入NDEF消息
                ndef.writeNdefMessage(message);
                ndef.close();
                return true;
            } else {
                // 如果标签还未格式化为NDEF格式，尝试格式化并写入
                NdefFormatable formatable = NdefFormatable.get(tag);
                if (formatable != null) {
                    formatable.connect();
                    formatable.format(message);
                    formatable.close();
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}