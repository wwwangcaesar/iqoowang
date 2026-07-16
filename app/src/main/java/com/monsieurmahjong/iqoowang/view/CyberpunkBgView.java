package com.monsieurmahjong.iqoowang.view;


import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * 赛博朋克风格全屏动画背景
 *
 * 效果层次（从下到上）：
 *  1. 深色底层背景
 *  2. 呼吸灯光晕（中心径向渐变，随机变换颜色，缓慢呼吸脉冲）
 *  3. 微型电路网格（极淡，增加质感）
 *  4. 顶部能量扫描条 + 粒子
 *  5. 底部能量条 + 粒子
 *  6. 漂浮粒子（全屏散布，缓慢上升）
 *  7. 四角霓虹装饰框（随呼吸闪烁）
 *
 * ── 性能重写说明（原实现在 QuickLogActivity 上会造成明显卡顿，含金额输入框跟着卡）──
 * 原实现的问题：
 *  1) setLayerType(LAYER_TYPE_SOFTWARE) 强制整个 View 走 CPU 软件光栅化；
 *  2) drawParticles() 每帧为 32 个粒子各创建 2 个新 BlurMaskFilter（每帧 64 次分配）；
 *  3) 静态不变的网格（drawGrid）每帧都重新画一遍所有网格线和交叉点圆点；
 *  4) 多处 LinearGradient/RadialGradient 在 onDraw 里每帧新建；
 *  5) 固定 16ms 定时器，不做帧间隔补偿。
 * 软件光栅化 + 每帧大量对象分配（GC 压力）是主线程卡顿、进而拖累同一线程上
 * EditText 输入响应的根本原因。
 *
 * 现在的做法：
 *  1) 去掉软件层，走硬件加速；
 *  2) 用一张预渲染好的"柔光贴图"(glowBitmap) 通过 ColorFilter 染色 + 缩放代替
 *     BlurMaskFilter 实时模糊，所有柔光效果变成一次性很便宜的 drawBitmap；
 *  3) 网格提前渲染进一张 Bitmap 缓存，逐帧只是 drawBitmap；
 *  4) 底色渐变的 Shader 只在尺寸变化时创建一次，逐帧复用；
 *  5) 动画帧率降到 ~30fps，并用真实帧间隔（delta time）驱动，不再假设固定 16ms，
 *     视觉速度不受帧率变化影响，也不会因为掉帧而"卡顿感"更明显。
 */
public class CyberpunkBgView extends View {

    // ── 呼吸灯颜色表（赛博朋克多彩） ─────────────────────
    private static final int[] BREATH_COLORS = {
            0xFF00FFFF,   // 电子青
            0xFF0066FF,   // 电子蓝
            0xFFAA00FF,   // 霓虹紫
            0xFFFF00CC,   // 霓虹粉
            0xFFFF3300,   // 等离子红
            0xFF00FF88,   // 霓虹绿
            0xFFFF8800,   // 等离子橙
            0xFF33DDFF,   // 冰蓝
    };

    /** 动画基准帧长（ms），所有速度常量都是"每 BASE_FRAME_MS 走多少"，用 dt 归一化后不受实际帧率影响 */
    private static final float BASE_FRAME_MS = 16f;
    /** 实际目标帧间隔：30fps 足够表现呼吸/扫描这种慢动画，比 60fps 省一半开销 */
    private static final long  TICK_INTERVAL_MS = 33L;

    // ── 呼吸状态 ─────────────────────────────────────────
    private float breathPhase   = 0f;
    private float breathSpeed   = 0.018f;   // ~5s / 周期（按 BASE_FRAME_MS 归一化）

    private int   curColor      = BREATH_COLORS[0];
    private int   tgtColor      = BREATH_COLORS[1];
    private float colorLerp     = 0f;
    private float colorLerpSpd  = 0.006f;
    private int   colorIdx      = 1;

