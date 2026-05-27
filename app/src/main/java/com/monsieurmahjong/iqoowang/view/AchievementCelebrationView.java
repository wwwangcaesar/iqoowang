package com.monsieurmahjong.iqoowang.view;


import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 成就庆祝动画视图
 *
 * 已解锁（UNLOCKED）：
 *   - 成就图标居中，周围金色闪光星粒
 *   - 下方冉冉燃烧的红色→橙→黄火焰粒子
 *   - 图标+文字上方金色光晕
 *
 * 未解锁（LOCKED）：
 *   - 图标朦胧遮挡（灰度+紫色半透明蒙版+锁形描绘）
 *   - 紫色火焰代替红色
 *   - 紫色星粒代替金色
 */
public class AchievementCelebrationView extends View {

    public enum Mode { UNLOCKED, LOCKED }

    // ── 配置 ─────────────────────────────────────────────
    private Mode mode = Mode.UNLOCKED;
    private int iconResId = -1;
    private String achievementName = "";
    private String achievementDesc = "";

    // ── 绘制工具 ─────────────────────────────────────────
    private final Paint paint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── 图标 Bitmap ──────────────────────────────────────
    private Bitmap iconBm;        // 原始图标
    private Bitmap blurIconBm;    // 模糊化图标（locked 用）

    // ── 粒子 ─────────────────────────────────────────────
    private final List<FlameParticle>  flames   = new ArrayList<>();
    private final List<SparkleParticle> sparkles = new ArrayList<>();

    private static final Random RND = new Random();

    // ── 火焰颜色表 ───────────────────────────────────────
    private static final int[] RED_COLORS = {
            0xFFFF1100, 0xFFFF4500, 0xFFFF6600, 0xFFFF8C00, 0xFFFFD700
    };
    private static final int[] PURPLE_COLORS = {
            0xFF5A0080, 0xFF8800CC, 0xFFAA00FF, 0xFFCC66FF, 0xFFEEB0FF
    };
    private static final int[] GOLD_SPARKLE = {
            0xFFFFD700, 0xFFFFE055, 0xFFFFF0A0, 0xFFFFAA00
    };
    private static final int[] PURPLE_SPARKLE = {
            0xFFCC00FF, 0xFFAA44FF, 0xFFDD99FF, 0xFF8800CC
    };

    // ── 布局尺寸（像素） ─────────────────────────────────
    private float cx, cy;            // view 中心
    private float iconR;             // 图标圆半径
    private float iconTop;           // 图标顶部 y
    private float iconBottom;        // 图标底部 y
    private float textBaseY;         // 标题基线 y
    private float descBaseY;         // 描述基线 y
    private float flameOriginY;      // 火焰发射源 y
    private float viewW, viewH;

    // ── 动画循环 ─────────────────────────────────────────
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable animLoop;
    private long startMs;
    private boolean running;

    // 金色光晕脉冲
    private float haloPhase = 0f;
    // 图标摇摆
    private float swingPhase = 0f;

    // ─────────────────────────────────────────────────────
    //  构造 & 配置
    // ─────────────────────────────────────────────────────

