package com.monsieurmahjong.iqoowang.view;


import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
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

    // ── 呼吸状态 ─────────────────────────────────────────
    private float breathPhase   = 0f;
    private float breathSpeed   = 0.018f;   // ~5s / 周期

    private int   curColor      = BREATH_COLORS[0];
    private int   tgtColor      = BREATH_COLORS[1];
    private float colorLerp     = 0f;
    private float colorLerpSpd  = 0.006f;   // ~10s 完成一次颜色过渡
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
    private float botScanSpd  = -3.2f;     // 反向

    // ── 角装饰脉冲 ───────────────────────────────────────
    private float cornerPhase = 0f;

    // ── 绘制工具 ─────────────────────────────────────────
    private final Paint  paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd   = new Random();

    // ── 动画循环 ─────────────────────────────────────────
    private final Handler  handler = new Handler(Looper.getMainLooper());
    private       Runnable loop;
    private       boolean  running = false;
    private       float    vw, vh;

    // ── 构造 ─────────────────────────────────────────────
    public CyberpunkBgView(Context c)                        { super(c); setup(); }
    public CyberpunkBgView(Context c, AttributeSet a)        { super(c, a); setup(); }
    public CyberpunkBgView(Context c, AttributeSet a, int d) { super(c, a, d); setup(); }

    private void setup() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    // ── 尺寸变化 ─────────────────────────────────────────
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        vw = w;  vh = h;
        topScanX = vw * 0.2f;
        botScanX = vw * 0.7f;
        initParticles();
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
        loop    = new Runnable() {
            @Override public void run() {
                if (!running) return;
                tick();
                invalidate();
                handler.postDelayed(this, 16);
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
    //  逐帧更新
    // ─────────────────────────────────────────────────────
    private void tick() {
        // 呼吸相位
        breathPhase += breathSpeed;
        if (breathPhase > (float)(Math.PI * 2)) breathPhase -= (float)(Math.PI * 2);

        // 颜色渐变
        colorLerp += colorLerpSpd;
        if (colorLerp >= 1f) {
            colorLerp = 0f;
            curColor  = tgtColor;
            int ni;
            do { ni = rnd.nextInt(BREATH_COLORS.length); } while (ni == colorIdx);
            colorIdx = ni;
            tgtColor = BREATH_COLORS[colorIdx];
        }

        // 角装饰相位
        cornerPhase += 0.035f;

        // 扫描光 X
        topScanX += topScanSpd;
        if (topScanX > vw + dp(120)) topScanX = -dp(120);
        botScanX += botScanSpd;
        if (botScanX < -dp(120)) botScanX = vw + dp(120);

        // 粒子
        for (int i = 0; i < PART_COUNT; i++) {
            py[i] += pvy[i];
            px[i] += pvx[i] + (float)Math.sin(py[i] * 0.018f) * 0.4f;
            if (py[i] < -dp(20)) resetParticle(i, false);
        }

        // ✅ 广播当前混合色，NeonCardView 通过 CyberpunkColorSync 实时同步
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

    // ── 1. 底层深色背景 ───────────────────────────────────
    private void drawBase(Canvas canvas) {
        // 上深紫 → 下深蓝，略微有色彩而非纯黑
        paint.setShader(new LinearGradient(
                vw / 2f, 0, vw / 2f, vh,
                new int[]{ 0xFF0B0820, 0xFF080D28, 0xFF060618 },
                new float[]{ 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, vw, vh, paint);
        paint.setShader(null);
    }

    // ── 2. 呼吸灯光晕 ────────────────────────────────────
    private void drawBreathing(Canvas canvas) {
        float breath = (float)(Math.sin(breathPhase) * 0.5 + 0.5);   // 0..1
        int   blendC = lerpColor(curColor, tgtColor, colorLerp);

        int   R = (blendC >> 16) & 0xFF;
        int   G = (blendC >> 8)  & 0xFF;
        int   B =  blendC        & 0xFF;

        // 主光晕：从中心向外扩散，随呼吸脉冲大小和亮度
        float baseR = vw * 0.42f;
        float pulseR= vw * 0.22f * breath;
        float radius = baseR + pulseR;

        float aCenter = 0.28f + 0.28f * breath;   // 中心 alpha
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

        // 二次柔光：顶部染色（颜色稍偏 tgt，增加层次感）
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

    // ── 3. 电路网格（极淡质感） ───────────────────────────
    private void drawGrid(Canvas canvas) {
        int blendC = lerpColor(curColor, tgtColor, colorLerp);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.6f);
        paint.setColor(blendC);
        paint.setAlpha(18);

        float gap = dp(32);
        for (float x = 0; x < vw; x += gap) canvas.drawLine(x, 0, x, vh, paint);
        for (float y = 0; y < vh; y += gap) canvas.drawLine(0, y, vw, y, paint);

        // 网格交叉节点小圆点
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(28);
        for (float x = 0; x < vw; x += gap) {
            for (float y = 0; y < vh; y += gap) {
                canvas.drawCircle(x, y, dp(1.5f), paint);
            }
        }
        paint.setAlpha(255);
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

        // ─── 静态底色条（全宽，极低亮度）
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

        // ─── 移动扫描亮斑
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

        // ─── 扫描亮斑的纵向辉光（向内扩散）
        float glowH = isTop ? dp(80) : -dp(80);
        paint.setMaskFilter(null);
        paint.setShader(new LinearGradient(
                0, barY, 0, barY + glowH,
                new int[]{ Color.argb(90, R, G, B), Color.TRANSPARENT },
                null, Shader.TileMode.CLAMP));
        float halfSpot = spotW * 0.35f;
        canvas.drawRect(scanX - halfSpot, barY,
                scanX + halfSpot, barY + glowH, paint);
        paint.setShader(null);

        // ─── 扫描位置发散星粒
        paint.setMaskFilter(new BlurMaskFilter(dp(6), BlurMaskFilter.Blur.NORMAL));
        paint.setColor(Color.WHITE);
        paint.setAlpha(200);
        canvas.drawCircle(scanX, barY + barH / 2f, dp(3), paint);
        paint.setMaskFilter(null);
        paint.setAlpha(255);
    }

    // ── 6. 漂浮粒子 ──────────────────────────────────────
    private void drawParticles(Canvas canvas) {
        for (int i = 0; i < PART_COUNT; i++) {
            int co = pCo[i];
            int R  = (co >> 16) & 0xFF;
            int G  = (co >> 8)  & 0xFF;
            int B  =  co        & 0xFF;

            // 柔光发散
            paint.setMaskFilter(new BlurMaskFilter(pSz[i] * 1.4f, BlurMaskFilter.Blur.NORMAL));
            paint.setColor(Color.argb((int)(pAl[i] * 180), R, G, B));
            canvas.drawCircle(px[i], py[i], pSz[i] * 0.6f, paint);

            // 亮芯
            paint.setMaskFilter(null);
            paint.setColor(Color.argb((int)(pAl[i] * 255), R, G, B));
            canvas.drawCircle(px[i], py[i], pSz[i] * 0.25f, paint);
        }
        paint.setAlpha(255);
    }

    // ── 7. 四角霓虹装饰框 ────────────────────────────────
    private void drawCorners(Canvas canvas) {
        float pulse  = (float)(Math.sin(cornerPhase) * 0.35 + 0.65);
        int   blendC = lerpColor(curColor, tgtColor, colorLerp);
        int   R      = (blendC >> 16) & 0xFF;
        int   G      = (blendC >> 8)  & 0xFF;
        int   B      =  blendC        & 0xFF;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setStrokeCap(Paint.Cap.SQUARE);

        // 双层：外层柔光 + 内层亮线
        for (int pass = 0; pass < 2; pass++) {
            boolean soft = (pass == 0);
            if (soft) {
                paint.setMaskFilter(new BlurMaskFilter(dp(5), BlurMaskFilter.Blur.NORMAL));
                paint.setColor(Color.argb((int)(pulse * 180), R, G, B));
                paint.setStrokeWidth(dp(4));
            } else {
                paint.setMaskFilter(null);
                paint.setColor(Color.argb((int)(pulse * 255), R, G, B));
                paint.setStrokeWidth(dp(2));
            }

            float m = dp(18);   // 边距
            float s = dp(28);   // 折线长度

            // 左上
            drawCornerBracket(canvas, m, m, s, 1, 1);
            // 右上
            drawCornerBracket(canvas, vw - m, m, s, -1, 1);
            // 左下
            drawCornerBracket(canvas, m, vh - m, s, 1, -1);
            // 右下
            drawCornerBracket(canvas, vw - m, vh - m, s, -1, -1);
        }

        // 在四角加一个亮点
        paint.setMaskFilter(new BlurMaskFilter(dp(8), BlurMaskFilter.Blur.NORMAL));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int)(pulse * 255), R, G, B));
        float m2 = dp(18);
        canvas.drawCircle(m2, m2,          dp(3), paint);
        canvas.drawCircle(vw - m2, m2,     dp(3), paint);
        canvas.drawCircle(m2, vh - m2,     dp(3), paint);
        canvas.drawCircle(vw - m2, vh - m2,dp(3), paint);
        paint.setMaskFilter(null);
        paint.setAlpha(255);
    }

    /**
     * 绘制单个角落 L 形装饰线
     * @param cx  角点 x
     * @param cy  角点 y
     * @param len 折线长度
     * @param dx  水平方向 (+1 向右, -1 向左)
     * @param dy  垂直方向 (+1 向下, -1 向上)
     */
    private void drawCornerBracket(Canvas canvas,
                                   float cx, float cy, float len,
                                   float dx, float dy) {
        // 横线
        canvas.drawLine(cx, cy, cx + dx * len, cy, paint);
        // 竖线
        canvas.drawLine(cx, cy, cx, cy + dy * len, paint);
    }

    // ─────────────────────────────────────────────────────
    //  工具
    // ─────────────────────────────────────────────────────

    /** 线性插值两个颜色（RGB 空间） */
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