    // ── 漂浮粒子 ─────────────────────────────────────────
    private static final int PART_COUNT = 32;
    private final float[] px  = new float[PART_COUNT];
    private final float[] py  = new float[PART_COUNT];
    private final float[] pvy = new float[PART_COUNT];
    private final float[] pvx = new float[PART_COUNT];
    private final float[] pSz = new float[PART_COUNT];
    private final float[] pAl = new float[PART_COUNT];
    private final int[]   pCo = new int  [PART_COUNT];

    // ── 顶/底扫描光 ──────────────────────────────────────
    private float topScanX    = 0f;
    private float botScanX    = 0f;
    private float topScanSpd  = 4.5f;
    private float botScanSpd  = -3.2f;

    // ── 角装饰脉冲 ───────────────────────────────────────
    private float cornerPhase = 0f;

    // ── 绘制工具 ─────────────────────────────────────────
    private final Paint  paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd   = new Random();

    // ── 预渲染柔光贴图（取代逐帧 BlurMaskFilter）───────────
    private Bitmap glowBitmap;
    private static final int GLOW_BMP_SIZE = 96;
    private final Rect glowSrc = new Rect(0, 0, GLOW_BMP_SIZE, GLOW_BMP_SIZE);
    private final android.graphics.RectF glowDst = new android.graphics.RectF();

    // ── 缓存：静态网格 + 静态底色渐变 ─────────────────────
    private Bitmap gridBitmap;
    private Shader baseGradient;

    // ── 动画循环 ─────────────────────────────────────────
    private final Handler  handler = new Handler(Looper.getMainLooper());
    private       Runnable loop;
    private       boolean  running = false;
    private       float    vw, vh;
    private       long     lastTickTime = 0L;

    // ── 构造 ─────────────────────────────────────────────
    public CyberpunkBgView(Context c)                        { super(c); setup(); }
    public CyberpunkBgView(Context c, AttributeSet a)        { super(c, a); setup(); }
    public CyberpunkBgView(Context c, AttributeSet a, int d) { super(c, a, d); setup(); }

    private void setup() {
        // 不再强制软件层：柔光效果已经用预渲染贴图实现，硬件加速可以正常绘制
        glowBitmap = createGlowBitmap(GLOW_BMP_SIZE);
    }