    public AchievementCelebrationView(Context c) { super(c); init(); }
    public AchievementCelebrationView(Context c, AttributeSet a) { super(c, a); init(); }
    public AchievementCelebrationView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);  // 支持 MaskFilter 等软件渲染
    }

    /** 配置：已解锁成就 */
    public void setupUnlocked(int iconRes, String name, String desc) {
        this.iconResId        = iconRes;
        this.achievementName  = name;
        this.achievementDesc  = desc;
        this.mode             = Mode.UNLOCKED;
        loadIcons();
    }

    /** 配置：未解锁成就 */
    public void setupLocked(int iconRes, String name, String desc) {
        this.iconResId        = iconRes;
        this.achievementName  = name;
        this.achievementDesc  = desc;
        this.mode             = Mode.LOCKED;
        loadIcons();
    }

    private void loadIcons() {
        if (iconResId == -1) return;
        try {
            Bitmap raw = BitmapFactory.decodeResource(getResources(), iconResId);
            int  sz  = dp(96);
            iconBm   = Bitmap.createScaledBitmap(raw, sz, sz, true);

            if (mode == Mode.LOCKED) {
                blurIconBm = makeBlurredBitmap(iconBm);
            }
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────
    //  测量 & 布局
    // ─────────────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        viewW  = w;  viewH  = h;
        cx     = w / 2f;
        iconR  = dp(52);

        // 整体内容垂直居中：图标 + 标题 + 描述 + 火焰区
        float contentH = iconR * 2 + dp(28) + dp(20) + dp(110);
        float top      = (h - contentH) / 2f;

        iconTop     = top;
        iconBottom  = top + iconR * 2;
        cy          = iconTop + iconR;          // 图标圆心 y

        textBaseY   = iconBottom + dp(24);
        descBaseY   = textBaseY  + dp(22);
        flameOriginY= descBaseY  + dp(18);

        initSparkles();
        initFlames();
    }

    // ─────────────────────────────────────────────────────
    //  粒子初始化
    // ─────────────────────────────────────────────────────

    private void initSparkles() {
        sparkles.clear();
        int count = 14;
        for (int i = 0; i < count; i++) {
            SparkleParticle sp = new SparkleParticle();
            sp.angle       = (float) (i * Math.PI * 2 / count);
            sp.orbitRadius = iconR + dp(18) + RND.nextFloat() * dp(20);
            sp.speed       = (RND.nextFloat() * 0.015f + 0.008f)
                    * (RND.nextBoolean() ? 1 : -1);
            sp.size        = dp(5) + RND.nextFloat() * dp(6);
            sp.phase       = RND.nextFloat() * (float) Math.PI * 2;
            sp.colorIdx    = RND.nextInt(4);
            sparkles.add(sp);
        }
    }

    private void initFlames() {
        flames.clear();
        // 预填 60 粒子，随机生命进度防止开头突然喷发
        for (int i = 0; i < 60; i++) {
            FlameParticle p = new FlameParticle();
            p.reset(cx, flameOriginY, viewW * 0.55f, RND);
            p.life = RND.nextFloat();   // 随机初始生命，避免同步喷发
            flames.add(p);
        }
    }

    // ─────────────────────────────────────────────────────
    //  动画启停
    // ─────────────────────────────────────────────────────

    public void startAnim() {
        if (running) return;
        running  = true;
        startMs  = System.currentTimeMillis();
        animLoop = new Runnable() {
            @Override public void run() {
                if (!running) return;
                tick();
                invalidate();
                handler.postDelayed(this, 16);   // ~60 fps
            }
        };
        handler.post(animLoop);
    }

    public void stopAnim() {
        running = false;
        handler.removeCallbacks(animLoop);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnim();
    }

    // ─────────────────────────────────────────────────────
    //  每帧更新
    // ─────────────────────────────────────────────────────

    private void tick() {
        haloPhase  += 0.06f;
        swingPhase += 0.04f;

        // 更新火焰粒子
        int[] flameCols = mode == Mode.LOCKED ? PURPLE_COLORS : RED_COLORS;
        for (FlameParticle p : flames) {
            p.life += p.lifeSpeed;
            if (p.life >= 1f) {
                p.reset(cx + (RND.nextFloat() - 0.5f) * dp(20),
                        flameOriginY,
                        viewW * 0.55f, RND);
                continue;
            }
            p.x  += p.vx + (float) Math.sin(p.life * 8 + p.x * 0.02f) * 1.2f;
            p.y  += p.vy;
            p.vy *= 0.985f;    // 略微减速
            // 颜色索引随年龄从深到浅
            p.colorIdx = (int) (p.life * (flameCols.length - 1));
        }

        // 更新星粒
        for (SparkleParticle sp : sparkles) {
            sp.angle += sp.speed;
            sp.phase += 0.07f;
        }
    }

    // ─────────────────────────────────────────────────────
    //  绘制
    // ─────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        if (viewW == 0) return;

        // 1. 背景渐变
        drawBackground(canvas);

        // 2. 图标光晕
        drawHalo(canvas);

        // 3. 火焰（在图标下方，先画以免遮盖图标）
        drawFlames(canvas);

        // 4. 图标
        drawIcon(canvas);

        // 5. 星粒（在图标之上）
        drawSparkles(canvas);

        // 6. 文字
        drawText(canvas);
    }

    // ── 背景 ─────────────────────────────────────────────

    private void drawBackground(Canvas canvas) {
        String topColor   = mode == Mode.LOCKED ? "#1A0030" : "#1A0A00";
        String midColor   = mode == Mode.LOCKED ? "#0D001A" : "#0D0500";
        paint.setShader(new LinearGradient(
                cx, 0, cx, viewH,
                new int[]{Color.parseColor(topColor),
                        Color.parseColor(midColor), Color.BLACK},
                new float[]{0f, 0.6f, 1f},
                Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, viewW, viewH, paint);
        paint.setShader(null);
    }

    // ── 光晕 ─────────────────────────────────────────────

    private void drawHalo(Canvas canvas) {
        float pulse  = (float) (Math.sin(haloPhase) * 0.25f + 0.75f);
        float r      = iconR * 1.8f * pulse;
        int   center = mode == Mode.LOCKED ? 0xFF6600BB : 0xFFFFAA00;
        paint.setShader(new RadialGradient(
                cx, cy, r,
                new int[]{center, 0x00000000},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setShader(null);
    }

    // ── 火焰 ─────────────────────────────────────────────

    private void drawFlames(Canvas canvas) {
        int[] cols = mode == Mode.LOCKED ? PURPLE_COLORS : RED_COLORS;
        for (FlameParticle p : flames) {
            if (p.life <= 0 || p.life >= 1f) continue;

            float alphaNorm = 1f - p.life;             // 粒子越老越透明
            float sz        = p.size * (1f - p.life * 0.6f);
            int   baseColor = cols[Math.min(p.colorIdx, cols.length - 1)];
            int   alpha     = (int) (alphaNorm * 210);

            paint.setColor(baseColor);
            paint.setAlpha(alpha);
            paint.setStyle(Paint.Style.FILL);
            paint.setMaskFilter(new android.graphics.BlurMaskFilter(
                    sz * 0.7f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            canvas.drawCircle(p.x, p.y, sz * 0.5f, paint);
        }
        paint.setMaskFilter(null);
        paint.setAlpha(255);
    }

    // ── 图标 ─────────────────────────────────────────────

    private void drawIcon(Canvas canvas) {
        // 图标圆形背景
        float swing = (float) Math.sin(swingPhase) * dp(3);

        // 背景圆
        paint.setStyle(Paint.Style.FILL);
        if (mode == Mode.UNLOCKED) {
            paint.setShader(new RadialGradient(
                    cx, cy + swing, iconR,
                    new int[]{0xFFFFE066, 0xFFCC8800, 0xFF664400},
                    new float[]{0f, 0.6f, 1f},
                    Shader.TileMode.CLAMP));
        } else {
            paint.setShader(new RadialGradient(
                    cx, cy + swing, iconR,
                    new int[]{0xFF6600AA, 0xFF330066, 0xFF110022},
                    new float[]{0f, 0.6f, 1f},
                    Shader.TileMode.CLAMP));
        }
        canvas.drawCircle(cx, cy + swing, iconR, paint);
        paint.setShader(null);

        // 图标 Bitmap
        if (mode == Mode.UNLOCKED && iconBm != null) {
            float left = cx - iconBm.getWidth() / 2f;
            float top2 = cy + swing - iconBm.getHeight() / 2f;
            paint.setAlpha(255);
            paint.setColorFilter(null);
            canvas.drawBitmap(iconBm, left, top2, paint);

        } else if (mode == Mode.LOCKED && iconBm != null) {
            drawLockedIcon(canvas, swing);
        }
    }

    /** 朦胧遮挡：灰度图标 + 紫色蒙版 + 锁型 */
    private void drawLockedIcon(Canvas canvas, float swing) {
        Bitmap bm = blurIconBm != null ? blurIconBm : iconBm;
        float left = cx - bm.getWidth() / 2f;
        float top2 = cy + swing - bm.getHeight() / 2f;

        // 灰度 + 低透明度图标
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        paint.setAlpha(80);
        canvas.drawBitmap(bm, left, top2, paint);
        paint.setAlpha(255);
        paint.setColorFilter(null);

        // 紫色半透明蒙版圆
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99440088);
        canvas.drawCircle(cx, cy + swing, iconR * 0.85f, paint);

        // 绘制锁形状
        drawLockShape(canvas, cx, cy + swing);
    }

    /** 以 Path 绘制一个简洁的锁形 */
    private void drawLockShape(Canvas canvas, float x, float y) {
        paint.setColor(0xFFDDB0FF);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(220);

        float sw = dp(22), sh = dp(17);
        float bw = dp(30), bh = dp(24);
        float arcR = sw / 2f;

        // 锁身（圆角矩形）
        RectF body = new RectF(x - bw/2, y - sh/2 + dp(4), x + bw/2, y + bh/2 + dp(4));
        canvas.drawRoundRect(body, dp(5), dp(5), paint);

        // 锁环（上半圆弧）
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(5));
        paint.setColor(0xFFDDB0FF);
        RectF arc = new RectF(x - arcR, y - dp(14), x + arcR, y + dp(2));
        canvas.drawArc(arc, 180, 180, false, paint);
        paint.setStyle(Paint.Style.FILL);

        // 钥匙孔
        paint.setColor(0xFF330055);
        paint.setAlpha(255);
        canvas.drawCircle(x, y + dp(8), dp(4), paint);

        paint.setAlpha(255);
    }

    // ── 星粒 ─────────────────────────────────────────────

    private void drawSparkles(Canvas canvas) {
        int[] cols = mode == Mode.LOCKED ? PURPLE_SPARKLE : GOLD_SPARKLE;
        for (SparkleParticle sp : sparkles) {
            float pulse = (float) (Math.sin(sp.phase) * 0.4f + 0.6f);
            float sx    = cx + (float) Math.cos(sp.angle) * sp.orbitRadius;
            float sy    = cy + (float) Math.sin(sp.angle) * sp.orbitRadius * 0.55f;
            float sz    = sp.size * pulse;
            int   alpha = (int) (200 * pulse);

            paint.setColor(cols[sp.colorIdx]);
            paint.setAlpha(alpha);
            paint.setMaskFilter(new android.graphics.BlurMaskFilter(
                    sz * 0.6f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            drawStar(canvas, sx, sy, sz, sz * 0.38f, 4);
        }
        paint.setMaskFilter(null);
        paint.setAlpha(255);
    }

    /** 绘制 n 角星 */
    private void drawStar(Canvas canvas, float cx, float cy,
                          float outerR, float innerR, int n) {
        Path path = new Path();
        double step = Math.PI / n;
        for (int i = 0; i < n * 2; i++) {
            double a = i * step - Math.PI / 2;
            float r = (i % 2 == 0) ? outerR : innerR;
            float x = cx + (float) (Math.cos(a) * r);
            float y = cy + (float) (Math.sin(a) * r);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
    }

    // ── 文字 ─────────────────────────────────────────────

    private void drawText(Canvas canvas) {
        // 标题
        tPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tPaint.setTextSize(dp(18));
        tPaint.setTextAlign(Paint.Align.CENTER);
        tPaint.setColor(mode == Mode.UNLOCKED ? 0xFFFFE066 : 0xFFCC99FF);
        tPaint.setAlpha(255);
        canvas.drawText(achievementName.isEmpty() ? "成就" : achievementName,
                cx, textBaseY, tPaint);

        // 描述
        tPaint.setTypeface(android.graphics.Typeface.DEFAULT);
        tPaint.setTextSize(dp(12));
        tPaint.setColor(mode == Mode.UNLOCKED ? 0xFFFFD080 : 0xFFAA88CC);
        String desc = achievementDesc.isEmpty() ? "" : achievementDesc;
        // 超长时截断
        if (tPaint.measureText(desc) > viewW * 0.85f) {
            while (tPaint.measureText(desc + "…") > viewW * 0.85f && desc.length() > 0)
                desc = desc.substring(0, desc.length() - 1);
            desc += "…";
        }
        canvas.drawText(desc, cx, descBaseY, tPaint);

        // 状态徽章
        tPaint.setTextSize(dp(11));
        tPaint.setColor(mode == Mode.UNLOCKED ? 0xFF00FF99 : 0xFFAA55FF);
        canvas.drawText(mode == Mode.UNLOCKED ? "✦ 已解锁 ✦" : "✦ 未解锁 ✦",
                cx, descBaseY + dp(22), tPaint);
    }

    // ─────────────────────────────────────────────────────
    //  工具
    // ─────────────────────────────────────────────────────

    /** 模拟模糊：多次偏移绘制 */
    private Bitmap makeBlurredBitmap(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c   = new Canvas(dst);
        Paint  p   = new Paint(Paint.ANTI_ALIAS_FLAG);
        int    r   = 3;
        p.setAlpha(30);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                c.drawBitmap(src, dx * 2, dy * 2, p);
            }
        }
        return dst;
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // ─────────────────────────────────────────────────────
    //  内部粒子类
    // ─────────────────────────────────────────────────────

    private static class FlameParticle {
        float x, y, vx, vy, size, life, lifeSpeed;
        int   colorIdx;

        void reset(float bx, float by, float spread, Random rnd) {
            x         = bx + (rnd.nextFloat() - 0.5f) * spread;
            y         = by + rnd.nextFloat() * 20f;
            vx        = (rnd.nextFloat() - 0.5f) * 2.5f;
            vy        = -(rnd.nextFloat() * 7f + 4f);
            size      = rnd.nextFloat() * 35f + 18f;
            life      = 0f;
            lifeSpeed = rnd.nextFloat() * 0.018f + 0.007f;
            colorIdx  = 0;
        }
    }

    private static class SparkleParticle {
        float angle, orbitRadius, speed, size, phase;
        int   colorIdx;
    }
}

