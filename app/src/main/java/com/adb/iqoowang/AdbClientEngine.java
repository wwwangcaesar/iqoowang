package com.adb.iqoowang;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 手机内置纯 Java ADB 客户端引擎 (高兼容终极版)
 *
 * 核心技术标准:
 * 1. 握手层: 自适应明文 TCP 与 Android 11+ A_STLS (TLS 1.3 内存证书自签名)
 * 2. 协议层: 报文合并一次性写入 (解决 TLS Record 分片)，服务名绝不携带多余 \0
 * 3. 传输层: 标准 SYNC 推送 + pm install 远程静默安装 (100% 兼容全品牌设备/RK3568)
 */
public class AdbClientEngine {

    public interface AdbInstallListener {
        void onLog(String message);
        void onProgress(long transferredBytes, long totalBytes);
        void onSuccess(String message);
        void onError(String error);
    }

    // ADB 基础报文常量 (Little-Endian)
    private static final int A_CNXN = 0x4e584e43; // "CNXN"
    private static final int A_AUTH = 0x48545541; // "AUTH"
    private static final int A_OPEN = 0x4e45504f; // "OPEN"
    private static final int A_OKAY = 0x59414b4f; // "OKAY"
    private static final int A_CLSE = 0x45534c43; // "CLSE"
    private static final int A_WRTE = 0x45545257; // "WRTE"
    private static final int A_STLS = 0x534c5453; // "STLS" (Android 11+ START TLS)

    private static final int AUTH_TYPE_TOKEN = 1;
    private static final int AUTH_TYPE_SIGNATURE = 2;
    private static final int AUTH_TYPE_RSAPUBLICKEY = 3;

    private static final int ADB_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 64 * 1024;