    /** 一次性生成一张白色柔光圆点贴图，后续通过 ColorFilter 染成任意颜色复用 */
    private static Bitmap createGlowBitmap(int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setMaskFilter(new BlurMaskFilter(size * 0.24f, BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(size / 2f, size / 2f, size * 0.24f, p);
        return bmp;
    }

    /** 用柔光贴图在 (cx,cy) 画一个指定半径/颜色/透明度的发光点，取代 BlurMaskFilter 实时模糊 */
    private void drawGlowDot(Canvas canvas, float cx, float cy, float radius, int color, int alpha) {
        float half = radius * 2.4f; // 贴图本身带有模糊扩散半径，略放大覆盖范围
        glowDst.set(cx - half, cy - half, cx + half, cy + half);
        paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        paint.setAlpha(alpha);
        canvas.drawBitmap(glowBitmap, glowSrc, glowDst, paint);
        paint.setColorFilter(null);
        paint.setAlpha(255);
    }

    // ── 尺寸变化 ─────────────────────────────────────────
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        vw = w;  vh = h;
        topScanX = vw * 0.2f;
        botScanX = vw * 0.7f;
        initParticles();
        rebuildGridBitmap();
        rebuildBaseGradient();
    }

    /** 底色渐变完全静态（颜色/位置从不随时间变化），只需要在尺寸变化时建一次，逐帧复用 */
    private void rebuildBaseGradient() {
        baseGradient = new LinearGradient(
                vw / 2f, 0, vw / 2f, vh,
                new int[]{ 0xFF0B0820, 0xFF080D28, 0xFF060618 },
                new float[]{ 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP);
    }

    /** 网格线 + 交叉点完全静态，预渲染进 Bitmap，逐帧只需一次 drawBitmap */
    private void rebuildGridBitmap() {
        if (vw <= 0 || vh <= 0) return;
        gridBitmap = Bitmap.createBitmap((int) vw, (int) vh, Bitmap.Config.ARGB_4444);
        Canvas c = new Canvas(gridBitmap);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.6f);
        p.setColor(0xFF33DDFF);
        p.setAlpha(18);

        float gap = dp(32);
        for (float x = 0; x < vw; x += gap) c.drawLine(x, 0, x, vh, p);
        for (float y = 0; y < vh; y += gap) c.drawLine(0, y, vw, y, p);

        p.setStyle(Paint.Style.FILL);
        p.setAlpha(28);
        for (float x = 0; x < vw; x += gap) {
            for (float y = 0; y < vh; y += gap) {
                c.drawCircle(x, y, dp(1.5f), p);
            }
        }
    }

    // ─────────────────────────────────────────────────────
    //  粒子初始化
    // ─────────────────────────────────────────────────────
    private void initParticles() {
        for (int i = 0; i < PART_COUNT; i++) resetParticle(i, true);
    }

    private void resetParticle(int i, boolean anyY) {
        px [i] = rnd.nextFloat() * vw;
        py [i] = anyY ? rnd.nextFloat() * vh : vh + dp(20);
        pvx[i] = (rnd.nextFloat() - 0.5f) * 0.6f;
        pvy[i] = -(rnd.nextFloat() * 1.2f + 0.4f);
        pSz[i] = dp(1.5f) + rnd.nextFloat() * dp(3f);
        pAl[i] = rnd.nextFloat() * 0.55f + 0.15f;
        pCo[i] = BREATH_COLORS[rnd.nextInt(BREATH_COLORS.length)];
    }

    // ─────────────────────────────────────────────────────
    //  动画启停
    // ─────────────────────────────────────────────────────
    public void startAnim() {
        if (running) return;
        running = true;
        lastTickTime = SystemClock.uptimeMillis();
        loop = new Runnable() {
            @Override public void run() {
                if (!running) return;
                long now = SystemClock.uptimeMillis();
                float dt = (now - lastTickTime) / BASE_FRAME_MS;
                lastTickTime = now;
                // 防止后台切回前台时 dt 过大导致动画"跳变"
                if (dt > 6f) dt = 1f;
                tick(dt);
                invalidate();
                handler.postDelayed(this, TICK_INTERVAL_MS);
            }
        };
        handler.post(loop);
    }

    public void stopAnim() {
        running = false;
        handler.removeCallbacks(loop);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnim();
    }

    // ─────────────────────────────────────────────────────
    //  逐帧更新（dt：相对 BASE_FRAME_MS 的归一化时间步长）
    // ─────────────────────────────────────────────────────
    private void tick(float dt) {
        breathPhase += breathSpeed * dt;
        if (breathPhase > (float)(Math.PI * 2)) breathPhase -= (float)(Math.PI * 2);

        colorLerp += colorLerpSpd * dt;
        if (colorLerp >= 1f) {
            colorLerp = 0f;
            curColor  = tgtColor;
            int ni;
            do { ni = rnd.nextInt(BREATH_COLORS.length); } while (ni == colorIdx);
            colorIdx = ni;
            tgtColor = BREATH_COLORS[colorIdx];
        }

        cornerPhase += 0.035f * dt;

        topScanX += topScanSpd * dt;
        if (topScanX > vw + dp(120)) topScanX = -dp(120);
        botScanX += botScanSpd * dt;
        if (botScanX < -dp(120)) botScanX = vw + dp(120);

        for (int i = 0; i < PART_COUNT; i++) {
            py[i] += pvy[i] * dt;
            px[i] += pvx[i] * dt + (float)Math.sin(py[i] * 0.018f) * 0.4f * dt;
            if (py[i] < -dp(20)) resetParticle(i, false);
        }

        // 广播当前混合色，NeonCardView 通过 CyberpunkColorSync 实时同步
        CyberpunkColorSync.updateColor(lerpColor(curColor, tgtColor, colorLerp));
    }

    // ─────────────────────────────────────────────────────
    //  onDraw
    // ─────────────────────────────────────────────────────
    @Override
    protected void onDraw(Canvas canvas) {
        if (vw == 0) return;
        drawBase       (canvas);
        drawBreathing  (canvas);
        drawGrid       (canvas);
        drawEnergyBar  (canvas, true);
        drawEnergyBar  (canvas, false);
        drawParticles  (canvas);
        drawCorners    (canvas);
    }

    // ── 1. 底层深色背景（Shader 只在尺寸变化时建一次，这里直接复用） ──
    private void drawBase(Canvas canvas) {
        paint.setShader(baseGradient);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, vw, vh, paint);
        paint.setShader(null);
    }

