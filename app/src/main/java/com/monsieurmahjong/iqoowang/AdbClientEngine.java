package com.monsieurmahjong.iqoowang;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * 手机内置纯 Java ADB 客户端引擎
 * 彻底代替电脑终端执行：adb connect <ip>:5555 与 adb install <apk>
 * 支持：免电脑 ADB 握手、RSA 密钥认证、流式直推安装 (pm/cmd package install)
 */
public class AdbClientEngine {

    public interface AdbInstallListener {
        void onLog(String message);
        void onProgress(long transferredBytes, long totalBytes);
        void onSuccess(String message);
        void onError(String error);
    }

    // ADB 协议基础指令常量 (Little-Endian)
    private static final int A_CNXN = 0x4e584e43; // "CNXN"
    private static final int A_AUTH = 0x48545541; // "AUTH"
    private static final int A_OPEN = 0x4e45504f; // "OPEN"
    private static final int A_OKAY = 0x59414b4f; // "OKAY"
    private static final int A_CLSE = 0x45534c43; // "CLSE"
    private static final int A_WRTE = 0x45545257; // "WRTE"

    private static final int AUTH_TYPE_TOKEN = 1;
    private static final int AUTH_TYPE_SIGNATURE = 2;
    private static final int AUTH_TYPE_RSAPUBLICKEY = 3;

    private static final int ADB_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 64 * 1024; // 64KB

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isCancelled = false;

    public AdbClientEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public void cancel() {
        isCancelled = true;
    }

