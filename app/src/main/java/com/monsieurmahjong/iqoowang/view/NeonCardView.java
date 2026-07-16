package com.monsieurmahjong.iqoowang.view;


import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
 *
 * ── 性能重写说明 ──
 * 这个 View 是 QuickLogActivity 里包着金额输入框 EditText 的容器（FrameLayout），
 * 原实现的 drawRunningLight() 彗星尾迹每帧要为 28 个尾迹点各创建一个新
 * BlurMaskFilter（每帧 28 次分配），加上头部/白芯又是 2 次，
 * 再叠加 setLayerType(LAYER_TYPE_SOFTWARE) 强制软件光栅化——
 * 这是导致"跑马灯很卡、打字也卡"最主要的原因（和 EditText 同一棵 View 树，
 * 抢占同一条主线程）。
 * 现在改用一张预渲染柔光贴图 + ColorFilter 染色 + 缩放代替实时模糊，
 * 尾迹步数也从 28 降到 14（视觉差异极小，开销减半），
 * 动画帧率从 16ms(~60fps) 降到 33ms(~30fps) 并用真实帧间隔驱动速度，
 * 不再假设固定帧长。
 */
public class NeonCardView extends FrameLayout
        implements CyberpunkColorSync.ColorListener {

    private static final float BASE_FRAME_MS = 16f;
    private static final long  TICK_INTERVAL_MS = 33L;

    // ── 动画参数 ─────────────────────────────────────────
    private float breathPhase   = (float)(Math.random() * Math.PI * 2);
    private final float BREATH_SPD   = 0.018f;

    private float runPos        = 0f;
    private final float RUN_SPD = 0.0038f;

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

    // ── 预渲染柔光贴图（取代逐帧 BlurMaskFilter） ──────────
    private Bitmap glowBitmap;
    private static final int GLOW_BMP_SIZE = 64;
    private final Rect  glowSrc = new Rect(0, 0, GLOW_BMP_SIZE, GLOW_BMP_SIZE);
    private final RectF glowDst = new RectF();

    // ── 彗星尾迹步数：28 → 14，配合贴图方案视觉差异很小，开销减半 ──
    private static final int TAIL_STEPS = 14;
    private static final float TAIL_SPAN = 0.1f;

    // ── 动画循环 ─────────────────────────────────────────
    private final Handler  handler = new Handler(Looper.getMainLooper());
    private       Runnable loop;
    private       boolean  running = false;
    private       long     lastTickTime = 0L;

    // ── 构造 ─────────────────────────────────────────────
    public NeonCardView(Context c)                        { super(c); init(); }
    public NeonCardView(Context c, AttributeSet a)        { super(c, a); init(); }
    public NeonCardView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        setWillNotDraw(false);
        // 不再强制软件层：柔光效果已用预渲染贴图实现，硬件加速可以正常绘制，
        // 这一项本身就是导致输入框跟着卡顿的主要原因之一
        cornerR = dp(20);
        glowBitmap = createGlowBitmap(GLOW_BMP_SIZE);
    }

    /** 一次性生成白色柔光圆点贴图，后续用 ColorFilter 染成任意颜色复用 */
    private static Bitmap createGlowBitmap(int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setMaskFilter(new BlurMaskFilter(size * 0.26f, BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(size / 2f, size / 2f, size * 0.22f, p);
        return bmp;
    }

    /** 用柔光贴图画一个发光点，取代 BlurMaskFilter 实时模糊 */
    private void drawGlowDot(Canvas canvas, float cx, float cy, float radius, int color, int alpha) {
        float half = radius * 2.6f;
        glowDst.set(cx - half, cy - half, cx + half, cy + half);
        paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        paint.setAlpha(Math.max(0, Math.min(255, alpha)));
        canvas.drawBitmap(glowBitmap, glowSrc, glowDst, paint);
        paint.setColorFilter(null);
        paint.setAlpha(255);
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
        lastTickTime = SystemClock.uptimeMillis();
        loop = new Runnable() {
            @Override public void run() {
                if (!running) return;
                long now = SystemClock.uptimeMillis();
                float dt = (now - lastTickTime) / BASE_FRAME_MS;
                lastTickTime = now;
                if (dt > 6f) dt = 1f; // 防止切前后台造成的大跳变
                tick(dt);
                invalidate();
                handler.postDelayed(this, TICK_INTERVAL_MS);
            }
        };
        handler.post(loop);
    }

    private void stopAnim() {
        running = false;
        handler.removeCallbacks(loop);
    }

    private void tick(float dt) {
        breathPhase += BREATH_SPD * dt;
        if (breathPhase > (float)(Math.PI * 2)) breathPhase -= (float)(Math.PI * 2);
        runPos += RUN_SPD * dt;
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
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xEE0A0820);
        paint.setShader(null);
        paint.setMaskFilter(null);
        canvas.drawRoundRect(bounds, cornerR, cornerR, paint);

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
    //  ② 内部呼吸环境光
    // ─────────────────────────────────────────────────────
    private void drawBreathingGlow(Canvas canvas) {
        float breath = (float)(Math.sin(breathPhase) * 0.5 + 0.5);

        int R = (liveColor >> 16) & 0xFF;
        int G = (liveColor >> 8)  & 0xFF;
        int B =  liveColor        & 0xFF;

        float aCenter = 0.15f + 0.09f * breath;
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
        float breath = (float)(Math.sin(breathPhase) * 0.5 + 0.5);
        int borderAlpha = (int)((0.15f + 0.20f * breath) * 255);

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
    //  ④ 跑马灯彗星（沿 borderPath 行进，改用预渲染柔光贴图）
    // ─────────────────────────────────────────────────────
    private void drawRunningLight(Canvas canvas) {
        int R = (liveColor >> 16) & 0xFF;
        int G = (liveColor >> 8)  & 0xFF;
        int B =  liveColor        & 0xFF;
        int liveRgb = Color.rgb(R, G, B);

        // ── 彗星尾迹（由远到近，逐渐变亮变大）──────────
        for (int i = TAIL_STEPS; i >= 1; i--) {
            float t = runPos - (TAIL_SPAN * i / TAIL_STEPS);
            if (t < 0) t += 1f;
            pm.getPosTan(t * pathLen, pmPos, null);

            float progress = 1f - (float) i / TAIL_STEPS;
            float sz       = dp(1.0f) + dp(3.0f) * progress * progress;
            int   alpha    = (int)(progress * progress * 160);

            drawGlowDot(canvas, pmPos[0], pmPos[1], sz, liveRgb, alpha);
        }

        // ── 彗星头部：颜色光晕 + 白芯 + 极亮白点 ────────
        pm.getPosTan(runPos * pathLen, pmPos, null);

        drawGlowDot(canvas, pmPos[0], pmPos[1], dp(4), liveRgb, 220);
        drawGlowDot(canvas, pmPos[0], pmPos[1], dp(2.5f), Color.WHITE, 230);

        paint.setColorFilter(null);
        paint.setMaskFilter(null);
        paint.setColor(Color.WHITE);
        paint.setAlpha(255);
        canvas.drawCircle(pmPos[0], pmPos[1], dp(1.2f), paint);
    }

    // ─────────────────────────────────────────────────────
    //  工具
    // ─────────────────────────────────────────────────────

    private static int complementCyberpunk(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8)  & 0xFF;
        int b =  color        & 0xFF;
        return Color.rgb(b, r, g);
    }

    private float dp(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