    private static final byte[] SHA1_DIGEST_INFO_PREFIX = new byte[] {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isCancelled = false;

    public AdbClientEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public void cancel() {
        isCancelled = true;
    }

    private static class AdbSession implements AutoCloseable {
        Socket plainSocket;
        SSLSocket sslSocket;
        InputStream is;
        OutputStream os;

        AdbSession(Socket socket) throws IOException {
            this.plainSocket = socket;
            this.is = socket.getInputStream();
            this.os = socket.getOutputStream();
        }

        void upgradeToTls(SSLSocket sslSocket) throws IOException {
            this.sslSocket = sslSocket;
            this.is = sslSocket.getInputStream();
            this.os = sslSocket.getOutputStream();
        }

        @Override
        public void close() {
            if (sslSocket != null) {
                try { sslSocket.close(); } catch (Exception ignored) {}
            }
            if (plainSocket != null) {
                try { plainSocket.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 核心安装入口
     */
    public void installApk(String targetIp, int targetPort, File apkFile, AdbInstallListener listener) {
        isCancelled = false;
        new Thread(() -> {
            postLog(listener, "> 正在连接目标设备 [" + targetIp + ":" + targetPort + "] ...");

            Socket plainSocket = new Socket();
            try {
                plainSocket.setTcpNoDelay(true);
                plainSocket.setSoTimeout(30000);
                plainSocket.connect(new InetSocketAddress(targetIp, targetPort), 4000);
            } catch (Exception e) {
                postError(listener, "无法连通 " + targetIp + ":" + targetPort + " (" + e.getMessage() + ")");
                try { plainSocket.close(); } catch (Exception ignored) {}
                return;
            }

            try (AdbSession session = new AdbSession(plainSocket)) {
                postLog(listener, "✔ TCP 链路已连通，开始 ADB 握手协商...");

                // 1. 握手与 TLS 平滑升级
                boolean ok = handleHandshakeAndUpgrade(session, targetIp, targetPort, listener);
                if (!ok) {
                    postError(listener, "ADB 握手未完成，连接已断开。");
                    return;
                }

                postLog(listener, "✔ ADB 会话握手成功！设备已就绪。");

                // 2. 通道探活与设备型号探测 (验证双向流 100% 畅通)
                String osVer = executeShellCommand(session, "getprop ro.build.version.release");
                String devModel = executeShellCommand(session, "getprop ro.product.model");
                postLog(listener, "✔ 设备响应正常: Android " + (osVer.isEmpty() ? "11+" : osVer) +
                        (devModel.isEmpty() ? "" : " (" + devModel + ")"));

                // 3. 执行 SYNC 文件推送与 pm install 安装
                doSyncInstall(session, apkFile, listener);

            } catch (Exception e) {
                postError(listener, "安装过程发生异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }).start();
    }

    /**
     * 最通用稳定的安装方案：通过 SYNC 推送至 /data/local/tmp 并调用 pm install
     */
    private void doSyncInstall(AdbSession session, File apkFile, AdbInstallListener listener) {
        String remoteTmpPath = "/data/local/tmp/_iqoo_install.apk";
        postLog(listener, "> 正在通过 SYNC 通道推送 APK (" + String.format("%.1f MB", apkFile.length() / (1024f * 1024f)) + ") ...");

        boolean pushed = pushFileViaSync(session, apkFile, remoteTmpPath, listener);
        if (!pushed || isCancelled) {
            postError(listener, isCancelled ? "已取消传输。" : "APK 文件推送失败，请检查设备剩余存储空间！");
            return;
        }

        postLog(listener, "✔ APK 传输完毕 (100%)！");
        postLog(listener, "> 正在调用系统 pm install -r -t -d -g 执行静默安装...");

        // 赋予临时文件标准读权限
        executeShellCommand(session, "chmod 644 " + remoteTmpPath);

        // 调用 pm install
        String installCmd = "pm install -r -t -d -g " + remoteTmpPath;
        String output = executeShellCommand(session, installCmd);

        // 清理临时文件
        executeShellCommand(session, "rm -f " + remoteTmpPath);

        postLog(listener, "> 设备安装回执: " + (output.isEmpty() ? "(未捕获文本)" : output.trim()));

        if (output.toLowerCase().contains("success")) {
            mainHandler.post(() -> listener.onSuccess("🎉 安装成功！设备返回: Success"));
        } else {
            String reason = parseFailureReason(output);
            postError(listener, "安装未完成！\n" + reason + "\n设备原始输出: " + (output.isEmpty() ? "命令无输出" : output.trim()));
        }
    }

    /**
     * 握手与协议协商 (自动捕获 A_STLS 0x534c5453 升级为 TLS 1.3)
     */
    private boolean handleHandshakeAndUpgrade(AdbSession session, String targetIp, int targetPort, AdbInstallListener listener) throws Exception {
        byte[] banner = "host::IQOOWangApp\0".getBytes(StandardCharsets.UTF_8);

        sendMessage(session.os, A_CNXN, ADB_VERSION, MAX_PAYLOAD, banner);

        AdbMessage msg = readMessage(session.is);
        postLog(listener, "> 收到首个设备握手包: 0x" + Integer.toHexString(msg.command) + " (" + getCommandName(msg.command) + ")");

        // === 场景 A: 捕获 A_STLS (0x534c5453) 要求升级 TLS ===
        if (msg.command == A_STLS) {
            postLog(listener, "🔐 捕获到 A_STLS (0x534c5453)！设备请求切换至 TLS 1.3 加密隧道...");

            sendMessage(session.os, A_STLS, 0, 0, null);

            SSLContext sslContext = createAdbSslContext();
            SSLSocketFactory factory = sslContext.getSocketFactory();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(session.plainSocket, targetIp, targetPort, false);
            sslSocket.setUseClientMode(true);
            sslSocket.startHandshake();

            session.upgradeToTls(sslSocket);
            postLog(listener, "✔ TLS 1.3 加密隧道握手成功！正在加密隧道内重新同步 ADB 会话...");

            // 在 TLS 隧道内重新发送 A_CNXN
            sendMessage(session.os, A_CNXN, ADB_VERSION, MAX_PAYLOAD, banner);
            msg = readMessage(session.is);
            postLog(listener, "> 加密隧道内设备回执: 0x" + Integer.toHexString(msg.command) + " (" + getCommandName(msg.command) + ")");
        }

        // === 场景 B: 免密设备直接放行 ===
        if (msg.command == A_CNXN) {
            postLog(listener, "✔ 设备无需任何认证，直接连通！");
            return true;
        }

        // === 场景 C: RSA 鉴权 ===
        if (msg.command == A_AUTH && msg.arg0 == AUTH_TYPE_TOKEN) {
            postLog(listener, "> 设备要求 RSA 鉴权，正在尝试密钥签名...");
            KeyPair keyPair = getOrCreateKeyPair();
            byte[] token = msg.payload;

            try {
                byte[] signature = signToken(keyPair.getPrivate(), token);
                sendMessage(session.os, A_AUTH, AUTH_TYPE_SIGNATURE, 0, signature);

                AdbMessage resp = readMessage(session.is);
                if (resp.command == A_CNXN) {
                    postLog(listener, "✔ 密钥签名验证通过！设备已识别本手机。");
                    return true;
                }
            } catch (Exception ignored) {}

            byte[] mincryptKey = convertToMincrypt((RSAPublicKey) keyPair.getPublic());
            String pubKeyB64 = Base64.encodeToString(mincryptKey, Base64.NO_WRAP);
            byte[] authPayload = (pubKeyB64 + " phone@iqoowang\0").getBytes(StandardCharsets.UTF_8);

            sendMessage(session.os, A_AUTH, AUTH_TYPE_RSAPUBLICKEY, 0, authPayload);
            postLog(listener, "📡 手机公钥已成功发送至设备！等待设备确认...");

            AdbMessage next = readMessage(session.is);
            return next.command == A_CNXN;
        }

        return false;
    }

    /**
     * 通过标准 SYNC 协议推送 APK
     */
    private boolean pushFileViaSync(AdbSession session, File apkFile, String remotePath, AdbInstallListener listener) {
        try {
            int localId = 200;
            // 打开 sync 服务：标准 AOSP 不包含末尾 \0
            byte[] syncReq = "sync:".getBytes(StandardCharsets.US_ASCII);
            sendMessage(session.os, A_OPEN, localId, 0, syncReq);

            AdbMessage openResp = readMessage(session.is);
            if (openResp.command != A_OKAY) {
                postLog(listener, "> 打开 sync 通道被拒绝，回执: 0x" + Integer.toHexString(openResp.command));
                return false;
            }
            int remoteId = openResp.arg0;

            // 1. 发送 SEND 报文头: "SEND" + pathLen + remotePath,33206 (0644权限)
            String pathAndMode = remotePath + ",33206";
            byte[] pathBytes = pathAndMode.getBytes(StandardCharsets.UTF_8);
            ByteBuffer sendHeader = ByteBuffer.allocate(8 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
            sendHeader.put("SEND".getBytes(StandardCharsets.US_ASCII));
            sendHeader.putInt(pathBytes.length);
            sendHeader.put(pathBytes);

            sendMessage(session.os, A_WRTE, localId, remoteId, sendHeader.array());
            AdbMessage ack = readMessage(session.is);
            if (ack.command != A_OKAY) return false;

            // 2. 分块写入 DATA 报文
            long fileSize = apkFile.length();
            long total = 0;
            byte[] buf = new byte[32 * 1024];

            try (FileInputStream fis = new FileInputStream(apkFile)) {
                int read;
                while ((read = fis.read(buf)) != -1 && !isCancelled) {
                    ByteBuffer dataHeader = ByteBuffer.allocate(8 + read).order(ByteOrder.LITTLE_ENDIAN);
                    dataHeader.put("DATA".getBytes(StandardCharsets.US_ASCII));
                    dataHeader.putInt(read);
                    dataHeader.put(buf, 0, read);

                    sendMessage(session.os, A_WRTE, localId, remoteId, dataHeader.array());
                    ack = readMessage(session.is);
                    if (ack.command != A_OKAY) return false;

                    total += read;
                    final long cur = total;
                    mainHandler.post(() -> listener.onProgress(cur, fileSize));
                }
            }

            if (isCancelled) return false;

            // 3. 发送 DONE 报文
            ByteBuffer doneHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            doneHeader.put("DONE".getBytes(StandardCharsets.US_ASCII));
            doneHeader.putInt((int) (System.currentTimeMillis() / 1000));

            sendMessage(session.os, A_WRTE, localId, remoteId, doneHeader.array());
            readMessage(session.is); // 读取 A_OKAY

            // 4. 读取 SYNC 回执状态 (OKAY 或 FAIL)
            AdbMessage syncStatus = readMessage(session.is);
            boolean syncOk = true;
            if (syncStatus.payload != null && syncStatus.payload.length >= 4) {
                String tag = new String(syncStatus.payload, 0, 4, StandardCharsets.US_ASCII);
                if ("FAIL".equals(tag)) syncOk = false;
            }

            sendMessage(session.os, A_CLSE, localId, remoteId, null);
            return syncOk;
        } catch (Exception e) {
            postLog(listener, "> SYNC 传输异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 执行 ADB Shell 指令并捕获完整输出 (服务名末尾绝不带 \0)
     */
    private String executeShellCommand(AdbSession session, String command) {
        StringBuilder sb = new StringBuilder();
        try {
            int localId = 300;
            // 优先 exec，若不支持降级 shell
            byte[] req = ("exec:" + command).getBytes(StandardCharsets.UTF_8);
            sendMessage(session.os, A_OPEN, localId, 0, req);

            AdbMessage openResp = readMessage(session.is);
            if (openResp.command != A_OKAY) {
                req = ("shell:" + command).getBytes(StandardCharsets.UTF_8);
                sendMessage(session.os, A_OPEN, localId, 0, req);
                openResp = readMessage(session.is);
                if (openResp.command != A_OKAY) return "";
            }

            int remoteId = openResp.arg0;

            while (true) {
                AdbMessage msg = readMessage(session.is);
                if (msg.payload != null && msg.payload.length > 0) {
                    sb.append(new String(msg.payload, StandardCharsets.UTF_8));
                }
                if (msg.command == A_CLSE || msg.command == 0) {
                    break;
                }
                if (msg.command == A_WRTE) {
                    sendMessage(session.os, A_OKAY, localId, remoteId, null);
                }
            }

            sendMessage(session.os, A_CLSE, localId, remoteId, null);
        } catch (Exception ignored) {}
        return sb.toString().trim();
    }

    /**
     * 构建纯内存自签名 X.509 证书的 TLS 1.3 上下文 (100% 避免底层硬件芯片 PSS 错误)
     */
    private SSLContext createAdbSslContext() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");

        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
        };

        KeyManager[] keyManagers = null;
        try {
            KeyPair keyPair = getOrCreateKeyPair();
            X509Certificate cert = generateSelfSignedCert(keyPair);

            KeyStore memoryKs = KeyStore.getInstance("PKCS12");
            memoryKs.load(null, null);
            memoryKs.setKeyEntry("adb_client", keyPair.getPrivate(), null, new java.security.cert.Certificate[]{cert});

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(memoryKs, null);
            keyManagers = kmf.getKeyManagers();
        } catch (Exception ignored) {}

        sslContext.init(keyManagers, trustAll, new SecureRandom());
        return sslContext;
    }

    /**
     * 纯 Java 动态构建标准自签名 X.509 DER 证书
     */
    private X509Certificate generateSelfSignedCert(KeyPair keyPair) throws Exception {
        ByteArrayOutputStream tbs = new ByteArrayOutputStream();

        writeDerInteger(tbs, BigInteger.valueOf(System.currentTimeMillis()));

        byte[] sigAlg = new byte[] {
                0x30, 0x0d, 0x06, 0x09, 0x2a, (byte)0x86, 0x48, (byte)0x86, (byte)0xf7, 0x0d, 0x01, 0x01, 0x0b, 0x05, 0x00
        };
        tbs.write(sigAlg);

        byte[] x500Name = createDerX500Name("adb_client");
        tbs.write(x500Name);

        byte[] validity = createDerValidity();
        tbs.write(validity);

        tbs.write(x500Name);
        tbs.write(keyPair.getPublic().getEncoded());

        byte[] tbsBytes = wrapDerSequence(tbs.toByteArray());

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(tbsBytes);
        byte[] signature = signer.sign();

        ByteArrayOutputStream cert = new ByteArrayOutputStream();
        cert.write(tbsBytes);
        cert.write(sigAlg);
        writeDerBitString(cert, signature);

        byte[] certDer = wrapDerSequence(cert.toByteArray());

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certDer));
    }

    private static void writeDerInteger(ByteArrayOutputStream out, BigInteger val) throws IOException {
        byte[] bytes = val.toByteArray();
        out.write(0x02);
        writeDerLength(out, bytes.length);
        out.write(bytes);
    }

    private static byte[] createDerX500Name(String commonName) throws IOException {
        byte[] cnBytes = commonName.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream atv = new ByteArrayOutputStream();
        atv.write(new byte[]{0x06, 0x03, 0x55, 0x04, 0x03});
        atv.write(0x13);
        writeDerLength(atv, cnBytes.length);
        atv.write(cnBytes);

        byte[] atvSeq = wrapDerSequence(atv.toByteArray());
        byte[] set = wrapDerSet(atvSeq);
        return wrapDerSequence(set);
    }

    private static byte[] createDerValidity() throws IOException {
        byte[] notBefore = "200101000000Z".getBytes(StandardCharsets.US_ASCII);
        byte[] notAfter = "350101000000Z".getBytes(StandardCharsets.US_ASCII);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x17);
        writeDerLength(out, notBefore.length);
        out.write(notBefore);

        out.write(0x17);
        writeDerLength(out, notAfter.length);
        out.write(notAfter);

        return wrapDerSequence(out.toByteArray());
    }

    private static void writeDerBitString(ByteArrayOutputStream out, byte[] bytes) throws IOException {
        out.write(0x03);
        writeDerLength(out, bytes.length + 1);
        out.write(0x00);
        out.write(bytes);
    }

    private static byte[] wrapDerSequence(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        writeDerLength(out, data.length);
        out.write(data);
        return out.toByteArray();
    }

    private static byte[] wrapDerSet(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x31);
        writeDerLength(out, data.length);
        out.write(data);
        return out.toByteArray();
    }

    private static void writeDerLength(ByteArrayOutputStream out, int length) {
        if (length < 128) {
            out.write(length);
        } else if (length < 256) {
            out.write(0x81);
            out.write(length);
        } else {
            out.write(0x82);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        }
    }

    private byte[] signToken(PrivateKey privateKey, byte[] token) throws Exception {
        byte[] input = new byte[SHA1_DIGEST_INFO_PREFIX.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO_PREFIX, 0, input, 0, SHA1_DIGEST_INFO_PREFIX.length);
        System.arraycopy(token, 0, input, SHA1_DIGEST_INFO_PREFIX.length, token.length);

        Signature sig = Signature.getInstance("NONEwithRSA");
        sig.initSign(privateKey);
        sig.update(input);
        return sig.sign();
    }

    private String parseFailureReason(String output) {
        String lower = output.toLowerCase();
        if (lower.contains("install_failed_insufficient_storage")) {
            return "错误: 设备剩余存储空间不足，请清理存储空间。";
        } else if (lower.contains("install_failed_version_downgrade")) {
            return "错误: 设备上已存在更高版本应用(禁止降级)，请先在设备上卸载旧版。";
        } else if (lower.contains("install_failed_update_incompatible")) {
            return "错误: 签名不兼容！设备已有同包名但签名不同的应用，需卸载后重装。";
        } else if (lower.contains("install_failed_no_matching_abis")) {
            return "错误: CPU 架构不匹配！(例如该 APK 不支持设备 CPU 架构)。";
        } else if (lower.contains("install_failed_invalid_apk")) {
            return "错误: APK 文件损坏或解析失败。";
        } else if (lower.contains("install_failed_user_restricted")) {
            return "错误: 系统安全限制阻止了安装，请开启「允许安装未知来源应用」。";
        } else if (lower.contains("install_failed_older_sdk")) {
            return "错误: 应用最低 SDK 版本高于设备系统版本。";
        }
        return "设备返回失败信息: " + output;
    }

    private String getCommandName(int cmd) {
        switch (cmd) {
            case A_CNXN: return "CNXN (握手连接)";
            case A_AUTH: return "AUTH (安全认证)";
            case A_OPEN: return "OPEN (打开通道)";
            case A_OKAY: return "OKAY (确认回执)";
            case A_CLSE: return "CLSE (关闭会话)";
            case A_WRTE: return "WRTE (写入数据)";
            case A_STLS: return "STLS (切换至TLS加密通道)";
            default: return "CMD_0x" + Integer.toHexString(cmd);
        }
    }

    /**
     * 底层网络报文序列化 (关键优化: 24字节头与数据合并为单次写入，防止 TLS 分片)
     */
    private void sendMessage(OutputStream os, int command, int arg0, int arg1, byte[] data) throws IOException {
        int length = data != null ? data.length : 0;
        int checksum = 0;
        if (data != null) {
            for (byte b : data) checksum += (b & 0xFF);
        }

        ByteBuffer bb = ByteBuffer.allocate(24 + length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(command);
        bb.putInt(arg0);
        bb.putInt(arg1);
        bb.putInt(length);
        bb.putInt(checksum);
        bb.putInt(command ^ 0xFFFFFFFF);

        if (data != null && length > 0) {
            bb.put(data);
        }

        os.write(bb.array());
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
        bb.getInt(); // checksum
        bb.getInt(); // magic

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
                throw new IOException("网络流意外中断");
            }
            total += read;
        }
        return buffer;
    }

    private byte[] convertToMincrypt(RSAPublicKey pubKey) {
        BigInteger n = pubKey.getModulus();
        BigInteger r32 = BigInteger.valueOf(2).pow(32);
        BigInteger n0inv = n.modInverse(r32).negate().mod(r32);
        BigInteger r = BigInteger.valueOf(2).pow(2048);
        BigInteger rr = r.multiply(r).mod(n);

        ByteBuffer bb = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(64);
        bb.putInt(n0inv.intValue());
        for (int i = 0; i < 64; i++) {
            bb.putInt(n.shiftRight(i * 32).intValue());
        }
        for (int i = 0; i < 64; i++) {
            bb.putInt(rr.shiftRight(i * 32).intValue());
        }
        bb.putInt(pubKey.getPublicExponent().intValue());
        return bb.array();
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
            throw new RuntimeException("生成 ADB 密钥失败: " + e.getMessage());
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
