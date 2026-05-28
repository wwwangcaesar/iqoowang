package com.monsieurmahjong.iqoowang.view;


import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * 霓虹玻璃卡片 View
 *
 * 视觉层次（从后到前）：
 *  ① 深色玻璃背景（圆角矩形）
 *  ② 顶部玻璃高光（模拟折射）
 *  ③ 内部呼吸环境光（极淡径向渐变，随呼吸脉冲）
 *  ④ 子 View 内容
 *  ⑤ 静态渐变描边（极淡底色边框）
 *  ⑥ 跑马灯光点（PathMeasure 沿圆角矩形路径运动的彗星）
 *
 * 颜色与 CyberpunkBgView 通过 CyberpunkColorSync 实时同步。
 */
public class NeonCardView extends FrameLayout
        implements CyberpunkColorSync.ColorListener {

    // ── 动画参数 ─────────────────────────────────────────
    /** 呼吸相位，与 CyberpunkBgView 独立但周期相同 */
    private float breathPhase   = (float)(Math.random() * Math.PI * 2); // 随机初相，避免完全同步
    private final float BREATH_SPD   = 0.018f;   // ~5s / 周期

    /** 跑马灯位置 0..1 沿路径百分比 */
    private float runPos        = 0f;
    private final float RUN_SPD = 0.0038f;       // ~4.4s / 圈

    // ── 颜色状态 ─────────────────────────────────────────
    private int  liveColor = CyberpunkColorSync.getCurrentColor();

    // ── 圆角半径（dp → px） ──────────────────────────────
    private float cornerR;

    // ── 绘制缓存 ─────────────────────────────────────────
    private final Paint       paint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF       bounds      = new RectF();
    private       Path        borderPath  = new Path();
    private       PathMeasure pm;
    private       float       pathLen     = 0f;
    private final float[]     pmPos       = new float[2];

    // ── 动画循环 ─────────────────────────────────────────
    private final Handler  handler = new Handler(Looper.getMainLooper());
    private       Runnable loop;
    private       boolean  running = false;

    // ── 构造 ─────────────────────────────────────────────
    public NeonCardView(Context c)                        { super(c); init(); }
    public NeonCardView(Context c, AttributeSet a)        { super(c, a); init(); }
    public NeonCardView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        setWillNotDraw(false);              // 让 onDraw 生效
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        cornerR = dp(20);
    }

    // ── 生命周期 ─────────────────────────────────────────
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        CyberpunkColorSync.addListener(this);
        startAnim();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        CyberpunkColorSync.removeListener(this);
        stopAnim();
    }

    @Override
    public void onColorChanged(int color) {
        liveColor = color;
        // 不在此处 invalidate，动画循环会自动刷新
    }

    // ── 动画 ─────────────────────────────────────────────
    private void startAnim() {
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

    private void stopAnim() {
        running = false;
        handler.removeCallbacks(loop);
    }

    private void tick() {
        breathPhase += BREATH_SPD;
        if (breathPhase > (float)(Math.PI * 2)) breathPhase -= (float)(Math.PI * 2);
        runPos += RUN_SPD;
        if (runPos >= 1f) runPos -= 1f;
    }

    // ── 尺寸变化时重建路径 ────────────────────────────────
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        bounds.set(0, 0, w, h);
        rebuildPath();
    }

    private void rebuildPath() {
        borderPath = new Path();
        borderPath.addRoundRect(bounds, cornerR, cornerR, Path.Direction.CW);
        pm      = new PathMeasure(borderPath, false);
        pathLen = pm.getLength();
    }

    // ─────────────────────────────────────────────────────
    //  onDraw：背景 + 环境光（在子 View 之前）
    // ─────────────────────────────────────────────────────
    @Override
    protected void onDraw(Canvas canvas) {
        if (bounds.isEmpty()) return;
        drawGlassBackground(canvas);
        drawBreathingGlow   (canvas);
    }

    // ─────────────────────────────────────────────────────
    //  dispatchDraw：先绘制子 View，再绘制边框特效（覆盖在上层）
    // ─────────────────────────────────────────────────────
    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (pathLen > 0) {
            drawStaticBorder  (canvas);
            drawRunningLight  (canvas);
        }
    }

    // ─────────────────────────────────────────────────────
    //  ① 深色玻璃背景
    // ─────────────────────────────────────────────────────
    private void drawGlassBackground(Canvas canvas) {
        // 主体：深色玻璃
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xEE0A0820);
        paint.setShader(null);
        paint.setMaskFilter(null);
        canvas.drawRoundRect(bounds, cornerR, cornerR, paint);

        // 顶部玻璃高光：上方约 40% 区域覆盖一层极淡白色渐变
        float midY = bounds.height() * 0.38f;
        paint.setShader(new LinearGradient(
                0, 0, 0, midY,
                new int[]{ 0x14FFFFFF, 0x00FFFFFF },
                null,
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(
                new RectF(bounds.left, bounds.top,
                        bounds.right, bounds.top + midY),
                cornerR, cornerR, paint);
        paint.setShader(null);
    }

    // ─────────────────────────────────────────────────────
    //  ② 内部呼吸环境光（极淡，衬托整体布局的氛围感）
    // ─────────────────────────────────────────────────────
    private void drawBreathingGlow(Canvas canvas) {
        // 呼吸曲线：sin 0..1，alpha 极低，仅做氛围
        float breath = (float)(Math.sin(breathPhase) * 0.5 + 0.5);   // 0..1

        int R = (liveColor >> 16) & 0xFF;
        int G = (liveColor >> 8)  & 0xFF;
        int B =  liveColor        & 0xFF;

        // 径向渐变：从卡片中心向外辐射，控制 alpha 使其非常克制
        float aCenter = 0.15f + 0.09f * breath;   // 最亮 0.15，最暗 0.06
        float radius  = Math.max(bounds.width(), bounds.height()) * 0.75f;

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(
                bounds.centerX(), bounds.centerY(),
                radius,
                new int[]{
                        Color.argb((int)(aCenter * 255), R, G, B),
                        Color.argb((int)(aCenter * 0.3f * 255), R, G, B),
                        Color.argb(0, R, G, B)
                },
                new float[]{ 0f, 0.55f, 1f },
                Shader.TileMode.CLAMP));

        // 限制在卡片圆角矩形内绘制，不溢出
        canvas.save();
        canvas.clipPath(borderPath);
        canvas.drawRoundRect(bounds, cornerR, cornerR, paint);
        canvas.restore();
        paint.setShader(null);
    }

    // ─────────────────────────────────────────────────────
    //  ③ 静态渐变描边（底色边框，极淡）
    // ─────────────────────────────────────────────────────
    private void drawStaticBorder(Canvas canvas) {
        int R = (liveColor >> 16) & 0xFF;
        int G = (liveColor >> 8)  & 0xFF;
        int B =  liveColor        & 0xFF;

        float breath = (float)(Math.sin(breathPhase) * 0.5 + 0.5);
        int borderAlpha = (int)((0.15f + 0.20f * breath) * 255);   // 随呼吸微亮

        // 扫描渐变描边（SweepGradient 让边框颜色从 liveColor 过渡到互补色）
        int compColor = complementCyberpunk(liveColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setShader(new SweepGradient(
                bounds.centerX(), bounds.centerY(),
                new int[]{ liveColor, compColor, liveColor },
                new float[]{ 0f, 0.5f, 1f }));
        paint.setAlpha(borderAlpha);
        paint.setMaskFilter(null);
        canvas.drawPath(borderPath, paint);
        paint.setShader(null);
        paint.setAlpha(255);
    }

    // ─────────────────────────────────────────────────────
    //  ④ 跑马灯彗星（沿 borderPath 行进）
    // ─────────────────────────────────────────────────────
    private void drawRunningLight(Canvas canvas) {
        int R = (liveColor >> 16) & 0xFF;
        int G = (liveColor >> 8)  & 0xFF;
        int B =  liveColor        & 0xFF;

        // ── 彗星尾迹（由远到近，逐渐变亮变大）──────────
        int tailSteps = 28;
        float tailSpan = 0.1f;   // 彗星尾占路径的比例

        for (int i = tailSteps; i >= 1; i--) {
            float t = runPos - (tailSpan * i / tailSteps);
            if (t < 0) t += 1f;
            pm.getPosTan(t * pathLen, pmPos, null);

            float progress = 1f - (float) i / tailSteps;   // 0(尾)→1(头)
            float sz       = dp(1.0f) + dp(3.0f) * progress * progress;
            int   alpha    = (int)(progress * progress * 160);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(alpha, R, G, B));
            paint.setMaskFilter(new BlurMaskFilter(sz * 1.8f, BlurMaskFilter.Blur.NORMAL));
            canvas.drawCircle(pmPos[0], pmPos[1], sz * 0.5f, paint);
        }

        // ── 彗星头部：亮白芯 + 颜色光晕 ────────────────
        pm.getPosTan(runPos * pathLen, pmPos, null);

        // 颜色光晕
        paint.setColor(Color.argb(220, R, G, B));
        paint.setMaskFilter(new BlurMaskFilter(dp(8), BlurMaskFilter.Blur.NORMAL));
        canvas.drawCircle(pmPos[0], pmPos[1], dp(4), paint);

        // 白芯
        paint.setColor(Color.WHITE);
        paint.setMaskFilter(new BlurMaskFilter(dp(3), BlurMaskFilter.Blur.NORMAL));
        canvas.drawCircle(pmPos[0], pmPos[1], dp(2.5f), paint);

        // 极亮白点（最中心）
        paint.setMaskFilter(null);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(pmPos[0], pmPos[1], dp(1.2f), paint);

        paint.setAlpha(255);
    }

    // ─────────────────────────────────────────────────────
    //  工具
    // ─────────────────────────────────────────────────────

    /** 取颜色的赛博朋克互补色（用于渐变描边更丰富） */
    private static int complementCyberpunk(int color) {
        // 简单地将 RGB 互换两个通道，产生互补感
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8)  & 0xFF;
        int b =  color        & 0xFF;
        // 偏移 180° hue（近似）
        return Color.rgb(b, r, g);
    }

    private float dp(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
