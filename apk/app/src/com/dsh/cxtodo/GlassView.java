package com.dsh.cxtodo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.graphics.Outline;
import android.widget.FrameLayout;

/**
 * 液态玻璃容器。
 *
 * 原理：Activity 启动时把极光背景画进一张 1/4 尺寸的 Bitmap 并做两趟 box blur，
 * 静态缓存在这里。每个 GlassView 绘制时按自己在窗口里的位置从这张模糊图上裁一块
 * 贴到自己的圆角范围内，再叠半透明着色、对角高光、1px 亮边。
 *
 * 因为背景固定在窗口、不随 ScrollView 滚动，玻璃滑动时贴图内容跟着位移，
 * 天然得到「玻璃滑过背景」的视差，且运行时零模糊开销。
 */
public class GlassView extends FrameLayout {

    private static Bitmap sBackdrop;
    private static float sScale = 0.25f;
    private static int sOriginX;
    private static int sOriginY;

    public static void setBackdrop(Bitmap bmp, float scale) {
        setBackdrop(bmp, scale, 0, 0);
    }

    public static void setBackdrop(Bitmap bmp, float scale, int originX, int originY) {
        sBackdrop = bmp;
        sScale = scale;
        sOriginX = originX;
        sOriginY = originY;
    }

    private final Paint glassPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint tintPaint = new Paint();
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint depthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF rect = new RectF();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private final int[] loc = new int[2];
    private float radiusPx;
    private float[] radii;
    private int edgeColor = 0x66FFFFFF;
    private float sheenT;
    private final Matrix sheenMatrix = new Matrix();
    private android.animation.ValueAnimator sheenAnim;

    public GlassView(Context c) {
        super(c);
        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);
        tintPaint.setColor(0x80FFFFFF);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(dp(1.1f));
        edgePaint.setColor(edgeColor);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public GlassView radius(float dp) {
        radiusPx = dp(dp);
        radii = null;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
            }
        });
        return this;
    }

    /** 只给底部两角圆角（顶部与状态栏接缝）。 */
    public GlassView radiusBottom(float dp) {
        radiusPx = 0f;
        float r = dp(dp);
        radii = new float[]{0, 0, 0, 0, r, r, r, r};
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 0);
            }
        });
        return this;
    }

    public GlassView tint(int color) {
        tintPaint.setColor(color);
        return this;
    }

    public GlassView edge(int color) {
        edgeColor = color;
        edgePaint.setColor(color);
        return this;
    }

    public GlassView highlight(int alpha) {
        highlightPaint.setAlpha(alpha);
        return this;
    }

    /** 让高光缓慢扫过，制造「液态」流动感。 */
    public GlassView sheen() {
        if (sheenAnim != null) {
            return this;
        }
        sheenAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        sheenAnim.setDuration(4600);
        sheenAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        sheenAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        sheenAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        sheenAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                sheenT = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        sheenAnim.start();
        return this;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (sheenAnim != null) {
            sheenAnim.cancel();
            sheenAnim = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        highlightPaint.setShader(new LinearGradient(
                0, 0, w * 0.75f, h,
                new int[]{0x66FFFFFF, 0x1AFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        depthPaint.setShader(new LinearGradient(0, h * 0.55f, 0, h,
                new int[]{0x00000000, 0x0A000000},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        rect.set(0, 0, w, h);
        clipPath.reset();
        if (radii != null) {
            clipPath.addRoundRect(rect, radii, Path.Direction.CW);
        } else {
            clipPath.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW);
        }

        int save = canvas.save();
        canvas.clipPath(clipPath);

        // 1) 背后内容的模糊采样
        boolean painted = false;
        if (sBackdrop != null && !sBackdrop.isRecycled()) {
            getLocationInWindow(loc);
            int sx = Math.round((loc[0] - sOriginX) * sScale);
            int sy = Math.round((loc[1] - sOriginY) * sScale);
            int sw = Math.max(1, Math.round(w * sScale));
            int sh = Math.max(1, Math.round(h * sScale));
            src.set(sx, sy, Math.min(sx + sw, sBackdrop.getWidth()),
                    Math.min(sy + sh, sBackdrop.getHeight()));
            if (src.width() > 0 && src.height() > 0) {
                dst.set(0, 0, w, h);
                canvas.drawBitmap(sBackdrop, src, dst, glassPaint);
                painted = true;
            }
        }
        // 模糊背景缺失或采样越界时铺一层近似底色，绝不透出深色底
        if (!painted) {
            canvas.drawColor(0xFFF1EEF9);
        }

        // 2) 玻璃着色
        canvas.drawRect(0, 0, w, h, tintPaint);

        // 3) 对角高光
        if (sheenT > 0f && highlightPaint.getShader() != null) {
            sheenMatrix.setTranslate(-w * 0.30f + sheenT * w * 0.60f, 0);
            highlightPaint.getShader().setLocalMatrix(sheenMatrix);
        }
        canvas.drawRect(0, 0, w, h, highlightPaint);

        // 4) 底部内阴影，制造厚度
        canvas.drawRect(0, 0, w, h, depthPaint);

        canvas.restoreToCount(save);

        // 5) 1px 亮边
        canvas.drawPath(clipPath, edgePaint);
    }

    // ------------------------------------------------------------ 背景模糊

    /** 两趟 box blur 近似高斯，跑在缩小的背景图上。 */
    public static void blur(int[] pixels, int w, int h, int radius) {
        if (radius < 1) {
            return;
        }
        int[] tmp = new int[pixels.length];
        pass(pixels, tmp, w, h, radius, true);
        pass(tmp, pixels, w, h, radius, false);
    }

    private static void pass(int[] srcPix, int[] dstPix, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h;
        int lines = horizontal ? h : w;
        long[] sa = new long[len + 1];
        long[] sr = new long[len + 1];
        long[] sg = new long[len + 1];
        long[] sb = new long[len + 1];
        for (int line = 0; line < lines; line++) {
            sa[0] = 0; sr[0] = 0; sg[0] = 0; sb[0] = 0;
            for (int i = 0; i < len; i++) {
                int idx = horizontal ? line * w + i : i * w + line;
                int c = srcPix[idx];
                sa[i + 1] = sa[i] + ((c >>> 24) & 0xFF);
                sr[i + 1] = sr[i] + ((c >> 16) & 0xFF);
                sg[i + 1] = sg[i] + ((c >> 8) & 0xFF);
                sb[i + 1] = sb[i] + (c & 0xFF);
            }
            for (int i = 0; i < len; i++) {
                int lo = Math.max(0, i - r);
                int hi = Math.min(len - 1, i + r);
                int n = hi - lo + 1;
                int a = (int) ((sa[hi + 1] - sa[lo]) / n);
                int rr = (int) ((sr[hi + 1] - sr[lo]) / n);
                int gg = (int) ((sg[hi + 1] - sg[lo]) / n);
                int bb = (int) ((sb[hi + 1] - sb[lo]) / n);
                int idx = horizontal ? line * w + i : i * w + line;
                dstPix[idx] = (a << 24) | (rr << 16) | (gg << 8) | bb;
            }
        }
    }
}