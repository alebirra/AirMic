package com.airmic.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Thin, fully-rounded horizontal mic level meter: a dark track with a
 * red brand-gradient fill driven by the RMS of the captured PCM frames.
 * Fast attack / slow release smoothing keeps the motion fluid.
 */
public class LevelMeterView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float level; // smoothed 0..1

    public LevelMeterView(Context context) {
        super(context);
        init();
    }

    public LevelMeterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        trackPaint.setColor(0xFF2C2C2E);
    }

    /** Sets the instantaneous level (0..1); the fill eases toward it. */
    public void setLevel(float newLevel) {
        newLevel = Math.max(0f, Math.min(1f, newLevel));
        // Fast attack, slow release — reads naturally on a meter.
        level = newLevel > level ? newLevel : level * 0.85f;
        if (level < 0.01f) {
            level = 0f;
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // Brand red gradient, deep on the left to bright on the right.
        fillPaint.setShader(new LinearGradient(0, 0, w, 0,
                0xFFC6090D, 0xFFE53238, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float radius = getHeight() / 2f;
        rect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(rect, radius, radius, trackPaint);
        if (level > 0f) {
            rect.set(0, 0, Math.max(getWidth() * level, getHeight()), getHeight());
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
        }
    }
}
