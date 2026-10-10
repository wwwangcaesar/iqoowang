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
import java.util.Arrays;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 手机端纯 Java ADB 客户端引擎
 *
 * 修复历史：
 * v4 (终极修复版)
 * - SYNC 协议：每 64KB DATA 块独立封装为一次 A_WRTE，每次发完后立即等 A_OKAY
 * - Shell 命令：使用标准 shell: 服务名，兼容 Android 4.x ~ 14+
 * - 废弃 pm install -S 流式模式（仅 Android 8+ 支持），统一走 SYNC push + pm install 文件路径
 * - Android 11+ TLS：A_STLS 残留报文透明过滤
 */
public class AdbClientEngine {

    public interface AdbInstallListener {
        void onLog(String message);
        void onProgress(long transferredBytes, long totalBytes);
        void onSuccess(String message);
        void onError(String error);
    }

    // ADB 报文命令字 (Little-Endian 解码为 int)
    private static final int A_CNXN = 0x4e584e43;  // "CNXN"
    private static final int A_AUTH = 0x48545541;  // "AUTH"
    private static final int A_OPEN = 0x4e45504f;  // "OPEN"
    private static final int A_OKAY = 0x59414b4f;  // "OKAY"
    private static final int A_CLSE = 0x45534c43;  // "CLSE"
    private static final int A_WRTE = 0x45545257;  // "WRTE"
    private static final int A_STLS = 0x534c5453;  // "STLS" (Android 11+)

    private static final int AUTH_TYPE_TOKEN       = 1;
    private static final int AUTH_TYPE_SIGNATURE   = 2;
    private static final int AUTH_TYPE_RSAPUBLICKEY = 3;

    private static final int ADB_VERSION = 0x01000001;
    private static final int MAX_PAYLOAD = 1024 * 1024;   // 1MB
    private static final int SYNC_DATA_MAX = 64 * 1024;   // 64KB per DATA chunk

    private static final byte[] SHA1_DI_PREFIX = {
            0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14
    };

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isCancelled = false;
    private final java.util.concurrent.atomic.AtomicInteger idGen = new java.util.concurrent.atomic.AtomicInteger(100);

    public AdbClientEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public void cancel() { isCancelled = true; }

    // ─────────────────────────────────────────────────────────────────────────
    // Session 封装
    // ─────────────────────────────────────────────────────────────────────────

    private static class AdbSession implements AutoCloseable {
        Socket plain;
        SSLSocket ssl;
        InputStream is;
        OutputStream os;

        AdbSession(Socket s) throws IOException {
            plain = s;
            is = s.getInputStream();
            os = s.getOutputStream();
        }

        void upgradeTls(SSLSocket s) throws IOException {
            ssl = s;
            is = s.getInputStream();
            os = s.getOutputStream();
        }

