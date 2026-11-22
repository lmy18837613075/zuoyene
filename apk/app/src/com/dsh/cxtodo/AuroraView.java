package com.dsh.cxtodo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * 极光背景：几层大半径径向渐变叠出来的流体色场。
 * 静止不动，给上层玻璃提供可模糊的内容。
 */
public class AuroraView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RadialGradient[] blobs;

    public AuroraView(Context c) {
        super(c);
        setLayerType(View.LAYER_TYPE_NONE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) {
            return;
        }
        float m = Math.max(w, h);
        blobs = new RadialGradient[]{
                blob(w, h, 0.14f, 0.06f, m * 0.70f, 0x998B6FE8),
                blob(w, h, 0.94f, 0.10f, m * 0.64f, 0x8C6FA8F5),
                blob(w, h, 0.80f, 0.40f, m * 0.56f, 0x80F27FB2),
                blob(w, h, 0.04f, 0.46f, m * 0.60f, 0x865FD3D8),
                blob(w, h, 0.52f, 0.86f, m * 0.68f, 0x7AB98BEB),
                blob(w, h, 0.16f, 0.96f, m * 0.48f, 0x66FFB27A)
        };
    }

    private RadialGradient blob(int w, int h, float cx, float cy, float radius, int color) {
        int clear = color & 0x00FFFFFF;
        return new RadialGradient(cx * w, cy * h, radius,
                new int[]{color, clear}, new float[]{0f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(0xFFF8F5FD);
        if (blobs == null) {
            return;
        }
        for (RadialGradient g : blobs) {
            paint.setShader(g);
            canvas.drawRect(0, 0, w, h, paint);
        }
        paint.setShader(null);
        // 一层薄雾，压住色块但保留色彩
        paint.setColor(0x14F8F5FD);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setColor(0xFFFFFFFF);
    }
}