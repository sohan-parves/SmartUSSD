package com.sohan.smartussd.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Loading indicator: three expanding ripple rings, a rotating
 * gradient arc and a softly pulsing centre dot.
 */
public class PulseLoaderView extends View {

    private static final int ACCENT = 0xFF22D3EE;
    private static final int ACCENT_DARK = 0xFF14B8A6;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private ValueAnimator spinAnimator;
    private ValueAnimator pulseAnimator;

    private float spin;   // 0..360
    private float pulse;  // 0..1

    public PulseLoaderView(Context context) {
        this(context, null);
    }

    public PulseLoaderView(Context context, AttributeSet attrs) {
        super(context, attrs);

        float density = getResources().getDisplayMetrics().density;

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2f * density);
        ringPaint.setColor(ACCENT);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(5f * density);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(ACCENT_DARK);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) * 0.30f;

        arcRect.set(cx - r, cy - r, cx + r, cy + r);

        arcPaint.setShader(new SweepGradient(
                cx, cy,
                new int[]{0x0022D3EE, ACCENT, ACCENT_DARK},
                new float[]{0f, 0.78f, 0.8f}
        ));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float maxR = Math.min(getWidth(), getHeight()) / 2f
                - ringPaint.getStrokeWidth();
        float minR = maxR * 0.35f;

        // Ripples
        for (int i = 0; i < 3; i++) {

            float t = (pulse + i / 3f) % 1f;
            float radius = minR + (maxR - minR) * t;

            ringPaint.setAlpha((int) (110 * (1f - t)));

            canvas.drawCircle(cx, cy, radius, ringPaint);
        }

        // Rotating arc
        canvas.save();
        canvas.rotate(spin, cx, cy);
        canvas.drawArc(arcRect, 0f, 288f, false, arcPaint);
        canvas.restore();

        // Centre dot
        float density = getResources().getDisplayMetrics().density;
        float dot = (4f + 2f * (float) Math.sin(pulse * 2 * Math.PI)) * density;

        canvas.drawCircle(cx, cy, dot, dotPaint);
    }

    private void startAnimators() {

        if (spinAnimator == null) {

            spinAnimator = ValueAnimator.ofFloat(0f, 360f);
            spinAnimator.setDuration(1100);
            spinAnimator.setInterpolator(new LinearInterpolator());
            spinAnimator.setRepeatCount(ValueAnimator.INFINITE);
            spinAnimator.addUpdateListener(a -> {
                spin = (float) a.getAnimatedValue();
                invalidate();
            });
        }

        if (pulseAnimator == null) {

            pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
            pulseAnimator.setDuration(2000);
            pulseAnimator.setInterpolator(new LinearInterpolator());
            pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnimator.addUpdateListener(a -> pulse = (float) a.getAnimatedValue());
        }

        if (!spinAnimator.isStarted()) {
            spinAnimator.start();
        }

        if (!pulseAnimator.isStarted()) {
            pulseAnimator.start();
        }
    }

    private void stopAnimators() {

        if (spinAnimator != null) {
            spinAnimator.cancel();
        }

        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
    }

    private void updateAnimators() {

        if (isAttachedToWindow() && getWindowVisibility() == VISIBLE
                && isShown()) {
            startAnimators();
        } else {
            stopAnimators();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimators();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimators();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateAnimators();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateAnimators();
    }
}