        @Override public void close() {
            if (ssl   != null) try { ssl.close();   } catch (Exception ignored) {}
            if (plain != null) try { plain.close(); } catch (Exception ignored) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 公共入口
    // ─────────────────────────────────────────────────────────────────────────

    public void installApk(String ip, int port, File apk, AdbInstallListener cb) {
        isCancelled = false;
        new Thread(() -> connect(ip, port, apk, cb, true)).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 连接 & 握手
    // ─────────────────────────────────────────────────────────────────────────

    private void connect(String ip, int port, File apk, AdbInstallListener cb, boolean tryFallback) {
        postLog(cb, "> 正在建立 ADB 调试会话 [" + ip + ":" + port + "] ...");

        Socket sock = new Socket();
        try {
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(30000);
            sock.connect(new InetSocketAddress(ip, port), 5000);
        } catch (Exception e) {
            postLog(cb, "⚠️ 端口 " + port + " 无法连通 (" + e.getMessage() + ")");
            try { sock.close(); } catch (Exception ignored) {}
            if (tryFallback && port != 5555 && portOpen(ip, 5555, 1500)) {
                postLog(cb, "⚡ 探测到 5555 端口开放，自动切换直连...");
                connect(ip, 5555, apk, cb, false);
            } else {
                postError(cb, "无法连通 " + ip + ":" + port + "。" + e.getMessage());
            }
            return;
        }

        try (AdbSession s = new AdbSession(sock)) {
            postLog(cb, "✔ TCP 已连通，开始 ADB 握手...");
            if (!handshake(s, ip, port, cb)) {
                if (tryFallback && port != 5555 && portOpen(ip, 5555, 1500)) {
                    postLog(cb, "⚠️ 握手失败，探测到 5555，自动切换...");
                    connect(ip, 5555, apk, cb, false);
                } else {
                    postError(cb, "ADB 握手未完成，连接已断开。");
                }
                return;
            }
            postLog(cb, "✔ ADB 握手成功！开始安装...");
            pushAndInstall(s, apk, cb);
        } catch (Exception e) {
            if (tryFallback && port != 5555 && portOpen(ip, 5555, 1500)) {
                postLog(cb, "⚠️ 异常: " + e.getMessage() + "，自动切换至 5555...");
                connect(ip, 5555, apk, cb, false);
            } else {
                postError(cb, "安装异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }
    }

    /**
     * ADB 握手：明文 TCP 或 TLS（Android 11+），支持 RSA 认证
     */
    private boolean handshake(AdbSession s, String ip, int port, AdbInstallListener cb) throws Exception {
        byte[] banner = "host::IQOOWang\0".getBytes(StandardCharsets.UTF_8);
        writeMsg(s.os, A_CNXN, ADB_VERSION, MAX_PAYLOAD, banner);

        AdbMessage m = readMsg(s.is);
        postLog(cb, "> 收到首个握手包: 0x" + Integer.toHexString(m.cmd) + " (" + cmdName(m.cmd) + ")");

        // Android 11+ TLS 升级
        if (m.cmd == A_STLS) {
            postLog(cb, "🔐 A_STLS 捕获，正在切换至 TLS 1.3...");
            writeMsg(s.os, A_STLS, 0, 0, null);
            SSLSocket ssl = (SSLSocket) buildTlsCtx().getSocketFactory()
                    .createSocket(s.plain, ip, port, false);
            ssl.setUseClientMode(true);
            ssl.startHandshake();
            s.upgradeTls(ssl);
            postLog(cb, "✔ TLS 1.3 建立成功！");

            writeMsg(s.os, A_CNXN, ADB_VERSION, MAX_PAYLOAD, banner);
            m = readFiltered(s.is, cb);  // 过滤 TLS 内残余 A_STLS
            postLog(cb, "> TLS 内回执: 0x" + Integer.toHexString(m.cmd) + " (" + cmdName(m.cmd) + ")");
        }

        // 免密直接放行
        if (m.cmd == A_CNXN) {
            String id = m.data != null ? new String(m.data, StandardCharsets.UTF_8).trim() : "";
            postLog(cb, "✔ 设备免密连通！标识: " + (id.isEmpty() ? "(已就绪)" : id));
            return true;
        }

        // RSA 鉴权
        if (m.cmd == A_AUTH && m.arg0 == AUTH_TYPE_TOKEN) {
            postLog(cb, "> 设备要求 RSA 鉴权...");
            KeyPair kp = loadOrGenKeys();

            // 尝试签名（已信任过的设备）
            try {
                byte[] sig = signToken(kp.getPrivate(), m.data);
                writeMsg(s.os, A_AUTH, AUTH_TYPE_SIGNATURE, 0, sig);
                AdbMessage r = readFiltered(s.is, null);
                if (r.cmd == A_CNXN) {
                    postLog(cb, "✔ 密钥签名验证通过！");
                    return true;
                }
            } catch (Exception ignored) {}

            // 发送公钥（ro.adb.secure=0 时自动接受）
            byte[] mk = toMincrypt((RSAPublicKey) kp.getPublic());
            byte[] pk = (Base64.encodeToString(mk, Base64.NO_WRAP) + " IQOOWang\0").getBytes(StandardCharsets.UTF_8);
            writeMsg(s.os, A_AUTH, AUTH_TYPE_RSAPUBLICKEY, 0, pk);
            postLog(cb, "📡 公钥已发送，等待设备授权...");

            AdbMessage r2 = readFiltered(s.is, null);
            if (r2.cmd == A_CNXN) {
                postLog(cb, "✔ 公钥授权通过！");
                return true;
            }
        }

        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 安装流程
    // ─────────────────────────────────────────────────────────────────────────

    private void pushAndInstall(AdbSession s, File apk, AdbInstallListener cb) {
        long sz = apk.length();
        postLog(cb, "> 启动 APK 传输 (" + String.format("%.1f MB", sz / 1048576f) + ") ...");

        String dst = "/data/local/tmp/_iqoo_inst.apk";

        // 通道1：SYNC 协议推送（全版本兼容）
        postLog(cb, "> 通道1: 使用 SYNC 协议推送文件...");
        boolean ok = syncPush(s, apk, dst, cb);

        // 通道2：shell cat 管道写入（备用）
        if (!ok && !isCancelled) {
            postLog(cb, "> SYNC 失败，切换至通道2: shell cat 管道...");
            ok = catPush(s, apk, dst, cb);
        }

        if (!ok || isCancelled) {
            postError(cb, isCancelled ? "已取消。" : "APK 文件传输失败，请检查网络和目标设备状态。");
            return;
        }

        postLog(cb, "✔ 文件传输完毕！正在执行系统安装...");
        shell(s, "chmod 644 " + dst, cb, false);
        String result = shell(s, "pm install -r -t -d -g " + dst, cb, true);
        shell(s, "rm -f " + dst, cb, false);

        postLog(cb, "> 安装回执: " + (result.isEmpty() ? "(无输出)" : result.trim()));
        if (result.toLowerCase().contains("success")) {
            mainHandler.post(() -> cb.onSuccess("🎉 应用已成功安装至设备！(Success)"));
        } else {
            postError(cb, "安装未完成！\n" + failReason(result) + "\n原始输出: " + (result.isEmpty() ? "命令无输出" : result.trim()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 通道1：SYNC 协议推送
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 使用标准 ADB SYNC 协议推送文件。
     *
     * 协议说明：
     *   Client → Server: A_OPEN "sync:"
     *   Server → Client: A_OKAY
     *   Client → Server: A_WRTE [SEND + pathlen + path,mode]  → 等 A_OKAY
     *   Client → Server: A_WRTE [DATA + datalen + bytes]       → 等 A_OKAY（每 64KB 一次）
     *   Client → Server: A_WRTE [DONE + mtime]                 → 等 A_OKAY
     *   Server → Client: A_WRTE [OKAY or FAIL + msg]
     *   Client → Server: A_CLSE
     */
    private boolean syncPush(AdbSession s, File apk, String dst, AdbInstallListener cb) {
        int lid = nextId();
        try {
            // 打开 sync 服务
            writeMsg(s.os, A_OPEN, lid, 0, "sync:".getBytes(StandardCharsets.US_ASCII));
            AdbMessage resp = readFiltered(s.is, null);
            if (resp.cmd != A_OKAY) {
                postLog(cb, "> SYNC 通道打开失败: 0x" + Integer.toHexString(resp.cmd));
                return false;
            }
            int rid = resp.arg1;

            long total = 0, sz = apk.length();

            // 1. SEND 头
            String pmode = dst + ",33188"; // 0644
            byte[] pb = pmode.getBytes(StandardCharsets.UTF_8);
            ByteBuffer sh = ByteBuffer.allocate(8 + pb.length).order(ByteOrder.LITTLE_ENDIAN);
            sh.put("SEND".getBytes(StandardCharsets.US_ASCII));
            sh.putInt(pb.length);
            sh.put(pb);
            syncWriteFrame(s, lid, rid, sh.array());

            // 2. DATA 块（每 64KB 一次 A_WRTE + A_OKAY）
            byte[] buf = new byte[SYNC_DATA_MAX];
            try (FileInputStream fis = new FileInputStream(apk)) {
                int n;
                while ((n = fis.read(buf)) != -1 && !isCancelled) {
                    ByteBuffer df = ByteBuffer.allocate(8 + n).order(ByteOrder.LITTLE_ENDIAN);
                    df.put("DATA".getBytes(StandardCharsets.US_ASCII));
                    df.putInt(n);
                    df.put(buf, 0, n);
                    syncWriteFrame(s, lid, rid, df.array());

                    total += n;
                    final long cur = total;
                    mainHandler.post(() -> cb.onProgress(cur, sz));
                }
            }

            if (isCancelled) { writeMsg(s.os, A_CLSE, lid, rid, null); return false; }

            // 3. DONE
            ByteBuffer df = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            df.put("DONE".getBytes(StandardCharsets.US_ASCII));
            df.putInt((int) (System.currentTimeMillis() / 1000));
            syncWriteFrame(s, lid, rid, df.array());

            // 4. 读 OKAY/FAIL 回执
            boolean ok = true;
            AdbMessage status = readNextWriteMsg(s.is, lid, rid, s.os);
            if (status != null && status.data != null && status.data.length >= 4) {
                String tag = new String(status.data, 0, 4, StandardCharsets.US_ASCII);
                if ("FAIL".equals(tag)) {
                    ok = false;
                    String reason = status.data.length > 8
                            ? new String(status.data, 8, status.data.length - 8, StandardCharsets.UTF_8) : "";
                    postLog(cb, "> SYNC FAIL: " + reason);
                } else {
                    postLog(cb, "> SYNC 状态: " + tag);
                }
            }

            writeMsg(s.os, A_CLSE, lid, rid, null);
            return ok;

        } catch (Exception e) {
            postLog(cb, "> SYNC 异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 发送一帧 SYNC 数据（A_WRTE payload），超长时分片，每片等 A_OKAY
     */
    private void syncWriteFrame(AdbSession s, int lid, int rid, byte[] data) throws IOException {
        int off = 0;
        while (off < data.length) {
            int len = Math.min(MAX_PAYLOAD, data.length - off);
            byte[] chunk = Arrays.copyOfRange(data, off, off + len);
            writeMsg(s.os, A_WRTE, lid, rid, chunk);
            off += len;
            ackWait(s.is, s.os, lid, rid, 10000);
        }
    }

    /**
     * 等待下一个 A_WRTE（携带 SYNC 状态），过滤中间的 A_OKAY
     */
    private AdbMessage readNextWriteMsg(InputStream is, int lid, int rid, OutputStream os) throws IOException {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            AdbMessage m = readMsg(is);
            if (m.cmd == A_WRTE) {
                rawWriteMsg(os, A_OKAY, lid, rid, null);
                return m;
            }
            if (m.cmd == A_OKAY) continue;
            return m; // A_CLSE or 0
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 通道2：shell cat 管道写入
    // ─────────────────────────────────────────────────────────────────────────

    private boolean catPush(AdbSession s, File apk, String dst, AdbInstallListener cb) {
        int lid = nextId();
        try {
            writeMsg(s.os, A_OPEN, lid, 0, ("shell:cat > " + dst).getBytes(StandardCharsets.UTF_8));
            AdbMessage resp = readFiltered(s.is, null);
            if (resp.cmd != A_OKAY) {
                postLog(cb, "> shell cat 管道打开失败: 0x" + Integer.toHexString(resp.cmd)
                        + " (" + cmdName(resp.cmd) + ")");
                return false;
            }
            int rid = resp.arg1;
            long sz = apk.length(), total = 0;
            byte[] buf = new byte[32 * 1024];

            try (FileInputStream fis = new FileInputStream(apk)) {
                int n;
                while ((n = fis.read(buf)) != -1 && !isCancelled) {
                    writeMsg(s.os, A_WRTE, lid, rid, Arrays.copyOf(buf, n));
                    ackWait(s.is, s.os, lid, rid, 8000);
                    total += n;
                    final long cur = total;
                    mainHandler.post(() -> cb.onProgress(cur, sz));
                }
            }
            writeMsg(s.os, A_CLSE, lid, rid, null);
            return !isCancelled;
        } catch (Exception e) {
            postLog(cb, "> cat 推送异常: " + e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shell 命令执行
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 执行 Shell 命令，返回全部输出。
     * 使用 shell: 服务名（兼容 Android 4.x ~ 14+，不用 exec:）。
     */
    private String shell(AdbSession s, String cmd, AdbInstallListener cb, boolean logLines) {
        StringBuilder sb = new StringBuilder();
        int lid = nextId();
        try {
            writeMsg(s.os, A_OPEN, lid, 0, ("shell:" + cmd).getBytes(StandardCharsets.UTF_8));
            AdbMessage resp = readFiltered(s.is, null);
            if (resp.cmd != A_OKAY) {
                postLog(cb, "> shell 通道打开失败: 0x" + Integer.toHexString(resp.cmd));
                return "";
            }
            int rid = resp.arg1;

            while (true) {
                AdbMessage m = readMsg(s.is);
                if (m.data != null && m.data.length > 0) {
                    String part = new String(m.data, StandardCharsets.UTF_8);
                    sb.append(part);
                    if (logLines && cb != null) postLog(cb, "  " + part.trim());
                }
                if (m.cmd == A_CLSE || m.cmd == 0) break;
                if (m.cmd == A_WRTE) rawWriteMsg(s.os, A_OKAY, lid, rid, null);
            }
        } catch (Exception ignored) {}
        return sb.toString().trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 流控辅助
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 等待对端 A_OKAY，期间接收并回复对端发来的 A_WRTE
     */
    private boolean ackWait(InputStream is, OutputStream os, int lid, int rid, long ms) throws IOException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            AdbMessage m = readMsg(is);
            if (m.cmd == A_OKAY) return true;
            if (m.cmd == A_WRTE) rawWriteMsg(os, A_OKAY, lid, rid, null);
            else if (m.cmd == A_CLSE || m.cmd == 0) return false;
        }
        return false;
    }

    /**
     * 过滤 TLS 握手残余的 A_STLS，返回第一个有效报文
     */
    private AdbMessage readFiltered(InputStream is, AdbInstallListener cb) throws IOException {
        while (true) {
            AdbMessage m = readMsg(is);
            if (m.cmd == 0) return m;
            if (m.cmd == A_STLS) {
                if (cb != null) postLog(cb, "> 过滤残余 A_STLS");
                continue;
            }
            return m;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 端口探测
    // ─────────────────────────────────────────────────────────────────────────

    private boolean portOpen(String ip, int port, int ms) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), ms);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TLS 上下文
    // ─────────────────────────────────────────────────────────────────────────

    private SSLContext buildTlsCtx() throws Exception {
        TrustManager[] tm = { new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        KeyManager[] km = null;
        try {
            KeyPair kp = loadOrGenKeys();
            X509Certificate cert = selfSignedCert(kp);
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("k", kp.getPrivate(), null, new java.security.cert.Certificate[]{cert});
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, null);
            km = kmf.getKeyManagers();
        } catch (Exception ignored) {}
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(km, tm, new SecureRandom());
        return ctx;
    }

    private X509Certificate selfSignedCert(KeyPair kp) throws Exception {
        ByteArrayOutputStream tbs = new ByteArrayOutputStream();
        derInt(tbs, BigInteger.valueOf(System.currentTimeMillis()));
        byte[] alg = {0x30,0x0d,0x06,0x09,0x2a,(byte)0x86,0x48,(byte)0x86,(byte)0xf7,0x0d,0x01,0x01,0x0b,0x05,0x00};
        tbs.write(alg);
        byte[] name = derX500("adb_client");
        tbs.write(name); tbs.write(derValidity()); tbs.write(name);
        tbs.write(kp.getPublic().getEncoded());
        byte[] tbsBytes = derSeq(tbs.toByteArray());
        Signature sg = Signature.getInstance("SHA256withRSA");
        sg.initSign(kp.getPrivate()); sg.update(tbsBytes);
        ByteArrayOutputStream cert = new ByteArrayOutputStream();
        cert.write(tbsBytes); cert.write(alg); derBits(cert, sg.sign());
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(derSeq(cert.toByteArray())));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 报文 I/O
    // ─────────────────────────────────────────────────────────────────────────

    private void writeMsg(OutputStream os, int cmd, int a0, int a1, byte[] data) throws IOException {
        rawWriteMsg(os, cmd, a0, a1, data);
    }

    private void rawWriteMsg(OutputStream os, int cmd, int a0, int a1, byte[] data) throws IOException {
        int len = data != null ? data.length : 0;
        int ck = 0;
        if (data != null) for (byte b : data) ck += (b & 0xFF);
        ByteBuffer bb = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd); bb.putInt(a0); bb.putInt(a1);
        bb.putInt(len); bb.putInt(ck); bb.putInt(cmd ^ 0xFFFFFFFF);
        if (data != null && len > 0) bb.put(data);
        os.write(bb.array()); os.flush();
    }

    private AdbMessage readMsg(InputStream is) throws IOException {
        byte[] h = readFull(is, 24);
        if (h == null) return new AdbMessage(0, 0, 0, null);
        ByteBuffer bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = bb.getInt(), a0 = bb.getInt(), a1 = bb.getInt();
        int dlen = bb.getInt(); bb.getInt(); bb.getInt();
        byte[] data = null;
        if (dlen > 0 && dlen <= MAX_PAYLOAD) data = readFull(is, dlen);
        return new AdbMessage(cmd, a0, a1, data);
    }

    private byte[] readFull(InputStream is, int n) throws IOException {
        byte[] buf = new byte[n]; int total = 0;
        while (total < n) {
            int r = is.read(buf, total, n - total);
            if (r == -1) { if (total == 0) return null; throw new IOException("流中断 (" + total + "/" + n + ")"); }
            total += r;
        }
        return buf;
    }

    private int nextId() { return idGen.incrementAndGet(); }

    // ─────────────────────────────────────────────────────────────────────────
    // RSA 认证
    // ─────────────────────────────────────────────────────────────────────────

    private byte[] signToken(PrivateKey pk, byte[] token) throws Exception {
        byte[] in = new byte[SHA1_DI_PREFIX.length + token.length];
        System.arraycopy(SHA1_DI_PREFIX, 0, in, 0, SHA1_DI_PREFIX.length);
        System.arraycopy(token, 0, in, SHA1_DI_PREFIX.length, token.length);
        Signature sg = Signature.getInstance("NONEwithRSA");
        sg.initSign(pk); sg.update(in); return sg.sign();
    }

    private byte[] toMincrypt(RSAPublicKey pub) {
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.valueOf(2).pow(32);
        BigInteger n0inv = n.modInverse(r32).negate().mod(r32);
        BigInteger r = BigInteger.valueOf(2).pow(2048);
        BigInteger rr = r.multiply(r).mod(n);
        ByteBuffer bb = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(64); bb.putInt(n0inv.intValue());
        for (int i = 0; i < 64; i++) bb.putInt(n.shiftRight(i * 32).intValue());
        for (int i = 0; i < 64; i++) bb.putInt(rr.shiftRight(i * 32).intValue());
        bb.putInt(pub.getPublicExponent().intValue());
        return bb.array();
    }

    private KeyPair loadOrGenKeys() {
        SharedPreferences sp = context.getSharedPreferences("adb_keys", Context.MODE_PRIVATE);
        String prv = sp.getString("priv", null), pub = sp.getString("pub", null);
        if (prv != null && pub != null) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                return new KeyPair(
                    kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, Base64.DEFAULT))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(prv, Base64.DEFAULT))));
            } catch (Exception ignored) {}
        }
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048); KeyPair kp = g.generateKeyPair();
            sp.edit()
              .putString("priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.DEFAULT))
              .putString("pub",  Base64.encodeToString(kp.getPublic().getEncoded(),  Base64.DEFAULT))
              .apply();
            return kp;
        } catch (Exception e) { throw new RuntimeException("密钥生成失败: " + e.getMessage()); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DER 工具
    // ─────────────────────────────────────────────────────────────────────────

    private static void derInt(ByteArrayOutputStream o, BigInteger v) throws IOException {
        byte[] b = v.toByteArray(); o.write(0x02); derLen(o, b.length); o.write(b);
    }
    private static byte[] derX500(String cn) throws IOException {
        byte[] cb = cn.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        a.write(new byte[]{0x06,0x03,0x55,0x04,0x03}); a.write(0x13); derLen(a, cb.length); a.write(cb);
        return derSeq(derSet(derSeq(a.toByteArray())));
    }
    private static byte[] derValidity() throws IOException {
        byte[] nb = "200101000000Z".getBytes(StandardCharsets.US_ASCII);
        byte[] na = "350101000000Z".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x17); derLen(o, nb.length); o.write(nb);
        o.write(0x17); derLen(o, na.length); o.write(na);
        return derSeq(o.toByteArray());
    }
    private static void derBits(ByteArrayOutputStream o, byte[] d) throws IOException {
        o.write(0x03); derLen(o, d.length + 1); o.write(0x00); o.write(d);
    }
    private static byte[] derSeq(byte[] d) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); o.write(0x30); derLen(o, d.length); o.write(d); return o.toByteArray();
    }
    private static byte[] derSet(byte[] d) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); o.write(0x31); derLen(o, d.length); o.write(d); return o.toByteArray();
    }
    private static void derLen(ByteArrayOutputStream o, int l) {
        if (l < 128) o.write(l);
        else if (l < 256) { o.write(0x81); o.write(l); }
        else { o.write(0x82); o.write((l >> 8) & 0xff); o.write(l & 0xff); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 辅助
    // ─────────────────────────────────────────────────────────────────────────

    private String failReason(String out) {
        String l = out.toLowerCase();
        if (l.contains("install_failed_insufficient_storage")) return "设备存储空间不足。";
        if (l.contains("install_failed_version_downgrade"))   return "设备已有更高版本，需先卸载。";
        if (l.contains("install_failed_update_incompatible")) return "签名不兼容，先卸载旧版。";
        if (l.contains("install_failed_no_matching_abis"))    return "CPU 架构不匹配。";
        if (l.contains("install_failed_invalid_apk"))         return "APK 无效或损坏。";
        if (l.contains("install_failed_user_restricted"))     return "系统安全策略阻止安装。";
        if (l.contains("install_failed_older_sdk"))           return "应用最低 SDK 版本高于设备。";
        return "设备返回: " + out;
    }

    private String cmdName(int c) {
        switch (c) {
            case A_CNXN: return "CNXN"; case A_AUTH: return "AUTH";
            case A_OPEN: return "OPEN"; case A_OKAY: return "OKAY";
            case A_CLSE: return "CLSE"; case A_WRTE: return "WRTE";
            case A_STLS: return "STLS"; default: return "0x" + Integer.toHexString(c);
        }
    }

    private void postLog(AdbInstallListener l, String m) { mainHandler.post(() -> l.onLog(m)); }
    private void postError(AdbInstallListener l, String e) { mainHandler.post(() -> l.onError(e)); }

    private static class AdbMessage {
        final int cmd, arg0, arg1; final byte[] data;
        AdbMessage(int cmd, int arg0, int arg1, byte[] data) {
            this.cmd = cmd; this.arg0 = arg0; this.arg1 = arg1; this.data = data;
        }
    }
}