    /**
     * 手机直接作为 ADB 主机，向目标电视 IP 远程推送安装应用 (代替电脑 adb install)
     */
    public void installApk(String targetIp, int targetPort, File apkFile, AdbInstallListener listener) {
        isCancelled = false;
        new Thread(() -> {
            postLog(listener, "> 正在建立连接至电视 ADB 服务 [" + targetIp + ":" + targetPort + "] ...");

            try (Socket socket = new Socket()) {
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(targetIp, targetPort), 3000);
                InputStream is = socket.getInputStream();
                OutputStream os = socket.getOutputStream();

                postLog(listener, "✔ TCP 链路已连通，开始 ADB 握手协商...");

                // 1. 发送 ADB CNXN 握手包
                byte[] hostBanner = "host::IQOOWang_Phone\0".getBytes("UTF-8");
                sendMessage(os, A_CNXN, ADB_VERSION, MAX_PAYLOAD, hostBanner);

                // 2. 处理握手返回 (直接成功 或 需RSA授权)
                boolean authenticated = handleHandshake(is, os, listener);
                if (!authenticated) {
                    postError(listener, "ADB 握手认证未完成。如电视屏幕出现「允许调试吗」，请用遥控器点击允许。");
                    return;
                }

                postLog(listener, "✔ ADB 握手认证成功！目标电视已就绪。");
                postLog(listener, "> 正在向电视系统调起应用安装器 (Stream Install) ...");

                // 3. 打开安装通道 (支持 Android 5.0+ 极速流式安装，免推送到临时目录)
                long fileSize = apkFile.length();
                int localId = 1;
                String installCmd = "exec:cmd package install -r -S " + fileSize + "\0";
                sendMessage(os, A_OPEN, localId, 0, installCmd.getBytes("UTF-8"));

                AdbMessage openResp = readMessage(is);
                if (openResp.command != A_OKAY) {
                    // 若 cmd package 不支持，降级尝试传统 pm install
                    installCmd = "exec:pm install -r -S " + fileSize + "\0";
                    sendMessage(os, A_OPEN, localId, 0, installCmd.getBytes("UTF-8"));
                    openResp = readMessage(is);
                }

                if (openResp.command != A_OKAY) {
                    postError(listener, "电视拒绝了安装通道请求，指令返回: " + Integer.toHexString(openResp.command));
                    return;
                }

                int remoteId = openResp.arg0;
                postLog(listener, "✔ 安装管道建立成功！开始推送 APK 数据 (" + String.format("%.1f MB", fileSize / (1024f * 1024f)) + ") ...");

                // 4. 分块传输 APK 二进制流
                try (FileInputStream fis = new FileInputStream(apkFile)) {
                    byte[] buffer = new byte[32 * 1024]; // 32KB 分块
                    int bytesRead;
                    long totalTransferred = 0;

                    while ((bytesRead = fis.read(buffer)) != -1 && !isCancelled) {
                        byte[] chunk = new byte[bytesRead];
                        System.arraycopy(buffer, 0, chunk, 0, bytesRead);

                        sendMessage(os, A_WRTE, localId, remoteId, chunk);
                        totalTransferred += bytesRead;

                        final long cur = totalTransferred;
                        mainHandler.post(() -> listener.onProgress(cur, fileSize));

                        // 等待电视返回 OKAY 确认包
                        AdbMessage ack = readMessage(is);
                        if (ack.command == A_CLSE) {
                            // 电视端提前关闭
                            break;
                        }
                    }

                    if (isCancelled) {
                        postError(listener, "用户已取消安装传输。");
                        return;
                    }
                }

                // 5. 传输完毕，关闭写通道，等待电视系统完成安装返回
                sendMessage(os, A_CLSE, localId, remoteId, null);
                postLog(listener, "> 数据传输完毕 (100%)，正在等待电视系统校验并执行安装...");

                // 读取电视系统的返回结果 (Success 或 Failure)
                StringBuilder resultBuilder = new StringBuilder();
                while (true) {
                    AdbMessage resultMsg = readMessage(is);
                    if (resultMsg.payload != null && resultMsg.payload.length > 0) {
                        resultBuilder.append(new String(resultMsg.payload, "UTF-8"));
                    }
                    if (resultMsg.command == A_CLSE || resultMsg.command == 0) {
                        break;
                    }
                }

                String finalResult = resultBuilder.toString().trim();
                postLog(listener, "> 电视返回: " + (finalResult.isEmpty() ? "OK" : finalResult));

                if (finalResult.toLowerCase().contains("success")) {
                    mainHandler.post(() -> listener.onSuccess("🎉 恭喜！App 已成功直接远程安装到电视！"));
                } else if (finalResult.isEmpty()) {
                    mainHandler.post(() -> listener.onSuccess("✔ 安装指令已执行完成，请查看电视主页新应用。"));
                } else {
                    postError(listener, "安装失败: " + finalResult);
                }

            } catch (Exception e) {
                postError(listener, "连接或传输异常: " + e.getMessage() + "\n(请确认电视在同一网段，且已开启网络调试)");
            }
        }).start();
    }

    private boolean handleHandshake(InputStream is, OutputStream os, AdbInstallListener listener) throws Exception {
        AdbMessage msg = readMessage(is);

        // 如果直接返回 CNXN，说明电视端无需密码/未设验证 (多数电视与盒子)
        if (msg.command == A_CNXN) {
            return true;
        }

        // 如果返回 AUTH，需要进行 RSA 密钥验证
        if (msg.command == A_AUTH && msg.arg0 == AUTH_TYPE_TOKEN) {
            postLog(listener, "> 电视开启了安全认证，正在向电视递交密钥指纹...");
            KeyPair keyPair = getOrCreateKeyPair();

            // 发送公钥让电视弹窗授权
            String pubKeyString = Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.NO_WRAP);
            byte[] pubKeyPayload = (pubKeyString + " phone@adb\0").getBytes("UTF-8");
            sendMessage(os, A_AUTH, AUTH_TYPE_RSAPUBLICKEY, 0, pubKeyPayload);

            postLog(listener, "💡 请注意查看电视屏幕！如果弹出「允许USB/网络调试吗」，请用遥控器勾选并点【允许】！");

            AdbMessage next = readMessage(is);
            return next.command == A_CNXN;
        }

        return false;
    }

    private void sendMessage(OutputStream os, int command, int arg0, int arg1, byte[] data) throws IOException {
        int length = data != null ? data.length : 0;
        int checksum = 0;
        if (data != null) {
            for (byte b : data) checksum += (b & 0xFF);
        }

        ByteBuffer bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(command);
        bb.putInt(arg0);
        bb.putInt(arg1);
        bb.putInt(length);
        bb.putInt(checksum);
        bb.putInt(command ^ 0xFFFFFFFF);

        os.write(bb.array());
        if (data != null && data.length > 0) {
            os.write(data);
        }
        os.flush();
    }

    private AdbMessage readMessage(InputStream is) throws IOException {
        byte[] header = readExact(is, 24);
        if (header == null) return new AdbMessage(0, 0, 0, null);

        ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = bb.getInt();
        int arg0 = bb.getInt();
        int arg1 = bb.getInt();
        int dataLength = bb.getInt();
        int dataCheck = bb.getInt();
        int magic = bb.getInt();

        byte[] payload = null;
        if (dataLength > 0 && dataLength <= 1048576) {
            payload = readExact(is, dataLength);
        }
        return new AdbMessage(command, arg0, arg1, payload);
    }

    private byte[] readExact(InputStream is, int length) throws IOException {
        byte[] buffer = new byte[length];
        int total = 0;
        while (total < length) {
            int read = is.read(buffer, total, length - total);
            if (read == -1) {
                if (total == 0) return null;
                throw new IOException("读取流意外断开");
            }
            total += read;
        }
        return buffer;
    }

    private KeyPair getOrCreateKeyPair() {
        SharedPreferences sp = context.getSharedPreferences("adb_keys", Context.MODE_PRIVATE);
        String privStr = sp.getString("priv", null);
        String pubStr = sp.getString("pub", null);

        if (privStr != null && pubStr != null) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                byte[] privBytes = Base64.decode(privStr, Base64.DEFAULT);
                byte[] pubBytes = Base64.decode(pubStr, Base64.DEFAULT);
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
                PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(pubBytes));
                return new KeyPair(pub, priv);
            } catch (Exception ignored) {}
        }

        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();

            sp.edit()
                .putString("priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.DEFAULT))
                .putString("pub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.DEFAULT))
                .apply();
            return kp;
        } catch (Exception e) {
            throw new RuntimeException("无法生成 RSA 密钥: " + e.getMessage());
        }
    }

    private void postLog(AdbInstallListener l, String msg) {
        mainHandler.post(() -> l.onLog(msg));
    }

    private void postError(AdbInstallListener l, String err) {
        mainHandler.post(() -> l.onError(err));
    }

    private static class AdbMessage {
        int command;
        int arg0;
        int arg1;
        byte[] payload;

        AdbMessage(int command, int arg0, int arg1, byte[] payload) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.payload = payload;
        }
    }
}