    // ── 2. 呼吸灯光晕 ────────────────────────────────────
    private void drawBreathing(Canvas canvas) {
        float breath = (float)(Math.sin(breathPhase) * 0.5 + 0.5);
        int   blendC = lerpColor(curColor, tgtColor, colorLerp);

        int   R = (blendC >> 16) & 0xFF;
        int   G = (blendC >> 8)  & 0xFF;
        int   B =  blendC        & 0xFF;

        float baseR = vw * 0.42f;
        float pulseR= vw * 0.22f * breath;
        float radius = baseR + pulseR;

        float aCenter = 0.28f + 0.28f * breath;
        float aMid    = 0.10f + 0.12f * breath;

        paint.setShader(new RadialGradient(
                vw / 2f, vh / 2f, radius,
                new int[]{
                        Color.argb((int)(aCenter * 255), R, G, B),
                        Color.argb((int)(aMid    * 255), R, G, B),
                        Color.argb(0,                    R, G, B)
                },
                new float[]{ 0f, 0.45f, 1f },
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, vw, vh, paint);
        paint.setShader(null);

        int R2 = (tgtColor >> 16) & 0xFF;
        int G2 = (tgtColor >> 8)  & 0xFF;
        int B2 =  tgtColor        & 0xFF;
        paint.setShader(new RadialGradient(
                vw / 2f, 0, vh * 0.55f,
                new int[]{
                        Color.argb((int)(0.10f * 255), R2, G2, B2),
                        Color.argb(0,                  R2, G2, B2)
                },
                new float[]{ 0f, 1f },
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, vw, vh, paint);
        paint.setShader(null);
    }

    // ── 3. 电路网格（预渲染 Bitmap，逐帧只 drawBitmap） ────
    private void drawGrid(Canvas canvas) {
        if (gridBitmap != null) canvas.drawBitmap(gridBitmap, 0, 0, null);
    }

    // ── 4/5. 顶部/底部能量扫描条 ─────────────────────────
    private void drawEnergyBar(Canvas canvas, boolean isTop) {
        float scanX  = isTop ? topScanX : botScanX;
        float barY   = isTop ? 0 : vh - dp(3);
        float barH   = dp(3);
        int   blendC = lerpColor(curColor, tgtColor, colorLerp);
        int   R      = (blendC >> 16) & 0xFF;
        int   G      = (blendC >> 8)  & 0xFF;
        int   B      =  blendC        & 0xFF;

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(
                0, barY, vw, barY,
                new int[]{ Color.TRANSPARENT,
                        Color.argb(60, R, G, B),
                        Color.argb(60, R, G, B),
                        Color.TRANSPARENT },
                new float[]{ 0f, 0.05f, 0.95f, 1f },
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, barY, vw, barY + barH, paint);
        paint.setShader(null);

        float spotW = dp(180);
        paint.setShader(new LinearGradient(
                scanX - spotW / 2f, 0,
                scanX + spotW / 2f, 0,
                new int[]{ Color.TRANSPARENT,
                        Color.argb(255, R, G, B),
                        Color.WHITE,
                        Color.argb(255, R, G, B),
                        Color.TRANSPARENT },
                new float[]{ 0f, 0.3f, 0.5f, 0.7f, 1f },
                Shader.TileMode.CLAMP));
        canvas.drawRect(scanX - spotW / 2f, barY, scanX + spotW / 2f, barY + barH, paint);
        paint.setShader(null);

        float glowH = isTop ? dp(80) : -dp(80);
        paint.setShader(new LinearGradient(
                0, barY, 0, barY + glowH,
                new int[]{ Color.argb(90, R, G, B), Color.TRANSPARENT },
                null, Shader.TileMode.CLAMP));
        float halfSpot = spotW * 0.35f;
        canvas.drawRect(scanX - halfSpot, barY,
                scanX + halfSpot, barY + glowH, paint);
        paint.setShader(null);

        // 扫描位置发散星粒（改用预渲染柔光贴图）
        drawGlowDot(canvas, scanX, barY + barH / 2f, dp(3), Color.WHITE, 200);
    }

    // ── 6. 漂浮粒子（改用预渲染柔光贴图，取代每帧 64 次 BlurMaskFilter 分配） ──
    private void drawParticles(Canvas canvas) {
        for (int i = 0; i < PART_COUNT; i++) {
            int co = pCo[i];
            // 柔光发散
            drawGlowDot(canvas, px[i], py[i], pSz[i] * 0.6f, co, (int)(pAl[i] * 180));
            // 亮芯
            paint.setColorFilter(null);
            paint.setColor(co);
            paint.setAlpha((int)(pAl[i] * 255));
            canvas.drawCircle(px[i], py[i], pSz[i] * 0.25f, paint);
        }
        paint.setAlpha(255);
    }

    // ── 7. 四角霓虹装饰框（柔光改用贴图，线条保留矢量绘制） ──
    private void drawCorners(Canvas canvas) {
        float pulse  = (float)(Math.sin(cornerPhase) * 0.35 + 0.65);
        int   blendC = lerpColor(curColor, tgtColor, colorLerp);
        int   R      = (blendC >> 16) & 0xFF;
        int   G      = (blendC >> 8)  & 0xFF;
        int   B      =  blendC        & 0xFF;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.SQUARE);
        paint.setMaskFilter(null);
        paint.setColor(Color.argb((int)(pulse * 255), R, G, B));
        paint.setStrokeWidth(dp(2));

        float m = dp(18);
        float s = dp(28);
        drawCornerBracket(canvas, m, m, s, 1, 1);
        drawCornerBracket(canvas, vw - m, m, s, -1, 1);
        drawCornerBracket(canvas, m, vh - m, s, 1, -1);
        drawCornerBracket(canvas, vw - m, vh - m, s, -1, -1);

        // 四角亮点（预渲染柔光贴图）
        int glowAlpha = (int)(pulse * 255);
        int cornerColor = Color.rgb(R, G, B);
        float m2 = dp(18);
        drawGlowDot(canvas, m2, m2, dp(5), cornerColor, glowAlpha);
        drawGlowDot(canvas, vw - m2, m2, dp(5), cornerColor, glowAlpha);
        drawGlowDot(canvas, m2, vh - m2, dp(5), cornerColor, glowAlpha);
        drawGlowDot(canvas, vw - m2, vh - m2, dp(5), cornerColor, glowAlpha);
        paint.setAlpha(255);
    }

    private void drawCornerBracket(Canvas canvas,
                                   float cx, float cy, float len,
                                   float dx, float dy) {
        canvas.drawLine(cx, cy, cx + dx * len, cy, paint);
        canvas.drawLine(cx, cy, cx, cy + dy * len, paint);
    }

    // ─────────────────────────────────────────────────────
    //  工具
    // ─────────────────────────────────────────────────────

    private static int lerpColor(int c1, int c2, float t) {
        int r1 = (c1 >> 16) & 0xFF, r2 = (c2 >> 16) & 0xFF;
        int g1 = (c1 >> 8)  & 0xFF, g2 = (c2 >> 8)  & 0xFF;
        int b1 =  c1        & 0xFF, b2 =  c2        & 0xFF;
        return Color.rgb(
                r1 + (int)((r2 - r1) * t),
                g1 + (int)((g2 - g1) * t),
                b1 + (int)((b2 - b1) * t));
    }

    private float dp(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
