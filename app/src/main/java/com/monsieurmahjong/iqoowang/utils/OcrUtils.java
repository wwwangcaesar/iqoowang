package com.monsieurmahjong.iqoowang.utils;


import android.content.Context;
import android.net.Uri;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OcrUtils {
    // 初始化中文识别器（单例）
    private static final TextRecognizer recognizer =   TextRecognition.getClient(
            new ChineseTextRecognizerOptions.Builder().build()
    );

    // 私有构造函数，防止实例化
    private OcrUtils() {}

    /**
     * 解析截图获取金额和可能的商户信息
     * 注意：此方法必须在后台线程调用，不能在主线程执行
     * @param context 上下文
     * @param imageUri 图片Uri
     * @return 解析结果ExpenseData，失败返回null
     */
    public static ExpenseData parsePaymentScreenshot(Context context, Uri imageUri) {
        try {
            InputImage image = InputImage.fromFilePath(context, imageUri);
            // 同步等待识别结果（必须在后台线程执行）
            Text result = Tasks.await(recognizer.process(image));

            String fullText = result.getText();
            // 正则：匹配 ￥ 或 ¥ 后面的数字，或者直接匹配带有小数点的常规金额
            Pattern amountPattern = Pattern.compile("(?<=[￥¥]\\s?)\\d+\\.\\d{2}");
            Matcher amountMatcher = amountPattern.matcher(fullText);

            if (amountMatcher.find()) {
                String amountStr = amountMatcher.group();
                // 转为分存储，避免浮点数精度问题
                long amount = (long) (Double.parseDouble(amountStr) * 100);
                return new ExpenseData(amount, fullText);
            } else {
                return null;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 异步版本的解析方法（推荐在主线程调用）
     * @param context 上下文
     * @param imageUri 图片Uri
     * @param callback 结果回调
     */
    public static void parsePaymentScreenshotAsync(Context context, Uri imageUri, OcrCallback callback) {
        try {
            InputImage image = InputImage.fromFilePath(context, imageUri);
            recognizer.process(image)
                    .addOnSuccessListener(result -> {
                        String fullText = result.getText();
                        Pattern amountPattern = Pattern.compile("(?<=[￥¥]\\s?)\\d+\\.\\d{2}");
                        Matcher amountMatcher = amountPattern.matcher(fullText);

                        if (amountMatcher.find()) {
                            String amountStr = amountMatcher.group();
                            long amount = (long) (Double.parseDouble(amountStr) * 100);
                            callback.onSuccess(new ExpenseData(amount, fullText));
                        } else {
                            callback.onFailure("未识别到有效金额");
                        }
                    })
                    .addOnFailureListener(e -> {
                        e.printStackTrace();
                        callback.onFailure("OCR识别失败: " + e.getMessage());
                    });
        } catch (IOException e) {
            e.printStackTrace();
            callback.onFailure("图片加载失败: " + e.getMessage());
        }
    }

    // 数据类
    public static class ExpenseData {
        private final long amount; // 金额，单位：分
        private final String rawText; // 识别到的原始文本

        public ExpenseData(long amount, String rawText) {
            this.amount = amount;
            this.rawText = rawText;
        }

        public long getAmount() {
            return amount;
        }

        public String getRawText() {
            return rawText;
        }
    }

    // 回调接口
    public interface OcrCallback {
        void onSuccess(ExpenseData data);
        void onFailure(String errorMessage);
    }
}