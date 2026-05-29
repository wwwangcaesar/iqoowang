package com.monsieurmahjong.iqoowang;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;

public class NfcTriggerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 注意：不要调用 setContentView()，保持绝对透明

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }
    private void handleIntent(Intent intent) {
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) {
            Uri uri = intent.getData();
            if (uri != null && "macproxy".equals(uri.getScheme())) {
                String path = uri.getPath();

                if ("/toggle".equals(path)) {
                    // 向 Mac 发送切换指令
                    triggerMacScript();
                    Toast.makeText(this, "🔄 正在切换 Mac 代理状态", Toast.LENGTH_SHORT).show();
                }
            }
        }
        finish();
    }

    private void triggerMacScript() {
        new Thread(() -> {
            try {
                String macIp = "10.164.155.142"; // 你的 Mac 局域网 IP
                // 请求 toggle 接口
                String urlString = "http://" + macIp + ":9999/proxy-toggle";

                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(3000);

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    Log.d("NFC_Trigger", "成功触发 Mac 切换！");
                }
                connection.disconnect();
            } catch (Exception e) {
                Log.e("NFC_Trigger", "局域网通信异常", e);
            }
        }).start();
    }

}

